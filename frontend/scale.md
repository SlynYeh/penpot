# 滚轮缩放粒度问题分析与修复

## 问题

用户反馈滚轮（Ctrl/Cmd + 滚轮）缩放粒度异常：鼠标滚轮每格缩放过大，触控板又过小（"要么缩放太大，要么缩放太小"）。

## 滚轮缩放逻辑是怎么控制的

### 事件绑定

`frontend/src/app/main/ui/workspace/viewport/hooks.cljs:78` 在 `window` 上监听 `WHEEL`（`passive: false`），路由到 `actions/on-mouse-wheel`。

### 核心计算

`frontend/src/app/main/ui/workspace/viewport/actions.cljs` 的 `on-mouse-wheel`：

```clojure
(def scale-per-pixel -0.0057)   ; 原常量

;; on-mouse-wheel 内部：
delta-y    (.-pixelY norm-event)      ; 归一化后的像素增量
delta-x    (.-pixelX norm-event)
delta-zoom (+ delta-y delta-x)

scale      (+ 1 (mth/abs (* scale-per-pixel delta-zoom)))
scale      (if (pos? delta-zoom) (/ 1 scale) scale)
```

`schedule-zoom!` 把多个 wheel 事件的 scale 累乘，下一帧 `rAF` 统一经 `dw/set-zoom` 应用；`set-zoom` 最终 `new-zoom = old-zoom * scale`。

### 根因

缩放用了 `normalize-wheel`（`frontend/src/app/util/dom/normalize_wheel.js`）返回的 **像素增量** `pixelY`/`pixelX`，该值在不同设备相差 50~100 倍：

| 设备 | 每次滚动的 `pixelY` | `scale = 1 + 0.0057 × Δ` |
|---|---|---|
| 鼠标滚轮（Chrome/Firefox） | ~100~120 px | 1.57 → 每格 **+57%** |
| 触控板（macOS） | ~1~10 px | 1.006~1.057 → 每事件 +0.6%~5.7% |

同一个 `0.0057` 常量导致鼠标滚轮猛跳、触控板过细。

`normalize-wheel` 其实还返回 `spinY`/`spinX`，注释明确它把"鼠标滚轮的慢速一格归一到 1"（鼠标每格 = 1，触控板 = 很小分数），是缩放场景的推荐字段。penpot 原实现缩放却没用它。

## 修复方案（已落地）

缩放路径改用 `spinY`/`spinX`，平移/滚动路径继续用 `pixelY`/`pixelX`。

文件：`frontend/src/app/main/ui/workspace/viewport/actions.cljs`

### 1. 替换常量（第 40 行）

```clojure
;; 旧
(def scale-per-pixel -0.0057)
;; 新
(def zoom-per-spin 0.1)   ;; 鼠标每格约 +10%，可按手感微调
```

### 2. 修改 `on-mouse-wheel`（第 451~458 行）

```clojure
delta-y    (.-pixelY norm-event)   ; 保留，供 schedule-scroll! 平移使用
delta-x    (.-pixelX norm-event)   ; 保留
spin-y     (.-spinY norm-event)    ; 新增
spin-x     (.-spinX norm-event)    ; 新增
delta-zoom (+ spin-y spin-x)       ; 缩放改用 spin

scale      (+ 1 (mth/abs (* zoom-per-spin delta-zoom)))
scale      (if (pos? delta-zoom) (/ 1 scale) scale)
```

`schedule-scroll!`（平移）仍使用 `delta-x`/`delta-y`，不受影响；`schedule-zoom!`（缩放）使用 spin 驱动的 `scale`，自动跨设备一致。

## 验证

1. 启动前端（WASM 视口与 SVG 视口共用该 `on-mouse-wheel`，都会生效）。
2. 鼠标滚轮 + Ctrl/Cmd：每格约放大/缩小 10%，不再猛跳 57%。
3. 触控板双指缩放保持平滑细腻。
4. 不按 Ctrl/Cmd 的滚轮：仍是画布平移，方向与手感不变（`pixelY`/`pixelX` 路径未被破坏）。
5. 边界：缩放范围仍受 `set-zoom` 的 `min 0.01 / max 200` 限制（zoom.cljs:92）。

## 备注

- 鼠标滚轮 `spinY` 在不同浏览器仍有轻微差异（Chrome ≈ 1、Firefox `detail` ≈ 3），属于 `normalize-wheel` 已知局限；如需进一步统一，可后续对 `delta-zoom` 做按格钳制。
- 若希望每格更大（如 15%~20%），把 `zoom-per-spin` 调到 `0.15`/`0.2`；想更细则调小。
