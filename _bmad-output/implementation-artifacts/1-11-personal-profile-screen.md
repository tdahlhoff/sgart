---
baseline_commit: 8ad78adf0e5ea952137cfb40c9e685cd2e09e4d8
---

# Story 1.11: Personal profile screen

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a member,
I want a personal profile screen,
so that I can control my own display settings and account.

## Acceptance Criteria

1. **The Profil tab is reachable and is personal-only — no household management on it.** (UX-DR14, EXPERIENCE §2)
   - The authenticated shell gains a **three-tab bottom navigation** — **„Listen" · „Einkauf" · „Profil"** — labelled icons (never icon-only, UX-DR5). **Listen** and **Einkauf** are **placeholder tabs** for this story (their features are Epic 2 / Epic 3); each renders a calm, plain-language German „coming later" placeholder. **Profil** is the live screen this story delivers.
   - The **persistent header stays on every tab**: the tappable household **switcher chip** (left) and the **sync-status placeholder** (right) — unchanged from Story 1.7. The household name is therefore still always in the header on the Profil tab (UX-DR9 safeguard); the Profil screen itself contains **no** household name, switcher, rename, stores, members, or invites.
2. **The Profil screen shows a personal identity header (display-only) and the three MVP sections.** (UX-DR14, AR4/AR5/AD-6)
   - **Identity header:** an avatar showing the person's initial, their **display name**, and their **email** — read live from the JWT/Keycloak-backed identity (`AuthState`, threaded from the existing `CallerIdentity`), **never persisted** (AD-6). No second identity call.
   - **„Sprache & Region" (Darstellung):** a tappable row that opens the existing `LocaleSettingsPage` (Story 1.10). This is that page's **first and only** user-facing entry point — the page was reachable by route only until now.
   - **„Benachrichtigungen":** an **info-only** section (no toggle, no backend) stating that the person is notified about invites, list changes, and shopping, that **content is never included** in a notification, and that these are **fixed in this version** (`NotificationSettingsUpdated` is reserved-not-built, arch-spine §Reserved; CAP-12).
   - **„Abmelden":** a sign-out action that calls `AuthCubit.signOut()` — the same teardown as today (clears tokens + on-device active household + per-user locale via the existing bridge), returning to the sign-in gate.
3. **The screen honors the accessibility overlay and does not add the deferred larger-display toggle.** (NFR10, UX-DR5, DESIGN §5)
   - Every interactive row/control meets the **48px minimum target** and the screen **honors OS Dynamic Type / text scaling** with reflow (no clipping); copy is plain-language German from the localization layer (no hard-coded strings, AR10).
   - The in-app **„Größere Darstellung"** larger-display toggle is **out of scope** (deferred to the first fast-follow, may be dropped — MVP larger text rests on OS Dynamic Type). Export/erasure („Meine Daten") is **out of scope** (Epic 6 / CAP-14) and is **not** shown on this screen in any form.

## Tasks / Subtasks

- [x] **Task 1 — Thread `email` through the auth state (identity header source)** (AC: #2)
  - [x] Add `email` to `AuthState.authenticated(...)` alongside the existing `displayName` and `keycloakUserId` — an `email` field set only while `status == authenticated`; include it in `==`/`hashCode` (mirror how Story 1.10 threaded `keycloakUserId`). Update the doc comment.
  - [x] In `AuthCubit._loadCallerIdentity`, pass `identity.email` into `AuthState.authenticated(...)`. `CallerIdentity` **already** carries `email` (`caller_identity.dart:11`) — **no** second identity call, **no** persistence (AD-6).
  - [x] Update the two existing call-site expectations: `auth_cubit_test.dart` (assert the authenticated state now carries the email) and any `AuthState.authenticated(...)` literal in tests (e.g. `auth_gate_body_test.dart` already builds `CallerIdentity(... email: ...)`, so only the emitted-state assertions change).
- [x] **Task 2 — String catalog: tabs, Profil sections, notifications info** (AC: #1, #2)
  - [x] Add keys to `app/lib/l10n/app_de.arb` (English keys, German values, each with `@`-metadata `description`, per the existing file style and the language policy). Reuse `authSignOutButtonLabel` („Abmelden") — **do not** add a new sign-out key. Suggested new keys:
    - `shellTabListsLabel` → „Listen"
    - `shellTabShoppingLabel` → „Einkauf"
    - `shellTabProfileLabel` → „Profil"
    - `shellTabListsPlaceholder` → a calm one-line „kommt in einer späteren Version" note for the Listen tab
    - `shellTabShoppingPlaceholder` → the same for the Einkauf tab
    - `profileDisplaySectionLabel` → „Darstellung"
    - `profileLocaleRowLabel` → „Sprache & Region"
    - `profileNotificationsSectionLabel` → „Benachrichtigungen"
    - `profileNotificationsInfo` → „Du bekommst Hinweise zu Einladungen, Listenänderungen und Einkäufen. Inhalte werden nie mitgeschickt. In dieser Version fest eingestellt." (from the mock)
    - `profileAccountSectionLabel` → „Konto" (heading over „Abmelden")
  - [x] Run `flutter gen-l10n` and confirm the getters appear on `AppLocalizations` (generated under `lib/l10n/gen/`, gitignored — do not commit it).
- [x] **Task 3 — Restructure `HouseholdShell` into a 3-tab scaffold** (AC: #1)
  - [x] Convert `HouseholdShell` to a `StatefulWidget` holding the selected tab index. Keep its existing `SgartAppBar` (switcher chip + sync-status placeholder, `_openSwitcher`) **exactly as the single, persistent `appBar`** across all tabs. Set `body` to an `IndexedStack(index: selectedIndex, children: [ListenPlaceholder, ShoppingPlaceholder, ProfileScreen])` and add a Material 3 `NavigationBar` as `bottomNavigationBar` with three `NavigationDestination`s (labelled icons, e.g. `Icons.list_alt` / `Icons.shopping_cart_outlined` / `Icons.person_outline`).
  - [x] Give each destination a stable `Key` and the `NavigationBar` selection wiring (`selectedIndex` + `onDestinationSelected` → `setState`). `NavigationBar` already guarantees ≥48px targets and shows labels — keep labels visible (`NavigationDestination.label`), never icon-only (UX-DR5).
  - [x] Add two tiny placeholder widgets (Listen, Einkauf) — a centered, plain-German „coming later" `Text` from the ARB. Keep them minimal (YAGNI); they are replaced by the real screens in Epics 2/3.
  - [x] **Retire `HouseholdHomePage`.** Its content (household name + sign-out) is now redundant — the name lives in the header switcher chip and sign-out moves to Profil. Delete the file and its import, or repurpose it as the Listen placeholder. The `current-household-name` and `sign-out-button` keys move as described in Task 6.
- [x] **Task 4 — `ProfileScreen` (personal-only)** (AC: #2, #3)
  - [x] Add `ProfileScreen` under `app/lib/features/settings/presentation/` (the personal-settings feature Story 1.10 established — a profile is personal, not a household concern, so it does **not** live under `features/households/`). It renders as a **tab body**, so it has **no `Scaffold`/`AppBar` of its own** (the shell owns the chrome). Use a `ListView`/`SafeArea` so it scrolls under Dynamic Type.
  - [x] **Identity header:** read `AuthState` (`context.watch<AuthCubit>().state` or a `BlocBuilder<AuthCubit, AuthState>`); render a `CircleAvatar` with the display name's first grapheme, the `displayName`, and the `email`. Guard against an empty display name (fallback avatar glyph). Display-only — never write these anywhere.
  - [x] **„Darstellung" → „Sprache & Region" row:** a `ListTile` (icon + `profileLocaleRowLabel` + trailing chevron, key `profile-locale-row`) that **pushes `LocaleSettingsPage`** on the navigator. `LocaleCubit` sits **above `MaterialApp`** (a global ancestor), so the pushed page reaches it with **no re-provide** needed (unlike `AuthCubit`/`HouseholdsCubit`). Import `LocaleSettingsPage` from the same feature.
  - [x] **„Benachrichtigungen" section:** a section label + the `profileNotificationsInfo` body text. **No toggle, no `Switch`, no backend call.**
  - [x] **„Abmelden":** reuse `SgartButton(variant: secondary, key: const Key('sign-out-button'), label: localizations.authSignOutButtonLabel, onPressed: () => context.read<AuthCubit>().signOut())` — identical behavior to today's home button, now living in Profil. `AuthCubit` is an ancestor of the shell (provided in `AuthGate`), so `context.read` at tap time resolves in production.
  - [x] Do **not** render „Meine Daten"/export/erasure or the „Größere Darstellung" toggle (out of scope, AC3).
- [x] **Task 5 — Verify provider reachability in the running app** (AC: #1, #2)
  - [x] Confirm the tree post-restructure: `AuthGate` (provides `AuthCubit`) → `FirstRunRouter` (provides `HouseholdsApi`/`StoresApi`/`StoreChainReferenceCache`/`HouseholdsCubit`) → `FirstRunRouterBody` → `HouseholdShell` → `IndexedStack` tabs. The Profil tab is **inside** this subtree, so it reads `AuthCubit` directly (no route boundary to cross) — this is why Profil is a body tab, not a pushed route.
  - [x] `IndexedStack` builds **all** children eagerly (to preserve tab state), so the Profil identity header reads `AuthCubit` at **build** time, not just on tap. Ensure nothing else in the tree reads a provider that isn't an ancestor.
- [x] **Task 6 — Migrate the shell/router widget tests** (AC: #1, #2)
  - [x] `household_shell_test.dart` and `first_run_router_test.dart` pump the shell via `FirstRunRouterBody` **without** an `AuthCubit` ancestor (today that works because `HouseholdHomePage` only reads `AuthCubit` on tap). The Profil identity header now reads `AuthCubit` at build time, so **wrap the test subject in a `BlocProvider<AuthCubit>.value`** driven to the `authenticated` state carrying a synthetic display name + email (drive a real `AuthCubit` with the existing fakes — set `identityToReturn`/`tokensToReturn` and `signIn()`, the `auth_gate_body_test.dart` pattern — or add a small shared helper in `test/support/`). Keep data synthetic (DSGVO).
  - [x] Replace the `current-household-name` assertions (first_run_router_test.dart:52, :82) — that key is gone — with an assertion that the active household name appears in the **switcher chip** (those tests already assert `switcher-chip`; assert the name via `find.descendant(of: switcher-chip, matching: find.text(...))`, mirroring `household_shell_test`). Do the same for household_shell_test if it relied on the home body.
  - [x] The existing switcher tests (chip tap, listing, switch-confirmation, persistence) must still pass unchanged aside from the added `AuthCubit` provider.
- [x] **Task 7 — New tests for the Profil screen & tabs** (AC: #1, #2, #3)
  - [x] **Auth state (unit):** `AuthState.authenticated` carries the email; `==`/`hashCode` include it; `AuthCubit` emits an authenticated state with the email from `CallerIdentity` (extend `auth_cubit_test.dart`).
  - [x] **Shell tabs (widget):** the shell shows a `NavigationBar` with the three German labels; tapping **Profil** shows the identity header (synthetic name + email) and the „Sprache & Region"/„Benachrichtigungen"/„Abmelden" surfaces; tapping **Listen**/**Einkauf** shows their placeholders; the **switcher chip stays visible on every tab**.
  - [x] **Profil screen (widget, via `wrapForTesting`):** renders display name + email from a provided authenticated `AuthCubit`; the notifications info text is present and there is **no** `Switch`; there is **no** „Meine Daten"/export/erasure surface. Tapping the „Sprache & Region" row navigates to `LocaleSettingsPage` (assert a `LocaleSettingsPage` / its heading is shown) — pump with `LocaleCubit` provided as an ancestor (as the harness/app does).
  - [x] **Sign-out from Profil (widget):** tapping `sign-out-button` invokes `AuthCubit.signOut()` (assert the observable teardown — e.g. the fake token storage `cleared`, or the state returns to unauthenticated), proving the action moved intact from the home body.
  - [x] **Accessibility (widget):** interactive rows meet 48px (`tester.getSize` height ≥ 48 on the locale row / sign-out control) and the screen renders without overflow at an elevated `textScaler` (pump under a larger text scale and expect no overflow error).
  - [x] `flutter analyze` clean; all tests pass. Run locally: `export PATH="$PATH:/home/timo/tools/flutter/bin"; cd app && flutter analyze && flutter test`.

## Dev Notes

### Scope & intent
**Client-only story (Flutter) — no backend, no docker, no CQRS/event sourcing, no new dependencies.** This story is pure **assembly + a small auth-state addition**: it stands up the app's real bottom-nav shell (with Listen/Einkauf as placeholders) and delivers the **personal-only Profil screen** that finally gives the Story 1.10 `LocaleSettingsPage` a visible entry point, relocates „Abmelden" into Profil, and shows the live (never-persisted) identity. Notification settings are **reserved-not-built** (fixed MVP defaults) — do **not** add a preference store, event, or toggle (KISS/YAGNI, CLAUDE.md §1/§4; arch-spine §Reserved-not-built).

### Decisions locked with Timo (2026-08-25) — realize exactly
1. **Bottom-nav now, with placeholders.** Introduce the three-tab `NavigationBar` (Listen · Einkauf · Profil) in this story; Listen and Einkauf are calm plain-German placeholders until Epics 2/3 fill them; Profil is live. *(This supersedes Story 1.10's note that "the Profil bottom-nav tab is Epic 2/3" — the tab bar arrives here, its other two tabs as stubs.)*
2. **Identity header shows name + email.** Match `screen-profile.html`. Thread `email` through `AuthState` from the existing `CallerIdentity` (no second call, never persisted — AD-6), mirroring exactly how Story 1.10 threaded `keycloakUserId`.
3. **Out of scope, explicitly:** „Meine Daten" export/erasure (Epic 6 / CAP-14) is **not** on the screen at all; the „Größere Darstellung" larger-display toggle stays deferred (MVP relies on OS Dynamic Type).

### Provider tree (read before wiring — the recurring lesson)
Post-restructure the authenticated tree is:
`AuthGate` *(provides `AuthCubit`)* → `LocaleAuthBridge` → `AuthGateBody` → **`FirstRunRouter`** *(provides `HouseholdsApi`/`StoresApi`/`StoreChainReferenceCache`/`HouseholdsCubit`)* → `FirstRunRouterBody` → **`HouseholdShell`** → `IndexedStack` → **`ProfileScreen`**.
- `AuthCubit` is an **ancestor** of `HouseholdShell`, so the Profil tab body reads it directly — **this is why Profil is a body tab, not a root-navigator push.** The Story 1.6/1.8 "re-provide across the route boundary" dance (`_pushOverProviders`) is **not** needed for Profil itself.
- `LocaleCubit` sits **above `MaterialApp`** (`main.dart:63`), a global ancestor — so pushing `LocaleSettingsPage` from the Profil row needs **no** re-provide (unlike `AuthCubit`/`HouseholdsCubit`).
- **Test consequence:** the shell tests currently pump `FirstRunRouterBody` with **no `AuthCubit`** and pass only because today's sign-out reads `AuthCubit` lazily (on tap). The Profil identity header reads it at **build** time (IndexedStack builds all tabs eagerly), so those tests must now provide an authenticated `AuthCubit` — see Task 6.

### Reuse — do not reinvent (existing substrate)
[Source: verified in the current tree]
- `app/lib/features/settings/presentation/locale_settings_page.dart` (Story 1.10) — the „Sprache & Region" screen. The Profil row **pushes it**; do not rebuild it. It already reads the ancestor `LocaleCubit`.
- `app/lib/features/auth/presentation/auth_state.dart` / `auth_cubit.dart` — the exact place to add `email` (mirror the `keycloakUserId` threading). `CallerIdentity` already carries `email`.
- `app/lib/features/auth/presentation/auth_cubit.dart#signOut` — the sign-out teardown (tokens + active-household clear; the locale bridge clears per-user locale on `unauthenticated`). Reuse as-is; just call it from Profil.
- `app/lib/shared/widgets/sgart_app_bar.dart` — the persistent header (switcher chip + Dynamic-Type-clamped title). Keep it as the shell's single `appBar`.
- `app/lib/shared/widgets/sgart_button.dart` — `SgartButton(variant: secondary)` for „Abmelden" (reuse the `sign-out-button` key so its behavior test migrates cleanly).
- `app/lib/theme/tokens/sgart_shapes.dart` — spacing tokens (`space4`, `cardPadding`, `headingGap`) and `minTapTarget = 48`. Use tokens, not magic numbers.
- `app/test/support/widget_test_harness.dart` (`wrapForTesting`), `fake_auth_dependencies.dart`, `fake_households_dependencies.dart` — pump through the real theme + localization delegates with fakes.

### What `HouseholdHomePage` becomes
Today `HouseholdShell.body = HouseholdHomePage` (household name + sign-out). After this story the household name is in the header chip and sign-out is in Profil, so `HouseholdHomePage` is dead weight. **Retire it** (delete, or reuse its shell as the Listen placeholder) and move the `current-household-name`/`sign-out-button` key coverage per Task 6 (Boy Scout Rule — leave no orphaned placeholder).

### Source of truth: requirements & design (binding)
- [Source: epics.md#Story 1.11] (lines 447–461) — user story + ACs (personal-only; „Sprache & Region", notifications-info fixed defaults, „Abmelden"; OS Dynamic Type + 48px; „Größere Darstellung" deferred).
- [Source: DESIGN.md §5 Accessibility overlay] (lines 145–156) — 48px targets, honor `MediaQuery.textScaler` with reflow, „Größere Darstellung" deferred, plain-language German, primary actions never overflow-only.
- [Source: EXPERIENCE.md §2 Information Architecture] (lines 42–67) — three bottom-nav tabs „Listen"/„Einkauf"/„Profil" (labelled icons); persistent header (household switcher + sync status) on the main tabs; **Profil is purely personal**; household management stays in the switcher.
- [Source: EXPERIENCE.md §3 + `.working/screen-profile.html`] (lines 114–118) — the Profil mock: identity (name + email), Darstellung → Sprache & Region, notifications-info (fixed), Abmelden; export/erasure is a **separate** „Meine Daten" flow (Epic 6), larger text via OS Dynamic Type in MVP.
- [Source: epics.md UX-DR14] (line 135) / [UX-DR5] (line 118) / [NFR10] (line 88) — Profil = personal-only (Sprache & Region, notifications-info, Abmelden; no household management); accessibility overlay.
- [Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md] — line 221 (content-free notifications, AD-5 no data in payload) and line 230 (`NotificationSettingsUpdated` **reserved-not-built**, MVP ships fixed default notifications). AD-6 / AR4–AR5: display name & email read live from the token, **never persisted**.

### Language policy (binding)
[Source: memory `language-policy`] ARB **keys** English, ARB **values** German (`@`-metadata `description` in English like every existing key). All new user-facing copy (tab labels, section headings, notifications info) is German. Docs/comments/code identifiers English, no abbreviations (`profileScreen`, `notifications` — never `prof`/`notif`).

### Previous-story intelligence
- **Story 1.10 (done):** built `LocaleSettingsPage` reachable by route only and deliberately left the visible entry to **this** story; threaded `keycloakUserId` through `AuthState` (the exact template for threading `email`); guarded every store read/write in try/catch. It also established the `features/settings/` home for personal settings — `ProfileScreen` belongs there.
- **Story 1.7 (done):** `SgartAppBar` switcher chip + the persistent header this shell keeps; `ActiveHouseholdStore` clear-on-sign-out (part of the teardown `signOut` already runs).
- **Story 1.6/1.8 (done):** the ProviderNotFoundException lesson for **root-navigator** pushes — relevant background, but Profil is a body tab so it does **not** apply to Profil; it still applies to the pushed `LocaleSettingsPage`, which is safe only because `LocaleCubit` is a global ancestor (confirmed).
- **Local test reality** [memory `flutter-test-local`]: Flutter SDK at `/home/timo/tools/flutter/bin` (add to PATH); tests **do** run — run `flutter analyze` + `flutter test` for real before marking review.

### Testing standards
[Source: CLAUDE.md §6] Fast unit tests for the `AuthState`/`AuthCubit` email change (no widgets); widget tests through `wrapForTesting` for the shell tabs + Profil; assert **behavior/outcomes** (labels shown, navigation happened, `signOut` teardown observable, 48px height, no-overflow at large text scale), not widget structure; test names as full behavioral sentences (e.g. `tappingProfileTabShowsTheDisplayNameAndEmailFromTheToken`, `theSpracheUndRegionRowOpensTheLocaleSettingsPage`, `signingOutFromProfilClearsTheSession`, `profilHasNoDataExportOrErasureSurface`). **DSGVO:** synthetic fake identities only; explicitly assert the identity is display-only (no persistence path exercised) and that no export/erasure surface exists yet.

### Project Structure Notes
```text
app/lib/features/settings/presentation/profile_screen.dart      # NEW — personal-only Profil tab body (no Scaffold/AppBar)
app/lib/features/households/presentation/household_shell.dart    # UPDATE — Stateful: persistent header + IndexedStack + NavigationBar (3 tabs)
app/lib/features/households/presentation/household_home_page.dart# RETIRE — replaced by Profil (sign-out) + header (name); delete or repurpose as Listen placeholder
app/lib/features/auth/presentation/auth_state.dart              # UPDATE — carry email on authenticated
app/lib/features/auth/presentation/auth_cubit.dart              # UPDATE — pass identity.email into the state
app/lib/l10n/app_de.arb                                          # UPDATE — tab labels, Profil section/notifications keys (Task 2)
app/test/features/settings/presentation/profile_screen_test.dart# NEW
app/test/features/households/presentation/household_shell_test.dart   # UPDATE — provide authenticated AuthCubit; assert tabs
app/test/features/households/presentation/first_run_router_test.dart  # UPDATE — provide AuthCubit; name via switcher chip
app/test/features/auth/presentation/auth_cubit_test.dart        # UPDATE — email in authenticated state
```
- `ProfileScreen` under `features/settings/presentation/` (personal setting, not a household concern) — consistent with where `LocaleSettingsPage`/`LocaleCubit` already live. Shell changes stay in `features/households/presentation/`.
- One class per file; SRP: the shell owns navigation/chrome, `ProfileScreen` renders personal settings, `LocaleSettingsPage` owns locale. No abbreviations.

### References
- [Source: epics.md#Story 1.11: Personal profile screen] (lines 447–461)
- [Source: epics.md UX-DR14 (135), UX-DR5 (118), NFR10 (88), UX-DR9 (126)]
- [Source: DESIGN.md §5 Accessibility overlay (145–156)]
- [Source: EXPERIENCE.md §2 (42–67), §3 + `.working/screen-profile.html` (114–118)]
- [Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md (221, 230; AD-6, AR4–AR5)]
- [Source: 1-10-view-and-change-my-locale.md] — the `LocaleSettingsPage`/`LocaleCubit` substrate and the `keycloakUserId`-threading template this story mirrors for `email`
- [Source: app/lib/features/households/presentation/household_shell.dart, household_home_page.dart, first_run_router.dart] — the shell/home/provider tree being restructured
- [Source: app/lib/features/auth/presentation/{auth_state.dart, auth_cubit.dart}, app/lib/features/auth/data/caller_identity.dart] — where email is threaded from
- [Source: CLAUDE.md] — Clean Code, naming, DDD/CQRS-where-it-adds-value, testing & DSGVO rules

## Clarifications (raised for Timo — his decisions of 2026-08-25 already applied)

1. **Profil entry point in a two-tabs-missing shell.** LOCKED → **introduce the three-tab bottom nav now**, with Listen/Einkauf as plain-German placeholders and Profil live (rather than a header person-icon that defers the tab bar to Epic 2/3). The persistent header (switcher + sync status) stays on every tab.
2. **Identity header content.** LOCKED → **name + email**, matching `screen-profile.html`; `email` threaded through `AuthState` from the existing `CallerIdentity` (no second call, never persisted, AD-6).
3. **Out-of-scope confirmations (by spec, no fork):** „Meine Daten" export/erasure is Epic 6 and is not shown at all; the „Größere Darstellung" toggle stays deferred — MVP larger text via OS Dynamic Type.

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

None — no failures requiring a debug log; `flutter analyze` and `flutter test` were clean on first
completed run of the full suite (233 passed).

### Completion Notes List

- Threaded `email` through `AuthState.authenticated(displayName, keycloakUserId, email)` (now a
  required third positional parameter, mirroring the existing `keycloakUserId` threading from
  Story 1.10) and through `AuthCubit._loadCallerIdentity`, sourced from the existing
  `CallerIdentity.email` — no second identity call, never persisted (AD-6).
- Added ARB keys for the three shell tab labels, the two tab placeholders, and the four Profil
  section/notification/account labels; removed the now-unused `householdsHomeCurrentHouseholdLabel`
  key after retiring `HouseholdHomePage` (no dead ARB entries).
- Restructured `HouseholdShell` into a `StatefulWidget` with a Material 3 `NavigationBar` (Listen ·
  Einkauf · Profil, labelled icons, stable keys) and an eagerly-built `IndexedStack` body; the
  `SgartAppBar` (switcher chip + sync-status placeholder) stays the single persistent `appBar`
  across all three tabs.
- Retired `HouseholdHomePage` (deleted) — household name now lives only in the header switcher
  chip; „Abmelden" moved to the new `ProfileScreen`.
- Added `ProfileScreen` under `features/settings/presentation/` (personal, not household-scoped):
  a display-only identity header (avatar initial + name + email from `AuthState`, never written
  anywhere), a „Sprache & Region" row that pushes the existing `LocaleSettingsPage` (Story 1.10;
  `LocaleCubit` is a global ancestor above `MaterialApp`, so no re-provide is needed), a fixed
  info-only „Benachrichtigungen" block (no toggle/backend — `NotificationSettingsUpdated` stays
  reserved-not-built), and the relocated „Abmelden" `SgartButton` reusing the `sign-out-button` key.
  No „Meine Daten" export/erasure and no „Größere Darstellung" toggle (both explicitly out of
  scope, AC3).
- Migrated `household_shell_test.dart` and `first_run_router_test.dart` to provide an authenticated
  `AuthCubit` ancestor (via a new `buildAuthenticatedAuthCubit()` test helper in
  `fake_auth_dependencies.dart`), since the Profil tab now reads `AuthCubit` at build time
  (`IndexedStack` builds all tabs eagerly). Replaced the retired `current-household-name` key
  assertions with assertions that the household name appears inside the `switcher-chip` descendant
  tree. All pre-existing switcher behavior (chip tap, listing, switch + confirmation, persistence)
  passes unchanged aside from the added provider.
- Added a new `HouseholdShell tabs` test group (three German tab labels, tapping Profil shows the
  identity header + Sprache & Region/Benachrichtigungen/Abmelden, tapping Listen/Einkauf shows
  their placeholders and updates `NavigationBar.selectedIndex`, switcher chip stays visible across
  all three tabs).
- Added `profile_screen_test.dart`: identity header render, notifications info present with no
  `Switch`, no „Meine Daten"/„Größere Darstellung" surface, „Sprache & Region" row navigates to
  `LocaleSettingsPage`, sign-out clears the fake token storage and returns `AuthCubit` to
  `unauthenticated`, 48px minimum tap targets on the locale row and sign-out button, no overflow at
  2x `textScaler`.
- Added an `AuthState` equality/hashCode unit-test group and extended every existing
  `auth_cubit_test.dart`/`locale_auth_bridge_test.dart` call site to carry a synthetic email
  (DSGVO: fake data only).
- Full regression: `flutter analyze` clean; `flutter test` — 233 passed, 0 failed.

### File List

- `app/lib/features/auth/presentation/auth_state.dart` — UPDATE (thread `email`)
- `app/lib/features/auth/presentation/auth_cubit.dart` — UPDATE (pass `identity.email`)
- `app/lib/l10n/app_de.arb` — UPDATE (shell/Profil keys added; unused home-page key removed)
- `app/lib/features/households/presentation/household_shell.dart` — UPDATE (3-tab `StatefulWidget` shell)
- `app/lib/features/households/presentation/household_home_page.dart` — DELETE (retired)
- `app/lib/features/settings/presentation/profile_screen.dart` — NEW
- `app/test/features/auth/presentation/auth_cubit_test.dart` — UPDATE (email in state; new `AuthState` group)
- `app/test/features/settings/presentation/locale_auth_bridge_test.dart` — UPDATE (email call sites)
- `app/test/support/fake_auth_dependencies.dart` — UPDATE (`buildAuthenticatedAuthCubit` helper)
- `app/test/features/households/presentation/household_shell_test.dart` — UPDATE (`AuthCubit` ancestor; new tabs group)
- `app/test/features/households/presentation/first_run_router_test.dart` — UPDATE (`AuthCubit` ancestor; switcher-chip assertions)
- `app/test/features/settings/presentation/profile_screen_test.dart` — NEW

### Change Log

- 2026-08-25 — Implemented Story 1.11: threaded `email` through `AuthState`/`AuthCubit`; introduced
  the three-tab `NavigationBar` shell (Listen/Einkauf placeholders, live Profil); added the
  personal-only `ProfileScreen` (identity header, „Sprache & Region" entry point, fixed
  notifications info, relocated „Abmelden"); retired `HouseholdHomePage`; migrated and extended the
  shell/router/auth test suites. `flutter analyze` clean; 233 tests passing.

### Review Findings

Code review 2026-08-25 (adversarial layers: Blind Hunter · Edge Case Hunter · Acceptance Auditor).
Independently verified: `flutter analyze` clean, `flutter test` 233 passing. Implementation is
faithful to all three ACs; findings are hardening/cleanliness only.

- [x] [Review][Patch] Avatar initial splits UTF-16 code units instead of the "first grapheme" the spec asks for — a display name beginning with an emoji/astral or combining character yields a lone surrogate (broken glyph); use `name.characters.first` [app/lib/features/settings/presentation/profile_screen.dart:96]
- [x] [Review][Patch] `tappingTheListenAndEinkaufTabsSelectsTheirPlaceholders` asserts the Listen placeholder but never the Einkauf placeholder — the test name promises "Placeholders" (plural) but a broken Einkauf wiring would still pass [app/test/features/households/presentation/household_shell_test.dart:170]
- [x] [Review][Patch] `rendersWithoutOverflowAtAnElevatedTextScale` copy-pastes the whole `buildSubject` provider/MaterialApp tree instead of parameterizing it with a `textScaler` — the two copies will drift (CLAUDE.md §6 DRY) [app/test/features/settings/presentation/profile_screen_test.dart:115]
- [x] [Review][Patch] Stale doc comment invalidated by this change — `FirstRunRouter`'s comment still says "1 → household home" after `HouseholdHomePage` was retired into the shell (Boy Scout, CLAUDE.md §1) [app/lib/features/households/presentation/first_run_router.dart:24]
- [x] [Review][Defer] Identity header has no `maxLines`/`overflow` on the display-name and email `Text` — a long unbreakable email at large text scale clips (bounded by `Expanded`, so no exception, but not polished) [app/lib/features/settings/presentation/profile_screen.dart:86] — deferred, cosmetic
- [x] [Review][Defer] `email` (personal data) now rides on the app-wide emitted `AuthState`; safe today (no `BlocObserver`) but the same as the pre-existing `displayName` exposure — add a redacting `toString`/logging guard if transition logging is ever introduced (DSGVO, CLAUDE.md §5) [app/lib/features/auth/presentation/auth_state.dart:33] — deferred, pre-existing pattern
