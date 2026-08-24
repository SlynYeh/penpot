;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.redis
  (:require
   ["ioredis" :as redis]
   [app.common.data.macros :as dm]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.config :as cf]
   [cuerdas.core :as str]))

(l/set-level! :trace)

(def client (atom nil))

(defn- url-decode
  [s]
  (when s
    ;; Equivalent to java.net.URLDecoder (application/x-www-form-urlencoded):
    ;; decode %xx sequences and turn '+' into space.
    (js/decodeURIComponent (.replace s "+" "%20"))))

(defn- parse-sentinel-uri
  "Parse a `redis-sentinel://` URI into an ioredis options object.

  ioredis v5 removed URL-based sentinel support, so we parse it
  manually following the same format accepted by the backend
  (see backend/src/app/redis.clj create-redis-uri):

      redis-sentinel://[password@]host1[:port],host2[:port],.../db#master"
  [uri-str]
  (let [body (subs uri-str (count "redis-sentinel://"))
        [body master] (if-let [i (str/index-of body "#")]
                        [(subs body 0 i) (subs body (inc i))]
                        [body "mymaster"])
        [body db] (if-let [i (str/index-of body "/")]
                    [(subs body 0 i) (js/parseInt (subs body (inc i)) 10)]
                    [body 0])
        [password host-part] (if-let [i (str/index-of body "@")]
                               [(url-decode (subs body 0 i)) (subs body (inc i))]
                               [nil body])
        srv (fn [s]
              (let [[h p] (str/split s #":" 2)]
                {:host h
                 :port (js/parseInt (or p "26379") 10)}))]
    (clj->js
     (cond-> {:sentinels (mapv srv (str/split host-part #","))
              :name      master
              :db        db}
       (some? password)
       (assoc :password password)))))

(defn- create-client
  [uri]
  (let [opts   (if (str/starts-with? uri "redis-sentinel")
                 (parse-sentinel-uri uri)
                 uri)
        ^js client (new redis/default opts)]
    (.on client "connect"
         (fn [] (l/info :hint "redis connection established" :uri uri)))
    (.on client "error"
         (fn [cause] (l/error :hint "error on redis connection" :cause cause)))
    (.on client "close"
         (fn [] (l/warn :hint "connection closed")))
    (.on client "reconnect"
         (fn [ms] (l/warn :hint "reconnecting to redis" :ms ms)))
    (.on client "end"
         (fn [] (l/warn :hint "client ended, no more connections will be attempted")))
    client))

(defn init
  []
  (swap! client (fn [prev]
                  (when prev (.disconnect ^js prev))
                  (create-client (cf/get :redis-uri)))))


(defn stop
  []
  (swap! client (fn [client]
                  (when client (.quit ^js client))
                  nil)))

(def ^:private tenant (cf/get :tenant))

(defn pub!
  [topic payload]
  (let [payload (if (map? payload) (t/encode-str payload) payload)
        topic   (dm/str tenant "." topic)]
    (when-let [client @client]
      (.publish ^js client topic payload))))
