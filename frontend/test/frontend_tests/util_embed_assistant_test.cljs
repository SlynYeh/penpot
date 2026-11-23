;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.util-embed-assistant-test
  (:require
   [app.util.embed.assistant :as assistant]
   [app.util.object :as obj]
   [cljs.test :as t :include-macros true]
   [promesa.core :as p]))

(def ^:private parent-origin "https://portal.example.com")

(defn- fake-io
  "Injectable postMessage/listener pair: records every posted message
  together with the target origin it was addressed to, and captures the
  installed message listener so tests can play the parent window."
  []
  (let [posted  (atom [])
        handler (atom nil)
        removed (atom 0)]
    {:posted  posted
     :removed removed
     :io      {:post-message!    (fn [message origin]
                                   (swap! posted conj {:message message :origin origin}))
               :add-listener!    (fn [h] (reset! handler h))
               :remove-listener! (fn [_] (swap! removed inc))}
     :emit!   (fn [parent data]
                (when-let [h @handler]
                  (h #js {"source" parent
                          "origin" parent-origin
                          "data"   data})))}))

(defn- conn-opts
  [io parent]
  (merge (:io io)
         {:parent parent
          :origin-allowed? (constantly true)
          :target-origin parent-origin
          ;; real timers, small values: fast enough for the suite,
          ;; slow enough to tell resends apart from the initial post.
          :resend-interval-ms 20
          :handshake-timeout-ms 400
          :request-timeout-ms 400}))

(defn- response-data
  [event-name payload]
  #js {"scope" "penpot/assistant"
       "type" "AI_ASSISTANT_RESPONSE"
       "eventName" event-name
       "payload" payload})

(defn- posted-req-id
  [io]
  (unchecked-get (:message (first @(:posted io))) "reqId"))

(defn- init-params
  []
  #js {"idToken" "token"
       "appName" "app"
       "agentId" "agent"
       "skillName" "skill"})

;; --- pure functions ---------------------------------------------------

(t/deftest valid-init-params-accepts-complete-params
  (t/is (true? (assistant/valid-init-params? (init-params))))
  ;; logoUrl is optional; a well formed one must not invalidate the rest.
  (t/is (true? (assistant/valid-init-params?
                (obj/set! (init-params) "logoUrl" "https://portal.example.com/logo.png")))))

(t/deftest valid-init-params-rejects-broken-required-fields
  (doseq [key ["idToken" "appName" "agentId" "skillName"]]
    (t/is (false? (assistant/valid-init-params? (obj/unset! (init-params) key)))
          (str "missing " key))
    (t/is (false? (assistant/valid-init-params? (obj/set! (init-params) key "")))
          (str "empty " key))
    (t/is (false? (assistant/valid-init-params? (obj/set! (init-params) key "   ")))
          (str "blank " key))
    (t/is (false? (assistant/valid-init-params? (obj/set! (init-params) key 42)))
          (str "non-string " key)))
  (t/is (false? (assistant/valid-init-params? nil)))
  (t/is (false? (assistant/valid-init-params? "idToken"))))

(t/deftest valid-init-params-rejects-broken-optional-logo
  (t/is (false? (assistant/valid-init-params? (obj/set! (init-params) "logoUrl" "  "))))
  (t/is (false? (assistant/valid-init-params? (obj/set! (init-params) "logoUrl" 42)))))

(t/deftest build-message-envs-includes-page-and-file
  (let [envs (assistant/build-message-envs "page-1" "file-1" nil)]
    (t/is (= "page-1" (obj/get envs "PAGE_ID")))
    (t/is (= "file-1" (obj/get envs "FILE_ID")))
    (t/is (undefined? (obj/get envs "DOC_CONTENT")))))

(t/deftest build-message-envs-includes-doc-content-when-string
  (let [envs (assistant/build-message-envs "page-1" "file-1" "the-doc")]
    (t/is (= "the-doc" (obj/get envs "DOC_CONTENT"))))
  (let [envs (assistant/build-message-envs "page-1" "file-1" "")]
    (t/is (= "" (obj/get envs "DOC_CONTENT")))))

;; --- auto-open ---------------------------------------------------------

(t/deftest open-chat-request-with-prompt-or-attachments
  ;; a non-blank prompt alone justifies a chat
  (t/is (true? (assistant/open-chat-request? "hello" nil)))
  ;; so does a non-empty attachments vector, with or without a prompt
  (t/is (true? (assistant/open-chat-request? "" [{:name "a" :url "b"}])))
  (t/is (true? (assistant/open-chat-request? nil [{:name "a" :url "b"}])))
  ;; nothing (or effectively nothing) provided: no chat
  (t/is (false? (assistant/open-chat-request? nil nil)))
  (t/is (false? (assistant/open-chat-request? nil [])))
  (t/is (false? (assistant/open-chat-request? "" nil)))
  (t/is (false? (assistant/open-chat-request? "   " []))))

(t/deftest open-chat-prompt-defaults-when-absent
  (t/is (= "hello" (assistant/open-chat-prompt "hello")))
  (t/is (= assistant/auto-open-default-prompt
           (assistant/open-chat-prompt nil)))
  (t/is (= assistant/auto-open-default-prompt
           (assistant/open-chat-prompt "")))
  (t/is (= assistant/auto-open-default-prompt
           (assistant/open-chat-prompt "   ")))
  (t/is (= assistant/auto-open-default-prompt
           (assistant/open-chat-prompt 42))))

(t/deftest build-open-chat-options-triggers-a-fresh-answer
  (let [options (assistant/build-open-chat-options nil)]
    (t/is (true? (obj/get options "triggerAnswer")))
    (t/is (true? (obj/get options "newSession")))
    (t/is (undefined? (obj/get options "attachments"))))
  ;; an empty vector is like "not provided" for the options object
  (let [options (assistant/build-open-chat-options [])]
    (t/is (undefined? (obj/get options "attachments"))))
  (let [options (assistant/build-open-chat-options [{:name "a" :url "b"}])]
    (t/is (true? (array? (obj/get options "attachments"))))
    (t/is (= "a" (obj/get (first (obj/get options "attachments")) "name")))
    (t/is (= "b" (obj/get (first (obj/get options "attachments")) "url")))))

(defn- fake-polling
  "A polling rig whose scheduler records (fn ms) pairs instead of
  firing real timers; the tests drive the ticks manually."
  []
  (let [scheduled (atom [])
        schedules (atom 0)
        opened    (atom [])
        cancelled (atom 0)]
    {:scheduled scheduled
     :schedules schedules
     :opened    opened
     :cancelled cancelled
     :schedule! (fn [f ms] (swap! schedules inc) (swap! scheduled conj [f ms]) :timer)
     :cancel!   (fn [_timer] (swap! cancelled inc))
     :open-chat! (fn [prompt options] (swap! opened conj [prompt options]))}))

(t/deftest start-auto-open-opens-immediately-when-values-present
  (let [io      (fake-polling)
        cancel! (assistant/start-auto-open!
                 {:values!    (fn [] ["设计一个登录页" nil])
                  :open-chat! (:open-chat! io)
                  :schedule!  (:schedule! io)
                  :cancel!    (:cancel! io)})]
    (t/is (= 1 (count @(:opened io))))
    (let [[prompt options] (first @(:opened io))]
      (t/is (= "设计一个登录页" prompt))
      (t/is (true? (obj/get options "triggerAnswer")))
      (t/is (true? (obj/get options "newSession"))))
    (t/is (empty? @(:scheduled io)) "no poll is scheduled after opening")
    (cancel!)))

(t/deftest start-auto-open-polls-until-values-arrive
  (let [io     (fake-polling)
        values (atom [nil nil])
        cancel! (assistant/start-auto-open!
                 {:values!    (fn [] @values)
                  :open-chat! (:open-chat! io)
                  :schedule!  (:schedule! io)
                  :cancel!    (:cancel! io)})]
    (t/is (empty? @(:opened io)))
    (t/is (= 1 (count @(:scheduled io))) "the first check schedules a retry")
    ;; nothing yet on the second check either
    (let [[tick-2 _] (first @(:scheduled io))]
      (tick-2)
      (t/is (= 2 (count @(:scheduled io))))
      (t/is (empty? @(:opened io))))
    ;; the third check sees the attachments and opens the chat with the
    ;; default prompt
    (reset! values [nil [{:name "spec.txt" :url "https://example.com/spec.txt"}]])
    (let [[tick-3 _] (second @(:scheduled io))]
      (tick-3)
      (t/is (= 1 (count @(:opened io))))
      (let [[prompt options] (first @(:opened io))]
        (t/is (= assistant/auto-open-default-prompt prompt))
        (t/is (= "spec.txt"
                 (obj/get (first (obj/get options "attachments")) "name"))))
      (t/is (= 2 (count @(:scheduled io))) "no further poll after opening"))
    (cancel!)))

(t/deftest start-auto-open-gives-up-after-max-attempts
  (let [io      (fake-polling)
        cancel! (assistant/start-auto-open!
                 {:values!      (fn [] [nil nil])
                  :open-chat!   (:open-chat! io)
                  :schedule!    (:schedule! io)
                  :cancel!      (:cancel! io)
                  :max-attempts 3})]
    ;; drive every scheduled tick until nothing is scheduled anymore
    (loop []
      (when (seq @(:scheduled io))
        (let [[tick _] (last @(:scheduled io))]
          (swap! (:scheduled io) pop)
          (tick)
          (recur))))
    (t/is (empty? @(:scheduled io)) "the poller stops scheduling")
    (t/is (empty? @(:opened io)) "no chat was opened")
    (t/is (= 2 @(:schedules io)) "3 checks total -> 2 re-schedules")
    (cancel!)))

(t/deftest start-auto-open-cancel-clears-the-pending-poll
  (let [io      (fake-polling)
        cancel! (assistant/start-auto-open!
                 {:values!    (fn [] [nil nil])
                  :open-chat! (:open-chat! io)
                  :schedule!  (:schedule! io)
                  :cancel!    (:cancel! io)})]
    (cancel!)
    (t/is (= 1 @(:cancelled io)) "the pending timer was cleared")
    ;; a late tick can neither open a chat nor reschedule
    (let [[tick _] (first @(:scheduled io))]
      (tick)
      (t/is (empty? @(:opened io)))
      (t/is (= 1 (count @(:scheduled io)))))))

(defn- recording-hooks
  "Wiring closures for build-init-config that record what the built
  config actually invokes: forward! produces the per-event callbacks,
  enrich-message counts the messages routed through it."
  []
  (let [events   (atom [])
        enriched (atom 0)]
    {:events   events
     :enriched enriched
     :hooks    {:enrich-message (fn [message] (swap! enriched inc) message)
                :forward!       (fn [event-name]
                                  (fn [data] (swap! events conj [event-name data])))}}))

(t/deftest build-init-config-passes-host-params-through
  ;; Anything the host returns in params must reach the init config
  ;; untouched: unknown keys (the point of the pass-through), same-name
  ;; keys and the raw alias keys alongside their derived counterparts.
  (let [prompts #js [#js {"id" "p1"}]
        params  (-> (init-params)
                    (obj/set! "theme" "dark")
                    (obj/set! "prompts" prompts)
                    (obj/set! "logoUrl" "https://portal.example.com/logo.png"))
        config  (assistant/build-init-config params (:hooks (recording-hooks)))]
    (t/is (= "dark" (obj/get config "theme")))
    (t/is (identical? prompts (obj/get config "prompts")))
    (t/is (= "app" (obj/get config "appName")))
    (t/is (= "agent" (obj/get config "agentId")))
    (t/is (= "token" (obj/get config "idToken")))
    (t/is (= "skill" (obj/get config "skillName")))
    (t/is (= "https://portal.example.com/logo.png" (obj/get config "logoUrl")))))

(t/deftest build-init-config-overlays-the-local-wiring
  (let [{:keys [events enriched hooks]} (recording-hooks)
        config (assistant/build-init-config (init-params) hooks)
        msg    #js {"messageId" "m1"}
        rating #js {"score" 5}]
    (t/is (= "domMount" (obj/get config "layoutType")))
    (t/is (= "skill" (obj/get config "defaultSkillName")))
    (t/is (fn? (obj/get config "getIdToken")))
    ;; the host SDK docs spell the hook two ways; both carry the same fn
    (t/is (identical? (obj/get config "beforeSendMessage")
                      (obj/get config "beforeSendMesssage")))
    ((obj/get config "beforeSendMessage") msg)
    ((obj/get config "afterSendMessage") msg)
    ((obj/get config "onMessageFinish") msg)
    (let [satisfaction (obj/get config "satisfactionRating")]
      (t/is (true? (obj/get satisfaction "enabled")))
      ((obj/get satisfaction "onComplete") rating))
    (t/is (= 1 @enriched))
    (t/is (= [["afterSendMessage" msg]
              ["onMessageFinish" msg]
              ["onSatisfactionRatingComplete" rating]]
             @events))))

(t/deftest build-init-config-get-id-token-resolves-the-host-token
  (t/async done
    (let [config (assistant/build-init-config (init-params) (:hooks (recording-hooks)))]
      (p/then ((obj/get config "getIdToken"))
              (fn [value]
                (t/is (= "token" value))
                (done))))))

(t/deftest build-init-config-derives-appimage-from-logo-url
  ;; logoUrl wins over a host supplied appImage (documented precedence)
  (let [config (assistant/build-init-config
                (-> (init-params)
                    (obj/set! "logoUrl" "https://portal.example.com/logo.png")
                    (obj/set! "appImage" #js {"src" "https://portal.example.com/other.png"}))
                (:hooks (recording-hooks)))
        app-image (obj/get config "appImage")]
    (t/is (= "https://portal.example.com/logo.png" (obj/get app-image "src")))
    (t/is (= "ai-penpot-icon" (obj/get app-image "className")))
    (t/is (true? (obj/get app-image "hideClose"))))
  ;; without logoUrl nothing is injected...
  (let [config (assistant/build-init-config (init-params) (:hooks (recording-hooks)))]
    (t/is (nil? (obj/get config "appImage"))))
  ;; ...and a host supplied appImage passes through untouched
  (let [direct #js {"src" "https://portal.example.com/direct.png" "hideClose" false}
        config (assistant/build-init-config (obj/set! (init-params) "appImage" direct)
                                            (:hooks (recording-hooks)))]
    (t/is (identical? direct (obj/get config "appImage")))))

(t/deftest build-init-config-does-not-mutate-the-host-params
  (let [params (-> (init-params)
                   (obj/set! "theme" "dark")
                   (obj/set! "logoUrl" "https://portal.example.com/logo.png"))
        before (js->clj (obj/clone params))]
    (assistant/build-init-config params (:hooks (recording-hooks)))
    (t/is (= before (js->clj params)))))

(t/deftest parse-incoming-accepts-valid-response
  (let [payload #js {"ok" true "reqId" "r1" "params" #js {"idToken" "t"}}
        parsed  (assistant/parse-incoming (response-data "getInitParams" payload))]
    (t/is (= :AI_ASSISTANT_RESPONSE (:type parsed)))
    (t/is (= :getInitParams (:event-name parsed)))
    (t/is (= "r1" (:req-id parsed)))
    (t/is (identical? payload (:payload parsed)))))

(t/deftest parse-incoming-reads-req-id-from-the-envelope
  ;; Tolerates a host that echoes the reqId next to the payload instead
  ;; of inside it.
  (let [data   (response-data "getInitParams" #js {"ok" true})
        parsed (assistant/parse-incoming (obj/set! data "reqId" "env-1"))]
    (t/is (= "env-1" (:req-id parsed)))))

(t/deftest parse-incoming-tolerates-missing-scope
  (let [parsed (assistant/parse-incoming
                #js {"type" "AI_ASSISTANT_RESPONSE"
                     "eventName" "ready"
                     "payload" #js {"ok" true}})]
    (t/is (= :ready (:event-name parsed)))
    (t/is (nil? (:req-id parsed)))))

(t/deftest parse-incoming-rejects-foreign-messages
  ;; wrong scope
  (t/is (nil? (assistant/parse-incoming
               (obj/set! (response-data "ready" #js {"ok" true})
                         "scope" "penpot/embed"))))
  ;; wrong type: an EVENT envelope is child->parent only
  (t/is (nil? (assistant/parse-incoming
               #js {"scope" "penpot/assistant"
                    "type" "AI_ASSISTANT_EVENT"
                    "eventName" "ready"
                    "payload" #js {"ok" true}})))
  ;; missing / non-string eventName
  (t/is (nil? (assistant/parse-incoming
               #js {"scope" "penpot/assistant"
                    "type" "AI_ASSISTANT_RESPONSE"
                    "payload" #js {"ok" true}})))
  (t/is (nil? (assistant/parse-incoming
               #js {"scope" "penpot/assistant"
                    "type" "AI_ASSISTANT_RESPONSE"
                    "eventName" 42
                    "payload" #js {"ok" true}}))))

(t/deftest parse-incoming-rejects-non-objects
  (t/is (nil? (assistant/parse-incoming nil)))
  (t/is (nil? (assistant/parse-incoming "AI_ASSISTANT_RESPONSE")))
  (t/is (nil? (assistant/parse-incoming 42)))
  (t/is (nil? (assistant/parse-incoming #js ["AI_ASSISTANT_RESPONSE"]))))

;; --- handshake! -------------------------------------------------------

(t/deftest handshake-resolves-ready-on-parent-ack
  (t/async done
    (let [io     (fake-io)
          parent (js-obj)
          conn   (assistant/create-conn (conn-opts io parent))]
      (p/then
       ((:handshake! conn))
       (fn [result]
         (t/is (= :ready result))
         (t/is (= "ready" (unchecked-get (:message (first @(:posted io))) "eventName")))
         ;; the handshake must stop resending once the ack arrived
         (let [posts-at-ack (count @(:posted io))]
           (js/setTimeout
            (fn []
              (t/is (= posts-at-ack (count @(:posted io)))
                    "ready is not resent after the ack")
              ((:close! conn))
              (done))
            60))))
      (js/setTimeout
       (fn []
         ((:emit! io) parent (response-data "ready" #js {"ok" true "timestamp" 1})))
       5))))

(t/deftest handshake-resolves-timeout-without-ack
  (t/async done
    (let [io      (fake-io)
          parent  (js-obj)
          opts    (assoc (conn-opts io parent) :handshake-timeout-ms 60)
          conn    (assistant/create-conn opts)
          promise ((:handshake! conn))
          promise-2 ((:handshake! conn))]
      ;; memoized: a second call must not restart the handshake
      (t/is (identical? promise promise-2))
      (p/then
       promise
       (fn [result]
         (t/is (= :timeout result))
         ((:close! conn))
         (done))))))

(t/deftest handshake-ignores-messages-that-fail-security-checks
  (t/async done
    (let [io       (fake-io)
          parent   (js-obj)
          intruder (js-obj)
          opts     (-> (conn-opts io parent)
                       (assoc :origin-allowed? (constantly false)
                              :handshake-timeout-ms 100))
          conn     (assistant/create-conn opts)
          ack      (response-data "ready" #js {"ok" true})]
      (p/then
       ((:handshake! conn))
       (fn [result]
         (t/is (= :timeout result)
               "a rejected ack can never complete the handshake")
         (t/is (>= (count @(:posted io)) 2)
               "ready keeps being resent while unanswered")
         ((:close! conn))
         (done)))
      ;; an ack from the wrong window...
      (js/setTimeout (fn [] ((:emit! io) intruder ack)) 10)
      ;; ...and one from the right window but a disallowed origin
      (js/setTimeout (fn [] ((:emit! io) parent ack)) 30))))

(t/deftest post-failures-never-escape-and-timeout-takes-over
  ;; .postMessage throws synchronously on payloads that cannot be
  ;; structurally cloned; nothing here may throw into the caller and
  ;; the handshake must still settle through its timeout backstop.
  (t/async done
    (let [io     (fake-io)
          parent (js-obj)
          opts   (-> (conn-opts io parent)
                     (assoc :post-message! (fn [_message _origin]
                                             (throw (js/Error. "DataCloneError")))
                            :handshake-timeout-ms 50
                            :request-timeout-ms 50))
          conn   (assistant/create-conn opts)]
      ;; any of these throwing synchronously fails the test
      (t/is (some? ((:handshake! conn))))
      (t/is (some? ((:request! conn) "getInitParams" #js {"timestamp" 1})))
      ((:post-event! conn) "initialized" {"timestamp" 1})
      (p/then
       ((:handshake! conn))
       (fn [result]
         (t/is (= :timeout result) "the timeout path is the backstop")
         ((:close! conn))
         (done))))))

;; --- request! ---------------------------------------------------------

(t/deftest request-resolves-on-matching-req-id
  (t/async done
    (let [io     (fake-io)
          parent (js-obj)
          conn   (assistant/create-conn (conn-opts io parent))]
      (p/then
       ((:request! conn) "getInitParams" #js {"timestamp" 1})
       (fn [result]
         (t/is (true? (:ok result)))
         (t/is (= "token" (unchecked-get (:payload result) "idToken")))
         ((:close! conn))
         (done)))
      (js/setTimeout
       (fn []
         (let [req-id (posted-req-id io)]
           (t/is (string? req-id) "the request is sent with a reqId")
           ((:emit! io) parent
                        (response-data "getInitParams"
                                       #js {"ok" true "reqId" req-id
                                            "params" #js {"idToken" "token"
                                                          "appName" "app"
                                                          "agentId" "agent"
                                                          "skillName" "skill"}}))))
       5))))

(t/deftest request-resolves-error-on-ok-false
  (t/async done
    (let [io     (fake-io)
          parent (js-obj)
          conn   (assistant/create-conn (conn-opts io parent))]
      (p/then
       ((:request! conn) "getInitParams" #js {"timestamp" 1})
       (fn [result]
         (t/is (= {:ok false :error "missing config"} result))
         ((:close! conn))
         (done)))
      (js/setTimeout
       (fn []
         ((:emit! io) parent
                      (response-data "getInitParams"
                                     #js {"ok" false "reqId" (posted-req-id io)
                                          "error" "missing config"})))
       5))))

(t/deftest request-keeps-resending-until-the-req-id-matches
  (t/async done
    (let [io     (fake-io)
          parent (js-obj)
          conn   (assistant/create-conn (conn-opts io parent))]
      (p/then
       ((:request! conn) "getInitParams" #js {"timestamp" 1})
       (fn [result]
         (t/is (true? (:ok result)))
         ;; one single reqId for the initial send and every resend
         (t/is (= 1 (count (distinct (map #(unchecked-get (:message %) "reqId")
                                          @(:posted io)))))
               "the reqId is stable across resends")
         ((:close! conn))
         (done)))
      ;; a response echoing some other reqId must be ignored...
      (js/setTimeout
       (fn []
         ((:emit! io) parent
                      (response-data "getInitParams"
                                     #js {"ok" true "reqId" "not-ours"
                                          "params" #js {"idToken" "nope"}})))
       5)
      ;; ...the conn keeps resending, and only the matching echo resolves
      (js/setTimeout
       (fn []
         (t/is (>= (count @(:posted io)) 2)
               "the request is resent while unanswered")
         ((:emit! io) parent
                      (response-data "getInitParams"
                                     #js {"ok" true "reqId" (posted-req-id io)
                                          "params" #js {"idToken" "token"
                                                        "appName" "app"
                                                        "agentId" "agent"
                                                        "skillName" "skill"}})))
       50))))

(t/deftest request-resolves-timeout-error
  (t/async done
    (let [io   (fake-io)
          parent (js-obj)
          opts (assoc (conn-opts io parent) :request-timeout-ms 80)
          conn (assistant/create-conn opts)]
      (p/then
       ((:request! conn) "getInitParams" #js {"timestamp" 1})
       (fn [result]
         (t/is (= {:ok false :error :timeout} result))
         (t/is (>= (count @(:posted io)) 2)
               "the request is resent until the deadline")
         ((:close! conn))
         (done))))))

;; --- post-event! ------------------------------------------------------

(t/deftest post-event-sends-the-protocol-envelope
  (let [io     (fake-io)
        parent (js-obj)
        conn   (assistant/create-conn (conn-opts io parent))]
    ((:post-event! conn) "initialized" {"timestamp" 123})
    (t/is (= 1 (count @(:posted io))))
    (let [{:keys [message origin]} (first @(:posted io))]
      (t/is (= "penpot/assistant" (unchecked-get message "scope")))
      (t/is (= "AI_ASSISTANT_EVENT" (unchecked-get message "type")))
      (t/is (= "initialized" (unchecked-get message "eventName")))
      (t/is (= 123 (unchecked-get (unchecked-get message "payload") "timestamp")))
      (t/is (nil? (unchecked-get message "reqId"))
            "one-way events carry no reqId")
      (t/is (= parent-origin origin)))
    ((:close! conn))))

(t/deftest post-event-falls-back-to-wildcard-target-origin
  ;; Nothing configured in the test environment and no injected origin:
  ;; the envelope must still go out, addressed to "*".
  (let [io     (fake-io)
        parent (js-obj)
        conn   (assistant/create-conn (assoc (conn-opts io parent) :target-origin nil))]
    ((:post-event! conn) "initialized" nil)
    (t/is (= "*" (:origin (first @(:posted io)))))
    ((:close! conn))))

(t/deftest post-event-targets-every-configured-origin
  ;; an exact-only target set addresses one envelope per origin
  (let [io     (fake-io)
        parent (js-obj)
        conn   (assistant/create-conn
                (assoc (conn-opts io parent)
                       :target-origin (fn [] ["https://a.example.com"
                                              "https://b.example.com"])))]
    ((:post-event! conn) "initialized" nil)
    (t/is (= ["https://a.example.com" "https://b.example.com"]
             (mapv :origin @(:posted io))))
    ((:close! conn))))

(t/deftest post-event-wraps-scalar-injected-target
  ;; a scalar override keeps working (one envelope, one origin)
  (let [io     (fake-io)
        parent (js-obj)
        conn   (assistant/create-conn
                (assoc (conn-opts io parent)
                       :target-origin (fn [] "https://a.example.com")))]
    ((:post-event! conn) "initialized" nil)
    (t/is (= ["https://a.example.com"] (mapv :origin @(:posted io))))
    ((:close! conn))))

;; --- close! -----------------------------------------------------------

(t/deftest close-removes-the-listener-and-is-idempotent
  (let [io     (fake-io)
        parent (js-obj)
        conn   (assistant/create-conn (conn-opts io parent))]
    (t/is (zero? @(:removed io)))
    ((:close! conn))
    (t/is (= 1 @(:removed io)))
    ;; a second call must be a no-op, not a throw
    ((:close! conn))
    (t/is (= 1 @(:removed io)))))

(t/deftest close-settles-a-pending-handshake
  ;; an unmount race must read as :closed, not as "host unreachable"
  (t/async done
    (let [io     (fake-io)
          parent (js-obj)
          conn   (assistant/create-conn (conn-opts io parent))]
      (p/then
       ((:handshake! conn))
       (fn [result]
         (t/is (= :closed result))
         (done)))
      ((:close! conn)))))

(t/deftest close-settles-a-pending-request
  ;; no promise is left dangling when the tab goes away mid-request
  (t/async done
    (let [io     (fake-io)
          parent (js-obj)
          conn   (assistant/create-conn (conn-opts io parent))]
      (p/then
       ((:request! conn) "getInitParams" #js {"timestamp" 1})
       (fn [result]
         (t/is (= {:ok false :error :closed} result))
         (done)))
      ((:close! conn)))))

;; ---------------------------------------------------------------------------
;; v2: session memory (localStorage) and history list formatting
;; ---------------------------------------------------------------------------

(defn- fake-storage
  "The localStorage surface the session memory needs, backed by an atom;
  also able to throw on every method for the containment tests."
  [& {:keys [throwing]}]
  (let [items (atom {})]
    #js {:setItem    (fn [k v]
                       (when throwing (throw (js/Error. "boom")))
                       (swap! items assoc k v))
         :getItem    (fn [k]
                       (when throwing (throw (js/Error. "boom")))
                       (get @items k))
         :removeItem (fn [k]
                       (when throwing (throw (js/Error. "boom")))
                       (swap! items dissoc k))}))

(t/deftest session-storage-key-format
  (t/is (= "penpot-assistant-session:p1:f1"
           (assistant/session-storage-key "p1" "f1"))))

(t/deftest saved-session-id-roundtrip
  (let [storage (fake-storage)]
    (t/is (nil? (assistant/load-saved-session-id "p1" "f1" storage)))
    (assistant/save-session-id! "p1" "f1" "s1" storage)
    (t/is (= "s1" (assistant/load-saved-session-id "p1" "f1" storage)))
    ;; a different (profile, file) pair never sees the same slot
    (t/is (nil? (assistant/load-saved-session-id "p2" "f1" storage)))
    (assistant/clear-saved-session-id! "p1" "f1" storage)
    (t/is (nil? (assistant/load-saved-session-id "p1" "f1" storage)))))

(t/deftest saved-session-id-nil-storage-is-a-no-op
  (t/is (nil? (assistant/load-saved-session-id "p" "f" nil)))
  (t/is (nil? (assistant/save-session-id! "p" "f" "s" nil)))
  (t/is (nil? (assistant/clear-saved-session-id! "p" "f" nil))))

(t/deftest saved-session-id-throwing-storage-is-contained
  (let [storage (fake-storage :throwing true)]
    (t/is (nil? (assistant/load-saved-session-id "p" "f" storage)))
    (t/is (nil? (assistant/save-session-id! "p" "f" "s" storage)))
    (t/is (nil? (assistant/clear-saved-session-id! "p" "f" storage)))))

(t/deftest parse-sdk-timestamp-units
  ;; 13 digit numeric string -> epoch millis
  (let [date (assistant/parse-sdk-timestamp "1732012800000")]
    (t/is (some? date))
    (t/is (= 1732012800000 (.getTime date))))
  ;; 10 digit numeric string -> epoch seconds (same instant)
  (let [date (assistant/parse-sdk-timestamp "1732012800")]
    (t/is (some? date))
    (t/is (= 1732012800000 (.getTime date)))))

(t/deftest parse-sdk-timestamp-garbage
  (t/is (nil? (assistant/parse-sdk-timestamp nil)))
  (t/is (nil? (assistant/parse-sdk-timestamp "")))
  (t/is (nil? (assistant/parse-sdk-timestamp "not-a-date"))))

(t/deftest format-session-time-fixed-shape
  ;; timezone independent assertions: the two unit spellings of the same
  ;; instant format identically, and the shape is always yyyy-MM-dd HH:mm
  (let [via-ms (assistant/format-session-time "1732012800000")
        via-s  (assistant/format-session-time "1732012800")]
    (t/is (some? via-ms))
    (t/is (= via-ms via-s))
    (t/is (re-find #"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$" via-ms))))

(t/deftest format-session-time-invalid
  (t/is (nil? (assistant/format-session-time "garbage")))
  (t/is (nil? (assistant/format-session-time nil))))

;; ---------------------------------------------------------------------------
;; v2.1: age grouped history list (today / last 7 days / last 30 days)
;; ---------------------------------------------------------------------------

(defn- hist-session
  "A session object with `uid` and, when `updated-at-ms` is given, an
  epoch-millis `updatedAt` (the unit SDK timestamps use)."
  [uid updated-at-ms]
  (cond-> #js {:uid uid}
    (some? updated-at-ms)
    (obj/set! "updatedAt" (str updated-at-ms))))

(defn- hist-now
  "The fixed 'now' of the grouping tests: 2026-11-20 12:00 local time."
  []
  (js/Date. 2026 10 20 12 0 0 0))

(defn- ms-at
  "Epoch millis of 12:00 on 2026-`month`-`day` local time (month is the
  js Date month index, 0 = January)."
  [month day]
  (.getTime (js/Date. 2026 month day 12)))

(defn- group-uids
  "Runs the grouping against the fixed now and flattens it to an
  ordered `[group-key [uid ...]]` vector for assertions."
  [sessions]
  (mapv (fn [{:keys [key sessions]}]
          [key (mapv #(obj/get % "uid") sessions)])
        (assistant/group-sessions-by-age sessions (hist-now))))

(t/deftest group-sessions-buckets-boundaries-and-order
  ;; boundaries: 7 days ago still belongs to the last-7-days bucket,
  ;; 8 days ago to last-30-days, 31 days ago is dropped entirely
  (let [sessions #js [(hist-session "d30" (ms-at 9 21))
                      (hist-session "today" (ms-at 10 20))
                      (hist-session "d31" (ms-at 9 20))
                      (hist-session "d7" (ms-at 10 13))
                      (hist-session "d8" (ms-at 10 12))
                      (hist-session "yesterday" (ms-at 10 19))]]
    (t/is (= [[:today ["today"]]
              [:last-7-days ["yesterday" "d7"]]
              [:last-30-days ["d8" "d30"]]]
             (group-uids sessions)))))

(t/deftest group-sessions-omits-empty-buckets
  (let [sessions #js [(hist-session "only-today" (ms-at 10 20))]]
    (t/is (= [[:today ["only-today"]]] (group-uids sessions)))))

(t/deftest group-sessions-drops-everything-older-than-30-days
  ;; a list whose every session is too old must yield no groups at all,
  ;; so the UI falls back to its empty state
  (let [sessions #js [(hist-session "old" (ms-at 9 20))]]
    (t/is (= [] (assistant/group-sessions-by-age sessions (hist-now))))))

(t/deftest group-sessions-future-timestamps-count-as-today
  ;; an SDK clock running ahead of the browser must not hide sessions
  (let [sessions #js [(hist-session "skewed" (+ (.getTime (hist-now)) 300000))]]
    (t/is (= [[:today ["skewed"]]] (group-uids sessions)))))

(t/deftest group-sessions-keeps-undated-sessions-reachable
  ;; no updatedAt at all or an unparseable one keeps the session in the
  ;; most inclusive bucket instead of silently hiding it; junk entries
  ;; that are not objects are skipped
  (let [garbage (obj/set! #js {:uid "garbage"} "updatedAt" "not-a-date")
        sessions #js [(hist-session "no-date" nil) "junk" garbage]]
    (t/is (= [[:last-30-days ["no-date" "garbage"]]] (group-uids sessions)))))

(t/deftest group-sessions-rejects-non-array-input
  (t/is (nil? (assistant/group-sessions-by-age nil (hist-now))))
  (t/is (nil? (assistant/group-sessions-by-age #js {} (hist-now))))
  (t/is (nil? (assistant/group-sessions-by-age "nope" (hist-now)))))

(t/deftest group-sessions-single-arity-uses-the-real-current-date
  ;; regression: the one-arg call used by the UI must bucket against
  ;; an actual js/Date, not a timestamp number (Date.now), which made
  ;; start-of-day throw "getFullYear is not a function"
  (let [fresh-ms (.getTime (js/Date.))]
    (t/is (= [[:today ["fresh"]]]
             (mapv (fn [{:keys [key sessions]}]
                     [key (mapv #(obj/get % "uid") sessions)])
                   (assistant/group-sessions-by-age
                    #js [(hist-session "fresh" fresh-ms)]))))))

;;; -------------------------------------------------------------------------
;;; v2 SDK tools extraction (the call-time contract with the UI
;;; component). The component keeps no ready-time snapshot: every SDK
;;; call re-extracts from the current global, so these tests pin what
;;; a fresh extraction must deliver.
;;; -------------------------------------------------------------------------

(defn- mk-fake-tools
  "A fake v2 tools object tagged with `tag`; its changeSessionId
  records the tag of the instance it was invoked on (through `this`)
  plus the session id."
  [tag]
  #js {"tag" tag
       "changeSessionId"
       (fn [id]
         (this-as self #js [(unchecked-get self "tag") id]))
       "jsSdk" #js {"getSessionList" (fn [] #js {"sessions" #js []})}})

(t/deftest extract-sdk-tools-binds-methods-to-their-owner
  (let [sdk  #js {"getTools" (fn [] (mk-fake-tools "gen0"))}
        {:keys [change-session-id! get-session-list!]}
        (assistant/extract-sdk-tools sdk)]
    (t/is (fn? change-session-id!))
    (t/is (fn? get-session-list!))
    ;; `this` inside the SDK method is the tools object it came from
    (t/is (= ["gen0" "s1"] (js->clj (change-session-id! "s1"))))
    (t/is (object? (get-session-list!)))))

(t/deftest extract-sdk-tools-follows-the-replaced-generation
  ;; simulates the SDK script being re-executed while the session
  ;; stays ready: getTools now returns a NEW tools object, and a fresh
  ;; extraction must reach the new generation. The previously bound
  ;; function keeps serving the old generation — which is exactly why
  ;; the UI must re-extract at call time instead of keeping a snapshot.
  (let [gen       (atom 0)
        tools-ref (atom (mk-fake-tools (str "gen" @gen)))
        sdk       #js {"getTools" (fn [] @tools-ref)}
        first!    (:change-session-id! (assistant/extract-sdk-tools sdk))]
    (reset! gen 1)
    (reset! tools-ref (mk-fake-tools (str "gen" @gen)))
    (let [second! (:change-session-id! (assistant/extract-sdk-tools sdk))]
      (t/is (not (identical? first! second!)))
      (t/is (= ["gen1" "x"] (js->clj (second! "x"))))
      (t/is (= ["gen0" "x"] (js->clj (first! "x")))))))

(t/deftest extract-sdk-tools-degrades-gracefully
  ;; a tools object without the v2 members yields a map of nils
  (let [result (assistant/extract-sdk-tools
                #js {"getTools" (fn [] #js {"jsSdk" #js {}})})]
    (t/is (map? result))
    (t/is (nil? (:change-session-id! result)))
    (t/is (nil? (:get-session-list! result)))
    (t/is (nil? (:open-chat! result))))
  ;; no tools object at all, a throwing getTools, a non-object sdk or
  ;; no sdk yields nil
  (t/is (nil? (assistant/extract-sdk-tools #js {"getTools" (fn [] nil)})))
  (t/is (nil? (assistant/extract-sdk-tools
               #js {"getTools" (fn [] (throw (js/Error. "boom")))})))
  (t/is (nil? (assistant/extract-sdk-tools #js {})))
  (t/is (nil? (assistant/extract-sdk-tools nil))))

(t/deftest extract-sdk-tools-accepts-an-object-gettools-and-binds-open-chat
  ;; per the host SDK docs getTools may carry the members directly
  ;; instead of being a function; openChat must bind to that same
  ;; object so `this` survives (this is the auto-open entry point).
  (let [tools #js {"tag" "obj"
                   "openChat"
                   (fn [prompt options]
                     (this-as self
                              #js [(unchecked-get self "tag") prompt
                                   (unchecked-get options "triggerAnswer")]))
                   "changeSessionId" (fn [_] nil)
                   "jsSdk" #js {"getSessionList" (fn [] nil)}}
        {:keys [open-chat! change-session-id!]}
        (assistant/extract-sdk-tools #js {"getTools" tools})]
    (t/is (fn? open-chat!))
    (t/is (fn? change-session-id!))
    (t/is (= ["obj" "hi" true]
             (js->clj (open-chat! "hi" #js {"triggerAnswer" true}))))))

;;; -------------------------------------------------------------------------
;;; SDK forward events contract: the exact event names the UI component
;;; subscribes with `on` and forwards to the host page. The host page
;;; relies on these names, so they are pinned here.
;;; -------------------------------------------------------------------------

(t/deftest sdk-forward-events-contract
  (t/is (= ["ai:connectX" "ai:tokenError" "ai:changeSession" "ai:createNewSession"]
           (vec assistant/sdk-forward-events))))
