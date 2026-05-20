---
name: repo-compose-discipline
description: Compose UI discipline for repository work. Use when adding, editing, extracting, or reviewing Jetpack Compose pages, components, dialogs, sheets, lists, cards, toolbars, action rows, empty/loading/error states, navigation UI, or UI state models, especially when reuse, component placement, stable keys, previews, lifecycle, or string resources matter.
---

# Repo Compose Discipline

Use this skill with `$repo-coding-gate` for Compose/UI changes.

## Before Editing

1. Search for existing UI capability in this order:
   - same page/package,
   - same feature component/widget package,
   - shared UI/widget/design-system areas,
   - full repo fallback.
2. Decide placement:
   - page-level composable only orchestrates state and structure,
   - feature reusable component goes near the feature,
   - cross-feature component goes to shared UI/widget/design-system area,
   - page-private component requires a real page-specific reason.
3. Convert business entities to stable UI state/config where possible.
4. Prefer slots/callbacks/config objects over many booleans or nullable parameters.
5. Check string resources before adding user-visible text.
6. Check lifecycle: collect Flow/Paging with lifecycle-aware APIs; avoid starting heavy work from invisible UI.

## Implementation Rules

- Provide `modifier: Modifier = Modifier` for reusable composables.
- Keep business side effects out of shared composables; pass callbacks instead.
- Use stable keys for `LazyColumn`, `LazyRow`, grids, pagers, and animated items.
- Do not nest cards inside cards or build page sections as decorative cards unless existing design requires it.
- Avoid one-off duplicated dialogs/sheets/cards; extract a component or document why not.
- Keep UI text fitting on small screens and dynamic font sizes.
- Add concise Chinese comments for non-obvious state, side effects, or component boundaries.
- Add or update previews/sample states for complex shared UI when practical.

## Final Notes

In the final answer, state:

- what existing component was reused or why not,
- what component was added/split and where,
- whether any page-private component remains as a tracked exception,
- validation run.
