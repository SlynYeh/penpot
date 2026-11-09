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
