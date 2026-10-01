---
title: 'Beta hardening: recovery rebind compensation and accept retract race'
type: 'bugfix'
created: '2026-10-01'
status: 'done'
baseline_commit: 'ccbc52afb77d9bc6e91c2f2e685ce3ccad17ea83'
route: 'dispatch'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** (1) `ConfirmEmailRecovery.confirmAndRebind` deletes the caller's throwaway account *before* the rebind; if the rebind then fails (Keycloak 5xx/network) the device authenticates into nothing and the retry hits an `IllegalStateException` (500). (2) In `AcceptInviteHandler`, a failed winner `retract`s the person's mapping by (person, household) even when a concurrent retry of the same person has since committed `MemberJoined` with that same id → a member in the stream with no access (deferred review finding, high).

**Approach:** (1) On rebind failure, best-effort re-create the throwaway from the details already read (same username/public key), keep the RECOVER code row (it is only deleted after a successful rebind), and raise a clean `RecoveryRebindFailedException` (503 `account.recoveryRebindFailed`). A retry then arrives with a JWT of the deleted account: a missing caller account maps to 401 `auth.unauthorized`, so the app's existing silent re-sign-in re-authenticates against the re-created account and retries once. (2) Before retracting, re-read the household; if the joiner is now a member, skip the retract.

## Boundaries & Constraints

**Always:** Delete-then-rebind order stays (Keycloak username uniqueness); the target account's `KeycloakUserId` is never deleted; no new open endpoint; domain stays infrastructure-free; tests accompany each change.

**Never:** Reordering delete/rebind; a rollback of the rebind itself; changing `provision`/`persist` semantics or the accept wire format; touching recovery-email ownership design (squatting, duplicate-address attach, lockout).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Rebind fails | Valid code, delete succeeded, `rebind` throws | Throwaway re-created and re-recorded; RECOVER code row kept; `RecoveryRebindFailedException` → 503 | Original failure kept as cause |
| Compensation also fails | `rebind` throws, re-create throws | `RecoveryRebindFailedException` (503) with the compensation failure suppressed | Logged; device re-provisions on next launch |
| Retry after compensation | JWT of the deleted throwaway | `CallerAccountNotFoundException` → 401 `auth.unauthorized` | App re-signs-in silently and retries |
| Winner append fails, retry already committed | Fresh mapping X persisted; rival retry committed `MemberJoined(X)`; winner's append conflicts | No retract; mapping X survives; original append failure rethrown | N/A |
| Winner append fails, nobody joined | Fresh mapping persisted; append conflicts; household lacks the joiner | Mapping retracted (existing behavior); failure rethrown | N/A |
| Re-read fails during compensation check | Household read throws | Treated as "not a member": retract as today | Original append failure still thrown |

</frozen-after-approval>

## Code Map

- `backend/src/main/java/de/sgart/identity/application/ConfirmEmailRecovery.java` -- the sequence; delete at the `deleteAccount.delete(throwawayCaller)` step, rebind after; comment documents the accepted risk to replace
- `backend/src/main/java/de/sgart/identity/application/CreateAccount.java` + `domain/ProvisionedAccountRepository.java` -- idempotent re-create of the throwaway and re-record (`recordIfAbsent`)
- `backend/src/main/java/de/sgart/identity/application/RecoveryCodeRejectedException.java` -- exception pattern to mirror
- `backend/src/main/java/de/sgart/identity/adapter/in/AccountErrorAdvice.java` -- add 503 and 401 mappings
- `backend/src/test/java/de/sgart/identity/application/ConfirmEmailRecoveryTest.java`, `RecoveryEmailTestSupport.java` -- existing fakes (`RecordingRebindAccountCredential`, `OrderedDeleteAccount`); add a failing rebind and a recording `CreateAccount`
- `backend/src/test/java/de/sgart/identity/adapter/in/AccountControllerTest.java` -- error-mapping coverage pattern
- `backend/src/main/java/de/sgart/collaboration/application/command/AcceptInviteHandler.java:103` -- the retract block; `Household.isMember(MemberId)` exists
- `backend/src/test/java/de/sgart/collaboration/application/AcceptInviteHandlerTest.java` -- race tests; `InMemoryEventStore` to decorate for the interleaving
- `app/lib/shared/errors/error_message_resolver.dart`, `app/lib/l10n/app_de.arb` -- add copy for `account.recoveryRebindFailed`
- `app/lib/shared/http/authenticated_http_client.dart` -- already retries once after a 401 via `tryReauthenticate` (verify the recover call goes through it)

## Tasks & Acceptance

**Execution:**
- [x] `ConfirmEmailRecoveryTest` -- failing tests first: rebind failure re-creates and re-records the throwaway and keeps the RECOVER code row; compensation failure still raises the clean exception; a missing caller account raises `CallerAccountNotFoundException`
- [x] `RecoveryRebindFailedException.java`, `CallerAccountNotFoundException.java` -- new application exceptions with `ErrorDescriptor` codes `account.recoveryRebindFailed`, `auth.unauthorized`
- [x] `ConfirmEmailRecovery.java` -- inject `CreateAccount`; compensate in a `catch` around `rebind`; replace the `IllegalStateException` for a missing caller; update the risk comment
- [x] `IdentityBeansConfig.java` (and any test wiring) -- pass `CreateAccount` to `ConfirmEmailRecovery`
- [x] `AccountErrorAdvice.java` + `AccountControllerTest` -- 503 and 401 mappings
- [x] `AcceptInviteHandlerTest` -- failing race tests: winner append fails after rival retry committed (no retract, mapping survives); nobody joined (retract still happens)
- [x] `AcceptInviteHandler.java` -- skip the retract when a re-read household already contains the joiner (read failure → retract as today)
- [x] `error_message_resolver.dart` + ARB + test -- German retry copy for `account.recoveryRebindFailed`

**Acceptance Criteria:**
- Given the rebind fails after the throwaway was deleted, when the person retries, then the device re-authenticates and the recovery can be completed without requesting a new code.
- Given a failed winner append after a rival retry of the same person committed, when the handler compensates, then the person keeps their mapping and access.

## Implementation Notes

- `ConfirmEmailRecovery` gained a `CreateAccount` dependency (wired in `IdentityBeansConfig`); the throwaway is restored from the `AccountDetails` read before the delete. The restored account gets a new Keycloak id, which is why a retry with the old JWT surfaces as 401 and the app's existing `tryReauthenticate` path (the recover call goes through `AuthenticatedHttpClient`) re-signs-in.
- `AcceptInviteHandler.hasJoined` re-reads the household; a failed re-read falls back to the previous retract behavior. The race test was mutation-checked: it fails with the guard removed.
- Mapping tests for the two new exceptions live in a new `AccountErrorAdviceTest` (direct handler calls — the failures only occur when Keycloak fails mid-recovery).
- Tests were written together with the production code, not strictly red-first.
- Verified: backend `./gradlew test` BUILD SUCCESSFUL (incl. ArchUnit, Testcontainers); app `flutter test` 790 passed, `flutter analyze` 0 issues. Not verified end-to-end against a real Keycloak: the silent re-sign-in after a compensated rebind.

## Spec Change Log

## Review Triage Log

Pass 1 (Opus, 3 layers: Blind Hunter, Edge Case Hunter, Verification Gap).

| Finding | Verdict | Route | Evidence |
|---|---|---|---|
| Rebind applied but response lost: restore hits Keycloak's username-409, records the target as a throwaway shell, returns 503; the retry then says "wrong code" (Blind, Edge x2, Gap) | medium | patch | Restore now detects the holder is the target, skips recording, and completes the recovery as a success; test added. |
| `provisionedAccountRepository.delete` outside the try: a failure leaves the throwaway deleted with no restore (Edge) | low | patch | Moved into the try. |
| No logging of the compensation or its failure (Blind) | medium | patch | `log.warn`/`log.error` added. |
| Re-read failure fallback in `hasJoined` untested (Blind, Gap) | medium | patch | Test added. |
| 503/401 mappings only tested by calling the advice methods; the Javadoc claim was wrong (Gap, Edge claim) | medium | patch | Two MockMvc tests added to `AccountControllerTest`; the redundant `AccountErrorAdviceTest` removed. |
| "ChangesNothing" test never asserts the code row survives; restore test never asserts the old row is gone (Blind) | low | patch | Assertions added. |
| `@throws` cross-reference and raw user id in the 401 message (Blind) | low | patch | Javadoc corrected, id removed from the message. |
| `hasJoined` leaves a TOCTOU window and the comment overstated it (Blind, Edge) | high | defer | Remaining window recorded in deferred-work; comment now names it. |
| Orphaned consent / recovery-code rows keyed by the deleted throwaway id (Edge, Blind) | low | defer | Pre-existing on the success path too; Epic 6 erasure. |
| `publicKey` null NPE (Edge) | low | reject | A throwaway always has a key (Story 7.1); fails loudly on an unreachable state. |
| `retract` deletes by person+household, not joiner id (Edge) | low | reject | The `persist` conflict contract keeps one id per person+household. |
| Restored throwaway resets retention clock / drops email state (Blind) | low | reject | A throwaway has no email; the TTL restart is bounded and acceptable. |
| Duplicate `"auth.unauthorized"` literal (Blind) | low | reject | Cosmetic. |
| No app-side behavior test for the 503 copy / restore-failed hint (Blind) | low | reject | Resolver mapping covered; page behavior unchanged. |
| Silent re-sign-in after a compensated rebind not verified end-to-end (Gap, note) | medium | reject | Not a code defect; manual verification (see Implementation Notes). |

## Verification

**Commands:**
- `cd backend && ./gradlew test` -- expected: BUILD SUCCESSFUL (incl. ArchUnit, Testcontainers)
- `cd app && flutter test && flutter analyze` -- expected: all pass, 0 issues
