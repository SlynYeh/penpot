;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.colorpicker.palette
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.color :as cc]
   [app.common.types.color :as ctc]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.colors :as mdc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.utilities.swatch :refer [swatch*]]
   [app.main.ui.formats :as fmt]
   [app.main.ui.workspace.colorpicker.slider-selector :refer [slider-selector*]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [clojure.string :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; --- Refs (re-declared locally to avoid coupling to colorpicker.cljs)

(def picking-color?
  (l/derived :picking-color? refs/workspace-global))

(def picked-color
  (l/derived :picked-color refs/workspace-global))

(def picked-color-select
  (l/derived :picked-color-select refs/workspace-global))

;; --- Default palette (preset)

;; 9 columns × 7 rows of preset colors, sourced from img_3.png in
;; 颜色选择器变更.md. Top row is a grayscale ramp from white to black;
;; each subsequent row is a single hue at increasing saturation; the
;; bottom row is the darkest variant of each hue.
(def default-palette
  [["#ffffff" "#f7f7f9" "#ecedf0" "#dedfdf" "#b8b9bd" "#8a8b8e" "#545456" "#303030" "#000000"]
   ["#fff5f2" "#fff6ea" "#fbf4e1" "#e4fbe4" "#eefcfc" "#e8f4ff" "#f4f8ff" "#f8f3ff" "#fff4f7"]
   ["#ffded7" "#ffe2c9" "#fee8b2" "#b0f7a5" "#96f7fa" "#bfe0fe" "#d1e3ff" "#e6ddff" "#ffdbe7"]
   ["#ff957f" "#ffb45d" "#f6d25c" "#5fe055" "#00dcdc" "#80c0fe" "#6ba8ff" "#b99afc" "#ff8bbb"]
   ["#fe5340" "#fd8b00" "#f1bf26" "#3bc11e" "#0bbdbb" "#4fa7ff" "#4481ff" "#9963ff" "#ff459f"]
   ["#d82500" "#e35c01" "#d49d00" "#2a9a0d" "#009292" "#068ae5" "#2061ff" "#771df4" "#dd1480"]
   ["#790d00" "#772b01" "#765000" "#194b00" "#004747" "#004572" "#0032a0" "#3f0089" "#7d014d"]])

;; --- Helpers

(defn opacity->string
  "Format opacity for display next to the slider. Mirrors the value shown in
   .main_ui_workspace_sidebar_options_rows_color_row__opacity-element-wrapper."
  [opacity]
  (if (= opacity :multiple)
    ""
    (str (-> opacity
             (d/coalesce 1)
             (* 100)
             (fmt/format-number)))))

(defn set-palette-color-css!
  "Drive the opacity slider gradient from the current color. Used inline
   as an effect body — never call directly."
  [node-ref current-color]
  (when-let [node (mf/ref-val node-ref)]
    (let [{:keys [color]} current-color
          rgb (when (some? color)
                (cc/hex->rgb color))]
      (if rgb
        (dom/set-css-property! node "--color" (str/join ", " rgb))
        (dom/set-css-property! node "--color" "0, 0, 0")))))

;; --- Component

(mf/defc palette-panel*
  [{:keys [on-change on-start-drag on-finish-drag state current-color]}]
  (let [picking-color?       (mf/deref picking-color?)
        picked-color         (mf/deref picked-color)
        picked-color-select  (mf/deref picked-color-select)
        recent-colors        (mf/deref refs/recent-colors)
        recent-colors        (mf/with-memo [recent-colors]
                               (filterv ctc/valid-color? recent-colors))

        slider-node-ref      (mf/use-ref nil)
        ;; Push the current color into the slider's `--color` CSS
        ;; variable so the opacity gradient updates per selection.
        _                    (mf/with-effect [current-color]
                               (set-palette-color-css! slider-node-ref current-color))

        libraries            (mf/deref refs/libraries)
        file-id              (mf/use-ctx ctx/current-file-id)

        ;; The palette grid renders the fixed 9×7 preset (img_3.png), with
        ;; the file's saved library colors appended below. The 最近颜色
        ;; section below renders the recent list explicitly so the user can
        ;; see history even when it overlaps with the palette grid.
        ;;
        ;; Each entry is wrapped as `{:color hex :opacity 1}` so it matches
        ;; the shape expected by swatch* / on-swatch-click (same shape as
        ;; recent-colors and library-color->color).
        grid-swatches
        (mf/with-memo [libraries file-id]
          (vec (concat
                (mapv (fn [hex] {:color hex :opacity 1})
                      (apply concat default-palette))
                (->> (vals (dm/get-in libraries [file-id :data :colors]))
                     (filterv ctc/valid-library-color?)
                     (sort-by :name)
                     (map #(ctc/library-color->color % file-id))))))

        ;; Selecting a swatch / eyedropper color. For a solid color we
        ;; normalise it into a fully materialized color (hex + alpha + rgb/hsv
        ;; components) BEFORE calling `update-colorpicker-color`, because that
        ;; event re-derives `:color` from `:hex`; without a synced `:hex` the
        ;; picked value would be silently replaced by the previous color (or
        ;; wiped to nil when the colorpicker was never initialized). We also
        ;; bubble the color up through `on-change` so the selected shape's fill
        ;; updates immediately (same behaviour as the 自定义 tab), and let
        ;; `update-colorpicker-color` add it to 最近颜色.
        handle-color-selected
        (mf/use-fn
         (mf/deps current-color on-change)
         (fn [color]
           (let [color (d/without-qualified color)
                 hex   (or (:color color) (:hex color))]
             (if hex
               (let [opacity (d/coalesce (or (:opacity color) (:alpha color)) 1)
                     color*  (mdc/split-color-components {:color hex :opacity opacity})
                     merged  (merge current-color color*)]
                 (st/emit! (mdc/update-colorpicker-color merged true))
                 (when (fn? on-change)
                   (on-change {:color hex :opacity opacity})))
               ;; Gradient / image — apply through the same path as library
               ;; color selection, then bubble up so the shape updates.
               (do (st/emit! (mdc/add-recent-color color)
                             (mdc/apply-color-from-colorpicker color))
                   (when (fn? on-change)
                     (on-change color)))))))

        on-swatch-click  handle-color-selected

        on-opacity-change
        (mf/use-fn
         (mf/deps current-color on-change)
         (fn [value]
           (when-let [hex (:color current-color)]
             (let [color*  (mdc/split-color-components {:color hex :opacity value})
                   merged  (merge current-color color*)]
               (st/emit! (mdc/update-colorpicker-color merged true))
               (when (fn? on-change)
                 (on-change {:color hex :opacity value}))))))

        on-click-picker
        (mf/use-fn
         (mf/deps picking-color?)
         (fn []
           (if picking-color?
             (do (modal/disallow-click-outside!)
                 (st/emit! (mdc/stop-picker)))
             (do (modal/allow-click-outside!)
                 (st/emit! (mdc/start-picker))))))]

    ;; Apply the eyedropper result. This mirrors the effect in
    ;; `colorpicker*`, but the palette panel needs its own copy because
    ;; `colorpicker*` is not mounted while the 色板 tab is shown.
    (mf/with-effect [picking-color? picked-color picked-color-select]
      (when (and picking-color? picked-color picked-color-select)
        (let [[r g b alpha] picked-color
              hex (cc/rgb->hex [r g b])]
          (handle-color-selected {:color hex
                                  :opacity (d/coalesce (/ alpha 255) 1)}))))

    [:div {:class (stl/css :colorpicker-palette)}

     [:ul {:class (stl/css :palette-grid)
           :aria-label (tr "workspace.colorpicker.palette.grid")}
      (for [color grid-swatches]
        [:li {:key (dm/str color)}
         [:> swatch* {:background color
                      :on-click on-swatch-click
                      :size "medium"}]])]

     [:div {:class (stl/css :palette-opacity-row)}
      [:button {:class (stl/css-case :picker-btn true
                                     :selected picking-color?)
                :on-click on-click-picker
                :title (tr "workspace.colorpicker.picker.tooltip")
                :aria-label (tr "workspace.colorpicker.picker.tooltip")}
       ;; Rendered as a raw svg instead of `icon*`: the DS component only
       ;; accepts the s/m/l sizes (12/16/32px) and its `<use>` is offset
       ;; inside a fixed 16×16 viewport, so a 20px glyph would be clipped.
       ;; Sizing both the viewport and the `<use>` to 20 paints it whole.
       [:svg {:width 20
              :height 20
              :viewBox "0 0 20 20"}
        [:use {:href (dm/str "#icon-" i/picker)
               :x 0
               :y 0
               :width 20
               :height 20}]]]

      [:div {:class (stl/css :palette-opacity-wrapper)
        :ref slider-node-ref}
       [:> slider-selector*
        {:type :opacity
         :value (d/coalesce (:opacity current-color) 1)
         :min-value 0
         :max-value 1
         :on-start-drag on-start-drag
         :on-finish-drag on-finish-drag
         :on-change on-opacity-change}]]

      [:span {:class (stl/css :palette-opacity-value)}
       (opacity->string (:opacity current-color))]]

     [:div {:class (stl/css :palette-recent)}
      [:h4 {:class (stl/css :palette-recent-title)}
       (tr "workspace.libraries.colors.recent-colors")]
      [:ul {:class (stl/css :palette-recent-grid)
            :aria-label (tr "workspace.libraries.colors.recent-colors")}
       (for [color (->> recent-colors
                        reverse
                        (take 18))]
         [:li {:key (dm/str color)}
          [:> swatch* {:background color
                       :on-click on-swatch-click
                       :size "medium"}]])]]]))
