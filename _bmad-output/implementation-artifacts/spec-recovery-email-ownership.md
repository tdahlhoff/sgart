---
title: 'Recovery-email ownership: the address as a digest index, not a Keycloak attribute'
type: 'feature'
created: '2026-10-01'
status: 'in-progress'
baseline_commit: '07ffa020be3e889a4a62080f6e36313d1e43711b'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '_bmad-output/planning-artifacts/recovery-email-ownership-design.md'
  - '_bmad-output/implementation-artifacts/spec-beta-hardening-rebind-compensation-and-retract-race.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** An attached recovery email is written straight onto the Keycloak account, and the realm rejects duplicate emails (`duplicateEmailsAllowed: false`). This causes four problems (design note §1). (1) Attaching an address another account already holds gets a silent `202` that sends nothing (8.6 D2), so a legitimate second account waits forever. (2) An unconfirmed attach already claims the address (squatting). (3) Attach and recover share one throttle budget per target account, so anyone who knows an address can lock the owner out for 24 h. (4) Any "taken" signal works as a registration oracle.

**Approach (decided with Timo, design note 07ffa02; not reopened here):** Stop using Keycloak's email field. The identity context keeps a `recovery_email_binding` side-store keyed by `HMAC-SHA256(pepper, normalizedAddress)`. It holds no plaintext address, allows several accounts per address, and only confirmed rows count. Attach writes a pending row and mails a code; confirm marks the row confirmed. Recover hashes the typed address, mails one code per address, and on confirm rebinds the single matching account. When several accounts match, it returns an in-app picker ("household name (your nickname)"). There are three separate in-memory budgets, mails are sent asynchronously, and the Keycloak email adapter methods are removed.

## Boundaries & Constraints

**Always:**
- Only **confirmed** bindings are used for recovery, sweep activation, and the profile status. A pending binding grants nothing.
- No plaintext address at rest anywhere: no column, no log line, no exception message. Throttle keys are digests or account ids only.
- `POST /api/v1/account/email` and `POST /api/v1/account/recovery/email` answer `202` in every case except a malformed address (`400`) and, for attach only, the **per-caller** budget (`429`). Nothing about the address changes the status, body, or timing.
- Mails are plain-text German with **no links** and **no household or nickname data**.
- The R1 delete-then-rebind order and the `8666e93` compensation in `ConfirmEmailRecovery` stay unchanged.
- `identity` never imports another context's types. Household names reach it only through an identity-owned out-port (F2, below).
- Tests come first (TDD) and use the ubiquitous language with no abbreviations. Fixtures use `*@example.test` only.

**Never:**
- Account merging (out of scope, Timo).
- A "wasn't me" link page, a signed token, a public endpoint, or a suppression list (all dropped, design note §3.3).
- A migration of existing Keycloak emails into bindings (F4: no production data).
- Pepper provisioning for real deployments (F3: belongs to the beta-infra secrets work). This spec adds only the property and the dev default.
- Any household or nickname datum in an email.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Attach, fresh address | Caller A, address free | Pending binding (digest, A, hint); `ATTACH_CONFIRM` code stored for A; mail handed off asynchronously; `202` | — |
| Attach, address already confirmed on account B | Caller A | Same as fresh: pending binding for A, code mailed; `202`. B is untouched | No 409, no silent drop |
| Attach, caller over per-caller budget | 6th attach in 24 h, or within 60 s | `429 account.recoveryCodeRateLimited`; nothing stored or sent | Checked before any side effect |
| Attach, address over per-address mail budget | 4th attach mail for this digest in 24 h (any callers) | `202`; nothing stored or sent | Silent, same response shape |
| Re-attach while a pending binding exists | Caller A has pending binding X, attaches Y | Pending X deleted; pending Y written; new code replaces the old one | — |
| Confirm attach | Caller A, correct code | A's pending binding gets `confirmed_at`; any **other** confirmed binding of A is deleted (one recovery email per account); code deleted; `204` | Wrong/expired/exhausted → `400` as today |
| Confirm attach, no pending binding | Code row exists, binding purged | `400` (same as a wrong code) | — |
| Detach | Caller A | All of A's bindings (pending and confirmed) and A's code rows deleted; `204`; no throttle reset | Idempotent |
| Profile status | `GET /api/v1/account/email` | `200 {addressHint}` for A's confirmed binding, or `200 {addressHint: null}` | Pending binding is never listed |
| Recover request, confirmed bindings exist | Throwaway T, address with ≥1 confirmed binding | `202` at once; on the executor: per-address recover budget, one `RECOVER` code stored for the **digest**, one mail | — |
| Recover request, unknown or pending-only address | T | `202`; nothing stored or sent | Same timing (whole issuance runs on the executor) |
| Recover request, over per-address recover budget | 11th in 24 h, or within 60 s | `202`; nothing new stored or sent; an already-issued valid code stays usable | Silent |
| Recover confirm, one candidate | T, correct code, digest → {A} | R1 rebind onto A; `RECOVER` code deleted; recover budget for the digest reset; T's own bindings and codes deleted; `204` | Rebind failure → existing 503 compensation |
| Recover confirm, several candidates, no `accountId` | Digest → {A, C} | `200 {candidates:[{accountId, households:[{householdName, nickname}]}]}`, sorted by household count, highest first; code **kept**; nothing rebound | Wrong code → `400`, increments attempts |
| Recover confirm, several candidates, chosen `accountId` | `accountId = C` ∈ candidates | Re-verify the code, then rebind onto C; `204` | `accountId` ∉ candidates → `400` (same as a wrong code) |
| Recover confirm, caller's own throwaway among bindings | Digest → {T, A} | T is excluded from candidates; proceeds with {A} | Digest → {T} only → `400` |
| Candidate with no household | Digest → {A (0 households), C} | Candidate listed with an empty `households` list | App shows a neutral label |
| Household name not yet projected | Name read model lags | `householdName: ""` | App shows a neutral fallback, as in `ListMyHouseholds` |
| Pending purge | Pending binding older than the code TTL; expired code rows | Deleted by the scheduled purge | Logged per failure, run continues |
| Erasure / export | Account A | `deleteAllFor(A)` removes every binding of A; `findAllFor(A)` yields hint and `confirmedAt` (never the digest) | — |

</frozen-after-approval>

## Decisions Taken in This Spec (implementation detail the note left open)

- **D1: one recovery email per account.** The note speaks of "the caller's binding" (singular) and the profile shows a single hint. Confirming a new address therefore replaces the caller's previous confirmed binding. Attaching a new address replaces any pending one.
- **D2: the hint is computed at attach, exposed only once confirmed.** The note says confirm "stores `address_hint`". Confirm only receives the code, though, so the plaintext is no longer available then. The masked hint (first character of the local part + `***@` + first character of the domain name + `***` + the top-level domain, for example `t***@g***.com`) is written with the pending row and is never read while `confirmed_at` is null.
- **D3: `created_at` column added** to the binding table. The pending purge needs it (pending + `created_at` older than the code TTL), so the purge does not have to join live codes.
- **D4: `RECOVER` codes are keyed by the address digest, not by an account.** "One code per address" with several candidate accounts cannot fit the current `(keycloak_user_id, purpose)` key. `recovery_code.keycloak_user_id` is renamed to `subject`, and the domain gets a `RecoveryCodeSubject` value object (`forAccount(KeycloakUserId)` / `forAddress(RecoveryEmailDigest)`). `ATTACH_CONFIRM` stays account-keyed. `deleteAll(KeycloakUserId)` keeps working for account-keyed rows.
- **D5: normalization.** Trim, then lowercase the whole address (`Locale.ROOT`). There is no provider-specific folding (no Gmail dot or plus stripping), which keeps it KISS and predictable.
- **D6: picker is stateless.** The first confirm call (several candidates, no `accountId`) verifies the code and returns candidates without consuming the code. The second call sends `{email, code, accountId}` and the server re-verifies everything. No server-side picker session exists. `accountId` is the candidate's pseudonymous Keycloak id, which is proven reachable only by someone holding the mailbox. **CQRS exception:** that first call returns read data from a command endpoint. This is justified because the note specifies a single confirm endpoint and the call has no side effect beyond the attempt counter.
- **D7: timing.** Recover hands the **entire** post-validation issuance (lookup, budget, code store, send) to an executor. A digest that has bindings and one that has none then take the same time on the request thread. Attach keeps its writes synchronous (an authenticated, own-flow request) and hands off only the mail.
- **D8: budgets.** Per-caller attach: 60 s cooldown and ≤ 5 / 24 h (unchanged). Per-address attach mail: ≤ 3 / 24 h, no cooldown. Per-address recover: 60 s cooldown and ≤ 10 / 24 h, reset on a successful rebind (raised from 3 after review, Timo 2026-10-01: a small cap let an attacker keep the owner locked out). All three are constants in one generic in-memory sliding-window class.

## Conflicts Between the Note and the Code

1. **F2 port shape (recommendation).** `ListHouseholdsForCaller` and `ResolveMembershipNicknames` are identity's **own** published ports. They supply household **ids** and **nicknames**, and identity can read both directly through `MemberMappingRepository.householdIdsFor` and `MembershipNicknameRepository.find(keycloakUserId, householdId)`. `ResolveMembershipNicknames` is shaped per household roster (`householdId, List<MemberId>`), which is the wrong shape here. The only foreign datum is the household **name**, which lives in collaboration's `HouseholdNameReadModel.namesFor(List<HouseholdId>)`. **Recommendation:** add an identity-owned out-port `identity.application.FindHouseholdNames` (`Map<HouseholdId, String> namesFor(List<HouseholdId>)`, with the shared-kernel `HouseholdId`). Implement it in `collaboration.adapter.out.CollaborationHouseholdNames`, which delegates to `HouseholdNameReadModel`, and wire it in `CollaborationApplicationConfig`. The dependency still points collaboration → identity only, mirroring `IdentityConsentGate`. Identity gains no import of collaboration.
2. **No code-TTL sweep exists.** The note says "pending rows are purged with the code TTL sweep". Expired `recovery_code` rows are currently never purged, only overwritten or deleted on use. This spec adds `PurgeExpiredRecoveryEmailState` plus a scheduled adapter (own cron, default every 15 min).
3. **Privacy guard vs. table name (decided, Timo 2026-10-01).** `NoPersistedPersonalDataTest.noFlywayMigrationEverDeclaresADisplayNameNicknameOrEmailColumn` greps every migration for `email`. The table is named what it is, `recovery_email_binding`, and the test gets a named whitelist entry for it (like V22's), plus a dedicated guard test proving the table holds no plaintext-address column (only `address_digest` and `address_hint`).
4. **AD-6 wording.** AD-6 ("the address lives only on the Keycloak account") and the V19 comment become untrue. A masked hint is persisted (reduced PII), and the address now lives nowhere. The architecture spine needs an AD-6 **rev G** note and an erasure-checklist line.
5. **Sweep activation.** `SweepNeverActivatedAccounts.hasConfirmedEmail` reads Keycloak `emailVerified` through `GetAccountDetails`. That must switch to a confirmed binding, otherwise every email-only account gets swept. `AccountDetails.email` / `emailVerified` then become dead and are removed.
6. **`/me` email.** `IdentityController` / `AuthenticatedCaller` return the JWT `email` / `email_verified` claims, and the profile header and `AccountEmailCubit` seed from them. Once nothing writes the Keycloak email, these are always empty. They are removed in Slice 3, together with the app (`CallerIdentity.fromJson` currently **requires** `email` to be a String, so the backend and app changes must land in one slice).

## Slices (ordering and dependencies)

| # | Slice | Depends on | Size |
|---|-------|-----------|------|
| 1 | Backend: binding index, attach/confirm/detach, status query, purge, sweep switch | — | M |
| 2 | Backend: recover on the index, picker response, F2 port, Keycloak email removal | 1 | M |
| 3 | App: profile hint, recover picker, `/me` email removal (backend + app) | 2 | M |

Recover-by-email does not work end-to-end between Slice 1 and Slice 2 (Slice 1 stops writing the Keycloak email). That is acceptable because no beta deployment exists (F4). Land Slices 1 and 2 back-to-back.

## Code Map

- `backend/src/main/java/de/sgart/identity/application/AttachRecoveryEmail.java`, `ConfirmRecoveryEmail.java`, `DetachRecoveryEmail.java`: rewritten onto the binding repository
- `.../application/RequestEmailRecoveryCode.java`, `ConfirmEmailRecovery.java`: rewritten onto the digest; `ConfirmEmailRecovery` keeps the `8666e93` compensation block verbatim
- `.../application/SetAccountEmail.java`, `FindAccountByEmail.java`, `adapter/out/DeferredSetAccountEmail.java`, `DeferredFindAccountByEmail.java`: **deleted** (Slice 2)
- `.../adapter/out/KeycloakAdminCreateAccount.java`: remove `setEmail` / `markEmailVerified` / `clearEmail` / `findByEmail` / `updateUserSuppressing409`, their request records, and the `email` fields in `UserDetailResponse` / `KeycloakUserResponse`
- `.../application/RecoveryCodeIssuanceThrottle.java`, `adapter/out/InMemoryRecoveryCodeIssuanceThrottle.java`: replaced by three ports over one generic in-memory class
- `.../application/SendRecoveryCodeEmail.java`, `adapter/out/JavaMailSenderRecoveryCodeEmail.java`, `DeferredSendRecoveryCodeEmail.java`: split into two intention-revealing methods
- `.../application/RecoveryEmailValidation.java`: gains normalization
- `.../domain/EmailRecoveryCodeStore.java`, `EmailRecoveryCode.java`, `adapter/out/JdbcEmailRecoveryCodeStore.java`, `InMemoryEmailRecoveryCodeStore.java`: subject-keyed (Slice 2)
- `.../application/SweepNeverActivatedAccounts.java`, `AccountDetails.java`, `GetAccountDetails.java`: activation from bindings; email fields removed
- `.../adapter/out/HmacSha256RecoveryCodeHasher.java`: shares an extracted HMAC helper with the new digester (DRY)
- `.../adapter/in/AccountController.java`, `AccountErrorAdvice.java`, `IdentityController.java`, `security/AuthenticatedCaller.java`
- `.../adapter/out/IdentityBeansConfig.java`: wiring; `backend/src/main/resources/application.yaml`: pepper, purge cron
- `backend/src/main/java/de/sgart/collaboration/adapter/out/CollaborationApplicationConfig.java` + new `CollaborationHouseholdNames.java` (F2)
- `backend/src/main/resources/db/migration/V24__recovery_email_binding.sql`, `V25__recovery_code_subject.sql`: new (the latest migration is V23)
- `backend/src/test/java/de/sgart/identity/NoPersistedPersonalDataTest.java`, `application/RecoveryEmailTestSupport.java`, `adapter/in/AccountControllerTest.java`, `adapter/out/IdentityBeansConfigTest.java`, `KeycloakAdminCreateAccountTest.java`
- App: `app/lib/features/auth/data/account_email_api.dart`, `caller_identity.dart`, `presentation/account_email_cubit.dart`, `account_email_state.dart`, `auth_state.dart`, `auth_cubit.dart`, `recover_by_email_page.dart`, `add_recovery_email_page.dart` (copy only), `app/lib/features/settings/presentation/profile_screen.dart`, `app/lib/l10n/app_de.arb`, `app/lib/shared/errors/error_message_resolver.dart`, `app/test/support/fake_account_email_api.dart`
- Docs: `_bmad-output/planning-artifacts/architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md` (AD-6 rev G, AD-7 erasure checklist)

## Tasks & Acceptance

### Slice 1: Binding index and attach/confirm/detach

**Execution (tests first in each pair):**
- [ ] `domain/RecoveryEmailDigestTest`, `RecoveryEmailHintTest`, `RecoveryEmailBindingTest`: unit tests first. `RecoveryEmailHint.masking("tester@example.test")` gives `t***@e***.test`; a one-character local part still masks; a digest rejects blank input; `RecoveryEmailBinding.pending(...)` has `isConfirmed() == false`, and `confirm(at)` sets `confirmedAt`
- [ ] `domain/RecoveryEmailDigest.java`, `RecoveryEmailHint.java`, `RecoveryEmailBinding.java` (entity: digest, `KeycloakUserId`, hint, `confirmedAt`, `createdAt`), `RecoveryEmailBindingRepository.java` (port: `savePending`, `findPendingFor`, `confirm`, `findConfirmedFor(digest)`, `findConfirmedFor(KeycloakUserId)`, `hasConfirmedBindingFor`, `deleteAllFor`, `findAllFor`, `deletePendingCreatedBefore`)
- [ ] `application/RecoveryEmailDigester.java` (port) + `adapter/out/HmacSha256RecoveryEmailDigester.java`. Extract a package-private `HmacSha256` helper shared with `HmacSha256RecoveryCodeHasher`, and fail fast on a blank pepper. Tests: `HmacSha256RecoveryEmailDigesterTest` (stable output, differs per pepper, blank pepper rejected)
- [ ] `RecoveryEmailValidation`: return the normalized address (D5). Test `validated_lowercasesAndTrimsTheAddress`
- [ ] `V24__recovery_email_binding.sql`: `address_digest VARCHAR(64)`, `keycloak_user_id VARCHAR(255)`, `address_hint VARCHAR(255)`, `confirmed_at TIMESTAMPTZ NULL`, `created_at TIMESTAMPTZ NOT NULL`, `PRIMARY KEY (address_digest, keycloak_user_id)`, an index on `keycloak_user_id` (erasure and caller lookups), and a partial index on `created_at WHERE confirmed_at IS NULL`
- [ ] `NoPersistedPersonalDataTest.recoveryEmailBindingTable_holdsOnlyADigestAndAMaskedHint`: asserts the column set and that no plaintext-address column exists (see Conflict 3)
- [ ] `adapter/out/JdbcRecoveryEmailBindingRepository` + `InMemoryRecoveryEmailBindingRepository`. Testcontainers test `JdbcRecoveryEmailBindingRepositoryTest`: the same digest on two accounts is allowed; `findConfirmedFor(digest)` ignores pending rows; `deleteAllFor_removesEveryBindingOfThePersonAndNoOtherPerson` (erasure); `findAllFor_exportsHintAndConfirmationTimeButNoDigest` (export); `deletePendingCreatedBefore_keepsConfirmedBindings` (retention)
- [ ] Throttles: `application/AttachRequestThrottle` (per `KeycloakUserId`), `AttachMailThrottle` (per digest), `RecoveryRequestThrottle` (per digest, with `reset`). One generic `adapter/out/InMemorySlidingWindowThrottle<K>` with a policy record replaces `InMemoryRecoveryCodeIssuanceThrottle`, and `RecoveryCodeIssuanceThrottle` is deleted. Port its tests into `InMemorySlidingWindowThrottleTest`, plus `reset_allowsTheNextIssuanceImmediately`
- [ ] `SendRecoveryCodeEmail`: split into `sendAttachConfirmationCode(address, code)` and `sendRecoveryCode(address, code)`. The attach mail says to ignore it if the reader did not request it (no contact line; deferred to the privacy-notice work, Timo 2026-10-01). Neither mail contains a link, household, or nickname. Tests: `JavaMailSenderRecoveryCodeEmailTest.attachConfirmationMail_containsTheCodeAndTheLifetimeButNoLink`, `recoveryMail_containsNoHouseholdNameNicknameOrLink`
- [ ] `adapter/out/AsynchronousSendRecoveryCodeEmail` (decorator over an `Executor`; logs a failure without the address). Test: `send_returnsBeforeTheMailIsDelivered` using a capturing executor
- [ ] `AttachRecoveryEmailTest` (rewrite first): `attach_toAnAddressConfirmedOnAnotherAccount_stillWritesAPendingBindingAndMailsACode`, `attach_whenTheCallerIsOverBudget_throwsRateLimitedAndChangesNothing`, `attach_whenTheAddressIsOverItsMailBudget_returnsSilentlyAndStoresNothing`, `attach_replacesTheCallersEarlierPendingBinding`, `attach_neverStoresThePlaintextAddress`
- [ ] `AttachRecoveryEmail.java`: per the matrix (caller budget, then normalize, digest, address mail budget, pending binding, code, asynchronous mail)
- [ ] `ConfirmRecoveryEmailTest` (rewrite first): `confirm_marksThePendingBindingAsConfirmed`, `confirm_replacesTheCallersPreviousConfirmedBinding`, `confirm_withoutAPendingBinding_isRejectedLikeAWrongCode`, `confirm_withAWrongCode_leavesTheBindingPending`
- [ ] `ConfirmRecoveryEmail.java`, `DetachRecoveryEmail.java` (+ `DetachRecoveryEmailTest.detach_removesPendingAndConfirmedBindingsAndCodes`, `detach_doesNotResetAnyThrottle`)
- [ ] `application/GetRecoveryEmailStatus.java` (query) + `GetRecoveryEmailStatusTest` (`returnsTheHintOfTheConfirmedBinding`, `neverListsAPendingBinding`, `hasNoSideEffects`). Expose it as `GET /api/v1/account/email` → `{addressHint}` in `AccountController`, with tests in `AccountControllerTest`
- [ ] `SweepNeverActivatedAccountsTest`: `sweep_keepsAnAccountWithAConfirmedBinding`, `sweep_deletesAShellWithOnlyAPendingBindingAndRemovesThatBinding`. In `SweepNeverActivatedAccounts`, use `hasConfirmedBindingFor` and delete bindings with the shell
- [ ] `application/PurgeExpiredRecoveryEmailState.java` + `adapter/in/ScheduledRecoveryEmailPurge.java` (`sgart.identity.email-recovery.purge-cron`, default `0 */15 * * * *`); add `EmailRecoveryCodeStore.deleteExpiredBefore`. Tests: `purge_deletesPendingBindingsOlderThanTheCodeLifetime`, `purge_keepsConfirmedBindings`, `purge_deletesExpiredCodes`
- [ ] `IdentityBeansConfig` + `IdentityBeansConfigTest`: wiring; `application.yaml`: `email-recovery.address-pepper` (no default; dev default in the `dev` profile), `purge-cron`
- [ ] `ARCHITECTURE-SPINE.md`: AD-6 rev G (digest + masked hint, the address is stored nowhere) and the AD-7 erasure checklist (`recovery_email_binding` next to `account_consent`)

**Acceptance Criteria:**
- Given an address already confirmed on account B, when account A attaches it, then A receives a code, can confirm it, and both A and B have a confirmed binding.
- Given a stranger attaches the owner's address to the stranger's account, when the owner attaches the same address, then the owner's attach is unaffected unless the per-address mail cap (3 / 24 h) is used up. In that case it answers `202` and sends nothing (accepted trade-off).
- Given a database dump, when it is searched for any attached address, then no plaintext address is found.

### Slice 2: Recover on the index and the multi-account picker

**Execution:**
- [ ] `V25__recovery_code_subject.sql`: `ALTER TABLE recovery_code RENAME COLUMN keycloak_user_id TO subject` (D4). Update the V19 guard test's Javadoc only; its assertion still reads V19
- [ ] `domain/RecoveryCodeSubject.java` + `RecoveryCodeSubjectTest` (account and address subjects with the same raw value are not equal). Re-key `EmailRecoveryCodeStore` / `EmailRecoveryCode` / the JDBC and in-memory stores / `VerifyRecoveryCode`, and update `JdbcEmailRecoveryCodeStoreTest`
- [ ] `application/FindHouseholdNames.java` (F2 out-port) + `collaboration/adapter/out/CollaborationHouseholdNames.java` (+ `CollaborationHouseholdNamesTest`), wired in `CollaborationApplicationConfig`; a `FakeFindHouseholdNames` in `RecoveryEmailTestSupport`
- [ ] `RequestEmailRecoveryCodeTest` (rewrite first): `request_withConfirmedBindings_storesOneCodeForTheAddressAndSendsOneMail`, `request_forAnAddressOnTwoAccounts_stillSendsExactlyOneMail`, `request_withOnlyAPendingBinding_sendsNothing`, `request_overTheAddressBudget_sendsNothingAndKeepsTheEarlierCodeUsable`, `request_runsTheWholeIssuanceOnTheExecutor` (a capturing executor proves nothing is stored before it runs)
- [ ] `RequestEmailRecoveryCode.java`: validate synchronously, then hand everything else to the injected `Executor` (D7)
- [ ] `ConfirmEmailRecoveryTest` (extend; keep every compensation test green): `confirm_withOneCandidate_rebindsOntoIt`, `confirm_withTwoCandidatesAndNoChoice_returnsCandidatesSortedByHouseholdCountAndKeepsTheCode`, `confirm_withAChosenCandidate_rebindsOntoIt`, `confirm_withAChoiceOutsideTheCandidates_isRejectedLikeAWrongCode`, `confirm_excludesTheCallersOwnThrowawayFromTheCandidates`, `confirm_afterASuccessfulRebind_resetsTheAddressRecoveryBudget`, `confirm_afterASuccessfulRebind_removesTheThrowawaysBindingsAndCodes`, `candidates_showHouseholdNamesAndTheCandidatesNicknames`
- [ ] `ConfirmEmailRecovery.java`: replace `FindAccountByEmail` with the digest lookup plus candidate resolution. Return a sealed `EmailRecoveryOutcome` (`Rebound` / `ChooseAccount(List<RecoveryCandidate>)`). `RecoveryCandidate` is an application record with plain strings, so `adapter.in` never imports the domain
- [ ] `AccountController.confirmRecovery`: the request gains an optional `accountId`; it returns `204` on `Rebound` and `200 {candidates}` on `ChooseAccount`. Add `AccountControllerTest` cases for both shapes
- [ ] Delete `SetAccountEmail`, `FindAccountByEmail`, and their `Deferred*` adapters; remove the email methods and records from `KeycloakAdminCreateAccount` (+ tests). Remove `email` / `emailVerified` from `AccountDetails`
- [ ] `identity/RecoverByEmailEndToEndTest` (the design note's end-to-end test): a Spring context with Testcontainers Postgres, a test configuration that replaces the Keycloak ports with in-memory fakes and the mail port with a capturing one, and a synchronous executor. It drives attach → confirm on account A over HTTP, then recover → confirm from throwaway T, and asserts the rebind onto A and that no table holds the plaintext address
- [ ] Optional realm hardening: see Open Question 4

**Acceptance Criteria:**
- Given one confirmed account for an address, when a fresh device requests and confirms a code, then the device authenticates into that account with all its households.
- Given two confirmed accounts for an address, when the device confirms the code, then it sees both as "household name (nickname)", with the account that has more households first, and recovers exactly the one it picks.
- Given an attacker sends five recover requests for the owner's address, when the owner attaches or confirms on their own device, then nothing is blocked (the budgets are separate).
- Given an address with and one without bindings, when recover is requested, then both return `202`, and the request-thread work is identical.

### Slice 3: App UI and `/me` email removal

**Execution:**
- [ ] Backend: remove `email` / `emailVerified` from `IdentityController.IdentityResponse` and `AuthenticatedCaller` (+ `IdentityControllerTest`, `NoPersistedPersonalDataTest` Javadoc)
- [ ] `caller_identity_test.dart` first, then `caller_identity.dart`: drop `email` / `emailVerified`. Drop them from `auth_state.dart` / `auth_cubit.dart` as well
- [ ] `account_email_api.dart` + `fake_account_email_api.dart`: add `fetchStatus()` → `addressHint?`; `confirmRecovery(email, code, {accountId})` returns a `RecoveryConfirmation` (`rebound` | `chooseAccount(candidates)`)
- [ ] `account_email_cubit_test.dart` first, then `account_email_cubit.dart` / `account_email_state.dart`: replace the email with `addressHint`; add `loadStatus()`; seeding from `AuthState` is removed
- [ ] `profile_screen_test.dart` first, then `profile_screen.dart`: drop the header email line; the recovery section shows `profileRecoveryEmailConfirmedLabel` with the hint (for example "Wiederherstellungs-E-Mail: t***@e***.test")
- [ ] `recover_by_email_page_test.dart` first, then `recover_by_email_page.dart`: on `chooseAccount`, show a third step with one entry per candidate, labeled "Haushaltsname (Spitzname)" for each household and preselected on the first candidate. Show a neutral label for no household or an empty name. Choosing re-calls confirm with `accountId`. Tests: `showsThePickerOnlyWhenSeveralAccountsMatch`, `preselectsTheAccountWithTheMostHouseholds`, `confirmingTheChoiceRecoversThatAccount`
- [ ] `app_de.arb`: picker copy and the hint label; attach-page copy that says the code goes to the typed address

**Acceptance Criteria:**
- Given a confirmed recovery email, when the person opens Profil after a relaunch, then the masked hint is shown.
- Given a shared address on two accounts, when the person recovers by email, then the picker appears; given a single account, then no picker appears.

## Deferred-Work Entries This Closes

In `_bmad-output/implementation-artifacts/deferred-work.md`:
- "Attach with an already-registered email → possible 500 + enumeration" (7.3 review, line ~249), already marked as superseded.
- "Rethink the recovery-email ownership model (follow-up design task)…" (8.6, line ~384): duplicates, squatting, and budget lockout.
- "Design and implement the recovery-email ownership model…" (beta should-fixes split, line ~404).
- **Partially:** "No throttling on `attach` / `requestRecoveryCode`" (7.3, line ~250). In-app budgets now exist per caller and per address. The proxy-layer rate limit (ADR-0002) stays open with beta infra.
- **Partially:** "Remove consent and recovery-code rows keyed by a deleted throwaway…" (line ~432). The throwaway's bindings and codes are removed on rebind (Slice 2). `account_consent` stays with Epic 6.

## Open Questions for Timo

1. ~~Table name~~ decided: `recovery_email_binding` with a whitelist entry.
2. ~~Contact line / shared-mailbox sentence~~ deferred to the privacy-notice work (not needed for this build).
3. ~~F2~~ approved (Timo): the `FindHouseholdNames` port, implemented in `collaboration.adapter.out`.
4. ~~Realm `loginWithEmailAllowed`~~ dropped (not needed).

## Verification

**Commands (every slice, before reporting green):**
- `cd backend && ./gradlew test`: expected BUILD SUCCESSFUL (incl. ArchUnit `HexagonalArchitectureTest`, `NoPersistedPersonalDataTest`, Testcontainers)
- `cd app && flutter test && flutter analyze`: expected all pass, 0 issues (Slice 3; run it in Slices 1–2 too if any shared contract changes)

**Manual (after Slice 3, Mailpit):** attach and confirm on emulator A; reinstall and recover by email (single account); attach the same address on a second account and recover again (picker).
