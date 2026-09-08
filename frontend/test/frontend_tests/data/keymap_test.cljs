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
    (if (km/gesture? kw)
      (t/is (= 2 (count (km/gesture-parts kw))) kw)
      (do
        (t/is (some? (km/get-entry kw)) kw)
        (t/is (some? (km/get-display-command kw)) kw)
        (t/is (seq (km/display-chars kw)) kw)))))

(t/deftest tab-columns-partition
  (t/is (= [4 3] (mapv count (km/tab-columns {:shortcuts (range 7)}))))
  (t/is (= [4 4] (mapv count (km/tab-columns {:shortcuts (range 8)}))))
  (t/is (= [1] (mapv count (km/tab-columns {:shortcuts [:a]}))))
  (t/is (= (range 7) (vec (apply concat (km/tab-columns {:shortcuts (range 7)})))))
  (t/is (= [2 2 2 2] (mapv count (km/tab-columns {:shortcuts (range 8) :max-items 2}))))
  (t/is (= [2 1] (mapv count (km/tab-columns {:shortcuts [:a :b :c] :max-items 2})))))

(t/deftest convert-char-platform
  (with-redefs [cf/check-platform? (constantly true)]
    (t/is (= "⌘" (km/convert-char "command")))
    (t/is (= "⇧" (km/convert-char "shift")))
    (t/is (= "⌥" (km/convert-char "alt")))
    (t/is (= "⎋" (km/convert-char "escape")))
    (t/is (= "⎋" (km/convert-char "esc")))
    (t/is (= "Z" (km/convert-char "z"))))
  (with-redefs [cf/check-platform? (constantly false)]
    (t/is (= "command" (km/convert-char "command")))
    (t/is (= "ctrl" (km/convert-char "ctrl")))
    (t/is (= "Esc" (km/convert-char "escape")))
    (t/is (= "G" (km/convert-char "g"))))
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

(t/deftest display-alternatives
  (t/is (= [["a"] ["b"]] (km/display-alternatives :draw-frame))) ; 向量按字母序 → A / B
  (t/is (= [["v"]] (km/display-alternatives :move)))             ; 字符串 command → 单组
  (t/is (nil? (km/display-alternatives :click-through)))         ; 手势 kw 无 command
  (t/is (= ["b"] (km/display-chars :draw-frame))))               ; display-chars 仍只取 first

(t/deftest gesture-parts-platform
  (with-redefs [cf/check-platform? (constantly true)]
    (t/is (= ["⌘" "点击"] (km/gesture-parts :click-through)))
    (t/is (= ["⇧" "点击"] (km/gesture-parts :multi-select)))
    (t/is (= ["空格" "拖动"] (km/gesture-parts :drag-canvas)))
    (t/is (= ["⌘" "滚轮"] (km/gesture-parts :zoom-canvas)))
    (t/is (= ["⌥" "悬停目标图层"] (km/gesture-parts :measure-distance))))
  (with-redefs [cf/check-platform? (constantly false)]
    (t/is (= ["Ctrl" "点击"] (km/gesture-parts :click-through)))
    (t/is (= ["Shift" "点击"] (km/gesture-parts :multi-select)))
    (t/is (= ["空格" "拖动"] (km/gesture-parts :drag-canvas)))
    (t/is (= ["Ctrl" "滚轮"] (km/gesture-parts :zoom-canvas)))
    (t/is (= ["Alt" "悬停目标图层"] (km/gesture-parts :measure-distance))))
  (t/is (nil? (km/gesture-parts :move))))

(t/deftest important-tab-layout
  (let [important (some #(when (= :important (:id %)) %) km/tabs)]
    (t/is (= 2 (:max-items important)))
    (t/is (= [[:click-through :multi-select :drag-canvas :draw-text]
              [:escape :zoom-canvas :draw-frame :group]]
             (apply mapv vector (km/tab-columns important))))))
