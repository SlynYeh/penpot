;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.util-cache-test
  (:require
   [app.util.cache :as c]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]))

(def ^:private max-entries 24)

(defn- wait-next-ms!
  "Busy-waits until the millisecond clock ticks, so consecutive cache
   entries get strictly increasing :created-at timestamps."
  []
  (let [t0 (js/Date.now)]
    (while (= (js/Date.now) t0))))

(defn- cache-one!
  [k]
  (rx/subs! identity (c/with-cache {:key k :max-age 60000} (rx/of k)))
  (wait-next-ms!))

(t/deftest with-cache-evicts-oldest-entries-over-the-limit
  (reset! c/cache {})
  (reset! c/pending {})
  (dotimes [i 30]
    (cache-one! (str "key-" i)))
  (let [cache @c/cache]
    (t/is (<= (count cache) max-entries))
    ;; the newest entry survives, the oldest is evicted
    (t/is (contains? cache "key-29"))
    (t/is (not (contains? cache "key-0")))))

(t/deftest with-cache-hits-within-max-age
  (reset! c/cache {})
  (reset! c/pending {})
  (let [hits (volatile! 0)]
    (rx/subs! #(vreset! hits %) (c/with-cache {:key "hit" :max-age 60000} (rx/of :first)))
    ;; second call with the same key must be served from the cache: the
    ;; observable is never subscribed (would emit :second)
    (rx/subs! #(vreset! hits %) (c/with-cache {:key "hit" :max-age 60000} (rx/of :second)))
    (t/is (= :first @hits))))
