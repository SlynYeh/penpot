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
  may also carry an optional `docContent` string, an optional `prompt`
  string and an optional `attachments` array of `{name, url}` string
  pairs; they are stored for the embedded workspace (assistant) and are
  never sent as HTTP headers.

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
(def prompt-key "prompt")
(def attachments-key "attachments")

(defonce ^:private credentials
  ;; nil | {:token "..." :client-id "..." :doc-content nil|"..."
  ;;        :prompt nil|"..." :attachments nil|[{:name "..." :url "..."}]}
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
  credentials have been received. The optional `docContent` / `prompt` /
  `attachments` fields are not headers."
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

(defn normalize-origin-pattern
  "Like `normalize-origin`, but additionally accepts a configured origin
  pattern with a leading `*.` wildcard label in the host. Returns a
  comparable `scheme://[*.]host[:port]` form, or nil when the value is
  missing or is not a valid url with a plain hostname — a bare `*` host
  is not a pattern (unconditional acceptance is the `none` sentinel)
  and `*` is only valid as the leading label. Public so the matching
  rule can be unit-tested."
  [pattern]
  (when (and (string? pattern) (not (str/blank? pattern)))
    (try
      (let [url (js/URL. pattern)]
        (when (and (re-find #"^(\*\.)?[A-Za-z0-9._-]+$" (.-hostname url))
                   (not= (.-origin url) "null"))
          (.-origin url)))
      (catch :default _ nil))))

(defn origin-accepted?
  "Whether `origin`, as reported by a received message, is an acceptable
  parent origin for the `configured` value. An absent or invalid
  configured origin means wildcard, so anything is accepted. Public so the
  matching rule can be unit-tested."
  [configured origin]
  (if-let [expected (normalize-origin configured)]
    ;; Exact comparison on normalized origins. A prefix check would accept
    ;; `https://good.example.attacker.io` for `https://good.example`.
    (= expected (normalize-origin origin))
    true))

(defn valid-payload?
  "Whether a received message carries usable `X-token` / `X-ClientId`
  values. Optional `docContent` / `prompt` / `attachments` are ignored
  here: credentials remain valid with or without them. Public so the
  parsing rule can be unit-tested."
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
  `{:token :client-id :doc-content :prompt :attachments}` when
  `valid-payload?` holds, else nil. Token and client-id are trimmed;
  `docContent` and `prompt` are kept as-is when they are strings
  (including empty) and nil otherwise; `attachments` is kept as a
  vector of `{:name :url}` maps only when it is an array whose every
  entry has string `name` and `url`, and nil otherwise. Public so the
  parsing rule can be unit-tested."
  [data]
  (when (valid-payload? data)
    (let [token           (str/trim (obj/get data token-key))
          client-id       (str/trim (obj/get data client-id-key))
          raw-doc-content (obj/get data doc-content-key)
          raw-prompt      (obj/get data prompt-key)]
      {:token token
       :client-id client-id
       :doc-content (when (string? raw-doc-content) raw-doc-content)
       :prompt (when (string? raw-prompt) raw-prompt)
       :attachments (parse-attachments data)})))

(defn- configured-origin
  []
  (normalize-origin cf/embed-parent-origin))

(defn- target-origin
  []
  (or (configured-origin) "*"))

(defn- origin-allowed?
  [origin]
  (if (configured-origin)
    (origin-accepted? cf/embed-parent-origin origin)
    (do
      (when-not (deref wildcard-warned?)
        (vreset! wildcard-warned? true)
        (log/wrn :hint "embed: no parent origin configured, accepting credentials from any window"))
      true)))

(defn- receive!
  [{:keys [token client-id doc-content prompt attachments] :as payload}]
  (let [updated? (some? @credentials)]
    (reset! credentials payload)
    ;; Never log the values themselves.
    (log/inf :hint "embed: credentials received"
             :token-length (count token)
             :client-id-length (count client-id)
             :doc-content-length (when (string? doc-content) (count doc-content))
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
  (let [origin (target-origin)]
    (when (= origin "*")
      (log/wrn :hint "embed: posting ready with wildcard target origin"))
    (.postMessage js/parent #js {:scope "penpot/embed" :type "ready"} origin)))

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
