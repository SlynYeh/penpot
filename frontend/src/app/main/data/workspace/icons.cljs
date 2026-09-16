;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.icons
  "Resolve IconPark library components for the Icons sidebar.
  Placement uses the same instantiate-component path as Assets."
  (:require
   [app.common.geom.shapes.strokes :as gss]
   [app.common.math :as mth]
   [app.common.path-names :as cpn]
   [app.common.types.components-list :as ctkl]
   [app.main.data.workspace.iconpark-search :as ips]
   [cuerdas.core :as str]))

;; Keep in sync with library/playground/generate-iconpark.mjs
(def iconpark-file-name "IconPark")
(def iconpark-component-path "图标 / IconPark")
(def iconpark-uncategorized "其它")
(def all-categories-id "all")

;; Official IconPark Chinese site sidebar order
;; (iconpark.oceanengine.com/official).
(def iconpark-category-order
  ["基础"
   "安全 & 防护"
   "办公文档"
   "编辑"
   "表情"
   "测量 & 试验"
   "抽象图形"
   "电商财产"
   "动物"
   "多媒体音乐"
   "服饰"
   "符号标识"
   "工业"
   "化妆美妆"
   "几何图形"
   "建筑"
   "箭头方向"
   "交流沟通"
   "交通旅游"
   "界面组件"
   "链接"
   "美颜调整"
   "母婴儿童"
   "能源 & 生命"
   "品牌"
   "生活"
   "时间日期"
   "食品"
   "手势动作"
   "数据"
   "数据图表"
   "体育运动"
   "天气"
   "星座"
   "医疗健康"
   "硬件"
   "用户人名"
   "游戏"
   "其它"])

(def ^:private iconpark-category-rank
  (into {} (map-indexed (fn [idx category] [category idx]) iconpark-category-order)))

(defn iconpark-component?
  "True when a component lives under the IconPark path."
  [component]
  (let [path (or (:path component) "")]
    (or (= path iconpark-component-path)
        (str/starts-with? path (str iconpark-component-path " /")))))

(defn icon-component?
  "True when a library component belongs in the Icons panel."
  [component]
  (iconpark-component? component))

(defn- path-segments
  [component]
  (let [path   (or (:path component) "")
        prefix (str iconpark-component-path " / ")]
    (when (str/starts-with? path prefix)
      (cpn/split-path (subs path (count prefix))))))

(defn icon-category
  "Official IconPark categoryCN stored as the first path segment under IconPark."
  [component]
  (or (first (path-segments component))
      iconpark-uncategorized))

(defn icon-theme
  "Outline/filled theme stored as the last path segment, defaulting to outline."
  [component]
  (case (last (path-segments component))
    "filled" :filled
    :outline))

(defn iconpark-library?
  "True when a file is the IconPark library (by name or by containing only IconPark components)."
  [file]
  (boolean
   (when file
     (or (= iconpark-file-name (:name file))
         (let [components (seq (ctkl/components-seq (:data file)))]
           (and components
                (every? iconpark-component? components)))))))

(defn assets-libraries
  "Libraries that should appear in the Assets panel. IconPark is listed in Icons instead."
  [files]
  (into [] (remove iconpark-library?) files))

(defn- include-in-icons?
  [file component]
  (or (iconpark-component? component)
      (= iconpark-file-name (:name file))))

(defn icon-catalog-file?
  "True when this file is the dedicated IconPark catalog, not leftover copies
  imported into a working file."
  [file]
  (= iconpark-file-name (:name file)))

(defn icon-catalog-snapshot
  "Keep only dedicated IconPark catalog files so workspace edits do not
   invalidate the Icons panel."
  [files]
  (into {}
        (keep (fn [[id file]]
                (when (icon-catalog-file? file)
                  [id (select-keys file [:id :name :data])])))
        (or files {})))

(defn icon-face-active?
  "True when this outline/filled face should render its glyph."
  [theme has-outline? has-filled? face]
  (let [theme (if (= theme :filled) :filled :outline)]
    (or (= theme face)
        (and (= face :outline)
             (= theme :filled)
             (not has-filled?))
        (and (= face :filled)
             (= theme :outline)
             (not has-outline?)))))

(defn- icon-entry-identity
  [entry]
  [(str/lower (or (:name (:component entry)) ""))
   (icon-theme (:component entry))
   (icon-category (:component entry))])

(defn- prefer-catalog-entry
  [entries]
  (or (first (remove :is-local entries))
      (first entries)))

(defn libraries-icon-entries
  "IconPark components from the dedicated IconPark catalog.
  Local copies left in a working file after using icons are omitted."
  [libraries current-file-id]
  (->> (vals libraries)
       (filter icon-catalog-file?)
       (mapcat (fn [file]
                 (let [file-id (:id file)]
                   (->> (ctkl/components-seq (:data file))
                        (filter #(include-in-icons? file %))
                        (map (fn [component]
                               {:file-id   file-id
                                :is-local  (= file-id current-file-id)
                                :component component}))))))
       (group-by icon-entry-identity)
       (map (fn [[_ items]]
              (prefer-catalog-entry items)))
       (sort-by (comp str/lower :name :component))
       vec))

(defn- category-sort-key
  [category]
  [(get iconpark-category-rank category 999)
   (str/lower category)])

(defn group-icon-entries
  "Group icon entries by official IconPark category, then by name."
  [entries]
  (->> entries
       (group-by (comp icon-category :component))
       (map (fn [[category items]]
              {:category category
               :entries  (vec (sort-by (comp str/lower :name :component) items))}))
       (sort-by (comp category-sort-key :category))
       vec))

(defn icon-categories
  "Official IconPark categories, plus any extra categories present in entries."
  [entries]
  (let [present (into #{} (map (comp icon-category :component)) entries)
        extras  (->> present
                     (remove #(contains? iconpark-category-rank %))
                     (sort-by category-sort-key))]
    (into [] (concat iconpark-category-order extras))))

(defn icon-search-text
  "Name plus official IconPark tags/english aliases for associative search."
  [component]
  (let [name  (or (:name component) "")
        extra (get ips/index name "")]
    (str/lower (str name " " extra))))

(defn icon-matches-search?
  [component query]
  (let [query (some-> query str/trim str/lower)]
    (or (str/blank? query)
        (str/includes? (icon-search-text component) query))))

(defn filter-icon-entries
  "Filter icon entries by category and associative name/tag search.
  Pass `:theme` to keep only outline or filled; omit it to keep both."
  [entries {:keys [search category theme]}]
  (into []
        (comp
         (filter (fn [entry]
                   (or (nil? theme)
                       (= theme (icon-theme (:component entry))))))
         (filter (fn [entry]
                   (or (nil? category)
                       (= category all-categories-id)
                       (= category (icon-category (:component entry))))))
         (filter (fn [entry]
                   (icon-matches-search? (:component entry) search))))
        entries))

(defn- icon-pair-key
  [entry]
  [(:file-id entry)
   (str/lower (or (:name (:component entry)) ""))])

(defn pair-icon-entries
  "Pair outline and filled variants of the same icon so both can stay mounted."
  [entries]
  (->> entries
       (group-by icon-pair-key)
       (map (fn [[_ items]]
              (let [by-theme (group-by (comp icon-theme :component) items)
                    outline  (first (:outline by-theme))
                    filled   (first (:filled by-theme))
                    sample   (or outline filled)]
                {:category (icon-category (:component sample))
                 :name     (:name (:component sample))
                 :outline  outline
                 :filled   filled})))
       (sort-by (comp str/lower :name))
       vec))

(defn group-icon-pairs
  "Group paired icons by official IconPark category, then by name."
  [pairs]
  (->> pairs
       (group-by :category)
       (map (fn [[category items]]
              {:category category
               :entries  (vec (sort-by (comp str/lower :name) items))}))
       (sort-by (comp category-sort-key :category))
       vec))

;; Tile size fills five columns in the default 318px left sidebar
;; (12px inline padding on each side, 12px column gap, plus the thin
;; scrollbar gutter reserved by `scrollbar-gutter: stable`). The app
;; rewrites scrollbars to `scrollbar-width: thin` (webkit fallback 12px).
;; Keep in sync with `--icon-tile-size` in icons.scss.
(def icon-sidebar-default-width 318)
(def icon-sidebar-max-width 500)
(def icon-sidebar-inline-padding 12)
(def icon-scrollbar-gutter 12)
(def icon-default-columns 5)
(def icon-column-gap 12)
(def icon-preview-rows 3)
(def icon-content-width-at-default
  (- icon-sidebar-default-width
     (* 2 icon-sidebar-inline-padding)
     icon-scrollbar-gutter))
(def icon-tile-size
  (/ (- icon-content-width-at-default
        (* (dec icon-default-columns) icon-column-gap))
     icon-default-columns))
(def icon-preview-limit
  (* icon-preview-rows icon-default-columns))

(defn icon-grid-columns
  "How many fixed-size icon columns fit in `available-width`.
   Keep `icon-tile-size` / `icon-column-gap` in sync with icons.scss."
  [available-width]
  (let [tile  icon-tile-size
        gap   icon-column-gap
        width (if (number? available-width) available-width 0)]
    (cond
      (< width tile)
      1

      :else
      (inc (int (/ (- width tile) (+ tile gap)))))))

(defn- track-size
  [part]
  (if-let [m (re-find #"([0-9]+(?:\.[0-9]+)?)" (str part))]
    (js/parseFloat (second m))
    0))

(defn count-grid-columns
  "Column count from a computed `grid-template-columns` value.
   Ignores collapsed 0-width auto-fill tracks."
  [template]
  (let [s (if (string? template) template "")]
    (if (or (str/blank? s) (= "none" s))
      1
      (->> (str/split s #"\s+")
           (remove str/blank?)
           (filter #(> (track-size %) 1))
           count
           (max 1)))))

(defn resolved-grid-columns
  "Visible icon columns from the live CSS template, with the width
   formula as fallback when the template is not available yet."
  [available-width template]
  (if (or (not (string? template))
          (str/blank? template)
          (= "none" template))
    (icon-grid-columns available-width)
    (count-grid-columns template)))

(def icon-max-columns
  "Most icon columns that fit in the max 500px left sidebar."
  (icon-grid-columns
   (- icon-sidebar-max-width
      (* 2 icon-sidebar-inline-padding)
      icon-scrollbar-gutter)))

(def icon-preview-fill-limit
  "Items to mount in each overview grid. CSS max-height clips to three
   rows, so this must cover three full rows at every column count up to
   `icon-max-columns`. Using the live column count would leave a short
   last row while the sidebar is being resized."
  (* icon-preview-rows icon-max-columns))

(defn preview-limit-for-columns
  "How many overview icons are visible at `columns` (three rows).
   Used for the 查看全部 affordance, not for how many tiles to mount."
  [columns]
  (* icon-preview-rows (max 1 (or columns 1))))

(defn preview-icon-entries
  "Keep the first `limit` paired icons for the overview grid.
   `limit` defaults to three rows of five columns (15)."
  ([entries]
   (preview-icon-entries entries icon-preview-limit))
  ([entries limit]
   (into [] (take (max 1 (or limit icon-preview-limit))) entries)))

(defn icon-category-overflows?
  "True when a category has more icons than the overview preview."
  ([entries]
   (icon-category-overflows? entries icon-preview-limit))
  ([entries limit]
   (> (count entries) (max 1 (or limit icon-preview-limit)))))

(defn find-icon-group
  "Return the grouped category named `category`, or nil."
  [groups category]
  (some (fn [group]
          (when (= category (:category group))
            group))
        groups))

(defn iconpark-instance-root?
  "True when this shape is the root copy of an IconPark component."
  [shape libraries]
  (and (true? (:component-root shape))
       (iconpark-component?
        (get-in libraries [(:component-file shape)
                           :data
                           :components
                           (:component-id shape)]))))

(defn icon-stroke-scale
  "Scale factor that keeps stroke width proportional to the instance size."
  [native-size target-size]
  (if (and (number? native-size)
           (pos? native-size)
           (number? target-size)
           (pos? target-size))
    (/ target-size native-size)
    1))

(defn scale-icon-strokes
  [shape scale]
  (cond
    (or (nil? scale)
        (mth/close? scale 1)
        (empty? (:strokes shape)))
    shape

    :else
    (gss/update-strokes-width shape scale)))

(def default-icon-size 24)
(def icon-size-presets [12 14 16 20 24 32 36 48 100])
(def icon-glyph-color "#495e74")
;; Shared IconPark library is authored at 48px / 2px (= 24px / 1px).
;; Keep in sync with library/playground/generate-iconpark.mjs.
(def icon-library-size 48)
(def icon-library-stroke-width 2)
;; Sidebar preview and dropped instances use 1.5px at 24px.
(def icon-canvas-stroke-width 1.5)
(def icon-instance-stroke-scale
  (/ (/ icon-canvas-stroke-width default-icon-size)
     (/ icon-library-stroke-width icon-library-size)))

(defn- recolor-paint
  [paint attr color]
  (let [current (get paint attr)
        value   (some-> current str/trim str/lower)]
    (cond-> paint
      (and (string? current)
           (not (str/blank? value))
           (not= "none" value)
           (not= "transparent" value)
           (not (contains? #{"#fff" "#ffffff" "#ffffffff"} value)))
      (assoc attr color))))

(defn recolor-icon-shape
  "Replace solid fill/stroke colors so dropped icons match the sidebar glyph."
  [shape color]
  (cond-> shape
    (seq (:fills shape))
    (update :fills (fn [fills]
                     (mapv #(recolor-paint % :fill-color color) fills)))

    (seq (:strokes shape))
    (update :strokes (fn [strokes]
                       (mapv #(recolor-paint % :stroke-color color) strokes)))))

(defn icon-stroke-width-at-size
  "Stroke width that matches the 24px / 1.5px rule at `size`."
  [size]
  (* icon-canvas-stroke-width (icon-stroke-scale default-icon-size size)))

(defn apply-icon-canvas-strokes
  "Set instance strokes to the canvas spec for `size` without editing the library."
  [shape size]
  (let [target (icon-stroke-width-at-size (or size default-icon-size))]
    (if (empty? (:strokes shape))
      shape
      (update shape :strokes
              (fn [strokes]
                (mapv #(assoc % :stroke-width target) strokes))))))

(defn style-dropped-icon-shape
  "Recolor a copy and set its strokes to the 24px / 1.5px canvas spec."
  ([shape color]
   (style-dropped-icon-shape shape color default-icon-size))
  ([shape color size]
   (-> shape
       (recolor-icon-shape color)
       (apply-icon-canvas-strokes size))))

(defn format-icon-size
  [n]
  (str n "px"))

(defn- without-px-suffix
  [s]
  (if (str/ends-with? (str/lower s) "px")
    (subs s 0 (- (count s) 2))
    s))

(defn parse-icon-size
  "Parse a dropdown or custom size value into a positive pixel number."
  [value]
  (let [n (cond
            (number? value)
            value

            (string? value)
            (-> value str/trim without-px-suffix str/trim js/parseFloat)

            :else
            js/NaN)]
    (when (and (number? n)
               (pos? n)
               (<= n 4096)
               (not (js/isNaN n)))
      n)))
