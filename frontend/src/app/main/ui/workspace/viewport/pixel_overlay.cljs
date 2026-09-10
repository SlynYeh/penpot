;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.viewport.pixel-overlay
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.math :as mth]
   [app.common.types.color :as cc]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.colors :as dwc]
   [app.main.data.workspace.undo :as dwu]
   [app.main.fonts :as fonts]
   [app.main.rasterizer :as thr]
   [app.main.store :as st]
   [app.render-wasm.api :as wasm.api]
   [app.util.dom :as dom]
   [app.util.globals :as ug]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.timers :as timers]
   [beicon.v2.core :as rx]
   [goog.events :as events]
   [rumext.v2 :as mf]))

(defn create-offscreen-canvas
  [width height]
  (js/OffscreenCanvas. width height))

(defn resize-offscreen-canvas
  [canvas width height]
  (let [resized (volatile! false)]
    (when-not (= (unchecked-get canvas "width") width)
      (obj/set! canvas "width" width)
      (vreset! resized true))
    (when-not (= (unchecked-get canvas "height") height)
      (obj/set! canvas "height" height)
      (vreset! resized true))
    canvas))

(def get-offscreen-canvas
  ((fn []
     (let [internal-state #js {:canvas nil}]
       (fn [width height]
         (let [canvas (unchecked-get internal-state "canvas")]
           (if canvas
             (resize-offscreen-canvas canvas width height)
             (let [new-canvas (create-offscreen-canvas width height)]
               (obj/set! internal-state "canvas" new-canvas)
               new-canvas))))))))

;; --- Circular, cursor-following magnifier loupe ---------------------------------
;; Draws the zoomed region into the #picker-detail canvas, clips it to a circle
;; (via .picker-loupe-circle border-radius), and moves the whole loupe (the
;; #picker-loupe node) so it follows the cursor. Shared by the SVG and WASM
;; pointer-move handlers. Matches img_6.png: round loupe centred on the cursor,
;; small hollow square crosshair, dark pill with R:G:B + hex below.

(def ^:private loupe-size 160)

;; Where the sampled pixel is drawn inside the loupe box. The loupe is anchored
;; on this point (not the centre) so the cursor/sample sits BELOW the circle
;; (sample-y > 160 → outside the bottom edge, img_7.png) — the eyedropper icon
;; and the crosshair both live here, while the magnified region is shown above.
(def ^:private sample-x 60)
(def ^:private sample-y 185)

(defn ^:private update-loupe-label!
  [r g b]
  (when-let [rgb-node (dom/get-element "picker-loupe-rgb")]
    (dom/set-text! rgb-node (dm/str "R:" r " G:" g " B:" b)))
  (when-let [hex-node (dom/get-element "picker-loupe-hex")]
    (dom/set-text! hex-node (.toUpperCase (cc/rgb->hex [r g b])))))

(defn ^:private draw-loupe!
  [zoom-view-context source-canvas sx sy sw sh client-x client-y color]
  ;; 1. Reposition the loupe so the sample point (the cursor pixel) sits at the
  ;;    bottom of the circle (img_7.png): anchor on `sample-x/y` instead of the
  ;;    centre, and publish those anchors for the CSS crosshair to use.
  (when-let [wrapper-node (dom/get-element "picker-loupe")]
    (when-let [overlay-node (dom/get-element "pixel-overlay")]
      (let [{left :left top :top} (dom/get-bounding-rect overlay-node)]
        (dom/set-css-property! wrapper-node "--loupe-x" (dm/str (- client-x left sample-x) "px"))
        (dom/set-css-property! wrapper-node "--loupe-y" (dm/str (- client-y top  sample-y) "px"))
        (dom/set-css-property! wrapper-node "--loupe-sample-x" (dm/str sample-x "px"))
        (dom/set-css-property! wrapper-node "--loupe-sample-y" (dm/str sample-y "px"))))

    ;; 2. Draw the pixelated zoomed region into the loupe canvas.
    (when-let [detail-node (dom/get-element "picker-detail")]
      (when-let [ctx (or (mf/ref-val zoom-view-context)
                         (do (mf/set-ref-val! zoom-view-context (.getContext detail-node "2d"))
                             (mf/ref-val zoom-view-context)))]
        (when (obj/get ctx "imageSmoothingEnabled")
          (obj/set! ctx "imageSmoothingEnabled" false))
        (.clearRect ctx 0 0 loupe-size loupe-size)
        (.drawImage ctx source-canvas sx sy sw sh 0 0 loupe-size loupe-size))))

  ;; 3. Live colour pill (R:G:B + #HEX). `color` is nil when outside the canvas,
  ;; so the pill keeps its last value instead of flashing empty.
  (when-let [[r g b] color]
    (update-loupe-label! r g b)))

(defn process-pointer-move
  [viewport-node canvas canvas-image-data zoom-view-context last-picked-color client-x client-y]
  (when-let [image-data (mf/ref-val canvas-image-data)]
    (let [{brx :left bry :top} (dom/get-bounding-rect viewport-node)
          x (mth/floor (- client-x brx))
          y (mth/floor (- client-y bry))
          img-width  (unchecked-get image-data "width")
          img-height (unchecked-get image-data "height")]

      ;; Read + store the pixel synchronously, so a pointer-down always sees a
      ;; fresh colour even when the loupe is not (yet) drawn — and reuse it for
      ;; the loupe label on the same frame.
      (let [color (when (and (>= x 0) (< x img-width) (>= y 0) (< y img-height))
                    (let [offset (* (+ (* y img-width) x) 4)
                          rgba   (unchecked-get image-data "data")
                          r      (d/check-num (obj/get rgba (+ 0 offset)) 255)
                          g      (d/check-num (obj/get rgba (+ 1 offset)) 255)
                          b      (d/check-num (obj/get rgba (+ 2 offset)) 255)
                          a      (d/check-num (obj/get rgba (+ 3 offset)) 255)]
                      [r g b a]))]
        (when (some? color)
          ;; Store latest color synchronously so the click handler always reads
          ;; the correct pixel even before the rAF fires (fixes race condition)
          (mf/set-ref-val! last-picked-color color)
          (timers/raf
           (fn []
             (st/emit! (dwc/pick-color color)))))

        ;; Draw the loupe: 40×40 source → 4× zoom into the 160×160 circle, but
        ;; offset so the cursor pixel lands at the sample point (bottom, img_7.png)
        ;; instead of the centre: nudge the source rect a quarter of the sample
        ;; offset up (the ÷4 is the 40→160 scale).
        (let [src-size 40
              sx (- x (/ sample-x 4.0))
              sy (- y (/ sample-y 4.0))]
          (draw-loupe! zoom-view-context canvas sx sy src-size src-size client-x client-y color))))))


(mf/defc pixel-overlay*
  [{:keys [vport viewport-ref]}]
  (let [viewport-node     (mf/ref-val viewport-ref)

        canvas            (get-offscreen-canvas (:width vport) (:height vport))
        canvas-context    (.getContext canvas "2d" #js {:willReadFrequently true})
        canvas-image-data (mf/use-ref nil)
        zoom-view-context (mf/use-ref nil)
        ;; Holds the last successfully picked [r g b a] synchronously so that
        ;; the pointer-down handler always has the current pixel, regardless of
        ;; whether the rAF-deferred store update has fired yet.
        last-picked-color (mf/use-ref nil)
        ;; Use a ref (not state) so tracking the cursor doesn't cause re-renders.
        ;; Updated by both on-mouse-enter and a document-level pointermove listener
        ;; so that the position is always current when the canvas first becomes ready.
        initial-mouse-pos (mf/use-ref {:x 0 :y 0})
        update-str        (rx/subject)

        handle-keydown
        (mf/use-callback
         (fn [event]
           (when (kbd/esc? event)
             (dom/stop-propagation event)
             (dom/prevent-default event)
             (st/emit! (dwc/stop-picker))
             (modal/disallow-click-outside!))))

        handle-pointer-down-picker
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           ;; Emit pick-color synchronously with the latest pixel colour before
           ;; pick-color-select, so the colorpicker effect never sees a stale value.
           (let [color (mf/ref-val last-picked-color)]
             (if (some? color)
               (st/emit! (dwu/start-undo-transaction :mouse-down-picker)
                         (dwc/pick-color color)
                         (dwc/pick-color-select true (kbd/shift? event)))
               (st/emit! (dwu/start-undo-transaction :mouse-down-picker)
                         (dwc/pick-color-select true (kbd/shift? event)))))))

        handle-pointer-up-picker
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (st/emit! (dwu/commit-undo-transaction :mouse-down-picker)
                     (dwc/stop-picker))
           (modal/disallow-click-outside!)))

        handle-draw-picker-canvas
        (mf/use-callback
         (fn []
           (let [svg-node (dom/get-element "render")
                 fonts    (fonts/get-node-fonts svg-node)
                 result {:node svg-node
                         :width (:width vport)
                         :result "image-bitmap"}]
             (->> (fonts/render-font-styles-cached fonts)
                  (rx/map (fn [styles]
                            (assoc result :styles styles)))
                  (rx/mapcat thr/render-node)
                  (rx/subs! (fn [image-bitmap]
                              (.drawImage canvas-context image-bitmap 0 0)
                              (let [width (unchecked-get canvas "width")
                                    height (unchecked-get canvas "height")
                                    image-data (.getImageData canvas-context 0 0 width height)
                                    ;; Read current mouse position from ref so the zoom
                                    ;; is populated immediately even without a mouse-move.
                                    {mx :x my :y} (mf/ref-val initial-mouse-pos)]
                                (mf/set-ref-val! canvas-image-data image-data)
                                (process-pointer-move viewport-node canvas canvas-image-data
                                                      zoom-view-context last-picked-color
                                                      mx my))))))))

        handle-svg-change
        (mf/use-callback
         (fn []
           (rx/push! update-str :update)))

        handle-mouse-enter
        (mf/use-callback
         (mf/deps viewport-node)
         (fn [event]
           (mf/set-ref-val! initial-mouse-pos
                            {:x (.-clientX event)
                             :y (.-clientY event)})))

        handle-pointer-move-picker
        (mf/use-callback
         (mf/deps viewport-node)
         (fn [event]
           (process-pointer-move viewport-node canvas canvas-image-data zoom-view-context
                                 last-picked-color (.-clientX event) (.-clientY event))))]

    (when (obj/get canvas-context "imageSmoothingEnabled")
      (obj/set! canvas-context "imageSmoothingEnabled" false))

    ;; Move focus to the overlay div on mount so the eyedropper button loses
    ;; :focus styling immediately.  Without this, prevent-default on pointer-down
    ;; keeps focus on the button and it looks "selected" even after picking.
    (mf/use-effect
     (fn []
       (when-let [node (dom/get-element "pixel-overlay")]
         (.focus node))))

    (mf/use-effect
     (fn []
       (let [listener (events/listen ug/document "keydown" handle-keydown)]
         #(events/unlistenByKey listener))))

    (mf/use-effect
     (fn []
       (let [sub (->> update-str
                      (rx/debounce 10)
                      (rx/subs! handle-draw-picker-canvas))]
         #(rx/dispose! sub))))

    (mf/use-effect
     (fn []
       (let [config #js {:attributes true
                         :childList true
                         :subtree true
                         :characterData true}
             svg-node (dom/get-element "render")
             observer (js/MutationObserver. handle-svg-change)]
         (.observe observer svg-node config)
         (handle-svg-change)

         ;; Disconnect on unmount
         #(.disconnect observer))))

    ;; Track the cursor position at document level so initial-mouse-pos is always
    ;; current when the canvas first becomes ready — even when the picker is opened
    ;; via the "i" shortcut and the cursor hasn't entered/moved over the overlay yet.
    (mf/use-effect
     (fn []
       (let [listener (events/listen ug/document "pointermove"
                                     (fn [e]
                                       (mf/set-ref-val! initial-mouse-pos
                                                        {:x (.-clientX e)
                                                         :y (.-clientY e)})))]
         #(events/unlistenByKey listener))))

    [:div {:id "pixel-overlay"
           :tab-index 0
           :class (dm/str "cursor-picker " (stl/css :pixel-overlay))
           :on-pointer-down handle-pointer-down-picker
           :on-pointer-up handle-pointer-up-picker
           :on-pointer-move handle-pointer-move-picker
           :on-mouse-enter handle-mouse-enter}
     ;; Circular magnifier loupe that follows the cursor (img_6.png / img_7.png).
     ;; Positioned via CSS vars set in draw-loupe!; the native eyedropper cursor
     ;; (`cursor-picker`) shows at the sample point, which now sits just below
     ;; (outside) the circle, with the magnified region shown above it.
     [:div {:id "picker-loupe" :class (stl/css :picker-loupe)}
      [:div {:class (stl/css :picker-loupe-circle)}
       [:canvas#picker-detail {:class (stl/css :picker-detail) :width 160 :height 160}]]
      ;; Crosshair is a direct child of #picker-loupe (no overflow:hidden) so it
      ;; can render OUTSIDE the circle at the sample point (below it, img_7.png).
      [:div {:class (stl/css :picker-loupe-crosshair)}]
      [:div {:class (stl/css :picker-loupe-pill)}
       [:span {:id "picker-loupe-rgb"}]
       [:span {:id "picker-loupe-hex"}]]]]))


(defn- viewport->canvas-coords
  "Maps client (viewport) coordinates to device-pixel canvas coordinates."
  [viewport-node client-x client-y]
  (let [{brx :left bry :top} (dom/get-bounding-rect viewport-node)
        dpr (wasm.api/get-dpr)
        x (mth/floor (- client-x brx))
        y (mth/floor (- client-y bry))]
    [(mth/floor (* x dpr))
     (mth/floor (* y dpr))]))

;; Tiny scratch 2D canvas used to sample a single pixel from the WebGL canvas.
(def ^:private get-pick-canvas
  ((fn []
     (let [internal-state #js {:canvas nil}]
       (fn []
         (or (unchecked-get internal-state "canvas")
             (let [c (js/OffscreenCanvas. 1 1)]
               (obj/set! internal-state "canvas" c)
               c)))))))

(defn pick-color-at-wasm
  "Reads the [r g b a] pixel under the cursor by compositing the WebGL canvas
   into a 2D canvas via `drawImage` and reading it back with `getImageData`.

   This is the same browser-composited path the loupe preview uses, so it gives
   the correct color even on GPUs where a raw WebGL `readPixels` returned
   values with their byte order swapped."
  [viewport-node canvas client-x client-y]
  (when canvas
    (let [[canvas-x canvas-y] (viewport->canvas-coords viewport-node client-x client-y)
          img-width  (.-width canvas)
          img-height (.-height canvas)]
      (when (and (>= canvas-x 0) (< canvas-x img-width) (>= canvas-y 0) (< canvas-y img-height))
        (let [scratch (get-pick-canvas)
              ctx     (.getContext scratch "2d" #js {:willReadFrequently true})]
          (.clearRect ctx 0 0 1 1)
          ;; Copy just the source pixel (top-left origin, no y-flip — same as the loupe).
          (.drawImage ctx canvas canvas-x canvas-y 1 1 0 0 1 1)
          (let [data (.-data (.getImageData ctx 0 0 1 1))]
            [(aget data 0) (aget data 1) (aget data 2) (aget data 3)]))))))

(defn process-pointer-move-wasm
  "Updates the circular magnifier loupe with the canvas region under the cursor.
   Reads the color under the cursor for the live pill label. The pixel actually
   applied on click is re-read in the pointer-down handler (see `pick-color-at-wasm`)."
  [viewport-node canvas zoom-view-context last-picked-color client-x client-y]
  (when canvas
    ;; Sample the color for the loupe label. Cheap: one drawImage + getImageData
    ;; on a 1×1 scratch canvas. Falls back to nil (label keeps its last value).
    (let [color (pick-color-at-wasm viewport-node canvas client-x client-y)]
      (when (some? color)
        (mf/set-ref-val! last-picked-color color))
      ;; 40×40 source → 4× zoom into the 160×160 circle, offset so the cursor
      ;; pixel lands at the sample point (bottom, img_7.png) instead of the centre.
      (let [[canvas-x canvas-y] (viewport->canvas-coords viewport-node client-x client-y)
            src-size 40
            sx (- canvas-x (/ sample-x 4.0))
            sy (- canvas-y (/ sample-y 4.0))]
        (draw-loupe! zoom-view-context canvas sx sy src-size src-size client-x client-y color)))))

(mf/defc pixel-overlay-wasm*
  [{:keys [viewport-ref canvas-ref]}]
  (let [viewport-node     (mf/ref-val viewport-ref)
        canvas            (mf/ref-val canvas-ref)
        zoom-view-context (mf/use-ref nil)
        ;; Holds the last successfully sampled [r g b a] so the pointer-down
        ;; handler always has a current pixel (mirrors pixel-overlay*).
        last-picked-color (mf/use-ref nil)
        ;; Use a ref (not state) so tracking the cursor doesn't cause re-renders.
        ;; Updated by both on-mouse-enter and a document-level pointermove listener
        ;; so that the position is always current when the canvas first becomes ready.
        initial-mouse-pos (mf/use-ref {:x 0 :y 0})
        update-str        (rx/subject)

        handle-keydown
        (mf/use-callback
         (fn [event]
           (when (kbd/esc? event)
             (dom/stop-propagation event)
             (dom/prevent-default event)
             (st/emit! (dwc/stop-picker))
             (modal/disallow-click-outside!))))

        handle-pointer-down-picker
        (mf/use-callback
         (mf/deps viewport-node canvas)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           ;; Read the pixel under the cursor only on click — not on every move.
           (let [color (pick-color-at-wasm viewport-node canvas
                                           (.-clientX event) (.-clientY event))]
             (if (some? color)
               (st/emit! (dwu/start-undo-transaction :mouse-down-picker)
                         (dwc/pick-color color)
                         (dwc/pick-color-select true (kbd/shift? event)))
               (st/emit! (dwu/start-undo-transaction :mouse-down-picker)
                         (dwc/pick-color-select true (kbd/shift? event)))))))

        handle-pointer-up-picker
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (st/emit! (dwu/commit-undo-transaction :mouse-down-picker)
                     (dwc/stop-picker))
           (modal/disallow-click-outside!)))

        handle-draw-picker-canvas
        (mf/use-callback
         (fn []
           (when canvas
             ;; Read current mouse position from ref so the loupe refreshes on
             ;; each render even without a mouse-move.
             (let [{mx :x my :y} (mf/ref-val initial-mouse-pos)]
               (process-pointer-move-wasm viewport-node canvas
                                          zoom-view-context last-picked-color
                                          mx my)))))

        handle-canvas-changed
        (mf/use-callback
         (fn [_]
           (rx/push! update-str :update)))

        handle-mouse-enter
        (mf/use-callback
         (mf/deps viewport-node)
         (fn [event]
           (mf/set-ref-val! initial-mouse-pos
                            {:x (.-clientX event)
                             :y (.-clientY event)})))

        handle-pointer-move-picker
        (mf/use-callback
         (mf/deps viewport-node)
         (fn [event]
           (process-pointer-move-wasm viewport-node canvas zoom-view-context
                                      last-picked-color (.-clientX event) (.-clientY event))))]

    ;; Move focus to the overlay div on mount so the eyedropper button loses
    ;; :focus styling immediately.  Without this, prevent-default on pointer-down
    ;; keeps focus on the button and it looks "selected" even after picking.
    (mf/use-effect
     (fn []
       (when-let [node (dom/get-element "pixel-overlay")]
         (.focus node))))

    (mf/use-effect
     (fn []
       (let [listener (events/listen ug/document "keydown" handle-keydown)]
         #(events/unlistenByKey listener))))

    (mf/use-effect
     (fn []
       (let [sub (->> update-str
                      (rx/debounce 10)
                      (rx/subs! handle-draw-picker-canvas))]
         #(rx/dispose! sub))))

    (mf/with-effect []
      (handle-canvas-changed)
      (.addEventListener ug/document "penpot:wasm:render" handle-canvas-changed)
      (fn []
        (.removeEventListener ug/document "penpot:wasm:render" handle-canvas-changed)))

    ;; Track the cursor position at document level so initial-mouse-pos is always
    ;; current when the canvas first becomes ready — even when the picker is opened
    ;; via the "i" shortcut and the cursor hasn't entered/moved over the overlay yet.
    (mf/use-effect
     (fn []
       (let [listener (events/listen ug/document "pointermove"
                                     (fn [e]
                                       (mf/set-ref-val! initial-mouse-pos
                                                        {:x (.-clientX e)
                                                         :y (.-clientY e)})))]
         #(events/unlistenByKey listener))))

    [:div {:id "pixel-overlay"
           :tab-index 0
           :class (dm/str "cursor-picker " (stl/css :pixel-overlay))
           :on-pointer-down handle-pointer-down-picker
           :on-pointer-up handle-pointer-up-picker
           :on-pointer-move handle-pointer-move-picker
           :on-mouse-enter handle-mouse-enter}
     ;; Circular magnifier loupe that follows the cursor (img_6.png / img_7.png).
     ;; Positioned via CSS vars set in draw-loupe!; the native eyedropper cursor
     ;; (`cursor-picker`) shows at the sample point, which now sits just below
     ;; (outside) the circle, with the magnified region shown above it.
     [:div {:id "picker-loupe" :class (stl/css :picker-loupe)}
      [:div {:class (stl/css :picker-loupe-circle)}
       [:canvas#picker-detail {:class (stl/css :picker-detail) :width 160 :height 160}]]
      ;; Crosshair is a direct child of #picker-loupe (no overflow:hidden) so it
      ;; can render OUTSIDE the circle at the sample point (below it, img_7.png).
      [:div {:class (stl/css :picker-loupe-crosshair)}]
      [:div {:class (stl/css :picker-loupe-pill)}
       [:span {:id "picker-loupe-rgb"}]
       [:span {:id "picker-loupe-hex"}]]]]))
