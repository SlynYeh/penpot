;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.fonts-test
  (:require
   [app.main.fonts :as fonts]
   [cljs.test :as t :include-macros true]))

(def sample-font
  {:id "sourcesanspro"
   :name "Source Sans Pro"
   :family "sourcesanspro"
   :variants
   [{:id "200"
     :name "200"
     :weight "200"
     :style "normal"
     :suffix "extralight"
     :ttf-url "sourcesanspro-extralight.ttf"}
    {:id "200italic"
     :name "200 Italic"
     :weight "200"
     :style "italic"
     :suffix "extralightitalic"
     :ttf-url "sourcesanspro-extralightitalic.ttf"}
    {:id "300"
     :name "300"
     :weight "300"
     :style "normal"
     :suffix "light"
     :ttf-url "sourcesanspro-light.ttf"}
    {:id "300italic"
     :name "300 Italic"
     :weight "300"
     :style "italic"
     :suffix "lightitalic"
     :ttf-url "sourcesanspro-lightitalic.ttf"}
    {:id "regular"
     :name "400"
     :weight "400"
     :style "normal"
     :ttf-url "sourcesanspro-regular.ttf"}
    {:id "italic"
     :name "400 Italic"
     :weight "400"
     :style "italic"
     :ttf-url "sourcesanspro-italic.ttf"}
    {:id "bold"
     :name "700"
     :weight "700"
     :style "normal"
     :ttf-url "sourcesanspro-bold.ttf"}
    {:id "bolditalic"
     :name "700 Italic"
     :weight "700"
     :style "italic"
     :ttf-url "sourcesanspro-bolditalic.ttf"}
    {:id "black"
     :name "900"
     :weight "900"
     :style "normal"
     :ttf-url "sourcesanspro-black.ttf"}
    {:id "blackitalic"
     :name "900 Italic"
     :weight "900"
     :style "italic"
     :ttf-url "sourcesanspro-blackitalic.ttf"}]
   :backend :builtin})

(t/deftest find-closest-weight-variant-test
  (t/testing "finds exact weight match"
    (let [result (fonts/find-closest-variant sample-font "400" nil)]
      (t/is (= "400" (:weight result)))
      (t/is (= "normal" (:style result)))))

  (t/testing "finds exact weight match with style"
    (let [result (fonts/find-closest-variant sample-font "400" "italic")]
      (t/is (= "400" (:weight result)))
      (t/is (= "italic" (:style result)))))

  (t/testing "chooses higher weight when exactly between two weights"
    (let [result (fonts/find-closest-variant sample-font "350" nil)]
      (t/is (= "400" (:weight result)))))

  (t/testing "finds exact weight match with style"
    (let [result (fonts/find-closest-variant sample-font "350" "italic")]
      (t/is (= "400" (:weight result)))
      (t/is (= "italic" (:style result)))))

  (t/testing "finds closest weight below minimum available"
    (let [result (fonts/find-closest-variant sample-font "0" nil)]
      (t/is (= "200" (:weight result)))))

  (t/testing "finds closest weight above maximum available"
    (let [result (fonts/find-closest-variant sample-font "1000" nil)]
      (t/is (= "900" (:weight result)))))

  (t/testing "keeps the closest weight match when style is not found"
    (let [font {:id "sourcesanspro"
                :name "Source Sans Pro"
                :family "sourcesanspro"
                :variants
                [{:id "200italic"
                  :name "200 Italic"
                  :weight "200"
                  :style "italic"
                  :suffix "extralightitalic"
                  :ttf-url "sourcesanspro-extralightitalic.ttf"}
                 {:id "300"
                  :name "300"
                  :weight "300"
                  :style "normal"
                  :suffix "light"
                  :ttf-url "sourcesanspro-light.ttf"}
                 {:id "300italic"
                  :name "300 Italic"
                  :weight "300"
                  :style "italic"
                  :suffix "lightitalic"
                  :ttf-url "sourcesanspro-lightitalic.ttf"}]}
          result (fonts/find-closest-variant font "200" nil)]
      (t/is (= "200" (:weight result)))
      (t/is (= "italic" (:style result))))))

;; FORK(字体列表只保留 Noto Sans SC): 字体选择列表（fonts 向量）只显示 Noto Sans SC
;; 与团队上传的自定义字体；builtin 的 sourcesanspro 与 google 字体仅注册在 fontsdb。
(t/deftest font-visible?-test
  (t/testing "whitelisted and custom fonts are visible"
    (t/is (true? (fonts/font-visible? {:id "notosanssc" :backend :builtin})))
    (t/is (true? (fonts/font-visible? {:id "custom-1" :backend :custom}))))

  (t/testing "builtin sourcesanspro and google fonts are hidden"
    (t/is (false? (fonts/font-visible? sample-font)))
    (t/is (false? (fonts/font-visible? {:id "gfont-noto-sans-sc" :backend :google})))))

;; FORK(放开字重): Noto Sans SC 注册 100–900 全部字重，400 保留 "regular" id 兼容旧文件。
(t/deftest notosanssc-weight-variants-test
  (let [font (fonts/get-font-data "notosanssc")]
    (t/testing "registers all weights 100-900 as normal variants"
      (t/is (= #{"100" "200" "300" "400" "500" "600" "700" "800" "900"}
               (set (map :weight (:variants font)))))
      (t/is (every? #(= "normal" (:style %)) (:variants font))))
    (t/testing "weight 400 keeps the legacy regular variant id"
      (t/is (= "regular" (:id (fonts/get-variant font "regular"))))
      (t/is (= "400" (:weight (fonts/get-variant font "regular")))))
    (t/testing "numeric variant ids resolve to their weight"
      (t/is (= "700" (:weight (fonts/get-variant font "700"))))
      (t/is (= "100" (:weight (fonts/get-variant font "100")))))))

(t/deftest fonts-list-only-contains-visible-fonts-test
  (t/testing "the selection list excludes sourcesanspro but keeps notosanssc registered"
    (let [visible-ids (set (map :id @fonts/fonts))]
      (t/is (contains? visible-ids "notosanssc"))
      (t/is (not (contains? visible-ids "sourcesanspro")))
      ;; sourcesanspro 仍注册在 fontsdb（旧文件渲染依赖）
      (t/is (some? (fonts/get-font-data "sourcesanspro"))))))
