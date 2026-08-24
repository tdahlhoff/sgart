---
baseline_commit: b3197de7baf255bed1ba8804ab6dd8b42dcd08cb
---

# Story 1.10: View and change my locale

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a member,
I want to view and change my language & region,
so that the app speaks my language and formats values the way I expect.

## Acceptance Criteria

1. **A member can view and change their own `Locale` on a „Sprache & Region" screen; it is per-user, defaults from the device, and falls back to `de-DE`.** (FR13, CAP-13, AR10, arch-spine §Localization)
   - The screen shows the currently effective locale and a short list of choices: **„Systemstandard (Gerät)"** (follow the device locale) plus the German region variants **„Deutsch (Deutschland)" (`de-DE`)**, **„Deutsch (Österreich)" (`de-AT`)**, **„Deutsch (Schweiz)" (`de-CH`)**. The active choice is unmistakably marked.
   - The chosen locale is persisted **on-device, per authenticated user**, restored on the next launch, and **cleared on sign-out** — a later sign-in on the same device never inherits the previous person's locale (mirrors `ActiveHouseholdStore`, DSGVO / AD-7).
   - With no stored choice, the effective locale is the device locale resolved against the supported set, **falling back to `de-DE`** when the device locale is unsupported.
2. **Applying a locale change updates language and number/date/quantity/currency formatting immediately — no reinstall, no restart.** (FR13, CAP-13, AR10, AD-9)
   - The whole running app re-renders under the selected locale (`MaterialApp.locale` is driven by the selection). UI copy stays German for every choice (MVP ships one language; all three regions match language code `de`); the **observable change is formatting** — e.g. a sample number renders `1.234,5` under `de-DE`/`de-AT` and `1'234.5` under `de-CH`.
   - Because no live screen currently renders a formatted value (the Story 1.3 demo screen was replaced in 1.4/1.6), the „Sprache & Region" screen itself carries a small **live formatting preview** (a sample number and date formatted via the existing `intl` formatters) that re-renders as the selection changes — this is the story's concrete proof of AC2.
   - No locale-formatted value is ever persisted; only the canonical locale *tag* (e.g. `de-CH`) is stored.

> **Entry-point note (decision by Timo — see Clarifications §3):** this story delivers the „Sprache & Region" screen **and the reactive locale mechanism only**. It is exercised via its route/tests; the visible entry that hosts it (the Profil screen) is **Story 1.11**. The epic AC's „Given the Profil screen" framing is therefore realized in 1.11 — 1.10 leaves the page reachable by route and the restore-on-launch plumbing live so 1.11 only has to add the entry.

## Tasks / Subtasks

- [x] **Task 1 — String catalog: „Sprache & Region" copy** (AC: #1, #2)
  - [x] Add keys to `app/lib/l10n/app_de.arb` (English keys, German values, per language policy; include `@`-metadata `description` like every existing key). Suggested keys:
    - `localeSettingsHeading` → „Sprache & Region"
    - `localeSettingsIntro` → a one-line plain-language explainer (e.g. „Wähle, wie die App Sprache und Zahlen anzeigt.")
    - `localeOptionSystemLabel` → „Systemstandard (Gerät)"
    - `localeOptionGermanyLabel` → „Deutsch (Deutschland)"
    - `localeOptionAustriaLabel` → „Deutsch (Österreich)"
    - `localeOptionSwitzerlandLabel` → „Deutsch (Schweiz)"
    - `localePreviewSectionLabel` → „Vorschau" (label above the live formatting sample)
    - `localeChangeConfirmation` → „Sprache & Region aktualisiert" (SnackBar after applying)
  - [x] Run `flutter gen-l10n` and confirm the new getters appear on `AppLocalizations` (generated under `lib/l10n/gen/`, gitignored — do not commit it).
- [x] **Task 2 — Support the German region variants app-wide** (AC: #1, #2)
  - [x] Set `MaterialApp.supportedLocales` explicitly to `[Locale('de','DE'), Locale('de','AT'), Locale('de','CH')]` (override the generated single-`de` list). The generated `AppLocalizations.delegate.isSupported`/`lookupAppLocalizations` already match on **language code `de`**, so all three resolve the German catalog — no new ARB files. Verify `Localizations.localeOf(context)` preserves the **region** (add a `localeResolutionCallback` that returns the requested locale when its language code is `de`, so Flutter does not collapse `de-CH` → `de`). Keep the `de-DE` fallback for an unsupported device locale.
  - [x] Extend `initializeDateFormatting` at boot (`main.dart`) to cover `de_AT` and `de_CH` as well as `de_DE` (or call `initializeDateFormatting()` with no argument to load all) — otherwise `DateFormat` for a newly-selected region throws `LocaleDataException` (the Story 1.3 date-symbols lesson). Confirm with the date-preview test.
- [x] **Task 3 — On-device locale preference store** (AC: #1)
  - [x] Add `LocalePreferenceStore` (interface) + `SharedPreferencesLocalePreferenceStore` under `app/lib/features/settings/data/` (new `settings` feature) — mirror `ActiveHouseholdStore` exactly in shape and doc style. Methods keyed by the authenticated user's id (Keycloak `sub`): `Future<String?> read(String userId)`, `Future<void> write(String userId, String localeTag)`, `Future<void> clear(String userId)`. Key = `'sgart.locale.<userId>'`. Store the canonical locale **tag** only (e.g. `de-CH`); absence of a key = „Systemstandard".
  - [x] `shared_preferences` is already a dependency — no pubspec change.
- [x] **Task 4 — Reactive active-locale holder above `MaterialApp`** (AC: #1, #2)
  - [x] Add `LocaleCubit` + `LocaleState` under `app/lib/features/settings/presentation/` (flutter_bloc, matching the app's Cubit-per-concern convention). State carries the **selection** (a sealed choice: `system` vs an explicit `Locale`) and exposes the **effective `Locale?`** to feed `MaterialApp.locale` (`null` = follow device).
  - [x] `LocaleCubit` takes the `LocalePreferenceStore`. API: `applyForUser(String userId)` (load that user's stored tag → set selection, or `system` if none), `select(LocaleSelection, {required String userId})` (update state + persist; `system` clears the stored key), `resetToDeviceDefault()` (state → `system`, no persistence — used on sign-out). Fail-safe: guard every store read/write in try/catch so a storage error degrades to `system` rather than crashing (mirror the `ActiveHouseholdStore` guarding added in Story 1.7 review).
- [x] **Task 5 — Wire the holder into the app root and the auth lifecycle** (AC: #1, #2)
  - [x] Restructure `SgartApp` (`main.dart`) so `LocaleCubit` is provided **above** `MaterialApp` and a `BlocBuilder<LocaleCubit,…>` feeds `MaterialApp.locale` from the effective locale. Preserve all existing wiring (theme light/dark + `ThemeMode.system`, `registerBundledFontLicenses`, delegates, `home: AuthGate`).
  - [x] Bridge auth → locale (per-user): when `AuthCubit` reaches `authenticated`, call `LocaleCubit.applyForUser(sub)`; on sign-out, call `resetToDeviceDefault()` **and** `LocalePreferenceStore.clear(sub)`. See Dev Notes §"Provider-tree constraint" for the recommended bridge (a `BlocListener<AuthCubit>` inside the `AuthGate` subtree that reads the ancestor `LocaleCubit`) — `AuthCubit` sits **below** `MaterialApp`, so `LocaleCubit` cannot depend on it directly. The `sub` must be available to the client; if `AuthState.authenticated` does not already carry it, thread it through from the identity response (do not invent a second identity call).
- [x] **Task 6 — „Sprache & Region" screen** (AC: #1, #2)
  - [x] Add `LocaleSettingsPage` under `app/lib/features/settings/presentation/`. Render the four choices as a single-select list (radio/`RadioListTile`-style), current selection marked, each row labelled from the ARB. Selecting a row calls `LocaleCubit.select(...)`, shows the `localeChangeConfirmation` SnackBar, and the app re-renders under the new locale.
  - [x] Include the **live formatting preview** section: a sample number and date formatted through the existing `NumberFormatter`/`DateFormatter` using the *currently effective* locale, so it visibly flips (`1.234,5` ↔ `1'234.5`) — this is the AC2 proof surface (Dev Notes §"AC2 is proven on this screen").
  - [x] Accessibility (NFR10 / UX-DR5): 48px minimum interactive targets, honor OS text scaling, plain-language German. The page is reachable via a route/`MaterialPageRoute` for tests; **do not** add a visible entry point in the shell (that is Story 1.11).
- [x] **Task 7 — Tests** (AC: #1, #2, #3-privacy)
  - [x] **Store** (unit, in-memory `SharedPreferences.setMockInitialValues` or a fake): write/read/clear round-trip; per-user isolation (user A's tag not returned for user B); absent key → `null`.
  - [x] **LocaleCubit** (unit): fresh → `system`; `applyForUser` restores a stored `de-CH`; `select(de-CH)` persists and emits effective `Locale('de','CH')`; `select(system)` clears the key; `resetToDeviceDefault` → `system` without persisting; a throwing store degrades to `system` (no crash).
  - [x] **Page** (widget, via the shared test harness): renders the four German option labels; the active option is marked; tapping „Deutsch (Schweiz)" flips the preview sample to the Swiss grouping and shows the confirmation SnackBar. **Assert the exact separators `intl` produces — do not eyeball** (the Story 1.3 non-breaking-space/`’` lesson: `de_CH` grouping is a Unicode `’`/`'`, decimal `.`).
  - [x] **Locale resolution** (widget): `MaterialApp` under `de-CH` still renders German catalog copy (language-code match) and `Localizations.localeOf` preserves the `CH` region; an unsupported device locale (e.g. `fr`) falls back to `de-DE`.
  - [x] **Sign-out clears locale** (unit/widget): a sign-out clears the stored tag and resets the holder to `system` (DSGVO / AD-7) — extend or mirror the existing `AuthCubit.signOut` teardown test.
  - [x] `flutter analyze` clean; all tests pass. Run locally: `export PATH="$PATH:/home/timo/tools/flutter/bin"; cd app && flutter analyze && flutter test`.

### Review Findings

_Code review 2026-08-24 (bmad-code-review) — three adversarial layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor) run fresh-context on Opus 4.8. Verified independently: `flutter analyze` clean, all 217 tests pass. No `high`/`medium` findings survived triage; the three below are all `low`._

- [x] [Review][Patch] `LocaleCubit` emits are not guarded against emit-after-close (no `isClosed` guard, unlike `AuthCubit._safeEmit` / the Story 1.7 guarding lesson) — an `emit` after `close()` during an awaited store op throws `StateError`; in `applyForUser` the `catch` then emits again and re-throws uncaught [app/lib/features/settings/presentation/locale_cubit.dart:29,40,64] — FIXED: added `_safeEmit`; regression test `doesNotThrowWhenAnAwaitedStoreCallResolvesAfterTheCubitIsClosed`
- [x] [Review][Patch] Tapping the already-active option still shows the „aktualisiert" SnackBar (and runs a no-op `select`) — misleading confirmation for a selection that did not change [app/lib/features/settings/presentation/locale_settings_page.dart:67] — FIXED: `_select` returns early when the tapped option equals the current selection; regression test `tappingTheAlreadyActiveOptionShowsNoConfirmation`
- [x] [Review][Patch] `LocaleAuthBridge.listenWhen` fires only on `status` change, so a future `authenticated`→`authenticated` account switch (same status, different `sub`) would not re-apply the new user's locale — latent (current auth flow always passes through `unauthenticated`), cheap to harden [app/lib/features/settings/presentation/locale_auth_bridge.dart:23] — FIXED: `listenWhen` also fires on `keycloakUserId` change; regression test `reAppliesTheLocaleWhenAnotherUserSignsInWithoutAnInterveningSignOut`

_Dismissed (recorded for traceability):_
- **False positive** — `resolveSupportedLocale` passing through un-initialized `de` regions (bare `de`, `de-LU`) was claimed to crash the preview via `LocaleDataException`. Reproduced empirically: with `de_DE`/`de_AT`/`de_CH` symbols initialized, `intl` falls back by language code to the `de` base — bare `de` and `de-LU` both format cleanly (`15.6.2026`). No crash.
- **By spec** — clear-on-sign-out "destroys" the user's own persisted preference: AC1 / locked clarification #2 mandate clear-on-sign-out (DSGVO / AD-7). Working as specified.
- **By design** — a locale change only visibly reformats the „Vorschau": the story explicitly scopes the preview as the sole live formatter consumer (no other formatted value in the live tree).
- **Minor / YAGNI / cosmetic** — `localeResolutionCallback` ignores the device's full preferred-locale list; the signature-mandated unused `supported` param + literal `de-DE` fallback; `_localeFromTag` not fail-fast on app-controlled canonical tags; `_previewInstant` timezone comment inaccurate at UTC+13/+14; DSGVO store doc phrasing; Dev Agent Record miscounts ("nine" ARB keys → actually 8; "28" new tests → 29).

## Dev Notes

### Scope & intent
**Client-only story (Flutter) — no backend, no docker, no CQRS/event sourcing.** A `Locale` preference is a personal *display* setting, not domain state — the arch spine reserves `NotificationSettingsUpdated` as "not built" and there is no user-preference backend surface; do **not** add one (KISS/YAGNI, CLAUDE.md §1/§4). This story consumes the Story 1.3 substrate (ARB pipeline + `intl` formatters, already `localeName`-parameterised) and adds the missing **override** step from the arch-spine localization rule: `device-default → override → de-DE fallback`.

### Decisions locked with Timo (2026-08-24) — realize exactly
1. **Region variants of German, not a language list.** The picker offers Systemstandard / `de-DE` / `de-AT` / `de-CH`. Language is always German (MVP ships one language; additional UI languages are explicitly Post-MVP, SPEC §Out-of-build). The three regions all resolve the German catalog by **language-code** match, so the *observable* effect of a change is **formatting**, which is exactly what makes AC2 demonstrable without a second language.
2. **On-device persistence, keyed per user (`sgart.locale.<sub>`), cleared on sign-out.** Mirrors `ActiveHouseholdStore`. "Per-user" (FR13) is satisfied on-device; it does **not** sync across a user's devices — acceptable for MVP, and consistent with the erasure/device-cache-purge rule (AD-7). *(Note: `ActiveHouseholdStore` itself uses a single fixed key + clear-on-sign-out; per-sub keying here is deliberately stronger isolation — see the keying sub-decision in Clarifications §2.)*
3. **Page + mechanism only; no visible entry until Story 1.11.** The Profil screen that hosts „Sprache & Region" is Story 1.11; the Profil bottom-nav tab is Epic 2/3. Deliver the page reachable by route and the restore-on-launch plumbing live. The app stays fully working (purely additive) — nothing in the running UI links to the page yet.

### Provider-tree constraint (read before wiring — the #1 thing that will trip implementation)
`AuthCubit` is provided **inside** `AuthGate`, and `AuthGate` is `MaterialApp.home` — i.e. `AuthCubit` lives **below** `MaterialApp` (`auth_gate.dart:25`). But `LocaleCubit` must feed `MaterialApp.locale`, so it must sit **above** `MaterialApp`, hence **above** `AuthCubit`. Therefore `LocaleCubit` **cannot** read `AuthCubit` the way `ActiveHouseholdStore` is consumed (that store lives inside the authenticated subtree).
- **Recommended:** keep `LocaleCubit` above `MaterialApp`; place a `BlocListener<AuthCubit, AuthState>` **inside** the `AuthGate`/authenticated subtree (where `AuthCubit` exists) that reaches the **ancestor** `LocaleCubit` via `context.read<LocaleCubit>()` and calls `applyForUser(sub)` on `authenticated` and `resetToDeviceDefault()` + `store.clear(sub)` on `unauthenticated`. This honors per-sub keying without lifting `AuthCubit`.
- **Lighter alternative (flag for review):** if threading the `sub` up proves disproportionate, fall back to a **single-key** store (`sgart.localeTag`) + clear-on-sign-out (exactly the `ActiveHouseholdStore` precedent), restored by `LocaleCubit` at boot and cleared by the same sign-out listener. Simpler, one accepted-risk (a returning different user on a shared device briefly inherits until the sign-out clear ran) — identical to the risk `ActiveHouseholdStore` already accepts.
- Do **not** nest a second `MaterialApp`. If avoiding a root restructure is strongly preferred, `Localizations.override(locale: …)` around the authenticated subtree is a legitimate narrower mechanism, but the root-level `MaterialApp.locale` approach is cleaner and is the recommended path.

### AC2 is proven on this screen (no other live formatter consumer exists)
Grep confirms **no widget in the live tree constructs a formatter** any more — the Story 1.3 `home_page.dart` formatted-quantity demo was removed when 1.4/1.6 replaced the home placeholder with `AuthGate`/`HouseholdHomePage`. So the only place a locale change is *visible* in this story is the picker's own **live preview**. Build it: a „Vorschau" block rendering `NumberFormatter(localeName: effective).format(1234.5)` and `DateFormatter(localeName: effective).formatDate(<fixed UTC instant>)`, rebuilt on selection. `de-AT` and `de-DE` produce **identical** number/date output (both `1.234,5`, `dd.MM.yyyy`); `de-CH` is the observable divergence (`1'234.5`). Tests must assert the **`de-CH`** delta concretely and may assert `de-AT == de-DE`.

### Reuse — do not reinvent (existing substrate from Story 1.3)
[Source: 1-3-localization-layer-de-de-formatting.md]
- `app/lib/l10n/formatting/{number_formatter,date_formatter,quantity_formatter,money_formatter}.dart` — each already takes a `localeName` (default `de_DE`). Feed them the **effective** locale string; do **not** create new formatters. `DateFormatter` already `toLocal()`s the UTC instant before formatting.
- `app/lib/l10n/gen/app_localizations.dart` (generated) — delegate matches language code `de`; `supportedLocales` is `[Locale('de')]` and is overridden at the `MaterialApp` call site in Task 2.
- `app/lib/features/households/data/active_household_store.dart` — the exact interface + `shared_preferences` impl pattern (+ DSGVO doc comment) to mirror for `LocalePreferenceStore`.
- `app/lib/features/auth/presentation/auth_cubit.dart` — `signOut()` already clears `ActiveHouseholdStore` inside a try/catch; add the locale clear/reset alongside (or via the bridge listener).
- `app/test/support/widget_test_harness.dart` (`wrapForTesting`) — pump widgets through the real theme + localization delegates.

### Source of truth: ARCHITECTURE-SPINE (binding)
[Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md]
- §Localization (line 142): "all via localization layer keyed by `Locale` (**per-user, device-default → override → `de-DE` fallback**). MVP ships German." → this story implements the **override**.
- §Dates & formatting (line 140): "format per user `Locale` client-side … **No locale-formatted values persisted.**" → only the locale *tag* is stored; formatters stay display-only.
- AD-9 (Money & Quantity) / AR10: formatters accept canonical shapes; timestamps stored UTC, formatted client-side per `Locale`.

### Language policy (binding)
[Source: memory `language-policy`] ARB **keys** English, ARB **values** German. New option labels, heading, preview label, and confirmation are German values under English keys. Docs/comments/code identifiers English.

### Latest tech notes (Flutter 3.44.9 / Dart 3.12.2 / `intl` 0.20.2)
- **`de_CH` number formatting:** `intl` renders Swiss grouping as a Unicode apostrophe (`’`, and historically `'`) with a `.` decimal → `1’234.5`. **Assert the exact character `intl` emits** (copy it from a failing test), per the Story 1.3 rule against eyeballing separators.
- **Date symbols per region:** `DateFormat('…','de_CH')` throws `LocaleDataException` unless that locale's symbols are initialized — extend `initializeDateFormatting` (Task 2).
- **`supportedLocales` with region:** overriding `MaterialApp.supportedLocales` to region-qualified `de` locales is fine because the generated delegate's `isSupported` keys on language code; a `localeResolutionCallback` guarantees the region survives resolution so the formatter sees `de_CH`, not `de`.
- **No new dependencies:** `shared_preferences`, `intl`, `flutter_localizations`, `flutter_bloc` are all already in `pubspec.yaml`.

### Project Structure Notes
```text
app/lib/features/settings/                       # NEW feature (personal settings)
  data/locale_preference_store.dart              # interface + SharedPreferences impl (per-user key)
  presentation/locale_cubit.dart                 # active-locale holder (above MaterialApp)
  presentation/locale_state.dart                 # selection (system | Locale) + effective Locale?
  presentation/locale_settings_page.dart         # „Sprache & Region" screen + live preview
app/lib/main.dart                                # UPDATE: LocaleCubit above MaterialApp; feed .locale; init date symbols de_AT/de_CH
app/lib/l10n/app_de.arb                          # UPDATE: new keys (Task 1)
app/lib/features/auth/presentation/auth_cubit.dart  # UPDATE (or bridge): clear/reset locale on sign-out
app/test/features/settings/…                     # store, cubit, page tests
app/test/…                                        # locale-resolution + sign-out-clears tests
```
- New `features/settings/` matches the feature-first convention (`features/<feature>/{data,presentation}`); a personal display setting is not a household concern, so it does not go under `features/households/`.
- One class per file, no abbreviations (`localePreference`, `localeSelection` — never `loc`/`pref`). SRP: store persists, cubit holds state, page renders.

### Previous-story intelligence
- **Story 1.9 (done):** frontend-only, wrapped existing paths; `PopScope`/route-pop edge cases bit the reviewer. Here, routing is trivial (single pushed page), but keep the SnackBar/selection flow test-covered.
- **Story 1.7 (done):** `ActiveHouseholdStore` read/write were later **guarded in try/catch** during review (a storage error was turning a load into a hard-failure screen) — apply the same defensive guarding to `LocaleCubit`↔store from the start.
- **Story 1.3 (done):** date-symbol init + exact-separator assertions are recurring gotchas — pre-empt both (Tasks 2, 7).
- **Local test reality** [memory `flutter-test-local`]: Flutter SDK at `/home/timo/tools/flutter/bin` (add to PATH); tests **do** run — run `flutter analyze` + `flutter test` for real before review.

### Testing standards
[Source: CLAUDE.md §6] Fast unit tests for store + cubit (no widgets, fake store); widget tests through `wrapForTesting`; assert **behavior/outcomes** (exact formatted strings, marked selection, persisted tag), not structure; test names as full behavioral sentences (`selectingSwitzerlandPersistsTheTagAndFlipsTheNumberPreview`, `signOutClearsThePersistedLocale`). **DSGVO:** synthetic data only; explicitly test the sign-out/erasure clear (right-to-erasure device-cache guarantee).

### References
- [Source: epics.md#Story 1.10: View and change my locale] (lines 431–445) — user story + ACs
- [Source: epics.md#FR13] (line 62), [#Story 1.11] (lines 447–459), [#UX-DR14] (line 135), [#UX-DR5] (line 118) — locale requirement, the Profil host (1.11), accessibility
- [Source: specs/spec-sgart/SPEC.md#CAP-13] (lines 71–73) — canonical success criteria
- [Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md] lines 140–142, AD-9, AR10 — localization/formatting conventions
- [Source: ux-designs/ux-sgart-2026-08-20/EXPERIENCE.md] lines 44,66–67,114–116 — Profil IA, „Sprache & Region" placement, Größere-Darstellung deferral
- [Source: 1-3-localization-layer-de-de-formatting.md] — the ARB + `intl` formatter substrate this story consumes
- [Source: app/lib/features/households/data/active_household_store.dart, .../auth/presentation/auth_cubit.dart, .../auth/presentation/auth_gate.dart] — persistence/lifecycle/provider-tree patterns to mirror
- [Source: CLAUDE.md] — Clean Code, naming, DDD/CQRS-where-it-adds-value, testing & DSGVO rules

## Clarifications (raised for Timo — his decisions of 2026-08-24 already applied)

1. **What „change locale" offers in a German-only MVP.** LOCKED → **region variants of German** (Systemstandard / de-DE / de-AT / de-CH); language stays German, region drives formatting; the picker's live preview proves AC2. (Trade-off accepted: de-AT ≈ de-DE, so de-CH is the visibly-different one.)
2. **Persistence location + keying.** LOCKED → **on-device SharedPreferences**, cleared on sign-out. Keying: this story specs **per-user (`sgart.locale.<sub>`)** as selected; the established `ActiveHouseholdStore` precedent uses a single key + clear-on-sign-out, so **if threading the `sub` above `MaterialApp` proves disproportionate, dev/review may simplify to single-key** (Dev Notes §Provider-tree). Either satisfies the DSGVO clear-on-sign-out requirement.
3. **Entry point / sequencing.** LOCKED → **page + mechanism only**; no visible entry until Story 1.11. The epic AC's „Given the Profil screen" is realized in 1.11; 1.10 is reachable by route/tests. (Only residual: the app has no user-facing way to reach the page during 1.10 — intentional; the restore-on-launch plumbing is nonetheless live.)

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (bmad-dev-story)

### Debug Log References

- Probed `intl` 0.20.2 separators before writing tests (Story 1.3 "never eyeball" rule). Actual output:
  `de-DE` = `1.234,5` (dot group, comma decimal); `de-AT` = `1 234,5` (**non-breaking-space** group);
  `de-CH` = `1’234.5` (U+2019 group, dot decimal). Dates (`yMd`) identical across all three (`15.6.2026`).
- `flutter analyze` clean; `flutter test` → **217 passed** (28 new for this story, plus the 6 auth-cubit
  expectations updated for the threaded `sub`).

### Completion Notes List

Client-only locale override on the Story 1.3 substrate. Implemented `device-default → override → de-DE
fallback` with reactive re-render.

- **New `settings` feature.** `LocalePreferenceStore` (interface + `SharedPreferences` impl, per-user key
  `sgart.locale.<sub>`, tag-only), `LocaleCubit` above `MaterialApp`, sealed `LocaleState`
  (`SystemLocale` | `ExplicitLocale`), `LocaleSettingsPage` with live „Vorschau", `LocaleAuthBridge`, and a
  reusable `resolveSupportedLocale` + `supportedLocales`.
- **Wiring.** `main.dart` provides `LocaleCubit` above `MaterialApp`, feeds `MaterialApp.locale` via a
  `BlocBuilder`, overrides `supportedLocales` to the three region-qualified `de` locales, adds a
  `localeResolutionCallback` that preserves the region (so the formatter sees `de_CH`, not `de`), and
  initializes date symbols for `de_DE`/`de_AT`/`de_CH`. `home: const AuthGate()` kept `const` so a locale
  rebuild does not recreate `AuthCubit`.
- **Auth lifecycle.** Threaded the Keycloak `sub` through `AuthState.authenticated(displayName, keycloakUserId)`
  (from the existing `CallerIdentity.keycloakUserId` — **no** second identity call). `LocaleAuthBridge` (a
  `BlocListener<AuthCubit>` inside the `AuthGate` subtree) reaches the ancestor `LocaleCubit`:
  `applyForUser(sub)` on sign-in, reset + per-user clear on sign-out (DSGVO / AD-7). `AuthCubit.signOut` was
  left untouched — the bridge owns the locale side-effect.

**Deviations from the story spec (made explicit per CLAUDE.md §1):**
1. **`select(LocaleState)` takes no `userId`** (story Task 4 wrote `select(selection, {required userId})`).
   The picker sits below `MaterialApp`/`AuthCubit` and cannot be handed the `sub` through the provider tree;
   the cubit instead remembers `_currentUserId` from `applyForUser`. Cleaner (SRP) and avoids leaking the
   `sub` into the page/route.
2. **`resetForSignOut()` consolidates the story's `resetToDeviceDefault()` + `LocalePreferenceStore.clear(sub)`**
   into one cubit method, so the auth bridge needs no direct store reference (store stays encapsulated).
3. **`SystemLocale`/`ExplicitLocale` sealed state replaces a separate `LocaleState` wrapper + `LocaleSelection`.**
   A one-field wrapper would be pure ceremony (KISS); the sealed state doubles as the cubit state.
4. **Went with per-`sub` keying** (the story's primary spec), not the lighter single-key fallback — threading
   the `sub` proved small and gives the stronger DSGVO isolation AC1 asks for.

**Finding that strengthens AC2:** the story assumed `de-AT == de-DE` (both `1.234,5`). In `intl` 0.20.2 that is
false — `de-AT` groups with a **non-breaking space** (`1 234,5`), distinct from `de-DE`'s dot. So **all
three** regions are observably different, and tests assert each exact string (incl. `de-CH`'s U+2019).

### File List

**Added (production):**
- `app/lib/features/settings/data/locale_preference_store.dart`
- `app/lib/features/settings/presentation/locale_state.dart`
- `app/lib/features/settings/presentation/locale_cubit.dart`
- `app/lib/features/settings/presentation/locale_auth_bridge.dart`
- `app/lib/features/settings/presentation/locale_settings_page.dart`
- `app/lib/features/settings/supported_locales.dart`

**Added (tests):**
- `app/test/support/fake_settings_dependencies.dart`
- `app/test/features/settings/data/locale_preference_store_test.dart`
- `app/test/features/settings/presentation/locale_state_test.dart`
- `app/test/features/settings/presentation/locale_cubit_test.dart`
- `app/test/features/settings/presentation/locale_settings_page_test.dart`
- `app/test/features/settings/presentation/locale_auth_bridge_test.dart`
- `app/test/features/settings/locale_resolution_test.dart`

**Modified:**
- `app/lib/main.dart` — `LocaleCubit` above `MaterialApp`, region-qualified `supportedLocales`,
  `localeResolutionCallback`, date-symbol init for `de_AT`/`de_CH`.
- `app/lib/l10n/app_de.arb` — nine new „Sprache & Region" keys (heading, intro, four option labels,
  preview label, confirmation).
- `app/lib/features/auth/presentation/auth_state.dart` — carry `keycloakUserId` on `authenticated`.
- `app/lib/features/auth/presentation/auth_cubit.dart` — pass `identity.keycloakUserId` into the state.
- `app/lib/features/auth/presentation/auth_gate.dart` — wrap the authenticated subtree in `LocaleAuthBridge`.
- `app/test/app_test.dart` — assert the three region variants in `supportedLocales`.
- `app/test/features/auth/presentation/auth_cubit_test.dart` — expectations carry the `sub`.

_Note: `app/lib/l10n/gen/` is regenerated by `flutter gen-l10n` and gitignored — not committed._

## Change Log

| Date | Change |
| --- | --- |
| 2026-08-24 | Code review via bmad-code-review (three fresh-context Opus-4.8 layers). Verified `flutter analyze` clean + tests green. No high/medium findings; the one cross-layer „crash" was a false positive (intl falls back to the `de` base — verified empirically). Applied 3 low patches with regression tests: `LocaleCubit._safeEmit` guarding; no confirmation SnackBar on a no-op selection; `LocaleAuthBridge.listenWhen` also fires on a `sub` change (same-status account switch). 220 tests pass (3 new). Status → done. |
| 2026-08-24 | Implemented via bmad-dev-story. New `settings` feature: per-user `LocalePreferenceStore`, reactive `LocaleCubit` above `MaterialApp`, sealed `LocaleState`, „Sprache & Region" screen with live formatting preview, and an `AuthCubit`→locale bridge (apply-per-`sub` on sign-in, reset + clear on sign-out). Threaded the Keycloak `sub` through `AuthState.authenticated`; region-qualified `supportedLocales` + region-preserving `localeResolutionCallback`; date symbols initialized for `de_DE`/`de_AT`/`de_CH`. Found `de-AT` ≠ `de-DE` in `intl` 0.20.2 (non-breaking-space grouping) — all three regions observably differ, strengthening AC2. `flutter analyze` clean; 217 tests pass (28 new). Status → review. |
| 2026-08-24 | Story created via bmad-create-story. Client-only locale-override step on the Story 1.3 substrate: „Sprache & Region" screen (Systemstandard / de-DE / de-AT / de-CH region variants, German copy throughout, live formatting preview as the AC2 proof surface), a per-user on-device `LocalePreferenceStore`, and a reactive `LocaleCubit` above `MaterialApp` feeding `MaterialApp.locale` with an auth-lifecycle bridge (load per-sub on sign-in, clear + reset on sign-out). Three scoping forks resolved with Timo and recorded (region-variants / on-device per-sub / page-only-until-1.11). Provider-tree constraint (AuthCubit sits below MaterialApp) documented with a recommended bridge + lighter single-key fallback. Status → ready-for-dev. |
