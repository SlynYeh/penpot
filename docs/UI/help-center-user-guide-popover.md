# 左侧边栏header区帮助中心按钮用户引导功能

增加帮助中心按钮气泡弹窗

## 交互说明

1. 什么时候显示？
用户第一次打开workspace页面显示气泡弹窗，点击 关闭/知道了 以后都不再显示。

2. 怎么关闭？
点击 关闭/知道了 按钮。未点击这两个按钮不关闭弹窗。

## UI说明

1. 草图

    - - - - - - - - - - - - - - -
  |                               |
  |    帮助中心                x   |
  |                               |
  |    查看常用快捷键，获取新手教     |
  |    程，快速掌握penpot使用方法    |
  |                               |
  |                    - - - -    |
  |                   | 知道了 |   |
  |                    - - - -    |
   - - - - - - - - - - - - - - - -
2. 描述

  - 标题
    - 字体：14px、700、white
  - 内容
    - 字体：14px、400、white
  - 关闭按钮
    - 大小：点击区域 20 * 20、图标 12 * 12
    - 颜色：white
  - 知道了按钮
    - 高度：24px
    - 圆角：3px
    - 背景：white
    - padding：2px 8px
  - 弹窗
    - 宽度：240px
    - 高度：自适应
    - 圆角：6px
    - box-shadow: 0 6px 16px -8px rgba(16,28,88,0.07), 0 9px 28px 0 rgba(2,22,61,0.05),0 12px 48px 16px rgba(2,22,61,0.03)

## 已确认决策（2026-09-09 草图与方案确认）

- 背景色：原文档未给底色，定为 **#296afd**（蓝底白字）；「知道了」白底、文字色 #296afd（需求空白，验收时可调）。
- 锚定：帮助按钮正下方、右对齐 sidebar 右缘（同现有 `.help-menu` 锚法 `absolute / top:48px / right:12px`），顶边 8px 旋转方块小箭头指向按钮（水平偏移浏览器微调，随语言 label 宽度略偏）。
- 显示受众与持久化：**所有用户一次**（含存量），存前端 `storage/global`（localStorage 前缀 `penpot-global`，key `app.main.ui.workspace.help-center-menu/help-guide-dismissed`）；登出不清空、跨账号共享本浏览器；换浏览器/设备会再显示一次（已接受）。
- 关闭路径（三条都视为已读：立即关闭并持久化）：点「知道了」、点 ✕、点帮助按钮打开下拉菜单；其余任何操作不关闭。
- 文案走 i18n：`workspace.header.help.guide.{title,body,got-it}`，en.po + zh_CN.po 成对新增（fork 惯例）；英文文案 "Help center" / "Find common shortcuts and beginner tutorials to quickly get up to speed with Penpot." / "Got it"。
- 实现落点：`frontend/src/app/main/ui/workspace/help_center_menu.cljs` / `help_center_menu.scss`（气泡收敛在帮助中心组件内）+ `frontend/translations/{en,zh_CN}.po`；不动后端。
- 已知接受项：多标签页不同步（无 storage 事件监听）；将来重命名组件 ns 会让老浏览器再弹一次。
