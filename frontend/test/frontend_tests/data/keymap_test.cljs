;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.keymap-test
  (:require
   [app.config :as cf]
   [app.main.data.keymap :as km]
   [app.main.data.shortcuts :as ds]
   [cljs.test :as t]))

(t/deftest tabs-are-nonempty-and-unique
  (t/is (seq km/tabs))
  (let [ids (map :id km/tabs)]
    (t/is (= (count ids) (count (distinct ids)))))
  (doseq [tab km/tabs]
    (t/is (keyword? (:id tab)) (:id tab))
    (t/is (pos? (count (:shortcuts tab))) (:id tab))))

(t/deftest all-shortcuts-resolve
  (doseq [tab km/tabs
          kw (:shortcuts tab)]
    (t/is (some? (km/get-entry kw)) kw)
    (t/is (some? (km/get-display-command kw)) kw)
    (t/is (seq (km/display-chars kw)) kw)))

(t/deftest tab-columns-partition
  (t/is (= [4 3] (mapv count (km/tab-columns {:shortcuts (range 7)}))))
  (t/is (= [4 4] (mapv count (km/tab-columns {:shortcuts (range 8)}))))
  (t/is (= [1] (mapv count (km/tab-columns {:shortcuts [:a]}))))
  (t/is (= (range 7) (vec (apply concat (km/tab-columns {:shortcuts (range 7)}))))))

(t/deftest convert-char-platform
  (with-redefs [cf/check-platform? (constantly true)]
    (t/is (= "⌘" (km/convert-char "command")))
    (t/is (= "⇧" (km/convert-char "shift")))
    (t/is (= "⌥" (km/convert-char "alt")))
    (t/is (= "⎋" (km/convert-char "escape")))
    (t/is (= "z" (km/convert-char "z"))))
  (with-redefs [cf/check-platform? (constantly false)]
    (t/is (= "command" (km/convert-char "command")))
    (t/is (= "ctrl" (km/convert-char "ctrl")))
    (t/is (= "escape" (km/convert-char "escape"))))
  ;; 方向键/加号替换与平台无关
  (t/is (= ds/up-arrow (km/convert-char "up")))
  (t/is (= "+" (km/convert-char "plus"))))

(t/deftest display-chars-first-alternative
  (let [vec-kw (some (fn [kw]
                       (let [cmd (km/get-display-command kw)]
                         (when (vector? cmd) kw)))
                     (mapcat :shortcuts km/tabs))]
    (t/is (some? vec-kw))
    (let [cmd (km/get-display-command vec-kw)]
      (t/is (= (ds/split-sc (first cmd)) (km/display-chars vec-kw))))))
