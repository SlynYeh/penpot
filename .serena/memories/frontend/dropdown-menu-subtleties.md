# Dropdown Menus and Submenu Coordination

`app.main.ui.components.dropdown-menu` (`dropdown-menu*`) is the only mechanism that closes
one menu when another opens. Per-component local `use-state` does not dismiss sibling menus,
and z-index does not either: menus such as `main_menu.scss` and `help_center_menu.scss` all
resolve to the same `var(--z-index-dropdown)`, so two simultaneously open menus overlap.

## The `:id` eviction channel

- A dropdown with non-nil `:id` emits `(ptk/data-event :dropdown/open {:id id})` on mount and
  subscribes to `:dropdown/open`; a payload with a different id triggers its `on-close`. The
  decision lives in the exported pure predicate `dropdown-evicts?` — keep it a pure fn, it is
  unit-tested without DOM and inlining it back into the component removes that coverage.
- Same `:id` across several `dropdown-menu*` instances = one logical group that coexists.
  Distinct ids = mutually exclusive. Nil `:id` = fully opted out: never evicts, never evicted,
  so it must close through its own trigger/blur logic.
- Group identity must be a single shared value (private var/constant), not repeated literals.
  Divergent literals silently split one menu into mutually-exclusive instances.
- Ids are app-global, not file-scoped: two unrelated menus sharing an id will never close each
  other. Prefix ids with the region they belong to.

## Multi-level menus

- A primary list and its secondary panels must share one group id. Per-panel ids make opening
  a child immediately evict the parent, which breaks hover/click-driven two-level menus.
- Closing the whole group resets both the primary visibility flag and the active submenu
  state. Secondary panels often render outside the primary `show?` guard, so leaving submenu
  state set orphans a panel on screen with no primary list attached.
- When two menus overlap instead of replacing each other, first check whether both pass `:id`;
  a missing id puts that menu outside the channel entirely.
