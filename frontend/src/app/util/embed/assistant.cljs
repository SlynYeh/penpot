;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.embed.assistant
  "postMessage channel between the embedded workspace and the hosting
  page, dedicated to the AI assistant tab.

  The parent cannot reach into this document, so the assistant session
  talks to it over postMessage: a `ready` handshake repeated until the
  parent acks it, a `getInitParams` request/response pair matched by
  `reqId`, and a stream of one-way events forwarded from the assistant
  SDK (`initialized`, `afterSendMessage`, ...). The protocol is defined
  by this side; see the communication section of
  docs/superpowers/plans/2026-11-09-ai-assistant-tab-integration.md.

  One connection is created every time the assistant tab activates and
  destroyed on unmount (`close!`); there is deliberately no defonce
  state in this namespace.

  IMPORTANT: this namespace must not have any top level side effect.
  All the IO (window/parent access, listeners, timers) is resolved
  lazily inside `create-conn`, so the namespace stays inert until a
  connection is explicitly created and unit tests can inject fakes."
  (:require
   [app.common.logging :as log]
   [app.config :as cf]
   [app.util.embed :as embed]
   [app.util.globals :as ug]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(def ^:private scope-value "penpot/assistant")
(def ^:private type-event "AI_ASSISTANT_EVENT")
(def ^:private type-response "AI_ASSISTANT_RESPONSE")

(def ^:private required-init-param-keys
  #js ["idToken" "appName" "agentId" "skillName"])

(defn valid-init-params?
  "Whether the `params` object of a getInitParams response carries
  usable assistant initialization values: `idToken`, `appName`,
  `agentId` and `skillName` must all be non-blank strings. `logoUrl` is
  optional but, when present, must be a non-blank string too. Public so
  the validation rule can be unit-tested."
  [params]
  (and (object? params)
       (every? (fn [key]
                 (let [value (obj/get params key)]
                   (and (string? value)
                        (pos? (count (str/trim value))))))
               required-init-param-keys)
       (let [logo-url (obj/get params "logoUrl")]
         (or (nil? logo-url)
             (and (string? logo-url)
                  (pos? (count (str/trim logo-url))))))))

(defn parse-incoming
  "Parses the `data` of a message event into
  `{:type :event-name :req-id :payload}`, or nil when the message does
  not belong to the assistant channel. Only RESPONSE messages are
  expected from the parent, so any other type is rejected; a missing
  `scope` is tolerated but a foreign one is not. The `reqId` is read
  from the envelope and, failing that, from the payload (where the
  getInitParams response carries it). Public so the parsing rule can be
  unit-tested."
  [data]
  (when (object? data)
    (let [scope      (obj/get data "scope")
          type       (obj/get data "type")
          event-name (obj/get data "eventName")]
      (when (and (or (nil? scope) (= scope-value scope))
                 (= type-response type)
                 (string? event-name)
                 (pos? (count event-name)))
        (let [payload (obj/get data "payload")
              req-id  (or (obj/get data "reqId")
                          (when (object? payload)
                            (obj/get payload "reqId")))]
          {:type (keyword type)
           :event-name (keyword event-name)
           :req-id (when (string? req-id) req-id)
           :payload payload})))))

(defn create-conn
  "Creates a postMessage connection to the embedding parent window.
  One connection == one assistant tab session: create it on tab
  activation, call `close!` on unmount. Returns:

   {:handshake!  (fn [] -> promise resolving :ready, :timeout, or
                   :closed when the session is torn down first)
    :request!    (fn [event-name payload] -> promise resolving
                   {:ok true :payload …} or {:ok false :error …};
                   timeouts resolve {:ok false :error :timeout},
                   nothing ever rejects)
    :post-event! (fn [event-name payload])   ; one way notification
    :close!      (fn [])}                    ; idempotent teardown

  Every IO touchpoint is injectable for tests: `post-message!`,
  `add-listener!`, `remove-listener!`, `set-timeout!`, `clear-timeout!`,
  `set-interval!`, `clear-interval!`, `origin-allowed?`, `target-origin`
  (string or nil-arity fn), `parent` and `on-event`. The defaults
  resolve lazily through `app.util.globals` and share the
  `penpotEmbedParentOrigin` configuration with the credentials
  handshake in `app.util.embed`. Inbound messages are only accepted
  from the injected `parent` window and an origin accepted by
  `origin-allowed?`; responses are matched to requests by `reqId`, and
  anything else that still parses is handed to the `on-event`
  placeholder."
  [opts]
  (let [{:keys [post-message! add-listener! remove-listener!
                set-timeout! clear-timeout! set-interval! clear-interval!
                origin-allowed? target-origin parent on-event
                resend-interval-ms handshake-timeout-ms request-timeout-ms]
         :or {resend-interval-ms 2000
              handshake-timeout-ms 15000
              request-timeout-ms 10000}}
        opts

        parent
        (or parent (unchecked-get ug/window "parent"))

        post-message!
        (or post-message!
            (fn [message origin]
              (when (some? parent)
                (.postMessage parent message origin))))

        add-listener!
        (or add-listener!
            (fn [handler] (.addEventListener ug/window "message" handler)))

        remove-listener!
        (or remove-listener!
            (fn [handler] (.removeEventListener ug/window "message" handler)))

        set-timeout!
        (or set-timeout! (fn [f ms] (js/setTimeout f ms)))

        clear-timeout!
        (or clear-timeout! (fn [id] (js/clearTimeout id)))

        set-interval!
        (or set-interval! (fn [f ms] (js/setInterval f ms)))

        clear-interval!
        (or clear-interval! (fn [id] (js/clearInterval id)))

        origin-allowed?
        (or origin-allowed?
            (let [warned? (volatile! false)]
              (fn [origin]
                (if (embed/normalize-origin cf/embed-parent-origin)
                  (embed/origin-accepted? cf/embed-parent-origin origin)
                  (do
                    (when-not @warned?
                      (vreset! warned? true)
                      (log/wrn :hint "assistant: no parent origin configured, accepting messages from any window"))
                    true)))))

        resolve-target-origin
        (cond
          (string? target-origin)
          (constantly target-origin)

          (some? target-origin)
          target-origin

          :else
          (let [warned? (volatile! false)]
            (fn []
              (or (embed/normalize-origin cf/embed-parent-origin)
                  (do
                    (when-not @warned?
                      (vreset! warned? true)
                      (log/wrn :hint "assistant: no parent origin configured, posting with a wildcard target origin"))
                    "*")))))

        on-event
        (or on-event (fn [_]))

        closed?
        (volatile! false)

        intervals
        (atom #{})

        timeouts
        (atom #{})

        handshake
        ;; nil | {:deferred :interval :timeout}; the entry survives
        ;; completion so `handshake!` stays memoized like `embed/start!`.
        (atom nil)

        pending
        ;; req-id -> {:deferred :interval :timeout}
        (atom {})

        counter
        (volatile! 0)

        install-interval!
        (fn [f ms]
          (let [id (set-interval! f ms)]
            (swap! intervals conj id)
            id))

        cancel-interval!
        (fn [id]
          (when (contains? @intervals id)
            (swap! intervals disj id)
            (clear-interval! id)))

        install-timeout!
        (fn [f ms]
          (let [id (set-timeout! f ms)]
            (swap! timeouts conj id)
            id))

        cancel-timeout!
        (fn [id]
          (when (contains? @timeouts id)
            (swap! timeouts disj id)
            (clear-timeout! id)))

        build-message
        (fn [event-name payload req-id]
          (let [payload (cond
                          (nil? payload) nil
                          (coll? payload) (clj->js payload)
                          :else payload)
                message #js {"scope" scope-value
                             "type" type-event
                             "eventName" (name event-name)
                             "payload" payload}]
            (when (some? req-id)
              (obj/set! message "reqId" req-id))
            message))

        post-failed?
        (volatile! false)

        post!
        (fn [message]
          ;; postMessage throws synchronously on payloads that cannot be
          ;; structurally cloned (third party SDK data flows through
          ;; here) and on illegal target origins. Contain it, log it
          ;; once, and let the resend/timeout paths be the backstop; an
          ;; exception escaping into a host SDK callback would be far
          ;; harder to trace.
          (try
            (post-message! message (resolve-target-origin))
            (catch :default cause
              (when-not @post-failed?
                (vreset! post-failed? true)
                (log/err :hint "assistant: failed to post a message to the parent window"
                         :cause cause))
              nil)))

        finish-handshake!
        (fn [result]
          (when-let [{:keys [deferred interval timeout]} @handshake]
            (some-> interval cancel-interval!)
            (some-> timeout cancel-timeout!)
            (swap! handshake assoc :interval nil :timeout nil)
            (when (p/pending? deferred)
              (p/resolve! deferred result))))

        finish-request!
        (fn [req-id result]
          (when-let [{:keys [deferred interval timeout]} (get @pending req-id)]
            (swap! pending dissoc req-id)
            (some-> interval cancel-interval!)
            (some-> timeout cancel-timeout!)
            (when (p/pending? deferred)
              (p/resolve! deferred result))))

        handshake!
        (fn []
          (if @closed?
            (p/resolved :closed)
            (or (some-> @handshake :deferred)
                (let [deferred (p/deferred)
                      ;; postMessage has no buffering: the first ready can
                      ;; be lost if the parent listener is not installed
                      ;; yet, so it is resent on an interval until the ack.
                      message (build-message "ready" #js {"timestamp" (.now js/Date)} nil)]
                  (reset! handshake {:deferred deferred})
                  (swap! handshake assoc
                         :interval (install-interval! #(post! message) resend-interval-ms)
                         :timeout (install-timeout! #(finish-handshake! :timeout) handshake-timeout-ms))
                  (post! message)
                  deferred))))

        request!
        (fn [event-name payload]
          (if @closed?
            (p/resolved {:ok false :error :closed})
            (let [req-id (str "penpot-assistant-"
                              (vswap! counter inc) "-"
                              (.now js/Date) "-"
                              (.random js/Math))
                  deferred (p/deferred)
                  ;; the same reqId identifies the initial send and every
                  ;; resend, so a slow parent answers exactly one request
                  message (build-message event-name payload req-id)]
              (swap! pending assoc req-id
                     {:deferred deferred
                      :interval (install-interval! #(post! message) resend-interval-ms)
                      :timeout (install-timeout!
                                #(finish-request! req-id {:ok false :error :timeout})
                                request-timeout-ms)})
              (post! message)
              deferred)))

        post-event!
        (fn [event-name payload]
          (when-not @closed?
            (post! (build-message event-name payload nil))))

        on-message
        (fn [^js event]
          (let [source (unchecked-get event "source")
                origin (unchecked-get event "origin")]
            (cond
              (not (identical? source parent))
              (log/dbg :hint "assistant: ignoring message from a non-parent window")

              (not (origin-allowed? origin))
              (log/dbg :hint "assistant: ignoring message from a disallowed origin"
                       :origin origin)

              :else
              (if-let [parsed (parse-incoming (unchecked-get event "data"))]
                (let [{:keys [event-name req-id payload]} parsed]
                  (cond
                    (and (= :ready event-name)
                         (some? @handshake)
                         (p/pending? (:deferred @handshake))
                         (obj/get payload "ok"))
                    (finish-handshake! :ready)

                    (and (some? req-id) (contains? @pending req-id))
                    (if (obj/get payload "ok")
                      (finish-request! req-id
                                       {:ok true
                                        :payload (let [params (obj/get payload "params")]
                                                   (if (nil? params) payload params))})
                      (finish-request! req-id
                                       {:ok false :error (obj/get payload "error")}))

                    :else
                    (on-event parsed)))
                (log/dbg :hint "assistant: ignoring message outside the assistant channel")))))

        close!
        (fn []
          ;; no CAS needed: single threaded, and close! never reenters
          (when (and (not @closed?)
                     (vreset! closed? true))
            (remove-listener! on-message)
            (doseq [id (vec @intervals)]
              (cancel-interval! id))
            (doseq [id (vec @timeouts)]
              (cancel-timeout! id))
            ;; settle anything still open so no promise is left dangling
            ;; once the session is gone; :closed, not :timeout — the UI
            ;; maps :timeout to "host page unreachable" copy, which a
            ;; plain unmount race must not trigger
            (finish-handshake! :closed)
            (doseq [req-id (vec (keys @pending))]
              (finish-request! req-id {:ok false :error :closed}))))]

    (add-listener! on-message)

    {:handshake! handshake!
     :request! request!
     :post-event! post-event!
     :close! close!}))
