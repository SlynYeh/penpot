# 智能助理接入需求 v2 UI 细节调整

## 问题

- main_ui_workspace_sidebar_assistant__action-btn 按钮内的图标和文字通过 flex 实现垂直居中对齐
- main_ui_workspace_sidebar_assistant__action-btn svg图标尺寸 16*16，颜色 #495e74，与文字间距 4
- main_ui_workspace_sidebar_assistant__action-btn 按钮文字 font-size=12, font-weight=400, color=#505463
- main_ui_workspace_sidebar_assistant__action-btn 两个按钮间距 16，垂直居中对齐
- main_ui_workspace_sidebar_assistant__history-head-label font-size=13，font-weight=600，color=#000000
- main_ui_ds_foundations_assets_icon__icon 返回图标尺寸 19*19，颜色 #8694a3，与文字间距 4
- main_ui_workspace_sidebar_assistant__history-head 与 main_ui_workspace_sidebar_assistant__history-list 间距 8
- main_ui_workspace_sidebar_assistant__history-item 的 padding=8, border-radius=4, hover-bg=rgba(16, 28, 88, 0.07)
- main_ui_workspace_sidebar_assistant__history-item-title 的 font-size=14, font-weight=400, color=#000000, 文字单行显示不换行 溢出隐藏

## 布局调整

- 历史记录列表显示方式重构为分组式，超过30天的记录不显示，分组标题与列表项间距 8，分组之间的间距 10，列表项只显示标题，移除原来的日期。
  - 当天：显示今天的会话记录，分组标题=当天
  - 近7天：显示除今天外的7日内记录，分组标题=近7天
  - 近30天：显示除今天和近7天外的30日内记录，分组标题=近30天

