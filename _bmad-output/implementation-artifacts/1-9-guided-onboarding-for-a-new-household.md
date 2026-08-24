---
baseline_commit: a888088
---

# Story 1.9: Guided onboarding for a new household

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a non-expert person setting up a household,
I want a gentle step-by-step setup,
so that I can get started alone without fear of breaking anything.

## Acceptance Criteria

**AC1 — The guided create → name → stores → invite wizard**
**Given** a person with zero households who chose **„Haushalt erstellen"** on the first-run choice
**When** onboarding runs
**Then** it walks a **one-step-at-a-time wizard** — **name** the household (with a „Den Namen kannst du später jederzeit ändern." reassurance) → **add stores** (advisory client-side chain suggestion, skippable) → **optional invite entry** — with a **visible progress indicator** („Schritt X von 3"), plain-language German, and a **back** affordance on each step (UX-DR10). Household **name** creation reuses the Story 1.6 `CreateHousehold` path; **store** creation reuses the Story 1.8 add-store path (AC4 of 1.8: the store-creation path is reusable, not screen-bound) — **no new backend, no duplicated create/add logic** (DRY).

**AC2 — Every optional step is skippable and lands in a fully usable app**
**Given** any optional step (stores, invite)
**When** the person skips it („Überspringen — später hinzufügen" / „Später einladen — fertig")
**Then** they still land in a **fully usable app** — solo works, and stores/invite can be added later from „Haushalt verwalten" (Story 1.8) — with no invite-nag and no lesser-mode treatment for solo (UX-DR13 „degrade to quiet for solo"). Landing in the app routes into the **just-created household** (read-your-writes on the returned `householdId`, exactly as the minimal 1.6 create path does).

**AC3 — Privacy stated up front, no account/marketing pressure**
**Given** onboarding
**When** it is shown
**Then** **privacy is stated up front** („Deine Daten bleiben bei dir." on the welcome/choice; „Nur du und Eingeladene sehen euren Haushalt." on the invite step) and **no account-creation or marketing pressure** is applied (the person is already authenticated via Keycloak — onboarding asks for nothing beyond a household name; stores and invite are optional).

**AC4 — Invite step is present but its send is deferred to Epic 4** *(scope guard — LOCKED as Option A, Clarification 1)*
**Given** the invite step (frame 4 of `screen-onboarding.html`)
**When** it is shown in **this** story
**Then** the wizard shows a **visible „Schritt 3 von 3" invite step** with the email field + a **„Einladung senden" affordance that is disabled / marked „folgt später"** (reusing the shipped await-invite deferral tone), and the **only functional action is „Später einladen — fertig"** → into the app (solo is first-class). The send is **not wired to a backend** — invite creation (`MemberInvited` + HMAC email + side-store + Keycloak delivery) is **Story 4.1** (Epic 4), which owns the invite mechanism and will wire the send in place of this disabled affordance. **Do not** build any invite backend, event, endpoint, or email handling in Story 1.9.

## Tasks / Subtasks

> **Frontend-only story.** No backend, no new events/commands/endpoints, no migrations. All work is in `app/` and reuses the create-household (1.6) and add-store (1.8) paths already shipped. Confirm this scope against Clarification 1 before starting.

### Flutter — `onboarding` wizard wrapping the existing create + add-store paths (AC1, AC2, AC3)

- [x] **Task 1 — Wizard shell + step model** (AC1, AC2, AC3)
  - [x] Add a `features/onboarding/` feature (mirror the `features/households` / `features/stores` folder shape: `presentation/`, and `domain/` only if a pure step-enum/value type is warranted). Build an `OnboardingWizardPage` (the multi-step container) that owns the current step and the **created `HouseholdSummary`** once the name step completes. Steps: **1 · Name**, **2 · Geschäfte**, **3 · Einladen** — the welcome/choice (frame 1) stays the existing `CreateOrAwaitChoicePage` (see Task 4).
  - [x] Render the **stepper**: a „Schritt X von 3" label + a progress track (33 % / 66 % / 100 %) and a **back** control, matching `screen-onboarding.html` frames 2–4. Reuse `SgartShapes`/theme tokens and existing widgets (`SgartButton`, `SgartAppBar` where it fits); **no ad-hoc styling** (DESIGN system, Story 1.2). Honor `prefers-reduced-motion` if any animation is added (mockup already guards the caret).
  - [x] **Household creation timing:** the household is created when advancing from **step 1 (name) → step 2 (stores)**, because the stores step needs a `householdId` (`StoresCubit` requires it; Story 1.8). Reuse `CreateHouseholdCubit.submit(name)`; on `CreateHouseholdStatus.success`, capture `state.household` (the returned `HouseholdSummary`) into the wizard and advance — **do not** route into the app yet (unlike the minimal 1.6 page, which pops straight in). Surface `household.nameRequired` / `household.nameTooLong` via `localizedMessageForErrorCode` exactly as `CreateHouseholdPage` does.
  - [x] **Back after creation is a no-op on the write side** (Epic 1 has no delete-household — that is Epic 4 governance). Keep it KISS: once created, „Zurück" from steps 2/3 navigates **within** the wizard (name step may show the created name read-only or simply be revisitable without re-creating); it never deletes or re-creates the household, and skipping any remaining step still lands the person in the created household. Document this one-way-creation choice in Dev Notes/Completion Notes (Clarification 2).

- [x] **Task 2 — Stores step reusing `StoresCubit` (AC1, AC2)** *(no duplicated add logic)*
  - [x] Mount the **existing** `StoresCubit` (Story 1.8) for step 2, constructed with the created household's `householdId` and the `StoresApi` / `StoreChainReferenceCache` already provided by `FirstRunRouter` (they sit in the provider tree — `context.read` them; re-provide over the pushed wizard route with `_pushOverProviders`-style value re-provisioning, the Story 1.6 `ProviderNotFoundException` lesson). Reuse the add-store row with the inline advisory chain suggestion (accept / **ändern** via `selectChain` / **löschen** via `clearSuggestion`), the added-store chips, and the archive/remove affordance — **the same building blocks `ManageStoresPage` uses**. Do **not** re-implement add/suggest/match logic (AC4 of 1.8 exists precisely so this step reuses the path; DRY).
  - [x] „Weiter" advances to step 3; „Überspringen — später hinzufügen" advances to step 3 without adding a store. Either way the added stores are already persisted by `StoresCubit.addStore` (no wizard-level batching needed).
  - [x] The stores step copy is onboarding-specific („Wo kauft ihr ein?" / „Später wird die Liste nach Geschäft sortiert.") — **new keys**, but the store-row/suggestion widgets and their existing store copy are reused where they already exist.

- [x] **Task 3 — Invite step (present, send deferred to Epic 4) (AC3, AC4)**
  - [x] Render step 3 („Möchtest du jemanden einladen?") with the privacy reassurance („Nur du und Eingeladene sehen euren Haushalt.") and the **„Später einladen — fertig"** action that finishes onboarding → into the app. Per **Clarification 1 (LOCKED: Option A)**: show the email field + „Einladung senden" **disabled / marked as coming in a later update** (mirror the shipped `householdsAwaitInviteBody` „Diese Funktion folgt in einem späteren Update." pattern) — **do not** wire a send. The wizard is a **3-step** flow.
  - [x] Finishing (skip/done) calls `HouseholdsCubit.selectHousehold(createdHousehold)` and pops to the first-run router root — the **same** landing transition `CreateHouseholdPage` performs today — so the person lands in the created household's shell (Story 1.7).

- [x] **Task 4 — Wire the wizard into the first-run choice (AC1)** *(replace the minimal create path)*
  - [x] In `CreateOrAwaitChoicePage._openCreateHousehold`, push the **`OnboardingWizardPage`** instead of the minimal `CreateHouseholdPage`. Frame 1 of the mockup (welcome + „Haushalt erstellen" / „Auf Einladung warten" + „Deine Daten bleiben bei dir.") **is** this choice page — extend its copy/privacy line to match frame 1 (AC3) rather than adding a redundant welcome screen. The „Auf Einladung warten" branch (`AwaitInvitePage`) is **unchanged**.
  - [x] Decide the fate of the now-unused minimal `CreateHouseholdPage`: if nothing else references it, **remove it** (Boy-Scout — no dead code) and update the choice page + any test that pushed it; if kept as a fallback, justify why. Note the decision in Completion Notes. (`CreateHouseholdCubit`/`CreateHouseholdState` are **kept** — the wizard's name step reuses the cubit.)
  - [x] Provide the wizard subtree with the providers it reads (`HouseholdsApi`, `HouseholdsCubit`, `StoresApi`, `StoreChainReferenceCache`) via value re-provisioning over the pushed route (the established pattern).

### Flutter — localization + copy (AC1, AC2, AC3, AC4)

- [x] **Task 5 — German copy (AC1, AC2, AC3, AC4)**
  - [x] Add German ARB keys (with `@`-descriptions, English keys / German values — language policy) under an `onboarding*` prefix for: the stepper label („Schritt {current} von {total}" — an ICU/parameterized message), the name-step title/help/reassurance, the stores-step title/help + skip label, the invite-step title/help + privacy line + „Später einladen — fertig" + (if kept) the disabled-send „folgt später" note, the welcome/choice privacy line („Deine Daten bleiben bei dir."), and the back label (reuse `householdsBackButtonLabel` if it fits). Regenerate `app_localizations*.dart` (`flutter gen-l10n`). **No hard-coded user-facing strings** (SGART stays a hard-coded proper noun). Reuse existing `households*` and `stores*` keys wherever the copy already exists — do not duplicate strings (DRY).

### Testing (TDD — write the failing test first; CLAUDE.md §6)

- [x] **Task 6 — Widget/cubit tests (stub the HTTP boundary — no real network)**
  - [x] Wizard flow widget test (reuse `test/support/widget_test_harness.dart` + fake `HouseholdsApi`/`StoresApi`, mirror `create_household_page_test`/`manage_stores_page_test`): name → create advances to the stores step **without** landing in the app; add-a-store shows the advisory suggestion and adds; „Überspringen" advances without adding; finishing on the invite step lands in the created household (asserts `selectHousehold` / route-to-shell). The progress label reads „Schritt 1/2/3 von 3" across steps.
  - [x] Skip-path test (AC2): create → skip stores → skip/finish invite lands in a usable shell for the created household (solo).
  - [x] Name-step error test: a `household.nameRequired` / `household.nameTooLong` `{code}` maps to the localized copy and does **not** advance (reuse the `CreateHouseholdCubit` failure path).
  - [x] Choice-page test update: „Haushalt erstellen" now opens `OnboardingWizardPage` (update the existing `create_or_await_choice_page` test expectation; if `CreateHouseholdPage` is removed, delete/adjust its test).
  - [x] Privacy-copy test (AC3): the welcome/choice shows „Deine Daten bleiben bei dir."; the invite step shows „Nur du und Eingeladene sehen euren Haushalt."; no invite-nag on the solo landing.
  - [x] Invite-deferral test (AC4): the „Einladung senden" send is not invocable (disabled / no API call) — or, under Clarification 1 option B, the wizard has exactly 2 steps and no invite step renders.
  - [x] Run the full suite for real: `export PATH="$PATH:/home/timo/tools/flutter/bin"; cd app && flutter analyze && flutter test` — zero analyzer issues, all green (backend suite is untouched but must stay green: `cd backend && ./gradlew test`). A red build blocks merge (memory `flutter-test-local`: 1.2 was marked done on a review that never ran tests).

### Review Findings

*Code review 2026-08-24 (bmad-code-review, 3 parallel layers: Blind Hunter, Edge Case Hunter, Acceptance Auditor, all Opus 4.8). AC1–AC4 and Clarifications 1,3,4 all pass. Findings below.*

- [x] [Review][Patch] DRY — extract a shared create-household name form (resolved from decision, 2026-08-24: extract) [app/lib/features/households/presentation/create_household_page.dart:59-92] — `_NameStep` (onboarding_wizard_page.dart:178-242) rebuilds the same name `TextField` + inline `localizedMessageForErrorCode` error block + isSubmitting-guarded submit that `_CreateHouseholdView` already has. Both drive `CreateHouseholdCubit`, so the *logic* is reused, but the *form widget* is duplicated — while this same story extracted `StoresManagementView` so the stores form is shared by both hosts. Fix: extract a shared create-household form widget (mirror the StoresManagementView refactor) embedded by both `CreateHouseholdPage` and the wizard's name step (CLAUDE.md §1 DRY).
- [x] [Review][Patch] Backing out of the wizard after the household is created strands it — the person is bounced back to the "create or await" choice while the household exists on the backend [app/lib/features/onboarding/presentation/onboarding_wizard_page.dart:105] — `_onHouseholdCreated` only sets local wizard state; `HouseholdsCubit.selectHousehold` runs *only* in `_finish()` (invite step). Any route-pop after creation strands it: the name-step back button once you have stepped back from stores (`onBack: () => Navigator.of(context).pop()`), or the Android hardware back gesture on the stores/invite steps. The route pops to `CreateOrAwaitChoicePage`, which `HouseholdsCubit` still renders because it stays in `needsChoice`; re-tapping "create" mints a fresh cubit/command-id and can create a duplicate household. Violates Clarification 2 ("any exit lands the person in the created household") and AC2. Untested. Fix: intercept the pop (`PopScope`) so that when `_createdHousehold != null` an exit calls `_finish()` instead of returning to the choice page; a pop at the name step with nothing created still returns to choice.
- [x] [Review][Patch] Editing the name after stepping back diverges the entered household name from the persisted one [app/lib/features/onboarding/presentation/onboarding_wizard_page.dart:234] — create "A" → back to name → edit to "B" → Next: the reused single command-id makes the backend converge on "A" (no duplicate), but `submit` builds `success(HouseholdSummary(id, name: "B"))` locally, so the person lands in and sees "B" until the next launch re-fetches "A". Read-your-writes divergence (self-heals on restart, mitigated by the "change name later" copy). Fix: after creation, advancing from the name step should reuse the captured `_createdHousehold` without re-submitting a changed name (the spec's sanctioned "name step read-only after creation" option).
- [x] [Review][Patch] `_totalOnboardingSteps` hand-duplicates the step enum's cardinality [app/lib/features/onboarding/presentation/onboarding_wizard_page.dart:24] — `const int _totalOnboardingSteps = 3` must stay in sync with `enum _OnboardingStep { name, stores, invite }`; adding/removing a step silently breaks the "Schritt X von 3" label and the progress bar. Fix: derive from `_OnboardingStep.values.length` (single source of truth, DRY).
- [x] [Review][Patch] Tests assert on `state.status.name` string literals instead of the `HouseholdsStatus` enum [app/test/features/onboarding/presentation/onboarding_wizard_page_test.dart:68] — `expect(householdsCubit.state.status.name, 'shell')` / `isNot('shell')` (lines 68, 94, 147, 168) compare a stringified enum to a magic string, bypassing compile-time safety (a rename leaves them green against a nonexistent state). Fix: compare against `HouseholdsStatus.shell` (CLAUDE.md §6 — assert through domain types).
- [x] [Review][Patch] Choice-page privacy test asserts key presence, not the German copy [app/test/features/households/presentation/create_or_await_choice_page_test.dart] — the privacy-up-front test (AC3) checks only that the `onboarding-choice-privacy` key is present, not the actual „Deine Daten bleiben bei dir." string Task 6 intended to assert. Low — the ARB value is correct and wired. Fix: assert the localized text.
- [x] [Review][Defer] No client-side blank/whitespace/length guard on the name step [app/lib/features/onboarding/presentation/onboarding_wizard_page.dart:234] — deferred, pre-existing: a blank/whitespace name makes a pointless backend round-trip (rejected inline as `household.nameRequired`), and there is no `maxLength`. The 1.6 `CreateHouseholdPage` (create_household_page.dart:85-87) has the identical non-guarding behavior, so 1.9 mirrors the existing pattern rather than regressing it. Fix-fast would add a guard to both.

*Dismissed as noise:* (1) the stores step re-bootstraps (a redundant stores refetch) when re-entered via invite→back→stores — harmless (added stores persist and reload) and the per-step `StoresCubit` ownership is the KISS design; (2) no explicit "the typed invite email is never passed to an API" assertion — the email field is controller-less so the value is unreachable by construction, and `theInviteStepIsPresentButItsSendIsDeferredAndStatesPrivacy` already proves the send is inert.

## Dev Notes

Story 1.9 is a **pure-frontend wizard** that re-organizes flows already shipped end-to-end. **It adds no backend.** The whole value is UX: turn the minimal 1.6 create screen + the 1.8 store management into a gentle, guided, skippable, privacy-first onboarding for a non-expert older Admin (Werner) with no power-user to help. **Reuse the existing paths; do not reinvent create or add-store.**

### The exact things to reuse (reuse, don't reinvent)

- **Name step = the 1.6 create path.** `CreateHouseholdCubit.submit(name)` (one command-id per intent; `isClosed`-guarded emits) → `CreateHouseholdState.success(HouseholdSummary)`. The wizard captures that summary and advances instead of popping into the app. Reuse `localizedMessageForErrorCode` for `household.nameRequired`/`household.nameTooLong`. [Source: app/lib/features/households/presentation/create_household_cubit.dart, create_household_page.dart]
- **Stores step = the 1.8 add-store path.** Mount `StoresCubit(storesApi, referenceCache, householdId)` for the created household; reuse its `onNameChanged`/`suggestFor`, `selectChain` (ändern), `clearSuggestion` (löschen), `addStore`, chips, and archive — the same widgets `ManageStoresPage` composes. **AC4 of Story 1.8 was written so this step reuses the creation path** („store creation is not limited to Haushalt verwalten"). Provide `StoresApi`/`StoreChainReferenceCache` — already provided in `FirstRunRouter`. [Source: app/lib/features/stores/presentation/stores_cubit.dart, manage_stores_page.dart; 1-8 story AC4]
- **Landing transition = the 1.6 pattern.** Finish → `HouseholdsCubit.selectHousehold(created)` → pop to first-run router root → the created household's shell (Story 1.7). [Source: app/lib/features/households/presentation/create_household_page.dart:51-54, households_cubit.dart]
- **Provider re-provisioning over pushed routes** = the `CreateOrAwaitChoicePage._openCreateHousehold` / `HouseholdSwitcherSheet._pushOverProviders` pattern (re-provide `HouseholdsApi`, `HouseholdsCubit`, `StoresApi`, `StoreChainReferenceCache` by value) — the Story 1.6 `ProviderNotFoundException` lesson. [Source: app/lib/features/households/presentation/create_or_await_choice_page.dart:54-72]
- **UI kit + tokens** (`SgartButton`, `SgartAppBar`, `SgartShapes`, theme) — Story 1.2 design system; the mockup's 52–54 px controls and text-only buttons already match `SgartButton`. [Source: app/lib/shared/widgets/*, app/lib/theme/**]
- **Informational-deferral copy pattern** for the invite step: the shipped `householdsAwaitInviteBody` („… Diese Funktion folgt in einem späteren Update.") is the exact tone for „invite send comes in Epic 4". [Source: app/lib/l10n/app_de.arb]

### Architecture guardrails (must follow)

- **No backend change, so no ArchUnit/CQRS/ES surface is touched.** Keep `flutter analyze` clean and the BLoC-per-screen + `isClosed`-guard conventions (from `AuthCubit`/`HouseholdsCubit`/`StoresCubit`) intact.
- **DRY across the create/add paths (the single biggest failure mode here):** the wizard must **compose** `CreateHouseholdCubit` and `StoresCubit`, not fork them. A second add-store implementation, a second command-id scheme, or a second create call would violate AC4-of-1.8's reuse intent and Clean Code DRY.
- **DSGVO / privacy (AC3):** onboarding collects only a household **name** (not personal data — it names a household, not a person) and, in the invite step, an email that in **this** story is **not sent, stored, or transmitted anywhere** (send is Epic 4). Do not persist or log the typed email. No PII enters any store in Story 1.9. Privacy is stated up front (welcome + invite step). [Source: CLAUDE.md §5; 1-6 AC1 „household name is not personal data"]
- **Solo is first-class (UX-DR13):** no invite-nag, no "complete your setup" pressure on the solo landing; skipping stores and invite is a full, quiet success (Jonas persona).

### Scope — what is and isn't in 1.9

- **In:** the guided wizard (name → stores → invite) with a progress indicator, reassurance, back, privacy-up-front, and skip options, wired from the first-run „Haushalt erstellen" choice; onboarding German copy; full widget/cubit test coverage; Boy-Scout removal of the now-superseded minimal create page (if unreferenced).
- **Reuse-only (already built):** create-household backend + `CreateHouseholdCubit` (1.6); add-store backend + `StoresCubit` + chain suggestion/matcher/cache (1.8); the first-run routing 0/1/≥2 + choice/await pages + shell (1.6/1.7).
- **Out (do not build):** **any invite backend/mechanism** — `MemberInvited`, HMAC email, side-store, Keycloak delivery, the invite endpoint — all **Story 4.1 (Epic 4)** (AC4, Clarification 1). Household **delete/undo** (Epic 4 governance). The offline queue / conflict UX (Epic 5). Locale screen (1.10) and Profil (1.11). No `go_router` — the wizard is a simple pushed multi-step widget (YAGNI; nav shell is later).

### Previous-story intelligence (Stories 1.6–1.8 — done)

- **1.6 explicitly named this story:** "the guided onboarding wizard is Story 1.9" — 1.6 shipped the *minimal* create screen deliberately, leaving the wizard here. Its Clarification 6 LOCKED "the onboarding wizard is 1.9" as a scope boundary. This story cashes that in. [Source: 1-6 story, Clarification 6 + Task 6]
- **1.8 explicitly named this story:** its "Out (do not build)" lists "onboarding's store step (Story 1.9 reuses this creation path)", and AC4 built the add-store path as a reusable unit **for exactly this**. Reuse it; do not re-implement. [Source: 1-8 story, AC4 + Scope]
- **`ProviderNotFoundException` lesson (1.6):** pushed routes escape the `FirstRunRouter` providers; re-provide by value. The wizard is a pushed route → same care. [Source: 1-6 patterns; create_or_await_choice_page.dart]
- **Command-id-per-intent (1.6/1.8):** already handled inside `CreateHouseholdCubit`/`StoresCubit`. The wizard must not introduce a competing id scheme (the 1.8 review's HIGH finding was a spent-command-id bug — do not recreate that class of error).
- **Local test reality (memory `flutter-test-local`):** Flutter SDK at `/home/timo/tools/flutter/bin` (not on PATH). Backend after 1.8 is green; client after 1.8 was 173 tests green. Run both for real; keep them green. Git: solo, **direct-to-`main`** pre-beta (memory `git-workflow`); baseline `a888088`.

### Testing standards

- **Frontend test pyramid:** cubit/widget tests with the HTTP boundary stubbed (fake `HouseholdsApi`/`StoresApi`), mirroring `create_household_page_test`, `manage_stores_page_test`, `stores_cubit_test`, and the `fake_*_dependencies` harness. No new backend tests (no backend change).
- **Behavior, not structure:** full-sentence names, e.g. `namingTheHouseholdCreatesItAndAdvancesToTheStoresStepWithoutLandingInTheApp`, `skippingStoresAndInviteLandsSoloInTheCreatedHousehold`, `theWizardShowsAThreeStepProgressIndicator`, `theInviteStepDoesNotSendAnInviteInThisStory`, `privacyIsStatedOnTheWelcomeAndInviteSteps`.
- **DSGVO in tests:** synthetic data only (`rita@example.test`, fake UUIDs); assert the typed invite email is **not** passed to any API in Story 1.9 (the AC4 deferral guard).
- **Keep green:** update the `create_or_await_choice_page` test (create now opens the wizard) and, if removed, the minimal `CreateHouseholdPage` test. `flutter analyze` zero issues; a red build blocks merge (NFR6).

### Project Structure Notes

```text
app/lib/features/onboarding/
  presentation/onboarding_wizard_page.dart     # the multi-step container: owns step + created HouseholdSummary (new)
  presentation/onboarding_wizard_cubit.dart    # thin step/state controller IF a cubit is warranted (new, optional — see Clarification 3)
  presentation/onboarding_step_*.dart          # name / stores / invite step widgets (new)
  domain/onboarding_step.dart                  # step enum + progress fraction, pure (new, optional)
app/lib/features/households/presentation/
  create_or_await_choice_page.dart             # push OnboardingWizardPage instead of CreateHouseholdPage (modified)
  create_household_page.dart                   # remove if unreferenced (Boy-Scout) — cubit/state kept (modified/removed)
app/lib/l10n/app_de.arb                        # + onboarding* German copy (modified; regenerate gen/)
app/test/features/onboarding/…                 # wizard flow / skip / error / privacy / invite-deferral tests (new)
app/test/features/households/…                 # choice-page test update (modified)
```
- One widget/cubit per concern (SRP); no abbreviations. Reuse `CreateHouseholdCubit`, `StoresCubit`, `HouseholdsCubit`, and the shared widgets — **do not duplicate** create/add-store logic. The `features/onboarding` folder mirrors `features/households`/`features/stores`.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story-1.9] (lines 411–429) — user story + AC (create→name→stores→invite, skippable, privacy up front); UX-DR10 (line 127); UX-DR13 solo-quiet (line 132); FR1 (line 38); FR2/CAP-2 invite is Epic 4 (line 40).
- [Source: _bmad-output/planning-artifacts/ux-designs/ux-sgart-2026-08-20/.working/screen-onboarding.html] — the 4-frame wizard: welcome/choice (privacy „Deine Daten bleiben bei dir."), name (reassurance), stores (chain suggestion, „Überspringen"), invite (privacy, „Später einladen — fertig") + design-decisions legend.
- [Source: _bmad-output/planning-artifacts/ux-designs/ux-sgart-2026-08-20/EXPERIENCE.md] (lines 53–54, 92–95) — first-run routing + onboarding wizard Werner journey (J1).
- [Source: _bmad-output/implementation-artifacts/1-6-create-a-household-first-run-routing.md] — the create path this wizard reuses; Clarification 6 ("onboarding wizard is 1.9"); `ProviderNotFoundException` re-provisioning lesson; landing transition.
- [Source: _bmad-output/implementation-artifacts/1-8-manage-stores-with-client-side-chain-matching.md] — AC4 reusable store-creation path + `StoresCubit`/suggestion/matcher/cache the stores step mounts; "onboarding's store step (Story 1.9 reuses this creation path)".
- [Source: app/lib/features/households/presentation/*, app/lib/features/stores/presentation/*, app/lib/l10n/app_de.arb] — the concrete cubits, pages, providers, and copy keys to reuse.
- [Source: CLAUDE.md] — Clean Code (DRY/KISS/YAGNI/Boy-Scout), no-abbreviations naming, DSGVO, TDD.
- [Source: memory `flutter-test-local`, `git-workflow`, `language-policy`, `model-preferences`] — run tests locally for real; direct-to-`main` pre-beta; English keys/German values; Sonnet 5 for impl.

## Clarifications (LOCKED by Timo 2026-08-24)

1. **Invite step scope in Epic 1 — how to handle the wizard's frame 4 when invite-send is Epic 4.** — ✅ **LOCKED: Option A.** Keep the invite step **visible but non-sending** — render „Schritt 3 von 3" with the privacy line and a disabled/„folgt später" send (reusing the shipped await-invite deferral tone); only „Später einladen — fertig" is functional. Keeps the wizard faithful to UX-DR10's 3-step shape; Epic 4.1 later wires the send in place of the disabled affordance. *Alternative (rejected): drop the invite step entirely in 1.9* (a 2-step wizard, Epic 4.1 adds step 3) — simpler, but the onboarding shape would change between epics.

2. **Back after the household is already created (no delete in Epic 1).** — ✅ **LOCKED: one-way creation (recommended default).** The household is created on name→stores; „Zurück" thereafter navigates within the wizard but never deletes/re-creates, and any exit lands the person in the created household. Delete/undo is Epic 4 governance (YAGNI here). *Alternative (rejected):* forbid „Zurück" past the name step once created.

3. **Wizard state: a dedicated `OnboardingWizardCubit` vs. `StatefulWidget` step state.** — ✅ **LOCKED: minimal (recommended default).** A `StatefulWidget` holding `currentStep` + the created `HouseholdSummary`, composing `CreateHouseholdCubit` (name) and `StoresCubit` (stores). A thin `OnboardingWizardCubit` is acceptable **only if** the step logic + `bloc_test` coverage reads cleaner as a cubit — dev's call at implementation, noted in Completion Notes; avoid over-engineering (KISS).

4. **Fate of the minimal `CreateHouseholdPage` (1.6).** — ✅ **LOCKED: remove it (recommended default)** if the wizard is the sole create entry and nothing else references it (Boy-Scout, no dead code); keep `CreateHouseholdCubit`/`CreateHouseholdState` (the wizard's name step reuses the cubit). Confirm nothing else pushes the page before deleting.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Opus 4.8), via `bmad-dev-story`.

### Debug Log References

- Frontend full suite: `export PATH="$PATH:/home/timo/tools/flutter/bin"; cd app && flutter analyze && flutter test` → **186 tests green** (178 baseline + 8 net new), `flutter analyze` → no issues.
- No backend changes (frontend-only story); backend suite untouched. `git status` confirms only `app/` and `_bmad-output/` changed.

### Completion Notes List

Implemented Story 1.9 end-to-end as a **frontend-only** guided onboarding wizard, reusing the shipped create-household (1.6) and add-store (1.8) paths — no backend, no duplicated create/add logic (DRY).

**All 4 clarifications honored:**
1. **Invite step = Option A:** a visible „Schritt 3 von 3" invite step whose „Einladung senden" is disabled with a „folgt später" note (`onboarding-invite-deferred-note`); only „Später einladen — fertig" is functional. The email field is inert — no controller is wired to it, so the typed email is never stored, logged, or transmitted (there is no invite backend until Story 4.1).
2. **One-way creation:** the wizard keeps a **single** `CreateHouseholdCubit` for its whole lifetime, so stepping back to the name step and resubmitting reuses the same `commandId` → the backend's deterministic household id (Story 1.6) converges rather than creating a second household. Verified by `goingBackFromTheStoresStepReturnsToTheNameStepWithoutCreatingASecondHousehold` (two create calls, same command id).
3. **Minimal wizard state:** a `StatefulWidget` (`_OnboardingWizardView`) holds the current step + created `HouseholdSummary`, composing `CreateHouseholdCubit` (name) and a per-step `StoresCubit` (stores). No dedicated onboarding cubit — the step logic is trivial (KISS).
4. **`CreateHouseholdPage` kept, NOT removed** — Clarification 4's condition ("remove if nothing else references it") is **not met**: `HouseholdSwitcherSheet` (Story 1.7 „Neuen Haushalt erstellen") still uses the minimal page for a returning user creating an *additional* household. **Scope decision:** onboarding is the **first-run creator path only** (UX-DR10 „First-run / onboarding wizard", the Werner journey). The switcher's power-user create keeps the minimal page unchanged. Unifying the switcher-create into the wizard is a possible follow-up, not required by any 1.9 AC.

**Key design points:**
- **DRY store reuse:** extracted the store-management body from `ManageStoresPage` into a shared, public `StoresManagementView` (Boy-Scout) that both `ManageStoresPage` and the wizard's stores step embed over their own `StoresCubit`. All Story 1.8 widget keys were preserved, so the existing `manage_stores_page_test` stays green unchanged. This is the concrete realization of 1.8's AC4 ("the store-creation path is reusable, not screen-bound").
- **Household creation timing:** created on name→stores (the stores step needs a `householdId`); the wizard's `BlocListener` on `CreateHouseholdStatus.success` captures the returned `HouseholdSummary` and advances — it does **not** route into the app yet (unlike the minimal 1.6 page). Finishing (skip/done) calls `HouseholdsCubit.selectHousehold` + `popUntil(isFirst)` — the same landing transition the minimal page performs.
- **Provider re-provisioning:** the wizard is a pushed route above the `FirstRunRouter` providers, so `_openOnboarding` re-provides all four deps by value (`HouseholdsApi`/`HouseholdsCubit` + `StoresApi`/`StoreChainReferenceCache`) — the Story 1.6 `ProviderNotFoundException` lesson.
- **Privacy up front (AC3):** frame 1 (`CreateOrAwaitChoicePage`) now shows „Deine Daten bleiben bei dir." (`onboarding-choice-privacy`); the invite step shows „Nur du und Eingeladene sehen euren Haushalt." (`onboarding-invite-privacy`). No invite-nag on the solo landing (UX-DR13).
- **Design system:** stepper uses `LinearProgressIndicator` (determinate, no motion — reduced-motion safe) + `SgartButton`/`SgartAppBar`/`SgartShapes` tokens; no ad-hoc styling.

**Deferred (not built here):** the invite send + all invite backend (Epic 4, Story 4.1); household delete/undo (Epic 4 governance); unifying the switcher's „Neuen Haushalt erstellen" into the wizard (scope decision above).

### File List

**Frontend — new (lib):**
- `app/lib/features/onboarding/presentation/onboarding_wizard_page.dart`
- `app/lib/features/stores/presentation/stores_management_view.dart`

**Frontend — modified (lib):**
- `app/lib/features/stores/presentation/manage_stores_page.dart` (slimmed to delegate its body to the shared `StoresManagementView`)
- `app/lib/features/households/presentation/create_or_await_choice_page.dart` (launches `OnboardingWizardPage` re-providing the four deps; adds the up-front privacy line)
- `app/lib/l10n/app_de.arb` (+ regenerated `lib/l10n/gen/app_localizations*.dart`) — `onboarding*` German copy

**Frontend — new (test):**
- `app/test/features/onboarding/presentation/onboarding_wizard_page_test.dart`

**Frontend — modified (test):**
- `app/test/features/households/presentation/create_or_await_choice_page_test.dart` (create now launches the wizard; privacy-up-front assertion)

### Change Log

- 2026-08-24: Implemented Story 1.9 (guided onboarding for a new household) — a frontend-only 3-step wizard (name → stores → invite) reusing the create-household (1.6) and add-store (1.8) paths; extracted the shared `StoresManagementView` (DRY); invite send deferred to Epic 4 (Option A); privacy stated up front; German `onboarding*` copy. 186 Flutter tests green, `flutter analyze` clean. Status → review.
