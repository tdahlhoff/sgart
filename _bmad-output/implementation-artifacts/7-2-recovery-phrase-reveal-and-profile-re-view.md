---
baseline_commit: 3d101fd7c4b7ed07313bff611154a76501f74839
---

# Story 7.2: Recovery phrase reveal and profile re-view

Status: done

<!-- Note: Validation is optional. Run validate-create-story for a quality check before dev-story. -->

## Story

As a person with a silently provisioned account,
I want a way to see, save, and re-enter my recovery phrase,
so that I can use SGART on another device or after reinstalling — and understand what I'd lose if I don't save it.

This is **Epic 7's second build story**. Story 7.1 already built the whole credential model — the
256-bit device secret in the secure enclave, its BIP39 24-word encoding (`RecoveryPhrase`), the
derived Ed25519 keypair/username, and the browserless Direct-Grant sign-in (`DirectGrantOidcClient`)
— but deliberately **wired none of it to any UI** ("No phrase UI in this story — 7.2 owns reveal").
7.2 is that UI layer plus the one missing primitive: turning a typed phrase back into the device
secret. It is an **almost entirely client-side (Flutter) story** — no new backend event, command,
endpoint, migration, or Keycloak change. Recovery signs in to an **existing** Keycloak account via
the Story 7.1 mechanism; the provisioning call on that path is an idempotent no-op (7.1 AC3).

It does **not** attach an email or build the emailed-code fallback (that is Story 7.3) and does
**not** capture consent (that is Story 7.4). Keep those decoupled.

## Context — what already exists vs. what 7.2 adds

**Already built in 7.1 (reuse verbatim, do NOT recreate):**
- `RecoveryPhrase.wordsFromEntropy(Uint8List) → List<String>` (`app/lib/features/auth/data/recovery_phrase.dart`)
  — the entropy → 24-word BIP39 encoder, already unit-tested (`recovery_phrase_test.dart`). Pure, no
  storage/UI dependency, built precisely so 7.2 can call it.
- `DeviceCredential` / `SecureEnclaveDeviceCredentialStore` / `DeviceCredentialStore` — the 256-bit
  entropy held in `flutter_secure_storage` under key `sgart.auth.deviceEntropy`, derived to the
  Ed25519 keypair. The entropy **is** the seed **is** the phrase source — one secret, three encodings.
- `DirectGrantOidcClient.signIn()` — `loadOrCreate()` the credential → idempotent `provision()` →
  device-signed challenge sign-in. Reused as-is by recovery once the imported entropy is stored.
- `AuthCubit` (`bootstrap`/`signIn`/`tryRefreshTokens`) + the `AuthGate` state machine: an
  `inProgress → authenticated` transition re-mounts `FirstRunRouter`, which re-creates
  `HouseholdsCubit` and re-bootstraps households for whatever identity is now signed in. **This is the
  seam that makes an identity swap on recovery Just Work** — no bespoke re-routing needed.
- `ActiveHouseholdStore.clear()` — kept in 7.1 explicitly "for a future identity switch on the same
  device (e.g. Story 7.2/7.3's recovery-phrase import)" (see its doc comment). 7.2 is that caller.
- `HouseholdsCubit` already tolerates a stale active-household id (a stored id not in the fetched list
  → `firstWhereOrNull` → null → routes normally), so a leftover selection from a throwaway account can
  never strand the recovered identity.
- `ProfileScreen` (Story 1.11, `features/settings/presentation/profile_screen.dart`), the
  `CreateOrAwaitChoicePage` (Story 1.6/4.2, `features/households/presentation/`), `SgartButton`,
  `SgartAppBar`, shape/spacing tokens, and the `app_de.arb` l10n pipeline.
- Deps already present: `bip39: ^1.0.6` (has `validateMnemonic` + `mnemonicToEntropy`, the inverse
  7.2 needs — verified), `cryptography: ^2.9.0`, `flutter_secure_storage: ^11.0.0`.

**7.2 adds (all client-side):**
- The inverse primitive: `RecoveryPhrase.entropyFromWords(List<String>) → Uint8List` (BIP39
  checksum-validated; throws a typed error on an invalid phrase — fail fast, CLAUDE.md §1).
- Two `DeviceCredentialStore` methods: `recoveryPhrase()` (read entropy → words; **raw entropy never
  leaves the store layer**) and `restoreFromPhrase(List<String>)` (validate + words → entropy + write
  to the enclave, replacing the current entropy).
- Lift the credential store from the `const` buried in `DirectGrantOidcClient` (auth_gate.dart:49) to
  a **single provided `DeviceCredentialStore`** the reveal UI, the recovery flow, and the OIDC client
  all share.
- A reusable **`RecoveryPhraseRevealPage`** (24 numbered words + the "lose this = unrecoverable"
  warning), reached from two entry points (choice screen + Profil) — DRY.
- A **recovery-phrase "save" action on `CreateOrAwaitChoicePage`** opening the reveal page.
- A **"Wiederherstellungsphrase" row in `ProfileScreen`** opening the same reveal page.
- A **`RecoverAccountPage`** (24-word entry form) reached from a third action on the choice screen,
  plus `AuthCubit.recoverFromPhrase(words)` orchestrating import → sign-in → clear active household →
  load identity.
- New `app_de.arb` strings (German UI, per the project language policy) + a11y labels.

## Locked Decisions (Timo, 2026-09-14)

- **D-A: reveal lives on the choice screen, not a one-time modal at first-household create/join.**
  This consciously supersedes `sprint-change-proposal-2026-09-13.md` §5 decision 3 ("reveal at first
  household creation/join"). `CreateOrAwaitChoicePage` is the **guaranteed gateway** for any
  0-household person — both "create" and "join" start there — so surfacing the phrase there covers the
  "first create *or* join" intent **without** a shell-transition hook or a one-time "revealed" flag
  (simpler than the proposal). Accepted tradeoff: it appears before the person has household data to
  protect (the proposal waited so the warning would land harder); mitigated by D-C — the phrase stays
  permanently re-viewable in Profil.
- **D-B: reveal is behind a button → a dedicated reveal page, not the 24 words rendered inline** on
  the choice screen. Keeps recoverable-account words off the always-visible first screen
  (shoulder-surfing), and the reveal page is shared verbatim with Profil's re-view (DRY).
- **D-C: re-view lives in `ProfileScreen`** (Story 1.11), opening the same `RecoveryPhraseRevealPage`.
- **D-D: recovery entry lives as a third, quiet action on `CreateOrAwaitChoicePage`.** A reinstalling
  person is silently auto-provisioned into a fresh empty account (7.1) and lands on the choice screen;
  Profil is unreachable with 0 households (it is a tab inside the household shell). The choice screen
  is therefore the **only** place a recovery entry can live for a 0-household person. This supersedes
  the proposal AC3's stale "on the sign-in screen" wording — 7.1 turned sign-in into a zero-input
  loading screen with no field.
- **D-E: recovery is an identity swap through the existing `AuthGate` state machine.** On a valid
  phrase, `AuthCubit.recoverFromPhrase` imports the entropy, then reuses `signIn()`
  (`loadOrCreate` now loads the imported entropy → re-derives the same username → signs in to the
  existing account; `provision()` is an idempotent no-op, 7.1 AC3). Going `inProgress → authenticated`
  re-mounts `FirstRunRouter` → `HouseholdsCubit` re-bootstraps for the recovered identity. The
  active-household selection is cleared (`ActiveHouseholdStore.clear()`, AD-7) so the recovered
  identity never inherits the throwaway's selection.
- **D-F: the throwaway account is left to 7.1's retention sweep.** The empty account silently
  provisioned on this reinstall is never activated → deleted after 14 days by 7.1's sweep (AC5). 7.2
  does **no** extra deletion. (Self-healing corollary: if a phrase's account was already swept —
  0 households + >14 days — the idempotent `provision()` on the recovery path simply re-creates an
  empty account under the same phrase; harmless.)
- **D-G: no clipboard "copy phrase" action.** Manual transcription of the numbered words only —
  copying a recovery secret to the shared clipboard is readable by other apps (security default,
  KISS). (Open to revisit — see Questions.)
- **D-H: client-only.** No backend/Keycloak change. Per CLAUDE.md §6 the green build names the module
  the change touches: `flutter test` + `flutter analyze`. Backend is untouched (mirrors the
  client-only Story 3.5 precedent).

## Acceptance Criteria

Derived from `sprint-change-proposal-2026-09-13.md` §4 (Story 7.2) with the locked decisions applied.

1. **AC1 — Reveal & save from the choice screen.** Given a person on `CreateOrAwaitChoicePage`
   (0 households, so a fresh install or a not-yet-joined reinstall), when they tap the
   "recovery phrase" save action, then a `RecoveryPhraseRevealPage` shows the **24-word BIP39 phrase**
   derived from the device's current stored entropy (via `RecoveryPhrase.wordsFromEntropy`), numbered
   for transcription, with an **explicit warning that losing the phrase means the account and its
   household data are unrecoverable**. The phrase is read from the device's **secure local storage
   only — never fetched from the server** — and no browser surface is shown.

2. **AC2 — Re-view from Profil, unlimited times.** Given the Profil screen (Story 1.11), when a person
   opens it, then a "Wiederherstellungsphrase" row is present that opens the **same**
   `RecoveryPhraseRevealPage` (AC1), on demand, any number of times — read from secure local storage,
   never re-fetched from the server.

3. **AC3 — Restore an existing account by entering the phrase.** Given the recovery action on
   `CreateOrAwaitChoicePage`, when a person enters a 24-word phrase and submits:
   - an **invalid** phrase (wrong word count, unknown word, or failed BIP39 checksum) is **rejected
     fast** with a clear, plain-German message and **changes nothing** (no enclave write, no sign-in
     attempt);
   - a **valid** phrase causes the app to reconstruct the 32-byte entropy, store it in the secure
     enclave (**replacing** the throwaway entropy), clear the active-household selection (AD-7), and
     re-sign-in via the Story 7.1 Direct-Grant mechanism — re-deriving the **same** username/keypair
     so it lands in the **existing** account's households — with **pure native UI and no browser**.
   After a successful restore the app shows the recovered identity's households (0 → create/await
   choice · 1 → shell · ≥2 → selection), via the existing `AuthGate → FirstRunRouter` re-mount.

4. **AC4 — Nothing else opens up; secret never leaves the device.** Given the reveal and recovery
   paths, when they run, then (a) no network request carries the entropy, the private key, or the
   phrase (the reveal is a pure local read; recovery sends only the Story 7.1 public key + signed
   challenge), and (b) the raw entropy bytes never leave the `DeviceCredentialStore` layer — the UI
   and cubits deal only in `List<String>` words.

5. **AC5 — Green build across the touched module + dependency currency.** Given the change is
   client-only, when the story completes, then app `flutter test` **and** `flutter analyze` are named
   and green; the backend suite is unaffected (no backend change). No new dependency is required
   (`bip39`/`cryptography`/`flutter_secure_storage` already present); if any is bumped, it is pinned to
   its current supported major (CLAUDE.md §7).

## Tasks / Subtasks

### Credential primitives (`features/auth/data`) — AC3, AC4

- [x] `RecoveryPhrase.entropyFromWords(List<String> words) → Uint8List`: join words, `validateMnemonic`
      (reject on false), `mnemonicToEntropy` → hex → 32 bytes. Throw a typed `InvalidRecoveryPhrase`
      (new small error type) on any invalid input — fail fast, no partial state. Unit-test both
      directions round-trip (`entropyFromWords(wordsFromEntropy(e)) == e`) and each rejection case.
- [x] `DeviceCredentialStore` (port) gains `Future<List<String>> recoveryPhrase()` and
      `Future<void> restoreFromPhrase(List<String> words)`. Implement in
      `SecureEnclaveDeviceCredentialStore`: `recoveryPhrase()` reads the stored entropy (must exist by
      this point — 7.1 generates it at first launch) and returns `RecoveryPhrase.wordsFromEntropy`;
      `restoreFromPhrase` computes `entropyFromWords` (propagating `InvalidRecoveryPhrase`) and writes
      it to `_entropyKey`, **replacing** the current value. **Raw entropy never leaves the store.**
- [x] Update the in-test fake `DeviceCredentialStore` (see `test/support/`) with the two new methods.

### DI: one shared credential store — AC1, AC2, AC3

- [x] Lift `SecureEnclaveDeviceCredentialStore` out of the `const` inside `DirectGrantOidcClient`
      (auth_gate.dart:49): construct it once in `AuthGate._buildAuthCubit`, inject it into both
      `DirectGrantOidcClient` and `AuthCubit`, and provide it via a `RepositoryProvider<DeviceCredentialStore>`
      reachable by `FirstRunRouter`'s subtree (choice screen + Profil). (The store is stateless over
      fixed keys, so correctness does not depend on a single instance — but one provided instance keeps
      the wiring honest and tests injectable, CLAUDE.md §6.)

### Reveal UI (shared page + two entry points) — AC1, AC2

- [x] `RecoveryPhraseRevealPage`: loads `store.recoveryPhrase()`, renders the 24 words **numbered**
      (a wrap/grid legible at large text scaling, UX-DR5), plus the unrecoverable-loss warning copy.
      No clipboard action (D-G). a11y labels; honors `MediaQuery.textScaler`; theme-token styling.
- [x] `CreateOrAwaitChoicePage`: add a quiet "Wiederherstellungsphrase sichern" action (tonal/
      secondary, non-competing with the two primary choices) opening `RecoveryPhraseRevealPage`
      (re-provide `DeviceCredentialStore` across the `Navigator.push` boundary, per the Story 1.6
      provider-escape lesson already documented in that file's `_openOnboarding`).
- [x] `ProfileScreen`: add a "Wiederherstellungsphrase" `ListTile` (mirroring the existing locale row)
      opening the same page.

### Recovery flow (import → sign-in → swap) — AC3

- [x] `AuthCubit.recoverFromPhrase(List<String> words)`: emit `inProgress`; `store.restoreFromPhrase(words)`
      (on `InvalidRecoveryPhrase` emit a **distinct** failure `AppError` code, e.g.
      `auth.invalidRecoveryPhrase`, so the UI shows "phrase invalid" not a generic error, and stop —
      no sign-in); on success reuse the existing `signIn()` body (or call `signIn()`), then
      `activeHouseholdStore.clear()` (AD-7) before/after `_loadCallerIdentity` as appropriate. Inject
      `DeviceCredentialStore` + `ActiveHouseholdStore` into `AuthCubit` (the latter is the
      7.1-anticipated `clear()` caller). Update `AuthCubit`'s test fakes/constructors accordingly.
- [x] `RecoverAccountPage`: a 24-word entry form (single multiline field or word chips — dev's call;
      keep it forgiving: trim, lowercase, collapse whitespace before validating), a submit button
      wired to `AuthCubit.recoverFromPhrase`, an inline error for the invalid-phrase code, and a
      loading state during sign-in. Reached from a third quiet action on `CreateOrAwaitChoicePage`
      ("Konto wiederherstellen"). No browser.
- [x] Confirm the swap routes correctly: after `recoverFromPhrase` succeeds, `AuthGateBody` transitions
      `inProgress → authenticated` and re-mounts `FirstRunRouter`, which re-bootstraps `HouseholdsCubit`
      for the recovered identity (a widget test drives this end-to-end with fakes).

### l10n, a11y, full build — AC1, AC2, AC3, AC5

- [x] Add `app_de.arb` keys for: the save action, the reveal page title + warning, the Profil row, the
      recovery action, the recovery page title/field/hint, and the invalid-phrase error. Regenerate
      l10n. No hard-coded strings; a11y labels on every new interactive widget.
- [x] Run and name green: app `flutter test` **and** `flutter analyze`. (Backend untouched — no
      `./gradlew` run required for this story; state that explicitly at completion.)

### Definition of Done (standing, per retros)

- [x] No dead strings/fields/stale comments; the lifted store leaves no orphaned `const`.
- [x] Fail-fast on invalid phrase input; a11y labels on the reveal grid, the Profil row, and the
      recovery form; forgiving input normalization (trim/case/whitespace) before validation.
- [x] `commandId`/`basedOnVersion` N/A (client-only; no event-sourced command).
- [x] Optimistic-state DoD (Epic-2 action): the recovery identity swap must leave no stale state from
      the throwaway account visible (active household cleared; households re-fetched) — verified in a
      widget test, not left to review.
- [x] The touched suite named at completion; a red build blocks (CLAUDE.md §6).

### Review Findings (code review 2026-09-14, Opus 4.8)

- [x] [Review][Patch] **Invalid/empty phrase entry tears down the authenticated session — contradicts AC3 "changes nothing"** (HIGH — resolved to PATCH: isolate the error) — `recoverFromPhrase` emits `AuthState.inProgress()` *unconditionally* before validating (auth_cubit.dart:107), then `failure` on `InvalidRecoveryPhrase`. Because `AuthCubit` is the app-wide auth state, a typo (very likely with 24 hand-typed words) or an empty field drives the global cubit `authenticated(throwaway) → inProgress → failure` — the story's own `auth_gate_body_test` proves `inProgress` unmounts the authenticated subtree. The pushed `RecoverAccountPage` shows its inline error correctly and stays open, but the *underlying* choice-screen session is gone; popping back lands the user on the failure/sign-in gate, not `CreateOrAwaitChoicePage`. Recoverable only by tapping retry (re-provisions the throwaway, since the enclave was untouched). AC3 says an invalid phrase "changes nothing (no enclave write, no sign-in attempt)" — true of the enclave/network, false of the global auth state and navigation. **Note: the Tasks bullet ("emit `inProgress`; on `InvalidRecoveryPhrase` emit a distinct failure") prescribes exactly this mechanic, so the contradiction is between the task step and AC3 — a human call.** Sources: blind-hunter + acceptance-auditor + edge-case-hunter. [app/lib/features/auth/presentation/auth_cubit.dart:106]
- [x] [Review][Defer] **Reveal screen displays the 24-word master secret with no screen-capture/recording protection** (MEDIUM) [app/lib/features/auth/presentation/recovery_phrase_reveal_page.dart:1] — `RecoveryPhraseRevealPage` renders full account-recovery material as plain text with no `FLAG_SECURE` (Android) / capture guard (CLAUDE.md §5 "security by design & by default"). Source: blind-hunter. — deferred: adds a platform channel/package — track as focused security hardening across all secret-display surfaces.

- [x] [Review][Patch] recoverFromPhrase handles only `InvalidRecoveryPhrase`; other failures hang at `inProgress` or show no feedback [app/lib/features/auth/presentation/auth_cubit.dart:106] — a non-`InvalidRecoveryPhrase` error from `restoreFromPhrase` (secure-storage write) or `_activeHouseholdStore.clear()` escapes uncaught, leaving the UI pinned at `inProgress` (submit disabled, no error). A caught generic `signIn()` failure after a valid restore (e.g. server unreachable) emits `failure` with a non-`auth.invalidRecoveryPhrase` code, which `RecoverAccountPage` renders no message for → the user sees a silently reset form. Mirror `signIn`'s own `on Object catch` for the restore/clear, and widen the page's error display beyond the invalid-phrase code. Sources: edge-case-hunter + blind-hunter + acceptance-auditor.
- [x] [Review][Patch] DRY: `_openRecoveryPhraseReveal` duplicated verbatim across two screens [app/lib/features/households/presentation/create_or_await_choice_page.dart:317] — the identical read-store-and-push method (plus its comment) also lives in `profile_screen.dart:60`. Extract a top-level `openRecoveryPhraseRevealPage(context)` beside the page, mirroring the existing `openAwaitInvitePage` precedent (CLAUDE.md §1 DRY, §8). The two copies will drift (e.g. if the provider-escape wrapping changes). Source: blind-hunter.
- [x] [Review][Patch] No test for the `auth.invalidRecoveryPhrase` → `recoveryPhraseRestoreInvalidPhraseError` resolver mapping [app/test/shared/errors/error_message_resolver_test.dart:78] — every sibling code has its own test asserting it resolves to its own copy and not `errorGenericFallback`; this one has none, and `recover_account_page_test` asserts only key-presence, never the rendered text. A deleted/mistyped switch arm ships green showing the generic fallback. Add one case mirroring `authUnauthorizedResolvesToTheSessionExpiredCopyNotTheGenericFallback`. Source: verification-gap (pre-verified).
- [x] [Review][Patch] `RecoveryPhraseRevealPage` FutureBuilder has no `hasError` branch [app/lib/features/auth/presentation/recovery_phrase_reveal_page.dart:40] — `snapshot.data == null` is treated as "loading", so a `recoveryPhrase()` read failure shows the spinner forever and swallows the error (fail-fast, CLAUDE.md §1). Low likelihood but on the secret-display screen; add a `hasError` branch. Sources: edge-case-hunter + blind-hunter.
- [x] [Review][Patch] a11y labels claimed in DoD are not explicitly present (LOW) [app/lib/features/auth/presentation/recovery_phrase_reveal_page.dart:1] — no `Semantics`/`semanticsLabel` on the reveal grid (`_NumberedWord` is a bare `Container`+`Text`), the warning, or the new Profil row; the DoD checkbox for "a11y labels on the reveal grid, the Profil row, and the recovery form" is marked done. Screens are functionally readable via default text/`ListTile`/`labelText` semantics, so this is a low polish gap, not inaccessibility. Source: acceptance-auditor.

- [x] [Review][Defer] AuthGate composition-root wiring is untested [app/lib/features/auth/presentation/auth_gate.dart:26] — deferred: the real `AuthGate` (which now provides `RepositoryProvider<DeviceCredentialStore>` + wires `SharedPreferencesActiveHouseholdStore` into the cubit) is never pumped; each consumer test injects its own provider, so dropping the wrapper would crash the recovery entry points at runtime with a green suite. Not cheaply widget-testable — `AuthGate.build()` constructs a real Dio/HTTP stack, the enclave store, and calls `bootstrap()`. Source: verification-gap (pre-verified).

**Rejected**

- (false) `recoverFromPhrase` never pops if the recovered identity is value-equal to the current one (edge-case-hunter) — the unconditional `inProgress` emit always intervenes, so the trailing `authenticated` is always a distinct state change from `inProgress`; `listenWhen` fires and the page pops.
- (false) Two independent `SharedPreferencesActiveHouseholdStore` instances could desync (blind-hunter) — the store is stateless (`SharedPreferences.getInstance()` per call, no in-memory cache), so `clear()` on the cubit's instance is visible to the households feature's instance; the bad outcome cannot occur.
- (false) Inconsistent test-name style/language (blind-hunter) — the new names follow the codebase's established per-type conventions: `snake_case` manifest names for unit/bloc tests, camelCase sentence names for widget tests; the German UI term mirrors the existing `theSpracheUndRegionRowOpensTheLocaleSettingsPage`.
- (false) Only `app_de.arb` gains keys; an English template arb would break gen-l10n (blind-hunter) — `l10n.yaml` sets `template-arb-file: app_de.arb`; this is a German-only app with no English template.
- (false) New files carry a doubled `b/home/timo/...` path prefix that won't apply cleanly (blind-hunter) — an artifact of `git diff --no-index` on the untracked files in the review diff; the real files are at the correct `app/lib/...` paths (git status confirms).
- (low) `recoveryPhrase()` silently generates entropy on read instead of failing the documented "must already exist" invariant / CQRS query-with-side-effect (blind-hunter) — real smell, but the reveal is only reachable behind the authenticated gate where the entropy always exists, and the fix adds a read-only-load method; not worth the surface here.
- (low) `'auth.invalidRecoveryPhrase'` code + internal message string duplicated across cubit/resolver/tests (blind-hunter) — the codebase uses raw string literals for *all* error codes (no shared constants anywhere); tests pin the value; a constant only here would be inconsistent and low-value.
- (low) Fragile cross-package test import `package:flutter_secure_storage/test/...` (blind-hunter) — it is the package's documented in-memory test platform; works and green. Worth re-checking on a future `flutter_secure_storage` bump (CLAUDE.md §7), not changing now.
- (low) New dev dependency `flutter_secure_storage_platform_interface: ^2.1.0` contradicts AC5's "no new dependency is required" (acceptance-auditor) — the dep is test-only and justified (in-memory secure-storage platform); the AC wording is about feature deps, and correcting it edits the spec (out of scope). Worth confirming `^2.1.0` is the current supported major per §7.

## Dev Notes

### Ground truth — read these before coding
- `sprint-change-proposal-2026-09-13.md` (rev E) §4 Story 7.2 (the ACs) + §5 decision 3 (the reveal
  timing this story consciously supersedes per D-A).
- The Story 7.1 file (`7-1-silent-account-provisioning-on-first-launch.md`) — the credential model,
  the "no phrase UI in this story" scope guard 7.2 completes, and the review findings (esp. #15, the
  removed sign-out and the deliberate device-bound permanence).
- `epic-7-story-7.1-provisioning-design.md` — the credential-model crux (entropy = seed = phrase).

### Patterns to mirror (exact files)
- `recovery_phrase.dart` — extend with the inverse, same pure/dependency-free style.
- `create_or_await_choice_page.dart` `_openOnboarding` — the exact provider-re-provide-across-push
  pattern the reveal/recovery pushes must copy (root Navigator sits above `FirstRunRouter`'s providers).
- `profile_screen.dart` `profile-locale-row` `ListTile` — the shape for the new re-view row.
- `auth_cubit.dart` `signIn()` — `recoverFromPhrase` mirrors it plus the import step and a distinct
  invalid-phrase failure.

### The credential-model crux (get this exactly right — AC3)
- The imported phrase must reconstruct the **identical** entropy, so `restoreFromPhrase` must write the
  same 32 bytes 7.1 stored, and `signIn()`'s `loadOrCreate` must then re-derive the **same** base64url
  username. Do not introduce a second derivation step or re-normalize the username differently —
  changing the derivation strands existing accounts (7.1 Dev Notes crux).
- Recovery reuses `signIn()` verbatim after the import — the idempotent `provision()` call is expected
  and harmless for an existing account (7.1 AC3). Do **not** add a "skip provision on recovery" branch.

### Identity-swap crux (AC3, D-E)
- On the choice screen the app is **already** authenticated (as the throwaway). `recoverFromPhrase`
  going `inProgress` makes `AuthGateBody` show `SignInPage` briefly, then `authenticated` re-mounts a
  **fresh** `FirstRunRouter` (the old one is disposed while `SignInPage` shows), so `HouseholdsCubit`
  bootstraps anew against the new access token. No manual re-route or cubit reset is needed — but the
  active-household **must** be cleared (AD-7) so the created-household-on-throwaway edge cannot carry a
  stale selection into the recovered identity (`HouseholdsCubit`'s stale-id tolerance is the backstop,
  not the primary guarantee).

### GDPR / privacy (CLAUDE.md §5, AD-6/AD-7)
- The phrase, entropy, and private key are the device's most sensitive local data. Reveal is a **local
  read only**; recovery sends only the public key + signed challenge. Assert in a test that no request
  carries the secret (AC4).
- AD-7: a recovered identity on a shared/reused device must not inherit the previous identity's
  active-household selection — hence the `clear()` on recovery. (Device-cache purge on full erasure is
  Epic 6's concern.)
- The reveal warning is a data-protection affordance (the person owns the only copy of their recovery
  material) — plain, non-alarming German; state the consequence honestly (unrecoverable), without
  promising the not-yet-built email backup (7.3).

### Scope guards (KISS / YAGNI — CLAUDE.md §1)
- **In 7.2:** the phrase inverse primitive, the two store methods, the shared reveal page + its two
  entry points, the recovery page + `recoverFromPhrase`, the DI lift, and l10n/a11y.
- **Not in 7.2:** email attach + emailed-code recovery + SMTP (7.3), consent capture (7.4),
  invite-by-code (7.5), clipboard copy (D-G), any backend/Keycloak change, any new event/command/
  migration/endpoint.

### Testing standards (CLAUDE.md §6)
- Unit: `RecoveryPhrase` round-trip + each rejection; `SecureEnclaveDeviceCredentialStore.recoveryPhrase`/
  `restoreFromPhrase` against a fake secure storage; `AuthCubit.recoverFromPhrase` happy path + invalid
  phrase (distinct error, no sign-in) + clears active household.
- Widget: reveal page renders 24 words + warning from a fake store (no network); Profil row opens it;
  choice-screen save action opens it; recovery form rejects an invalid phrase inline and, on a valid
  phrase, drives the `inProgress → authenticated` swap to a re-bootstrapped router (with fakes).
- AC4: a test asserting the reveal/recovery paths issue no request carrying entropy/phrase/private key.
- Synthetic phrases only (a fixed test-vector mnemonic) — never a real one.

## Test Manifest (task → named test)

| Task / AC | Named test |
|-----------|------------|
| AC3 phrase inverse | `entropyFromWords_roundTripsWithWordsFromEntropy`, `entropyFromWords_withInvalidChecksum_throwsInvalidRecoveryPhrase` |
| AC1/AC2 store read | `recoveryPhrase_returnsTwentyFourWordsFromStoredEntropy` |
| AC3 store import | `restoreFromPhrase_replacesStoredEntropy_soCredentialReDerivesSameUsername`, `restoreFromPhrase_withInvalidPhrase_writesNothing` |
| AC3 cubit | `recoverFromPhrase_withValidPhrase_signsInAndClearsActiveHousehold`, `recoverFromPhrase_withInvalidPhrase_emitsInvalidRecoveryPhraseAndDoesNotSignIn` |
| AC1 reveal | `revealPage_showsNumberedTwentyFourWordsAndUnrecoverableWarning` |
| AC1 entry | `choiceScreen_savePhraseAction_opensRevealPage` |
| AC2 re-view | `profileScreen_recoveryPhraseRow_opensRevealPage` |
| AC3 recovery UI + swap | `recoverAccountPage_invalidPhrase_showsInlineError`, `recoverAccountPage_validPhrase_swapsIdentityAndReroutes` |
| AC3 swap remount (added during dev-story verification) | `recoverFromPhraseRemountsTheAuthenticatedSubtreeForTheRecoveredIdentity` |
| AC4 secret stays local | `recoveryPaths_sendNoEntropyOrPrivateKeyOverTheNetwork` |

## Project Structure Notes

- All new code is Flutter under `app/lib/features/auth/` (data primitives + `recover_account_page`) and
  the reveal page under `features/auth/presentation/` (shared), with entry points edited in
  `features/households/presentation/create_or_await_choice_page.dart` and
  `features/settings/presentation/profile_screen.dart`.
- No backend files change; no Flyway migration (latest is 7.1's `V18`); no Keycloak/realm change.
- l10n additions in `app/lib/l10n/app_de.arb` (+ regenerated `l10n/gen/*`).

## References

- `sprint-change-proposal-2026-09-13.md` (rev E) §4 Story 7.2, §5 decision 3
- Story 7.1 file + `epic-7-story-7.1-provisioning-design.md` (credential model)
- `ARCHITECTURE-SPINE.md` AD-6/AD-7 (pseudonymous ids, erasure by de-linking, device-cache purge)
- `recovery_phrase.dart` · `device_credential.dart` · `secure_enclave_device_credential_store.dart`
  · `direct_grant_oidc_client.dart` · `auth_cubit.dart` · `auth_gate.dart`
- `create_or_await_choice_page.dart` · `profile_screen.dart` · `active_household_store.dart`
  · `households_cubit.dart`

## Questions for Timo (non-blocking — sensible defaults chosen)

- **Reveal warning wording & email mention** — default: state plainly that losing the phrase means the
  account and its household data are unrecoverable, **without** mentioning the not-yet-built email
  backup (7.3). Say "email backup coming" instead? (Default: no — don't promise unshipped features.)
- **Recovery input shape** — default: a single forgiving multiline field (trim/lowercase/collapse
  whitespace, then validate) over 24 individual word boxes (heavier UI). Prefer word-boxes?
- **Clipboard copy (D-G)** — default: **no** copy action (a recovery secret on the shared clipboard is
  readable by other apps). Revisit if you'd rather trade that risk for convenience.

## Change Log

| Date | Change | By |
|------|--------|-----|
| 2026-09-14 | Story drafted (create-story) from the rev-E proposal §4 Story 7.2, grounded in the 7.1 code; forks resolved with Timo: D-A reveal-on-choice-screen (supersedes proposal's first-household-modal timing), D-B button→reveal-page, D-D recovery entry on choice screen, D-H client-only. Status → ready for dev | Claude (Opus 4.8) |
| 2026-09-14 | Code review (Opus 4.8, 4 layers): 2 decision-needed + 5 patch + 1 defer + 9 rejected. Decisions: #1 invalid-phrase teardown → PATCH (isolate the error), #2 reveal-screen capture protection → DEFER. Applied 6 patches: (1) `recoverFromPhrase` validates before any global emit so an invalid/empty phrase no longer tears down the authenticated session (AC3 "changes nothing"), with the invalid-phrase error shown inline locally in `RecoverAccountPage`; (2) recover page now also surfaces a generic sign-in-phase failure (`recover-account-signin-error`) instead of silently resetting; (3) extracted shared `openRecoveryPhraseRevealPage` helper (DRY, mirrors `openAwaitInvitePage`) used by both entry points; (4) added the missing `auth.invalidRecoveryPhrase` resolver-mapping test; (5) `RecoveryPhraseRevealPage` FutureBuilder gains a `hasError` branch; (6) explicit a11y semantic labels on the numbered words. Deferred: screen-capture/`FLAG_SECURE` protection on the reveal screen → `deferred-work.md`. Green build: `flutter analyze` 0 issues + `flutter test` all passing (686). Backend untouched (D-H). Status → done | Claude (Opus 4.8) |
| 2026-09-14 | Implemented (dev-story): all tasks landed as planned, client-side only. Added the phrase inverse (`RecoveryPhrase.entropyFromWords` + `InvalidRecoveryPhrase`), the two `DeviceCredentialStore` methods, the shared credential-store DI lift in `AuthGate`, `RecoveryPhraseRevealPage` (choice-screen + Profil entry points), `RecoverAccountPage` + `AuthCubit.recoverFromPhrase`, and all l10n/a11y strings. Orchestration pass closed one gap the implementation left open: added `recoverFromPhraseRemountsTheAuthenticatedSubtreeForTheRecoveredIdentity` to `auth_gate_body_test.dart` (a Completer-blocked fake `OidcClient` to force a pumpable mid-flight frame) to actually prove the `inProgress → authenticated` swap unmounts/remounts the authenticated subtree per-identity, rather than resting on the pre-existing generic switch test. Green build: app `flutter test` 683/0 + `flutter analyze` 0 issues (backend untouched, no `./gradlew` run needed — client-only per D-H). Status → in-progress, pending review | Claude (Sonnet 5) |
