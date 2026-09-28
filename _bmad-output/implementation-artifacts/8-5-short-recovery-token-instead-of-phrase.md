---
title: 'Story 8.5: Short recovery token instead of a 24-word phrase'
type: 'feature'
created: '2026-09-28'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
context: []
baseline_commit: '43e79ed4c1b2dcb8cd94232b26c2722f74063d05'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The recovery secret is a 24-word BIP39 mnemonic of the 32-byte Ed25519 seed — tedious to transcribe, re-type, or keep. Beta testers balked at it (manual test 2026-09-20).

**Approach:** Replace it with one short token encoding **128 bits** of stored entropy (≥ 120-bit floor) plus a mistype checksum. The Ed25519 seed is **KDF-derived** from that entropy (HKDF-SHA256 → 32 bytes). Reveal, restore, and Profil re-view show and accept the token, and `bip39` is dropped. Client-only: the server still sees only the public key (= Keycloak username), so the backend, the Keycloak SPI, and recover-by-email stay unchanged.

**Decisions (Timo, 2026-09-28):**
- **D1 Format:** Crockford Base32. 26 data chars (128 bits + 2 zero pad bits) + 2 checksum chars (first 10 bits of SHA-256(entropy)) = 28 chars, displayed as 7 hyphen-separated groups of 4, e.g. `K7QM-2XRA-9FDT-HB4W-0NCE-M3PY-Q8ZJ`. The input accepts Crockford aliases (`O→0`, `I/L→1`).
- **D2 Copy:** the reveal page gets a „Kopieren" button (clipboard) next to the token, which supersedes 7.2's D-G. The restore field accepts a pasted token.
- **D3 UI term:** „Wiederherstellungsschlüssel" in all German copy.
- **D4 Size:** keep as one spec (~2.1k tokens, accepted over the 1600 guideline — single cohesive swap).

## Boundaries & Constraints

**Always:** Keep the ubiquitous language "recovery token" everywhere (types, methods, keys, error code, arb keys, tests); no leftover "phrase" identifiers. Validation (format + checksum) runs once, in the token codec, before any secure-storage write or auth-state change (7.2's "invalid input changes nothing" holds). Forgiving input: case-insensitive, hyphens/whitespace ignored. Only synthetic tokens in tests.

**Never:** No backend, Keycloak SPI, event, migration, or endpoint change. No migration of the old 32-byte `sgart.auth.deviceEntropy` entry (no real beta ran; emulator devs reinstall, and a stale entry is simply ignored). Don't use a slow password KDF (Argon2/PBKDF2): the input is 128 bits of full entropy. No FLAG_SECURE work (separate deferred item).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| First launch | no stored entropy | 16 random bytes stored under a new key; keypair = Ed25519(HKDF(entropy)) | N/A |
| Reveal | stored entropy | one grouped token, e.g. `K7QM-2XRA-…` | read failure → existing load-error copy |
| Restore, valid | token typed lowercase, with spaces or without hyphens | same entropy → same public key → existing account signed in | N/A |
| Restore, mistype | one character wrong, or two swapped | rejected inline; nothing written | `auth.invalidRecoveryToken` |
| Restore, wrong length / illegal char | e.g. 27 chars or `U` | rejected inline; nothing written | `auth.invalidRecoveryToken` |
| Round trip | encode → decode | identical entropy; determinism: same token ⇒ same public key on two store instances | N/A |

</frozen-after-approval>

## Code Map

- `app/lib/features/auth/data/recovery_phrase.dart` -- BIP39 encode/decode + `InvalidRecoveryPhrase`. Replace with `recovery_token.dart`: `RecoveryToken.format(Uint8List) → String`, `RecoveryToken.parse(String) → Uint8List` (normalize, alphabet, length, checksum), `InvalidRecoveryToken`. Hand-rolled Crockford codec, no new dependency.
- `app/lib/features/auth/data/device_credential.dart` -- `fromEntropy` seeds Ed25519 directly. Add the HKDF-SHA256 step (`cryptography` `Hkdf`, fixed `info` such as `sgart-device-ed25519-v1`) so the 16-byte entropy yields the 32-byte seed.
- `app/lib/features/auth/data/device_credential_store.dart` -- interface: `recoveryPhrase()`/`restoreFromPhrase(words)` → `recoveryToken()`/`restoreFromToken(String)`.
- `app/lib/features/auth/data/secure_enclave_device_credential_store.dart` -- 16-byte entropy under the new key `sgart.auth.recoveryTokenEntropy`. Validate via `RecoveryToken.parse` before writing.
- `app/lib/features/auth/presentation/auth_cubit.dart:115` -- `recoverFromPhrase` → `recoverFromToken(String)`. `recoverFromEmailRebind` is unchanged (it reuses the device credential).
- `app/lib/features/auth/presentation/recovery_phrase_reveal_page.dart` -- becomes `recovery_token_reveal_page.dart`: a single monospace token (the `_NumberedWord` chips go away) plus the „Kopieren" button (D2). Keep the error/loading states and the `isFreshAfterEmailRecovery` flag.
- `app/lib/features/auth/presentation/recover_account_page.dart` -- a single-line token field instead of the 4-line word field; drop `_normalizedWords` (normalization lives in the codec).
- `app/lib/features/auth/presentation/recover_by_email_page.dart:142`, `app/lib/features/households/presentation/create_or_await_choice_page.dart:78-92`, `app/lib/features/settings/presentation/profile_screen.dart:212-216` -- callers: rename imports, keys, and labels only.
- `app/lib/shared/errors/error_message_resolver.dart:45` -- `auth.invalidRecoveryPhrase` → `auth.invalidRecoveryToken`.
- `app/lib/l10n/app_de.arb` (39–85, 148–155, 1098, 1368, 1453) -- rename `recoveryPhrase*` keys to `recoveryToken*`, rewrite the copy (no "24 Wörter"), and drop `recoveryPhraseWordSemanticLabel`. Regenerate `l10n/gen`.
- `app/pubspec.yaml:30` -- remove `bip39`.
- Tests to migrate: `test/support/fake_auth_dependencies.dart:105-135`, `test/features/auth/data/{recovery_phrase,secure_enclave_device_credential_store,direct_grant_oidc_client}_test.dart`, `test/features/auth/presentation/{recovery_phrase_reveal_page,recover_account_page,recover_by_email_page,auth_cubit,auth_gate_body}_test.dart`, `test/features/households/presentation/create_or_await_choice_page_test.dart`, `test/features/settings/presentation/profile_screen_test.dart`, `test/shared/errors/error_message_resolver_test.dart`.
- `docs/first-real-world-test.md:207` -- "recovery phrase" → "recovery token".

## Tasks & Acceptance

**Execution:**
- [x] `app/test/features/auth/data/recovery_token_test.dart` -- write first: round trip, grouping, forgiving normalization (case, spaces, missing hyphens, Crockford aliases `O→0`, `I/L→1`), and rejection of wrong length, illegal char, single-char substitution, and adjacent transposition -- TDD for the I/O matrix.
- [x] `app/lib/features/auth/data/recovery_token.dart` -- implement the codec; delete `recovery_phrase.dart` and its test.
- [x] `app/lib/features/auth/data/device_credential.dart` + test -- HKDF seed derivation; test same entropy ⇒ same public key, different ⇒ different, and a pinned known-answer public key for one fixed entropy (guards against silent KDF drift).
- [x] `device_credential_store.dart`, `secure_enclave_device_credential_store.dart` + test -- new interface, 16-byte generation, new storage key, validate-before-write.
- [x] `auth_cubit.dart` + test -- `recoverFromToken`; an invalid token leaves both the state and storage untouched.
- [x] Reveal page, recover page, three callers, error resolver, arb + gen -- UI swap per D2/D3; update their widget tests and fakes.
- [x] `app/pubspec.yaml` -- drop `bip39`, then `flutter pub get`.
- [x] `docs/first-real-world-test.md` -- wording.

**Acceptance Criteria:**
- Given the choice screen or Profil, when the person opens the reveal, then exactly one token is shown and no word list, and a screen reader announces it with a label.
- Given a token from device A, when it is restored on device B, then device B signs into A's existing account (same public key/username).
- Given the finished change, when `grep -ri "bip39\|mnemonic\|recoveryPhrase" app/lib app/test app/pubspec.yaml` runs, then nothing is found.

## Design Notes

Why HKDF and not a raw seed: the AC requires a KDF step, and it lets a 16-byte secret feed a 32-byte Ed25519 seed with domain separation (`info` versions the derivation). A slow KDF only adds value for guessable secrets. At 128 bits, online guessing is already stopped by the rate-limited, signature-checked Direct-Grant flow.

```dart
final seed = await Hkdf(hmac: Hmac.sha256(), outputLength: 32)
    .deriveKey(secretKey: SecretKey(entropy), nonce: const <int>[], info: utf8.encode('sgart-device-ed25519-v1'));
```

## Verification

**Commands:**
- `cd app && flutter test` -- expected: all pass
- `cd app && flutter analyze` -- expected: 0 issues
- `cd backend && ./gradlew test` -- expected: green. The backend is untouched; run it only to confirm, and name it in the report.

## Review Triage Log

Review round 1 (Blind Hunter, Edge Case Hunter, Verification Gap; Opus, 2026-09-28).

| # | Finding | Verdict | Evidence | Route |
|---|---------|---------|----------|-------|
| 1 | `RecoveryToken.parse` never checks the 2 zero padding bits of the last data char, so 4 spellings of that char decode to the same entropy | medium | Confirmed at `recovery_token.dart:59` — `dataValue >> 2` drops the bits unchecked; decode still yields correct entropy so no account harm, but violates the spec's "single mistyped char rejected" claim | patch |
| 2 | No pinned known-answer test for the token wire format itself | medium | Confirmed — `recovery_token_test.dart` only has round-trip/determinism tests; `device_credential_test.dart` sets exactly this precedent for the derived key | patch |
| 3 | `RecoveryToken.format` rejecting non-16-byte entropy is untested | low | Confirmed — code throws `ArgumentError` (line 35) but no test exercises it | patch |
| 4 | Misleading test comment/name: "drop one group's worth minus a char" (actually drops 2 chars); `parse_isDeterministic_theSameTokenAlwaysDecodesToTheSamePublicIdentity` compares entropy, not a public identity | low | Confirmed by reading `recovery_token_test.dart:48,86` | patch |
| 5 | No test asserts the reveal page's accessibility semantics label | medium | Verification-gap finding, pre-verified per that layer's rules; confirmed no `bySemanticsLabel`/label assertion in `recovery_token_reveal_page_test.dart` | patch |
| 6 | `_copyToken`'s `Clipboard.setData` has no error handling; a `PlatformException` is unhandled and the snackbar still implies success | medium | Confirmed at `recovery_token_reveal_page.dart:59-66` — no try/catch | patch |
| 7 | Copy-confirmation snackbar reuses `invitesCopiedSnackBar` (Story 7.5's string); test never asserts the snackbar appears | low | Confirmed at `recovery_token_reveal_page.dart:65` and `app_de.arb:352`'s description ties it to invites | patch |
| 8 | `recoveryTokenRevealWarning` copy/description overstate the token's exclusivity ("Nur damit …", "without mentioning the not-yet-built email backup") though Story 7.3 shipped email recovery | low | Confirmed at `app_de.arb:44-46`; fix is a direct text correction | patch |
| 9 | `_normalize`'s `RegExp(r'[\s-]')` only strips ASCII hyphen-minus, rejecting Unicode dashes from pasted input despite the forgiving-input promise | low | Confirmed at `recovery_token.dart:129` | patch |
| 10 | Fake token literal `'K7QM-2XRA-…'` duplicated across 4 test files, not DRY | low | Confirmed via grep across `create_or_await_choice_page_test.dart`, `consent_gated_choice_page_test.dart`, `profile_screen_test.dart`, `recover_by_email_page_test.dart` | patch |
| 11 | Stale story/feature references: `pubspec.yaml:39` "recovery-phrase tests"; `device_credential_store.dart:9` misattributes both primitives to 8.5 (they're from 7.2, only the format changed); `docs/first-real-world-test.md:207` still says "Story 7.2, not yet built" | low | Confirmed by reading all three files | patch |
| 12 | Reveal page's token `TextStyle(fontFamily: 'monospace', …)` is hard-coded outside `SgartTypography`; `'monospace'` doesn't resolve on iOS, undermining the token's transcribability | medium | Confirmed at `recovery_token_reveal_page.dart:114`; `SgartTypography` (with `withTabularFigures`) exists and is bypassed | patch |
| 13 | Recovery-token `TextField` doesn't disable autocorrect/suggestions or set `textCapitalization` | medium (unverified — pre-existing) | Confirmed identical gap existed in the pre-8.5 `recover_account_page.dart` TextField (`git show {baseline_commit}`) — not caused by this story | defer |
| 14 | `recoverFromToken` → `signIn()` silently provisions a new empty account when a wrong-but-checksum-passing token is restored, instead of a distinct "no such account" outcome | medium (unverified — pre-existing) | `recoverFromToken` (`auth_cubit.dart:116-120`) is `recoverFromPhrase` renamed only; this diff's 10-bit checksum is *stronger* than BIP39's 4-bit, so risk decreased, not increased, by this story | defer |
| 15 | Old `sgart.auth.deviceEntropy` key never deleted | false | Frozen Boundaries "Never" section explicitly excludes this migration/deletion | reject (intent-excluded) |
| 16 | Stored entropy read without a length check, could silently misbehave if corrupt | false | Both writers of `sgart.auth.recoveryTokenEntropy` (`_generateEntropy()`, `RecoveryToken.parse` output) are guaranteed exactly 16 bytes; the claimed state cannot arise from this code | reject |
| 17 | Clipboard not marked sensitive / not auto-cleared; `SelectableText` offers a second copy path | low | Real but an accepted trade-off already made explicitly by D2 (supersedes 7.2's no-copy stance); fix needs a platform channel or timer, more than a direct correction | reject (low + non-trivial fix) |
| 18 | Screen reader may mispronounce the token as a word instead of spelling it | maybe-false | Depends on platform TTS heuristics, unverifiable from the diff; the value is still announced either way, so would only be low if true | reject (maybe-false, would be low) |
