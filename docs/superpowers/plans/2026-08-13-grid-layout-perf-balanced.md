# Grid Layout Performance (wasm=false) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make grid-layout interactions (resize, add/remove row/col, add/remove siblings, resize parent) smooth in `wasm=false` (SVG/React-DOM) mode by attacking both levers: per-call reflow cost (geom) and call frequency (frontend).

**Architecture:** Two independent levers, confirmed by source: (A) `sizing-auto-modifiers` re-solves each grid/flex `calc-layout-data` ~3× per auto node with zero caching (~6s auto phase); (B) `wasm=false` runs a full reflow every animation frame during drag, and grid track/cell resize is completely unthrottled. We add a per-call `calc-layout-data` cache (A1), throttle the shared `use-drag` hook to one rAF (B2), and a lightweight no-solve preview for shape transforms (B1). Pointer-up still runs the exact full solve, so committed results are bit-identical to today.

**Tech Stack:** ClojureScript (frontend), CLJC (common geom), Rumext hooks, Potok + RxJS, `app.util.timers` rAF helper, `cljs.test` / `karma` for CLJS, `clojure.test` for JVM common.

**Repo discipline:** CLAUDE.md / AGENTS.md govern. No `git push`. Commit only on request, with `:emoji:` subject + `Signed-off-by` + `AI-assisted-by`. Run `tools/paren-repair.bb` on any `.clj/.cljs/.cljc` you edit before linting.

---

## Refined scope: which fix applies to which operation

Reading the actual code corrected the earlier assumption. B1 (skip the layout solve during preview) is only valid where the dragged element's own transform is the feedback:

| Operation | Modif-tree kind | Preview needs layout solve? | Winning fix |
|---|---|---|---|
| Shape resize / move / rotate (`transforms.cljs`) | geometry transform | No — own transform is enough | **B1** (+A1 for the pointer-up settle) |
| Grid track / cell resize (`grid_layout_editor.cljs`) | `change-property` (rows/cols/cells) | **Yes** — track size only renders via re-solve | **A1 + B2** |
| Add/remove row/col, add/remove sibling, resize parent | one-shot, already `buffer-time 100` batched | Yes | **A1** (one solve, make it cheap) |

So: **A1 is the core fix for the grid complaint**; B2 caps frequency everywhere; B1 smooths shape transforms. All three compose.

---

## File map

- Modify `common/src/app/common/geom/modifiers.cljc` — Phase 0 timing + Phase 2 (A1) cache `binding` (and add `glld` require).
- Modify `common/src/app/common/geom/shapes/grid_layout/layout_data.cljc` — Phase 2 (A1): define `*grid-layout-cache*` + cache consult in `calc-layout-data`.
- Modify `frontend/src/app/main/ui/workspace/viewport/grid_layout_editor.cljs` — Phase 1 (B2) throttle `use-drag`.
- Modify `frontend/src/app/main/data/workspace/modifiers.cljs` — Phase 3 (B1) add `set-preview-modifiers`.
- Modify `frontend/src/app/main/data/workspace/transforms.cljs` — Phase 3 (B1) wire preview into resize (move/rotate follow same pattern).
- Test `common/test/common_tests/geom_modifiers_test.cljc` — Phase 2 (A1) equality test (add `glld` require + one deftest).

---

## Phase 0 — Baseline measurement + dev guard (prerequisite, do first)

**Why:** A1 has rollback history (a prior batch rewrite changed layout results). We cannot claim "faster, same result" without a measured before/after.

### Task 0.1: Establish `[som]` timing in `set-objects-modifiers`

**Files:**
- Modify: `common/src/app/common/geom/modifiers.cljc:385-388`

- [ ] **Step 1: Replace the `sizing-auto-modifiers` binding with a dev-gated timed call**

Current (`modifiers.cljc:384-388`):
```clojure
         ;; Find layouts with auto width/height
         sizing-auto-layouts (find-auto-layouts objects shapes-tree-layout)

         modif-tree
         (sizing-auto-modifiers modif-tree sizing-auto-layouts objects bounds-map ignore-constraints)
```

Replace with:
```clojure
         ;; Find layouts with auto width/height
         sizing-auto-layouts (find-auto-layouts objects shapes-tree-layout)

         modif-tree
         (let [result
               #?(:cljs
                  (let [t0 (js/performance.now)
                        r  (sizing-auto-modifiers modif-tree sizing-auto-layouts objects bounds-map ignore-constraints)]
                    (when ^boolean *assert*
                      (.log js/console "[som]"
                            "auto#=" (count sizing-auto-layouts)
                            "auto=" (.toFixed (- (js/performance.now) t0) 1) "ms"))
                    r)
                  :clj
                  (sizing-auto-modifiers modif-tree sizing-auto-layouts objects bounds-map ignore-constraints))]
           result)
```

- [ ] **Step 2: Build the frontend and confirm it compiles**

Run: `cd frontend && pnpm run watch:app` (or the devenv watch). Confirm no compile error.
Expected: compiles; `[som]` lines only appear in a dev build (where `*assert*` is true).

- [ ] **Step 3: Capture the baseline number**

With the app running (`wasm=false`), open the problematic board, resize a grid cell once. From the browser console, record the `[som] auto#=… auto=…ms` line.
Expected: a concrete baseline, e.g. `auto#=108 auto=6087.2ms`. Save this number — every later phase compares against it.

- [ ] **Step 4: Commit**

```bash
git add common/src/app/common/geom/modifiers.cljc
git commit -m ":sparkles: add [som] baseline timing to sizing-auto-modifiers

Co-Authored-By: Claude <noreply@anthropic.com>"
```
(Add `Signed-off-by` + `AI-assisted-by:` trailers per `mem:workflow/creating-commits` before committing — only if the user asks to commit.)

---

## Phase 1 — B2: throttle the shared `use-drag` hook (quick win, low risk)

**Why:** `use-drag`'s `handle-pointer-move` calls `on-drag-position` on every native pointermove (can be 120Hz+), and each call `st/emit!`s a full reflow. Grid track/cell resize have NO throttling at all. Coalescing to one rAF caps emits at one-per-rendered-frame and is a single localized change that fixes both track-resize and cell-resize.

### Task 1.1: Add `app.util.timers` require

**Files:**
- Modify: `frontend/src/app/main/ui/workspace/viewport/grid_layout_editor.cljs:9-40` (ns form)

- [ ] **Step 1: Add the require**

In the `:require` vector, add (alphabetically, near `[app.util.keyboard :as kbd]`):
```clojure
   [app.util.timers :as tmr]
```

### Task 1.2: Coalesce pointer-move into rAF

**Files:**
- Modify: `frontend/src/app/main/ui/workspace/viewport/grid_layout_editor.cljs:140-185` (`use-drag`)

- [ ] **Step 1: Add a pending-rAF ref and cancel helper; route move through rAF**

Replace the whole `use-drag` body (lines 140-185) with:
```clojure
(defn use-drag
  [{:keys [on-drag-start on-drag-end on-drag-delta on-drag-position]}]
  (let [dragging-ref    (mf/use-ref false)
        start-pos-ref   (mf/use-ref nil)
        current-pos-ref (mf/use-ref nil)
        raf-id-ref      (mf/use-ref nil)

        cancel-pending-raf
        (mf/use-fn
         (fn []
           (when-let [id (mf/ref-val raf-id-ref)]
             (tmr/cancel-af! id)
             (mf/set-ref-val! raf-id-ref nil))))

        handle-pointer-down
        (mf/use-fn
         (mf/deps on-drag-start)
         (fn [event]
           (let [raw-pt (dom/get-client-position event)
                 position (uwvv/point->viewport raw-pt)]
             (dom/capture-pointer event)
             (mf/set-ref-val! dragging-ref true)
             (mf/set-ref-val! start-pos-ref raw-pt)
             (mf/set-ref-val! current-pos-ref raw-pt)
             (when on-drag-start (on-drag-start event position)))))

        handle-lost-pointer-capture
        (mf/use-fn
         (mf/deps on-drag-end cancel-pending-raf)
         (fn [event]
           ;; Drop any not-yet-flushed frame so a stale position can never
           ;; be emitted after drag-end. current-pos-ref always holds the
           ;; exact latest pointer position (updated synchronously on move),
           ;; so on-drag-end computes the precise final modifiers.
           (cancel-pending-raf)
           (let [raw-pt (mf/ref-val current-pos-ref)
                 position (uwvv/point->viewport raw-pt)
                 start (mf/ref-val start-pos-ref)
                 delta (gpt/to-vec start raw-pt)]
             (dom/release-pointer event)
             (mf/set-ref-val! dragging-ref false)
             (mf/set-ref-val! start-pos-ref nil)
             (when on-drag-end (on-drag-end event position delta)))))

        handle-pointer-move
        (mf/use-fn
         (mf/deps on-drag-delta on-drag-position cancel-pending-raf)
         (fn [event]
           (when (mf/ref-val dragging-ref)
             (let [pos (dom/get-client-position event)]
               ;; Keep the latest raw position synchronously — drag-end is exact.
               (mf/set-ref-val! current-pos-ref pos)
               ;; Coalesce the (potentially expensive) emit into the next
               ;; animation frame: at most one on-drag-position per rendered
               ;; frame, regardless of pointermove frequency.
               (cancel-pending-raf)
               (mf/set-ref-val! raf-id-ref
                 (tmr/raf
                  (fn []
                    (mf/set-ref-val! raf-id-ref nil)
                    (let [start (mf/ref-val start-pos-ref)
                          p     (mf/ref-val current-pos-ref)]
                      ;; Note: `event` here is only passed for API symmetry;
                      ;; every consumer (cell/track/reorder handlers) ignores
                      ;; it (their signature is (fn [_ position] ...)), so a
                      ;; recycled synthetic event after rAF is harmless.
                      (when on-drag-delta (on-drag-delta event (gpt/to-vec start p)))
                      (when on-drag-position (on-drag-position event (uwvv/point->viewport p)))))))))))]

    {:handle-pointer-down handle-pointer-down
     :handle-lost-pointer-capture handle-lost-pointer-capture
     :handle-pointer-move handle-pointer-move}))
```

- [ ] **Step 2: Paren-safety + format**

Run: `./tools/paren-repair.bb frontend/src/app/main/ui/workspace/viewport/grid_layout_editor.cljs` then `cd frontend && pnpm run check-fmt:clj && pnpm run lint:clj`.
Expected: clean.

- [ ] **Step 3: Manual verification of correctness (drag-end must be exact)**

In the app (`wasm=false`): drag a grid track to resize, release. The committed track size must equal where you released (no off-by-one, no jump-back). Repeat for cell resize and track reorder.
Expected: final committed state identical to before the change.

- [ ] **Step 4: Manual verification of frequency reduction**

While dragging a grid track, watch the console `[som]` lines. Before: one per pointermove (many per frame). After: at most one per frame (~60/s).
Expected: `[som]` emission rate capped to ~60Hz during drag.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/main/ui/workspace/viewport/grid_layout_editor.cljs
git commit -m ":zap: throttle grid-layout-editor drag via requestAnimationFrame"
```
(trailers per workflow memory, only if asked to commit.)

---

## Phase 2 — A1: per-call `calc-layout-data` cache (the core grid fix)

**Why:** Within one `set-objects-modifiers` call the same grid's `calc-layout-data` is solved redundantly — once in `propagate-modifiers-layouts` (`set-grid-layout-modifiers`, `modifiers.cljc:125`) and again per auto node in `sizing-auto-modifiers` (`calc-auto-modifiers`, `modifiers.cljc:249`), plus recursively from `child-min-*`. When ~108 auto nodes share grid ancestors, the same grid is solved with **identical inputs** ~108×. A cache keyed by the actual input content collapses that to 1×.

**Correctness crux (the thing that broke last time):** `calc-auto-modifiers` (call site :249) runs with a *different* `bounds-map* than `set-grid-layout-modifiers` (call site :125) — bounds evolves through `transform-bounds-map` (`modifiers.cljc:372,382`). So the key **cannot** be grid-id alone; it must fingerprint the variable inputs (`transformed-parent-bounds`, `children`, `bounds`, `auto?`). A wrong key returns a stale result → wrong layout. The dev guard in Task 2.5 proves the key is complete.

### Task 2.1: Write the failing equality test (RED)

**Files:**
- Test: `common/test/common_tests/geom_modifiers_test.cljc` (add a var; this is the real path — confirmed existing grid/flex `set-objects-modifiers` tests live here).

- [ ] **Step 1: Add the `glld` require to the test ns**

In the `(:require […] )` of `common_tests.geom-modifiers-test`, add:
```clojure
   [app.common.geom.shapes.grid_layout.layout_data :as glld]
```
(`gm`/`ctm`/`gpt`/`thf`/`ths`/`thi` are already required — they're used by the existing `grid-layout-with-nonexistent-child` test at line 122.)

- [ ] **Step 2: Add a test asserting cached == uncached**

This mirrors the existing `grid-layout-with-nonexistent-child` fixture (a grid frame + a rect child) but makes the child a `:fill` cell and resizes it, so the auto-sizing path runs. Append:
```clojure
(t/deftest sizing-auto-modifiers-cache-preserves-result
  (t/testing "binding *grid-layout-cache* does not change set-objects-modifiers output"
    (let [file    (-> (thf/sample-file :file1)
                      (ths/add-sample-shape :frame1
                                            {:type :frame
                                             :name "GridFrame"
                                             :layout-grid-dir :row
                                             :width 200
                                             :height 200})
                      (add-rect-child :rect1 :frame1))
          page    (thf/current-page file)
          objects (-> (:objects page)
                      (assoc-in [(thi/id :rect1) :layout-item-h-sizing] :fill)
                      (assoc-in [(thi/id :rect1) :layout-item-v-sizing] :fill))
          rect-id (thi/id :rect1)
          modif   {rect-id {:modifiers (ctm/resize-modifiers
                                        (gpt/point 1.5 1.5)
                                        (gpt/point 0 0))}}
          uncached (gm/set-objects-modifiers modif objects)
          cached   (binding [glld/*grid-layout-cache* (atom {})]
                     (gm/set-objects-modifiers modif objects))]
      (t/is (= uncached cached)))))
```
(If `add-rect-child` is a private helper in this file rather than required, it is still callable from a `deftest` in the same ns — the existing tests already call it that way.)

- [ ] **Step 3: Run it to confirm it fails for the right reason**

Run: `cd common && clojure -M:dev:test --focus common-tests.geom-modifiers-test/sizing-auto-modifiers-cache-preserves-result`
Expected: FAIL — `Unable to resolve symbol: *grid-layout-cache*` (the var is not defined yet). This is the RED state.

### Task 2.2: Define the cache dynamic var

**Files:**
- Modify: `common/src/app/common/geom/shapes/grid_layout/layout_data.cljc` (the `glld` ns — this is where `calc-layout-data` actually lives; `grid_layout.cljc` only re-exports it via `(dm/export glld/calc-layout-data)` at line 15).

**Why here, not `grid_layout.cljc`:** `binding` must target the *same var object* that `calc-layout-data` reads. Defining it next to `calc-layout-data` (in `glld`) and binding `glld/*grid-layout-cache*` from `modifiers.cljc` guarantees one var, no aliasing trap.

- [ ] **Step 1: Add the dynamic var near the top of the ns (after the `(:require …)` form)**

```clojure
;; Per-set-objects-modifiers-call cache for calc-layout-data. Bound to a
;; fresh atom in app.common.geom.modifiers/set-objects-modifiers; nil
;; elsewhere (no caching). Keyed by a fingerprint of calc-layout-data's
;; inputs so that bounds-map evolution between pipeline stages can never
;; cause a stale hit.
(def ^:dynamic *grid-layout-cache* nil)
```

### Task 2.3: Consult + populate the cache inside `calc-layout-data`

**Files:**
- Modify: `common/src/app/common/geom/shapes/grid_layout/layout_data.cljc:398-401` (the `calc-layout-data` 1-arg → 5-arg delegations + the 6-arg body).

- [ ] **Step 1: Wrap the body of the 6-arg arity in a cache lookup**

The function is `(defn calc-layout-data ([parent tp-bounds children bounds objects] … false) ([parent tp-bounds children bounds objects auto?] <body>))`. Keep both arities; only wrap `<body>` of the 6-arg form. Replace the 6-arg body's outermost `let` with a cache check:

```clojure
  ([parent transformed-parent-bounds children bounds objects auto?]
   (if-not *grid-layout-cache*
     ;; No cache bound (e.g. JVM tests calling calc-layout-data directly):
     ;; behave exactly as before.
     (calc-layout-data* parent transformed-parent-bounds children bounds objects auto?)

     (let [fingerprint
           [(:id parent)
            auto?
            (hash (:layout-grid-columns parent))
            (hash (:layout-grid-rows parent))
            (hash (:layout-grid-cells parent))
            (hash transformed-parent-bounds)
            (hash (mapv (fn [[child-bounds child]]
                          [(hash @child-bounds) (:id child)])
                        children))]]
       (if-let [hit (find @*grid-layout-cache* fingerprint)]
         (val hit)
         (let [result (calc-layout-data* parent transformed-parent-bounds children bounds objects auto?)]
           (swap! *grid-layout-cache* assoc fingerprint result)
           result))))))
```

- [ ] **Step 2: Rename the original body to `calc-layout-data*` (private)**

Rename the current `(defn calc-layout-data …)` 6-arg body to a private `(defn- calc-layout-data* …)` with the **exact same body** (move only the name). Keep its 4-arg/5-arg delegating arities calling `calc-layout-data*`:
```clojure
(defn- calc-layout-data*
  ([parent transformed-parent-bounds children bounds objects]
   (calc-layout-data* parent transformed-parent-bounds children bounds objects false))
  ([parent transformed-parent-bounds children bounds objects auto?]
   ;; … the original body, unchanged …
   ))
```
Then the public `calc-layout-data` is only the caching wrapper above (same arities, delegating to `calc-layout-data*` when cache is nil, or to the fingerprint path when bound).

**Why the split:** zero behavior change when the cache is unbound (every existing direct caller and JVM test is untouched); caching only activates inside `set-objects-modifiers`.

- [ ] **Step 3: Make the equality test pass (GREEN)**

Run: `cd common && clojure -M:dev:test --focus common-tests.geom-modifiers-test/sizing-auto-modifiers-cache-preserves-result`
Expected: PASS. If it FAILS with a value mismatch, the fingerprint is missing an input that varies between call sites — add that input to the `fingerprint` vector (e.g. `(hash bounds)` if bounds identity matters) and re-run. Do not proceed until green.

### Task 2.4: Bind the cache in `set-objects-modifiers`

**Files:**
- Modify: `common/src/app/common/geom/modifiers.cljc:18` (ns require) and `:334-397` (`set-objects-modifiers` 4-arg body).

- [ ] **Step 1: Add the `glld` require**

`modifiers.cljc` currently requires `gcgl` (line 18) but not `glld`. Add right after the gcgl require:
```clojure
   [app.common.geom.shapes.grid_layout.layout_data :as glld]
```
(No circular dependency: modifiers → gcgl → glld already exists transitively; glld does not require modifiers.)

- [ ] **Step 2: Wrap the pipeline body in a `binding`**

The current 4-arg body is `(let […pipeline…] … modif-tree)` (lines 341-397). Wrap it so the cache is fresh per call:
```clojure
   (binding [glld/*grid-layout-cache* (atom {})]
     (let [;; …the entire existing let body, unchanged…
           …]
       modif-tree))
```
When the cache is unbound (every caller other than `set-objects-modifiers`, plus JVM tests), `calc-layout-data` takes its `*grid-layout-cache*`-nil branch and behaves exactly as before.

- [ ] **Step 2: Paren-safety + format + full common regression**

Run:
```bash
./tools/paren-repair.bb common/src/app/common/geom/modifiers.cljc
./tools/paren-repair.bb common/src/app/common/geom/shapes/grid_layout/layout_data.cljc
cd common && clojure -M:dev:test --focus common-tests.geom-grid-layout-test
cd common && clojure -M:dev:test --focus common-tests.geom-flex-layout-test
cd common && clojure -M:dev:test --focus common-tests.geom-modifiers-test
```
Expected: ALL PASS. These three suites are the correctness gate for layout. Any red = the fingerprint is incomplete or the rename leaked; fix before continuing.

### Task 2.5: Dev-guard bit-equality on the real board + measure

**Files:** none (measurement only).

- [ ] **Step 1: Confirm committed layout is unchanged**

In the app (`wasm=false`), perform the full complaint matrix on the problematic board: resize a cell, resize a track, add/remove a row, add/remove a column, resize the parent frame. After each, the final laid-out geometry must be visually identical to the Phase-0 baseline build.
Expected: no layout drift on commit.

- [ ] **Step 2: Measure the speedup**

Compare the `[som] auto=…ms` number (same single cell resize) against the Phase 0 baseline.
Expected: `auto` time drops materially (target: the ~6087ms baseline → substantially lower; the cache collapses the 108× redundant solves). Record the after-number.

- [ ] **Step 3: If speedup is marginal, diagnose before extending**

If `auto` barely dropped, the redundant solves did not share fingerprints (inputs genuinely differ per call) — the win is elsewhere. In that case do NOT pile on more changes; instead re-profile with the `[som]` timer widened to also wrap `propagate-modifiers-layouts`, and reconsider A2 (merge shared reflow-roots) instead. Record findings.

- [ ] **Step 4: Commit**

```bash
git add common/src/app/common/geom/modifiers.cljc common/src/app/common/geom/shapes/grid_layout/layout_data.cljc common/src/app/common/geom/shapes/grid_layout.cljc common/test/...
git commit -m ":zap: cache calc-layout-data per set-objects-modifiers call"
```

---

## Phase 3 — B1: lightweight no-solve preview for shape transforms

**Why:** For shape resize/move/rotate, the dragged element's own transform is the feedback the user needs; descendants reflowing can wait for pointer-up. Skipping `gm/set-objects-modifiers` during the drag removes the ~6s/frame freeze. (Does NOT apply to grid track/cell resize — those need the solve to render track sizes; covered by Phase 1+2.)

### Task 3.1: Add `set-preview-modifiers` event

**Files:**
- Modify: `frontend/src/app/main/data/workspace/modifiers.cljs` (add near `set-modifiers`, ~line 554).

- [ ] **Step 1: Add the event**

```clojure
(defn set-preview-modifiers
  "Lightweight live-preview for wasm=false: writes the raw modif-tree straight
  to :workspace-modifiers WITHOUT running gm/set-objects-modifiers (no
  layout/constraint/auto-sizing propagation). Only the explicitly-modified
  shapes render their new transform; descendants are NOT re-laid-out until
  pointer-up. The trailing rx/last frame and apply-modifiers still run the
  full solve, so the committed result is exact.

  Only valid for transform modifiers (resize/move/rotate). Do NOT use for
  grid track/cell change-property modifiers — those only render via the solve."
  ([modif-tree]
   (set-preview-modifiers modif-tree false))
  ([modif-tree _ignore-snap-pixel]
   (ptk/reify ::set-preview-modifiers
     ptk/UpdateEvent
     (update [_ state]
       (assoc state :workspace-modifiers modif-tree)))))
```

### Task 3.2: Wire preview into resize (sampled frames), keep full solve on the final frame

**Files:**
- Modify: `frontend/src/app/main/data/workspace/transforms.cljs:332-346`.

- [ ] **Step 1: Split emit into preview (cheap) and final (full solve)**

Current:
```clojure
                      (let [emit-modifiers
                            (fn [modifiers]
                              (let [modif-tree (dwm/create-modif-tree shape-ids modifiers)]
                                (rx/of (dwm/set-modifiers modif-tree (contains? layout :scale-text)))))]
                        (rx/merge
                         (->> resize-events-stream
                              (rx/sample mconst/resize-sample-time)
                              (rx/mapcat emit-modifiers)
                              (rx/take-until stopper))
                         (->> resize-events-stream
                              (rx/take-until stopper)
                              (rx/last)
                              (rx/mapcat emit-modifiers))))
```

Replace with:
```clojure
                      (let [emit-preview
                            (fn [modifiers]
                              (let [modif-tree (dwm/create-modif-tree shape-ids modifiers)]
                                (rx/of (dwm/set-preview-modifiers modif-tree))))

                            emit-final
                            (fn [modifiers]
                              (let [modif-tree (dwm/create-modif-tree shape-ids modifiers)]
                                (rx/of (dwm/set-modifiers modif-tree (contains? layout :scale-text)))))]
                        ;; Live frames skip the layout solve (smooth); the
                        ;; trailing rx/last + apply-modifiers run the exact
                        ;; full solve so the committed result is unchanged.
                        (rx/merge
                         (->> resize-events-stream
                              (rx/sample mconst/resize-sample-time)
                              (rx/mapcat emit-preview)
                              (rx/take-until stopper))
                         (->> resize-events-stream
                              (rx/take-until stopper)
                              (rx/last)
                              (rx/mapcat emit-final))))
```

- [ ] **Step 2: Manual verification**

Resize a shape (not a grid track): the drag must be smooth (no `[som]` lines during the move — the solve is skipped). On release, the shape and its descendants settle to the correct laid-out positions (the `[som]` line fires once on pointer-up).
Expected: smooth drag; one solve on release; final geometry identical to baseline.

### Task 3.3 (optional): Apply the same preview pattern to move and rotate

**Files:**
- Modify: `frontend/src/app/main/data/workspace/transforms.cljs:846-877` (move) and `:538-553` (rotate).

- [ ] **Step 1:** Replicate Task 3.2's preview/final split in the non-wasm branches of `start-move` and `start-rotate` (same shape: sampled → `set-preview-modifiers`, `rx/last` → `set-modifiers`). Keep `apply-modifiers` + `finish-transform` unchanged.
Expected: move and rotate drag smoothly; commit identical.

---

## Verification (whole plan)

- [ ] **Common geom regression green:** `geom-grid-layout-test`, `geom-flex-layout-test`, `geom-modifiers-test` (JVM, `clojure -M:dev:test --focus …`).
- [ ] **CLJS regression green:** `cd frontend && pnpm run test:quiet -- --focus frontend-tests.logic.components-and-tokens` (covers layout).
- [ ] **Lint/fmt clean:** `./scripts/lint && ./scripts/check-fmt`.
- [ ] **Manual complaint matrix (wasm=false), all smooth + committed layout unchanged:** resize cell, resize track, resize parent, add/remove row, add/remove column, add/remove sibling, resize shape, move, rotate.
- [ ] **`[som]` after-numbers recorded** for each phase in the plan's commit messages or a results note.

## Rollback notes
- Phase 0 is instrumentation only — safe to keep or drop.
- Phase 1 (B2) is isolated to `use-drag` — revert one file.
- Phase 2 (A1) is gated by `*grid-layout-cache*` being bound; if any layout drift appears in QA, removing the single `binding` form in `set-objects-modifiers` disables it instantly with zero other changes.
- Phase 3 (B1) is per-call-site; revert the `emit-preview`/`emit-final` split to restore the old `set-modifiers`-every-frame behavior.

## Out of scope (explicitly not doing, with rationale)
- **A3 (fix `full-tree?` defeater):** high risk (this is what made the prior batch rewrite change layout results), ~10–20% upside. Not worth it while A1 lands the bulk of the win.
- **A5 (skip off-screen subtrees):** mismatch — the lag is on the on-screen grid being interacted with.
- **B3 (web worker offload):** `lazy-map`/`delay` don't cross the worker boundary; serialization cost may erase the gain; B1 is simpler for the same problem.
- **C1 (push wasm=true):** the highest single-path win, but a separate decision — worth a dedicated validation pass (the `a10c3da204` webgl-toggle fix may have unblocked it). Tracked in memory, not this plan.
