# 原型表格（Prototype Table）

一个用于 **Penpot** 的插件：在画布上渲染出**锁定、可拖拽**的原型表格，并通过插件编辑器（表单 / JSON）修改后重新渲染。适合在设计稿中快速搭建表格原型（列表、看板、数据网格等）。

- 中文名：**原型表格** ｜ 英文名：**Prototype Table**
- 技术栈：Penpot Plugin API · React + TypeScript · Vite

---

## 功能特性

- 🧩 **表单化编辑**：无需手写 JSON，通过表单配置行列、列类型、单元格内容
- 📦 **内置模板 & 历史**：从场景模板快速开始，本地历史可一键复用
- 🔒 **锁定可拖拽**：渲染出的表格内容锁定，整表可整体拖拽，不散开
- 🖼 **图片与内置图**：图片列支持 URL 或内置默认图（不依赖外网）
- 🎨 **三种表格样式**：全边框 / 仅横向边框 / 斑马纹，支持隐藏表头
- 👀 **HTML 预览**：保存前先用 HTML 预览表格效果，确认后再渲染到画布
- ✏️ **支持 11 种列类型**：`text`、`dropdown`、`radio`、`checkbox`、`multi-select`、`switch`、`action`、`image`、`icon`、`label`、`input`

---

## 快速上手（使用者）

> 如果你只想在 Penpot 里用这个插件，看这一节就够了。

### 1. 加载插件

1. 打开 Penpot，进入任一文件。
2. 找到 **插件 → 开发插件**（开发模式），粘贴插件地址并加载：
   - 本地开发环境：`http://localhost:5173/manifest.json`（需先运行 `npm run dev`）
   - 已部署的线上环境：对应托管地址的 `manifest.json`
3. 加载成功后，在插件列表中即可看到 **Prototype Table**。

### 2. 新建一张表格

1. 触发插件，面板打开（标题显示「原型表格」）。
2. 点击顶部 **新建**，面板进入编辑模式。
3. 也可以点击 **模板** 从内置场景模板开始，或点击 **历史** 复用本机历史。

### 3. 编辑与渲染

1. 在面板中配置：表格名、显示表头、表格样式、行列数、各列类型与单元格内容。
2. 点击底部 **预览**，可在面板内先查看 HTML 表格效果；点「返回」继续编辑。
3. 满意后点击 **保存并重渲**，表格即渲染到画布（内容锁定，整表可拖拽）。

### 4. 再次编辑

在画布上**点击表格** → 面板自动切到该表格，修改后点「保存并重渲」即可原位替换。

---

### ⚠️ 重要：请勿解锁表格

- 生成的原型表格**内容默认锁定**，这是刻意设计——保证表格作为整体可拖拽、不错位。
- **请勿在 Penpot 画布上手动解锁表格**。如需修改内容，**一律通过本插件编辑器**修改后「保存并重渲」。
- 手动解锁并直接编辑表格会导致表格结构或数据异常，且无法被插件正常识别与重渲。

---

## 开发者指南

> 如果你想构建、测试或扩展这个插件，看这一节。

### 环境要求

- Node.js ≥ 18
- 包管理器：pnpm（推荐，仓库含 `pnpm-workspace.yaml`）或 npm

### 安装

```bash
pnpm install
# 或 npm install
```

### 常用命令

| 命令 | 说明 |
|---|---|
| `npm run dev` | 首次构建 + watch 重建 + 静态服务；在 Penpot「开发插件」加载 `http://localhost:5173/manifest.json`（端口可用 `PORT` 覆盖） |
| `npm run build` | 生产构建，产物输出到 `dist/`（`manifest.json` / `plugin.js` / `ui.html` / `assets/`） |
| `npm test` | 运行单元测试（Vitest） |
| `npm run typecheck` | TypeScript 类型检查 |
| `npm run lint` | ESLint 检查（零告警） |

> 注意：`npm run dev` 只对 `src/` 的变更做 watch 重建；**修改 `manifest.json` 后需重启 `npm run dev`** 才会重新复制到 `dist/`。

### 项目结构

```
├── manifest.json          # Penpot 插件清单（名称 / 入口 / 权限）
├── ui.html                # 插件 UI 入口页面
├── vite.config.ts         # UI 构建配置（→ dist/ui.html + assets）
├── vite.plugin.config.ts  # 宿主脚本构建配置（→ dist/plugin.js，IIFE）
├── scripts/
│   ├── clean.mjs          # 清理 dist
│   ├── dev-server.mjs     # 开发服务器（首次构建 + watch + 静态服务）
│   └── finalize.mjs       # 收尾：复制 manifest.json 并校验产物完整性
├── src/
│   ├── plugin.ts          # Penpot 宿主脚本：渲染 / 扫描 / 锁定 / 替换重渲
│   ├── shared/            # 与 UI 共享的纯逻辑（可单测）
│   │   ├── types.ts       # TableDefinition / ColumnType 等数据契约
│   │   ├── validator.ts   # JSON 校验（中文错误信息）
│   │   ├── layout.ts      # 行列尺寸 / 布局计算
│   │   ├── constants.ts   # 颜色 / 尺寸 / 表格样式常量
│   │   ├── templates.ts   # 内置模板 + 缩略图
│   │   ├── htmlPreview.ts # HTML 表格预览生成
│   │   ├── history.ts     # 本地历史（localStorage）
│   │   └── messaging.ts   # UI ↔ 宿主 postMessage 消息协议
│   └── ui/                # React 面板
│       ├── App.tsx        # 面板框架（标题 / TAB / 模板 / 历史 / 解锁提醒）
│       ├── TableForm.tsx  # 表单化表格编辑器
│       ├── ImagePicker.tsx# 图片选择弹层
│       └── App.css        # 面板样式
└── openspec/              # OpenSpec 规格文档（主规格 / 变更 / 归档）
```

### 架构速览

- **UI ↔ 宿主**：面板（React）通过 `messaging.ts` 的 postMessage 协议与宿主脚本（`plugin.ts`）通信；`render-new` / `render-update` 渲染或替换画布表格，`request-state` 拉取画布表格列表。
- **数据契约**：宿主与 UI 共用 `src/shared/` 的纯逻辑（校验、布局、模板、预览、历史），保证任何合法 JSON 都能被稳定解析与渲染。
- **渲染**：`plugin.ts` 依据 `TableDefinition` 构建表格组，内容递归锁定（`blocked = true`），组本身不锁定以保证整体可拖拽。

### 扩展入口

- 新增列类型：在 `src/shared/types.ts` 的 `ColumnType` 增加枚举 → `validator.ts` 补充校验 → `plugin.ts` / `htmlPreview.ts` 补充渲染形态 → `TableForm.tsx` 补充编辑控件。
- 新增模板：在 `src/shared/templates.ts` 的 `TABLE_TEMPLATES` 增加一项。
- 新增 UI 功能：在 `src/ui/` 组件中实现，并通过 `messaging.ts` 定义新的消息类型与宿主处理。

---

## JSON 数据契约

最简可用示例：

```json
{
  "name": "示例项目列表",
  "rows": 2,
  "showHeader": true,
  "tableStyle": "full",
  "columns": [
    { "id": "name", "title": "项目名称", "type": "text", "width": 180 },
    { "id": "status", "title": "状态", "type": "dropdown", "options": ["进行中", "已完成"], "width": 110 },
    { "id": "active", "title": "启用", "type": "switch" },
    { "id": "actions", "title": "操作", "type": "action", "actions": [{ "label": "编辑" }, { "label": "删除", "disabled": true }], "width": 130 }
  ],
  "cells": {
    "0:name": "原型表格 插件",
    "0:status": "进行中",
    "0:active": true,
    "1:name": "官网改版",
    "1:status": "已完成",
    "1:active": false
  }
}
```

**顶层字段：**

| 字段 | 必填 | 说明 |
|---|---|---|
| `name` | 否 | 表格名（缺省时使用默认名） |
| `rows` | 是 | 行数（≥1 的整数） |
| `columns` | 是 | 列定义数组（非空） |
| `cells` | 是 | 单元格内容，键为 `${行索引}:${列标识}` |
| `showHeader` | 否 | 是否显示表头，默认 `true` |
| `tableStyle` | 否 | `full` / `horizontal` / `striped`，默认 `full` |
| `pagination` / `pageSize` | 否 | 底部页码条（可选） |

**列类型与特有字段：**

| 类型 | 说明 | 可选字段 |
|---|---|---|
| `text` | 文本 | `width` |
| `dropdown` / `radio` / `multi-select` | 选择类 | `options` |
| `checkbox` / `switch` | 布尔 | `width` |
| `action` | 操作按钮 | `actions`（字符串 或 `{ label, disabled }`） |
| `image` | 图片 | URL 或内置图标识 |
| `icon` | 图标 | 图标标识 |
| `label` | 标签 | `width` |
| `input` | 文本输入框 | `placeholder` |

> 单元格值约束：`text`/`dropdown`/`radio`/`multi-select`/`label`/`input`/`icon` 为字符串；`switch`/`checkbox` 为布尔；`image` 为 URL 或内置图标识；`action` 无需内容。缺失的单元格渲染为空。

---

## 常见问题（FAQ）

**Q：渲染出来的表格可以编辑吗？**
A：内容默认锁定，不能直接编辑。要修改请用插件面板，选中表格后改完点「保存并重渲」。

**Q：表格能拖吗？**
A：可以。整张表格作为整体拖拽移动，内部不会散开。

**Q：图片不显示了？**
A：确认图片列使用有效 URL，或改用内置默认图标识（不依赖外网）。外网图片加载失败时会降级为内置占位图。

**Q：保存后表格没有更新？**
A：请确认画布上已选中该表格后再点「保存并重渲」；替换重渲会删除旧表格并在原位置生成新表格。

**Q：改动 manifest.json 后 dev 环境没生效？**
A：`npm run dev` 不会 watch manifest.json，重启 dev 服务器即可。

---

## 文档与规范

- OpenSpec 规格文档位于 [`openspec/`](openspec/)，主规格在 [`openspec/specs/`](openspec/specs/)（表格编辑流程 / JSON 数据契约 / 渲染契约），历史变更归档在 [`openspec/changes/archive/`](openspec/changes/archive/)。
