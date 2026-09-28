# Resize Preview Conditional Live-Solve Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 wasm=false (SVG) 模式下，resize/rotate 拖拽预览改为条件求解——受影响树（resolve-tree 闭包）含任何 flex/grid 时冻结子元素（现状），纯 plain 子树时实时跑全量 solve 让子元素按约束跟手。

**Architecture:** 新增纯函数 `gm/skip-live-solve?`（common，JVM 可测）做跳过判定；`start-resize`/`start-rotate` 的 wasm=false 分支在手势开始时调用一次并缓存结果，采样帧据此选择 `set-preview-modifiers`（冻结）或 `set-modifiers`/`set-rotation-modifiers`（全量 solve）。提交路径（pointer-up）完全不动。

**Tech Stack:** Clojure/CLJC (common + frontend Potok events)。无新依赖。

---

## 背景（为什么是这个规则）

完整数据与机制分析：`.serena/memories/frontend/drag-resize-vertex-perf.md`；基准脚本 `tools/analysis/solve_resize_bench{,2,3}.clj`（JVM，可复跑）。

实测要点（JVM，浏览器 ×2-4）：
- 纯 plain 子树 solve 线性便宜：100/500/2000 子元素 = 0.56/1.34/3.92 ms/帧
- **判定边界必须是 `resolve-tree` 闭包**而非拖动根子树：`get-reflow-root` 会向上走到 layout 祖先（拖 grid 直系子元素 → 重解整个 grid，~4ms/帧；普通 frame 祖先截断回溯）
- 嵌套 auto 尺寸 layout 是灾难放大器（182 节点 63ms/帧，17×）——必须保持跳过
- 判定成本 0.003-0.44ms，每手势一次（子树成员与 layout 标记在手势中不会变）

**红线（来自 mem:grid-layout-svg-perf）**：不得改动 pointer-up 提交路径；提交结果在规则开/关两种情况下必须逐位一致（本计划不动 `emit-final`，天然满足）。

## 前置状态

- 分支：`perf/grid-layout-wasm-false`（`set-preview-modifiers` 已存在于此分支）。工作区有**无关的未提交改动**（`THANKYOU.md`、`docker/devenv/files/nginx.conf`、`plugins/` 下未跟踪文件）——提交前 `git status`，**只显式 stage 本计划涉及的文件**。
- 实施前先读 `.serena/memories/frontend/drag-resize-vertex-perf.md` 与 `mem:workflow/creating-commits`（提交格式）。

## 文件结构

| 文件 | 职责 | 动作 |
|---|---|---|
| `common/src/app/common/geom/modifiers.cljc` | 新增 `skip-live-solve?` 纯函数（判定边界=resolve-tree 闭包） | Modify（~:318 `set-objects-modifiers` 前） |
| `common/test/common_tests/geom_modifiers_test.cljc` | 6 个判定用例（手写 objects map，同 `geom_shapes_tree_seq_test` 风格） | Modify（追加 deftest + require uuid） |
| `frontend/src/app/main/constants.cljs` | 上限常量 `preview-solve-max-affected-nodes` | Modify（:343 `move-sample-time` 后） |
| `frontend/src/app/main/data/workspace/transforms.cljs` | `start-resize` / `start-rotate` 的 wasm=false 预览分支接线 | Modify（:326-355、:537-556） |

---

### Task 1: `gm/skip-live-solve?` + JVM 测试（TDD）

**Files:**
- Modify: `common/test/common_tests/geom_modifiers_test.cljc`（追加；需在 `:require` 加 `[app.common.uuid :as uuid]`）
- Modify: `common/src/app/common/geom/modifiers.cljc`（`filter-layouts-ids`（:318）之后、`set-objects-modifiers` 之前插入）

- [ ] **Step 1: 写失败测试**

在 `geom_modifiers_test.cljc` 末尾追加（`uuid` require 加到 ns 的 `:require` 向量）：

```clojure
;; ---- skip-live-solve? --------------------------------------------------

(defn- sm-shape
  ([id type parent-id] (sm-shape id type parent-id [] nil))
  ([id type parent-id shapes layout]
   (cond-> {:id id :type type :parent-id parent-id :shapes (vec shapes)}
     (some? layout) (assoc :layout layout))))

(t/deftest skip-live-solve-test
  (t/testing "pure plain subtree, no layouts anywhere -> solve allowed"
    (let [root (uuid/next) r1 (uuid/next) r2 (uuid/next)
          objects {root (sm-shape root :frame uuid/zero [r1 r2])
                   r1   (sm-shape r1 :rect root)
                   r2   (sm-shape r2 :rect root)}]
      (t/is (false? (gm/skip-live-solve? [root] objects 100)))))

  (t/testing "subtree containing a grid descendant -> skip"
    (let [root (uuid/next) grid (uuid/next) c1 (uuid/next)
          objects {root (sm-shape root :frame uuid/zero [grid])
                   grid (sm-shape grid :frame root [c1] :grid)
                   c1   (sm-shape c1 :rect grid)}]
      (t/is (true? (gm/skip-live-solve? [root] objects 100)))))

  (t/testing "dragged shape is a DIRECT child of a grid (ancestor closure) -> skip"
    (let [grid (uuid/next) cell (uuid/next)
          objects {grid (sm-shape grid :frame uuid/zero [cell] :grid)
                   cell (sm-shape cell :rect grid)}]
      (t/is (true? (gm/skip-live-solve? [cell] objects 100)))))

  (t/testing "plain frame between shape and grid cuts the reflow walk -> solve allowed"
    (let [grid (uuid/next) plain (uuid/next) deep (uuid/next)
          objects {grid  (sm-shape grid :frame uuid/zero [plain] :grid)
                   plain (sm-shape plain :frame grid [deep])
                   deep  (sm-shape deep :rect plain)}]
      (t/is (false? (gm/skip-live-solve? [deep] objects 100)))))

  (t/testing "tree larger than max-nodes -> skip even without layouts"
    (let [root (uuid/next)
          kids (repeatedly 5 uuid/next)
          objects (into {root (sm-shape root :frame uuid/zero kids)}
                        (map (fn [id] [id (sm-shape id :rect root)]) kids))]
      (t/is (true?  (gm/skip-live-solve? [root] objects 5)))
      (t/is (false? (gm/skip-live-solve? [root] objects 100)))))

  (t/testing "multi-root: one root whose closure hits a layout flips the gesture"
    (let [proot (uuid/next) r1 (uuid/next)
          gparent (uuid/next) gchild (uuid/next)
          objects {proot  (sm-shape proot :frame uuid/zero [r1])
                   r1     (sm-shape r1 :rect proot)
                   gparent (sm-shape gparent :frame uuid/zero [gchild] :grid)
                   gchild (sm-shape gchild :rect gparent)}]
      (t/is (true? (gm/skip-live-solve? [proot gchild] objects 100))))))
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd common && clojure -M:dev:test --focus common-tests.geom-modifiers-test/skip-live-solve-test
```
预期：FAIL，`Unable to resolve symbol: skip-live-solve?`（编译/求值错误即算 RED）。

- [ ] **Step 3: 实现**

在 `common/src/app/common/geom/modifiers.cljc` 的 `filter-layouts-ids` 之后插入：

```clojure
(defn skip-live-solve?
  "Decides whether a live (per-frame) layout solve should be SKIPPED for a
  transform preview drag over `ids`.

  Returns true when the affected tree -- the resolve-tree closure of `ids`,
  which walks up to layout ancestors -- contains any flex/grid frame, or when
  the tree exceeds `max-nodes` (giant plain subtrees stay affordable only
  below that size).

  Measured rationale (mem:frontend/drag-resize-vertex-perf,
  tools/analysis/solve_resize_bench*.clj): per-frame cost tracks the NUMBER
  of layout frames in the affected tree (~0.1ms each on JVM), with
  auto-sized nested layouts amplifying up to 17x; pure plain subtrees are
  linear and cheap. The boundary MUST be the resolve-tree closure, not the
  dragged roots' own subtrees: resizing a direct child of a grid re-solves
  the whole grid (get-reflow-root walks up through layouts/groups; a
  plain-frame ancestor cuts the walk). Compute ONCE per gesture."
  [ids objects max-nodes]
  (let [tree (vec (cgst/resolve-tree (set ids) objects))]
    (boolean
     (or (some ctl/any-layout? tree)
         (> (count tree) max-nodes)))))
```

命名空间已有所需 alias（`cgst`、`ctl`），无需改 require。

- [ ] **Step 4: 跑测试确认通过**

同 Step 1 命令。预期：6 testing 块全 PASS。

- [ ] **Step 5: 回归相关套件**

```bash
cd common && clojure -M:dev:test --focus common-tests.geom-modifiers-test
cd common && clojure -M:dev:test --focus common-tests.geom-shapes-tree-seq-test
```
预期：全 PASS（新 var 在已有 ns，无需注册 runner）。

- [ ] **Step 6: 提交**（先 `git status`，只 stage 这两个文件；格式见 `mem:workflow/creating-commits`，模型名用当次会话的实际模型）

```bash
git add common/src/app/common/geom/modifiers.cljc common/test/common_tests/geom_modifiers_test.cljc
git commit -s -m ":zap: Add skip-live-solve? boundary for live preview solves

Pure-plain affected trees get a per-frame solve (children follow via
constraints); anything whose resolve-tree closure contains a flex/grid,
or exceeds the size cap, stays frozen until pointer-up.

AI-assisted-by: <model-name>"
```

---

### Task 2: 常量 + `start-resize` 接线

**Files:**
- Modify: `frontend/src/app/main/constants.cljs:343`（`move-sample-time` 之后）
- Modify: `frontend/src/app/main/data/workspace/transforms.cljs:333-355`（wasm=false 的 `emit-preview`）

本任务是 rx 流接线，无单测；验证 = 编译 + Task 4 的浏览器实测矩阵。

- [ ] **Step 1: 加常量**（`frontend/src/app/main/constants.cljs`，`move-sample-time` 后）

```clojure
(def ^:const preview-solve-max-affected-nodes
  "Live (per-frame) layout solves during resize/rotate previews are allowed
  only when the affected tree is pure-plain AND below this node count. Above
  it the preview freezes even without layouts: a 2000-child plain frame
  costs ~8-16ms/frame in-browser (mem:frontend/drag-resize-vertex-perf)."
  800)
```

- [ ] **Step 2: 接线 `start-resize`**

`transforms.cljs` wasm=false 分支，把现有代码（:333-355 附近）：

```clojure
(let [emit-preview
      (fn [modifiers]
        (let [modif-tree (dwm/create-modif-tree shape-ids modifiers)]
          (rx/of (dwm/set-preview-modifiers modif-tree))))
```

改为（`skip-solve?` 在 let 顶部、`emit-preview` 之前绑定，整手势只算一次）：

```clojure
(let [skip-solve? (gm/skip-live-solve? shape-ids objects
                                      mconst/preview-solve-max-affected-nodes)

      emit-preview
      (fn [modifiers]
        (let [modif-tree (dwm/create-modif-tree shape-ids modifiers)]
          (rx/of (if skip-solve?
                   (dwm/set-preview-modifiers modif-tree)
                   ;; Pure-plain affected tree: cheap linear solve, children
                   ;; follow live via constraints (upstream behavior pre-fork).
                   (dwm/set-modifiers modif-tree (contains? layout :scale-text))))))
```

alias 已存在（`gm` :15、`mconst` :30），`objects`/`shape-ids`/`layout` 均在该 watch 作用域内。`emit-final` **不动**。

- [ ] **Step 3: 语法/格式校验 + watch 编译验证**

```bash
cd frontend && pnpm run check-fmt:clj
```
预期：PASS（cljfmt 解析失败 = 括号损坏 → 先跑 `tools/paren-repair.bb` 再继续）。

CLJS 编译通过 Task 4 的 watch 流程验证：devenv 前端 watch 在跑时，宿主机改码后 `docker exec` touch 对应文件触发重编译（见 `mem:devenv-watch-inotify-touch`），观察 watch 输出无 `skip-live-solve?` / `preview-solve-max-affected-nodes` 未解析或编译错误。

- [ ] **Step 4: 提交**

```bash
git add frontend/src/app/main/constants.cljs frontend/src/app/main/data/workspace/transforms.cljs
git commit -s -m ":zap: Let plain-subtree resize previews solve live

Affected tree without layouts (and under the size cap) now runs the full
solve per sampled frame so constrained children follow the drag; layout
subtrees keep the frozen preview. Commit path unchanged.

AI-assisted-by: <model-name>"
```

---

### Task 3（可选，建议做）: `start-rotate` 同规则

**Files:**
- Modify: `frontend/src/app/main/data/workspace/transforms.cljs:537-556`（start-rotate wasm=false 分支）

- [ ] **Step 1: 接线**

把：

```clojure
(let [emit-preview
      (fn [angle]
        (dwm/set-preview-modifiers (rotation-modifiers angle shapes group-center)))
```

改为：

```clojure
(let [objects    (dsh/lookup-page-objects state)
      skip-solve? (gm/skip-live-solve? (mapv :id shapes) objects
                                       mconst/preview-solve-max-affected-nodes)

      emit-preview
      (fn [angle]
        (if skip-solve?
          (dwm/set-preview-modifiers (rotation-modifiers angle shapes group-center))
          ;; Pure-plain affected tree: full rotation solve per frame.
          (dwm/set-rotation-modifiers angle shapes group-center)))
```

注意：该 watch 原本没有 `objects` 绑定，需新增（`dsh` alias 已存在）。`emit-final` **不动**。

- [ ] **Step 2: 编译验证 + 提交**

```bash
git add frontend/src/app/main/data/workspace/transforms.cljs
git commit -s -m ":zap: Let plain-subtree rotate previews solve live

Same skip-live-solve? condition as resize; layout subtrees stay frozen.

AI-assisted-by: <model-name>"
```

---

### Task 4: 浏览器实测矩阵 + 全量校验

**Files:** 无代码改动（验证任务）。

- [ ] **Step 1: 浏览器实测**（现有 wasm=false devenv 实例；宿主机改码后 `docker exec touch` 踢一下容器内 watch，见 `mem:devenv-watch-inotify-touch`；强制刷新）

| # | 场景 | 预期 |
|---|---|---|
| 1 | 普通 frame + ~50 个混合约束子元素（右钉/居中/缩放），拖右边界 | 子元素实时跟随；60fps 流畅 |
| 2 | 同场景开 snap-pixel | 仍正确跟随、无跳变 |
| 3 | 普通 frame 内含 grid 子容器，拖 frame 边界 | 子元素冻结（现状不变），无卡顿 |
| 4 | 直接拖 grid 边界 | 冻结（现状不变） |
| 5 | **拖 grid 单元格内的元素**（回归守卫） | 冻结，无卡顿（祖先闭包生效） |
| 6 | 每个 scenario 松手 | 提交位置正确（全量 solve 一次，结果与改动前逐位一致） |
| 7 | 旋转普通 frame（Task 3 做了的话） | 子元素跟随；grid 冻结 |

- [ ] **Step 2: 全量测试与 lint**（仓库根目录）

```bash
cd common && clojure -M:dev:test
cd ../ && ./scripts/lint
./scripts/check-fmt
```
预期：common 全绿；lint/fmt 无新告警（`skip-live-solve?` docstring 注意 cljfmt 换行对齐；有问题先 `./scripts/fmt`）。

- [ ] **Step 3: 若有修复则追加提交**（无改动则跳过）

---

## 回滚

- 单 commit 级：revert Task 2/3 的接线 commit 即恢复全冻结现状；Task 1 的纯函数无害可保留（bench 已验证）。
- 极端回滚：`git revert` 接线 commit，无数据/迁移风险。

## 明确不做（YAGNI / 已否决）

- 不改 `emit-final`/`apply-modifiers` 提交路径
- 不给 grid track/cell 拖拽改判定（它们本就直连 solve + 已有 rAF 节流/A1 缓存）
- 不做「constraints-only solve」新模式（仅在用户强烈要求 layout 子树实时预览时再立项）
- 判定不用「拖动根自身有 layout」或天真的子树遍历（bench 已否决，见背景）
