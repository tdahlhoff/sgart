---
title: 'Story 8.9: Close the provisioning epic''s remaining critical-path test gaps'
type: 'chore'
created: '2026-09-28'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
context: []
baseline_commit: '6d12484267726df472492f929cef95098ca76784'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The 2026-09-20 manual test flagged that Epic 7 shipped "without its critical-path test coverage". An audit on 2026-09-28 shows most of it now exists:
- `ProvisionAccountTest`: state change, idempotency, rejections.
- `ConfirmEmailRecoveryTest`: rebind, preserved id, wrong code, exhausted attempts, replay.
- `SweepNeverActivatedAccountsTest`: retention.
- `DetachRecoveryEmailTest` plus controller tests: detach.

Two real gaps remain. An **expired** code on the *recovery* path is untested (expiry is only covered on attach and in the store). And no test drives recover-by-email **end-to-end through HTTP** (request code → confirm with it → account rebound) for a registered account.

**Approach:** Add exactly those two tests and correct the record on what's covered. No production code changes unless a new test exposes a defect; in that case fix it, test first.

**Decisions (Timo, 2026-09-28):**
- **D1 Erasure/export → Epic 6:** no erasure/export use case exists yet. Its tests land with the Epic 6 feature, and the checklist is already in `deferred-work.md` (7.4/8.3 entries: account, consent, nickname, email). No "erasure-readiness" test here.
- **D2 Provisioning rate limit → beta-infra reverse proxy:** `POST /api/v1/accounts` has no limiter, and there's no account to key one on. Per ADR-0002 it belongs at the proxy seam. It is deferred alongside the beta SMTP item and not built here.
- **D3 DoD:** green = backend `./gradlew test` (incl. ArchUnit) **and** app `flutter test` / `flutter analyze`, both run and named at completion (CLAUDE.md §6).

## Boundaries & Constraints

**Always:** Synthetic data only (`*@example.com`/`example.test` style addresses, fake subs). Reuse the existing doubles (`FakeGetAccountDetails`, `RecordingRebindAccountCredential`, `AccountControllerTest`'s `@TestConfiguration` `@Primary` beans and captured-code recorder) — no new mocking library. Control time with a fixed/mutable `Clock`, never a sleep.

**Never:** No erasure/export code or tests (D1). No rate limiter (D2). No change to recovery semantics (TTL, attempts, throttle, D2-202).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Recovery code expired | valid RECOVER code, clock past `expiresAt` | rejected; no delete, no rebind; the code row is unchanged | `account.recoveryCodeInvalid` (same as wrong code) |
| Recovery end-to-end over HTTP | throwaway JWT; registered email → `POST …/recovery/email` (202) → captured code → `POST …/recovery/email/confirm` | `204`; the throwaway is deleted and the target is rebound to the throwaway's username/public key | N/A |

</frozen-after-approval>

## Code Map

- `backend/src/test/java/de/sgart/identity/application/ConfirmEmailRecoveryTest.java` -- unit harness (fixed `Clock` at `NOW`, `InMemoryEmailRecoveryCodeStore`, recording doubles). Add the expired-code test here, following `confirmEmailRecovery_exhaustedAttempts_isRejected` (~line 123).
- `backend/src/main/java/de/sgart/identity/application/RecoveryCode.java` -- `TTL` = 15 min; the expiry check is `clock.instant().isAfter(expiresAt)`.
- `backend/src/test/java/de/sgart/identity/adapter/in/AccountControllerTest.java:62-135` -- the `@TestConfiguration` with `@Primary` doubles, incl. `TestFindAccountByEmail.REGISTERED_EMAIL` (8.6) and the code-capturing mail recorder (~line 77). Controller recovery tests start ~line 300. Add the end-to-end test here. If confirm needs `GetAccountDetails`/`RebindAccountCredential`/`DeleteAccount` doubles that the context lacks, add them as `@Primary` beans in the same config.
- `_bmad-output/implementation-artifacts/deferred-work.md` -- add the D2 entry; D1 is already covered by the existing 7.4/8.3 entries.

## Tasks & Acceptance

**Execution:**
- [x] `ConfirmEmailRecoveryTest.java` -- add `confirmEmailRecovery_withExpiredCode_isRejectedAndChangesNothing` per the matrix.
- [x] `AccountControllerTest.java` -- add `recoverByEmail_requestThenConfirmWithTheEmailedCode_rebindsTheTargetAccount` per the matrix (assert `204` plus the recorded delete/rebind).
- [x] `_bmad-output/implementation-artifacts/deferred-work.md` -- append the D2 provisioning-rate-limit entry (proxy seam, ADR-0002, with the beta-infra work).

**Acceptance Criteria:**
- Given the full suites, when the story completes, then backend `./gradlew test` and `flutter test` + `flutter analyze` were all run and are green, and the completion note names both.

## Implementation Notes

- `ConfirmEmailRecoveryTest.java`: swapped the fixed `Clock` field for a package-private `MutableClock` (same
  pattern as `InMemoryRecoveryCodeIssuanceThrottleTest`'s) so `confirmEmailRecovery_withExpiredCode_isRejectedAndChangesNothing`
  can advance past the stored code's `expiresAt` without a sleep, then added the test itself (stores a code,
  advances the clock past `NOW.plusSeconds(60)`, asserts `RecoveryCodeRejectedException`, and that no
  delete/rebind happened and the code row is still present — i.e. not consumed). No production code changed;
  `RecoveryCode.matches`/`VerifyRecoveryCode` already reject an expired code correctly.
- `AccountControllerTest.java`: added three new `@Primary` test doubles (`RecordingGetAccountDetails`,
  `RecordingDeleteAccount`, `RecordingRebindAccountCredential`) in `InMemoryAdaptersConfig`, replacing the
  default `Deferred*` stand-ins (which always answer empty/no-op, since no live Keycloak exists in this
  slice) — needed so the new end-to-end test can register the throwaway device's `username`/`publicKey`
  and observe the resulting delete + rebind. Added `recoverByEmail_requestThenConfirmWithTheEmailedCode_rebindsTheTargetAccount`,
  which drives `POST /api/v1/account/recovery/email` (captures the emailed code via the existing
  `RecordingSendRecoveryCodeEmail`) then `POST /api/v1/account/recovery/email/confirm` with that code,
  asserting `204` plus the recorded delete (throwaway id) and rebind (target id + throwaway's
  username/publicKey). All three new beans are cleared in the existing shared `@BeforeEach`, following the
  established pattern for the other `@Primary` singleton doubles in this class.
- `deferred-work.md`: appended the D2 entry ("planning of story-8.9") documenting that the provisioning
  rate limit is deferred to the reverse-proxy seam per ADR-0002, alongside the beta SMTP item.
- No defects were exposed by either new test; no other production code changed.

## Spec Change Log

## Review Triage Log

Review 2026-09-28 (Opus; Edge Case Hunter + Verification Gap; Blind Hunter skipped for the thin, test-only slice). VG: no gaps. Both new tests fail when the behavior they protect is removed.

| # | Finding | Verdict | Evidence / route |
|---|---------|---------|------------------|
| 1 | (VG other) Expired-code test's no-delete/no-rebind asserts are vacuous: throwaway details aren't registered, so the path throws first | low | Real → patch (register the throwaway's details). |
| 2 | (VG other, EC ×2) Matrix says "code row unchanged", but `VerifyRecoveryCode` increments attempts on an expired code; the test only asserts `isPresent()` | low | Production behavior is correct; the frozen matrix wording is loose ("not consumed" was meant) → patch the test to pin the real outcome (present, attempts = 1). Spec text left as is (frozen). |
| 3 | (EC) No boundary test at exactly `expiresAt` | low | Real, a direct test addition → patch. |
| 4 | (EC) `lastCode()` on an empty list → IndexOutOfBounds instead of a clear failure | low | Real → patch (assert one code sent first). |
| 5 | (EC) Success path doesn't assert the RECOVER code was consumed or the throwaway's provisioned row de-linked | low | Real → patch. |
| 6 | (EC) The 03:00 retention-sweep cron could fire during the test and add to `deletedIds` | false | The sweep only deletes never-activated accounts past the 14-day TTL in the in-memory repo; this test's accounts are never past TTL, so no stray delete can occur. |
| 7 | (EC claim) New recording doubles in `adapter.in` duplicate `RecoveryEmailTestSupport`'s (package-private in `application`) | low | Reuse would mean making test support public across packages; the spec's "reuse" intent is met within each package → rejected (low + more surface). |
| 8 | (VG other) Third identical private `MutableClock` in the test tree | low | Real DRY smell, but extracting a cross-package public test clock is more than a direct fix → rejected. |

## Verification

**Commands run:**
- `cd backend && ./gradlew :test --tests '*ConfirmEmailRecoveryTest' --tests '*AccountControllerTest'` → `BUILD SUCCESSFUL`.
- `cd backend && ./gradlew test` → `BUILD SUCCESSFUL` (full suite, incl. ArchUnit).
- `cd app && flutter test` → `All tests passed!` (778 tests).
- `cd app && flutter analyze` → `No issues found!`

All four green; D3 satisfied (both backend and app suites run and named at completion).
