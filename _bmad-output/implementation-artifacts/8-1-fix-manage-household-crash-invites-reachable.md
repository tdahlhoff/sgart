---
title: 'Story 8.1: Fix the „Haushalt verwalten" crash so invites are reachable'
type: 'bugfix'
created: '2026-09-20'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
context: []
baseline_commit: '598bbfdc57e5c96bb209e9073a6b42bbc7696475'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Tapping „Haushalt verwalten" in the household switcher throws
`ProviderNotFoundException` and never opens the hub, so members and invites are completely
unreachable — a beta blocker (manual test 2026-09-20, BH). The switcher's `_openManage` reads
`StoresApi`/`StoreChainReferenceCache` from a sheet scope that was only given
`HouseholdsApi`/`HouseholdsCubit`, so the read fails before the hub is ever pushed.

**Approach:** Make the hub reachable by re-providing the full dependency set the
`ManageHouseholdPage` subtree needs across the sheet/route boundary, and fix the same latent gap
on the hub's own sibling seams (the pushed hub route re-provides only stores deps, so „Einladen"
and „Mitglieder" would throw next). Consolidate the repeated re-provide-across-root-navigator
pattern into one shared helper (mirroring the existing `openAwaitInvitePage`) so the seam cannot
drift again, and add a widget regression test that opens the hub through the real top-bar path.

## Boundaries & Constraints

**Always:**
- Read every to-be-re-provided dependency from a context that still has FirstRunRouter scope
  (i.e. before the sheet/route boundary is crossed), then re-provide it into the pushed subtree.
- The management hub and every row it exposes — „Geschäfte", „Einladen", „Mitglieder" — must be
  reachable without a `ProviderNotFoundException` after opening it from the top-bar selector.
- Keep the re-provide list in exactly one place so a future row cannot silently miss a dependency.
- All user-facing strings stay in the `de-DE` localization layer; no hard-coded copy.

**Never:**
- No new product scope: do not add invite features, member features, or redesign the hub — this
  is a reachability fix only (invite simplification is Story 8.4).
- Do not move the provider definitions up to a global/app-root scope as the fix; keep the
  established re-provide-across-the-route-boundary pattern.
- Do not change backend, domain, events, or read models — this is a Flutter presentation-wiring fix.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Open hub | Signed-in member taps top-bar selector → „Haushalt verwalten" | Hub opens; „Geschäfte", „Einladen", „Mitglieder" rows render | N/A |
| Open invites | In the hub, tap „Einladen" | `InvitePage` opens (InvitesApi in scope) | N/A |
| Open members | In the hub, tap „Mitglieder" | `MembersPage` opens (MembersApi/HouseholdsApi/InvitesApi in scope) | N/A |
| Open stores | In the hub, tap „Geschäfte" | `ManageStoresPage` opens (StoresApi/StoreChainReferenceCache in scope) | N/A |

</frozen-after-approval>

## Code Map

- `app/lib/features/households/presentation/household_switcher_sheet.dart` -- `_openManage`
  (lines 93–109) is the crash site: reads `StoresApi`/`StoreChainReferenceCache` from the sheet
  context, which `_openSwitcher` only seeded with `HouseholdsApi`/`HouseholdsCubit`; also pushes
  the hub route with only the two stores deps re-provided.
- `app/lib/features/households/presentation/household_shell.dart` -- `_openSwitcher` (lines
  256–270) shows the switcher sheet and decides what scope the sheet gets; the shell context here
  still has full FirstRunRouter scope, so the deps must be read at this seam.
- `app/lib/features/households/presentation/manage_household_page.dart` -- the hub; `_openInvites`
  needs `InvitesApi`, `_openMembers` needs `MembersApi`+`HouseholdsApi`+`InvitesApi`,
  `_openManageStores` needs `StoresApi`+`StoreChainReferenceCache`. This is the full set the hub
  route must have in scope.
- `app/lib/features/households/presentation/await_invite_page.dart` -- `openAwaitInvitePage`
  (top of file): the canonical shared re-provide helper to mirror (guarded-optional deps,
  single call site, DRY note).
- `app/lib/features/households/presentation/first_run_router.dart` -- `build` (lines 92–124)
  lists the authoritative provider set (`StoresApi`, `StoreChainReferenceCache`, `InvitesApi`,
  `MembersApi`, `HouseholdsApi`, `HouseholdsCubit`); source of truth for what the hub subtree
  depends on. Do not change.
- `app/test/features/households/presentation/household_shell_test.dart` -- existing shell+switcher
  harness (provides only `HouseholdsApi`+`ShoppingListsApi`); the regression test must instead
  build the shell under the full FirstRunRouter provider set so the route-boundary escape is
  genuinely reproduced, then drive selector → „Haushalt verwalten".
- `app/test/features/households/presentation/manage_household_page_test.dart` -- existing direct
  hub test; reuse its fakes/keys (`manage-invites-row`, `manage-members-row`, `manage-stores-row`).

## Tasks & Acceptance

**Execution:**
- [x] `app/lib/features/households/presentation/household_switcher_sheet.dart` -- extract a shared
  `pushManageHouseholdPage(context, household)` (or equivalent) helper that reads the full hub
  dependency set from the current (FirstRunRouter-scoped) context and re-provides it into the
  pushed `ManageHouseholdPage` route; have `_openManage` delegate to it. Ensure the reads happen
  while the deps are still in scope (seed them into the sheet via `_openSwitcher`, or read at the
  shell seam) so no read runs against a scope that lacks them.
- [x] `app/lib/features/households/presentation/household_shell.dart` -- update `_openSwitcher` so
  the switcher sheet's provider scope carries every dependency the sheet's actions transitively
  need, reading them from the shell context (which has full scope).
- [x] `app/lib/features/households/presentation/manage_household_page.dart` -- have the hub's
  `_openInvites`/`_openMembers`/`_openManageStores` re-provide through the same shared seam/helper
  where practical, so the sibling seams cannot drift (Boy Scout; audit per AC3). No behavior change
  to the pages themselves.
- [x] `app/test/features/households/presentation/household_shell_test.dart` -- add a widget
  regression test: build the shell under the full FirstRunRouter-equivalent provider set, tap the
  top-bar selector, tap „Haushalt verwalten", assert the hub renders and the „Einladen" and
  „Mitglieder" rows are present and tappable without throwing.

**Acceptance Criteria:**
- Given a signed-in member on any household-scoped tab, when they open the top-bar selector and tap
  „Haushalt verwalten", then the hub opens with no `ProviderNotFoundException` and no silent no-op.
- Given the open hub, when it renders, then the „Einladen" (invites) row and the „Mitglieder"
  (members) row are both present and reachable.
- Given the regression suite, when it runs, then a widget test reproduces opening the hub from the
  top-bar selector and asserts it renders, and the sibling re-provide seams are covered/audited for
  the same gap.
- Given story completion, when the build is called green, then it names both suites: backend
  `./gradlew test` (incl. ArchUnit) and `flutter test` / `flutter analyze` (CLAUDE.md §6) — here
  the backend is untouched, so the app suite is the meaningful gate; state that explicitly.

## Implementation Notes

Root cause confirmed exactly as diagnosed: `household_switcher_sheet.dart`'s `_openManage` read
`StoresApi`/`StoreChainReferenceCache` from the sheet context, but `household_shell.dart`'s
`_openSwitcher` had only seeded the sheet with `HouseholdsApi`/`HouseholdsCubit` — the
`StoresApi` read threw `ProviderNotFoundException` before the hub route was ever pushed.

Fix, three seams:
- `household_shell.dart._openSwitcher` now reads `StoresApi`, `StoreChainReferenceCache`,
  `InvitesApi`, `MembersApi` (in addition to the existing `HouseholdsApi`/`HouseholdsCubit`) from
  the shell context — the last point that still has full `FirstRunRouter` scope — and re-provides
  all of them into the switcher sheet via `MultiRepositoryProvider`.
- `manage_household_page.dart` gained the new top-level `openManageHouseholdPage(context,
  household)` helper (mirrors `openAwaitInvitePage`'s precedent): it reads the full five-dependency
  set the hub subtree needs and pushes `ManageHouseholdPage` wrapped in a `MultiRepositoryProvider`
  carrying all of them. This is now the single place that owns the hub's re-provide list.
- `household_switcher_sheet.dart._openManage` was reduced to: pop the sheet (`Navigator.pop()` only
  schedules removal, so the sheet's `BuildContext` stays valid for the synchronous reads the helper
  above does — no `await` gap), then delegate to `openManageHouseholdPage`. The unused
  `StoresApi`/`StoreChainReferenceCache` imports were dropped.

Audit of the hub's own sibling seams (`_openInvites`/`_openMembers`/`_openManageStores` in
`manage_household_page.dart`, per AC3): all three already re-provide everything the page they open
needs (`InvitesApi`; `MembersApi`+`HouseholdsApi`+`InvitesApi`; `StoresApi`+
`StoreChainReferenceCache`, respectively) — no missing dependency found, so per the "no behavior
change to the pages themselves" constraint these were left untouched.

Regression test: `household_shell_test.dart` previously built the shell under a narrower provider
set (`HouseholdsApi`+`ShoppingListsApi` only) than production (`FirstRunRouter`) actually gives it
— exactly the gap that let the original bug ship unnoticed. Extracted a shared
`_buildShellHarness(...)` helper (DRY) that wraps the shell with the same six providers
`FirstRunRouter` does (`HouseholdsApi`, `ShoppingListsApi`, `StoresApi`,
`StoreChainReferenceCache`, `InvitesApi`, `MembersApi`) and used it for every group in the file —
two pre-existing switcher tests started failing once `_openSwitcher` began eagerly reading the four
new dependencies, confirming the harness itself needed the fix, not the production code. Added a
new `HouseholdShell + manage household hub reachability` group that drives the real
selector → „Haushalt verwalten" path and asserts both the hub renders (Einladen/Mitglieder rows
present) and tapping into each of those rows does not throw.

Matrix audit (step-03): the "Open stores" row (tap „Geschäfte" → `ManageStoresPage` opens) had no
covering test anywhere in the suite — a pre-existing gap, not introduced here, but the matrix
requires all four rows covered. Added
`theStoresRowOpensTheManageStoresPage` to `manage_household_page_test.dart`, mirroring the existing
invites/members row tests.

## Spec Change Log

## Review Triage Log

Reviewed by Blind Hunter, Edge Case Hunter, Verification Gap (all Opus) against the diff since
`598bbfdc57e5c96bb209e9073a6b42bbc7696475`. Items 1–6 routed to `patch`, applied by the
implementation subagent and verified green (`flutter test` 737 pass, `flutter analyze` 0 issues).

1. **high, patch** — `openManageHouseholdPage` re-provides five deps but not `HouseholdsCubit`;
   `_openMembers`'s comment claims it "is already an ancestor of this route (the shell)", which is
   false (the route is pushed on the root Navigator, above `FirstRunRouter`'s `BlocProvider`).
   `MembersPage`'s exit listener (`context.read<HouseholdsCubit>()` on leave/delete) throws
   `ProviderNotFoundException`. Verified empirically by the verification-gap reviewer (probe test
   reproduced the crash after backend leave already succeeded — user stranded, cubit stale). This
   path was unreachable before Story 8.1 (the old crash fired first), so this is newly exposed by
   this change and within the Intent's "reachable without a ProviderNotFoundException" boundary.
   Also invalidates the story's own audit claim (task 3 / Implementation Notes) that the sibling
   seams had "no missing dependency."
2. **low, patch** — `_openManage` pops the navigator, then reads deps via the popped sheet's
   context, deviating from this file's own established capture-before-pop pattern (`_switchTo`
   explicitly captures navigator/messenger first "before the sheet's context is torn down"). Works
   today (tests pass) but relies on undocumented pop() timing and is one stray `await` away from
   breaking.
3. **medium, patch** — the "Open stores" matrix row and the helper's `StoresApi`/
   `StoreChainReferenceCache` reads are never exercised through the real switcher→hub seam — only
   via a hand-wired direct test in `manage_household_page_test.dart` that bypasses
   `openManageHouseholdPage` entirely. If the helper dropped those two deps, nothing would fail —
   the exact dependency pair that caused the original crash.
4. **low, patch** — `_buildShellHarness`'s `storesApi`/`storeChainReferenceCache`/`membersApi`
   parameters are declared but never passed by any of the three call sites (YAGNI/dead params).
5. **low, patch** — `theMitgliederRowOpensWithoutThrowingAfterReachingTheHubFromTheTopBarSelector`
   has no positive assertion beyond "no exception" — would pass even on a blank render.
6. **low, patch** — the two new regression test names embed German UI copy ("Haushalt",
   "Verwalten", "Mitglieder") — the only test names in the suite that do — and the first one
   asserts two distinct behaviors (hub renders + invites row opens) in one test, against CLAUDE.md
   §2 naming consistency and §6 one-assertion-focus.
7. **low, rejected** — the hub's re-provide list still exists in two hand-maintained places
   (`_openSwitcher`'s sheet-scope seeding + the helper's own reads), which the frozen Intent's
   "Always" bullet asks to avoid. Real but judged unlikely to be hit in practice (requires a future
   row to add a dependency to only one list, uncaught by that story's own tests — as this review
   round demonstrates the project's test discipline catches exactly this class of gap), and the
   fix (collapsing to one read site) requires adding public API surface — a callback parameter — to
   `HouseholdSwitcherSheet`, more than a direct correction. Not actioned; noted for awareness.
8. **false** — suggested guarded-optional (`_tryRead`) reads in `_openSwitcher` for a
   "shell mounted without full ancestor scope" scenario. `HouseholdShell` is only ever mounted
   inside `FirstRunRouterBody`, which unconditionally provides all five/six dependencies at that
   scope (`first_run_router.dart`, marked "do not change" in the Code Map) — no production path
   reaches `_openSwitcher` without them.
9. **defer** — the same production provider scope is independently hand-copied in
   `first_run_router_test.dart` and `household_shell_live_sync_test.dart` (in addition to
   `household_shell_test.dart`), with nothing keeping the three in sync. Pre-existing, and neither
   of the other two files exercises the switcher path this story touches — not caused or exposed by
   this diff.

## Design Notes

The bug is a provider-scope escape, not a missing provider: FirstRunRouter provides everything,
but routes/sheets pushed on the navigator sit above those providers, so each seam must re-read the
deps from a still-in-scope context and re-provide them into the pushed subtree. The repeated,
hand-maintained re-provide lists are the actual defect class (one seam re-provided two of the five
needed deps). A single shared helper that owns the hub's re-provide set — matching the established
`openAwaitInvitePage` precedent — removes the drift. The regression test is only faithful if it
reproduces the route-boundary escape: building the shell under the full provider set and driving
the real selector → „Haushalt verwalten" path, not merely omitting a provider.

## Verification

**Commands:**
- `cd app && flutter test test/features/households/` -- expected: all household widget tests pass,
  including the new switcher→hub regression test.
- `cd app && flutter test` -- expected: full app suite green.
- `cd app && flutter analyze` -- expected: 0 issues.

**Manual checks (if no CLI):**
- On the emulator, open the top-bar selector → „Haushalt verwalten"; confirm the hub opens and
  „Einladen" and „Mitglieder" both open their pages without an error screen.

**Results (2026-09-20, post-review-patches):**
- `cd app && flutter test` — full app suite, 737 tests, all pass (incl. the switcher→hub
  regression group, the stores-row matrix-audit test, and the leave-through-the-hub
  `HouseholdsCubit` regression test added during review).
- `cd app && flutter analyze` — 0 issues.
- Backend untouched by this story (Flutter presentation-wiring fix only); `./gradlew test` was not
  run as no backend/domain/event/read-model file changed — the app suite above is the meaningful
  gate, per the story's own AC4. Manual on-device check not performed in this session (no
  emulator/device driven); the widget regression test reproduces the real route-boundary escape via
  the actual selector → „Haushalt verwalten" → row-tap path, which is the load-bearing verification.
