# 快捷键面板手势条目拆分设计

日期：2026-09-08
状态：已获用户批准

## 背景与目标

快捷键面板（`frontend/src/app/main/ui/workspace/keymap_panel.cljs`）中，5 个手势条目
（click-through / multi-select / drag-canvas / zoom-canvas / measure-distance）目前把
按键和手势词渲染在**单个** `.keymap-gesture` 元素里（如 "⌘ 点击"、"空格 拖动"）。

目标：拆成**两个独立元素** —— 按键段（⌘/⇧/空格/⌥ 等）用现有 `.keymap-key` 键帽样式，
手势词（点击/拖动/滚轮/悬停目标图层）保留 `.keymap-gesture` 胶囊样式。两元素由容器
`.keymap-keys` 的 flex gap（`$s-2`）自然间隔，与键帽组间距一致。

## 数据层 — `frontend/src/app/main/data/keymap.cljs`

`gesture-shortcuts` 的值从每平台一个合并字符串改为每平台一个二元向量
`[按键 手势词]`，沿用现有 `{:windows … :macos …}` 结构，平台选择逻辑不变：

```clojure
(def ^:private gesture-shortcuts
  {:click-through    {:windows ["Ctrl" "点击"]       :macos ["⌘" "点击"]}
   :multi-select     {:windows ["Shift" "点击"]      :macos ["⇧" "点击"]}
   :drag-canvas      {:windows ["空格" "拖动"]       :macos ["空格" "拖动"]}
   :zoom-canvas      {:windows ["Ctrl" "滚轮"]       :macos ["⌘" "滚轮"]}
   :measure-distance {:windows ["Alt" "悬停目标图层"] :macos ["⌥" "悬停目标图层"]}})
```

`gesture-text` 重命名为 `gesture-parts`，返回当前平台的 `[key gesture]` 向量。
字符串是按平台准备好的显示文本，**不再**过 `convert-char`。

## 渲染层 — `keymap_panel.cljs` 的 `shortcut-keys*`

手势分支从单个 `[:span.keymap-gesture 全文本]` 改为 Fragment 包裹的两个兄弟元素：

```clojure
[:*
 [:span {:class (stl/css :keymap-key)} 按键]
 [:span {:class (stl/css :keymap-gesture)} 手势词]]
```

`[:* …]` 是 rumext v2 的 Fragment 语法（compiler.clj `:*` handler →
`rumext.v2/Fragment`），仓库已有大量先例（如 `sidebar/options/page.cljs:57`）。

## SCSS

无改动 —— `.keymap-key` 与 `.keymap-gesture` 均已存在且样式正确。

## 已否决的备选

保留合并字符串、渲染时按空格拆分 —— 依赖字符串格式，脆弱；
`measure-distance` 的 "悬停目标图层" 若未来含空格即崩坏。

## 测试

- `gesture-parts` 是纯数据函数：新增 frontend 测试，覆盖全部 5 个手势条目，
  断言返回二元字符串向量（非手势 kw 返回 nil）。
- 渲染本身无现有测试基建：靠浏览器（devenv）验证 important / selection / zoom
  三个含手势的 tab，mac 与 windows 平台字符串路径各查一遍（代码走查即可，浏览器
  只验当前平台）。

## 影响面

`gesture?` / `gesture-text` 的唯一调用方就是 `keymap_panel.cljs`（已 grep 确认），
无其他消费方；`keymap.cljs:140` docstring 中提及 `gesture-text` 的注释同步更新。
