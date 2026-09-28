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
  backend call (see `app.util.http/default-headers`).

  IMPORTANT: this namespace must not have any top level side effect. It
  is reachable from `app.util.http`, which lands on the `:shared` chunk
  of the `:main` build and is therefore also compiled into the
  rasterizer/render/viewer modules. Listening eagerly would make the
  rasterizer iframe post a bogus `ready` to its own parent. The
  handshake is started explicitly from `app.main/init` only."
  (:require
   [app.common.logging :as log]
   [app.config :as cf]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(def token-key "X-token")
(def client-id-key "X-ClientId")

(defonce ^:private credentials
  ;; nil | {:token "..." :client-id "..."}
  (atom nil))

(defonce ^:private handshake
  ;; nil | js/Promise, memoized so `start!` is idempotent.
  (atom nil))

(defonce ^:private wildcard-warned?
  (volatile! false))

(defn- embedded?
  "True when this document is rendered inside another window."
  []
  (not (identical? js/window js/parent)))

(defn headers
  "Request headers to add to every backend call. Empty until valid
  credentials have been received."
  []
  (if-let [{:keys [token client-id]} @credentials]
    {token-key token client-id-key client-id}
    {}))

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
  values. Public so the parsing rule can be unit-tested."
  [data]
  (and (object? data)
       (let [token     (obj/get data token-key)
             client-id (obj/get data client-id-key)]
         (and (string? token) (pos? (count (str/trim token)))
              (string? client-id) (pos? (count (str/trim client-id)))))))

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
  [token client-id]
  (let [updated? (some? @credentials)]
    (reset! credentials {:token token :client-id client-id})
    ;; Never log the values themselves.
    (log/inf :hint "embed: credentials received"
             :token-length (count token)
             :client-id-length (count client-id)
             :updated updated?)
    (when (p/pending? @handshake)
      (p/resolve! @handshake {:token token :client-id client-id}))))

(defn- on-message
  [^js event]
  (let [data (unchecked-get event "data")]
    (cond
      (not (identical? (unchecked-get event "source") js/parent))
      (log/dbg :hint "embed: ignoring message from a non-parent window")

      (not (origin-allowed? (unchecked-get event "origin")))
      (log/dbg :hint "embed: ignoring message from a disallowed origin"
               :origin (unchecked-get event "origin"))

      (not (valid-payload? data))
      (log/dbg :hint "embed: ignoring malformed credentials payload")

      :else
      (receive! (obj/get data token-key)
                (obj/get data client-id-key)))))

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
