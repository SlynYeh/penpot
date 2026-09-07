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

(def ^:private gesture-shortcuts
  {:click-through    {:windows "Ctrl 点击"        :macos "⌘ 点击"}
   :multi-select     {:windows "Shift 点击"       :macos "⇧ 点击"}
   :drag-canvas      {:windows "空格 拖动"        :macos "空格 拖动"}
   :zoom-canvas      {:windows "Ctrl 滚轮"        :macos "⌘ 滚轮"}
   :measure-distance {:windows "Alt 悬停目标图层" :macos "⌥ 悬停目标图层"}})

(defn gesture?
  [kw]
  (contains? gesture-shortcuts kw))

(defn gesture-text
  [kw]
  (when-let [entry (get gesture-shortcuts kw)]
    (if (cf/check-platform? :macos)
      (:macos entry)
      (:windows entry))))

;; keymap 面板配置：tab → 快捷键 keyword 列表（关键字既可来自上面
;; shortcuts map，也可来自 gesture-shortcuts；按键与文案渲染时实时解析，
;; 保持与真实绑定/手势描述一致；跨 tab 重复是有意的精选）
;; 分组依据：docs/UI/new-keymap-group.md
(def tabs
  ;; 分组依据：docs/UI/new-keymap-group.md
  ;; 每个 tab 下的 :shortcuts 列表按视觉"列优先"读取 doc 表格内容，
  ;; partition-all max-items-per-column 切出的列组成视觉布局，
  ;; 用户从左到右、从上到下逐行阅读时，所遇项顺序恰好等于 doc 的逐行顺序。
  [{:id :important
    :shortcuts [:click-through :drag-canvas :escape :draw-frame
                :multi-select :draw-text :zoom-canvas :group]}
   {:id :tools-view
    :shortcuts [:move :draw-frame :draw-text :draw-rect
                :draw-path :draw-curve :draw-ellipse :open-color-picker
                :add-comment :hide-ui :toggle-colorpalette :toggle-textpalette]}
   {:id :text
    :shortcuts [:bold :underline :font-size-dec :escape
                :font-size-inc]}
   {:id :selection
    :shortcuts [:click-through :select-all :escape :measure-distance
                :start-editing :select-parent-layer :select-next :select-prev]}
   {:id :zoom
    :shortcuts [:drag-canvas :increase-zoom :decrease-zoom :reset-zoom
                :fit-all :zoom-selected :zoom-lense-increase :zoom-lense-decrease]}
   {:id :layers
    :shortcuts [:undo :find :group :ungroup
                :artboard-selection :flip-horizontal :flip-vertical :rename
                :toggle-visibility :toggle-lock :bring-forward :bring-backward
                :bring-front :bring-back]}
   {:id :edit
    :shortcuts [:copy :cut :paste :paste-replace
                :copy-props :paste-props :start-editing :detach-component
                :opacity-0 :opacity-5 :opacity-1]}
   {:id :arrange
    :shortcuts [:align-left :align-right :align-top :align-bottom
                :align-hcenter :align-vcenter :toggle-layout-flex]}])

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
    (assert (or (contains? all-shortcuts kw)
                (contains? gesture-shortcuts kw))
            (str "keymap: unknown shortcut " (d/name kw)))))
