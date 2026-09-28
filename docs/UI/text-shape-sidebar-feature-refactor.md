# 文字图形侧边工具栏功能布局调整
文字图形选中态侧边栏sidebar功能和布局调整优化

## 细节描述

- 隐藏（注释掉）大小写切换功能组：整个text-transform
- 增加加粗功能按钮
  - 位置：按钮排列于字体类型下一行首位置
  - 样式：宽高 32px，其它样式保持与现有功能按钮相同
  - 功能：点击为当前选中的文字图形切换加粗样式状态，已加粗时取消加粗、未加粗时添加加粗
  - 联动：当前选中文字图形已加粗时，按钮激活高亮；未加粗/取消加粗时，按钮恢复默认状态
- 调整字重下拉选择
  - 修改字重功能下拉选择项文案，选项值仍映射为原来的
    - 100: Thin
    - 200: ExtraLight
    - 300: Light
    - 400: Regular
    - 500: Medium
    - 600: SemiBold
    - 700: Bold
    - 800: ExtraBold
    - 900: Black
  - 位置：下拉选择位置移动到字号选择之前、加粗之后，与字号选择、加粗同行
  - 样式：宽度110px，其它保持原状
- 调整字号下拉选择
  - 样式：宽度146px，其它保持原状
  - 位置：下拉选择位置移动到移动后的字重下拉选择之后，与字号选择、加粗同行
- 保持现有规则：当文字应用了排版typography时，main_ui_workspace_sidebar_options_menus_typography__font-modifiers行不可见
- 隐藏（注释掉）text-directions功能按钮组
- 调整 main_ui_workspace_sidebar_options_menus_typography__font-modifiers 位置，放到功能列表最后，默认折叠起来不可见
- 交换 main_ui_workspace_sidebar_options_menus_text__grow-options 与 main_ui_workspace_sidebar_options_menus_text__vertical-align-options 位置
- 移除 main_ui_workspace_sidebar_options_menus_typography__font-selector 覆盖式的字体选择面板
- 调整 main_ui_workspace_sidebar_options_menus_typography__font-option 字体选择
  - 交互方式改为参考字重的下拉菜单，菜单项为原 font-selector 的选项，不需要搜索功能
