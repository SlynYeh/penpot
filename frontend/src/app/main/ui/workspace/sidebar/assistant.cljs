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
  against the `penpot-assistant-mount` div. Leaving the tab (or hitting
  retry) destroys the SDK, removes the script node, clears
  `window.X_Assistant` and closes the connection, so every entry into
  the tab starts a brand new assistant session."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.logging :as log]
   [app.config :as cf]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.hooks :as hooks]
   [app.util.dom :as dom]
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

(mf/defc assistant-tab*
  [{:keys [file-id page-id]}]
  (let [state*   (mf/use-state :handshaking)
        state    (deref state*)
        attempt* (mf/use-state 0)
        attempt  (deref attempt*)

        ;; The SDK callbacks are created once per session; they read the
        ;; ids through these refs so every message carries the page and
        ;; file that are current when it is sent.
        page-id-ref (hooks/use-update-ref page-id)
        file-id-ref (hooks/use-update-ref file-id)

        on-retry
        (mf/use-fn
         (fn []
           (swap! attempt* inc)))]

    (mf/use-effect
     (mf/deps attempt)
     (fn []
       (let [cancelled? (volatile! false)
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
                             #js {"PAGE_ID"  (dm/str (mf/ref-val page-id-ref))
                                  "FILE_ID" (dm/str (mf/ref-val file-id-ref))}))
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
                                 ;; Only now, with both init and render
                                 ;; settled, is the assistant really up.
                                 (post-event! "initialized" #js {"timestamp" (now-ts)})
                                 (advance! :ready)))
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

         (advance! :handshaking)
         (-> (handshake!)
             (p/then on-handshake!)
             (p/catch
              (fn [cause]
                (log/err :hint "assistant: unexpected error during session setup" :cause cause)
                (fail! :sdk-init))))

         (fn []
           (vreset! cancelled? true)
           ;; Fixed order: stop the SDK first, so its callbacks cannot
           ;; reach an already closed connection; then the script node,
           ;; then the global, then the connection itself. Every step is
           ;; nil guarded and the whole cleanup is idempotent.
           (let [sdk (unchecked-get ug/global sdk-global-name)]
             (when (object? sdk)
               (try
                 (let [destroy (obj/get sdk "destroy")]
                   (when (fn? destroy)
                     (destroy)))
                 (catch :default cause
                   (log/wrn :hint "assistant: SDK destroy failed" :cause cause)))))
           (dom/remove! (dom/query sdk-script-selector))
           (unchecked-set ug/global sdk-global-name nil)
           (close!)))))

    (let [[_ reason :as error] (when (vector? state) state)]
      [:div {:class (stl/css :assistant-tab)}
       [:div {:id mount-dom-id
              :class (stl/css :mount)}]

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
