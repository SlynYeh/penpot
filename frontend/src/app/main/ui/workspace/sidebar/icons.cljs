;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.icons
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.types.file :as ctf]
   [app.common.types.path :as path]
   [app.main.data.workspace.icons :as dwi]
   [app.main.refs :as refs]
   [app.main.ui.components.search-bar :refer [search-bar*]]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.combobox :refer [combobox*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.product.empty-state :refer [empty-state*]]
   [app.main.ui.ds.tooltip :refer [tooltip*]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.workspace.sidebar.assets.components :as wsac]
   [app.util.dom.dnd :as dnd]
   [app.util.i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private icon-catalog-files
  (l/derived dwi/icon-catalog-snapshot refs/files =))

(defn- glyph-paint
  [shape]
  (let [fill   (some-> (:fills shape) first)
        stroke (some-> (:strokes shape) first)
        cap    (or (:stroke-cap-start stroke) "round")
        cap    (if (keyword? cap) (name cap) cap)]
    {:fill (or (:fill-color fill) "none")
     :fill-opacity (or (:fill-opacity fill) 1)
     :stroke (or (:stroke-color stroke) "none")
     :stroke-opacity (or (:stroke-opacity stroke) 1)
     :stroke-width (or (:stroke-width stroke) 0)
     :stroke-linecap cap
     :stroke-linejoin "round"}))

(defn- path-d
  [shape]
  (try
    (let [content (:content shape)]
      (cond
        (nil? content)
        ""

        (path/content? content)
        (.toString content)

        :else
        (.toString (path/content content))))
    (catch :default _
      "")))

(mf/defc icon-glyph-shape*
  {::mf/private true}
  [{:keys [shape objects]}]
  (when-not (:hidden shape)
    (let [type (:type shape)]
      (if (or (= type :group) (= type :frame))
        [:g
         (for [child (cfh/get-immediate-children objects (:id shape) {:remove-hidden true})]
           [:> icon-glyph-shape*
            {:key (dm/str (:id child))
             :shape child
             :objects objects}])]
        ;; Rumext treats a runtime map as React children. SVG attrs must be
        ;; a literal hiccup map so they compile to DOM props.
        (let [paint           (glyph-paint shape)
              fill            (:fill paint)
              fill-opacity    (:fill-opacity paint)
              stroke          (:stroke paint)
              stroke-opacity  (:stroke-opacity paint)
              stroke-width    (if (= stroke "none")
                                (:stroke-width paint)
                                dwi/icon-canvas-stroke-width)
              stroke-linecap  (:stroke-linecap paint)
              stroke-linejoin (:stroke-linejoin paint)
              x               (or (:x shape) 0)
              y               (or (:y shape) 0)
              w               (or (:width shape) 0)
              h               (or (:height shape) 0)]
          (cond
            (or (= type :path) (= type :bool))
            [:path {:d (path-d shape)
                    :fill fill
                    :fill-opacity fill-opacity
                    :stroke stroke
                    :stroke-opacity stroke-opacity
                    :stroke-width stroke-width
                    :stroke-linecap stroke-linecap
                    :stroke-linejoin stroke-linejoin
                    :vector-effect "non-scaling-stroke"}]

            (= type :rect)
            [:rect {:x x
                    :y y
                    :width w
                    :height h
                    :rx (or (:rx shape) (:r1 shape) 0)
                    :fill fill
                    :fill-opacity fill-opacity
                    :stroke stroke
                    :stroke-opacity stroke-opacity
                    :stroke-width stroke-width
                    :stroke-linecap stroke-linecap
                    :stroke-linejoin stroke-linejoin
                    :vector-effect "non-scaling-stroke"}]

            (= type :circle)
            [:circle {:cx (+ x (/ w 2))
                      :cy (+ y (/ h 2))
                      :r (/ (min w h) 2)
                      :fill fill
                      :fill-opacity fill-opacity
                      :stroke stroke
                      :stroke-opacity stroke-opacity
                      :stroke-width stroke-width
                      :stroke-linecap stroke-linecap
                      :stroke-linejoin stroke-linejoin
                      :vector-effect "non-scaling-stroke"}]

            (= type :ellipse)
            [:ellipse {:cx (+ x (/ w 2))
                       :cy (+ y (/ h 2))
                       :rx (/ w 2)
                       :ry (/ h 2)
                       :fill fill
                       :fill-opacity fill-opacity
                       :stroke stroke
                       :stroke-opacity stroke-opacity
                       :stroke-width stroke-width
                       :stroke-linecap stroke-linecap
                       :stroke-linejoin stroke-linejoin
                       :vector-effect "non-scaling-stroke"}]

            :else
            nil))))))

(mf/defc icon-glyph*
  {::mf/private true
   ::mf/memo true}
  [{:keys [root-shape objects]}]
  (when (and (some? root-shape) (some? objects))
    (let [width  (or (:width root-shape) 48)
          height (or (:height root-shape) 48)]
      [:svg {:class (stl/css :icon-glyph)
             :viewBox (dm/str "0 0 " width " " height)
             :fill "none"
             :aria-hidden true}
       [:g {:transform (dm/str "translate(" (- (or (:x root-shape) 0)) " " (- (or (:y root-shape) 0)) ")")}
        [:> icon-glyph-shape*
         {:shape root-shape
          :objects objects}]]])))

(mf/defc icon-component-tile*
  {::mf/private true
   ::mf/memo true}
  [{:keys [file-id component is-local placement-size active visible]}]
  (let [on-drag-start
        (mf/use-fn
         (mf/deps file-id is-local placement-size)
         (fn [event]
           (let [file-data  (dm/get-in @refs/files [file-id :data])
                 shape-main (ctf/get-component-root file-data component)]
             (wsac/set-drag-data! {:file-id file-id
                                   :component component
                                   :shape shape-main
                                   :is-local is-local
                                   :placement-size placement-size
                                   :glyph-color dwi/icon-glyph-color})
             (dnd/set-data! event "penpot/component" true)
             (dnd/set-drag-image! event (dnd/invisible-image))
             (dnd/set-allowed-effect! event "move"))))

        preview
        (mf/with-memo [file-id component active visible]
          (when (dwi/show-icon-glyph? active visible)
            (let [file-data  (dm/get-in @refs/files [file-id :data])
                  root-shape (ctf/get-component-root file-data component)
                  container  (ctf/get-component-page file-data component)]
              [root-shape (:objects container)])))]

    [:> tooltip*
     {:content (:name component)
      :class (stl/css :icon-tile-tooltip)
      :placement "top"
      :delay 200}
     [:div {:class (stl/css :icon-tile)
            :draggable true
            :on-drag-start on-drag-start}
      (when preview
        [:> icon-glyph*
         {:root-shape (first preview)
          :objects (second preview)}])]]))

(mf/defc icon-pair-cell*
  {::mf/private true
   ::mf/memo true}
  [{:keys [outline filled placement-size theme]}]
  (let [cell-ref     (mf/use-ref nil)
        visible      (hooks/use-visible cell-ref :once? true)
        has-outline? (some? outline)
        has-filled?  (some? filled)]
    [:div {:ref cell-ref
           :class (stl/css :icon-cell)}
     (when outline
       [:div {:class (stl/css :icon-face :icon-face-outline)}
        [:> icon-component-tile*
         {:file-id (:file-id outline)
          :component (:component outline)
          :is-local (:is-local outline)
          :placement-size placement-size
          :active (dwi/icon-face-active? theme has-outline? has-filled? :outline)
          :visible visible}]])
     (when filled
       [:div {:class (stl/css :icon-face :icon-face-filled)}
        [:> icon-component-tile*
         {:file-id (:file-id filled)
          :component (:component filled)
          :is-local (:is-local filled)
          :placement-size placement-size
          :active (dwi/icon-face-active? theme has-outline? has-filled? :filled)
          :visible visible}]])]))

(mf/defc icon-grid*
  {::mf/private true}
  [{:keys [entries placement-size theme]}]
  [:div {:class (stl/css :icon-grid)
         :data-icon-grid "true"}
   (for [{:keys [name outline filled]} entries]
     [:> icon-pair-cell*
      {:key (dm/str (:file-id (or outline filled)) "-" name)
       :outline outline
       :filled filled
       :placement-size placement-size
       :theme theme}])])

(mf/defc icon-category-title*
  {::mf/private true}
  [{:keys [category total]}]
  [:div {:class (stl/css :icon-category-title)}
   [:span {:class (stl/css :icon-category-name)} category]
   [:span {:class (stl/css :icon-category-count)}
    (tr "workspace.sidebar.icons.category-count" (str total))]])

(mf/defc icon-category-group*
  {::mf/private true
   ::mf/memo true}
  [{:keys [category entries placement-size theme preview-limit on-view-all]}]
  (let [group-ref  (mf/use-ref nil)
        visible    (hooks/use-visible group-ref :once? true)
        total      (count entries)
        limit      (or preview-limit dwi/icon-preview-limit)
        preview    (dwi/preview-icon-entries entries dwi/icon-preview-fill-limit)
        overflows? (dwi/icon-category-overflows? entries limit)
        on-open    (mf/use-fn
                    (mf/deps category on-view-all)
                    (fn []
                      (on-view-all category)))]
    [:section {:ref group-ref
               :class (stl/css :icon-group)}
     [:div {:class (stl/css :icon-group-header)}
      [:> icon-category-title*
       {:category category
        :total total}]
      (when ^boolean overflows?
        [:button {:type "button"
                  :class (stl/css :view-all)
                  :on-click on-open}
         (tr "workspace.sidebar.icons.view-all")])]

     (if (dwi/mount-icon-preview-grid? visible)
       [:> icon-grid*
        {:entries preview
         :placement-size placement-size
         :theme theme}]
       [:div {:class (stl/css :icon-grid :icon-grid-placeholder)
              :data-icon-grid "true"
              :aria-hidden true}])]))

(mf/defc icon-category-detail*
  {::mf/private true
   ::mf/memo true}
  [{:keys [category entries placement-size theme on-back]}]
  [:section {:class (stl/css :icon-detail)
             :data-testid "icons-category-detail"}
   [:div {:class (stl/css :icon-detail-header)}
    [:> icon-button*
     {:variant "ghost"
      :aria-label (tr "labels.back")
      :icon i/arrow-left
      :on-click on-back}]
    [:> icon-category-title*
     {:category category
      :total (count entries)}]]
   (if (seq entries)
     [:div {:class (stl/css :icon-detail-body)}
      [:> icon-grid*
       {:entries entries
        :placement-size placement-size
        :theme theme}]]
     [:> empty-state*
      {:class (stl/css :empty)
       :icon i/search
       :text (tr "workspace.sidebar.icons.empty")}])])

(mf/defc icon-groups-panel*
  {::mf/private true
   ::mf/memo true}
  [{:keys [groups placement-size theme preview-limit on-view-all]}]
  (if (seq groups)
    [:div {:class (stl/css :icon-groups)
           :data-icon-groups "true"}
     (for [{:keys [category entries]} groups]
       [:> icon-category-group*
        {:key category
         :category category
         :entries entries
         :placement-size placement-size
         :theme theme
         :preview-limit preview-limit
         :on-view-all on-view-all}])]
    [:> empty-state*
     {:class (stl/css :empty)
      :icon i/search
      :text (tr "workspace.sidebar.icons.empty")}]))

(mf/defc icon-size-control*
  {::mf/private true}
  [{:keys [size on-change]}]
  (let [options
        (mf/with-memo []
          (mapv (fn [preset]
                  (let [label (dwi/format-icon-size preset)]
                    {:id label
                     :label label}))
                dwi/icon-size-presets))

        on-size-change
        (mf/use-fn
         (mf/deps on-change)
         (fn [value]
           (when-let [parsed (dwi/parse-icon-size value)]
             (on-change parsed))))]

    [:div {:class (stl/css :size-control)}
     [:> combobox*
      {:id "icon-placement-size"
       :class (stl/css :size-combobox)
       :options options
       :default-selected (dwi/format-icon-size size)
       :placeholder (tr "workspace.sidebar.icons.size.custom")
       :on-change on-size-change}]]))

(mf/defc icon-theme-toggle*
  {::mf/private true}
  [{:keys [theme on-change]}]
  (let [outline-label (tr "workspace.sidebar.icons.theme.outline")
        filled-label  (tr "workspace.sidebar.icons.theme.filled")
        on-outline    (mf/use-fn
                       (mf/deps on-change)
                       (fn []
                         (on-change :outline)))
        on-filled     (mf/use-fn
                       (mf/deps on-change)
                       (fn []
                         (on-change :filled)))]
    [:div {:class (stl/css-case :theme-toggle true
                                :theme-toggle-filled (= theme :filled))
           :role "group"
           :aria-label (tr "workspace.sidebar.icons.theme")}
     [:span {:class (stl/css :theme-slider)
             :aria-hidden true}]
     [:div {:class (stl/css :theme-buttons)}
      [:> tooltip*
       {:content outline-label
        :placement "bottom"}
       [:button {:type "button"
                 :class (stl/css :theme-button)
                 :aria-label outline-label
                 :aria-pressed (= theme :outline)
                 :on-click on-outline}
        [:span {:class (stl/css :theme-swatch :theme-swatch-outline)
                :aria-hidden true}]]]
      [:> tooltip*
       {:content filled-label
        :placement "bottom"}
       [:button {:type "button"
                 :class (stl/css :theme-button)
                 :aria-label filled-label
                 :aria-pressed (= theme :filled)
                 :on-click on-filled}
        [:span {:class (stl/css :theme-swatch :theme-swatch-filled)
                :aria-hidden true}]]]]]))

(defn- columns-from-container
  [node]
  (let [grid (.querySelector node "[data-icon-grid]")]
    (if (nil? grid)
      (dwi/icon-grid-columns (.-clientWidth node))
      (dwi/resolved-grid-columns
       (.-clientWidth grid)
       (.-gridTemplateColumns (js/getComputedStyle grid))))))

(mf/defc icons-toolbox*
  {::mf/memo true}
  []
  (let [current-file-id    (mf/use-ctx ctx/current-file-id)
        catalog            (mf/deref icon-catalog-files)
        entries            (mf/with-memo [catalog current-file-id]
                             (dwi/libraries-icon-entries catalog current-file-id))
        search-input*      (mf/use-state "")
        search-input       (deref search-input*)
        search             (hooks/use-debounce 300 search-input)
        theme*             (mf/use-state :outline)
        theme              (deref theme*)
        size*              (mf/use-state dwi/default-icon-size)
        size               (deref size*)
        columns*           (mf/use-state dwi/icon-default-columns)
        columns            (deref columns*)
        preview-limit      (dwi/preview-limit-for-columns columns)
        stage-ref          (mf/use-ref nil)
        expanded-category* (mf/use-state nil)
        expanded-category  (deref expanded-category*)
        visible            (mf/with-memo [entries search]
                             (dwi/filter-icon-entries entries {:search search}))
        groups             (mf/with-memo [visible]
                             (dwi/group-icon-pairs (dwi/pair-icon-entries visible)))
        expanded-group     (mf/with-memo [groups expanded-category]
                             (dwi/find-icon-group groups expanded-category))
        has-groups?        (boolean (seq groups))
        on-search-change   (mf/use-fn
                            (fn [value]
                              (reset! search-input* (or value ""))))
        on-theme-change    (mf/use-fn #(reset! theme* %))
        on-size-change     (mf/use-fn #(reset! size* %))
        on-view-all        (mf/use-fn #(reset! expanded-category* %))
        on-back            (mf/use-fn #(reset! expanded-category* nil))]

    (mf/with-effect [has-groups?]
      (when-let [stage (mf/ref-val stage-ref)]
        (let [aside (.getElementById js/document "left-sidebar-aside")
              sync-columns
              (fn []
                (when-let [current (mf/ref-val stage-ref)]
                  (let [node (or (.querySelector current "[data-icon-groups]") current)
                        next (columns-from-container node)]
                    (when (not= next (deref columns*))
                      (reset! columns* next)))))
              observer (js/ResizeObserver.
                        (fn [_]
                          (sync-columns)))]
          (.observe observer stage)
          (when (some? aside)
            (.observe observer aside))
          (sync-columns)
          (fn []
            (.disconnect observer)))))

    (mf/with-layout-effect [has-groups? columns]
      (when-let [stage (mf/ref-val stage-ref)]
        (let [node (or (.querySelector stage "[data-icon-groups]") stage)
              next (columns-from-container node)]
          (when (not= next (deref columns*))
            (reset! columns* next)))))

    [:article {:class (stl/css :icons-bar)
               :data-theme (name theme)
               :data-testid "icons-sidebar"}
     [:> search-bar*
      {:id "icon-search"
       :class (stl/css :search)
       :value search-input
       :placeholder (tr "workspace.sidebar.icons.search")
       :on-change on-search-change}]

     [:div {:class (stl/css :filter-row)}
      [:> icon-size-control*
       {:size size
        :on-change on-size-change}]
      [:> icon-theme-toggle*
       {:theme theme
        :on-change on-theme-change}]]

     [:div {:ref stage-ref
            :class (stl/css :icon-stage)}
      [:> icon-groups-panel*
       {:groups groups
        :placement-size size
        :theme theme
        :preview-limit preview-limit
        :on-view-all on-view-all}]
      (when expanded-category
        [:> icon-category-detail*
         {:category expanded-category
          :entries (or (:entries expanded-group) [])
          :placement-size size
          :theme theme
          :on-back on-back}])]]))
