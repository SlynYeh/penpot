;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.shape-name
  (:require
   [app.util.i18n :refer [tr]]))

(def ^:private default-name->tr-key
  {"Rectangle" "inspect.tabs.code.selected.rect"
   "Ellipse"   "inspect.tabs.code.selected.ellipse"
   "Circle"    "inspect.tabs.code.selected.circle"
   "Board"     "inspect.tabs.code.selected.frame"
   "Group"     "inspect.tabs.code.selected.group"
   "Path"      "inspect.tabs.code.selected.path"
   "Text"      "inspect.tabs.code.selected.text"
   "Image"     "inspect.tabs.code.selected.image"
   "Mask"      "inspect.tabs.code.selected.mask"
   "Curve"     "inspect.tabs.code.selected.curve"
   "Bool"      "inspect.tabs.code.selected.bool"
   "Component" "inspect.tabs.code.selected.component"
   "SVG"       "inspect.tabs.code.selected.svg-raw"})

;; Execution time translation strings:
;;   (tr "inspect.tabs.code.selected.bool")
;;   (tr "inspect.tabs.code.selected.circle")
;;   (tr "inspect.tabs.code.selected.component")
;;   (tr "inspect.tabs.code.selected.curve")
;;   (tr "inspect.tabs.code.selected.ellipse")
;;   (tr "inspect.tabs.code.selected.frame")
;;   (tr "inspect.tabs.code.selected.group")
;;   (tr "inspect.tabs.code.selected.image")
;;   (tr "inspect.tabs.code.selected.mask")
;;   (tr "inspect.tabs.code.selected.path")
;;   (tr "inspect.tabs.code.selected.rect")
;;   (tr "inspect.tabs.code.selected.svg-raw")
;;   (tr "inspect.tabs.code.selected.text")
;;   (tr "workspace.options.flows.flow")

(defn localized-layer-name
  "Translate default English layer names (Rectangle, Rectangle 2, …) for display.
   Custom names are returned unchanged."
  [name]
  (if-let [[_ base suffix] (re-matches #"^(Rectangle|Ellipse|Circle|Board|Group|Path|Text|Image|Mask|Curve|Bool|Component|SVG)( \d+)?$" (str name))]
    (str (tr (get default-name->tr-key base)) (or suffix ""))
    name))

(defn localized-flow-name
  "Translate default English flow names (Flow, Flow 1, …) for display.
   Custom names are returned unchanged. Does not mutate stored data."
  [name]
  (if-let [[_ _ suffix] (re-matches #"^(Flow)( \d+)?$" (str name))]
    (str (tr "workspace.options.flows.flow") (or suffix ""))
    name))
