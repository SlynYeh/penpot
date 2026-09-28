# Workspace 底部 Keymap 快捷键弹层面板 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **本计划供下次会话执行（本次不实施）。** 按任务串行派发子代理，每任务完成后主代理审查再派下一个。

**Goal:** 在 workspace 页新增仿 Pixso 的底部非模态快捷键面板（tab 区 + 多列 keymap 列表，每列最多 4 条），并移除旧的侧栏 shortcuts 面板。

**Architecture:** keymap 配置独立成纯数据 ns（`app.main.data.keymap`，只存「tab → 快捷键 keyword 列表」），按键与文案渲染时从真实 shortcuts map（workspace + path + text 深合并）解析——按键永远准确，条目文案复用已有 `shortcuts.<name>` msgid，只需新增 8 个 tab 名 msgid。UI 为新组件 `keymap-panel*`（本地 state 默认打开，× 关闭），挂载在 `workspace-content*` 的 palette 旁。非模态（wrapper `pointer-events:none`）。

**Tech Stack:** ClojureScript (Rumext v2 + Potok + SCSS modules)，i18n 走 .po + `pnpm run translations`，测试 cljs.test（shadow-cljs test build）。

**用户已确认的决策**（2026-09-04）：
1. 8 个 tab：重要快捷键 / 工具&视图 / 文本 / 选择 / 缩放 / 图层 / 编辑 / 排列；内容取 Penpot 真实快捷键
2. 入口后续设计；**暂时默认打开**；**移除**旧侧栏面板
3. 文案走标准 i18n（.po）
4. 非模态浮动面板（无遮罩，画布可交互）

**Git 纪律（仓库规则，优先于一切模板）**：不自动 commit/push；仅当用户明确要求时才提交，格式 `:emoji: 祈使句 ≤70字符` + 正文 + `Signed-off-by`（`git commit -s`）+ `AI-assisted-by: <model>`。建议的提交信息见文末附表。

**已核实的事实**（实施时无需重复验证）：
- 挂载点：`frontend/src/app/main/ui/workspace.cljs` `workspace-content*` 的 `[:*` fragment，palette 块在 90-92 行；父级 `section.workspace`（268-271 行）已是 `position:relative`
- 旧面板：`frontend/src/app/main/ui/workspace/sidebar/shortcuts.cljs`（+ 同名 .scss），仅被 `sidebar.cljs`（require ~40 行 / binding 129 行 / 渲染 198-200 行）引用
- 文本快捷键在 `app.main.data.workspace.text.shortcuts/shortcuts`（236 行起，:bold/:italic/:underline/:line-through/:font-size-inc/:font-size-dec 全存在）；其余 76 个草稿 keyword 已逐一核实存在于 workspace/path shortcuts
- `ds/c-mod` 在 mac 产出 `command+X`、win 产出 `ctrl+X` token；`ds/split-sc` 按 `+`/空格切分为 token 向量
- `icon-button*`：ns `app.main.ui.ds.buttons.icon-button`，props `{:variant "ghost" :icon i/close :aria-label ... :on-click ...}`（先例 sidebar.cljs:26）；`i/close` = "close"（icon.cljs:99）
- SCSS token 全部存在：`$br-4/$br-8`、`$s-1/2/4/8/12/16/20/24/192`、`deprecated.$z-index-10`（palette 用 $z-index-2，modal=300/dropdown=400）；语义 var `--panel-background-color/--panel-border-color/--menu-shortcut-background-color/--color-foreground-primary/--color-foreground-secondary/--color-accent-primary`
- `use-typography("body-small"|"body-medium")` mixin 存在（ds/typography.scss:113，tab_switcher.scss 有 import 先例）
- msgid 存在：`labels.close`（en.po:2771）、`shortcuts.title`、全部 82 个 `shortcuts.<key>`（草稿 keyword 均查过）
- main_menu 无需改动：`file-menu-shortcuts` 菜单项本就在被 `#_` 隐藏的 help-info 子菜单内（~856-866 行）
- 测试注册：`frontend/test/frontend_tests/runner.cljs` require 列表（7-66 行）+ `test-namespaces` 向量（79-141 行），143 行 assert 强制两者一致

---

### Task 0: 保存计划副本到仓库（主代理自做，不派子代理）

**Files:**
- Create: `docs/superpowers/plans/2026-09-04-workspace-keymap-panel.md`

- [x] **Step 1: 复制计划文件**

```bash
cp /Users/slynyeh/.claude/plans/workspace-tab-tab-content-keymap-keymap-rosy-sparrow.md \
   /Users/slynyeh/Downloads/penpot-2.17.0/docs/superpowers/plans/2026-09-04-workspace-keymap-panel.md
```

Expected: 文件存在，`git status` 显示 untracked 新文件。

---

### Task 1: 数据层 `app.main.data.keymap`（TDD：先测试后实现）

**Files:**
- Test: `frontend/test/frontend_tests/data_keymap_test.cljs`（新建）
- Modify: `frontend/test/frontend_tests/runner.cljs`（注册测试 ns）
- Create: `frontend/src/app/main/data/keymap.cljs`

- [x] **Step 1: 写失败测试**

创建 `frontend/test/frontend_tests/data_keymap_test.cljs`：

```clojure
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.keymap-test
  (:require
   [app.config :as cf]
   [app.main.data.keymap :as km]
   [app.main.data.shortcuts :as ds]
   [cljs.test :as t]))

(t/deftest tabs-are-nonempty-and-unique
  (t/is (seq km/tabs))
  (let [ids (map :id km/tabs)]
    (t/is (= (count ids) (count (distinct ids)))))
  (doseq [tab km/tabs]
    (t/is (keyword? (:id tab)) (:id tab))
    (t/is (pos? (count (:shortcuts tab))) (:id tab))))

(t/deftest all-shortcuts-resolve
  (doseq [tab km/tabs
          kw (:shortcuts tab)]
    (t/is (some? (km/get-entry kw)) kw)
    (t/is (some? (km/get-display-command kw)) kw)
    (t/is (seq (km/display-chars kw)) kw)))

(t/deftest tab-columns-partition
  (t/is (= [4 3] (mapv count (km/tab-columns {:shortcuts (range 7)}))))
  (t/is (= [4 4] (mapv count (km/tab-columns {:shortcuts (range 8)}))))
  (t/is (= [1] (mapv count (km/tab-columns {:shortcuts [:a]}))))
  (t/is (= (range 7) (vec (apply concat (km/tab-columns {:shortcuts (range 7)}))))))

(t/deftest convert-char-platform
  (with-redefs [cf/check-platform? (constantly true)]
    (t/is (= "⌘" (km/convert-char "command")))
    (t/is (= "⇧" (km/convert-char "shift")))
    (t/is (= "⌥" (km/convert-char "alt")))
    (t/is (= "z" (km/convert-char "z"))))
  (with-redefs [cf/check-platform? (constantly false)]
    (t/is (= "command" (km/convert-char "command")))
    (t/is (= "ctrl" (km/convert-char "ctrl"))))
  ;; 方向键/加号替换与平台无关
  (t/is (= ds/up-arrow (km/convert-char "up")))
  (t/is (= "+" (km/convert-char "plus"))))

(t/deftest display-chars-first-alternative
  (let [vec-kw (some (fn [kw]
                       (let [cmd (km/get-display-command kw)]
                         (when (vector? cmd) kw)))
                     (mapcat :shortcuts km/tabs))]
    (t/is (some? vec-kw))
    (let [cmd (km/get-display-command vec-kw)]
      (t/is (= (ds/split-sc (first cmd)) (km/display-chars vec-kw))))))
```

- [x] **Step 2: 注册测试 ns**

修改 `frontend/test/frontend_tests/runner.cljs`：在 ns 的 `:require` 列表按字母序插入一行；在 `test-namespaces` 向量按字母序插入 `'frontend-tests.data.keymap-test`。**两处都必须加**（143 行 assert 校验一致）：

```clojure
[frontend-tests.data.keymap-test]
```
```clojure
'frontend-tests.data.keymap-test
```

- [x] **Step 3: 跑测试确认失败（RED）**

```bash
cd /Users/slynyeh/Downloads/penpot-2.17.0/frontend && pnpm run test:quiet -- --focus frontend-tests.data.keymap-test
```

Expected: 编译错误——找不到命名空间 `app.main.data.keymap`（pnpm 异常时回退：`pnpm run build:test && node target/tests/test.js --focus frontend-tests.data.keymap-test`）。

- [x] **Step 4: 写实现**

创建 `frontend/src/app/main/data/keymap.cljs`：

```clojure
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
```

- [x] **Step 5: 跑测试确认通过（GREEN）**

```bash
pnpm run test:quiet -- --focus frontend-tests.data.keymap-test
```

Expected: 5 个 deftest 全 PASS（`Testing frontend-tests.data.keymap-test`，0 failures）。

- [x] **Step 6: lint**

```bash
pnpm run lint:clj && pnpm run check-fmt:clj
```

Expected: 无 new warning（若编辑造成括号损伤先跑 `../tools/paren-repair.bb <file>`）。若 cljfmt 报排序，跑 `pnpm run fmt:clj`。

---

### Task 2: 面板组件 `keymap-panel*` + 样式

**Files:**
- Create: `frontend/src/app/main/ui/workspace/keymap_panel.scss`
- Create: `frontend/src/app/main/ui/workspace/keymap_panel.cljs`

- [x] **Step 1: 写 SCSS**

创建 `frontend/src/app/main/ui/workspace/keymap_panel.scss`：

```scss
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC Sucursal en España SL

@use "ds/spacing.scss" as *;
@use "ds/typography.scss" as *;
@use "ds/z-index.scss" as *;
@use "ds/_sizes.scss" as *;
@use "refactor/common-refactor.scss" as deprecated;

.keymap-wrapper {
  position: absolute;
  inset-inline: 0;
  bottom: 0;
  z-index: deprecated.$z-index-10;
  display: flex;
  justify-content: center;
  padding-inline: deprecated.$s-16;
  pointer-events: none; // 非模态：wrapper 不拦截画布交互
}

.keymap-panel {
  pointer-events: auto;
  width: min(100%, 1200px);
  max-height: 40vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background-color: var(--panel-background-color);
  border: deprecated.$s-1 solid var(--panel-border-color);
  border-radius: deprecated.$br-8 deprecated.$br-8 0 0;
}

.keymap-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: deprecated.$s-8;
  padding-inline-end: deprecated.$s-8;
}

.keymap-tabs {
  display: flex;
  gap: deprecated.$s-16;
  margin: 0;
  padding: 0;
  list-style: none;
  overflow-x: auto;
  align-self: stretch;
  border-bottom: deprecated.$s-1 solid var(--panel-border-color);
}

.keymap-tab {
  @include use-typography("body-medium");

  appearance: none;
  border: none;
  background: none;
  cursor: pointer;
  white-space: nowrap;
  padding: deprecated.$s-8 deprecated.$s-4;
  color: var(--color-foreground-secondary);
  border-bottom: deprecated.$s-2 solid transparent;

  &.selected {
    color: var(--color-foreground-primary);
    font-weight: 600;
    border-bottom-color: var(--color-accent-primary);
  }
}

.keymap-content {
  display: flex;
  gap: deprecated.$s-24;
  padding: deprecated.$s-12 deprecated.$s-16;
  overflow-x: auto;
}

.keymap-column {
  display: flex;
  flex-direction: column;
  gap: deprecated.$s-4;
  flex-shrink: 0;
  width: deprecated.$s-192;
}

.keymap-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: deprecated.$s-16;
  min-height: deprecated.$s-24;
}

.keymap-item-label {
  @include use-typography("body-small");

  color: var(--color-foreground-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.keymap-keys {
  display: flex;
  gap: deprecated.$s-2;
  flex-shrink: 0;
}

.keymap-key {
  @include use-typography("body-small");

  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: deprecated.$s-20;
  min-height: deprecated.$s-20;
  padding: 0 deprecated.$s-4;
  border-radius: deprecated.$br-4;
  background-color: var(--menu-shortcut-background-color);
  border: deprecated.$s-1 solid var(--panel-border-color);
}
```

- [x] **Step 2: 写组件**

创建 `frontend/src/app/main/ui/workspace/keymap_panel.cljs`：

```clojure
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
               (on-change id)))))]
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

(mf/defc keymap-item*
  {::mf/private true}
  [{:keys [kw]}]
  [:div {:class (stl/css :keymap-item)}
   [:span {:class (stl/css :keymap-item-label)}
    (tr (str "shortcuts." (d/name kw)))]
   [:span {:class (stl/css :keymap-keys)}
    (for [char (km/display-chars kw)]
      [:span {:class (stl/css :keymap-key)
              :key (dm/str (d/name kw) "-" char)}
       (km/convert-char char)])]])

(mf/defc keymap-content*
  {::mf/private true}
  [{:keys [selected]}]
  ;; 用 (keyword selected) 直接查 km/tabs（其 :id 是 keyword）；
  ;; UI tabs 向量的 id 是字符串，仅用于 tab bar
  (let [tab (some #(when (= (keyword selected) (:id %)) %) km/tabs)]
    [:div {:class (stl/css :keymap-content)
           :role "tabpanel"
           :tab-index 0
           :aria-labelledby (str "keymap-tab-" selected)}
     (when tab
       (for [[idx column] (map-indexed vector (km/tab-columns tab))]
         [:div {:class (stl/css :keymap-column)
                :key idx}
          (for [kw column]
            [:> keymap-item* {:key (d/name kw) :kw kw}])]))]))

(mf/defc keymap-panel*
  {::mf/memo true}
  [{:keys [class] :rest props}]
  (let [open? (mf/use-state true)
        selected (mf/use-state "important")
        tabs [{:id "important" :label (tr "keymap.tab.important")}
              {:id "tools-view" :label (tr "keymap.tab.tools-view")}
              {:id "text" :label (tr "keymap.tab.text")}
              {:id "selection" :label (tr "keymap.tab.selection")}
              {:id "zoom" :label (tr "keymap.tab.zoom")}
              {:id "layers" :label (tr "keymap.tab.layers")}
              {:id "edit" :label (tr "keymap.tab.edit")}
              {:id "arrange" :label (tr "keymap.tab.arrange")}]
        on-close (mf/use-callback #(reset! open? false))
        on-change (mf/use-callback #(reset! selected %))]
    (when @open?
      [:div (mf/spread-props props {:class [class (stl/css :keymap-wrapper)]})
       [:div {:class (stl/css :keymap-panel)
              :role "region"
              :aria-label (tr "shortcuts.title")}
        [:div {:class (stl/css :keymap-header)}
         [:> keymap-tab-bar* {:tabs tabs :selected @selected :on-change on-change}]
         [:> icon-button* {:variant "ghost"
                           :icon i/close
                           :aria-label (tr "labels.close")
                           :on-click on-close}]]
        [:> keymap-content* {:selected @selected}]]])))
```

- [x] **Step 3: lint**

```bash
pnpm run lint:clj && pnpm run lint:scss && pnpm run check-fmt:clj && pnpm run check-fmt:scss
```

Expected: 无 error / 无 new warning。

**评审修订（2026-09-04，Task 2 质量评审结论，替代上方代码块中的对应片段）：**

1. **焦点跟随选中（Important）**：`keymap-tab-bar*` 的 `on-key-down` 在 `(on-change id)` 后必须把 DOM 焦点移到新选中的 tab 按钮（镜像 `app.main.ui.ds.layout.tab_switcher` 的 `on-key-down` 中 `dom/focus!` 模式，按钮 id 即 `keymap-tab-<id>`）。上方代码块漏掉此步属计划缺陷，已由实现者修复。
2. **msgid 提取引用（Minor，Task 4 前必须）**：`keymap_panel.cljs` 需从 `sidebar/shortcuts.cljs` 移植 `(comment (tr "shortcuts.…") …)` 提取提示块（覆盖面板用到的 `shortcuts.*` 键），否则 Task 4 删除旧面板后 rehash 会将全部 `shortcuts.*` 标记 unused，未来清理会把动态 `(tr (str "shortcuts." …))` 的 msgid 删掉，面板标签退化为裸 msgid。
3. **spread-props 内联崩溃（Critical，2026-09-05 Task 5 端到端验证发现）**：上方代码块 `[:div (mf/spread-props props {:class [...]}) ...]` 把 `mf/spread-props` 内联写在 props 位置——Rumext 编译器该位置只接受裸局部变量，list 形式会被编译进 children，运行时 React 抛 "Objects are not valid as a React child" 导致**整个 workspace 崩溃**（3 次复现 100% 确定）。正确惯例（forms.cljs / icon_button.cljs / comments.cljs）：`let` 绑定 + **interop 形式 `[:> :div wrapper-props ...]`**（裸局部配普通关键字元素仍会编译进 children，v2.25 compiler.clj:297,314）。此缺陷源自计划代码块本身，以独立 bugfix 提交修复（`f6e0a74b`）。
4. **键帽文字颜色缺失（2026-09-05 Task 5 复验发现）**：本 fork 全局 body 基色为黄色，`.keymap-key` 未显式设 `color` → 键帽文字两主题下均渲染为黄色。修复：`.keymap-key` 增加 `color: var(--color-foreground-primary)`。
5. **escape 键帽字面量（2026-09-05 Task 5 复验发现）**：命令 token 为字符串 `"escape"`，`macos-keys` 只映射 `:esc` → mac 上显示字面 "escape"（旧面板同病，非回归）。修复：`macos-keys` 增加 `:escape "⎋"`（win 保持字面量）。

---

### Task 3: 挂载 + i18n

**Files:**
- Modify: `frontend/src/app/main/ui/workspace.cljs`（require 列表 + `workspace-content*`）
- Modify: `frontend/translations/en.po`、`frontend/translations/zh_CN.po`

- [x] **Step 1: 加 require**

`frontend/src/app/main/ui/workspace.cljs` 的 ns `:require` 列表按字母序插入：

```clojure
[app.main.ui.workspace.keymap-panel :refer [keymap-panel*]]
```

- [x] **Step 2: 挂载组件**

`workspace-content*` 中 palette 块（90-92 行）之后追加同级片段：

```clojure
     [:*
      (when (not ^boolean hide-ui?)
        [:> palette* {:layout layout
                      :on-change-size on-resize-palette}])

      (when (not ^boolean hide-ui?)
        [:> keymap-panel* {}])
```

（只添加第二个 `when` 块；palette 块保持原样。）

- [x] **Step 3: 提取新 msgid**

```bash
pnpm run translations rehash
```

Expected: `en.po`/`zh_CN.po` 中自动新增 8 个空 msgstr 条目：`keymap.tab.important/tools-view/text/selection/zoom/layers/edit/arrange`。

- [x] **Step 4: 填写翻译**

`en.po`：

```po
msgid "keymap.tab.arrange"
msgstr "Arrange"

msgid "keymap.tab.edit"
msgstr "Edit"

msgid "keymap.tab.important"
msgstr "Important"

msgid "keymap.tab.layers"
msgstr "Layers"

msgid "keymap.tab.selection"
msgstr "Selection"

msgid "keymap.tab.text"
msgstr "Text"

msgid "keymap.tab.tools-view"
msgstr "Tools & view"

msgid "keymap.tab.zoom"
msgstr "Zoom"
```

`zh_CN.po`：

```po
msgid "keymap.tab.arrange"
msgstr "排列"

msgid "keymap.tab.edit"
msgstr "编辑"

msgid "keymap.tab.important"
msgstr "重要快捷键"

msgid "keymap.tab.layers"
msgstr "图层"

msgid "keymap.tab.selection"
msgstr "选择"

msgid "keymap.tab.text"
msgstr "文本"

msgid "keymap.tab.tools-view"
msgstr "工具&视图"

msgid "keymap.tab.zoom"
msgstr "缩放"
```

（按 rehash 实际生成的位置/顺序填 msgstr 即可，不要求块序一致。）

- [x] **Step 5: lint**

```bash
pnpm run lint:clj
```

Expected: 干净。**注意：翻译编译进 index.html，验证时需硬刷新浏览器才生效。**

---

### Task 4: 移除旧侧栏 shortcuts 面板

**Files:**
- Modify: `frontend/src/app/main/ui/workspace/sidebar.cljs`
- Delete: `frontend/src/app/main/ui/workspace/sidebar/shortcuts.cljs`、`frontend/src/app/main/ui/workspace/sidebar/shortcuts.scss`
- Modify（仅注释）: `frontend/src/app/main/data/workspace/shortcuts.cljs`

- [x] **Step 1: 删 sidebar.cljs 的三处引用**

1. ns `:require` 中删除（~40 行）：
```clojure
[app.main.ui.workspace.sidebar.shortcuts :refer [shortcuts-container*]]
```
2. `left-sidebar*` let 中删除（129 行）：
```clojure
shortcuts?     (contains? layout :shortcuts)
```
3. `cond` 中删除首个分支（198-200 行），使 cond 从 `show-debug?` 开始：
```clojure
      (cond
        (true? shortcuts?)
        [:> shortcuts-container* {:class (stl/css :settings-bar-content)}]

        (true? show-debug?)
```
改为：
```clojure
      (cond
        (true? show-debug?)
```

- [x] **Step 2: 删除旧面板文件**

```bash
rm src/app/main/ui/workspace/sidebar/shortcuts.cljs \
   src/app/main/ui/workspace/sidebar/shortcuts.scss
```

- [x] **Step 3: 确认无残留引用**

```bash
grep -rn "sidebar.shortcuts\|shortcuts-container" src/ test/
```

Expected: 无输出。

- [x] **Step 4: 给失效 flag 加说明注释**

`frontend/src/app/main/data/workspace/shortcuts.cljs` 的 `:show-shortcuts` 条目（~466 行）上方加一行：

```clojure
;; NOTE(keymap): :shortcuts layout flag is no longer rendered (old sidebar panel removed); new keymap-panel entry point TBD
```

- [x] **Step 5: lint + 全量测试**

```bash
pnpm run lint:clj && pnpm run check-fmt:clj && pnpm run test:quiet
```

Expected: lint 干净；全量测试 PASS（sidebar/workspace require 变更能抓到死引用 fallout）。

---

### Task 5: 端到端验证（devenv 视觉 + 收尾）

**Files:** 无新改动（只验证与微调）

- [x] **Step 1: 触发 watch 重编译**

宿主机改动默认不触发容器内 watch（见 memory `devenv-watch-inotify-touch`）：

```bash
docker exec <frontend容器名> touch /home/penpot/frontend/src/app/main/ui/workspace.cljs
```

（容器名以 `docker ps` 为准；或等 watch 自行 pick up。）

- [x] **Step 2: 浏览器硬刷新验证清单**

打开 `https://localhost:3449`（devenv main 实例）进入任一文件 workspace，逐项检查：

1. 底部面板**默认出现**：面板底色、上圆角下贴边、宽度居中（≤1200px）
2. 8 个 tab，选中态 = **加粗 + 下划线**；点击/左右方向键可切换
3. 逐 tab 检查：条目**每列 ≤4 条、从左往右**列填充；动作名左侧、键帽右侧
4. mac 键帽符号正确（⌘ ⇧ ⌥ ⌫），Windows 浏览器显示 Ctrl/Shift/Alt
5. 面板开着时点击面板上方画布可正常框选/拖动画布（wrapper `pointer-events:none` 生效）
6. `×` 关闭面板、底部 palette 露出；刷新页面面板**再次默认打开**
7. 中文界面 tab 名显示中文（`.po` 改动需硬刷新）
8. 暗色主题下面板配色正常（语义 var 生效）
9. 窄窗口：tab 区与内容区横向滚动
10. `\`（hide-ui）时面板隐藏
11. 旧面板确认死亡：按 `?` 无侧栏面板出现；主菜单无 shortcuts 入口（原入口本就被 fork 隐藏）
12. 窄视口（~1366px）：观察面板与右侧栏底部重叠（评审已接受的外观取舍，记录现象即可）
13. 关闭面板 → 切换页面再切回：确认面板不会意外重现（loader 分支 remount 边缘情况，Task 2 评审遗留验证项）
14. 中文界面：8 个 tab 名与条目文案渲染为中文（zh_CN 为手工镜像条目，验证格式无误）

- [x] **Step 3: 收尾 lint/fmt**

```bash
pnpm run fmt:clj && pnpm run fmt:scss && pnpm run lint:clj && pnpm run lint:scss && pnpm run check-fmt:clj && pnpm run check-fmt:scss
```

Expected: 全部干净。

- [x] **Step 4: 汇报**

向用户汇报：完成项清单、测试结果原文、视觉验证截图（可用 playwright MCP 截图）、任何偏离计划的调整及原因。

---

## 已知取舍（用户已接受）

- **多候选按键只显示第一个**（如 `:delete` 只显示 Del；旧面板显示全部+「或」）——紧凑布局，后续可改
- **默认打开会盖住底部 palette**——入口设计好后默认态再议
- **`:shortcuts` flag 与 `?` 键暂时失效但保留**——为后续入口接线预留（Task 4 Step 4 有注释）
- DS `tab-switcher*` 不复用（pill 选中态无下划线样式入口），手写 40 行 ARIA tablist
- **搜索功能随旧面板移除**（旧面板有实时搜索框与 not-found 空态）——后续入口设计时一并考虑（2026-09-05 最终评审补录）
- **清单为精选子集而非全量**：dashboard/viewer 快捷键与 ~90 个未入选的 workspace 快捷键（opacity-0..9、路径编辑器节点操作、measure 等）不再有任何展示入口（2026-09-05 最终评审补录）

## 附：建议提交信息（仅当用户明确要求提交时使用）

| Task | 建议 subject |
|---|---|
| 1 | `:sparkles: Add keymap data config with tab grouping` |
| 2 | `:sparkles: Add workspace keymap bottom panel component` |
| 3 | `:sparkles: Mount keymap panel and add tab translations` |
| 4 | `:fire: Remove legacy sidebar shortcuts panel` |

均需 `git commit -s`（Signed-off-by）+ `AI-assisted-by: <model-name>` trailer。
