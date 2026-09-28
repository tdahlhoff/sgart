---
title: 'Story 8.7: Hide the household selector on the Profile tab'
type: 'bugfix'
created: '2026-09-28'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
context: []
baseline_commit: '864fd4237c10fec0ef0d8c00772c34fccccaed17'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The persistent shell header shows the tappable household switcher chip on every tab, including Profil (manual test F4, 2026-09-20). Profil is personal-only (UX-DR6/UX-DR14), so a household-scoped control is out of place there.

**Approach:** Make the shell's app bar depend on the tab. On Listen and Einkauf it keeps the switcher chip. On Profil the title is the plain „Profil" landmark with no chip and no tap target.

**Decisions (Timo, 2026-09-28):**
- **D1 Nickname anchor:** the Profil nickname section label names the active household, e.g. „Dein Name in „WG Küche"" (a parameterized `profileNicknameSectionLabel`), replacing „Dein Name in diesem Haushalt". The nickname stays on Profil, so the 8.3 decision stands.

## Boundaries & Constraints

**Always:** The shell's `IndexedStack` stays unchanged, so tab state is preserved and no cubit is re-created on a tab change. The live-sync lifecycle is untouched. All strings are localized (reuse `shellTabProfileLabel`). The switcher sheet and „Haushalt verwalten" stay reachable from Listen/Einkauf.

**Never:** No change to the switcher sheet, household routing, or live sync. No per-tab `Scaffold`s (they would break the eager-built `IndexedStack`). No new household-management entry on Profil.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Listen tab | any household | header = switcher chip with the household name; tap opens the sheet | N/A |
| Einkauf tab | any household | same as Listen | N/A |
| Profil tab | any household | header = plain „Profil"; no `switcher-chip` key in the tree; nothing tappable | N/A |
| Tab round-trip | Listen → Profil → Listen | the chip returns; list state preserved | N/A |
| Household switch, then Profil | switch on Listen, open Profil | Profil shows the new household's nickname (8.3 reload unchanged) | N/A |

</frozen-after-approval>

## Code Map

- `app/lib/features/households/presentation/household_shell.dart:198-212` -- the single `Scaffold` + `SgartAppBar` for all three tabs; `_selectedTabIndex` (tab 2 = Profil) already lives in this `State`. Change only the `appBar` construction.
- `app/lib/shared/widgets/sgart_app_bar.dart` -- `onTitleTap == null` already renders a plain title, so no widget change is needed.
- `app/lib/features/settings/presentation/profile_screen.dart:~198` + `app/lib/l10n/app_de.arb:1332` (`profileNicknameSectionLabel`) -- the nickname section label (D1): turn it into a `{householdName}` placeholder string, fed from `widget.activeHousehold.name`.
- `app/test/features/households/presentation/household_shell_test.dart:~104-140` -- existing switcher-chip tests; extend them here.
- Sync-status indicator (`actions`): keep it on all tabs. It reports the app's connection state, which is useful everywhere, and the AC only removes the selector.

## Tasks & Acceptance

**Execution:**
- [x] `app/test/features/households/presentation/household_shell_test.dart` -- failing tests first (regression, F4): Profil tab → no `switcher-chip`, title „Profil"; Listen/Einkauf → chip present and tappable; Listen→Profil→Listen round-trip restores the chip.
- [x] `app/lib/features/households/presentation/household_shell.dart` -- build the `SgartAppBar` from `_selectedTabIndex`: on Profil, `title: localizations.shellTabProfileLabel` with no `onTitleTap`/`titleKey`; otherwise unchanged. Name the Profil index (no magic `2`).
- [x] `app/lib/l10n/app_de.arb` + `profile_screen.dart` -- D1: parameterize `profileNicknameSectionLabel` („Dein Name in „{householdName}"") and pass the active household's name. Add a profile_screen widget test that the label shows the household name and updates after a household switch.

**Acceptance Criteria:**
- Given the Profile tab, when it is shown, then the top-bar household selector is not shown.
- Given the Listen or Einkauf tab, when it is shown, then the selector still appears and opens the switcher sheet.

## Implementation Notes

- Implemented by Sonnet subagent 2026-09-28. `HouseholdShell._HouseholdShellState` builds the `SgartAppBar` from `_selectedTabIndex == _profileTabIndex` (named constant `_profileTabIndex = 2`, no magic number); the sync-status `actions` list is built once and passed to both branches so it stays on every tab per the code map. `SgartAppBar` itself needed no change (`onTitleTap == null` already renders a plain title).
- `profileNicknameSectionLabel` is now parameterized (`{householdName}`) in `app_de.arb`; `flutter gen-l10n` regenerated `lib/l10n/gen/app_localizations*.dart`. `profile_screen.dart` passes `widget.activeHousehold.name`.
- Test changes: replaced the now-obsolete `theSwitcherChipStaysVisibleOnEveryTab` (asserted the old, now-wrong, always-visible behavior) with three tests — chip present+tappable on Listen/Einkauf, chip absent + plain „Profil" title on Profil, and a Listen→Profil→Listen round-trip. Added `profile_screen_test.dart`'s `theSectionLabelNamesTheActiveHouseholdAndUpdatesAfterASwitch`.

## Spec Change Log

## Review Triage Log

Review 2026-09-28 (Opus; Edge Case Hunter + Verification Gap; Blind Hunter skipped for the thin slice).

| # | Finding | Verdict | Evidence / route |
|---|---------|---------|------------------|
| 1 | (EC) `_profileTabIndex = 2` could drift if tabs are reordered; suggests a `ShellTab` enum | low | Hypothetical, and the new shell tests tap `shell-tab-profile` by key and assert the chip is absent, so a reorder would turn them red. The enum refactor adds complexity → rejected. |
| 2 | (VG) The sync-status indicator on the Profil tab is untested; the separate Profil branch could drop it silently | low | Pre-verified gap → patch applied: `theProfilTabKeepsTheSyncStatusIndicator`. |

## Verification

**Commands:**
- `cd app && flutter test && flutter analyze` -- ran: 777 tests passed, 0 failed; analyze: no issues found.
- `cd backend && ./gradlew test` -- not run: no backend change (app-only story).

**Manual checks:**
- Not yet run on the emulator: switch between the three tabs; the chip only appears on Listen/Einkauf. (left to Timo/next session — no emulator available in this environment.)
