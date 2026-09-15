# 快捷键面板手势条目拆分 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 keymap 面板 5 个手势条目（click-through 等）从「单个 `.keymap-gesture` 元素渲染整段文本」拆成「`.keymap-key` 键帽 + `.keymap-gesture` 手势词」两个独立元素。

**Architecture:** 数据层把 `gesture-shortcuts` 的每平台值从合并字符串改为 `[按键 手势词]` 二元向量，`gesture-text` 更名 `gesture-parts` 返回该向量；渲染层 `shortcut-keys*` 的手势分支用 rumext Fragment（`[:* …]`）渲染两个兄弟 span。SCSS 零改动。

**Tech Stack:** ClojureScript (rumext v2 + shadow-cljs)、cljs.test。设计文档：`docs/superpowers/specs/2026-09-08-keymap-gesture-split-design.md`。

**Git 纪律（仓库规则，优先于一切模板）**：不自动 commit/push；仅当用户明确要求时才提交，格式 `:emoji: 祈使句 ≤70字符` + 正文 + `Signed-off-by`（`git commit -s`）+ `AI-assisted-by: <model>`。建议提交信息见文末附表。

**宿主机测试命令（绕开 pnpm，2026-09-08 已验证可用）**：`pnpm` 因版本 pin 问题不可用（详见 memory `frontend-test-and-pnpm-workflow`），直接用 shadow-cljs + node。

---

### Task 1: 数据层 — `gesture-parts`（TDD）

**Files:**
- Modify: `frontend/src/app/main/data/keymap.cljs:23-39`（`gesture-shortcuts` + `gesture-text`）
- Modify: `frontend/src/app/main/data/keymap.cljs:140`（`display-alternatives` docstring 提及 `gesture-text`）
- Test: `frontend/test/frontend_tests/data/keymap_test.cljs`（新增 deftest + 更新 `all-shortcuts-resolve`）

- [ ] **Step 1: 写失败测试**

在 `frontend/test/frontend_tests/data/keymap_test.cljs` 的 `display-alternatives` deftest（约 66-70 行）之后插入：

```clojure
(t/deftest gesture-parts-platform
  (with-redefs [cf/check-platform? (constantly true)]
    (t/is (= ["⌘" "点击"] (km/gesture-parts :click-through)))
    (t/is (= ["⇧" "点击"] (km/gesture-parts :multi-select)))
    (t/is (= ["空格" "拖动"] (km/gesture-parts :drag-canvas)))
    (t/is (= ["⌘" "滚轮"] (km/gesture-parts :zoom-canvas)))
    (t/is (= ["⌥" "悬停目标图层"] (km/gesture-parts :measure-distance))))
  (with-redefs [cf/check-platform? (constantly false)]
    (t/is (= ["Ctrl" "点击"] (km/gesture-parts :click-through)))
    (t/is (= ["Shift" "点击"] (km/gesture-parts :multi-select)))
    (t/is (= ["空格" "拖动"] (km/gesture-parts :drag-canvas)))
    (t/is (= ["Ctrl" "滚轮"] (km/gesture-parts :zoom-canvas)))
    (t/is (= ["Alt" "悬停目标图层"] (km/gesture-parts :measure-distance))))
  (t/is (nil? (km/gesture-parts :move))))
```

> `with-redefs cf/check-platform?` 是本文件 `convert-char-platform`（41 行）已验证的同款手法；`cf` 已在 ns require 里。

- [ ] **Step 2: 编译 + 跑测试，确认失败（RED）**

```bash
cd /Users/slynyeh/Downloads/penpot-2.17.0/frontend
clojure -M:dev:shadow-cljs compile test && node target/tests/test.js --focus frontend-tests.data.keymap-test --log-level warn
```

Expected: 失败。CLJS 里 unresolved var 默认是 warning 不是 error，所以两种形态都算 RED：编译输出含 `Unable to resolve var: gesture-parts` 警告且测试运行报 error；或编译直接报错。若意外全绿，说明测试没生效，停下来排查。

- [ ] **Step 3: 实现 — 数据改二元向量 + 函数更名**

3a. 把 `frontend/src/app/main/data/keymap.cljs:23-39` 的块整体替换为：

```clojure
(def ^:private gesture-shortcuts
  {:click-through    {:windows ["Ctrl" "点击"]       :macos ["⌘" "点击"]}
   :multi-select     {:windows ["Shift" "点击"]      :macos ["⇧" "点击"]}
   :drag-canvas      {:windows ["空格" "拖动"]       :macos ["空格" "拖动"]}
   :zoom-canvas      {:windows ["Ctrl" "滚轮"]       :macos ["⌘" "滚轮"]}
   :measure-distance {:windows ["Alt" "悬停目标图层"] :macos ["⌥" "悬停目标图层"]}})

(defn gesture?
  [kw]
  (contains? gesture-shortcuts kw))

(defn gesture-parts
  "手势条目的 [按键 手势词] 显示文本（按当前平台取值）；
   两段均为可直接渲染的显示文本，不再过 convert-char"
  [kw]
  (when-let [entry (get gesture-shortcuts kw)]
    (if (cf/check-platform? :macos)
      (:macos entry)
      (:windows entry))))
```

（`gesture?` 原样保留，仅随块重排位置；函数体与原 `gesture-text` 相同，只更名。）

3b. `frontend/src/app/main/data/keymap.cljs:140` docstring 里 `仅对手势之外的条目调用（手势走 gesture-text）` 改为 `仅对手势之外的条目调用（手势走 gesture-parts）`。

3c. 更新同测试文件的 `all-shortcuts-resolve`（原 25-26 行）：

```clojure
    (if (km/gesture? kw)
      (t/is (= 2 (count (km/gesture-parts kw))) kw)
```

（原行是 `(t/is (some? (km/gesture-text kw)) kw)`，更名后必须同步，否则编译失败。）

- [ ] **Step 4: 编译 + 跑测试，确认通过（GREEN）**

```bash
cd /Users/slynyeh/Downloads/penpot-2.17.0/frontend
clojure -M:dev:shadow-cljs compile test && node target/tests/test.js --focus frontend-tests.data.keymap-test --log-level warn
```

Expected: 末尾输出 `Ran 8 tests containing N assertions.`（原 7 个 + 新 1 个）`0 failures, 0 errors.`

---

### Task 2: 渲染层 — `shortcut-keys*` 手势分支拆分

**Files:**
- Modify: `frontend/src/app/main/ui/workspace/keymap_panel.cljs:54-72`（`shortcut-keys*`）

- [ ] **Step 1: 修改手势分支**

把 `shortcut-keys*` 整个组件（54-72 行）替换为：

```clojure
(mf/defc shortcut-keys*
  {::mf/private true}
  [{:keys [kw alternatives]}]
  [:span {:class (stl/css :keymap-keys)}
   (if (km/gesture? kw)
     (let [[gkey gesture] (km/gesture-parts kw)]
       [:*
        [:span {:class (stl/css :keymap-key)} gkey]
        [:span {:class (stl/css :keymap-gesture)} gesture]])
     (let [groups (if alternatives
                    (or (km/display-alternatives kw) [])
                    [(km/display-chars kw)])]
       (for [[gidx group] (map-indexed vector groups)]
         [:span {:class (stl/css :keymap-key-group)
                 :key (dm/str (d/name kw) "-" gidx)}
          (for [[cidx char] (map-indexed vector group)]
            [:span {:class (stl/css :keymap-key)
                    :key (dm/str (d/name kw) "-" gidx "-" cidx)}
             (km/convert-char char)])
          (when (< (inc gidx) (count groups))
            [:span {:class (stl/css :keymap-key-sep)} "/"])])))])
```

改动点只有手势分支：原 `[:span {:class (stl/css :keymap-gesture)} (km/gesture-text kw)]`
变成 Fragment（`[:* …]`，rumext v2 语法，仓库先例 `sidebar/options/page.cljs:57`）包的
`.keymap-key` + `.keymap-gesture` 两个兄弟元素；其余分支逐字保留。

- [ ] **Step 2: 编译 main bundle 验证无编译错误**

```bash
cd /Users/slynyeh/Downloads/penpot-2.17.0/frontend
SHADOW_SERVER_URL=http://localhost:3447 clojure -M:dev:shadow-cljs compile main
```

Expected: `[:main] Build completed.`（env 变量必须带，否则 :dev 合并块的 spec 校验报错。）

---

### Task 3: 验证 — lint / fmt / 浏览器

**Files:** 无新改动（发现问题才回来修）。

- [ ] **Step 1: clj-kondo（宿主 -Sdeps 别名，2026-09-04 验证可用）**

```bash
cd /Users/slynyeh/Downloads/penpot-2.17.0
clojure -Sdeps '{:aliases {:kondo {:main-opts ["-m" "clj-kondo.main"] :extra-deps {clj-kondo/clj-kondo {:mvn/version "2025.12.23"}}}}}' -M:kondo --lint frontend/src/app/main/data/keymap.cljs frontend/src/app/main/ui/workspace/keymap_panel.cljs frontend/test/frontend_tests/data/keymap_test.cljs
```

Expected: 无 error/warning（lint output 为空或仅 `linting took` 摘要）。备选：devenv 容器 `docker exec penpot-devenv-ws0-main sh -c 'cd /home/penpot/penpot/frontend && pnpm run lint:clj'`。

- [ ] **Step 2: cljfmt check（必须从仓库根跑才读 .cljfmt.edn）**

```bash
cd /Users/slynyeh/Downloads/penpot-2.17.0
clojure -Sdeps '{:aliases {:fmt {:main-opts ["-m" "cljfmt.main" "check"] :extra-deps {dev.weavejester/cljfmt {:mvn/version "0.13.1"}}}}}' -M:fmt frontend/src/app/main/data/keymap.cljs frontend/src/app/main/ui/workspace/keymap_panel.cljs frontend/test/frontend_tests/data/keymap_test.cljs
```

Expected: 无输出（全部格式正确）。若报格式问题，把命令里的 `check` 换成 `fix` 修复后重跑 check，并复查 diff 无意外改动。

- [ ] **Step 3: 浏览器验证（devenv，watch 自动重编译，无需 touch）**

前置：devenv 在跑（不在则 `./manage.sh run-devenv --agentic`，等 watch 出 bundle）。

1. Playwright 打开 `https://localhost:3449`（origin 直连，勿走 :8000 缓存）进任一文件 workspace。
2. 打开快捷键面板（`keymap-panel*`，默认 `open?=true`，页面底部）。
3. 逐 tab 检查手势条目渲染为两个相邻元素：
   - **important**：click-through `[⌘][点击]`、multi-select `[⇧][点击]`、zoom-canvas `[⌘][滚轮]`、drag-canvas `[空格][拖动]`
   - **selection**：measure-distance `[⌥][悬停目标图层]`、click-through 同上
   - **zoom**：drag-canvas 同上
4. DOM 抽查：`.keymap-keys` 内手势条目应含一个 `.keymap-key` + 一个 `.keymap-gesture` 兄弟节点，键帽样式与同 tab 其他按键一致（2px flex gap 分隔）。
5. 检查浏览器 console 无新报错（rumext spread-props 类静态门槛抓不住的问题只能靠这里，memory `rumext-spread-props-gotcha`）。

Expected: 三个 tab 的 5 种手势全部呈现「键帽 + 胶囊」两元素；console 干净。

---

## 附表：建议提交信息（仅在用户明确要求提交时使用）

| 范围 | 信息 |
|---|---|
| 单次全量 | `:sparkles: Split keymap gestures into key and gesture elements` |

正文要点：keymap 面板手势条目拆分为 `.keymap-key` 键帽 + `.keymap-gesture` 手势词两个元素；`gesture-text` → `gesture-parts` 返回 `[按键 手势词]`；补 `gesture-parts-platform` 测试。
