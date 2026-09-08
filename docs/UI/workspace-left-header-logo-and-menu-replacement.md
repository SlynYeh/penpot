# Workspace page left header logo & menu replacement requirements

替换workspace页面左侧header的logo和menu菜单按钮及功能

## 具体要求

- 移除logo图标按钮
- 原menu菜单按钮移动到logo位置
  - 替换menu图标按钮 (附件提供新的menu图标svg代码)
  - 按钮宽高 32 * 32、圆角4px、字体色 #495e74、hover背景色 #f3f4f6、hover字体色 #6911D4
  - 图标尺寸32px，铺满按钮
  - 原菜单点击展开下拉菜单功能保持不变
- 原菜单按钮位置增加[帮助中心]按钮
  - height 32px、padding 6px 12px、radius 4px、字体色 #495e74、hover背景色 ~~#f2f3f6~~（终审裁定为笔误，统一用设计系统 `$gray-50`）、hover字体色 #6911D4
  - 按钮组成：?图标、文字。复用现有的帮助中心图标
  - 点击按钮显示下拉菜单
    - 菜单项：快捷键指南（打开显示快捷键面板浮层）、新手教程（新窗口打开 PENPOT_HELP_CENTER_URI 地址）、常见问题（新窗口打开跳转 PENPOT_LEARNING_CENTER_URI 地址）
    - 菜单样式参考menu菜单



## 附件

menu 图标
```xml
<svg viewBox="0 0 32 32" xmlns="http://www.w3.org/2000/svg" width="1em" height="1em">
	<g>
		<path d="M10 10L22 10" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round" stroke-width="1.2" />
		<path d="M10 16L22 16" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round" stroke-width="1.2" />
		<path d="M10 22L22 22" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round" stroke-width="1.2" />
	</g>
</svg>
```

## 已确认决策与实现说明（2026-09-08 草图确认）

- 布局：`≡` 菜单按钮放在左 sidebar header 最左（原 logo 位置），`? 帮助中心` 按钮放在最右（原菜单位置），两者用 space-between 拉开；主菜单下拉在按钮下方左对齐（距 sidebar 左缘 12px），帮助下拉在触发按钮下方右对齐，主菜单的子菜单向右侧弹出（12 + 192 + 2px 锚定）。
- 图标：保持附件的线型风格，但图形放大至 16px 宽（x:8→24）、线宽 2px、圆头端点；附件原样（12px 宽 / 1.2px 线宽）在 32×32 按钮中视觉过轻，草图评审确认放大。
- 快捷键面板：默认关闭；通过帮助菜单「快捷键指南」项或按 `?` 键开关，面板右上角 `✕` 关闭；可见性改由 store 的 `:shortcuts` layout flag 驱动（会话级状态，不做持久化）。
- 帮助菜单项不加 `?` 键帽提示；「新手教程」「常见问题」两个外链项带 external-link 图标，点击新窗口打开并上报 analytics 事件。
- 两个触发按钮的 hover/focus 背景统一用设计系统 `$gray-50`（#f3f4f6，2026-09-08 终审确认；原文档帮助按钮的 #f2f3f6 为笔误）。
- URI 未配置时隐藏对应菜单项：新手教程 → `PENPOT_HELP_CENTER_URI`（config.js 的 `penpotHelpCenterURI`），常见问题 → `PENPOT_LEARNING_CENTER_URI`（`penpotLearningCenterURI`）；标签与 URI 的命名交叉是有意为之，勿"修正"。
- 实现落点：
  - `frontend/src/app/main/ui/icons.cljs` + `frontend/resources/images/icons/menu-lines.svg`（新 menu-lines 图标）
  - `frontend/src/app/main/ui/workspace/main_menu.cljs` / `main_menu.scss`（触发按钮换装、下拉/子菜单重锚定）
  - `frontend/src/app/main/ui/workspace/help_center_menu.cljs` / `help_center_menu.scss`（新帮助中心组件）
  - `frontend/src/app/main/ui/workspace/left_header.cljs` / `left_header.scss`（logo 移除、头部组装）
  - `frontend/src/app/main/ui/workspace/keymap_panel.cljs` + `frontend/src/app/main/ui/workspace.cljs`（面板可见性接入 `:shortcuts` flag，默认关闭）
  - `frontend/src/app/main/data/workspace/shortcuts.cljs`（`?` 键开关联动，既有绑定）
  - `frontend/translations/en.po` + `zh_CN.po`（`workspace.header.help.option.{shortcuts,tutorials,faq}`）
- 已知后续项：`left_header.cljs` 中仍保留存量死代码（文件名编辑、返回项目等未用 binding 及 refs/persistence 活跃订阅），按最小 diff 决策本次未清理；`clj-kondo` 对该文件的 unused 警告与改动前完全一致。
