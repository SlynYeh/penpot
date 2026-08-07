# Penpot Frontend – Agent Instructions

ClojureScript SPA for the Penpot design editor. Built on **React 19** via the
[Rumext](https://github.com/funcool/rumext) `mf` macros, **RxJS**, and the **Potok**
event store, with **okulary** reactive lenses and **SCSS modules**. Shares `common/`
(CLC) and consumes the Rust/WASM renderer and JS/TS workspace packages.

## 1. Testing & Validation

### Unit tests

- Code changed in `src/` ⇒ add or update the matching test in `test/frontend_tests/`.
  Tests mirror the source namespace: `app.utils.timers` → `frontend-tests.util-timers-test`.
- Framework: `cljs.test`. Isolated — no full app state or running backend. Mock side effects
  (RPC, storage, timers) with `with-redefs`; assert on outcomes, not the DOM (e2e covers UI).
  Deterministic — no `setTimeout` or real network.
- **New test namespaces must be registered** in `frontend_tests/runner.cljs` (new vars in an
  existing namespace need no change).

```bash
pnpm run test:quiet                                   # full run, build output suppressed (preferred)
pnpm run test:quiet -- --focus frontend-tests.logic.components-and-tokens   # one namespace
pnpm run test:quiet -- --focus ns/test-specific-var                          # one var
pnpm run test:quiet -- --log-level warn              # silence app.* logging
pnpm run test                                        # full run, build output visible
pnpm run watch:test                                  # rebuild + rerun on change
```

Never pipe test output through `head`/`tail` (it can hide failures). Narrow with `--focus`,
or tee to a file first: `pnpm run test:quiet 2>&1 | tee /tmp/penpot-fe.txt`.

### Storybook component tests

The shared UI package (`packages/ui`) ships Storybook stories; visual tests run on Vitest:

```bash
pnpm run test:storybook          # vitest run --project=storybook
pnpm run watch:storybook         # storybook dev + watch
```

### E2E (Playwright)

Do **not** add, modify, or run tests under `frontend/playwright` unless explicitly asked.
When asked, ensure deps are installed (`./scripts/setup`) and mock every RPC/websocket the
page needs via the Page Object Models (`BasePage.mockRPC` already prefixes
`/api/rpc/command/`; pass command names like `get-profile`).

```bash
pnpm run test:e2e                       # all e2e
pnpm run test:e2e --grep "pattern"      # filtered
```

Prefer user-facing locators in this order: `getByRole` → `getByLabel` → `getByPlaceholder`
→ `getByText` → semantic (alt/title) → `getByTestId` (last resort).

## 2. Lint & Format

All changes must pass lint and formatting (run from `frontend/`):

```bash
# Lint
pnpm run lint:clj      # clj-kondo over ../common/src + src/   (CLJ/CLJS/CLJC)
pnpm run lint:scss     # stylelint over {src,resources}/**/*.scss
pnpm run lint:js       # currently a no-op (exit 0)

# Format check / fix
pnpm run check-fmt:clj && pnpm run check-fmt:js && pnpm run check-fmt:scss
pnpm run fmt           # fix all, or scope with fmt:clj / fmt:js / fmt:scss
```

After editing translation `.po` files, run `pnpm run translations` (changes are bundled into
`index.html` and need a browser refresh).

If an edit breaks delimiters in a `.cljs`, run `tools/paren-repair.bb` on the file **before**
linting — paren errors produce misleading compiler output.

## 3. Build & Stack-trace analysis

```bash
pnpm run watch:app     # dev watch (clears caches, builds WASM, watches assets + CLJS + libs)
pnpm run build:app     # production release build (main + worker + libs)
```

To map a minified production stack trace back to source, build locally with `pnpm run build:app`
(source maps land in `resources/public/js`). For bundle-size / unexpected-inclusion analysis,
inspect those modules or run a shadow-cljs build report (build IDs `main`, `worker` are in
`shadow-cljs.edn`).

## 4. Implementation rules

- **Logic vs. View:** Extract logic embedded in a UI component into a function — same
  namespace if only used locally, otherwise a helper namespace — so it is unit-testable.
- Prefer helpers in `app.util.dom` over direct DOM calls; add a new helper if none fits.
- Prefer the performance macros in `app.common.data.macros` over `clojure.core` equivalents
  (see below).

## 5. 访问地址

- **Base Url:** [http://localhost:8000/penpot/](http://localhost:8000/penpot/)
- **Dashboard:** [http://localhost:8000/penpot/dashboard/recent?team-id=e5eb2a15-2336-814a-8008-0254f8f90b6a](http://localhost:8000/penpot/dashboard/recent?team-id=e5eb2a15-2336-814a-8008-0254f8f90b6a)
- **File:** [http://localhost:8000/penpot/workspace?team-id=e5eb2a15-2336-814a-8008-0254f8f90b6a&file-id=f525d1de-aede-80a3-8008-5ff6cda4b19e&page-id=f525d1de-aede-80a3-8008-5ff6cda4b19f&layout=layers&wasm=false](http://localhost:8000/penpot/workspace?team-id=e5eb2a15-2336-814a-8008-0254f8f90b6a&file-id=f525d1de-aede-80a3-8008-5ff6cda4b19e&page-id=f525d1de-aede-80a3-8008-5ff6cda4b19f&layout=layers&wasm=false)

## 6. 访问令牌 access-token

调用API时可以使用下方token:

```text
eyJhbGciOiJBMjU2S1ciLCJlbmMiOiJBMjU2R0NNIn0.uy27tuFLNVevzoOvbf8A_DHnLlKbQjnqEjh5spEfiQ8_RqNT3fQSIA.RmpLGpl4sGb7r3Sz.gWOp_wp2DD78EIubiAHcQtewk5dpcSzLWjS9p-AcicFR8RZKJSACv_8U60M5f4cPiPEVRxtAU1IsrBiZ1nqlZuIB-VZQ9Y7DUUy_UW7DcqdDBBqKZU39OCX4o59mRkDXzj5bAJWx81g9U_6TZqTydnJTeP1ekT2Mp-p2SRkCQdXB1l9v1XdV5iQ43ljhKD9PdXq5yKd3AYQg.gWucFYlTwmwzzAwvklVm3A
```

## Code Conventions

### Namespace overview

- `app.main.ui.*` — Rumext/React UI components (workspace, dashboard, viewer, settings, auth).
- `app.main.data.*` — Potok event handlers (state mutations + side effects).
- `app.main.refs` — Reactive subscriptions (okulary lenses over the store).
- `app.main.store` — Potok store; `emit!` dispatches events.
- `app.util.*` — DOM, HTTP, i18n, keyboard, codegen, general utilities.
- `app.plugins.*` — CLJS implementation of the Plugin JS API proxies.
- `app.render_wasm.*` — bridge to the Rust/WASM renderer.
- `packages/*`, `text-editor/` — JS/TS workspace packages consumed by the app.

### State management (Potok)

State is a single atom in a Potok store. Events are defined with `ptk/reify` and implement
protocols:

```clojure
(defn my-event
  "docstring"
  [data]
  (ptk/reify ::my-event
    ptk/UpdateEvent
    (update [_ state]                 ;; synchronous state transition
      (assoc state :key data))

    ptk/WatchEvent
    (watch [_ state stream]           ;; async: returns an observable
      (->> (rp/cmd! :some-rpc-command params)
           (rx/map success-event)
           (rx/catch error-handler)))

    ptk/EffectEvent
    (effect [_ state _]               ;; pure side effects (DOM, logging)
      (dom/focus (dom/get-element "id")))))
```

Dispatch with `app.main.store/emit!`:

```clojure
(ns some.ns
  (:require [app.main.data.my-events :refer [my-event]]
            [app.main.store :as st]))

(st/emit! (my-event))
```

`app.main.refs` holds reactive lenses over the store. Use them with care — a complex lens
recomputes on every state change; for granular memoization prefer a simple reference plus
React's `mf/use-memo`.

### UI components (Rumext `mf/defc`)

**`*` suffix:** name components `my-component*`. The suffix tells the `mf/defc` macro to
apply its props/optimization handling.

```clj
(mf/defc my-component*
  {::mf/wrap [mf/memo]}            ;; React.memo equivalent
  [{:keys [name on-click]}]        ;; destructured props
  [:div {:class (stl/css :root)
         :on-click on-click}
   name])
```

**Hooks** (from the `mf` namespace): `mf/use-state` (returns an atom-like — `swap!`/`reset!`/`deref`,
not a setter function), `mf/use-effect`, `mf/use-memo`, `mf/use-fn`. `mf/deref` subscribes to an
atom or okulary lens (e.g. a ref in `app.main.refs`) and returns its current value.

**Prefer the macros** `mf/with-effect` and `mf/with-memo` over the function forms:

```clj
(mf/with-effect [team-id]
  (st/emit! (dd/initialize team-id))
  (fn [] (st/emit! (dd/finalize team-id))))

(mf/with-memo [projects team-id]
  (->> (vals projects) (filterv #(= team-id (:team-id %)))))
```

**Invoke** with `[:> component* props]`. `props` is a map literal (the macro interprets it), a
`#js` object, or built with `mf/spread-object`:

```clj
[:> my-component* {:data-foo "bar"}]
[:> my-component* (mf/spread-object base-props {:extra "data"})]
```

### Styles (CSS modules)

Each `.cljs` component has a co-located `.scss`. Reference classes via the
**`app.main.style` macros** (`stl/css`, `stl/css-case`) — required as a macro:

```clojure
(:require-macros [app.main.style :as stl])

[:div {:class (stl/css :container :active)}]                                   ;; one or more classes
[:div {:class (stl/css-case :some-class true :selected (= tool :rect))}]       ;; conditional
[:div {:class [existing-class (stl/css-case :selected (= tool :rect))]}]       ;; concat with an external class
```

The design system lives in `resources/styles/common/refactor/` and is imported at the top of a
component's `.scss`, conventionally aliased `as deprecated`:

```scss
@use "refactor/common-refactor.scss" as deprecated;

.modal-title {
  @include deprecated.headline-medium-typography;   // typography mixins are named *-typography
  color: var(--modal-title-foreground-color);        // colors are CSS custom properties
  margin-bottom: deprecated.$s-24;                   // spacing scale is $s-*
}
```

- Typography: `@include deprecated.<style>-typography;` (e.g. `headline-medium-typography`,
  `body-large-typography`, `big-title-typography`). Full list in
  `resources/styles/common/refactor/mixins.scss`.
- Colors: use the `var(--*)` custom properties / themes in
  `resources/styles/common/refactor/` (`color-defs.scss`, `themes/`, `design-tokens.scss`).
- Spacing/sizing/radius/shadows: use the defined variables (`$s-*`, etc.) — **do not** hardcode
  one-off values or invent new variables.
- Prefer logical CSS properties (`margin-inline-start`, `padding-inline-end`,
  `inset-inline-start`) over physical ones (`left`/`right`) for RTL/LTR support.
- Keep selectors flat and low-specificity (no deep nesting, no IDs); drive hover/focus via
  component-level CSS variables rather than re-declaring properties.
- The styles layer is mid-migration: most files use `refactor/...`, a few still `@use "ds/..."`.
  **Match the conventions of the neighboring files** rather than introducing a new pattern.

### Performance macros (`app.common.data.macros`)

Prefer these over `clojure.core` equivalents — they compile to faster JavaScript:

```clojure
(dm/select-keys m [:a :b])   ;; ~6x faster than core/select-keys
(dm/get-in obj [:a :b :c])   ;; faster than core/get-in
(dm/str "a" "b" "c")         ;; string concatenation
```

### Configuration

`src/app/config.cljs` reads the globally defined config (build-time `config.js`, env) and
exposes precomputed values to the rest of the app.
