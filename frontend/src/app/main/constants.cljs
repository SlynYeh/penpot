;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.constants)

(def viewport-width 4000)
(def viewport-height 4000)

(def frame-start-x 1200)
(def frame-start-y 1200)

;; Gap between the rightmost canvas object and a board created from a size preset.
(def board-preset-gap 40)

(def grid-x-axis 10)
(def grid-y-axis 10)

;; Before changing these values also check:
;; frontend/src/app/main/ui/workspace/sidebar/common/sidebar.scss

(def right-sidebar-default-width 318)
(def right-sidebar-default-max-width 768)

(def left-sidebar-default-width 318)
(def left-sidebar-default-max-width 500)

(def page-metadata
  "Default data for page metadata."
  {:grid-x-axis grid-x-axis
   :grid-y-axis grid-y-axis
   :grid-color "var(--df-secondary)"
   :grid-alignment true
   :background "var(--app-white)"})

(def size-presets
  [{:name "workspace.options.size-preset.category.web"}
   {:name "Web 1280"
    :width 1280
    :height 800}
   {:name "Web 1366"
    :width 1366
    :height 768}
   {:name "Web 1024"
    :width 1024
    :height 768}
   {:name "Web 1920"
    :width 1920
    :height 1080}

   {:name "workspace.options.size-preset.category.phone"}
   {:name "iPhone 16"
    :width 393
    :height 852}
   {:name "iPhone 16 Pro"
    :width 402
    :height 874}
   {:name "iPhone 16 Pro Max"
    :width 440
    :height 956}
   {:name "iPhone 16 Plus"
    :width 430
    :height 932}
   {:name "14/15 Pro Max"
    :width 430
    :height 932}
   {:name "iPhone 15/15 Pro"
    :width 393
    :height 852}
   {:name "iPhone 13/14 "
    :width 390
    :height 844}
   {:name "iPhone 14 Plus"
    :width 428
    :height 926}
   {:name "iPhone 13 Mini"
    :width 375
    :height 812}
   {:name "iPhone SE"
    :width 320
    :height 568}
   {:name "iPhone 12/12 Pro"
    :width 390
    :height 844}
   {:name "iPhone 12 Mini"
    :width 360
    :height 780}
   {:name "iPhone 12 Pro Max"
    :width 428
    :height 926}
   {:name "iPhone X/XS/11 Pro"
    :width 375
    :height 812}
   {:name "iPhone XS Max/XR/11"
    :width 414
    :height 896}
   {:name "Compact"
    :width 412
    :height 917}
   {:name "Large"
    :width 360
    :height 800}
   {:name "Small"
    :width 360
    :height 640}
   {:name "Mobile"
    :width 360
    :height 640}
   {:name "Google Pixel 7 Pro"
    :width 412
    :height 892}
   {:name "Google Pixel 6a/6"
    :width 412
    :height 915}
   {:name "Google Pixel 4a/5"
    :width 393
    :height 851}
   {:name "Samsung Galaxy S22"
    :width 360
    :height 780}
   {:name "Samsung Galaxy S20+"
    :width 384
    :height 854}
   {:name "Samsung Galaxy A71/A51"
    :width 412
    :height 914}])

(def max-input-length 255)

(def ^:const default-slow-progress-threshold
  "A constant value that represents a threshold in milliseconds when a
  normal progress becomes tagged as slow if no event received in the
  specified amount of time"
  1000)

;; ------------------------------------------------
;; Typography
;; ------------------------------------------------

(def ^:const font-size 11)

;; ------------------------------------------------
;; Colors (CSS custom properties)
;; ------------------------------------------------

(def ^:const select-color "var(--color-accent-tertiary)")

(def ^:const distance-color "var(--color-accent-quaternary)")
(def ^:const distance-text-color "var(--app-white)")

;; ------------------------------------------------
;; Selection rectangle & guides
;; ------------------------------------------------

(def ^:const selection-rect-width 1)

;; ------------------------------------------------
;; Transform preview sampling
;; ------------------------------------------------

(def ^:const default-sample-time
  "Default time in ms for the sampling of transforms, this caps to one per frame the preview of modifiers"
  16)

(def ^:const resize-sample-time default-sample-time)
(def ^:const rotation-sample-time default-sample-time)
(def ^:const move-sample-time default-sample-time)

(def ^:const preview-solve-max-affected-nodes
  "Live (per-frame) layout solves during resize/rotate previews are allowed
  only when the affected tree is pure-plain AND below this node count. Above
  it the preview freezes even without layouts: a 2000-child plain frame
  costs ~8-16ms/frame in-browser (mem:frontend/drag-resize-vertex-perf);
  800 ≈ 4-8ms/frame in-browser, interpolated from that bench."
  800)
(def ^:const nudge-commit-time
  "Minimum ms between keyboard-nudge commits while a key is held.
   Nudge writes the file tree instead of using a live modifier preview,
   so this keeps OS key-repeat (~30Hz) from committing on every tick."
  50)
