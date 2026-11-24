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
   [app.common.time :as ct]
   [app.util.embed :as embed]
   [app.util.globals :as ug]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(def ^:private scope-value "penpot/assistant")
(def ^:private type-event "AI_ASSISTANT_EVENT")
(def ^:private type-response "AI_ASSISTANT_RESPONSE")

(def sdk-forward-events
  "The SDK events the component layer subscribes with `on` and
  forwards under their SDK names to the host page over the assistant
  postMessage channel, one-way (no reqId, no resend, no timeout).
  Contract anchor: unit tested, and the host page can rely on these
  names."
  ["ai:connectX" "ai:tokenError" "ai:changeSession" "ai:createNewSession"])

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

(defn build-message-envs
  "Builds the `envs` object injected into each assistant SDK message:
  current page/file ids plus the optional `docContent` / `docTitle`
  received on the embed credentials handshake. Each optional field is
  omitted when it is not a string so a host that never sends it does
  not get an empty env key. Public so the shape can be unit-tested."
  [page-id file-id doc-content doc-title]
  (let [envs #js {"PAGE_ID" (str page-id)
                  "FILE_ID" (str file-id)}]
    (when (string? doc-content)
      (obj/set! envs "DOC_CONTENT" doc-content))
    (when (string? doc-title)
      (obj/set! envs "DOC_TITLE" doc-title))
    envs))

(defn build-init-config
  "Builds the config object handed to the host SDK `init` method: the
  init params returned by getInitParams are passed through in full —
  any extra key the host adds reaches the SDK untouched — and overlaid
  with the locally resolved wiring: the callback hooks (functions
  cannot cross postMessage), the derived getIdToken/defaultSkillName
  aliases, the domMount layout and the logoUrl->appImage derivation.
  The received params object is never mutated."
  [params {:keys [enrich-message forward!]}]
  (let [config (obj/merge params
                          #js {"getIdToken" (fn [] (p/resolved (obj/get params "idToken")))
                               "defaultSkillName" (obj/get params "skillName")
                               "layoutType" "domMount"
                               ;; The host SDK docs spell this hook two
                               ;; different ways; set both to the same fn.
                               "beforeSendMesssage" enrich-message
                               "beforeSendMessage" enrich-message
                               "afterSendMessage" (forward! "afterSendMessage")
                               "onMessageFinish" (forward! "onMessageFinish")
                               "satisfactionRating"
                               #js {"enabled" true
                                    "onComplete" (forward! "onSatisfactionRatingComplete")}})
        logo-url (obj/get params "logoUrl")]
    (if (some? logo-url)
      (obj/set! config "appImage"
                #js {"src" logo-url
                     "className" "ai-penpot-icon"
                     "hideClose" true})
      config)))

;; -------------------------------------------------------------------------
;; Auto-open: right after the SDK finished initializing, the prompt,
;; attachments and doc-title received on the embed credentials handshake
;; (app.util.embed) open a chat automatically. The shape/decision
;; helpers are pure, and the polling driver keeps every side effect
;; injectable, so the whole contract stays unit-testable.
;; -------------------------------------------------------------------------

(def auto-open-max-attempts 10)
(def auto-open-poll-ms 500)
(def auto-open-default-prompt "生成原型")

(defn open-chat-request?
  "Whether the handshake values justify auto-opening a chat: a
  non-blank prompt or a non-empty attachments vector."
  [prompt attachments]
  (boolean
   (or (and (string? prompt) (not (str/blank? prompt)))
       (and (vector? attachments) (seq attachments)))))

(defn open-chat-doc-title-request?
  "Whether a doc-title alone justifies the attachment-simulating
  auto-open: a non-blank string."
  [doc-title]
  (boolean
   (and (string? doc-title) (not (str/blank? doc-title)))))

(defn open-chat-prompt
  "The prompt handed to `openChat`: the host supplied non-blank prompt,
  or the default when it was not provided."
  [prompt]
  (if (and (string? prompt) (not (str/blank? prompt)))
    prompt
    auto-open-default-prompt))

(defn build-open-chat-options
  "Builds the options object of the `openChat` auto-open call:
  `triggerAnswer` and `newSession` are always true; `attachments` is
  included only when a non-empty vector arrived on the handshake (a
  vector of `{:name :url}` maps, converted to a plain JS array)."
  [attachments]
  (let [options #js {"triggerAnswer" true
                     "newSession" true}]
    (when (and (vector? attachments) (seq attachments))
      (obj/set! options "attachments" (clj->js attachments)))
    options))

(defn build-doc-title-open-chat-options
  "Builds the options object of the `openChat` doc-title fallback
  call: `triggerAnswer` is false so the model is not asked anything,
  `newSession` is true, and the doc title rides along as a single
  name-only attachment (`{name: doc-title, url: \"\"}`) to simulate a
  pre-attached file without triggering a chat turn."
  [doc-title]
  #js {"triggerAnswer" false
       "newSession" true
       "attachments" #js [#js {"name" doc-title "url" ""}]})

(defn start-auto-open!
  "Polls `values!` — a fn returning `[prompt attachments doc-title]` —
  and calls `open-chat!` as soon as the values justify a chat: a
  non-blank prompt or non-empty attachments (`open-chat-request?`)
  opens with `triggerAnswer` true; otherwise a non-blank doc-title
  (`open-chat-doc-title-request?`) opens an empty-prompt chat that
  carries the title as a name-only attachment with `triggerAnswer`
  false. Until either holds it re-checks through `schedule!` every
  `poll-ms`, at most `max-attempts` checks in total (the first one
  runs immediately). Returns a cancel fn that clears any pending
  schedule through `cancel!`."
  [{:keys [values! open-chat! schedule! cancel!
           max-attempts poll-ms]
    :or {max-attempts auto-open-max-attempts
         poll-ms auto-open-poll-ms
         cancel! (constantly nil)}}]
  (let [cancelled? (volatile! false)
        timer      (volatile! nil)
        tick!      (fn tick! [attempt]
                     (when-not @cancelled?
                       (let [[prompt attachments doc-title] (values!)]
                         (cond
                           (open-chat-request? prompt attachments)
                           (open-chat! (open-chat-prompt prompt)
                                       (build-open-chat-options attachments))

                           (open-chat-doc-title-request? doc-title)
                           (open-chat! "" (build-doc-title-open-chat-options doc-title))

                           :else
                           (when (< attempt max-attempts)
                             (vreset! timer
                                      (schedule! #(tick! (inc attempt))
                                                 poll-ms)))))))]
    (tick! 1)
    (fn []
      (vreset! cancelled? true)
      (when-let [scheduled @timer]
        (vreset! timer nil)
        (cancel! scheduled)))))

;; -------------------------------------------------------------------------
;; v2: session memory (localStorage) and history list formatting.
;; Pure key building / parsing lives here so the exact contracts stay
;; unit-testable; the localStorage IO is nil-safe and injectable.
;; -------------------------------------------------------------------------

(def ^:private session-storage-prefix "penpot-assistant-session")

(defn session-storage-key
  "Builds the localStorage key under which the assistant tab remembers
  the last session of a (profile, file) pair: the file dimension keeps
  the conversations of different designs apart, the profile dimension
  keeps different Penpot accounts apart on a shared browser. Public so
  the exact key format can be unit-tested."
  [profile-id file-id]
  (str session-storage-prefix ":" (str profile-id) ":" (str file-id)))

(defn- resolve-session-storage
  "The localStorage of the current window, or nil when unavailable
  (node tests, privacy modes, storage that throws on access)."
  []
  (try (unchecked-get ug/window "localStorage")
       (catch :default _ nil)))

(defn load-saved-session-id
  "Returns the remembered session id for the (profile, file) pair, or
  nil when nothing (or nothing usable) is stored. `storage` is
  injectable for tests and defaults to the window localStorage."
  ([profile-id file-id]
   (load-saved-session-id profile-id file-id (resolve-session-storage)))
  ([profile-id file-id storage]
   (when (some? storage)
     (try (let [value (.getItem storage (session-storage-key profile-id file-id))]
            (when (and (string? value) (pos? (count value))) value))
          (catch :default _ nil)))))

(defn save-session-id!
  "Remembers `session-id` as the current session of the (profile, file)
  pair. Never throws: a quota or privacy failure only loses the
  memory, the live session is unaffected."
  ([profile-id file-id session-id]
   (save-session-id! profile-id file-id session-id (resolve-session-storage)))
  ([profile-id file-id session-id storage]
   (when (some? storage)
     (try (.setItem storage (session-storage-key profile-id file-id) (str session-id))
          (catch :default _ nil)))))

(defn clear-saved-session-id!
  "Forgets the remembered session of the (profile, file) pair."
  ([profile-id file-id]
   (clear-saved-session-id! profile-id file-id (resolve-session-storage)))
  ([profile-id file-id storage]
   (when (some? storage)
     (try (.removeItem storage (session-storage-key profile-id file-id))
          (catch :default _ nil)))))

;; -------------------------------------------------------------------------
;; v2: host SDK tools surface extraction. Lives here (not in the UI
;; component) so the extraction contract stays unit-testable in node;
;; the component re-runs it against the current SDK global at every
;; call site instead of keeping a ready-time snapshot.
;; -------------------------------------------------------------------------

(defn extract-sdk-tools
  "Extracts the v2 session management surface from the host SDK object
  (`getTools() -> {jsSdk: {getSessionList}, changeSessionId, openChat}`),
  every member nil checked and bound to its owner object so `this`
  survives the extraction. `getTools` may be a function or, per the
  host SDK docs, an object carrying the members directly. An SDK
  without the v2 surface yields a map of nils — the v2 UI and the
  auto-open chat degrade to hidden instead of throwing."
  [^js sdk]
  (when (object? sdk)
    (let [get-tools (obj/get sdk "getTools")
          tools     (cond
                      (fn? get-tools)
                      (try (.getTools sdk) (catch :default _ nil))

                      (object? get-tools)
                      get-tools

                      :else nil)
          bind      (fn [owner name]
                      (let [method (when (object? owner) (obj/get owner name))]
                        (when (fn? method) (.bind method owner))))]
      (when (object? tools)
        (let [js-sdk (obj/get tools "jsSdk")]
          {:change-session-id! (bind tools "changeSessionId")
           :get-session-list!  (bind js-sdk "getSessionList")
           :open-chat!         (bind tools "openChat")})))))

(defn- valid-date?
  [date]
  (and (some? date) (not (js/isNaN (.getTime ^js date)))))

(defn parse-sdk-timestamp
  "Parses an SDK `updatedAt`/`createdAt` string into a js/Date, or nil.
  The host docs call the field a timestamp without naming the unit, so
  numeric strings are read as epoch seconds when their magnitude fits
  the 10 digit era and as epoch millis otherwise; non numeric strings
  go through the Date constructor. Public so the heuristic can be
  unit-tested."
  [value]
  (when (and (string? value) (pos? (count value)))
    (let [number (js/Number value)]
      (if (js/isFinite number)
        (let [millis (if (and (pos? number) (< number 100000000000))
                       (* number 1000)
                       number)
              date  (js/Date. millis)]
          (when (valid-date? date) date))
        (let [date (try (js/Date. value) (catch :default _ nil))]
          (when (valid-date? date) date))))))

(defn format-session-time
  "Formats an SDK timestamp string with the fixed, never localized
  `yyyy-MM-dd HH:mm` shape. Public so the shape can be unit-tested."
  [value]
  (when-let [date (parse-sdk-timestamp value)]
    (try (ct/format-inst date "yyyy-MM-dd HH:mm")
         (catch :default _ nil))))

;; -------------------------------------------------------------------------
;; v2.1: age grouped history list (today / last 7 days / last 30 days).
;; Pure bucketing lives here so the day edges stay unit-testable.
;; -------------------------------------------------------------------------

(def history-group-order
  "The history age buckets in display order."
  [:today :last-7-days :last-30-days])

(def ^:private day-ms 86400000)

(defn- start-of-day
  [^js date]
  (js/Date. (.getFullYear date) (.getMonth date) (.getDate date)))

(defn- calendar-days-ago
  "Whole calendar days between `date` and `now` (0 = the same local
  day). Rounded so a one hour DST shift cannot move a bucket edge."
  [^js date ^js now]
  (js/Math.round (/ (- (.getTime (start-of-day now))
                       (.getTime (start-of-day date)))
                    day-ms)))

(defn session-age-group
  "Maps an age in whole calendar days (0 = today) to its history
  bucket: :today, :last-7-days (1-7 days ago) or :last-30-days (8-30
  days ago). Older ages yield nil (spec: not displayed); negative ages
  (an SDK clock running ahead) count as today. Public so the bucket
  edges can be unit-tested."
  [days-ago]
  (cond
    (<= days-ago 0) :today
    (<= days-ago 7) :last-7-days
    (<= days-ago 30) :last-30-days
    :else nil))

(defn- entry-time-ms
  "The millis of the date (index 1) of a `[group date session]` entry,
  nil for an undated session."
  [entry]
  (let [^js date (nth entry 1)]
    (when (some? date) (.getTime date))))

(defn- by-time-desc
  "Comparator over `[group date session]` entries: newest first, the
  undated ones last, equal keys keep their input order (the platform
  sort is stable)."
  [a b]
  (let [ta (entry-time-ms a)
        tb (entry-time-ms b)]
    (cond
      (nil? ta) (if (nil? tb) 0 1)
      (nil? tb) -1
      :else     (compare tb ta))))

(defn group-sessions-by-age
  "Groups the SDK `sessions` array into the history buckets relative to
  `now` (a js/Date, injectable for tests): a vector of
  `{:key :today|:last-7-days|:last-30-days :sessions [...]}` in display
  order, newest session first inside a bucket, empty buckets omitted
  and sessions older than 30 days dropped (spec: not displayed). A
  session whose updatedAt cannot be parsed stays in the most inclusive
  bucket instead of silently disappearing. Non-object entries are
  skipped and a non-array input yields nil."
  ([sessions]
   (group-sessions-by-age sessions (js/Date.)))
  ([sessions now]
   (when (array? sessions)
     (let [entries   (keep
                      (fn [session]
                        (when (object? session)
                          (let [date  (parse-sdk-timestamp (obj/get session "updatedAt"))
                                group (if (nil? date)
                                        :last-30-days
                                        (session-age-group (calendar-days-ago date now)))]
                            (when (some? group)
                              [group date session]))))
                      sessions)
           by-group  (group-by first entries)]
       (into []
             (keep (fn [group]
                     (when-let [entries (get by-group group)]
                       {:key group
                        :sessions (mapv #(nth % 2) (sort by-time-desc entries))})))
             history-group-order)))))

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
                (let [parsed   (embed/origin-config)
                      allowed? (embed/origins-accept? parsed origin)]
                  (when (and allowed?
                             (not (deref warned?))
                             (or (= :none parsed) (nil? parsed)))
                    (vreset! warned? true)
                    (if (= :none parsed)
                      (log/inf :hint "assistant: parent origin validation disabled by config (none)")
                      (log/wrn :hint "assistant: no parent origin configured, accepting messages from any window")))
                  allowed?))))

        resolve-target-origin
        (cond
          (string? target-origin)
          (let [origin target-origin]
            (constantly [origin]))

          (some? target-origin)
          (let [resolve target-origin]
            (fn []
              (let [resolved (resolve)]
                (if (vector? resolved) resolved [resolved]))))

          :else
          (let [warned? (volatile! false)]
            (fn []
              (let [parsed  (embed/origin-config)
                    targets (embed/target-origins parsed)]
                (when (and (some #(= "*" %) targets) (not (deref warned?)))
                  (vreset! warned? true)
                  (cond
                    (= :none parsed)
                    (log/inf :hint "assistant: parent origin validation disabled by config (none), posting with a wildcard target origin")

                    ;; wildcard pattern configured: "*" is by design, not
                    ;; a misconfiguration — info, not wrn
                    (vector? parsed)
                    (log/inf :hint "assistant: posting with a wildcard target origin (wildcard pattern configured)")

                    :else
                    (log/wrn :hint "assistant: no usable parent origin configured, posting with a wildcard target origin")))
                targets))))

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
          ;;
          ;; A throw aborts the remaining targets of the loop below;
          ;; handshake!/request! resends are the backstop (post-event! is
          ;; one-way and does not resend).
          (try
            (doseq [origin (resolve-target-origin)]
              (post-message! message origin))
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
