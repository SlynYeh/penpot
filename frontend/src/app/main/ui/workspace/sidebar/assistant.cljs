;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.assistant
  "AI assistant panel for the workspace right sidebar.

  One session per tab activation: on mount (and again on every retry) a
  fresh postMessage connection (`app.util.embed.assistant`) runs the
  handshake and the `getInitParams` request against the embedding host
  page, then the host provided SDK script is injected and initialized
  against the `penpot-assistant-mount` div.
  Leaving the tab (or hitting retry) destroys the SDK, removes the
  script node, clears `window.X_Assistant` and closes the connection.
  Since v2 the last session of the (profile, file) pair is remembered
  in localStorage: re-entering the tab restores it through
  changeSessionId instead of always starting a new one.
  Right after the SDK is initialized, a prompt and/or attachments
  received on the embed credentials handshake (app.util.embed)
  auto-open a chat; the values are polled every 500ms, at most 10
  checks, because the handshake may still be in flight."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.logging :as log]
   [app.config :as cf]
   [app.main.refs :as refs]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [app.main.ui.hooks :as hooks]
   [app.util.dom :as dom]
   [app.util.embed :as embed]
   [app.util.embed.assistant :as assistant]
   [app.util.globals :as ug]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.object :as obj]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

(def ^:private sdk-global-name "X_Assistant")
(def ^:private sdk-script-selector "[data-penpot-assistant-sdk]")
(def ^:private sdk-script-attribute "data-penpot-assistant-sdk")
(def ^:private mount-dom-id "penpot-assistant-mount")

;; v2.1: i18n key of each history age bucket, in the display order of
;; assistant/history-group-order.
(def ^:private history-group->i18n-key
  {:today         "workspace.options.assistant.history.group.today"
   :last-7-days   "workspace.options.assistant.history.group.last-7-days"
   :last-30-days  "workspace.options.assistant.history.group.last-30-days"})

(def ^:private error->i18n-key
  {:handshake-timeout "workspace.options.assistant.error.handshake"
   :params-timeout    "workspace.options.assistant.error.params"
   :invalid-params    "workspace.options.assistant.error.params"
   :sdk-load          "workspace.options.assistant.error.sdk"
   :sdk-global        "workspace.options.assistant.error.sdk"
   :sdk-init          "workspace.options.assistant.error.sdk"})

(defn- now-ts
  []
  (.now js/Date))

(defn- event-payload
  "Builds the payload of an event forwarded to the host page: the
  current timestamp with the data received from the SDK callback
  spread over it."
  [data]
  (if (object? data)
    (obj/merge #js {"timestamp" (now-ts)} data)
    #js {"timestamp" (now-ts)}))

(defn- adopt-sdk-result
  "Adopts the return value of a host SDK method. The SDK is implemented
  by an external team and may run init/render asynchronously, so a
  thenable return value is taken over (a rejection must reach the
  session state machine instead of dying as an unhandled rejection);
  anything else resolves right away."
  [^js value]
  (if (and (some? value) (fn? (unchecked-get value "then")))
    (p/create
     (fn [resolve reject]
       (try
         (.then value #(resolve %) #(reject %))
         (catch :default cause
           (reject cause)))))
    (p/resolved nil)))

(defn- fresh-sdk-tools
  "Re-extracts the v2 tools surface from the CURRENT SDK global at
  call time. Every SDK invocation goes through this instead of the
  ready-time snapshot in `sdk-tools*`: the SDK script may be
  re-injected and `window.X_Assistant` replaced while the session
  stays ready, and methods bound to a previous script generation must
  not keep being called. Nil when the global is gone (a torn-down
  session) or lacks the surface; callers degrade exactly as they did
  for a nil snapshot member."
  []
  (assistant/extract-sdk-tools (unchecked-get ug/global sdk-global-name)))

(mf/defc assistant-tab*
  [{:keys [file-id page-id]}]
  (let [state*   (mf/use-state :handshaking)
        state    (deref state*)
        attempt* (mf/use-state 0)
        attempt  (deref attempt*)

        ;; v2: id of the session currently shown in the chat area (nil =
        ;; a fresh session whose first message has not produced an id
        ;; yet). Mirrors the localStorage memory of this (profile,
        ;; file) pair.
        session-id* (mf/use-state nil)

        ;; v2: liveness flag only — set when the session goes ready and
        ;; cleared when the setup effect restarts. SDK methods are NOT
        ;; taken from here: every call re-extracts them from the
        ;; current global (fresh-sdk-tools).
        sdk-tools* (mf/use-state nil)

        profile    (mf/deref refs/profile)
        profile-id (some-> profile :id str)

        profile-id-ref (hooks/use-update-ref profile-id)

        ;; The SDK callbacks are created once per session; they read the
        ;; ids through these refs so every message carries the page and
        ;; file that are current when it is sent.
        page-id-ref (hooks/use-update-ref page-id)
        file-id-ref (hooks/use-update-ref file-id)

        ;; v2: which of the two mutually exclusive views the tab content
        ;; shows — :chat (SDK mount + action bar) or :history.
        view* (mf/use-state :chat)
        view  (deref view*)

        ;; v2: history list fetch state — nil (never fetched), :loading,
        ;; :error or [:ok sessions-js-array]. history-attempt bumps on
        ;; retry; the fetch effect reruns on it.
        history*         (mf/use-state nil)
        history-attempt* (mf/use-state 0)

        on-retry
        (mf/use-fn
         (fn []
           (swap! attempt* inc)))

        on-back-to-chat!
        (mf/use-fn (fn [] (reset! view* :chat)))

        on-history-head-keydown!
        (mf/use-fn
         (fn [event]
           (when (or (= (.-key event) "Enter") (= (.-key event) " "))
             (dom/prevent-default event)
             (on-back-to-chat!))))

        on-open-history!
        (mf/use-fn (fn [] (reset! view* :history)))

        on-history-retry!
        (mf/use-fn (fn [] (swap! history-attempt* inc)))

        on-new-session!
        (mf/use-fn
         (fn []
           (if-let [change! (:change-session-id! (fresh-sdk-tools))]
             (-> (try (adopt-sdk-result (change! ""))
                      (catch :default cause (p/rejected cause)))
                 (p/then (fn [_]
                           (assistant/clear-saved-session-id!
                            (mf/ref-val profile-id-ref) (mf/ref-val file-id-ref))
                           (reset! session-id* nil)))
                 (p/catch (fn [cause]
                            (log/wrn :hint "assistant: failed to start a new session"
                                     :cause cause))))
             (log/wrn :hint "assistant: no SDK changeSessionId available for a new session"))))

        on-select-session!
        (mf/use-fn
         (fn [session-id]
           (if-let [change! (:change-session-id! (fresh-sdk-tools))]
             (-> (try (adopt-sdk-result (change! session-id))
                      (catch :default cause (p/rejected cause)))
                 (p/then (fn [_]
                           (assistant/save-session-id!
                            (mf/ref-val profile-id-ref) (mf/ref-val file-id-ref) session-id)
                           (reset! session-id* session-id)
                           (reset! view* :chat)))
                 (p/catch (fn [cause]
                            (log/wrn :hint "assistant: failed to switch session"
                                     :cause cause))))
             (log/wrn :hint "assistant: no SDK changeSessionId available to switch session"))))]

    (mf/use-effect
     (mf/deps attempt)
     (fn []
       (let [cancelled? (volatile! false)
             ;; Cancel fn of the prompt/attachments poll started in
             ;; ready!, invoked by the unmount cleanup below.
             auto-open-cancel (volatile! nil)
             {:keys [handshake! request! post-event! close!]}
             (assistant/create-conn {})

             advance!
             (fn [new-state]
               (when-not @cancelled?
                 (reset! state* new-state)))

             fail!
             (fn [reason]
               (log/wrn :hint "assistant: session setup failed" :reason reason)
               (advance! [:error reason]))

             enrich-message
             (fn [message]
               (try
                 (when (object? message)
                   (obj/set! message "envs"
                             (assistant/build-message-envs
                              (mf/ref-val page-id-ref)
                              (mf/ref-val file-id-ref)
                              (embed/doc-content))))
                 (catch :default cause
                   ;; A frozen/sealed SDK message object cannot be
                   ;; enriched; hand it back untouched instead of
                   ;; throwing inside the SDK's own callback.
                   (log/wrn :hint "assistant: unable to enrich the SDK message" :cause cause)))
               message)

             forward!
             (fn [event-name]
               (fn [data]
                 (post-event! event-name (event-payload data))))

             sdk-forward-handlers
             (into {}
                   (comp (remove #(= "ai:createNewSession" %))
                         (map (fn [event-name] [event-name (forward! event-name)])))
                   assistant/sdk-forward-events)

             create-session-handler
             (fn [data]
               (when-not @cancelled?
                 (let [session-id (when (object? data) (obj/get data "sessionId"))]
                   (when (and (string? session-id) (pos? (count session-id)))
                     (assistant/save-session-id!
                      (mf/ref-val profile-id-ref) (mf/ref-val file-id-ref) session-id)
                     (reset! session-id* session-id)))
                 ;; one-way host notification, after the local
                 ;; persistence, whether or not the event carried a
                 ;; usable session id
                 (post-event! "ai:createNewSession" (event-payload data))))

             start-auto-open!
             (fn [open-chat!]
               (vreset! auto-open-cancel
                        (assistant/start-auto-open!
                         {:values! (fn []
                                     [(embed/prompt) (embed/attachments)])
                          :open-chat! (fn [prompt options]
                                        ;; Never log the values
                                        ;; themselves: the prompt may be
                                        ;; sensitive host content.
                                        (log/dbg :hint "assistant: auto opening a chat"
                                                 :attachments? (some? (obj/get options "attachments")))
                                        (try
                                          (open-chat! prompt options)
                                          (catch :default cause
                                            (log/wrn :hint "assistant: auto open chat failed"
                                                     :cause cause))))
                          :schedule! js/setTimeout
                          :cancel!  js/clearTimeout})))

             ready!
             (fn [^js sdk tools]
               (when-not @cancelled?
                 (reset! sdk-tools* tools)
                 (when-let [on (when (object? sdk) (obj/get sdk "on"))]
                   (when (fn? on)
                     (doseq [[event-name handler] sdk-forward-handlers]
                       (try
                         (on event-name handler)
                         (catch :default cause
                           (log/wrn :hint "assistant: could not subscribe to the SDK event"
                                    :event event-name :cause cause))))
                     (try
                       (on "ai:createNewSession" create-session-handler)
                       (catch :default cause
                         (log/wrn :hint "assistant: could not subscribe to the SDK session events"
                                  :cause cause)))))
                 ;; The host may have handed over a prompt/attachments on
                 ;; the embed handshake before or while this tab was
                 ;; initializing; when present they auto-open a chat.
                 (when-let [open-chat! (:open-chat! tools)]
                   (start-auto-open! open-chat!))
                 ;; Only now, with init, render and (when applicable) the
                 ;; session restore settled, is the assistant really up.
                 (post-event! "initialized" #js {"timestamp" (now-ts)})
                 (advance! :ready)))

             restore-and-ready!
             (fn [^js sdk]
               (let [saved-id (assistant/load-saved-session-id
                               (mf/ref-val profile-id-ref)
                               (mf/ref-val file-id-ref))
                     tools   (assistant/extract-sdk-tools sdk)
                     change! (:change-session-id! (fresh-sdk-tools))]
                 (when-not @cancelled?
                   (reset! session-id* saved-id))
                 (if (or (nil? saved-id) (nil? change!))
                   (ready! sdk tools)
                   (-> (try (adopt-sdk-result (change! saved-id))
                            (catch :default cause (p/rejected cause)))
                       (p/catch
                        (fn [cause]
                          (log/wrn :hint "assistant: could not restore the last session, starting a new one"
                                   :cause cause)
                          (assistant/clear-saved-session-id!
                           (mf/ref-val profile-id-ref) (mf/ref-val file-id-ref))
                          (when-not @cancelled?
                            (reset! session-id* nil))
                          (if-let [fallback! (:change-session-id! (fresh-sdk-tools))]
                            (try (fallback! "")
                                 (catch :default cause
                                   (log/wrn :hint "assistant: could not arm a new session after a failed restore"
                                            :cause cause)))
                            (log/wrn :hint "assistant: no SDK changeSessionId available after a failed restore"))
                          ;; fall through: ready! arms the UI on both the
                          ;; restored and the fallback path, exactly once
                          nil))
                       (p/then (fn [_] (ready! sdk tools)))))))

             init-and-render!
             (fn [^js sdk params]
               (try
                 (let [config (assistant/build-init-config
                               params
                               {:enrich-message enrich-message
                                :forward!       forward!})]
                   (-> (adopt-sdk-result (.init sdk config))
                       (p/then (fn [_]
                                 (adopt-sdk-result (.render sdk mount-dom-id))))
                       (p/then (fn [_]
                                 (restore-and-ready! sdk)))
                       (p/catch
                        (fn [cause]
                          (log/err :hint "assistant: SDK initialization failed" :cause cause)
                          (fail! :sdk-init)))))
                 (catch :default cause
                   (log/err :hint "assistant: SDK initialization failed" :cause cause)
                   (fail! :sdk-init))))

             check-sdk!
             (fn [params]
               (advance! :initializing)
               (let [^js sdk (unchecked-get ug/global sdk-global-name)]
                 (if (and (object? sdk)
                          (fn? (obj/get sdk "init"))
                          (fn? (obj/get sdk "render")))
                   (init-and-render! sdk params)
                   (fail! :sdk-global))))

             load-sdk!
             (fn [params]
               (advance! :loading-sdk)
               ;; Drop any stale node left over from an older session
               ;; before injecting this one.
               (dom/remove! (dom/query sdk-script-selector))
               (let [script (dom/create-element "script")
                     loaded (p/create
                             (fn [resolve]
                               (set! (.-onload script) (fn [] (resolve :loaded)))
                               (set! (.-onerror script) (fn [_event] (resolve :failed)))))]
                 (dom/set-attribute! script "src" cf/x-front-sdk)
                 (dom/set-attribute! script sdk-script-attribute "")
                 (dom/append-child! (.-head ug/document) script)
                 (p/then loaded
                         (fn [status]
                           (when-not @cancelled?
                             (if (= status :failed)
                               (fail! :sdk-load)
                               (check-sdk! params)))))))

             on-params!
             (fn [{:keys [ok error payload]}]
               (when-not @cancelled?
                 (cond
                   (= error :closed)
                   nil

                   (not ok)
                   (fail! (if (= error :timeout) :params-timeout :invalid-params))

                   (not (assistant/valid-init-params? payload))
                   (fail! :invalid-params)

                   :else
                   (load-sdk! payload))))

             on-handshake!
             (fn [result]
               (when-not @cancelled?
                 (if (= result :ready)
                   (do
                     (advance! :requesting)
                     (p/then (request! "getInitParams" #js {"timestamp" (now-ts)})
                             on-params!))
                   (fail! :handshake-timeout))))]

         (reset! sdk-tools* nil)
         (reset! session-id* nil)
         (advance! :handshaking)
         (-> (handshake!)
             (p/then on-handshake!)
             (p/catch
              (fn [cause]
                (log/err :hint "assistant: unexpected error during session setup" :cause cause)
                (fail! :sdk-init))))

         (fn []
           (vreset! cancelled? true)
           (when-let [cancel! @auto-open-cancel]
             (vreset! auto-open-cancel nil)
             (cancel!))
           ;; Fixed order: stop the SDK first, so its callbacks cannot
           ;; reach an already closed connection; then the script node,
           ;; then the global, then the connection itself. Every step is
           ;; nil guarded and the whole cleanup is idempotent.
           (let [sdk (unchecked-get ug/global sdk-global-name)]
             (when (object? sdk)
               (let [off (obj/get sdk "off")]
                 (when (fn? off)
                   (try
                     (doseq [[event-name handler] sdk-forward-handlers]
                       (off event-name handler))
                     (off "ai:createNewSession" create-session-handler)
                     (catch :default _ nil))))
               (try
                 (let [destroy (obj/get sdk "destroy")]
                   (when (fn? destroy)
                     (destroy)))
                 (catch :default cause
                   (log/wrn :hint "assistant: SDK destroy failed" :cause cause)))))
           (dom/remove! (dom/query sdk-script-selector))
           (unchecked-set ug/global sdk-global-name nil)
           (close!)))))

    ;; v2: every entry into the history view fetches the list fresh
    ;; (spec: no caching); retry re-fetches bump history-attempt.
    (mf/use-effect
     (mf/deps view attempt (deref history-attempt*))
     (fn []
       (when (= :history view)
         (let [cancelled? (volatile! false)]
           (reset! history* :loading)
           (let [get-session-list! (:get-session-list! (fresh-sdk-tools))]
             (if (nil? get-session-list!)
               (do (log/wrn :hint "assistant: no SDK getSessionList available")
                   (reset! history* :error))
               (-> (try (adopt-sdk-result (get-session-list!))
                        (catch :default cause (p/rejected cause)))
                   (p/then (fn [^js result]
                             (when-not @cancelled?
                               (let [sessions (when (object? result)
                                                (obj/get result "sessions"))]
                                 (reset! history* [:ok (when (array? sessions) sessions)])))))
                   (p/catch (fn [cause]
                              (when-not @cancelled?
                                (log/wrn :hint "assistant: failed to load the session history"
                                         :cause cause)
                                (reset! history* :error)))))))
           (fn []
             (vreset! cancelled? true))))))

    (let [[_ reason :as error] (when (vector? state) state)
          history  (deref history*)
          sessions (when (vector? history) (second history))
          ;; v2.1: sessions grouped into the today / last 7 days / last
          ;; 30 days buckets; older ones are already dropped here, so
          ;; an all-old list renders the empty state.
          groups    (assistant/group-sessions-by-age sessions)]
      [:div {:class (stl/css :assistant-tab)}
       (if (= :history view)
         ;; history header row replaces the action bar entirely. The
         ;; back arrow is a hand-rolled svg because the spec asks for a
         ;; 19px glyph and icon* only renders the fixed s/m/l sizes.
         [:div {:class (stl/css :history-head)
                :role "button"
                :tab-index "0"
                :on-click on-back-to-chat!
                :on-key-down on-history-head-keydown!}
          [:svg {:class (stl/css :history-back-icon)
                 :width 19
                 :height 19
                 :aria-hidden true}
           [:use {:href "#icon-arrow-left"
                  :width 19
                  :height 19}]]
          [:span {:class (stl/css :history-head-label)}
           (tr "workspace.options.assistant.history")]]
         (when (and (= :ready state) (some? (deref sdk-tools*)))
           [:div {:class (stl/css :action-bar)}
            [:button {:type "button"
                      :class (stl/css :action-btn)
                      :on-click on-new-session!}
             [:> icon* {:icon-id i/session-new :size "m"}]
             (tr "workspace.options.assistant.new-session")]
            [:button {:type "button"
                      :class (stl/css :action-btn)
                      :on-click on-open-history!}
             [:> icon* {:icon-id i/session-history :size "m"}]
             (tr "workspace.options.assistant.history")]]))

       ;; The SDK mount div is NEVER conditionally rendered: the host
       ;; SDK owns its DOM and a React remount would destroy it. It is
       ;; only hidden while the history view is on. The hide is an
       ;; inline display:none instead of a css-module class so it cannot
       ;; be defeated by a stale or desynchronized css bundle — it
       ;; travels with the JS itself (verified broken in the real
       ;; environment as a class: the js carried the class while the
       ;; served css predated the rule).
       [:div {:id mount-dom-id
              :class (stl/css :mount)
              :style {:display (when (= :history view) "none")}}]

       (when (= :history view)
         (cond
           (or (nil? history) (= :loading history))
           [:div {:class (stl/css :history-state)}
            [:span {:class (stl/css :spinner)}]]

           (= :error history)
           [:div {:class (stl/css :history-state)}
            [:div {:class (stl/css :status-text)}
             (tr "workspace.options.assistant.history.error")]
            [:> button* {:variant "secondary"
                         :on-click on-history-retry!}
             (tr "labels.retry")]]

           (or (nil? sessions) (zero? (alength sessions)) (empty? groups))
           [:div {:class (stl/css :history-state)}
            [:div {:class (stl/css :status-text)}
             (tr "workspace.options.assistant.history.empty")]]

           :else
           [:div {:class (stl/css :history-list)}
            (for [{:keys [key sessions]} groups]
              [:div {:key (name key)
                     :class (stl/css :history-group)}
               [:div {:class (stl/css :history-group-title)}
                (tr (get history-group->i18n-key key))]
               (for [^js session sessions
                     :when (string? (obj/get session "uid"))]
                 (let [uid   (obj/get session "uid")
                       title (obj/get session "title")]
                   [:div {:key uid
                          :class (stl/css :history-item)
                          :on-click #(on-select-session! uid)}
                    [:span {:class (stl/css :history-item-title)}
                     (if (and (string? title) (pos? (count title)))
                       title
                       (tr "workspace.options.assistant.new-session"))]]))])]))

       (if error
         [:div {:class (stl/css :status)}
          [:div {:class (stl/css :status-text)}
           (tr (get error->i18n-key reason "workspace.options.assistant.error.sdk"))]
          [:> button* {:variant "secondary"
                       :on-click on-retry}
           (tr "labels.retry")]]

         (when (not= :ready state)
           [:div {:class (stl/css :status)}
            [:span {:class (stl/css :spinner)}]
            [:div {:class (stl/css :status-text)}
             (tr "workspace.options.assistant.loading")]]))])))
