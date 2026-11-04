;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.cache
  (:require
   [app.common.time :as ct]
   [beicon.v2.core :as rx]))

(defonce cache (atom {}))
(defonce pending (atom {}))

;; Entries can hold whole font TTFs or images as base64 data-URI strings;
;; without an upper bound the cache grows monotonically for the lifetime
;; of the tab (max-age only gates reuse, it never removes entries).
(def ^:private max-entries 24)

(defn- evict-oldest!
  "Keeps the cache under `max-entries` by dropping the least recently
   created entries."
  [cache]
  (let [overflow (- (count cache) max-entries)]
    (if (pos? overflow)
      (let [oldest-keys (->> cache
                             (sort-by (fn [[_ {:keys [created-at]}]]
                                        (.getTime ^js created-at)))
                             (map first)
                             (take overflow))]
        (reduce dissoc cache oldest-keys))
      cache)))

(defn with-cache
  [{:keys [key max-age]} observable]
  (let [entry (get @cache key)
        pending-entry (get @pending key)
        age   (when entry
                (ct/diff-ms (:created-at entry) (ct/now)))]
    (cond
      (and (some? entry) (< age max-age))
      (rx/of (:data entry))

      (some? pending-entry)
      pending-entry

      :else
      (let [subject (rx/subject)]
        (do
          (swap! pending assoc key subject)

          (rx/subscribe
           observable

           (fn [data]
             (let [entry {:created-at (ct/now) :data data}]
               (swap! cache assoc key entry))
             (swap! cache evict-oldest!)
             (swap! pending dissoc key)
             (rx/push! subject data)
             (rx/end! subject))

           #(do
              (swap! pending dissoc key)
              (rx/error! subject %))))
        subject))))
