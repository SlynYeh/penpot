;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.font-style
  (:require
   [app.common.data :as d]
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

(def weight-style-names
  "CSS font-weight → dropdown style name."
  {100 "Thin"
   200 "ExtraLight"
   300 "Light"
   400 "Regular"
   500 "Medium"
   600 "SemiBold"
   700 "Bold"
   800 "ExtraBold"
   900 "Black"})

(def custom-font-style-labels
  "Canonical custom-font style dropdown labels, in display order."
  ["Light" "Regular" "Medium" "SemiBold" "Bold"])

(def custom-style-weights
  "Canonical custom-font style → CSS font-weight."
  {"Light" 300
   "Regular" 400
   "Medium" 500
   "SemiBold" 600
   "Bold" 700})

(defn font-weight-display-label
  "Thin-100, ExtraLight-200, Regular-400, … Unmapped weights stay numeric."
  [weight]
  (let [n (d/parse-integer weight)]
    (when (some? n)
      (if-let [style-name (get weight-style-names n)]
        (str style-name "-" n)
        (str n)))))

(defn custom-style-display-label
  "Light-300, Regular-400, Medium-500, SemiBold-600, Bold-700."
  [style]
  (if-let [weight (get custom-style-weights style)]
    (str style "-" weight)
    style))

(defn variant-style-label
  "Builtin variant dropdown label. Numeric weights become Thin-100;
   italic/oblique variants keep that suffix after the weight label."
  [variant]
  (let [weight (or (:weight variant) (:name variant))
        name   (str (or (:name variant) ""))
        base   (or (font-weight-display-label weight) name)]
    (if (re-find #"(?i)italic|oblique" name)
      (str base " Italic")
      base)))

(def ^:private excluded-style
  ::excluded)

(defn- style-from-blob
  [blob]
  (when (and (string? blob) (not (str/blank? blob)))
    (cond
      (re-find #"(?:extra|ultra)\s+(?:light|bold|black)" blob) excluded-style
      (re-find #"\b(?:hairline|thin|black|heavy|solid)\b" blob) excluded-style
      (re-find #"\b(?:semi|demi)\s+bold\b" blob) "SemiBold"
      (re-find #"\blight\b" blob) "Light"
      (re-find #"\bmedium\b" blob) "Medium"
      (re-find #"\bbold\b" blob) "Bold"
      (re-find #"\b(?:regular|normal)\b" blob) "Regular"
      :else nil)))

(defn- style-from-weight
  [weight]
  (case (d/parse-integer weight)
    300 "Light"
    400 "Regular"
    500 "Medium"
    600 "SemiBold"
    700 "Bold"
    nil))

(defn variant->custom-style
  "Map a font variant onto one of Light/Regular/Medium/SemiBold/Bold.
   Name wins; excluded names such as ExtraBold/Black do not fall through to
   weight. Weights outside 300–700 are ignored unless the name matches."
  [variant]
  (when (map? variant)
    (let [from-name (style-from-blob (normalize (:name variant)))]
      (cond
        (= excluded-style from-name) nil
        (some? from-name)            from-name
        :else                        (style-from-weight (:weight variant))))))

(defn- preferred-variant
  [current candidate]
  (cond
    (nil? current)
    candidate

    (and (not= "normal" (:style current))
         (= "normal" (:style candidate)))
    candidate

    :else
    current))

(defn custom-style-index
  "Canonical style label → preferred variant. Missing styles are omitted."
  [variants]
  (reduce
   (fn [index variant]
     (if-let [style (variant->custom-style variant)]
       (update index style preferred-variant variant)
       index))
   {}
   (or variants [])))

(defn custom-style-options
  "Select options for the custom-font style dropdown. Only styles that exist
   on the font are included; Regular is not injected as a default."
  [style-index]
  (into []
        (keep (fn [label]
                (when (contains? style-index label)
                  {:value label
                   :key   label
                   :label (custom-style-display-label label)})))
        custom-font-style-labels))

(defn variant-id->custom-style
  [variants variant-id]
  (when (and (some? variant-id)
             (not (#{:multiple "mixed"} variant-id)))
    (some-> (d/seek #(= (:id %) variant-id) variants)
            variant->custom-style)))

(defn custom-bold-selected?
  "加粗 is selected when the mapped style is SemiBold or Bold."
  [style]
  (contains? #{"SemiBold" "Bold"} style))

(defn custom-bold-target-style
  "Toggle target: Bold when 加粗 is off, Regular when it is on."
  [style]
  (if (custom-bold-selected? style)
    "Regular"
    "Bold"))
