# Left sidebar CSS 调整

## 背景

图层层级过深时，行内容的 min-content 宽度（每层缩进 `--layer-indentation-size` + 图标/名称/操作按钮）
超过侧栏宽度，图层列表被横向自动撑开，超出 left-sidebar 区域、遮挡画布边缘的 ruler。

根因：`.settings-bar-content` 是 `.left-settings-bar` grid 的 item，其内容轨道为 `1fr`（即
`minmax(auto, 1fr)`）。旧的祖先链上没有任何 grid/flex item 的 overflow ≠ visible，深层行的
min-content 一路上传把轨道撑宽；aside 自身 `overflow: visible` 且受 min/max-width 约束，
于是被撑宽的子树直接溢出 aside 绘制到画布上。旧的 `.layers-tab { overflow-x: hidden }`
拦不住——它只裁「超出自己盒子」的内容，而此时整棵子树连同它的盒子一起变宽了，并未发生溢出。

修复思路（横向裁切链 + 纵向 grid/flex 管道）：

- `.sidebar-tab-panel` 设 `display: grid`，使 `.layers-tab` 成为 grid item；
- `.layers-tab` 设 `overflow: hidden`——grid/flex item 的 overflow ≠ visible 时 automatic
  minimum size 归零，min-content 不再向上传递，轨道保持 `1fr` 可用宽，超出部分在侧栏边界内裁切；
- `.layers` 同样 `overflow: hidden` + flex column，深层列表在容器内横向滚动；
- `min-block-size: 0` / `block-size: 100%` / `flex: 1 1 auto` 打通纵向链路，
  列表在固定高度内伸缩滚动。

注：`.tool-window-content` 的 `inline-size: calc(var(--left-sidebar-width) + var(--depth) * …)`
中 `--depth` 只在行内（layer-name）定义，对该容器为 invalid → `inline-size` 退化为 `auto`，等效死代码。

## main_ui_workspace_sidebar__settings-bar-content 增加下列样式

```css
  inset-inline-end: calc(-1 * var(--sp-s));
  min-block-size: 0;
```

## main_ui_workspace_sidebar__left-sidebar-tabs main_ui_ds_layout_tab_switcher__tabs 增加样式

```css
  block-size: 100%;
```

## main_ui_ds_layout_tab_switcher__tab-panel  增加样式

```css
  min-block-size: 0;
```

## main_ui_workspace_sidebar__sidebar-tab-panel 增加样式

```css
  display: grid;
```

## main_ui_workspace_sidebar__layers-tab 增加样式

```css
  overflow: hidden;
  display: flex;
  flex-direction: column;
```

## main_ui_workspace_sidebar_layers__layers 增加样式

```css
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  overflow: hidden;
```

## main_ui_workspace_sidebar_layers__tool-window-content 增加样式

```css
flex: 1 1 auto;
```

