;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.db
  (:refer-clojure :exclude [get run!])
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.geom.point :as gpt]
   [app.common.json :as json]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.transit :as t]
   [app.common.uuid :as uuid]
   [app.db.sql :as sql]
   [app.metrics :as mtx]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.set :as set]
   [integrant.core :as ig]
   [next.jdbc :as jdbc]
   [next.jdbc.date-time :as jdbc-dt]
   [next.jdbc.prepare :as jdbc.prepare]
   [next.jdbc.transaction])
  (:import
   com.zaxxer.hikari.HikariConfig
   com.zaxxer.hikari.HikariConfigMXBean
   com.zaxxer.hikari.HikariDataSource
   com.zaxxer.hikari.HikariPoolMXBean
   com.zaxxer.hikari.metrics.prometheus.PrometheusMetricsTrackerFactory
   io.whitfin.siphash.SipHasher
   io.whitfin.siphash.SipHasherContainer
   java.io.InputStream
   java.io.OutputStream
   java.sql.Connection
   java.sql.PreparedStatement
   java.sql.Savepoint
   com.huawei.opengauss.jdbc.geometric.PGpoint
   com.huawei.opengauss.jdbc.jdbc.PgArray
   com.huawei.opengauss.jdbc.largeobject.LargeObject
   com.huawei.opengauss.jdbc.largeobject.LargeObjectManager
   com.huawei.opengauss.jdbc.PGConnection
   com.huawei.opengauss.jdbc.util.PGInterval
   com.huawei.opengauss.jdbc.util.PGobject))

(def ^:dynamic *conn* nil)

(declare open)
(declare create-pool)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Initialization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:pool-options
  [:map {:title "pool-options"}
   [::connect-timeout {:optional true} ::sm/int]
   [::max-size {:optional true} ::sm/int]
   [::min-size {:optional true} ::sm/int]
   [::name {:optional true} :keyword]
   [::uri {:optional true} :string]
   [::password {:optional true} :string]
   [::username {:optional true} :string]
   [::validation-timeout {:optional true} ::sm/int]
   [::read-only {:optional true} ::sm/boolean]])

(def defaults
  {::name :main
   ::max-size 60
   ::connection-timeout 30000
   ::validation-timeout 10000
   ::idle-timeout 120000 ; 2min
   ::max-lifetime 1800000 ; 30m
   ::read-only false})

(defmethod ig/assert-key ::pool
  [_ options]
  (assert (sm/check schema:pool-options options)))

(defmethod ig/init-key ::pool
  [_ cfg]
  (let [{:keys [::uri ::read-only] :as cfg}
        (merge defaults (d/without-nils cfg))]
    (when uri
      (l/info :hint "initialize connection pool"
              :name (d/name (::name cfg))
              :uri (str uri)
              :read-only read-only
              :credentials (and (contains? cfg ::username)
                                (contains? cfg ::password))
              :min-size (or (::min-size cfg)
                            (::max-size cfg))
              :max-size (::max-size cfg))
      (create-pool cfg))))

(defmethod ig/halt-key! ::pool
  [_ pool]
  (when pool
    (.close ^HikariDataSource pool)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API & Impl
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def initsql
  "SET statement_timeout = 300000")

(defn- create-datasource-config
  [{:keys [::uri] :as cfg}]

  ;; (app.common.pprint/pprint cfg)
  (let [uri (-> uri
                (str/replace #"\?preferQueryMode=simple" "")
                (str/replace #"&preferQueryMode=simple" ""))
        max-size (::max-size cfg)
        min-size (or (::min-size cfg) max-size)
        config (HikariConfig.)]
    (doto config
      (.setJdbcUrl           (str "jdbc:" uri))
      (.setPoolName          (d/name (::name cfg)))
      (.setAutoCommit true)
      (.setReadOnly          (::read-only cfg))
      (.setConnectionTimeout (::connection-timeout cfg))
      (.setValidationTimeout (::validation-timeout cfg))
      (.setIdleTimeout       (::idle-timeout cfg))
      (.setMaxLifetime       (::max-lifetime cfg))
      (.setMinimumIdle       min-size)
      (.setMaximumPoolSize   max-size)
      (.setConnectionInitSql initsql)
      (.setInitializationFailTimeout -1))

    ;; When metrics namespace is provided
    (when-let [instance (::mtx/metrics cfg)]
      (->> (mtx/get-registry instance)
           (PrometheusMetricsTrackerFactory.)
           (.setMetricsTrackerFactory config)))

    (some->> ^String (::username cfg) (.setUsername config))
    (some->> ^String (::password cfg) (.setPassword config))

    config))

(defn pool?
  [v]
  (instance? javax.sql.DataSource v))

(defn connection?
  [conn]
  (instance? Connection conn))

(defn connectable?
  [o]
  (or (connection? o)
      (pool? o)))

(sm/register!
 {:type ::conn
  :pred connection?})

(sm/register!
 {:type ::connectable
  :pred connectable?})

(sm/register!
 {:type ::pool
  :pred pool?})

(defn closed?
  [pool]
  (.isClosed ^HikariDataSource pool))

(defn read-only?
  [pool-or-conn]
  (cond
    (instance? HikariDataSource pool-or-conn)
    (.isReadOnly ^HikariDataSource pool-or-conn)

    (instance? Connection pool-or-conn)
    (.isReadOnly ^Connection pool-or-conn)

    :else
    (ex/raise :type :internal
              :code :invalid-connection
              :hint "invalid connection provided")))

(defn pool-stats
  "Given a HikariDataSource instance, returns a map with current pool
  statistics: active/idle connections, threads awaiting connection,
  total connections, maximum pool size, and minimum idle connections."
  [^HikariDataSource pool]
  (let [^HikariPoolMXBean pool-mxbean (.getHikariPoolMXBean pool)
        ^HikariConfigMXBean cfg-mxbean  (.getHikariConfigMXBean pool)]
    {:active-connections        (.getActiveConnections pool-mxbean)
     :idle-connections          (.getIdleConnections pool-mxbean)
     :threads-awaiting-connection (.getThreadsAwaitingConnection pool-mxbean)
     :total-connections         (.getTotalConnections pool-mxbean)
     :maximum-pool-size         (.getMaximumPoolSize cfg-mxbean)
     :minimum-idle              (.getMinimumIdle cfg-mxbean)}))

(defn create-pool
  [cfg]
  (let [dsc (create-datasource-config cfg)]
    (jdbc-dt/read-as-instant)
    (HikariDataSource. dsc)))

(defn unwrap
  [conn klass]
  (.unwrap ^Connection conn klass))

(defn lobj-manager
  [conn]
  (let [conn (unwrap conn com.huawei.opengauss.jdbc.PGConnection)]
    (.getLargeObjectAPI ^PGConnection conn)))

(defn lobj-create
  [manager]
  (.createLO ^LargeObjectManager manager LargeObjectManager/READWRITE))

(defn lobj-open
  ([manager oid]
   (lobj-open manager oid {}))
  ([manager oid {:keys [mode] :or {mode :rw}}]
   (let [mode (case mode
                (:r :read) LargeObjectManager/READ
                (:w :write) LargeObjectManager/WRITE
                (:rw :read+write) LargeObjectManager/READWRITE)]
     (.open ^LargeObjectManager manager (long oid) mode))))

(defn lobj-unlink
  [manager oid]
  (.unlink ^LargeObjectManager manager (long oid)))

(extend-type LargeObject
  io/IOFactory
  (make-reader [lobj opts]
    (let [^InputStream is (.getInputStream ^LargeObject lobj)]
      (io/make-reader is opts)))
  (make-writer [lobj opts]
    (let [^OutputStream os (.getOutputStream ^LargeObject lobj)]
      (io/make-writer os opts)))
  (make-input-stream [lobj opts]
    (let [^InputStream is (.getInputStream ^LargeObject lobj)]
      (io/make-input-stream is opts)))
  (make-output-stream [lobj opts]
    (let [^OutputStream os (.getOutputStream ^LargeObject lobj)]
      (io/make-output-stream os opts))))

(defn open
  [system-or-pool]
  (if (pool? system-or-pool)
    (jdbc/get-connection system-or-pool)
    (if (map? system-or-pool)
      (open (::pool system-or-pool))
      (throw (IllegalArgumentException. "unable to resolve connection pool")))))

(defn get-update-count
  [result]
  (:next.jdbc/update-count result))

(defn get-connection
  [cfg-or-conn]
  (if (connection? cfg-or-conn)
    cfg-or-conn
    (if (map? cfg-or-conn)
      (get-connection (::conn cfg-or-conn))
      (throw (IllegalArgumentException. "unable to resolve connection")))))

(defn connection-map?
  "Check if the provided value is a map like data structure that
  contains a database connection."
  [o]
  (and (map? o) (connection? (::conn o))))

(defn get-connectable
  "Resolve to a connection or connection pool instance; if it is not
  possible, raises an exception"
  [o]
  (cond
    (connection? o) o
    (pool? o)       o
    (map? o)        (get-connectable (or (::conn o) (::pool o)))
    :else           (throw (IllegalArgumentException. "unable to resolve connectable"))))

(def ^:private params-mapping
  {::return-keys :return-keys})

(defn rename-opts
  [opts]
  (set/rename-keys opts params-mapping))

(declare duplicate-key-error?)

(def ^:private default-insert-opts
  (assoc sql/default-opts :return-keys true))

(def ^:private default-opts
  sql/default-opts)

(defn exec!
  ([ds sv] (exec! ds sv nil))
  ([ds sv opts]
   (let [conn (get-connectable ds)
         opts (if (empty? opts)
                default-opts
                (into default-opts (rename-opts opts)))]
     (jdbc/execute! conn sv opts))))

(defn exec-one!
  ([ds sv] (exec-one! ds sv nil))
  ([ds sv opts]
   (let [conn (get-connectable ds)
         opts (if (empty? opts)
                default-opts
                (into default-opts (rename-opts opts)))]
     (jdbc/execute-one! conn sv opts))))

(defn set-config!
  "Set a database configuration parameter via SET statement.
  Silently ignores 'unrecognized configuration parameter' errors
  for cross-database compatibility."
  [conn sql]
  (try
    (exec-one! conn [sql])
    (catch Throwable e
      (if (str/includes? (ex-message e) "unrecognized configuration parameter")
        (l/wrn :hint "skipping unrecognized configuration parameter"
               :sql sql
               :message (ex-message e))
        (throw e)))))

(def ^:private idle-timeout-params
  "Ordered list of idle-in-transaction timeout parameters to probe.
  GaussDB (Huawei Cloud): idle_in_transaction_timeout
  openGauss:              idle_in_transaction_session_timeout
  PostgreSQL 14+:         idle_in_transaction_session_timeout"
  ["idle_in_transaction_timeout"
   "idle_in_transaction_session_timeout"])

(defn disable-idle-timeout!
  "Disable idle-in-transaction timeout to allow long-running operations.
  Probes pg_settings to detect which parameter the database supports,
  avoiding 'unrecognized configuration parameter' errors that would
  abort the current transaction on GaussDB/openGauss."
  [conn & {:keys [local?] :or {local? true}}]
  (let [scope (if local? "LOCAL" "")
        ;; Probe pg_settings to find supported parameter (safe, won't abort tx)
        supported
        (try
          (->> (exec! conn [(str "SELECT name FROM pg_settings WHERE name IN ("
                                 (str/join "," (map #(str "'" % "'") idle-timeout-params))
                                 ")")])
               (map :name)
               (into #{}))
          (catch Exception _
            #{}))]
    (if-let [param (some supported idle-timeout-params)]
      (let [sql (str/trim (str "SET " scope " " param " = 0"))]
        (l/dbg :hint "disable idle-in-transaction timeout"
               :param param :scope scope)
        (exec-one! conn [sql]))
      (l/wrn :hint "unable to disable idle-in-transaction timeout, no supported parameter found"
             :probed idle-timeout-params))))

(defn insert!
  "A helper that builds an insert sql statement and executes it. By
  default returns the inserted row with all the field; you can delimit
  the returned columns with the `::sql/columns` option."
  [ds table params & {:as opts}]
  (let [conn (get-connectable ds)
        sql  (sql/insert table params opts)
        opts (if (empty? opts)
               default-insert-opts
               (into default-insert-opts (rename-opts opts)))
        conflict-do-nothing? (or (::sql/on-conflict-do-nothing opts)
                                 (::on-conflict-do-nothing? opts))]
    (if conflict-do-nothing?
      (try
        (jdbc/execute-one! conn sql opts)
        (catch Exception e
          (if (duplicate-key-error? e)
            nil
            (throw e))))
      (jdbc/execute-one! conn sql opts))))

(defn insert-many!
  "An optimized version of `insert!` that perform insertion of multiple
  values at once.

  This expands to a single SQL statement with placeholders for every
  value being inserted. For large data sets, this may exceed the limit
  of sql string size and/or number of parameters.

  See `insert-many-chunked!` for a safe alternative that automatically
  partitions rows to stay within the parameter limit."
  [ds table cols rows & {:as opts}]
  (let [conn (get-connectable ds)
        sql  (sql/insert-many table cols rows opts)
        opts (if (empty? opts)
               default-insert-opts
               (into default-insert-opts (rename-opts opts)))
        opts (update opts :return-keys boolean)
        conflict-do-nothing? (or (::sql/on-conflict-do-nothing opts)
                                 (::on-conflict-do-nothing? opts))]
    (if conflict-do-nothing?
      (try
        (jdbc/execute! conn sql opts)
        (catch Exception e
          (if (duplicate-key-error? e)
            nil
            (throw e))))
      (jdbc/execute! conn sql opts))))

(def ^:private default-max-params
  "PostgreSQL PreparedStatement parameter limit."
  65535)

(defn insert-many-chunked!
  "Like `insert-many!` but partitions rows into chunks that stay within
  PostgreSQL's 65,535 PreparedStatement parameter limit.

  The chunk size is computed as `floor(max-params / num-columns)`,
  so callers do not need to calculate it. All chunks execute within
  the same transaction when called inside `tx-run!`."
  [ds table cols rows & {:keys [max-params] :as opts
                         :or   {max-params default-max-params}}]
  (let [chunk-size (quot max-params (count cols))
        opts       (dissoc opts :max-params)]
    (doseq [chunk (partition-all chunk-size rows)]
      (apply insert-many! ds table cols chunk (mapcat identity opts)))))

(defn update!
  "A helper that build an UPDATE SQL statement and executes it.

  Given a connectable object, a table name, a hash map of columns and
  values to set, and either a hash map of columns and values to search
  on or a vector of a SQL where clause and parameters, perform an
  update on the table.

  By default returns an object with the number of affected rows; a
  complete row can be returned if you pass `::return-keys` with `true`
  or with a vector of columns.

  Also it can be combined with the `::many` option if you perform an
  update to multiple rows and you want all the affected rows to be
  returned."
  [ds table params where & {:as opts}]
  (let [conn (get-connectable ds)
        sql  (sql/update table params where opts)
        opts (if (empty? opts)
               default-opts
               (into default-opts (rename-opts opts)))
        opts (update opts :return-keys boolean)]
    (if (::many opts)
      (jdbc/execute! conn sql opts)
      (jdbc/execute-one! conn sql opts))))

(defn delete!
  "A helper that builds an DELETE SQL statement and executes it.

  Given a connectable object, a table name, and either a hash map of columns
  and values to search on or a vector of a SQL where clause and parameters,
  perform a delete on the table.

  By default returns an object with the number of affected rows; a
  complete row can be returned if you pass `::return-keys` with `true`
  or with a vector of columns.

  Also it can be combined with the `::many` option if you perform an
  update to multiple rows and you want all the affected rows to be
  returned."
  [ds table params & {:as opts}]
  (let [conn (get-connectable ds)
        sql  (sql/delete table params opts)
        opts (if (empty? opts)
               default-opts
               (into default-opts (rename-opts opts)))]
    (if (::many opts)
      (jdbc/execute! conn sql opts)
      (jdbc/execute-one! conn sql opts))))

(defn query
  [ds table params & {:as opts}]
  (exec! ds (sql/select table params opts) opts))

(defn is-row-deleted?
  [{:keys [deleted-at]}]
  (some? deleted-at))

(defn get*
  "Retrieve a single row from database that matches a simple filters. Do
  not raises exceptions."
  [ds table params & {:as opts}]
  (let [rows (exec! ds (sql/select table params opts))
        rows (cond->> rows
               (::remove-deleted opts true)
               (remove is-row-deleted?))]
    (first rows)))

(defn get
  "Retrieve a single row from database that matches a simple
  filters. Raises :not-found exception if no object is found."
  [ds table params & {:as opts}]
  (let [row (get* ds table params opts)]
    (when (and (not row) (::check-deleted opts true))
      (ex/raise :type :not-found
                :code :object-not-found
                :table table
                :params params
                :hint "database object not found"))
    row))

(defn get-with-sql
  [ds sql & {:as opts}]
  (let [rows
        (cond->> (exec! ds sql opts)
          (::remove-deleted opts true)
          (remove is-row-deleted?)

          :always
          (not-empty))]

    (when (and (not rows) (::throw-if-not-exists opts true))
      (ex/raise :type :not-found
                :code :object-not-found
                :hint "database object not found"))

    (first rows)))

(def ^:private default-plan-opts
  (-> default-opts
      (assoc :fetch-size 1000)
      (assoc :concurrency :read-only)
      (assoc :cursors :close)
      (assoc :result-type :forward-only)))

(defn plan
  ([ds sql]
   (-> (get-connectable ds)
       (jdbc/plan sql default-plan-opts)))
  ([ds sql opts]
   (-> (get-connectable ds)
       (jdbc/plan sql (merge default-plan-opts opts)))))

(defn cursor
  "Return a lazy seq of rows using server side cursors"
  [conn query & {:keys [chunk-size] :or {chunk-size 25}}]
  (let [cname  (str (gensym "cursor_"))
        fquery [(str "FETCH " chunk-size " FROM " cname)]]

    ;; declare cursor
    (exec-one! conn
               (if (vector? query)
                 (into [(str "DECLARE " cname " CURSOR FOR " (nth query 0))]
                       (rest query))
                 [(str "DECLARE " cname " CURSOR FOR " query)]))

    ;; return a lazy seq
    ((fn fetch-more []
       (lazy-seq
        (when-let [chunk (seq (exec! conn fquery))]
          (concat chunk (fetch-more))))))))

(defn get-by-id
  [ds table id & {:as opts}]
  (get ds table {:id id} opts))

(defn pgobject?
  ([v]
   (instance? PGobject v))
  ([v type]
   (and (instance? PGobject v)
        (= type (.getType ^PGobject v)))))

(defn pginterval?
  [v]
  (instance? PGInterval v))

(defn pgpoint?
  [v]
  (instance? PGpoint v))

(defn pgarray?
  ([v] (instance? PgArray v))
  ([v type]
   (and (instance? PgArray v)
        (= type (.getBaseTypeName ^PgArray v)))))

(defn pgarray-of-uuid?
  [v]
  (and (pgarray? v) (= "uuid" (.getBaseTypeName ^PgArray v))))

;; TODO rename to decode-pgarray-into
(defn decode-pgarray
  ([v] (decode-pgarray v []))
  ([v in]
   (into in (some-> ^PgArray v .getArray)))
  ([v in xf]
   (into in xf (some-> ^PgArray v .getArray))))

(defn pgarray->set
  [v]
  (set (.getArray ^PgArray v)))

(defn pgarray->vector
  [v]
  (vec (.getArray ^PgArray v)))

(defn pgpoint
  [p]
  (PGpoint. (:x p) (:y p)))

(defn create-array
  [conn type objects]
  (let [^PGConnection conn (unwrap conn com.huawei.opengauss.jdbc.PGConnection)]
    (if (coll? objects)
      (.createArrayOf conn ^String type (into-array Object objects))
      (.createArrayOf conn ^String type objects))))

(defn encode-pgarray
  [data conn type]
  (create-array conn type data))

(defn decode-pgpoint
  [^PGpoint v]
  (gpt/point (.-x v) (.-y v)))

(defn pginterval
  [data]
  (com.huawei.opengauss.jdbc.util.PGInterval. ^String data))

(defn savepoint
  ([^Connection conn]
   (.setSavepoint conn))
  ([^Connection conn label]
   (.setSavepoint conn (name label))))

(defn release!
  [^Connection conn ^Savepoint sp]
  (.releaseSavepoint conn sp))

(defn rollback!
  ([conn]
   (if (and (map? conn) (::savepoint conn))
     (rollback! conn (::savepoint conn))
     (let [^Connection conn (get-connection conn)]
       (l/trc :hint "explicit rollback requested")
       (.rollback conn))))
  ([conn ^Savepoint sp]
   (let [^Connection conn (get-connection conn)]
     (l/trc :hint "explicit rollback requested (savepoint)")
     (.rollback conn sp))))

(defn transact!
  "A lower-level function for executing function in a transaction"
  ([transactable f] (transact! transactable f {}))
  ([transactable f opts]
   (binding [next.jdbc.transaction/*nested-tx* :ignore]
     (jdbc/transact transactable f opts))))

(defn tx-run!
  "Run a function in a transaction."
  [system f & params]
  (if (connection? system)
    (tx-run! {::conn system} f)
    (if (pool? system)
      (tx-run! {::pool system} f)
      (if-let [conn (or (::conn system)
                        (::pool system))]
        (transact! conn
                   (fn [conn]
                     (let [system' (-> system
                                       (dissoc ::rollback)
                                       (assoc ::conn conn))]
                       (apply f system' params)))
                   {:rollback-only (::rollback system)
                    :read-only (::read-only system)})
        (throw (IllegalArgumentException. "invalid system/cfg provided"))))))

(defn run!
  [system f & params]
  (cond
    (connection? system)
    (apply run! {::conn system} f params)

    (pool? system)
    (apply run! {::pool system} f params)

    (::conn system)
    (apply f system params)

    (::pool system)
    (with-open [^Connection conn (open (::pool system))]
      (apply f (assoc system ::conn conn) params))

    :else
    (throw (IllegalArgumentException. "invalid arguments"))))

(defn interval
  [o]
  (cond
    (or (integer? o)
        (float? o))
    (->> (/ o 1000.0)
         (format "%s seconds")
         (pginterval))

    (string? o)
    (pginterval o)

    (ct/duration? o)
    (interval (inst-ms o))

    :else
    (ex/raise :type :not-implemented
              :hint (format "no implementation found for value %s" (pr-str o)))))

(defn decode-json-pgobject
  "Decode a JSON/JSONB PGobject to a Clojure data structure.
  Handles GaussDB JDBC driver which may return non-standard type names."
  [^PGobject o]
  (when o
    (let [typ (.getType o)
          val (.getValue o)]
      (if (or (= typ "json")
              (= typ "jsonb")
              ;; GaussDB may return type names like 'jsonb' with
              ;; different casing or nil; try parsing as JSON anyway
              (nil? typ))
        (json/decode val :key-fn keyword)
        (try
          (json/decode val :key-fn keyword)
          (catch Exception _
            val))))))

(defn decode-transit-pgobject
  "Decode a Transit-encoded JSON/JSONB PGobject to a Clojure data structure.
  Handles GaussDB JDBC driver which may return non-standard type names."
  [^PGobject o]
  (when o
    (let [typ (.getType o)
          val (.getValue o)]
      (if (or (= typ "json")
              (= typ "jsonb")
              (nil? typ))
        (t/decode-str val)
        (try
          (t/decode-str val)
          (catch Exception _
            val))))))

(defn decode-transit-jsonb
  "Decode a JSONB/JSON value regardless of whether the JDBC driver
  returns it as a PGobject or a raw String (GaussDB may return raw
  strings for JSONB columns)."
  [value]
  (cond
    (pgobject? value) (decode-transit-pgobject value)
    (string? value)   (t/decode-str value)
    :else             value))

(defn safe-decode-jsonb
  "Safely decode a JSONB value into a map, handling all possible types
  that different JDBC drivers may return (PGobject, String, CharSequence,
  or already-decoded map). Returns an empty map on any failure."
  [v]
  (try
    (let [decoded (cond
                    (nil? v)    nil
                    (map? v)    v
                    (pgobject? v) (decode-transit-pgobject v)
                    (string? v) (t/decode-str v)
                    (instance? CharSequence v) (t/decode-str (str v))
                    :else       nil)]
      (if (map? decoded) decoded {}))
    (catch Throwable _
      {})))

(defn inet
  [ip-addr]
  (when ip-addr
    (doto (com.huawei.opengauss.jdbc.util.PGobject.)
      (.setType "inet")
      (.setValue (str ip-addr)))))

(defn decode-inet
  [^PGobject o]
  (when o
    (if (= "inet" (.getType o))
      (.getValue o)
      nil)))

(defn tjson
  "Encode as transit json."
  [data]
  (when data
    (doto (com.huawei.opengauss.jdbc.util.PGobject.)
      (.setType "jsonb")
      (.setValue (t/encode-str data {:type :json-verbose})))))

(defn json
  "Encode as plain json."
  [data]
  (when data
    (doto (com.huawei.opengauss.jdbc.util.PGobject.)
      (.setType "jsonb")
      (.setValue (json/encode data)))))

;; --- Locks

(def ^:private siphash-state
  (SipHasher/container
   (uuid/get-bytes uuid/zero)))

(defn uuid->hash-code
  [o]
  (.hash ^SipHasherContainer siphash-state
         ^bytes (uuid/get-bytes o)))

(defn- xact-check-param
  [n]
  (cond
    (uuid? n) (uuid->hash-code n)
    (int? n)  n
    :else (throw (IllegalArgumentException. "uuid or number allowed"))))

(def ^:private sql:pg-xact-lock
  "select pg_advisory_xact_lock(?::bigint) as lock")

(def ^:private sql:pg-xact-try-lock
  "select pg_try_advisory_xact_lock(?::bigint) as lock")

(def ^:private sql:gauss-xact-lock
  "select gs_advisory_xact_lock(?::bigint) as lock")

(def ^:private sql:gauss-xact-try-lock
  "select gs_try_advisory_xact_lock(?::bigint) as lock")

(defn- advisory-lock-fn-not-found?
  [e]
  (let [msg (ex-message e)]
    (or (str/includes? msg "does not exist")
        (str/includes? msg "function")
        (str/includes? msg "unrecognized"))))

(defn xact-lock!
  "Acquire an exclusive transaction-level advisory lock.
  Uses PostgreSQL's pg_advisory_xact_lock; falls back to
  GaussDB native mechanism if unavailable."
  [conn n]
  (let [n (xact-check-param n)]
    (try
      (exec-one! conn [sql:pg-xact-lock n])
      (catch Throwable e
        (if (advisory-lock-fn-not-found? e)
          (exec-one! conn [sql:gauss-xact-lock n])
          (throw e))))
    true))

(defn xact-try-lock!
  "Try to acquire an exclusive transaction-level advisory lock.
  Uses PostgreSQL's pg_try_advisory_xact_lock; falls back to
  GaussDB native mechanism if unavailable."
  [conn n]
  (let [n (xact-check-param n)]
    (try
      (:lock (exec-one! conn [sql:pg-xact-try-lock n]))
      (catch Throwable e
        (if (advisory-lock-fn-not-found? e)
          (:lock (exec-one! conn [sql:gauss-xact-try-lock n]))
          (throw e))))))

(defn sql-exception?
  [cause]
  (instance? java.sql.SQLException cause))

(defn connection-error?
  [cause]
  (and (sql-exception? cause)
       (contains? #{"08003" "08006" "08001" "08004"}
                  (.getSQLState ^java.sql.SQLException cause))))

(defn serialization-error?
  [cause]
  (and (sql-exception? cause)
       (= "40001" (.getSQLState ^java.sql.SQLException cause))))

(defn duplicate-key-error?
  [cause]
  (and (sql-exception? cause)
       (= "23505" (.getSQLState ^java.sql.SQLException cause))))


(extend-protocol jdbc.prepare/SettableParameter
  clojure.lang.Keyword
  (set-parameter [^clojure.lang.Keyword v ^PreparedStatement s ^long i]
    (.setObject s i ^String (d/name v))))
