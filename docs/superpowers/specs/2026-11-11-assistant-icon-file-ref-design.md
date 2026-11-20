# AI 助理图标改为文件直引（替代 sprite）— 设计

日期：2026-11-11
状态：已确认方案（方案 1：`<img>` 直引文件）

## 背景与目标

AI 助理 tab 图标当前走 sprite 链路：`resources/images/icons/assistant.svg`（源文件）
→ 构建期 `compileSvgSprites` 编入 `resources/public/images/sprites/symbol/icons.svg`
→ `icon*` 组件以 `<use href="#icon-assistant">` 渲染。替换图标需重编 sprite
（且 watch 对 svg sprite 有盲区，需 `build:app:assets` 兜底）。

`copyAssets` 已将 `resources/images/` 整目录原样同步到产物 `resources/public/images/`，
产物下 `images/icons/assistant.svg` 已存在。

**目标**：图标改为按 URL 引用该产物文件（`<img>`），替换图标时直接用新文件覆盖
产物下的 `assistant.svg` 即可生效，无需任何重新编译。

## 方案

给 DS 组件 `tab-switcher` 增加可选 `:icon-url` 属性，渲染 `<img>`；助理 tab 传
`:icon-url "images/icons/assistant.svg"`。文件内容完全决定图标外观（含颜色），
适合投放彩色品牌图标。

### 改动点（共 2 个文件）

1. `frontend/src/app/main/ui/ds/layout/tab_switcher.cljs`
   - `schema:tab-attrs` 增加 `[:icon-url {:optional true} :string]`。
     （`schema:tab` 的校验函数无需改：助理 tab 带 `:label`，走既有 `(contains? tab :label)` 分支。）
   - `tab-nav*`：从 element 额外解构 `icon-url` 并传给 `tab*`。
   - `tab*`：
     - 解构 `icon-url`；在现有 `(when (some? icon) ...)` 旁增加：
       ```clojure
       (when (some? icon-url)
         [:img {:src icon-url
                :width 16
                :height 16
                :alt (when (not label) aria-label)
                :aria-hidden (when label true)}])
       ```
       尺寸 16px 与 `icon*` 默认档（`icon-size-m`）一致；aria 语义镜像 `icon*` 路径
       （有 label 时对辅助技术隐藏，无 label 时用 aria-label 作 alt）。
     - label span 的 `:tab-text-and-icon` 条件由 `icon` 改为 `(or icon icon-url)`，
       保持图标+文字组合时的 padding。
   - 无 SCSS 改动（尺寸走 img 内联属性）。

2. `frontend/src/app/main/ui/workspace/sidebar/options.cljs`
   - 助理 tab：`:icon i/assistant` → `:icon-url "images/icons/assistant.svg"`。
   - `i/assistant` 是该文件中 `i/` 别名的唯一使用点，移除 ns 中的
     `[app.main.ui.ds.foundations.assets.icon :as i]`。

### 不改的部分

- `icon.cljs` 的 `(def ^:icon-id assistant "assistant")` 与源 svg 保留：
  源文件 `resources/images/icons/assistant.svg` 是产物文件的来源（`copyAssets`
  同步），必须保留；def 与 sprite 条目无害，保留可最小化 diff。
- sprite 及其他图标完全不动。

## URL 形式与部署兼容

使用相对路径 `"images/icons/assistant.svg"`，与 `auth.cljs`
（`"images/registration-illustration.png"`）等现有先例一致。Penpot 为 hash 路由，
文档 base 稳定，相对路径在裸部署与 `/penpot/` 前缀部署下均正确。

## 运维注意

- **覆盖即生效**：直接覆盖产物（devenv 中即 frontend 容器内
  `resources/public/images/icons/assistant.svg`；正式部署为 dist 下同路径）。
- **全量重建会还原**：`build` 脚本会 `rm -rf resources/public` 后从源目录重新同步；
  需长期更换时同步替换源文件 `resources/images/icons/assistant.svg`。
- **缓存**：同 URL 覆盖后浏览器/nginx 缓存可能滞留旧图标，验证时需硬刷新。

## 验证

- `frontend` 下 `pnpm run lint:clj`、`pnpm run check-fmt:clj` 通过（确认无
  unused-namespace 残留）。
- 无相关单测（`frontend/test` 无 tab-switcher/options 覆盖），无测试注册需求。
- 浏览器人工验证（用户执行）：嵌入式工作区打开助理 tab，确认图标正常渲染 16px；
  覆盖产物文件后硬刷新确认新图标生效。
