;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.embed
  "Credentials handshake with the third party system embedding this
  application in an iframe.

  The parent window cannot inject values into this document, so the
  credentials arrive over postMessage: we post a `ready` message to the
  parent and it replies with a flat object holding the `X-token` and
  `X-ClientId` strings, which are then sent as request headers on every
  backend call (see `app.util.http/default-headers`). The same payload
  may also carry optional `docContent` / `docTitle` strings, an optional
  `prompt` string and an optional `attachments` array of `{name, url}`
  string pairs; they are stored for the embedded workspace (assistant)
  and are never sent as HTTP headers.

  IMPORTANT: this namespace must not have any top level side effect. It
  is reachable from `app.util.http`, which lands on the `:shared` chunk
  of the `:main` build and is therefore also compiled into the
  rasterizer/render/viewer modules. Listening eagerly would make the
  rasterizer iframe post a bogus `ready` to its own parent. The
  handshake is started explicitly from `app.main/init` only. The boot
  gate (wait-boot!) is likewise driven only from app.main/init; this
  namespace keeps no top-level side effects."
  (:require
   [app.common.logging :as log]
   [app.config :as cf]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(def token-key "X-token")
(def client-id-key "X-ClientId")
(def doc-content-key "docContent")
(def doc-title-key "docTitle")
(def prompt-key "prompt")
(def attachments-key "attachments")

(def ebee-user-key
  "sessionStorage key under which the embedding portal stores the
  session user as a JSON object. Shared contract of the host page:
  the workspace menu reads `originPathId` from it, the assistant tab
  reads `idToken` (see `read-user-key`)."
  "EBEE_USER_KEY")

(defn read-user-key
  "Reads and parses the host page's sessionStorage user object (see
  `ebee-user-key`). Returns the parsed JS object, or nil when the key
  is absent, the stored value is not a string, the JSON is malformed,
  the parsed value is not an object, or the storage itself throws
  (node tests, privacy modes). The storage is injectable for tests;
  the zero arity resolves the window's sessionStorage inside the same
  try, on every call, so a host writing the key later is seen without
  a reload."
  ([]
   (try
     (let [storage (unchecked-get js/window "sessionStorage")]
       (when (some? storage)
         (read-user-key storage)))
     (catch :default _ nil)))
  ([^js storage]
   (try
     (let [raw (when (some? storage)
                 (.getItem storage ebee-user-key))]
       (when (string? raw)
         (let [parsed (js/JSON.parse raw)]
           (when (object? parsed) parsed))))
     (catch :default _ nil))))

(defonce ^:private credentials
  ;; nil | {:token "..." :client-id "..." :doc-content nil|"..."
  ;;        :doc-title nil|"..." :prompt nil|"..."
  ;;        :attachments nil|[{:name "..." :url "..."}]}
  (atom nil))

(defonce ^:private handshake
  ;; nil | js/Promise, memoized so `start!` is idempotent.
  (atom nil))

(defonce ^:private wildcard-warned?
  (volatile! false))

(defn embedded?
  "True when this document is rendered inside another window. Public so the
   boot sequence in `app.main` can skip the embed gate entirely for
   top-level loads."
  []
  (not (identical? js/window js/parent)))

(defn headers
  "Request headers to add to every backend call. Empty until valid
  credentials have been received. The optional `docContent` / `docTitle`
  / `prompt` / `attachments` fields are not headers."
  []
  (if-let [{:keys [token client-id]} @credentials]
    {token-key token client-id-key client-id}
    {}))

(defn doc-content
  "The optional `docContent` string from the last accepted parent
  payload, or nil when it was absent, not a string, or no credentials
  have been received yet."
  []
  (get @credentials :doc-content))

(defn doc-title
  "The optional `docTitle` string from the last accepted parent
  payload, or nil when it was absent, not a string, or no credentials
  have been received yet."
  []
  (get @credentials :doc-title))

(defn prompt
  "The optional `prompt` string from the last accepted parent payload,
  or nil when it was absent, not a string, or no credentials have been
  received yet."
  []
  (get @credentials :prompt))

(defn attachments
  "The optional `attachments` vector of `{:name :url}` maps from the
  last accepted parent payload, or nil when it was absent, malformed,
  or no credentials have been received yet."
  []
  (get @credentials :attachments))

(defn normalize-origin
  "Reduces a configured origin, or the `event.origin` of a received
  message, to a comparable `scheme://host[:port]` form. Returns nil when
  the value is missing or is not a valid absolute url. Public so the
  matching rule can be unit-tested."
  [origin]
  (when (and (string? origin) (not (str/blank? origin)))
    (try
      (let [value (.-origin (js/URL. origin))]
        (when-not (= value "null") value))
      (catch :default _ nil))))

(def ^:private wildcard-host-prefix
  "An absolute url whose host starts with the `*.` wildcard label,
  capturing the scheme and the remainder. The label is split off before
  `js/URL` parsing because URL engines disagree on a `*` in the host:
  Blink (Chrome and every Chromium based browser) percent-escapes it
  (`new URL(\"https://*.a.b\").hostname` is `\"%2A.a.b\"`) while Node's
  URL passes it through — neither behavior can be relied on, so the
  wildcard label never reaches the parser. Anchored to the start so a
  `://*.` inside a path cannot be mistaken for a wildcard host."
  #"^([a-zA-Z][a-zA-Z0-9+.-]*)://\*\.(.+)$")

(defn normalize-origin-pattern
  "Like `normalize-origin`, but additionally accepts a configured origin
  pattern with a leading `*.` wildcard label in the host. The wildcard
  label is stripped before parsing (see `wildcard-host-prefix`) and
  re-attached to the parsed origin afterwards, so the result does not
  depend on how the runtime URL engine treats a `*` in the host.
  Returns a comparable `scheme://[*.]host[:port]` form, or nil when the
  value is missing or is not a valid url with a plain hostname or
  contains percent escapes — a bare `*` host
  is not a pattern (unconditional acceptance is the `none` sentinel)
  and `*` is only valid as the leading label. Public so the matching
  rule can be unit-tested."
  [pattern]
  (when (and (string? pattern) (not (str/blank? pattern))
             (not (str/includes? pattern "%")))
    (try
      (let [[_ scheme tail] (re-find wildcard-host-prefix pattern)
            url (js/URL. (if scheme (str scheme "://" tail) pattern))]
        (when (and (re-find #"^[A-Za-z0-9._-]+$" (.-hostname url))
                   (not= (.-origin url) "null"))
          (if (nil? scheme)
            (.-origin url)
            (let [idx  (str/index-of (.-origin url) "://")
                  base (.-origin url)]
              (str (subs base 0 (+ idx 3))
                   "*."
                   (subs base (+ idx 3)))))))
      (catch :default _ nil))))

(defn parse-origin-config
  "Parses the raw `penpotEmbedParentOrigin` value. Returns `:none` for
  the trimmed, case-insensitive sentinel (validation disabled), a
  vector of normalized origin patterns when at least one entry is
  usable, or nil when unset/blank or when every entry was unusable
  (wildcard fallback, the historical behavior). Entries are separated
  by commas and/or whitespace; invalid entries are dropped with a
  warning. Public so the parsing rule can be unit-tested."
  [configured]
  (cond
    (not (string? configured)) nil
    (= "none" (str/lower (str/trim configured))) :none
    :else
    (let [tokens  (re-seq #"[^,\s]+" configured)
          dropped (volatile! [])
          parsed  (into []
                        (keep (fn [token]
                                (or (normalize-origin-pattern token)
                                    (do (vswap! dropped conj token) nil))))
                        tokens)]
      (when (seq @dropped)
        (log/wrn :hint "embed: dropping invalid penpotEmbedParentOrigin entries"
                 :entries (str/join "," @dropped)))
      (when (seq parsed) parsed))))

(defn- split-origin
  "Splits a normalized `scheme://host[:port]` string into
  `[scheme host-with-port]`, or nil for an unexpected shape."
  [origin]
  (when-let [idx (and (string? origin) (str/index-of origin "://"))]
    [(subs origin 0 idx)
     (subs origin (+ idx 3))]))

(defn- wildcard-pattern?
  "Whether `pattern` carries the leading `*.` host label."
  [pattern]
  (some? (str/index-of pattern "://*.")))

(defn- origin-matches-pattern?
  "Whether the normalized `origin` matches the normalized `pattern`.
  Scheme and `host[:port]` compare exactly; a `*.` pattern host
  additionally matches the bare domain and subdomains at any depth.
  The port, when present, is part of the compared remainder and must
  match too — so a pattern without a port never matches an origin
  with one, and vice versa."
  [pattern origin]
  (when-let [[p-scheme p-rest] (split-origin pattern)]
    (when-let [[o-scheme o-rest] (split-origin origin)]
      (and (= p-scheme o-scheme)
           (if (wildcard-pattern? pattern)
             (let [suffix (subs p-rest 2)]
               (or (= suffix o-rest)
                   (str/ends-with? o-rest (str "." suffix))))
             (= p-rest o-rest))))))

(defn origins-accept?
  "Whether `origin`, as reported by a received message, is accepted by
  the parsed configuration `parsed` (`:none` | pattern vector | nil,
  the shape returned by `parse-origin-config`). `:none` and absent or
  unusable configurations accept anything; otherwise the origin must
  match at least one pattern. Public so the matching rule can be
  unit-tested."
  [parsed origin]
  (if (or (= :none parsed) (nil? parsed) (empty? parsed))
    true
    (let [normalized (normalize-origin origin)]
      (boolean (and normalized
                    (some #(origin-matches-pattern? % normalized) parsed))))))

(defonce ^:private parsed-origin-config
  ;; Memoized `parse-origin-config` over the compile-time constant
  ;; `cf/embed-parent-origin`: :none | pattern vector | nil.
  (delay (parse-origin-config cf/embed-parent-origin)))

(defn origin-config
  "The memoized parse of the configured `penpotEmbedParentOrigin`:
  `:none` | pattern vector | nil (unconfigured/unusable). Public so
  the assistant namespace shares one parse and one policy."
  []
  (deref parsed-origin-config))

(defn target-origins
  "Vector of postMessage target origins for the parent window. Exact
  patterns return themselves (one postMessage per origin; the browser
  delivers only the one matching the real parent). The `none` sentinel,
  an absent or unusable configuration, or any wildcard pattern returns
  `[\"*\"]` — a targetOrigin cannot express a wildcard host, and the
  ready/handshake messages carry no credentials. The one argument form
  takes an already parsed config; the zero argument form parses the
  live configuration. Public so the strategy can be unit-tested."
  ([]
   (target-origins (parse-origin-config cf/embed-parent-origin)))
  ([parsed]
   (cond
     (or (= :none parsed) (nil? parsed) (empty? parsed)) ["*"]
     (some wildcard-pattern? parsed) ["*"]
     :else (vec parsed))))

(defn origin-accepted?
  "Whether `origin`, as reported by a received message, is an acceptable
  parent origin for the raw `configured` value. The value is parsed
  with `parse-origin-config` (comma/whitespace separated origin
  patterns; `none` and unusable values mean wildcard) and matched with
  `origins-accept?`. Public so the matching rule can be unit-tested."
  [configured origin]
  (origins-accept? (parse-origin-config configured) origin))

(defn valid-payload?
  "Whether a received message carries usable `X-token` / `X-ClientId`
  values. Optional `docContent` / `docTitle` / `prompt` / `attachments`
  are ignored here: credentials remain valid with or without them.
  Public so the parsing rule can be unit-tested."
  [data]
  (and (object? data)
       (let [token     (obj/get data token-key)
             client-id (obj/get data client-id-key)]
         (and (string? token) (pos? (count (str/trim token)))
              (string? client-id) (pos? (count (str/trim client-id)))))))

(defn- valid-attachment?
  "An attachment entry must be an object whose `name` and `url` are
  strings; both are kept as-is (empty strings included), any other
  fields are ignored."
  [item]
  (and (object? item)
       (string? (obj/get item "name"))
       (string? (obj/get item "url"))))

(defn- parse-attachments
  "`attachments` is kept only when it is an array whose every entry is
  a valid attachment; it is normalized to a vector of `{:name :url}`
  maps. Any other value — including an array holding a malformed entry
  — is treated as not provided (nil)."
  [data]
  (let [raw (obj/get data attachments-key)]
    (when (and (obj/array? raw)
               (every? valid-attachment? (seq raw)))
      (mapv (fn [item]
              {:name (obj/get item "name")
               :url  (obj/get item "url")})
            (seq raw)))))

(defn parse-payload
  "Extracts the handshake fields from a received message. Returns
  `{:token :client-id :doc-content :doc-title :prompt :attachments}`
  when `valid-payload?` holds, else nil. Token and client-id are
  trimmed; `docContent`, `docTitle` and `prompt` are kept as-is when
  they are strings (including empty) and nil otherwise; `attachments`
  is kept as a vector of `{:name :url}` maps only when it is an array
  whose every entry has string `name` and `url`, and nil otherwise.
  Public so the parsing rule can be unit-tested."
  [data]
  (when (valid-payload? data)
    (let [token           (str/trim (obj/get data token-key))
          client-id       (str/trim (obj/get data client-id-key))
          raw-doc-content (obj/get data doc-content-key)
          raw-doc-title   (obj/get data doc-title-key)
          raw-prompt      (obj/get data prompt-key)]
      {:token token
       :client-id client-id
       :doc-content (when (string? raw-doc-content) raw-doc-content)
       :doc-title (when (string? raw-doc-title) raw-doc-title)
       :prompt (when (string? raw-prompt) raw-prompt)
       :attachments (parse-attachments data)})))

(defonce ^:private none-warned?
  (volatile! false))

(defn- origin-allowed?
  [origin]
  (let [parsed   (origin-config)
        allowed? (origins-accept? parsed origin)]
    (when (and allowed? (or (= :none parsed) (nil? parsed)))
      (if (= :none parsed)
        (when-not (deref none-warned?)
          (vreset! none-warned? true)
          (log/inf :hint "embed: parent origin validation disabled by config (none)"))
        (when-not (deref wildcard-warned?)
          (vreset! wildcard-warned? true)
          (log/wrn :hint "embed: no parent origin configured, accepting credentials from any window"))))
    allowed?))

(defn- receive!
  [{:keys [token client-id doc-content doc-title prompt attachments] :as payload}]
  (let [updated? (some? @credentials)]
    (reset! credentials payload)
    ;; Never log the values themselves.
    (log/inf :hint "embed: credentials received"
             :token-length (count token)
             :client-id-length (count client-id)
             :doc-content-length (when (string? doc-content) (count doc-content))
             :doc-title-length (when (string? doc-title) (count doc-title))
             :prompt-length (when (string? prompt) (count prompt))
             :attachments-count (when (vector? attachments) (count attachments))
             :updated updated?)
    (when (p/pending? @handshake)
      (p/resolve! @handshake payload))))

(defn- on-message
  [^js event]
  (let [data    (unchecked-get event "data")
        payload (parse-payload data)]
    (cond
      (not (identical? (unchecked-get event "source") js/parent))
      (log/dbg :hint "embed: ignoring message from a non-parent window")

      (not (origin-allowed? (unchecked-get event "origin")))
      (log/dbg :hint "embed: ignoring message from a disallowed origin"
               :origin (unchecked-get event "origin"))

      (nil? payload)
      (log/dbg :hint "embed: ignoring malformed credentials payload")

      :else
      (receive! payload))))

(defn- post-ready!
  []
  (let [parsed  (origin-config)
        targets (target-origins parsed)]
    (when (some #(= "*" %) targets)
      (cond
        (= :none parsed)
        (log/inf :hint "embed: posting ready with a wildcard target origin (validation disabled by config)")

        ;; a wildcard pattern degrades the target set to "*" by design
        ;; (a targetOrigin cannot express a wildcard host) — not a
        ;; misconfiguration, so info, not wrn
        (vector? parsed)
        (log/inf :hint "embed: posting ready with a wildcard target origin (wildcard pattern configured)")

        :else
        (log/wrn :hint "embed: posting ready with a wildcard target origin")))
    (doseq [origin targets]
      (.postMessage js/parent #js {:scope "penpot/embed" :type "ready"} origin))))

(defn start!
  "Starts the handshake and returns a promise resolved with the received
  credentials. Outside of an iframe nothing is installed and the promise
  resolves to nil right away.

  A later message carrying fresh credentials updates the stored values
  and takes effect on the next request; it does not resolve the promise
  twice."
  []
  (or @handshake
      (let [deferred (p/deferred)]
        (reset! handshake deferred)
        (if (embedded?)
          (do
            (.addEventListener js/window "message" on-message)
            (post-ready!))
          (p/resolve! deferred nil))
        deferred)))

(defn wait-boot!
  "Boot gate for embedded loads. Returns a promise resolved with one of:

   :credentials  — the parent delivered the postMessage credentials
   :authenticated — a probe says the cookie jar already authenticates
   :deadline     — gave up; caller should boot degraded (logged-out)

   Phases: until `timeout-ms` only the handshake is watched (no requests,
   so the happy path never sends uncredentialed traffic); from `timeout-ms`
   to `deadline-ms` the app probes `probe` (a function returning a promise of boolean, injected
   to avoid a layering cycle with app.main.repo) and re-posts `ready` every
   `resend-interval-ms` so a parent that missed the first message can still
   answer. Non-embedded callers never reach this function."
  [{:keys [handshake probe timeout-ms deadline-ms
           probe-interval-ms resend-interval-ms]
    :or {timeout-ms 5000
         deadline-ms 30000
         probe #(p/resolved false)
         probe-interval-ms 3000
         resend-interval-ms 2000}}]
  (let [result (p/deferred)
        timers (atom #{})
        open!  (fn [outcome]
                 (when (p/pending? result)
                   (run! js/clearInterval @timers)
                   (p/resolve! result outcome)))
        every! (fn [ms f] (swap! timers conj (js/setInterval f ms)))
        probe! (fn []
                 (-> (probe)
                     (p/catch (constantly false))
                     (p/then (fn [auth?] (when auth? (open! :authenticated))))))]

    ;; Phase 1: the handshake resolves as soon as credentials arrive.
    (p/then handshake (fn [creds] (when (some? creds) (open! :credentials))))

    ;; Phase 2: once the handshake-only window is over, probe the cookie
    ;; jar (immediately, then on an interval).
    (js/setTimeout
     (fn []
       (when (p/pending? result)
         (probe!)
         (every! probe-interval-ms #(when (p/pending? result) (probe!)))))
     timeout-ms)

    ;; Re-post ready while unresolved (postMessage has no buffering; the
    ;; first one can be lost if the parent listener is not installed yet).
    (every! resend-interval-ms
            (fn []
              (when (p/pending? result)
                (post-ready!))))

    ;; Phase 3: degraded boot at the deadline.
    (swap! timers conj (js/setTimeout #(open! :deadline) deadline-ms))

    result))
