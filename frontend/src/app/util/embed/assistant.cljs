;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.embed.assistant
  "postMessage channel between the embedded workspace and the hosting
  page, dedicated to the AI assistant tab.

  The parent cannot reach into this document, so the assistant session
  emits events to it over postMessage: a one-way stream of forwarded
  assistant SDK events (`initialized`, the init callback forwards and
  the `ai:*` SDK events). The channel is emit-only — no handshake, no
  request/response, no inbound listener. The init values the SDK needs
  are resolved locally (appName/agentId/skillName from the environment
  configuration, the idToken from the host page's sessionStorage
  EBEE_USER_KEY), and postMessage does not queue messages, so the host
  page must install its message listener before the user opens the
  assistant tab.

  One connection is created on every tab activation that passes the
  environment config check, and destroyed on unmount (`close!`); there
  is deliberately no defonce state in this namespace.

  IMPORTANT: this namespace must not have any top level side effect.
  All the IO (window/parent access and origin resolution) is resolved
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

(def sdk-forward-events
  "The SDK events the component layer subscribes with `on` and
  forwards under their SDK names to the host page over the assistant
  postMessage channel, one-way (no reqId, no resend, no timeout).
  Contract anchor: unit tested, and the host page can rely on these
  names."
  ["ai:connectX" "ai:tokenError" "ai:changeSession" "ai:createNewSession"])

(def id-token-poll-ms
  "Delay between two idToken availability checks while waiting for the
  host page to write EBEE_USER_KEY. Contract anchor: unit tested."
  500)

(def id-token-max-attempts
  "Total number of idToken CHECKS (the first one immediate), not
  retries: 10 checks / 9 intervals / ~4.5s before the tab gives up and
  enters the :token-missing error state. Contract anchor: unit
  tested."
  10)

(defn- id-token-from
  "Extracts the trimmed non-blank idToken from a parsed EBEE_USER_KEY
  object, nil when there is nothing usable."
  [^js user-key]
  (let [value (when (some? user-key)
                (obj/get user-key "idToken"))]
    (when (string? value)
      (let [value (str/trim value)]
        (when-not (str/blank? value)
          value)))))

(defn load-id-token
  "Reads the assistant `idToken` from the host page's sessionStorage
  user object (`embed/read-user-key` on the shared EBEE_USER_KEY
  contract): present, a string and non-blank after trimming returns the
  trimmed value; anything else returns nil. The one arity injects the
  storage for tests; the zero arity reads through `embed/read-user-key`'s
  own default resolution (fresh window lookup on every call)."
  ([] (id-token-from (embed/read-user-key)))
  ([storage] (id-token-from (embed/read-user-key storage))))

(defn missing-config-keys
  "Returns the keyword vector of assistant init config keys that are
  nil among their environment configured values — [:app-name :agent-id
  :skill-name] in that fixed order, [] when all are set. Pure helper:
  the component's fail-fast logs exactly which env var is absent."
  [app-name agent-id skill-name]
  (into []
        (keep (fn [[key value]]
                (when (nil? value) key)))
        [[:app-name app-name]
         [:agent-id agent-id]
         [:skill-name skill-name]]))

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
  "Builds the config object handed to the host SDK `init` method from
  the locally resolved init values — the environment configured
  app/agent/skill names and the idToken polled from the host page's
  sessionStorage — overlaid with the local wiring: the callback hooks,
  the getIdToken/defaultSkillName derivatives and the domMount layout.
  `getIdToken` re-reads the
  sessionStorage on every call (the host may refresh the token; it must
  write it back to EBEE_USER_KEY, Penpot never pulls it). Nothing else
  is passed through: additional SDK config keys are added by adding an
  environment variable, not by a generic channel. `load-id-token!` is
  injectable (defaults to `load-id-token`) so the re-reading behavior
  stays unit-testable."
  [{:keys [id-token app-name agent-id skill-name]}
   {:keys [enrich-message forward! load-id-token!]
    :or {load-id-token! load-id-token}}]
  #js {"idToken" id-token
       "appName" app-name
       "agentId" agent-id
       "skillName" skill-name
       "getIdToken" (fn [] (p/resolved (load-id-token!)))
       "defaultSkillName" skill-name
       "layoutType" "domMount"
       ;; The host SDK docs spell this hook two different ways; set
       ;; both to the same fn.
       "beforeSendMesssage" enrich-message
       "beforeSendMessage" enrich-message
       "afterSendMessage" (forward! "afterSendMessage")
       "onMessageFinish" (forward! "onMessageFinish")
       "satisfactionRating"
       #js {"enabled" true
            "onComplete" (forward! "onSatisfactionRatingComplete")}})

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

(defn start-token-wait!
  "Polls `read!` (expected to return the idToken string or nil) and
  calls `ready!` with the first non-nil value; when the
  `max-attempts`-th CHECK still yields nothing, calls `exhausted!`
  without scheduling again. max-attempts counts checks (first one
  immediate), not retries — the defaults give 10 checks / 9 intervals /
  ~4.5s. Returns a cancel fn that stops any pending schedule
  (idempotent). All of `read!`, `ready!`, `exhausted!`, `schedule!`
  and `cancel!` are injectable so the whole contract stays
  unit-testable."
  [{:keys [read! ready! exhausted! schedule! cancel!
           max-attempts poll-ms]
    :or {max-attempts id-token-max-attempts
         poll-ms      id-token-poll-ms
         cancel!      (constantly nil)}}]
  (let [cancelled? (volatile! false)
        timer      (volatile! nil)
        tick!      (fn tick! [attempt]
                     (when-not @cancelled?
                       (if-let [token (read!)]
                         (ready! token)
                         (if (< attempt max-attempts)
                           (vreset! timer
                                    (schedule! #(tick! (inc attempt)) poll-ms))
                           (exhausted!)))))]
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

(defn- build-message
  "The outbound envelope of the assistant channel. Every message is a
  one-way event; the envelope carries no request id."
  [event-name payload]
  (let [payload (cond
                  (nil? payload) nil
                  (coll? payload) (clj->js payload)
                  :else payload)]
    #js {"scope" scope-value
         "type" type-event
         "eventName" (name event-name)
         "payload" payload}))

(defn create-conn
  "Creates the one-way postMessage outlet to the embedding parent
  window for the assistant channel. One connection == one assistant
  tab session: create it on every tab activation that passes the
  environment config check, call `close!` on unmount.
  Returns:

   {:post-event! (fn [event-name payload])   ; one way notification
    :close!      (fn [])}                    ; idempotent teardown

  The channel is emit-only: no handshake, no request/response, no
  inbound listener. The init values the SDK needs are resolved locally
  (environment configuration + the host page's sessionStorage), and
  postMessage does not queue messages — the host page must install its
  message listener BEFORE the user opens the assistant tab, and a
  misconfigured PENPOT_EMBED_PARENT_ORIGIN is silent (nothing probes
  the parent any more).

  Every IO touchpoint is injectable for tests: `post-message!`,
  `target-origin` (string or nil-arity fn) and `parent`. The defaults
  resolve lazily through `app.util.globals` and share the
  `penpotEmbedParentOrigin` configuration with the credentials
  handshake in `app.util.embed`."
  [opts]
  (let [{:keys [post-message! target-origin parent]} opts

        parent
        (or parent (unchecked-get ug/window "parent"))

        post-message!
        (or post-message!
            (fn [message origin]
              (when (some? parent)
                (.postMessage parent message origin))))

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

                    ;; wildcard pattern configured: "*" is by design,
                    ;; not a misconfiguration — info, not wrn
                    (vector? parsed)
                    (log/inf :hint "assistant: posting with a wildcard target origin (wildcard pattern configured)")

                    :else
                    (log/wrn :hint "assistant: no usable parent origin configured, posting with a wildcard target origin")))
                targets))))

        closed?
        (volatile! false)

        post-failed?
        (volatile! false)

        post!
        (fn [message]
          ;; postMessage throws synchronously on payloads that cannot
          ;; be structurally cloned (third party SDK data flows through
          ;; here) and on illegal target origins. Contain it, log it
          ;; once; an exception escaping into a host SDK callback would
          ;; be far harder to trace. A throw aborts the remaining target
          ;; origins of the loop below; there is no resend.
          (try
            (doseq [origin (resolve-target-origin)]
              (post-message! message origin))
            (catch :default cause
              (when-not @post-failed?
                (vreset! post-failed? true)
                (log/err :hint "assistant: failed to post a message to the parent window"
                         :cause cause))
              nil)))

        post-event!
        (fn [event-name payload]
          (when-not @closed?
            (post! (build-message event-name payload))))

        close!
        (fn []
          ;; idempotent: a plain set, nothing else to release
          (vreset! closed? true))]

    {:post-event! post-event!
     :close! close!}))
