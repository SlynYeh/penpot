;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.keymap
  (:require
   [app.common.data :as d]
   [app.config :as cf]
   [app.main.data.shortcuts :as ds]
   [app.main.data.workspace.path.shortcuts :as psc]
   [app.main.data.workspace.shortcuts :as wsc]
   [app.main.data.workspace.text.shortcuts :as tsc]))

(def max-items-per-column 4)

;; 与旧侧栏面板相同的合并策略：workspace 覆盖 path/text
(def all-shortcuts
  (d/deep-merge psc/shortcuts tsc/shortcuts wsc/shortcuts))

;; keymap 面板配置：tab → 快捷键 keyword 列表（引用上面 shortcuts map 的键，
;; 按键与文案渲染时实时解析，保持与真实绑定一致；跨 tab 重复是有意的精选）
(def tabs
  [{:id :important
    :shortcuts [:undo :redo :copy :cut :paste :delete :duplicate
                :group :ungroup :select-all :escape :hide-ui]}
   {:id :tools-view
    :shortcuts [:move :draw-frame :draw-rect :draw-ellipse :draw-text
                :draw-path :draw-curve :add-comment :insert-image :scale
                :open-color-picker :toggle-focus-mode :toggle-rulers
                :show-pixel-grid :snap-pixel-grid :toggle-guides]}
   {:id :text
    :shortcuts [:draw-text :start-editing :bold :italic :underline
                :line-through :font-size-inc :font-size-dec]}
   {:id :selection
    :shortcuts [:select-all :select-next :select-prev :select-parent-layer
                :escape :find :find-and-replace :delete]}
   {:id :zoom
    :shortcuts [:increase-zoom :decrease-zoom :reset-zoom :fit-all
                :zoom-selected :zoom-lense-increase :zoom-lense-decrease]}
   {:id :layers
    :shortcuts [:toggle-layers :toggle-assets :toggle-history :toggle-lock
                :toggle-visibility :toggle-lock-size :rename :group :ungroup
                :mask :unmask :create-component-variant :detach-component
                :artboard-selection :toggle-layout-flex :toggle-layout-grid]}
   {:id :edit
    :shortcuts [:undo :redo :copy-props :paste-props :paste-replace :delete
                :duplicate :start-editing :find :find-and-replace
                :export-shapes :bool-union :bool-difference :bool-intersection
                :bool-exclude :join-nodes :make-corner :make-curve]}
   {:id :arrange
    :shortcuts [:align-left :align-right :align-top :align-bottom
                :align-hcenter :align-vcenter :h-distribute :v-distribute
                :flip-vertical :flip-horizontal :bring-front :bring-forward
                :bring-backward :bring-back :move-unit-up :move-unit-down
                :move-unit-left :move-unit-right :move-fast-up :move-fast-down
                :move-fast-left :move-fast-right]}])

(defn get-entry
  [kw]
  (get all-shortcuts kw))

(defn get-display-command
  [kw]
  (let [entry (get-entry kw)]
    (or (:show-command entry) (:command entry))))

(defn tab-columns
  "把一个 tab 的条目切成最多 max-items-per-column 条的列（列优先填充）"
  [tab]
  (partition-all max-items-per-column (:shortcuts tab)))

(def ^:private modified-keys
  {:up ds/up-arrow
   :down ds/down-arrow
   :left ds/left-arrow
   :right ds/right-arrow
   :plus "+"})

(def ^:private macos-keys
  {:command "⌘"
   :option "⌥"
   :alt "⌥"
   :delete "⌫"
   :del "⌫"
   :shift "⇧"
   :control "⌃"
   :esc "⎋"
   :escape "⎋"
   :enter "⏎"})

(defn convert-char
  "单个按键 token 的显示转换：方向键/加号始终替换；mac 下修饰键转符号"
  [char]
  (let [char (if (contains? modified-keys (keyword char))
               (get modified-keys (keyword char))
               char)
        char (if (and (cf/check-platform? :macos)
                      (contains? macos-keys (keyword char)))
               (get macos-keys (keyword char))
               char)]
    char))

(defn display-chars
  "条目的键帽字符序列；多候选 command 只取第一个候选"
  [kw]
  (let [command (get-display-command kw)
        command (if (vector? command) (first command) command)]
    (ds/split-sc command)))

(when *assert*
  (doseq [tab tabs
          kw (:shortcuts tab)]
    (assert (contains? all-shortcuts kw)
            (str "keymap: unknown shortcut " (d/name kw)))))
