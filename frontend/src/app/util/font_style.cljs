;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.font-style
  (:require
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]))

(def ^:private style-name->tr-key
  {"hairline"        "workspace.fonts.style.hairline"
   "thin"            "workspace.fonts.style.thin"
   "extra light"     "workspace.fonts.style.extra-light"
   "ultra light"     "workspace.fonts.style.extra-light"
   "light"           "workspace.fonts.style.light"
   "regular"         "workspace.fonts.style.regular"
   "normal"          "workspace.fonts.style.regular"
   "medium"          "workspace.fonts.style.medium"
   "semi bold"       "workspace.fonts.style.semi-bold"
   "demi bold"       "workspace.fonts.style.semi-bold"
   "bold"            "workspace.fonts.style.bold"
   "extra bold"      "workspace.fonts.style.extra-bold"
   "ultra bold"      "workspace.fonts.style.extra-bold"
   "black"           "workspace.fonts.style.black"
   "heavy"           "workspace.fonts.style.black"
   "solid"           "workspace.fonts.style.black"
   "extra black"     "workspace.fonts.style.extra-black"
   "ultra black"     "workspace.fonts.style.extra-black"
   "italic"          "workspace.fonts.style.italic"
   "oblique"         "workspace.fonts.style.italic"
   "bold italic"     "workspace.fonts.style.bold-italic"
   "regular italic"  "workspace.fonts.style.regular-italic"
   "regular oblique" "workspace.fonts.style.regular-italic"})

;; Longest-first so "Bold Italic" / "Extra Light" win over "Italic" / "Light".
(def ^:private style-suffixes
  ["extra black italic"
   "ultra black italic"
   "extra bold italic"
   "ultra bold italic"
   "semi bold italic"
   "demi bold italic"
   "extra light italic"
   "ultra light italic"
   "regular italic"
   "medium italic"
   "light italic"
   "thin italic"
   "black italic"
   "bold italic"
   "hairline italic"
   "heavy italic"
   "extra black"
   "ultra black"
   "extra bold"
   "ultra bold"
   "semi bold"
   "demi bold"
   "extra light"
   "ultra light"
   "bolditalic"
   "blackitalic"
   "regular"
   "medium"
   "light"
   "thin"
   "black"
   "bold"
   "hairline"
   "heavy"
   "solid"
   "normal"
   "italic"
   "oblique"])

;; Execution time translation strings:
;;   (tr "workspace.fonts.style.black")
;;   (tr "workspace.fonts.style.bold")
;;   (tr "workspace.fonts.style.bold-italic")
;;   (tr "workspace.fonts.style.extra-black")
;;   (tr "workspace.fonts.style.extra-bold")
;;   (tr "workspace.fonts.style.extra-light")
;;   (tr "workspace.fonts.style.hairline")
;;   (tr "workspace.fonts.style.italic")
;;   (tr "workspace.fonts.style.light")
;;   (tr "workspace.fonts.style.medium")
;;   (tr "workspace.fonts.style.regular")
;;   (tr "workspace.fonts.style.regular-italic")
;;   (tr "workspace.fonts.style.semi-bold")
;;   (tr "workspace.fonts.style.thin")

(defn- normalize
  [value]
  (-> (str value)
      (str/lower)
      (str/replace #"[-_]+" " ")
      (str/replace #"(extra|ultra|semi|demi)\s*(light|bold|black)" "$1 $2")
      (str/replace #"(extra|ultra|semi|demi)(light|bold|black)" "$1 $2")
      (str/replace #"([a-z0-9])(italic|oblique)$" "$1 $2")
      (str/replace #"\s+" " ")
      (str/trim)))

(defn- translate-normalized
  [normalized original]
  (if-let [key (get style-name->tr-key normalized)]
    (tr key)
    (if-let [[_ weight rest] (re-matches #"^(\d+)\s+(.+)$" normalized)]
      (if-let [rest-key (get style-name->tr-key rest)]
        (str weight " " (tr rest-key))
        original)
      original)))

(defn localized-font-style
  "Translate English font style names (Regular, Bold, Italic, …) for display.
   Numeric weights such as 400/500 are left unchanged. Does not mutate stored data."
  [value]
  (if (or (nil? value) (str/blank? (str value)))
    value
    (translate-normalized (normalize value) (str value))))

(defn localized-typography-name
  "Translate a trailing font-style suffix in a typography asset name
   (e.g. \"Source Sans Pro Regular\" → \"Source Sans Pro 常规\").
   Custom names without a known suffix are returned unchanged."
  [name]
  (if (or (nil? name) (str/blank? name))
    name
    (let [s     (str name)
          lower (str/lower s)
          match (some (fn [suffix]
                        (cond
                          (= lower suffix)
                          ["" suffix]

                          (str/ends-with? lower (str " " suffix))
                          [(subs s 0 (- (count s) (count suffix))) suffix]

                          :else nil))
                      style-suffixes)]
      (if match
        (let [[prefix suffix] match]
          (str prefix (localized-font-style suffix)))
        s))))
