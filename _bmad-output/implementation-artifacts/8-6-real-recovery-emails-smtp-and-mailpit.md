---
title: 'Story 8.6: Real recovery emails locally (Mailpit) + recovery-endpoint hardening'
type: 'feature'
created: '2026-09-28'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
context: []
baseline_commit: 'fa8d909429ab5c11fa1a989ffc6162704ce37e9f'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Recover-by-email (7.3) has never delivered a real mail. Mail is gated off everywhere and no local SMTP exists, so the flow can't be exercised on the emulator. The two recovery-code endpoints are also unthrottled: an attacker can spam a victim's inbox, and re-requesting a code resets the 5-attempt counter, which allows an unbounded brute force of the 6-digit code. Attaching an address already held by another account ends in an unmapped `500`.

**Approach:** Add a Mailpit container to the dev stack and have `start.sh` run the backend with mail on and pointed at it. Add an application-level issuance throttle for recovery codes, keyed by the target account and shared across both purposes, and answer a duplicate-address attach with a silent `202`.

**Decisions (Timo, 2026-09-28):**
- **D1 Scope split:** the original AC1 (beta SMTP at netcup, SPF/DKIM, real-send smoke test) is deferred to the beta-infra work (`deferred-work.md`). This story covers local Mailpit and endpoint hardening only.
- **D2 Duplicate address, interim:** attaching an address already held by another account answers **`202` and sends nothing**. Nothing is set or stored, and nothing distinguishes it from success. This is a stop-gap: the full ownership model (the dup-address UX and squatting by an unconfirmed attach) gets redesigned in a follow-up task (`deferred-work.md`).

## Boundaries & Constraints

**Always:** Mail stays **off by default** (`sgart.identity.mail.enabled=false`), and the `dev` profile does NOT enable it, because `dev` is also the test/CI default. Only `start.sh` turns it on via env. The throttle is keyed by the **pseudonymous `KeycloakUserId`**, never the email, and holds no PII. Detach must NOT reset the throttle. `request-recovery` keeps its constant `202` (no enumeration) even when throttled. Only synthetic addresses in tests (`*@example.test`).

**Never:** No beta/prod SMTP config, secrets handling, or SPF/DKIM work (deferred). No reverse proxy or IP-based limiting. No new DB table or migration: the throttle is in-memory, which suits the single-node beta (a restart resets the counters and attackers can't trigger restarts). No change to the code format, TTL, or attempt cap. No squatting fix or other ownership-model redesign (D2 follow-up).

## I/O & Edge-Case Matrix

Throttle policy (per target account, both purposes combined): ≥ 60 s between issues, ≤ 5 issues per rolling 24 h.

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Attach, first time | caller, valid free address | `202`, code sent (visible in Mailpit locally) | N/A |
| Attach, too soon / over cap | 2nd attach < 60 s, or 6th in 24 h | nothing set, stored, or sent | `429 account.recoveryCodeRateLimited` |
| Detach + re-attach loop | attach → detach → attach < 60 s | throttle still applies | `429` as above |
| Recover request, throttled | known address, target over budget | `202`, no code stored/sent | silent (constant response) |
| Recover request, unknown address | no account | `202`, nothing sent, no throttle entry | N/A |
| Attach, address on another account | Keycloak PUT → `409` | `202`, no code stored/sent; caller's account unchanged | silent (D2) |
| Window rolls | 24 h after the first issue in the window | issuing allowed again | N/A |

</frozen-after-approval>

## Code Map

- `docker-compose.yml` -- dev infra; add the `mailpit` service following the existing service/healthcheck pattern.
- `scripts/start.sh` -- the backend `bootRun` env block (~line 90); the tier-1 log line lists the containers.
- `docs/first-real-world-test.md:401-420` -- the „Recover by email" section; add local-Mailpit usage and keep the prod text as the deferred part.
- `backend/src/main/resources/application.yaml:48-60` -- `spring.mail.*`; `smtp.auth`/`starttls.enable` are hard-coded `true` today (Mailpit has neither).
- `backend/.../identity/application/AttachRecoveryEmail.java`, `RequestEmailRecoveryCode.java` -- the two issuing services; the throttle check goes before any side effect.
- `backend/.../identity/application/SetAccountEmail.java` + `adapter/out/KeycloakAdminCreateAccount.java:179 updateUser` (a PUT with no status handling; `setEmail` goes through it; the 409-suppression pattern is at `:79`) + `DeferredSetAccountEmail`.
- `backend/.../identity/application/RecoveryCode.java` -- the code policy constants (TTL, MAX_ATTEMPTS); reuse, don't duplicate.
- `backend/.../identity/adapter/out/IdentityBeansConfig.java` -- wires the 7.3 services; add the throttle bean.
- `backend/.../identity/adapter/in/AccountErrorAdvice.java` -- maps application exceptions to `4xx` `ErrorDescriptor`.
- `app/lib/shared/errors/error_message_resolver.dart:45-48` -- `account.*` code → l10n; `app/lib/l10n/app_de.arb`.
- Tests: `backend/src/test/.../identity/application/{AttachRecoveryEmailTest,RequestEmailRecoveryCodeTest}.java`, `adapter/out/KeycloakAdminCreateAccountTest.java`, `adapter/in/AccountControllerTest.java`, `app/test/shared/errors/error_message_resolver_test.dart`.

## Tasks & Acceptance

**Execution:**
- [x] `docker-compose.yml` -- add `mailpit` (`axllent/mailpit:v1.31.3`, current stable), ports `1025` SMTP / `8025` UI, healthcheck `["CMD", "/mailpit", "readyz"]` -- a local mail catcher, joined by `--wait`.
- [x] `backend/src/main/resources/application.yaml` -- make `smtp.auth` / `starttls.enable` env-overridable (`SGART_SMTP_AUTH`, `SGART_SMTP_STARTTLS`, both defaulting to `true`) -- Mailpit accepts plain unauthenticated SMTP; prod defaults stay secure.
- [x] `scripts/start.sh` -- backend env: `SGART_IDENTITY_MAIL_ENABLED=true`, `SGART_SMTP_PORT=1025`, `SGART_SMTP_AUTH=false`, `SGART_SMTP_STARTTLS=false`, `SGART_IDENTITY_MAIL_FROM=no-reply@sgart.local`; log the Mailpit UI URL `http://localhost:8025`; mention mailpit in the header and tier-1 log.
- [x] `backend/.../identity/application/RecoveryCodeIssuanceThrottle.java` (new port) + `adapter/out/InMemoryRecoveryCodeIssuanceThrottle.java` (new, `Clock`-driven, thread-safe, prunes expired windows) -- the matrix policy; one `tryIssue(KeycloakUserId)` → boolean.
- [x] `backend/.../identity/application/RecoveryCodeRateLimitedException.java` (new) + `AttachRecoveryEmail` -- check first; throttled → throw before `setEmail`. `RequestEmailRecoveryCode` -- after lookup, throttled → return silently.
- [x] `SetAccountEmail.setEmail` → returns `boolean` (`false` = address held by another account); `KeycloakAdminCreateAccount` suppresses only `409` on that PUT and returns `false`; `DeferredSetAccountEmail` returns `true`. `AttachRecoveryEmail`: on `false`, return without storing or sending -- D2, and no exception is used for control flow.
- [x] `AccountErrorAdvice` -- `RecoveryCodeRateLimitedException` → `429`.
- [x] `IdentityBeansConfig` -- wire the throttle into both services.
- [x] `app/lib/l10n/app_de.arb` + `error_message_resolver.dart` -- `account.recoveryCodeRateLimited` → „Zu viele Anfragen. Bitte warte kurz und versuche es erneut.".
- [x] Tests (TDD) -- a throttle unit test (cooldown, daily cap, window roll, per-account isolation, detach-independence); the two service tests (throttled attach sends/sets nothing; throttled recover returns silently, sends nothing); attach with a taken address stores and sends nothing and returns normally; a Keycloak adapter `409` → `false` test (MockRestServiceServer, as existing); controller `429` mapping; the Flutter resolver test.
- [x] `docs/first-real-world-test.md` -- a local Mailpit how-to (start.sh, read the code at `:8025`); point the prod SMTP part at the deferred item.

**Acceptance Criteria:**
- Given `scripts/start.sh` on the dev machine, when the stack is up and the tester attaches or recovers by email on the emulator, then the 6-digit code shows up in Mailpit's web UI and completes the flow end-to-end.
- Given a plain `./gradlew test` / CI run, when the context starts, then no SMTP server is needed and the `Deferred` mail adapter is wired (unchanged).
- Given any throttled request from the matrix, then the response is `429` (attach) or the constant `202` (recover), never a `500`.

## Implementation Notes

- Implemented by Sonnet subagent 2026-09-28; both suites green per its run (backend `./gradlew test`, flutter 774 + analyze 0; mailpit container healthy). `SetAccountEmail.setEmail` returns `boolean`; `KeycloakAdminCreateAccount.updateUserSuppressing409`. `InMemoryRecoveryCodeIssuanceThrottle.clear()` wired into `AccountControllerTest` `@BeforeEach` (shared singleton across the cached context).
- Judgment call: a duplicate-address attach still consumes one unit of the caller's own budget (throttle checked before `setEmail`) — affects only the caller's quota, leaks nothing.
- Matrix audit: added `AccountControllerTest.attachEmail_detachThenReattachWithinTheCooldown_isStillThrottled` (the detach+re-attach row had no covering test).

## Spec Change Log

## Review Triage Log

Review 2026-09-28 (Opus; Edge Case Hunter + Verification Gap — Blind Hunter skipped for the thin slice, per review-fanout tuning).

| # | Finding | Verdict | Evidence / route |
|---|---------|---------|------------------|
| 1 | Unauthenticated recover spam for a known address exhausts the victim's shared budget → victim can't recover, and attach answers 429, for 24 h | medium | Real: the per-target shared budget (frozen Approach) makes lockout inherent. Timo: accept for now → deferred into the recovery-email ownership follow-up task. |
| 2 | Keycloak/SMTP failure after `tryIssue` in attach burns a slot | low | Real, infra-failure-only; the fix adds a release API → rejected (low + added complexity). |
| 3 | Same as #2 on the recover path | low | Same as #2 → rejected. |
| 4 | Throttle map never shrinks for accounts that don't return | low | Real, but each entry holds ≤5 instants and they're bounded by accounts that ever got a code; a sweep adds complexity → rejected. |
| 5 | Entry exactly at first+24h not pruned (`isBefore`) → denied at precisely 24 h | low | Real, contradicts the matrix; one-token fix → patch. |
| 6 | start.sh prints "codes land in Mailpit" even when reusing a backend started without mail | low | Real → patch (log only in the start branch, warn otherwise). |
| 7 | Window-roll test advances past the last issuance; the rolling boundary isn't verified | low | Real (test claim) → patch, merged with #5. |
| 8 | New tests use `@example.com`, spec says `*@example.test` | low | Both are RFC 2606 reserved/synthetic; the existing tests in the same file use `example.com` → rejected (the synthetic-data intent is met). |
| 9 | Matrix "window rolls at 24 h" not met exactly | low | Same root cause as #5 → patch. |
| 10 | (VG) Recover-path throttle wiring + shared attach/recover budget only verified via stubs | medium | Gap pre-verified; the recover path never reaches `tryIssue` in the controller context (Deferred `FindAccountByEmail`) → patch (integration tests). |
| 11 | (VG) SMTP auth/STARTTLS secure defaults untested | low | Pre-verified; prod SMTP is deferred (D1) → defer. |
| 12 | (VG other) A D2 taken-address attach consumes a budget slot | low | Real; documented judgment call in Implementation Notes; the user never learns it was taken anyway (silent 202) → rejected. |

## Design Notes

**Why key by target account, not caller or IP:** the harm is to the *recipient* (inbox spam, brute force of the code sent to them). Callers are free, because provisioning is unauthenticated, so per-caller limits are bypassable. A per-target budget bounds the worst case to 5 codes × 5 attempts = 25 guesses/day against 10⁶ codes.


## Verification

**Commands:**
- `cd backend && ./gradlew test` -- expected: BUILD SUCCESSFUL (incl. ArchUnit)
- `cd app && flutter test && flutter analyze` -- expected: all pass, 0 issues
- `docker compose up -d --wait` -- expected: mailpit healthy

**Manual checks:**
- `scripts/start.sh`, then Profil → „E-Mail hinzufügen": the code appears at `http://localhost:8025` and confirming it works.
