;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.keymap-panel
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.keymap :as km]
   [app.main.data.workspace :as dw]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

(mf/defc keymap-tab-bar*
  {::mf/private true}
  [{:keys [tabs selected on-change]}]
  (let [on-key-down
        (mf/use-fn
         (mf/deps tabs selected on-change)
         (fn [event]
           (let [len (count tabs)
                 idx (d/index-of-pred tabs #(= selected (:id %)))
                 id  (cond
                       (kbd/left-arrow? event)
                       (get-in tabs [(mod (- idx 1) len) :id])

                       (kbd/right-arrow? event)
                       (get-in tabs [(mod (+ idx 1) len) :id]))]
             (when (some? id)
               (dom/prevent-default event)
               (on-change id)
               (dom/focus! (dom/get-element (str "keymap-tab-" id)))))))]
    [:ul {:class (stl/css :keymap-tabs)
          :role "tablist"
          :on-key-down on-key-down}
     (for [tab tabs]
       (let [id (:id tab)
             selected? (= id selected)]
         [:li {:key id}
          [:button {:class (stl/css-case :keymap-tab true :selected selected?)
                    :id (str "keymap-tab-" id)
                    :role "tab"
                    :aria-selected selected?
                    :tab-index (if selected? 0 -1)
                    :on-click #(on-change id)}
           (:label tab)]]))]))

(mf/defc shortcut-keys*
  {::mf/private true}
  [{:keys [kw alternatives]}]
  [:span {:class (stl/css :keymap-keys)}
   (if (km/gesture? kw)
     (let [[gkey gesture] (km/gesture-parts kw)]
       [:*
        [:span {:class (stl/css :keymap-key)} gkey]
        [:span {:class (stl/css :keymap-gesture)} gesture]])
     (let [groups (if alternatives
                    (or (km/display-alternatives kw) [])
                    [(km/display-chars kw)])]
       (for [[gidx group] (map-indexed vector groups)]
         [:span {:class (stl/css :keymap-key-group)
                 :key (dm/str (d/name kw) "-" gidx)}
          (for [[cidx char] (map-indexed vector group)]
            [:span {:class (stl/css :keymap-key)
                    :key (dm/str (d/name kw) "-" gidx "-" cidx)}
             (km/convert-char char)])
          (when (< (inc gidx) (count groups))
            [:span {:class (stl/css :keymap-key-sep)} "/"])])))])

(mf/defc keymap-item*
  {::mf/private true}
  [{:keys [kw]}]
  [:div {:class (stl/css :keymap-item)}
   [:span {:class (stl/css :keymap-item-label)}
    (tr (str "shortcuts." (d/name kw)))]
   [:> shortcut-keys* {:kw kw}]])

(mf/defc important-item*
  {::mf/private true}
  [{:keys [kw]}]
  [:div {:class (stl/css :keymap-important-item)}
   [:div {:class (stl/css :keymap-important-row)}
    [:span {:class (stl/css :keymap-important-label)}
     (tr (str "keymap.important." (d/name kw)))]
    [:> shortcut-keys* {:kw kw :alternatives true}]]
   [:span {:class (stl/css :keymap-important-desc)}
    (tr (str "keymap.important." (d/name kw) ".desc"))]])

(mf/defc keymap-content*
  {::mf/private true}
  [{:keys [selected]}]
  ;; 用 (keyword selected) 直接查 km/tabs（其 :id 是 keyword）；
  ;; UI tabs 向量的 id 是字符串，仅用于 tab bar。
  ;; important tab 用双行条目组件，其余 tab 沿用单行 keymap-item*
  (let [tab        (some #(when (= (keyword selected) (:id %)) %) km/tabs)
        important? (= :important (:id tab))
        item       (if important? important-item* keymap-item*)]
    [:div {:class (stl/css-case :keymap-content true
                                :keymap-content-important important?)
           :role "tabpanel"
           :tab-index 0
           :aria-labelledby (str "keymap-tab-" selected)}
     (when tab
       (for [[idx column] (map-indexed vector (km/tab-columns tab))]
         [:div {:class (stl/css :keymap-column)
                :key idx}
          (for [kw column]
            [:> item {:key (d/name kw) :kw kw}])]))]))

(mf/defc keymap-panel*
  {::mf/memo true}
  [{:keys [class] :rest props}]
  (let [selected (mf/use-state "important")
        tabs [{:id "important" :label (tr "keymap.tab.important")}
              {:id "tools-view" :label (tr "keymap.tab.tools-view")}
              {:id "text" :label (tr "keymap.tab.text")}
              {:id "selection" :label (tr "keymap.tab.selection")}
              {:id "zoom" :label (tr "keymap.tab.zoom")}
              {:id "layers" :label (tr "keymap.tab.layers")}
              {:id "edit" :label (tr "keymap.tab.edit")}
              {:id "arrange" :label (tr "keymap.tab.arrange")}]
        on-close (mf/use-fn #(st/emit! (dw/remove-layout-flag :shortcuts)))
        on-change (mf/use-fn #(reset! selected %))
        wrapper-props (mf/spread-props props {:class [class (stl/css :keymap-wrapper)]})]
    [:> :div wrapper-props
     [:div {:class (stl/css :keymap-panel)
            :role "region"
            :aria-label (tr "shortcuts.title")}
      [:div {:class (stl/css :keymap-header)}
       [:> keymap-tab-bar* {:tabs tabs :selected @selected :on-change on-change}]
       [:> icon-button* {:variant "ghost"
                         :icon i/close
                         :aria-label (tr "labels.close")
                         :tooltip-class (stl/css :keymap-close-trigger)
                         :on-click on-close}]]
      [:> keymap-content* {:selected @selected}]]]))
;; Execution time translation strings: the keymap panel resolves
;; shortcuts.* msgids dynamically in keymap-item* and keymap.important.*
;; msgids in important-item*, so they are listed here to stay visible
;; to the translations extractor.
;; Ported from the legacy sidebar shortcuts panel (pruned of old-panel-only strings; copy-props/paste-props added).
(comment
  (tr "keymap.important.click-through")
  (tr "keymap.important.click-through.desc")
  (tr "keymap.important.drag-canvas")
  (tr "keymap.important.drag-canvas.desc")
  (tr "keymap.important.draw-frame")
  (tr "keymap.important.draw-frame.desc")
  (tr "keymap.important.draw-text")
  (tr "keymap.important.draw-text.desc")
  (tr "keymap.important.escape")
  (tr "keymap.important.escape.desc")
  (tr "keymap.important.group")
  (tr "keymap.important.group.desc")
  (tr "keymap.important.multi-select")
  (tr "keymap.important.multi-select.desc")
  (tr "keymap.important.zoom-canvas")
  (tr "keymap.important.zoom-canvas.desc")
  (tr "shortcut-subsection.alignment")
  (tr "shortcut-subsection.edit")
  (tr "shortcut-subsection.general-dashboard")
  (tr "shortcut-subsection.general-viewer")
  (tr "shortcut-subsection.main-menu")
  (tr "shortcut-subsection.modify-layers")
  (tr "shortcut-subsection.navigation-dashboard")
  (tr "shortcut-subsection.navigation-viewer")
  (tr "shortcut-subsection.navigation-workspace")
  (tr "shortcut-subsection.panels")
  (tr "shortcut-subsection.path-editor")
  (tr "shortcut-subsection.shape")
  (tr "shortcut-subsection.text-editor")
  (tr "shortcut-subsection.tools")
  (tr "shortcut-subsection.zoom-viewer")
  (tr "shortcut-subsection.zoom-workspace")
  (tr "shortcuts.add-comment")
  (tr "shortcuts.add-node")
  (tr "shortcuts.align-bottom")
  (tr "shortcuts.align-center")
  (tr "shortcuts.align-hcenter")
  (tr "shortcuts.align-justify")
  (tr "shortcuts.align-left")
  (tr "shortcuts.align-right")
  (tr "shortcuts.align-top")
  (tr "shortcuts.align-vcenter")
  (tr "shortcuts.artboard-selection")
  (tr "shortcuts.bold")
  (tr "shortcuts.bool-difference")
  (tr "shortcuts.bool-exclude")
  (tr "shortcuts.bool-intersection")
  (tr "shortcuts.bool-union")
  (tr "shortcuts.bring-back")
  (tr "shortcuts.bring-backward")
  (tr "shortcuts.bring-forward")
  (tr "shortcuts.bring-front")
  (tr "shortcuts.clear-undo")
  (tr "shortcuts.click-through")
  (tr "shortcuts.copy")
  (tr "shortcuts.copy-link")
  (tr "shortcuts.copy-props")
  (tr "shortcuts.create-component-variant")
  (tr "shortcuts.create-new-project")
  (tr "shortcuts.cut")
  (tr "shortcuts.decrease-zoom")
  (tr "shortcuts.delete")
  (tr "shortcuts.delete-node")
  (tr "shortcuts.detach-component")
  (tr "shortcuts.drag-canvas")
  (tr "shortcuts.draw-curve")
  (tr "shortcuts.draw-ellipse")
  (tr "shortcuts.draw-frame")
  (tr "shortcuts.draw-nodes")
  (tr "shortcuts.draw-path")
  (tr "shortcuts.draw-rect")
  (tr "shortcuts.draw-text")
  (tr "shortcuts.duplicate")
  (tr "shortcuts.escape")
  (tr "shortcuts.export-shapes")
  (tr "shortcuts.find")
  (tr "shortcuts.find-and-replace")
  (tr "shortcuts.fit-all")
  (tr "shortcuts.flip-horizontal")
  (tr "shortcuts.flip-vertical")
  (tr "shortcuts.font-size-dec")
  (tr "shortcuts.font-size-inc")
  (tr "shortcuts.go-to-drafts")
  (tr "shortcuts.go-to-libs")
  (tr "shortcuts.go-to-search")
  (tr "shortcuts.group")
  (tr "shortcuts.h-distribute")
  (tr "shortcuts.hide-ui")
  (tr "shortcuts.increase-zoom")
  (tr "shortcuts.insert-image")
  (tr "shortcuts.italic")
  (tr "shortcuts.join-nodes")
  (tr "shortcuts.line-through")
  (tr "shortcuts.make-corner")
  (tr "shortcuts.make-curve")
  (tr "shortcuts.mask")
  (tr "shortcuts.measure-distance")
  (tr "shortcuts.merge-nodes")
  (tr "shortcuts.move")
  (tr "shortcuts.move-fast-down")
  (tr "shortcuts.move-fast-left")
  (tr "shortcuts.move-fast-right")
  (tr "shortcuts.move-fast-up")
  (tr "shortcuts.move-nodes")
  (tr "shortcuts.move-unit-down")
  (tr "shortcuts.move-unit-left")
  (tr "shortcuts.move-unit-right")
  (tr "shortcuts.move-unit-up")
  (tr "shortcuts.multi-select")
  (tr "shortcuts.next-frame")
  (tr "shortcuts.opacity-0")
  (tr "shortcuts.opacity-1")
  (tr "shortcuts.opacity-2")
  (tr "shortcuts.opacity-3")
  (tr "shortcuts.opacity-4")
  (tr "shortcuts.opacity-5")
  (tr "shortcuts.opacity-6")
  (tr "shortcuts.opacity-7")
  (tr "shortcuts.opacity-8")
  (tr "shortcuts.opacity-9")
  (tr "shortcuts.open-color-picker")
  (tr "shortcuts.open-comments")
  (tr "shortcuts.open-dashboard")
  (tr "shortcuts.open-inspect")
  (tr "shortcuts.open-interactions")
  (tr "shortcuts.open-viewer")
  (tr "shortcuts.open-workspace")
  (tr "shortcuts.paste")
  (tr "shortcuts.paste-props")
  (tr "shortcuts.paste-replace")
  (tr "shortcuts.prev-frame")
  (tr "shortcuts.redo")
  (tr "shortcuts.rename")
  (tr "shortcuts.reset-zoom")
  (tr "shortcuts.scale")
  (tr "shortcuts.search-placeholder")
  (tr "shortcuts.select-all")
  (tr "shortcuts.select-next")
  (tr "shortcuts.select-parent-layer")
  (tr "shortcuts.select-prev")
  (tr "shortcuts.separate-nodes")
  (tr "shortcuts.show-pixel-grid")
  (tr "shortcuts.show-shortcuts")
  (tr "shortcuts.snap-nodes")
  (tr "shortcuts.snap-pixel-grid")
  (tr "shortcuts.start-editing")
  (tr "shortcuts.start-measure")
  (tr "shortcuts.stop-measure")
  (tr "shortcuts.thumbnail-set")
  (tr "shortcuts.toggle-alignment")
  (tr "shortcuts.toggle-assets")
  (tr "shortcuts.toggle-colorpalette")
  (tr "shortcuts.toggle-focus-mode")
  (tr "shortcuts.toggle-fullscreen")
  (tr "shortcuts.toggle-guides")
  (tr "shortcuts.toggle-history")
  (tr "shortcuts.toggle-layers")
  (tr "shortcuts.toggle-layout-flex")
  (tr "shortcuts.toggle-layout-grid")
  (tr "shortcuts.toggle-lock")
  (tr "shortcuts.toggle-lock-size")
  (tr "shortcuts.toggle-rulers")
  (tr "shortcuts.toggle-snap-guides")
  (tr "shortcuts.toggle-snap-ruler-guide")
  (tr "shortcuts.toggle-textpalette")
  (tr "shortcuts.toggle-theme")
  (tr "shortcuts.toggle-visibility")
  (tr "shortcuts.toggle-zoom-style")
  (tr "shortcuts.underline")
  (tr "shortcuts.undo")
  (tr "shortcuts.ungroup")
  (tr "shortcuts.unmask")
  (tr "shortcuts.v-distribute")
  (tr "shortcuts.zoom-canvas")
  (tr "shortcuts.zoom-lense-decrease")
  (tr "shortcuts.zoom-lense-increase"))

