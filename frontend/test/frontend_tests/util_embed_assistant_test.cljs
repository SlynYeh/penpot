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
  "Injectable postMessage double: records every posted message together
  with the target origin it was addressed to."
  []
  (let [posted (atom [])]
    {:posted posted
     :io     {:post-message! (fn [message origin]
                               (swap! posted conj {:message message :origin origin}))}}))

(defn- conn-opts
  [io parent]
  (merge (:io io)
         {:parent parent
          :target-origin parent-origin}))

(defn- fake-storage
  [m]
  #js {"getItem" (fn [k] (get m k))})

;; --- load-id-token / missing-config-keys --------------------------------

(t/deftest load-id-token-reads-and-trims-the-token
  (t/is (= "tok" (assistant/load-id-token
                  (fake-storage {"EBEE_USER_KEY" "{\"idToken\":\"  tok  \"}"})))))

(t/deftest load-id-token-returns-nil-when-unusable
  (t/is (nil? (assistant/load-id-token (fake-storage {}))))
  (t/is (nil? (assistant/load-id-token (fake-storage {"EBEE_USER_KEY" "{bad"}))))
  (t/is (nil? (assistant/load-id-token (fake-storage {"EBEE_USER_KEY" "{}"}))))
  (t/is (nil? (assistant/load-id-token
               (fake-storage {"EBEE_USER_KEY" "{\"idToken\":42}"}))))
  (t/is (nil? (assistant/load-id-token
               (fake-storage {"EBEE_USER_KEY" "{\"idToken\":\"\"}"}))))
  (t/is (nil? (assistant/load-id-token
               (fake-storage {"EBEE_USER_KEY" "{\"idToken\":\"   \"}"}))))
  (t/is (nil? (assistant/load-id-token
               (fake-storage {"EBEE_USER_KEY" "{\"other\":\"x\"}"}))))
  (t/is (nil? (assistant/load-id-token
               #js {"getItem" (fn [_] (throw (js/Error. "boom")))}))))

(t/deftest missing-config-keys-empty-when-all-set
  (t/is (= [] (assistant/missing-config-keys "app" "agent" "skill"))))

(t/deftest missing-config-keys-lists-in-fixed-order
  (t/is (= [:app-name] (assistant/missing-config-keys nil "agent" "skill")))
  (t/is (= [:agent-id :skill-name] (assistant/missing-config-keys "app" nil nil)))
  (t/is (= [:app-name :agent-id :skill-name]
           (assistant/missing-config-keys nil nil nil))))

(t/deftest id-token-poll-contract-anchors
  (t/is (= 500 assistant/id-token-poll-ms))
  (t/is (= 10 assistant/id-token-max-attempts)))

;; --- pure functions ---------------------------------------------------

(t/deftest build-message-envs-includes-page-and-file
  (let [envs (assistant/build-message-envs "page-1" "file-1" nil nil)]
    (t/is (= "page-1" (obj/get envs "PAGE_ID")))
    (t/is (= "file-1" (obj/get envs "FILE_ID")))
    (t/is (undefined? (obj/get envs "DOC_CONTENT")))
    (t/is (undefined? (obj/get envs "DOC_TITLE")))))

(t/deftest build-message-envs-includes-doc-content-when-string
  (let [envs (assistant/build-message-envs "page-1" "file-1" "the-doc" nil)]
    (t/is (= "the-doc" (obj/get envs "DOC_CONTENT"))))
  (let [envs (assistant/build-message-envs "page-1" "file-1" "" nil)]
    (t/is (= "" (obj/get envs "DOC_CONTENT"))))
  ;; Non-string doc-content is omitted, like it was never sent.
  (let [envs (assistant/build-message-envs "page-1" "file-1" 42 nil)]
    (t/is (undefined? (obj/get envs "DOC_CONTENT")))))

(t/deftest build-message-envs-includes-doc-title-when-string
  (let [envs (assistant/build-message-envs "page-1" "file-1" nil "the-design")]
    (t/is (= "the-design" (obj/get envs "DOC_TITLE"))))
  (let [envs (assistant/build-message-envs "page-1" "file-1" nil "")]
    (t/is (= "" (obj/get envs "DOC_TITLE"))))
  ;; Non-string doc-title is omitted, like it was never sent.
  (let [envs (assistant/build-message-envs "page-1" "file-1" nil 42)]
    (t/is (undefined? (obj/get envs "DOC_TITLE")))))

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

(t/deftest open-chat-doc-title-request-with-doc-title
  ;; a non-blank doc-title alone justifies the attachment-simulating chat
  (t/is (true? (assistant/open-chat-doc-title-request? "my design")))
  ;; absent, blank or non-string doc-title: nothing to attach
  (t/is (false? (assistant/open-chat-doc-title-request? nil)))
  (t/is (false? (assistant/open-chat-doc-title-request? "")))
  (t/is (false? (assistant/open-chat-doc-title-request? "   ")))
  (t/is (false? (assistant/open-chat-doc-title-request? 42))))

(t/deftest build-doc-title-open-chat-options-simulates-a-name-only-attachment
  (let [options (assistant/build-doc-title-open-chat-options "my design")]
    (t/is (false? (obj/get options "triggerAnswer")))
    (t/is (true? (obj/get options "newSession")))
    (let [attachments (obj/get options "attachments")]
      (t/is (array? attachments))
      (t/is (= 1 (count attachments)))
      (t/is (= "my design" (obj/get (first attachments) "name")))
      (t/is (= "" (obj/get (first attachments) "url"))))))

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
                 {:values!    (fn [] ["设计一个登录页" nil nil])
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

(t/deftest start-auto-open-falls-back-to-the-doc-title-attachment
  (let [io      (fake-polling)
        cancel! (assistant/start-auto-open!
                 {:values!    (fn [] [nil nil "my design"])
                  :open-chat! (:open-chat! io)
                  :schedule!  (:schedule! io)
                  :cancel!    (:cancel! io)})]
    (t/is (= 1 (count @(:opened io))))
    (let [[prompt options] (first @(:opened io))]
      (t/is (= "" prompt))
      (t/is (false? (obj/get options "triggerAnswer")))
      (t/is (true? (obj/get options "newSession")))
      (let [attachments (obj/get options "attachments")]
        (t/is (= "my design" (obj/get (first attachments) "name")))
        (t/is (= "" (obj/get (first attachments) "url")))))
    (t/is (empty? @(:scheduled io)) "no poll is scheduled after opening")
    (cancel!)))

(t/deftest start-auto-open-prefers-the-prompt-branch-over-the-doc-title
  ;; when both branches would fire, prompt/attachments win and the
  ;; doc-title is ignored
  (let [io      (fake-polling)
        cancel! (assistant/start-auto-open!
                 {:values!    (fn [] ["hi" nil "my design"])
                  :open-chat! (:open-chat! io)
                  :schedule!  (:schedule! io)
                  :cancel!    (:cancel! io)})]
    (let [[prompt options] (first @(:opened io))]
      (t/is (= "hi" prompt))
      (t/is (true? (obj/get options "triggerAnswer")))
      (t/is (undefined? (obj/get options "attachments"))))
    (cancel!)))

(t/deftest start-auto-open-polls-until-the-doc-title-arrives
  (let [io     (fake-polling)
        values (atom [nil nil nil])
        cancel! (assistant/start-auto-open!
                 {:values!    (fn [] @values)
                  :open-chat! (:open-chat! io)
                  :schedule!  (:schedule! io)
                  :cancel!    (:cancel! io)})]
    (t/is (empty? @(:opened io)))
    (t/is (= 1 (count @(:scheduled io))) "the first check schedules a retry")
    (let [[tick-2 _] (first @(:scheduled io))]
      (tick-2)
      (t/is (= 2 (count @(:scheduled io))))
      (t/is (empty? @(:opened io))))
    ;; the third check sees the doc-title and opens the simulated
    ;; attachment chat with an empty prompt
    (reset! values [nil nil "my design"])
    (let [[tick-3 _] (second @(:scheduled io))]
      (tick-3)
      (t/is (= 1 (count @(:opened io))))
      (let [[prompt options] (first @(:opened io))]
        (t/is (= "" prompt))
        (t/is (false? (obj/get options "triggerAnswer")))
        (t/is (= "my design"
                 (obj/get (first (obj/get options "attachments")) "name"))))
      (t/is (= 2 (count @(:scheduled io))) "no further poll after opening"))
    (cancel!)))

(t/deftest start-auto-open-polls-until-values-arrive
  (let [io     (fake-polling)
        values (atom [nil nil nil])
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
    (reset! values [nil [{:name "spec.txt" :url "https://example.com/spec.txt"}] nil])
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
                 {:values!      (fn [] [nil nil nil])
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
                 {:values!    (fn [] [nil nil nil])
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

;; --- start-token-wait! ---------------------------------------------------

(t/deftest token-wait-opens-immediately-when-the-token-is-there
  (let [io      (fake-polling)
        got     (atom nil)
        cancel! (assistant/start-token-wait!
                 {:read!      (fn [] "tok")
                  :ready!     (fn [token] (reset! got token))
                  :exhausted! (fn [] (throw (js/Error. "must not exhaust")))
                  :schedule!  (:schedule! io)
                  :cancel!    (:cancel! io)})]
    (t/is (= "tok" @got))
    (t/is (empty? @(:scheduled io)) "no poll is scheduled after a hit")
    (cancel!)))

(t/deftest token-wait-polls-until-the-token-arrives
  (let [io      (fake-polling)
        reads   (atom [nil "tok"])
        got     (atom nil)
        cancel! (assistant/start-token-wait!
                 {:read!      (fn [] (let [v (first @reads)] (swap! reads rest) v))
                  :ready!     (fn [token] (reset! got token))
                  :exhausted! (fn [] (throw (js/Error. "must not exhaust")))
                  :schedule!  (:schedule! io)
                  :cancel!    (:cancel! io)})]
    (t/is (nil? @got))
    (t/is (= 1 (count @(:scheduled io))) "the first miss schedules a retry")
    (let [[tick-2 ms] (first @(:scheduled io))]
      (t/is (= assistant/id-token-poll-ms ms) "the retry polls at the contract interval")
      (tick-2)
      (t/is (= "tok" @got))
      (t/is (= 1 (count @(:scheduled io))) "no further poll after a hit"))
    (cancel!)))

(t/deftest token-wait-gives-up-after-max-attempts
  (let [io       (fake-polling)
        checks   (atom 0)
        worn-out (atom false)
        _        (assistant/start-token-wait!
                  {:read!        (fn [] (swap! checks inc) nil)
                   :ready!       (fn [_] (throw (js/Error. "must not open")))
                   :exhausted!   (fn [] (reset! worn-out true))
                   :schedule!    (:schedule! io)
                   :cancel!      (:cancel! io)
                   :max-attempts 3})]
    ;; drive every scheduled tick until nothing is scheduled anymore
    (loop []
      (when (seq @(:scheduled io))
        (let [[tick _] (last @(:scheduled io))]
          (swap! (:scheduled io) pop)
          (tick))
        (recur)))
    (t/is (empty? @(:scheduled io)) "the poller stops scheduling")
    (t/is (= 2 @(:schedules io)) "3 checks total -> 2 re-schedules")
    (t/is (true? @worn-out))
    (t/is (= 3 @checks) "exactly max-attempts checks, the last one only exhausts")))

(t/deftest token-wait-cancel-clears-the-pending-poll
  (let [io      (fake-polling)
        cancel! (assistant/start-token-wait!
                 {:read!      (fn [] nil)
                  :ready!     (fn [_] (throw (js/Error. "must not open")))
                  :exhausted! (fn [] (throw (js/Error. "must not exhaust")))
                  :schedule!  (:schedule! io)
                  :cancel!    (:cancel! io)})]
    (cancel!)
    ;; a second call is a no-op, not another cancellation
    (cancel!)
    (t/is (= 1 @(:cancelled io)) "the pending timer was cleared")
    ;; a late tick can neither open nor reschedule
    (let [[tick _] (first @(:scheduled io))]
      (tick)
      (t/is (= 1 (count @(:scheduled io)))))))

(t/deftest token-wait-defaults-match-the-contract-anchors
  (let [io     (fake-polling)
        checks (atom 0)
        _      (assistant/start-token-wait!
                {:read!      (fn [] (swap! checks inc) nil)
                 :ready!     (fn [_] nil)
                 :exhausted! (fn [] nil)
                 :schedule!  (:schedule! io)
                 :cancel!    (:cancel! io)})]
    (loop []
      (when (seq @(:scheduled io))
        (let [[tick _] (last @(:scheduled io))]
          (swap! (:scheduled io) pop)
          (tick))
        (recur)))
    (t/is (= assistant/id-token-max-attempts @checks))))

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

;; --- build-init-config (env config + sessionStorage edition) -------------

(t/deftest build-init-config-carries-the-local-init-values
  (let [config (assistant/build-init-config
                {:id-token "tok" :app-name "app"
                 :agent-id "agent" :skill-name "skill"}
                (:hooks (recording-hooks)))]
    (t/is (= "tok" (obj/get config "idToken")))
    (t/is (= "app" (obj/get config "appName")))
    (t/is (= "agent" (obj/get config "agentId")))
    (t/is (= "skill" (obj/get config "skillName")))
    (t/is (= "skill" (obj/get config "defaultSkillName")))
    (t/is (= "domMount" (obj/get config "layoutType")))
    ;; the host passthrough and the logo/appImage derivation are gone
    (t/is (nil? (obj/get config "appImage")))
    (t/is (nil? (obj/get config "theme")))))

(t/deftest build-init-config-omits-platform-env-when-unset
  (let [config (assistant/build-init-config
                {:id-token "tok" :app-name "app"
                 :agent-id "agent" :skill-name "skill"}
                (:hooks (recording-hooks)))]
    ;; the optional key is absent, so the SDK reads `undefined`
    (t/is (undefined? (obj/get config "xPlatformEnv")))
    (t/is (false? (.hasOwnProperty config "xPlatformEnv")))))

(t/deftest build-init-config-carries-platform-env-when-set
  (let [config (assistant/build-init-config
                {:id-token "tok" :app-name "app"
                 :agent-id "agent" :skill-name "skill"
                 :platform-env "uat"}
                (:hooks (recording-hooks)))]
    (t/is (= "uat" (obj/get config "xPlatformEnv")))))

(t/deftest build-init-config-overlays-the-local-wiring
  (let [{:keys [events enriched hooks]} (recording-hooks)
        config (assistant/build-init-config
                {:id-token "tok" :app-name "app"
                 :agent-id "agent" :skill-name "skill"}
                hooks)
        msg    #js {"messageId" "m1"}
        rating #js {"score" 5}]
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

(t/deftest build-init-config-get-id-token-rereads-on-every-call
  (t/async done
    (let [tokens (atom ["t1" "t2"])
          config (assistant/build-init-config
                  {:id-token "init" :app-name "app"
                   :agent-id "agent" :skill-name "skill"}
                  (assoc (:hooks (recording-hooks))
                         :load-id-token!
                         (fn []
                           (let [value (first @tokens)]
                             (swap! tokens rest)
                             value))))]
      (p/then ((obj/get config "getIdToken"))
              (fn [first-value]
                (t/is (= "t1" first-value) "not the init-time snapshot")
                (p/then ((obj/get config "getIdToken"))
                        (fn [second-value]
                          (t/is (= "t2" second-value) "every call re-reads")
                          (done))))))))

;; --- post-event! --------------------------------------------------------

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

(t/deftest post-event-contains-a-throwing-post-message
  ;; .postMessage throws synchronously on payloads that cannot be
  ;; structurally cloned (third party SDK data flows through here) and
  ;; on illegal target origins. Nothing here may throw into the SDK
  ;; callback that forwarded the event (a synchronous test: any escape
  ;; errors), and one failed post must not poison the outlet: the next
  ;; event is still delivered. The one-shot error log is emitted on a
  ;; microtask by the logging executor and is deliberately not asserted.
  (let [delivered (atom [])
        {:keys [post-event! close!]}
        (assistant/create-conn
         {:parent        #js {}
          :target-origin parent-origin
          :post-message! (fn [message _origin]
                           (if (= "afterSendMessage" (unchecked-get message "eventName"))
                             (throw (js/Error. "DataCloneError"))
                             (swap! delivered conj (unchecked-get message "eventName"))))})]
    (post-event! "afterSendMessage" #js {"timestamp" 1})
    (post-event! "initialized" #js {"timestamp" 2})
    (t/is (= ["initialized"] @delivered) "a failed post does not poison the outlet")
    (close!)))

;; --- close! (emit-only edition) -------------------------------------------

(t/deftest close-is-idempotent-and-drops-further-events
  (let [io (fake-io)
        {:keys [post-event! close!]}
        (assistant/create-conn (conn-opts io #js {}))]
    (post-event! "initialized" #js {"timestamp" 1})
    (close!)
    (close!)
    (post-event! "initialized" #js {"timestamp" 2})
    (t/is (= 1 (count @(:posted io))) "events after close are dropped")))

;; ---------------------------------------------------------------------------
;; v2: session memory (localStorage) and history list formatting
;; ---------------------------------------------------------------------------

(defn- fake-local-storage
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
  (let [storage (fake-local-storage)]
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
  (let [storage (fake-local-storage :throwing true)]
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
