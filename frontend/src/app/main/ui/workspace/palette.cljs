;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.palette
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.constants :refer [left-sidebar-default-width
                               right-sidebar-default-width]]
   [app.main.data.event :as ev]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.colors :as mdc]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as h]
   [app.main.ui.hooks.resize :as r]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.workspace.color-palette :refer [color-palette*]]
   [app.main.ui.workspace.color-palette-ctx-menu :refer [color-palette-ctx-menu*]]
   [app.main.ui.workspace.text-palette :refer [text-palette*]]
   [app.main.ui.workspace.text-palette-ctx-menu :refer [text-palette-ctx-menu*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.object :as obj]
   [app.util.timers :as ts]
   [goog.events :as events]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private ref:viewport
  (l/derived :vport refs/workspace-local))

(defn- calculate-palette-style
  [rulers? right-sidebar-size]
  (let [left-sidebar           (dom/get-element "left-sidebar-aside")
        left-sidebar-size      (-> (dom/get-data left-sidebar "width")
                                   (d/parse-integer))
        rulers-width           (if rulers? 22 0)
        min-left-sidebar-width left-sidebar-default-width
        palette-gap            4
        calculate-padding-left (+ rulers-width (or left-sidebar-size min-left-sidebar-width) palette-gap 1)
        ;; Both sidebars are user-resizable and the right one also widens on
        ;; the assistant tab, so the palette's right edge is measured from
        ;; the DOM instead of using a fixed padding.
        calculate-padding-right (+ (or right-sidebar-size right-sidebar-default-width) palette-gap)]

    #js {"paddingLeft" (dm/str calculate-padding-left "px")
         "paddingRight" (dm/str calculate-padding-right "px")}))

(defn default-palette-library
  "Shared library id to pre-select in the 调色盘 colour palette, or nil.
  Returns `cf/default-palette-library-id` only when it resolves to a
  library present in `libraries` that is not the current `file-id`;
  otherwise nil so the palette falls back to 最近颜色."
  [libraries file-id]
  (let [configured cf/default-palette-library-id]
    (when (and configured
               (not= configured file-id)
               (contains? libraries configured))
      configured)))

(mf/defc palette*
  [{:keys [layout on-change-size]}]
  (let [color-palette? (:colorpalette layout)
        text-palette?  (:textpalette layout)
        hide-palettes? (:hide-palettes layout)

        read-only?     (mf/use-ctx ctx/workspace-read-only?)
        container      (mf/use-ref nil)

        state*         (mf/use-state #(-> {:show-menu false}))
        state          (deref state*)
        show-menu?     (:show-menu state)

        selected       (h/use-shared-state mdc/colorpalette-selected-broadcast-key :recent)

        file-id          (mf/use-ctx ctx/current-file-id)
        libraries        (mf/deref refs/libraries)
        default-applied* (mf/use-var false)

        selected-text* (mf/use-state :file)
        selected-text  (deref selected-text*)

        on-select      (mf/use-fn #(reset! selected %))

        rulers?        (mf/deref refs/rulers?)
        vport          (mf/deref ref:viewport)
        vport-width    (get vport :width)

        right-sidebar-size* (mf/use-state nil)
        right-sidebar-size  (deref right-sidebar-size*)

        {:keys [on-pointer-down
                on-lost-pointer-capture
                on-pointer-move
                parent-ref
                size]}
        (r/use-resize-hook :palette 72 54 80 :y true :bottom on-change-size)

        on-resize
        (mf/use-fn
         (fn [_]
           (let [dom   (mf/ref-val container)
                 width (obj/get dom "clientWidth")]
             (swap! state* assoc :width width))))

        on-close-menu
        (mf/use-fn
         (fn [_]
           (swap! state* assoc :show-menu false)))

        on-select-palette
        (mf/use-fn
         (mf/deps on-select)
         (fn [event]
           (let [node  (dom/get-current-target event)
                 value (dom/get-attribute node "data-palette")]
             (on-select (if (or (= "file" value) (= "recent" value))
                          (keyword value)
                          (uuid/parse value))))))

        on-select-text-palette-menu
        (mf/use-fn
         (mf/deps on-select)
         (fn [lib]
           (if (or (nil? lib) (= :file lib))
             (reset! selected-text* :file)
             (reset! selected-text* (:id lib)))))

        toggle-palettes
        (mf/use-fn
         (fn [_]
           (r/set-resize-type! :top)
           (dom/add-class! (dom/get-element-by-class "color-palette") "fade-out-down")
           (st/emit! (-> (dw/toggle-layout-flag :hide-palettes)
                         (vary-meta assoc ::ev/origin "workspace-left-toolbar")))))

        on-select-color-palette
        (mf/use-fn
         (fn [event]
           (let [node (dom/get-current-target event)]
             (r/set-resize-type! :top)
             (dom/add-class! (dom/get-element-by-class "color-palette") "fade-out-down")
             (ts/schedule 300 #(st/emit! (dw/remove-layout-flag :hide-palettes)
                                         (dw/remove-layout-flag :textpalette)
                                         (-> (dw/toggle-layout-flag :colorpalette)
                                             (vary-meta assoc ::ev/origin "workspace-left-toolbar"))))
             (dom/blur! node))))

        on-select-text-palette
        (mf/use-fn
         (fn [event]
           (let [node (dom/get-current-target event)]
             (r/set-resize-type! :top)
             (dom/add-class! (dom/get-element-by-class "color-palette") "fade-out-down")
             (ts/schedule 300 #(st/emit! (dw/remove-layout-flag :hide-palettes)
                                         (dw/remove-layout-flag :colorpalette)
                                         (-> (dw/toggle-layout-flag :textpalette)
                                             (vary-meta assoc ::ev/origin "workspace-left-toolbar"))))
             (dom/blur! node))))

        any-palette?
        (or color-palette? text-palette?)

        size-classname
        (cond
          (<= size 64) (stl/css :small-palette)
          (<= size 72) (stl/css :mid-palette)
          (<= size 80) (stl/css :big-palette))]

    (mf/with-effect []
      (let [key1 (events/listen js/window "resize" on-resize)]
        #(events/unlistenByKey key1)))

    ;; Pre-select the configured shared library and force the palette bar
    ;; open once per mount (i.e. on every workspace/file/page entry) as soon
    ;; as the libraries are loaded. Guarded by `default-applied*` so manual
    ;; changes made later in the same session are never reset; the next
    ;; entry re-applies the default (this mirrors the assets sidebar's
    ;; `apply-default-asset-expansions` behaviour).
    (mf/with-effect [libraries read-only?]
      (let [default-lib (default-palette-library libraries file-id)]
        (when (and (not @default-applied*) default-lib (not ^boolean read-only?))
          (reset! default-applied* true)
          (reset! selected default-lib)
          (st/emit! (dw/remove-layout-flag :hide-palettes)
                    (dw/remove-layout-flag :textpalette)
                    (dw/toggle-layout-flag :colorpalette :force? true)))))

    (mf/with-layout-effect []
      (let [dom     (mf/ref-val parent-ref)
            width (obj/get dom "clientWidth")]
        (swap! state* assoc :width width)))

    ;; Track the right sidebar width so the palette never overlaps it: the
    ;; sidebar is resizable and widens when the assistant tab is selected.
    (mf/with-layout-effect []
      (when-let [node (dom/get-element "right-sidebar-aside")]
        (let [update-size
              (fn []
                (let [size (obj/get node "offsetWidth")]
                  (when (and (some? size) (not= size @right-sidebar-size*))
                    (reset! right-sidebar-size* size))))
              observer (js/ResizeObserver. update-size)]
          (update-size)
          (.observe observer node)
          (fn [] (.disconnect observer)))))

    [:section {:class (stl/css :palette-wrapper)
               :id "palette-wrapper"
               :style  (calculate-palette-style rulers? right-sidebar-size)
               :aria-label (tr "workspace.toolbar.palette-bar")
               :data-testid "palette"}
     (when-not ^boolean read-only?
       [:div {:ref parent-ref
              :class (dm/str size-classname " " (stl/css-case :palettes true
                                                              :wide any-palette?
                                                              :hidden-bts hide-palettes?))
              :style #js {"--height" (dm/str size "px")}}

        [:div {:class (stl/css :resize-area)
               :on-pointer-down on-pointer-down
               :on-lost-pointer-capture on-lost-pointer-capture
               :on-pointer-move on-pointer-move}]
        [:ul {:class (dm/str size-classname " " (stl/css-case :palette-btn-list true
                                                              :hidden-bts hide-palettes?))}
         [:li {:class (stl/css :palette-item)}
          [:button {:title (tr "workspace.toolbar.color-palette" (sc/get-tooltip :toggle-colorpalette))
                    :aria-label (tr "workspace.toolbar.color-palette" (sc/get-tooltip :toggle-colorpalette))
                    :class (stl/css-case :palette-btn true
                                         :selected color-palette?)
                    :on-click on-select-color-palette}
           deprecated-icon/drop-icon]]

         [:li {:class (stl/css :palette-item)}
          [:button {:title (tr "workspace.toolbar.text-palette" (sc/get-tooltip :toggle-textpalette))
                    :aria-label (tr "workspace.toolbar.text-palette" (sc/get-tooltip :toggle-textpalette))
                    :class (stl/css-case :palette-btn true
                                         :selected text-palette?)
                    :on-click on-select-text-palette}
           deprecated-icon/text-palette]]]


        (if any-palette?
          [:*
           [:button {:class (stl/css :palette-actions)
                     :on-click #(swap! state* update :show-menu not)}
            deprecated-icon/menu]
           [:div {:class (stl/css :palette)
                  :ref container}
            (when text-palette?
              [:*
               [:> text-palette-ctx-menu* {:show-menu  show-menu?
                                           :close-menu on-close-menu
                                           :on-select-palette on-select-text-palette-menu
                                           :selected selected-text}]
               [:> text-palette* {:size size
                                  :selected selected-text
                                  :width vport-width}]])
            (when color-palette?
              [:*
               [:> color-palette-ctx-menu* {:show show-menu?
                                            :on-close on-close-menu
                                            :on-select on-select-palette
                                            :selected @selected}]
               [:> color-palette* {:size size
                                   :selected @selected
                                   :width vport-width}]])]]
          [:div {:class (stl/css :handler)
                 :on-click toggle-palettes
                 :data-testid "toggle-palettes-visibility"}
           [:div {:class (stl/css :handler-btn)}]])])]))
