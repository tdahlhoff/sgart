---
baseline_commit: 8f1e072d9eece8f82cf898702e5641d1e24ee4f1
---

# Story 1.3: Localization layer & de-DE formatting

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a user,
I want all text and formatting to come from a locale-driven layer,
so that the app is fully German today and translatable later without code changes.

## Acceptance Criteria

1. **Every user-facing string resolves through a key-based localization layer — none is hard-coded — and the app defaults to `de-DE`.** (FR13, CAP-13, AR10)
   - The Flutter app has a generated, ARB-backed localization pipeline (`flutter gen-l10n`) wired into `MaterialApp` via `localizationsDelegates` + `supportedLocales`, plus the `flutter_localizations` global Material/Widgets/Cupertino delegates.
   - No shipped widget contains a hard-coded user-facing string; every such string is a key looked up from the German catalog. The `// TODO(Story 1.3 localization)` placeholder strings currently in `home_page.dart` are migrated behind the layer and the TODO is removed.
   - Locale resolves from the device and falls back to `de-DE` when the device locale is unsupported; German is the shipped/primary catalog. (The in-app locale **selection UI** is out of scope — that is Story 1.10.)
2. **Currency, date, number, and quantity values are formatted per the active locale (`de-DE`), and no locale-formatted value is ever persisted.** (FR13, CAP-13, AR10, AD-9)
   - A locale-driven formatting layer (built on `intl`) formats: numbers (comma decimal, `1.234,5`), dates (from UTC/canonical input), currency (`Money`-shaped input → „1,09 €"), and quantity (`amount` + controlled `Unit` → e.g. „0,5 kg", „3 Stück").
   - Formatters are **display-only**: they take canonical input (UTC ISO-8601 instants, `Money` minor units, `Quantity` amount+unit) and return a display string. Nothing in the app stores a locale-formatted string; canonical values are what would be persisted/sent. (No persistence ships in this story — this is the binding direction the layer enforces.)
3. **A domain/application error reaching the client is shown as localized copy keyed by its `code`, never the raw `message`.** (AR10, Consistency Conventions)
   - The client has a model mirroring the shared `{ code, message, details }` error shape and a resolver that maps a `code` to a localized string from the catalog.
   - An **unknown** `code` resolves to a generic localized fallback message; the raw `message` field is never surfaced to the user (it is debug/log-only).

## Tasks / Subtasks

- [x] **Task 1 — Add the localization + formatting toolchain** (AC: #1, #2)
  - [x] Add `flutter_localizations` (`sdk: flutter`) and `intl` to `pubspec.yaml` dependencies. Let `flutter pub add intl` resolve the SDK-pinned version (Flutter 3.44.9 pins `intl ^0.20.x`); do **not** hand-pin a mismatched version — a mismatch breaks `gen-l10n`.
  - [x] Enable `flutter: generate: true` in `pubspec.yaml`.
  - [x] Add `l10n.yaml` at `app/` root: `arb-dir: lib/l10n`, `template-arb-file: app_de.arb`, `output-localization-file: app_localizations.dart`, `output-class: AppLocalizations`, `nullable-getter: false`.
- [x] **Task 2 — German string catalog (ARB)** (AC: #1, #3)
  - [x] Create `app/lib/l10n/app_de.arb` as the template + primary catalog. Keys in English (`@@locale: "de"`), values in German (per language policy: English keys/comments, German UI copy). Include `@`-metadata (`description`) for each key.
  - [x] Migrate the current placeholder copy: the home screen's „Scaffold ready", the „Probe" button label, the `probes: N` line (as a parameterized message with a plural/number placeholder), and the „Admin" status label demo string.
  - [x] Add the controlled-vocabulary **unit labels** (`Unit`: piece→„Stück", gram→„g", kilogram→„kg", millilitre→„ml", litre→„l", pack→„Pack") used by the quantity formatter.
  - [x] Add error-copy keys: a generic fallback (e.g. „Es ist ein Fehler aufgetreten. Bitte versuche es erneut.") plus any error codes that are genuinely established today (see Dev Notes — keep this set minimal; later stories add their own codes).
  - [x] Run `flutter gen-l10n` and confirm `AppLocalizations` generates under `.dart_tool/`. Do **not** commit generated output; add its path to `.gitignore` if not already ignored.
- [x] **Task 3 — Wire delegates into the app** (AC: #1)
  - [x] Update `lib/main.dart` `SgartApp`: add `localizationsDelegates: [AppLocalizations.delegate, GlobalMaterialLocalizations.delegate, GlobalWidgetsLocalizations.delegate, GlobalCupertinoLocalizations.delegate]` and `supportedLocales: AppLocalizations.supportedLocales`.
  - [x] Confirm the `de-DE` fallback path (e.g. `localeResolutionCallback` or relying on Flutter's default resolution against `supportedLocales`) so an unsupported device locale lands on German. Keep the `SgartTheme` light/dark + `ThemeMode.system` wiring intact.
  - [x] Call `intl` date-symbol initialization if the date formatter needs it for `de_DE` (`initializeDateFormatting('de_DE')` at boot) — verify with the date-format test whether it is required.
- [x] **Task 4 — Locale-driven formatting layer** (AC: #2)
  - [x] Create `lib/l10n/formatting/` with a small, single-responsibility formatter surface (e.g. `SgartFormatting` or discrete `MoneyFormatter` / `QuantityFormatter` / `DateFormatting` — one class per concern, no abbreviations). Each takes an explicit `Locale`/locale string (default `de_DE`) so it is unit-testable without a widget.
  - [x] **Number:** `NumberFormat.decimalPattern('de_DE')` → comma decimal, dot thousands.
  - [x] **Currency:** input = `Money`-shaped `(amountMinor, currencyCode)`; convert minor→major by the currency's fraction digits and format with `NumberFormat.currency(locale: 'de_DE', symbol: '€', decimalDigits: 2)` → „1,09 €". (Prices are post-MVP UI, but this formatter is the headline `de-DE` example named in FR13/CAP-13 — build and test it as infrastructure; do not build any price screen.)
  - [x] **Date:** input = UTC `DateTime`/ISO-8601 instant; format to a `de-DE` pattern (`DateFormat.yMd('de_DE')` and/or a day-month-time pattern as needed). Assert the formatter reads canonical UTC and never round-trips a formatted string back to storage.
  - [x] **Quantity:** input = `(amount, Unit)`; format the amount via the decimal formatter and append the localized unit label from the ARB (e.g. „0,5 kg", „3 Stück"). Mirror the backend `Unit` vocabulary as a Dart enum (`piece, gram, kilogram, millilitre, litre, pack`) — a single source the formatter maps to labels.
- [x] **Task 5 — Client error model + code→copy resolver** (AC: #3)
  - [x] Create a Dart model mirroring the shared `ErrorDescriptor` shape exactly — field names `code`, `message`, `details` (JSON-compatible for when REST endpoints arrive in Stories 1.4/1.5). Keep it a plain immutable value type; no HTTP/transport code (that is later stories).
  - [x] Create a resolver `localizedMessageForErrorCode(AppLocalizations, code)` (or an equivalent extension) that returns the catalog copy for a known `code` and the **generic fallback** for an unknown one. It must never return `ErrorDescriptor.message`.
  - [x] Document (code comment + Dev Notes) that the client `code`-copy catalog grows per feature story; this story establishes the mechanism + fallback, not an exhaustive code list.
- [x] **Task 6 — Consume the layer in the placeholder screen** (AC: #1, #2)
  - [x] Update `home_page.dart` to read every string via `AppLocalizations.of(context)` and remove the `TODO(Story 1.3 localization)` comment. Keep the `HomeCubit` probe behavior and the `probe-count` / `probe-button` `Key`s intact so the 1.1/1.2 home tests keep passing (update those tests deliberately if the visible copy changes).
  - [x] Format the live `probes: N` count through the number formatter (keeps the tabular-figures demo meaningful) and, if it reads naturally, show one formatted-value demo (a quantity or date) to prove the formatting layer renders end-to-end. Keep it minimal — no new screens.
- [x] **Task 7 — Tests** (AC: #1, #2, #3)
  - [x] **Localization wiring:** a widget test pumps `SgartApp` (or `HomePage` wrapped with `AppLocalizations.delegate` + `supportedLocales`) and asserts the German copy renders and comes from `AppLocalizations`, not a literal. Assert `de-DE` resolves and an unsupported locale falls back to German.
  - [x] **No-hard-coded-strings guard:** at minimum assert the migrated home strings resolve via `AppLocalizations`; optionally add a lightweight source-scan test asserting no shipped `lib/` widget contains a bare user-facing string literal (document the approach chosen).
  - [x] **Formatting (unit tests, no widgets):** number „1.234,5"; currency `Money(109, EUR)` → „1,09 €" (assert the exact string incl. the `de-DE` space before €); date from a fixed UTC instant → expected `de-DE` string; quantity `(0.5, kilogram)` → „0,5 kg" and `(3, piece)` → „3 Stück". Include a boundary (e.g. `Money(0, EUR)` → „0,00 €").
  - [x] **Display-only guarantee:** assert the formatting API is one-directional (canonical in → string out) — there is no formatter path that parses a German-formatted string for persistence.
  - [x] **Error resolver:** a known `code` → its localized copy; an unknown `code` → the generic fallback; the resolver never returns the raw `message`.
  - [x] `flutter analyze` clean; all tests pass. (Run locally: `export PATH="$PATH:/home/timo/tools/flutter/bin"; cd app && flutter analyze && flutter test`.)

## Dev Notes

### Scope & intent
**Client-only story (Flutter) — no backend change, no docker, no CQRS.** It delivers the locale-driven substrate every later screen consumes: the ARB localization pipeline, the `intl` formatting layer, and the client error-code→copy resolver. Because only the placeholder home screen exists so far, the actual translated content is small (the migrated placeholders + unit labels + a generic error message). The value is the **mechanism and conventions**, not volume of copy. Resist building screens, a settings/locale UI, or an HTTP/error interceptor (YAGNI — locale selection is Story 1.10; the REST layer and backend error mapping arrive with Stories 1.4/1.5).

**Deliberate scope boundaries (decisions taken while writing this story — see Clarifications):**
- **German only, single ARB.** Ship `app_de.arb` as template + primary; `supportedLocales = [de]`. CAP-13's "internationalization-ready" is satisfied by the pipeline itself (adding a language = drop in `app_en.arb`), and CAP-13 states "MVP ships German." A second ARB now would only invite untranslated-string drift (YAGNI).
- **Currency formatter is built, but no price UI.** DESIGN/Story 1.2 defer *prices* to post-MVP; AC2 and FR13 nonetheless name currency („1,09 €") as the canonical `de-DE` example. Resolution: build + unit-test the currency formatter as infrastructure; wire no price screen.
- **Backend untouched.** The shared `ErrorDescriptor` record already exists (`backend/.../shared/ErrorDescriptor.java`) and is the contract. The REST `@RestControllerAdvice` that serializes it to `{code,message,details}` JSON belongs with the first endpoints (Stories 1.4/1.5). This story builds only the **client** mirror + resolver, with JSON-compatible field names so it is wire-ready.

### Source of truth: ARCHITECTURE-SPINE Consistency Conventions (binding)
[Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md]
- **Dates & formatting (§ Conventions, line 140):** "Store UTC ISO-8601; format per user `Locale` client-side (de-DE → „1,09 €", comma decimal). **No locale-formatted values persisted.**" → drives AC2's display-only rule.
- **Error shape (line 141):** "`{ code, message, details }`. `code` = client-facing machine key → localized copy client-side; `message` = log/debug only, never shown to users." → drives AC3. Matches `ErrorDescriptor` verbatim.
- **Localization (line 142):** "No hard-coded user-facing strings; all via localization layer keyed by `Locale` (per-user, device-default → override → `de-DE` fallback). MVP ships German." → drives AC1. The **override** step is Story 1.10; this story does device-default → `de-DE` fallback.
- **AD-9 (Money & Quantity):** `Money` = integer minor units + ISO currency; `Quantity` = amount + `Unit` from a controlled vocabulary. The client formatters must accept these canonical shapes, not pre-divided floats or free-text units.
- **AR10 (line 104):** ties the above together as the enforced convention set; also `timestamps stored UTC ISO-8601, formatted client-side per Locale`.

### The shared contract already in the repo (read before mirroring)
- `backend/src/main/java/de/sgart/shared/ErrorDescriptor.java` — `record ErrorDescriptor(String code, String message, Map<String,Object> details)`; `code`/`message` non-null, `details` defaults to empty map. The Dart mirror must use the **same field names** (`code`, `message`, `details`) so it deserializes 1:1 when endpoints ship.
- `backend/src/main/java/de/sgart/shared/Money.java` — `record Money(long amountMinor, Currency currency)`; `Money.euro(minor)`. Client currency formatter converts `amountMinor` by the currency's fraction digits (EUR = 2) → major, then formats.
- `backend/src/main/java/de/sgart/shared/{Quantity,Unit}.java` — `Quantity(BigDecimal amount, Unit unit)`; `Unit { PIECE, GRAM, KILOGRAM, MILLILITRE, LITRE, PACK }`. Mirror the enum in Dart and map each to a localized label in the ARB.

### Previous-story intelligence (Stories 1.1 & 1.2 — done)
[Source: implementation-artifacts/1-2-design-system-theming-foundation.md]
- **Story 1.2 explicitly deferred localization to 1.3:** "*No localization delegates while the UI already ships German copy — deferred, Story 1.3 scope.*" This story closes that gap.
- **Existing files this story touches (UPDATE — read before editing):**
  - `app/lib/main.dart` — `SgartApp` sets `MaterialApp(theme/darkTheme/themeMode.system, home: HomePage)` and registers the bundled font licence. **Add** the localization delegates + `supportedLocales` here; preserve everything else (theme wiring, `registerBundledFontLicenses`).
  - `app/lib/features/home/presentation/home_page.dart` — carries the `TODO(Story 1.3 localization)` and four hard-coded strings („Scaffold ready", „Admin", `probes: $probeCount`, „Probe"). Migrate all four; keep the `probe-count` / `probe-button` `Key`s and the `HomeCubit` probe flow.
  - `app/pubspec.yaml` — add `flutter_localizations`, `intl`, and `generate: true`; keep the existing Inter `fonts:`/`assets:` blocks and deps intact.
  - `app/test/features/home/home_page_test.dart` — pumps `HomePage` through `SgartTheme`; it will now also need `AppLocalizations` delegates in the test harness (a shared test helper that wraps a widget with theme + localization is worth adding to `app/test/support/`). Update deliberately and note it in the Change Log.
- **Conventions from 1.1/1.2 (keep):** `flutter analyze` stays at "No issues found" (`flutter_lints`); LF line endings; feature-first with `lib/shared/` for cross-feature code; no hard-coded user-facing strings in shipped widgets (this story now provides the mechanism that makes that enforceable); tests assert behavior + exact values, not structure.
- **Local test-run reality:** the Flutter SDK is at `/home/timo/tools/flutter/bin` but not on PATH. Tests **do** run locally once PATH is set. Story 1.2 was once marked done on a review that never executed tests (6 were red); **run `flutter analyze` and `flutter test` for real before moving to review.** [See memory `flutter-test-local`.]

### Language policy (binding for this story especially)
[Source: memory `language-policy`] Docs, code, comments, and **ARB keys** are English; **ARB values (the UI copy) are German.** Error-code copy, unit labels, and all catalog values are German; the keys that address them are English (`homeProbeButtonLabel`, `errorGenericFallback`, `unitKilogram`, …).

### Latest tech notes (Flutter 3.44.9 / Dart 3.12.2)
- **gen-l10n toolchain:** `flutter gen-l10n` reads `l10n.yaml` + ARB files and generates `AppLocalizations` into `.dart_tool/flutter_gen/` (import `package:flutter_gen/gen_l10n/app_localizations.dart` or the configured output). With `generate: true`, a normal `flutter pub get` / build regenerates it — generated files are **not** committed.
- **`intl` version coupling:** `flutter_localizations` pins a specific `intl` (≈`0.20.2` for this SDK). Add `intl` via `flutter pub add intl` so the resolver picks the compatible version; a hand-pinned mismatch fails `pub get`.
- **Date symbols:** `intl`'s `DateFormat` uses its own locale data. If a `de_DE` date test throws `LocaleDataException`, call `initializeDateFormatting('de_DE', null)` at app boot (and in the test `setUp`). `NumberFormat` for `de_DE` generally works without extra init, but verify in tests.
- **`de-DE` currency spacing:** `intl` renders the euro symbol after the amount with a narrow/regular space („1,09 €"). Assert the produced string exactly (copy the actual separator) rather than eyeballing it.

### Project Structure Notes
```text
app/l10n.yaml                          # gen-l10n config (new, app root)
app/lib/l10n/
  app_de.arb                           # template + primary German catalog (new)
  formatting/
    money_formatter.dart               # Money(minor,currency) -> "1,09 €"
    quantity_formatter.dart            # (amount, Unit) -> "0,5 kg"
    date_formatting.dart               # UTC instant -> de-DE date string
    number_formatting.dart             # de-DE number (or fold into the above)
app/lib/shared/errors/
  app_error.dart                       # Dart mirror of {code,message,details}
  error_message_resolver.dart          # code -> localized copy, generic fallback
app/test/l10n/…                        # formatting + wiring tests
app/test/shared/errors/…              # resolver tests
app/test/support/…                     # widget test helper (theme + localization)
```
- Put the ARB + formatters under `lib/l10n/` (localization is a cross-cutting concern like `lib/theme/`), and the error model/resolver under `lib/shared/errors/` (it is shared vocabulary, not a feature).
- One class per concern (SRP, CLAUDE.md §1); no abbreviations in names (CLAUDE.md §2) — `quantity`, `currency`, `formatter`, not `qty`/`fmt`.

### Testing standards
[Source: CLAUDE.md §6]
- Fast widget/unit tests, no infra. Formatters are pure → plain unit tests with an explicit locale, no `WidgetTester` needed.
- Test **behavior/outcomes**: exact formatted strings and resolved copy, not internal structure. Name tests as full behavioral sentences (`formatsEuroAmountWithGermanCommaDecimal`, `unknownErrorCodeResolvesToGenericFallback`).
- **DSGVO:** use only synthetic data in fixtures (fake amounts/dates); never real personal data. `ErrorDescriptor.details` must carry no personal data — keep test fixtures clean of it.
- Keep the 1.1/1.2 client tests green; update the home test harness intentionally (localization delegates now required) and log it.

### References
- [Source: epics.md#Story 1.3: Localization layer & de-DE formatting] — user story + ACs (lines 280–298)
- [Source: epics.md#AR10] (line 104) and [#UX-DR2] (line 113) — convention IDs the ACs realize
- [Source: specs/spec-sgart/SPEC.md#CAP-13] (lines 71–73) — canonical success criteria
- [Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md] lines 140–142, AD-9, AR10 — dates/format, error shape, localization conventions
- [Source: backend/src/main/java/de/sgart/shared/{ErrorDescriptor,Money,Quantity,Unit}.java] — canonical contracts the client mirrors
- [Source: implementation-artifacts/1-2-design-system-theming-foundation.md] — deferred-localization item, files to update, test-harness pattern
- [Source: CLAUDE.md] — Clean Code, naming (no abbreviations), DDD/CQRS scope, testing & DSGVO rules
- [Source: memory `language-policy`, `flutter-test-local`] — English keys/German values; how to run tests locally

## Clarifications (raised for Timo — sensible defaults already chosen in the story)

1. **Single German ARB vs. also scaffolding `app_en.arb`.** Chosen: German-only, pipeline generic (recommended — YAGNI, avoids untranslated drift, still proves i18n-readiness). Flag: if you want a *visible* second locale to demonstrate readiness in a demo, adding a token `app_en.arb` is a small follow-up.
2. **Currency formatter now.** Chosen: build + unit-test it (AC2/FR13 name it as the headline `de-DE` example) but wire no price UI (prices post-MVP). Confirm you're happy formatting infra lands ahead of its first screen.
3. **Error-code catalog seeding.** Chosen: ship the resolver + generic fallback + only genuinely-established codes; later feature stories add their own `code` copy. Confirm you don't want a larger up-front code list (would risk inventing codes no endpoint emits yet — YAGNI).

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `flutter pub add intl` initially resolved `intl 0.20.3`, which conflicted once `flutter_localizations` (pinning `intl 0.20.2`) was added; re-running `flutter pub add intl` after adding `flutter_localizations` let pub resolve the SDK-pinned `0.20.2` — matches the story's documented `intl`/SDK coupling risk.
- `flutter gen-l10n`'s synthetic-package mode is deprecated in Flutter 3.44.9 and cannot be enabled — output always writes into the real project tree. Set `output-dir: lib/l10n/gen` in `l10n.yaml` and added `/lib/l10n/gen/` to `.gitignore` so generated `AppLocalizations` code stays out of version control (satisfies the "do not commit generated output" instruction under this SDK's constraints).
- `DateFormat` for `de_DE` threw `LocaleDataException` until `initializeDateFormatting('de_DE')` was awaited at app boot (`main()`) and in test `setUpAll` — confirms the story's flagged risk; documented in `main.dart`.
- `NumberFormat.currency(locale: 'de_DE', ...)` separates the amount from `€` with a non-breaking space (U+00A0), not a regular space; asserted the exact character in `money_formatter_test.dart` per the story's explicit instruction not to eyeball it.

### Completion Notes List

- Added the ARB/gen-l10n pipeline (`l10n.yaml`, `lib/l10n/app_de.arb`, `flutter_localizations` + `intl` deps, `generate: true`) and wired the generated `AppLocalizations` delegates + `supportedLocales` into `SgartApp` (`main.dart`). Locale falls back to `de-DE` via Flutter's default resolution (single-locale `supportedLocales` list) — verified by a widget test that sets the device locale to `fr` and asserts German copy still renders.
- Built the `de-DE` formatting layer under `lib/l10n/formatting/`: `NumberFormatting`, `MoneyFormatter` (+ `Money` value type, EUR-only, fails fast on any other currency code), `DateFormatting` (UTC-in, de-DE string out, rejects non-UTC input), `QuantityFormatter` (+ `Unit` enum mirroring the backend vocabulary, unit labels sourced from the ARB catalog). All are pure, locale-parameterised classes — unit-tested without a widget tree via `AppLocalizationsDe()`.
- Added the client error model: `AppError` (mirrors `ErrorDescriptor` field-for-field: `code`, `message`, `details`) and `localizedMessageForErrorCode` — today this always returns `errorGenericFallback` since no error codes are established anywhere in the backend yet (grepped `ErrorDescriptor.of`/`new ErrorDescriptor` call sites — none found); the resolver signature is ready for a `switch` over known codes as feature stories add them.
- Migrated `home_page.dart` off every hard-coded string (removed the `TODO(Story 1.3 localization)`), including a new minimal formatted-quantity demo line (`formatting-demo` key) proving the formatting layer renders end-to-end; kept the `probe-count`/`probe-button` keys and `HomeCubit` flow unchanged. The `probes: N` count now renders through the generated `homeProbeCountLabel(count)` accessor, which itself runs the value through `intl`'s decimal formatter.
- Left the `"SGART"` app/brand name hard-coded in `main.dart` and `home_page.dart` — treated as a proper noun, not translatable UI copy (same treatment as `MaterialApp.title`); documented inline. Grepped `lib/` for any other `Text(`/`label:` string literals after the migration — none remain outside the brand name and generated code.
- Chose not to add a source-scan "no hard-coded strings" test (the story's optional alternative): the AC1 guard is instead a widget test asserting the migrated home-screen strings render and resolve through `AppLocalizations`, plus a manual `grep` sweep recorded here — a source scanner would need to special-case the brand name and generated code and risked speculative complexity (YAGNI) for a small, fully-reviewed screen.
- Added `test/support/widget_test_harness.dart` (`wrapForTesting`) so widget tests pump through the app's real theme + localization delegates, matching the Story 1.2 test-harness suggestion; updated `app_test.dart` and `home_page_test.dart` to use it and to assert on the German ARB copy.
- `flutter analyze`: no issues. `flutter test`: 80/80 passing (includes the pre-existing 1.1/1.2 suites, updated for the new German strings and localization delegates).

### File List

**New**
- `app/l10n.yaml`
- `app/lib/l10n/app_de.arb`
- `app/lib/l10n/formatting/number_formatter.dart`
- `app/lib/l10n/formatting/money_formatter.dart`
- `app/lib/l10n/formatting/date_formatter.dart`
- `app/lib/l10n/formatting/quantity_formatter.dart`
- `app/lib/shared/errors/app_error.dart`
- `app/lib/shared/errors/error_message_resolver.dart`
- `app/test/support/widget_test_harness.dart`
- `app/test/l10n/formatting/number_formatter_test.dart`
- `app/test/l10n/formatting/money_formatter_test.dart`
- `app/test/l10n/formatting/date_formatter_test.dart`
- `app/test/l10n/formatting/quantity_formatter_test.dart`
- `app/test/l10n/formatting/display_only_guarantee_test.dart`
- `app/test/shared/errors/app_error_test.dart`
- `app/test/shared/errors/error_message_resolver_test.dart`

**Modified**
- `app/pubspec.yaml` — added `flutter_localizations`, `intl`, `generate: true`.
- `app/.gitignore` — added `/lib/l10n/gen/` (generated `AppLocalizations`, not committed).
- `app/lib/main.dart` — wired localization delegates + `supportedLocales`; added `initializeDateFormatting('de_DE')` at boot.
- `app/lib/features/home/presentation/home_page.dart` — migrated all strings behind `AppLocalizations`, removed the Story 1.3 TODO, added the formatting-demo line.
- `app/test/app_test.dart` — added a localization group (German copy renders; unsupported device locale falls back to `de-DE`).
- `app/test/features/home/home_page_test.dart` — pumped through the new test harness; assertions updated to the German ARB copy; added AppLocalizations-guard and locale-fallback tests.

**Not committed (generated, gitignored)**
- `app/lib/l10n/gen/app_localizations.dart`, `app/lib/l10n/gen/app_localizations_de.dart` — regenerated by `flutter pub get` / `flutter gen-l10n` via `generate: true`.

## Change Log

| Date | Change |
| --- | --- |
| 2026-08-23 | Story created via bmad-create-story. Client-only localization + `de-DE` formatting substrate: ARB/gen-l10n pipeline, `intl` formatting layer (number/currency/date/quantity), client error-code→copy resolver mirroring the shared `ErrorDescriptor`. Scope boundaries recorded (German-only ARB; currency infra without price UI; backend untouched — REST error mapping deferred to 1.4/1.5; locale selection UI deferred to 1.10). Status → ready-for-dev. |
| 2026-08-23 | Implemented all 7 tasks: gen-l10n/ARB pipeline, `intl` formatting layer (number/currency/date/quantity), client `AppError` + error resolver, home screen migrated off hard-coded strings, and full test coverage (localization wiring, de-DE fallback, formatter exact-string assertions, display-only guarantee, error resolver). `flutter analyze` clean; 80/80 tests pass. Status → review. |
| 2026-08-23 | Code-review fixes: (1) `DateFormatter` now converts the UTC instant to the device-local zone (`toLocal()`) before formatting so users see their own wall-clock, not UTC — added a regression test proving local rendering; (2) renamed `NumberFormatting`/`DateFormatting` → `NumberFormatter`/`DateFormatter` (+ files/tests) for naming consistency with `MoneyFormatter`/`QuantityFormatter` (CLAUDE.md §2); (3) `homeProbeCountLabel` German copy „probes: {count}" → „Proben: {count}" (language policy: German UI values); (4) `AppError` now has value equality (`==`/`hashCode`). `flutter analyze` clean; 83/83 tests pass. |
