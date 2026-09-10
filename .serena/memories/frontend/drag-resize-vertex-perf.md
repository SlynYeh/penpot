# Frontend Drag / Resize / Vertex-Drag Rendering Perf Coverage

Which historical rendering-perf optimizations apply to which drag interactions.
Analyzed 2026-09-03 on `perf/grid-layout-wasm-false`; every cited commit is an
ancestor of that branch HEAD. Rust-side semantics of interactive transforms:
`mem:render-wasm/ffi-rendering-subtleties`. Modifier semantics in general:
`mem:frontend/workspace-transform-subtleties`.

## Two separate pipelines (know which one you are touching)

Edge/corner resize — `start-resize`
(`frontend/src/app/main/data/workspace/transforms.cljs:151`). All 8 handles
(corners + sides) share one pipeline; the handle only changes
`get-handler-multiplier`. Preview sampled at 16ms (`mconst/resize-sample-time`,
`frontend/src/app/main/constants.cljs:337`):

- wasm=true → `set-wasm-modifiers`
  (`frontend/src/app/main/data/workspace/modifiers.cljs:683`). The modif-tree
  contains `ctm/resize`, so `translation?` is false → general path every frame:
  `set-wasm-props!` + `clean-modifiers` + `set-structure-modifiers` +
  `wasm.api/propagate-modifiers` (full Rust tree walk + FFI roundtrip) +
  `get-selection-rect`. Commit on pointer-up via `apply-wasm-modifiers`.
- wasm=false → `set-preview-modifiers` (fork addition, commit `e009fdcf58`;
  `modifiers.cljs:556`): raw modif-tree written straight to
  `:workspace-modifiers` with NO `gm/set-objects-modifiers` solve (no
  constraint/layout/descendant propagation). The SVG viewport only
  `gsh/transform-shape`s the *selected* shapes
  (`frontend/src/app/main/ui/workspace/viewport.cljs:66`). The full solve runs
  once on pointer-up (`rx/last` → `set-modifiers` → `apply-modifiers`).
  Valid ONLY for transform modifiers (resize/move/rotate); grid track/cell
  `change-property` modifiers render only via the solve, never through this.

Path-editor vertex drag — `start-move-handler` / `start-move-path-point` /
`move-selected` (`frontend/src/app/main/data/workspace/path/edition.cljs:243`).
Bypasses the modifiers system entirely: each pointer event merges deltas into
`:workspace-local :edit-path <id> :content-modifiers` (store update per event —
`move-handler-stream`, `path/streams.cljs:115`, has NO `rx/sample`, unlike
resize/move/rotate). Rendering: `path-wrapper*`
(`frontend/src/app/main/ui/workspace/shapes/path.cljs:29`) applies
content-modifiers at render time and recomputes geometry
(`types.path/update-geometry` → `content->selrect` → `calculate-extremities`)
per frame, memoized on `[shape content-modifiers]`. Commit via
`apply-content-modifiers` → `generate-path-changes`.

## Coverage by commit

Vertex drag (exactly one):
- `33bcbd89f1` (:zap: Optimize calculate-extremities, 2025-04): single-pass
  transient loop for path extremities (`common/src/app/common/types/path/segment.cljc:783`).
  Runs every preview frame via `update-geometry`; also benefits bool ops and
  library logic.

Edge/corner resize, wasm=true:
- PR #9190 `2aff116906` / `483ce8b1c9` (2026-04): `set_modifiers_start` → Rust
  `fast_mode` + `interactive_transform` (skip blur/shadows, skip AA, skip
  ancestor tile invalidation during `update_shapes`, chunked tile rendering +
  `current_tile_had_shapes` anti-flicker). Applies to EVERY gesture routed
  through `set-wasm-modifiers`: move/resize/rotate, grid track/cell drags
  (`viewport/grid_layout_editor.cljs:273/489/523`), flex padding/margin/gap
  (`ui/flex_controls/*`), text-editor resize (`texts.cljs:1020`). NOT vertex
  drag (path editor never calls it); NOT wasm=false.
- `5c4d16fc2b`: preview state (`workspace-selrect` / `wasm-modifiers`) moved
  from store state to Rx behavior-subjects + atom refs (bypasses lens
  re-derivation); sidebar `shape-options*` throttle 100→200ms.
- `8dd4b486e7`: `modified_shape_cache` (OnceCell) +
  `find_nearest_ancestor_modifier` in `render-wasm/src/state/shapes_pool.rs`
  also benefit resize rendering. Caveat: the hover-ids skip during drag only
  checks `transform=:move` — hover detection still runs every frame during
  resize drags (`viewport/hooks.cljs` `setup-hover-shapes`).
- `e950ec56eb`: text paragraphs built once per layout (general). The
  `new_bounds` skip only fires when size is unchanged → move only; resizing a
  text shape re-runs `new_bounds` per frame.

Edge/corner resize, wasm=false (fork):
- `e009fdcf58`: `set-preview-modifiers` lightweight preview (pipeline above) —
  the main perf lever for edge-resize in SVG mode.

Translation-only fast paths — do NOT apply to resize (resize matrices contain
scale; `d457eb5e5c` docstring names propagate-modifiers as "the general
(resize/rotate) case"):
- `d457eb5e5c`: CLJS-side subtree expansion skipping the WASM tree walk/FFI;
  `cached-translation-selrect`; `translation?` skips grid cell reassignment in
  `generate-update-shapes` on commit.
- `4a0cd0b7ce`: Rust move-only matrix fast paths (`transform_selrect` shift,
  extrect shift instead of invalidation, Path segments `+=tx/ty`,
  `skia_path.with_offset`). General exceptions that do help resize:
  `math::identitish` check in `get_skia_path`, single-solid-fill fast path in
  `merge_fills`.
- `27d854ed5b`: skip component-sync on pure-translation drag commits.
- `37f75a6fb5` (fork): SVG-mode solve filters ALL `only-move?` modifiers out of
  the ignore-tree (not just root frames).
- `f4516915a8` (fork): drag-move content follows cursor (move-only).

## Matrix

| scenario | 16ms sampling | wasm fast/interactive | preview coalesce | translation fast path | dedicated |
|---|---|---|---|---|---|
| move (wasm)            | yes | yes | yes | full set | — |
| move (svg)             | yes | —   | —   | yes (fork) | set-preview-modifiers |
| edge/corner resize (wasm) | yes | yes | yes | NO | modified_shape_cache |
| edge/corner resize (svg)  | yes | —   | —   | NO | set-preview-modifiers (fork) |
| vertex drag            | NO  | no (not even wasm) | no | no | calculate-extremities only |

## Quantified: cost of re-enabling the live solve for non-layout roots

Question analyzed 2026-09-03: if "children skip live re-layout during resize"
applied ONLY when the dragged root itself has a layout, what's the perf impact?
Benchmark: `tools/analysis/solve_resize_bench.clj` (JVM, modifier construction
mirrors `start-resize`; children pinned `:scale` so propagation emits entries;
grid cells assigned via `ctl/assign-cells`).

| scenario | shapes | solve ms/frame (JVM, warmed) | browser est (x2-4) |
|---|---|---|---|
| current fork preview (skip solve) | any | ~0.02 | ~0.1 |
| plain root, no layout, 100 children | 102 | 0.56 | 1-2 |
| plain root, no layout, 500 children | 502 | 1.34 | 3-5 |
| plain root, no layout, 2000 children | 2002 | 3.92 | 8-16 |
| plain root containing grid 10x10 | 103 | 1.83 | 4-7 |
| plain root containing grid 20x20 | 403 | 4.13 | 8-17 |
| plain root containing auto-sized grid 10x10 | 103 | 2.25 | 5-9 |
| grid 10x10 / 20x20 resized directly | 102/402 | 1.09 / 4.03 | 0 (rule skips) |

(NB: the first bench run without explicit JIT warmup over-reported the
plain-root rows ~2.5x — the first scenario executed pays cold-code costs.
Always warm ~100 iterations before timing; the saved scripts do.)

Key findings:

- **The condition "root has layout" is the wrong cost boundary.** Solve cost
  is driven by layout frames ANYWHERE in the subtree: `set-modifiers-layout`
  (`modifiers.cljc:184-192`) runs the flex/grid re-solve branches for every
  layout node in the resolve-tree expansion, NOT gated on that node having
  modifiers; `resolve-tree` always expands the dragged root's full subtree.
- **Inversion: resizing a plain frame that wraps a grid (4.1ms) costs ~2.3x
  MORE than resizing the grid directly (1.8ms)** — constraint propagation gives
  the grid child a scale modifier so it re-solves against transformed bounds,
  plus two full-subtree walks (`shapes-tree-all` + `shapes-tree-layout`) and
  three `transform-bounds-map` passes. The proposed rule exempts only the
  cheap case.
- Subtrees with NO layout anywhere are linear and affordable (~3-6us/shape
  JVM): ≤500 children stays ~60fps-capable in-browser. This is also where
  "children follow via constraints" is visually most wanted.
- Rendering side when the solve runs: no React reconciliation — solved
  entries are applied as direct DOM writes (`use-dynamic-modifiers`,
  `viewport/hooks.cljs:397`): one `dom/query #shape-<id>` + transform attr
  write per entry (~5-20us/shape; 400 entries ≈ 2-8ms). Text children are
  counter-scaled (`adapt-text-modifiers`), never re-laid out per frame.
- Unaffected either way: move drags (only-move filtered out of the layout
  tree; propagation takes the cheap splat branch at `modifiers.cljc:46-47`)
  and the one full solve on pointer-up (both rules pay it once).

Recommendation recorded: if the goal is children-follow + perf, the skip
condition should be "dragged subtree contains any flex/grid" (cheap, visually
correct constraint path for plain subtrees; layout subtrees stay frozen until
pointer-up), not "dragged root has a layout".

## Follow-up (2026-09-03): evaluating "skip iff affected tree has any flex/grid"

Second bench: `tools/analysis/solve_resize_bench2.clj` (warmed JVM).

**Ancestor hole — the boundary must be the SOLVE's tree, not the subtree.**
`resolve-tree` walks UP via `get-reflow-root` (`tree_seq.cljc:41`): a dragged
shape that is a DIRECT child of a layout (or whose ancestors are layouts /
groups / bools up to the layout) roots the solve at the layout ancestor and
re-solves its whole subtree. A plain-frame ancestor CUTS the walk (branch 1
returns immediately). Measured per frame when the dragged shape's own subtree
has NO layout:

| scenario | solve-tree | solve ms/frame (JVM) |
|---|---|---|
| grid10x10-full, resize plain frame that is a direct grid child | 111 (whole grid) | 1.77 |
| grid20x20-full, resize ONE direct rect child (icon-in-cell) | 401 (whole grid) | 4.06 |
| grid10x10-full, resize rect inside a plain frame inside the grid | 1 (walk cut) | 0.02 |

So a naive "subtree contains layout?" check re-introduces the exact jank the
fork killed, on the MOST common grid interaction (resizing an element inside a
cell). The condition must reuse the solve's own boundary:

```clojure
;; once per gesture (start-resize), not per frame
(defn affected-tree-has-layout? [ids objects]
  (boolean (some ctl/any-layout? (cgst/resolve-tree (set ids) objects))))
```

**Check cost**: 0.44ms JVM for a 2002-shape tree, once per gesture (subtree
membership and layout flags cannot change mid-resize) — negligible. Compute at
gesture start and cache; do not re-run per frame.

**Verdict on the refined rule** (skip iff affected tree has any flex/grid):

- Everything layout-involving stays skipped: 0 ms/frame (the 1.8-4.1ms cases
  above never run live).
- Only pure plain subtrees pay: 0.56 / 1.34 / 3.92 ms (100/500/2000 children,
  JVM) + DOM transform writes (~5-20us/entry, est 0.5-2ms per 100 entries,
  un-measured order-of-magnitude). Browser worst case ≈ 2-7ms (≤500 children,
  60fps safe) / 11-26ms (2000 children, borderline — a child-count cap can
  freeze those, e.g. >800).
- Failure direction is safe: false positives only freeze the preview (no
  jank); false negatives are impossible if the boundary reuses resolve-tree.
- Multi-selection: pass ALL dragged root ids to the check (search-common-roots
  merges them); one non-layout root whose closure hits a layout flips the
  whole gesture to skip.

Remaining worst case under the refined rule: giant pure-plain frames
(2k+ children). Optional cap on affected-tree size for the un-skip decision.

## Nested / mixed subtree coverage (2026-09-03, third bench)

Third bench: `tools/analysis/solve_resize_bench3.clj` (warmed JVM). Flex is
NOT covered — see landmine below. All costs below are per resize frame.

| scenario | shapes | layouts | solve ms/frame (JVM) |
|---|---|---|---|
| plain > 4 sibling grid5x5 (100 cells) | 106 | 4 | 2.40 |
| grid6x6 > 36 nested grid2x2 (grid-in-grid) | 182 | 37 | 3.67 |
| plain > grid6x6 > 36 plain frames (10c each) | 399 | 1 | 1.25 |
| plain>plain>plain>plain > 100 rects (deep chain) | 105 | 0 | 0.10 |
| grid6x6 > 36 grid2x2 ALL AUTO-SIZED | 182 | 37 | **62.93** |
| resize leaf inside nested grid2x2-in-grid6x6 | 42 | 1 (tree=5) | 0.07 |

Findings:

- **Auto-sizing cascade is a catastrophic amplifier**: identical 182-shape
  tree, 3.67ms fixed-size vs 62.93ms auto (17x). Browser x2-4 → 125-250ms
  per frame. `sizing-auto-modifiers` re-runs resolve-tree + layout
  propagation per auto layout. This is the single most important case to
  keep skipped — the refined rule does (layouts in tree).
- **Cost tracks layout COUNT, not node count**: 37 nested small grids (3.67ms
  @182 shapes) ≈ one 400-cell grid (4.13ms @403). Per-layout fixed cost
  ~0.1ms dominates. Sibling layouts: 4 grids cost 1.3x one grid at the same
  cell count.
- **Plain frames at depth are nearly free** (1.25ms @399 shapes with one
  layout; deep plain chain 0.10ms): depth itself adds nothing; only layouts
  and node count matter. (Deep-chain bench had entries=1 because frame
  children defaulted to left/left constraints — no-op under right-edge
  resize; the walk cost is what's measured.)
- **The ancestor hole is naturally bounded by nesting**: resizing a leaf in
  grid2x2-inside-grid6x6 roots at the INNER grid only (`get-reflow-root`
  returns the immediate layout parent) — 0.07ms. Deep layout nesting limits
  rather than amplifies the ancestor case.
- has-layout? check on the 182-shape nested tree: 0.003ms — trivial.

**Landmine — flex layout calc cannot run on JVM**: flex
`init-layout-lines` (flex_layout/layout_data.cljc:66-100) destructures nil
`line-data` on the first child and immediately multiplies/adds the results
(`num-children`, `line-min-*`). CLJS evaluates `8 * null` → 0 and
`null + x` → x silently; the JVM NPEs. Flex therefore has zero JVM test
coverage of its full solve and cannot be benched there. If flex numbers are
ever needed, run the same bench through the CLJS/node test build.

Verdict unchanged and reinforced: skip-iff-affected-tree-has-layout keeps
every nested/mixed/auto case (including the 63ms/frame one) at zero preview
cost; only pure-plain subtrees pay (linear, cheap).

Bench gotchas (cost a few debug rounds): `repeat` for tracks must be `vec`-ed
(`add-at-index` asserts vector); grids need `:layout :grid` (only
`:layout-grid-dir` is NOT a grid for `ctl/grid-layout?`); flex-track maps need
`:size`/`:max-size`; a synthetic flex frame could not be made to solve on JVM
(NPE in flex `init-layout-lines`) — flex numbers are qualitative (same
`calc-layout-data` structure as grid).

## Known gaps (candidate follow-ups)

- `move-handler-stream` has no `rx/sample`; adding 16ms sampling would match
  the other gestures (each pointer event currently hits the store).
- Hover-ids skip condition `transform=:move` could widen to any non-nil
  `transform`.
- Text resize re-runs `new_bounds` per frame in wasm (only move skips it).
- Resize still pays full `propagate-modifiers` per frame in wasm and the full
  solve on pointer-up in both modes — optimizations cover rendering mode, tile
  invalidation and state channels, not modifier propagation itself.
