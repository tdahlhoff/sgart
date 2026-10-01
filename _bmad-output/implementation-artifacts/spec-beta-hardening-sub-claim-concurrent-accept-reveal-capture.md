---
title: 'Beta hardening: sub-less JWT, concurrent same-user accept, reveal-screen capture guard'
type: 'bugfix'
created: '2026-10-01'
status: 'done'
baseline_commit: '09e05e2488336eb823c8d1460d65dcbbf3f56563'
route: 'dispatch'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Three small beta-readiness defects from `deferred-work.md`: (1) a validated JWT with no `sub` claim NPEs in `AuthenticatedCaller.fromJwt` → opaque 500 on every controller; (2) two concurrent first-time accepts by the same person (two devices) race on `persist`'s check-then-act: either the loser hits the unique index → 500, or (worse, the likelier interleaving) the loser's `persist` silently no-ops, it appends `MemberJoined` with its own different `MemberId`, and the winner's compensating `retract` then deletes the only mapping → phantom member in the stream, no access for the person; (3) the recovery-token reveal screen shows the account secret with no screen-capture/recording protection (CLAUDE.md §5 security by default).

**Approach:** (1) Reject sub-less tokens at the edge with a JWT validator composed in `SecurityConfig` (standard 401), mirroring `AudienceValidator`. (2) Make a lost mapping race throw a new identity `MemberMappingConflictException` (code `membership.mappingConflict`, → 409 via `WriteErrorAdvice`) from `persist`, before the append's try block so no `retract` runs; the user retries manually and `provision` then finds the winner's mapping. (3) Set Android `FLAG_SECURE` only while the reveal page is mounted, via a small platform channel; iOS and the recovery *input* field are out of scope.

## Boundaries & Constraints

**Always:** Fail fast, one place per fix; no abbreviations; domain/port stays infrastructure-free (the JDBC adapter translates `DuplicateKeyException`); tests accompany each change (regression test first).

**Never:** App-wide `FLAG_SECURE` (would block all screenshots and the app-switcher preview); a third-party screen-protection package; handling the missing `sub` per-controller; changing `provision`/`retract` semantics or the accept wire format; touching the deferred `confirmAndRebind` / recovery-email ownership goals.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Token without `sub` | Signed, valid iss/aud, no/blank `sub` | `401` from the resource-server chain | Spring `invalid_token` |
| Lost mapping race | Same user+household, mapping already saved with a different `MemberId` (or unique-index violation on save) | `MemberMappingConflictException` → `409` `membership.mappingConflict`; nothing appended; the winner's mapping survives | Manual retry converges via `provision` |
| Idempotent persist | Mapping already saved with the same `MemberId` | No-op, no exception | N/A |
| Reveal page mounted | Android | `FLAG_SECURE` set on mount, cleared on dispose | Non-Android / no channel: silently no-op |

</frozen-after-approval>

## Code Map

- `backend/src/main/java/de/sgart/identity/adapter/in/security/AudienceValidator.java` -- pattern to mirror (package-private `OAuth2TokenValidator<Jwt>`)
- `backend/src/main/java/de/sgart/identity/adapter/in/security/SecurityConfig.java:66` -- `DelegatingOAuth2TokenValidator` composition point
- `backend/src/main/java/de/sgart/identity/adapter/in/security/AuthenticatedCaller.java` -- `fromJwt`; stays as is (validator guarantees `sub`)
- `backend/src/main/java/de/sgart/identity/application/IssueMemberIdentity.java` -- `persist` check-then-act
- `backend/src/main/java/de/sgart/identity/adapter/out/JdbcMemberMappingRepository.java` -- `save` (unique index `idx_identity_member_mapping_household_keycloak`)
- `backend/src/main/java/de/sgart/identity/domain/MemberMappingRepository.java` -- port; document the conflict contract
- `backend/src/main/java/de/sgart/shared/ConcurrencyConflictException.java` -- NOT reusable here (only `(AggregateVersion, AggregateVersion)` ctor, fixed code `concurrency.staleVersion`); pattern to mirror for the new exception
- `backend/src/main/java/de/sgart/collaboration/adapter/in/WriteErrorAdvice.java:89` -- global advice; already imports `identity.application.NotAMemberException`, so mapping an identity application exception here has precedent
- `backend/src/main/java/de/sgart/collaboration/application/command/AcceptInviteHandler.java:97` -- `persist` runs before the append try block, so a throw skips `retract`
- `backend/src/main/java/de/sgart/identity/adapter/out/InMemoryMemberMappingRepository.java:46` -- `save` silently overwrites today; must honour the new conflict contract
- `app/lib/shared/errors/error_message_resolver.dart` -- add the retry copy for the new code
- `backend/src/test/java/de/sgart/identity/adapter/in/security/AudienceValidatorTest.java`, `.../application/IssueMemberIdentityTest.java`, `.../adapter/out/JdbcMemberMappingRepositoryTest.java` -- test patterns
- `app/lib/features/auth/presentation/recovery_token_reveal_page.dart` -- page to guard (`initState`/`dispose`)
- `app/android/app/src/main/kotlin/de/sgart/sgart/MainActivity.kt` -- add the channel handler
- `app/test/features/auth/presentation/recovery_token_reveal_page_test.dart` -- existing page tests

## Tasks & Acceptance

**Execution:**
- [x] `SecurityConfig.java` -- extract the validator composition into a testable factory and add `new JwtClaimValidator<String>(JwtClaimNames.SUB, subject -> subject != null && !subject.isBlank())` (no hand-written class, KISS) -- wires it for every `/api/v1/**` call
- [x] `SecurityConfigTest` (new) -- the composed validator rejects a token without/with blank `sub` (MockMvc `jwt()` skips the decoder, so test the validator itself)
- [x] `identity/application/MemberMappingConflictException.java` -- new exception carrying an `ErrorDescriptor` with code `membership.mappingConflict`
- [x] `WriteErrorAdvice.java` -- map it to 409
- [x] `IssueMemberIdentityTest` / `InMemoryMemberMappingRepositoryTest` -- failing tests first: different-id persist conflicts, same-id is a no-op, in-memory `save` on an existing pair conflicts
- [x] `IssueMemberIdentity.java` -- `persist` throws `MemberMappingConflictException` when the pair is already mapped to a different `MemberId`
- [x] `InMemoryMemberMappingRepository.java` -- `save` on an existing pair throws the same exception
- [x] `JdbcMemberMappingRepository.java` (+ `JdbcMemberMappingRepositoryTest`) -- translate `DuplicateKeyException` on `save` to it
- [x] `MemberMappingRepository.java` -- document the `save` conflict contract
- [x] `AcceptInviteHandlerTest` -- race regression: loser gets the conflict, appends nothing, and the winner's mapping survives
- [x] `error_message_resolver.dart` + ARB + test -- map `membership.mappingConflict` to a short German retry message
- [x] `app/lib/shared/platform/screen_capture_guard.dart` + test -- `MethodChannel` wrapper (`protect`/`release`), swallow `MissingPluginException`
- [x] `recovery_token_reveal_page.dart` + page test -- protect in `initState`, release in `dispose`
- [x] `MainActivity.kt` -- handle `protect`/`release` by adding/clearing `WindowManager.LayoutParams.FLAG_SECURE`

**Acceptance Criteria:**
- Given a valid token lacking `sub`, when any `/api/v1/**` endpoint is called, then the response is 401, not 500.
- Given two concurrent first-time accepts by one person, when the second `persist` loses, then the response is 409 `membership.mappingConflict`, no `MemberJoined` is appended, the winner's mapping survives, and a manual retry succeeds.
- Given the reveal page is open on Android, when it is disposed, then `FLAG_SECURE` is cleared and other screens stay capturable.

## Implementation Notes

- Layering: the port's `save` throws a new *domain* `MemberMappingAlreadyExistsException` (so `adapter.out` never imports `application`); `IssueMemberIdentity.persist` translates it, and the different-id case, into the application `MemberMappingConflictException` (`membership.mappingConflict`), which `WriteErrorAdvice` maps to 409 (ArchUnit-clean).
- `SecurityConfig.tokenValidator` is a package-private static factory (testable without a signed token); `sub` is checked with Spring's `JwtClaimValidator`.
- `InMemoryMemberMappingRepository.seed` still overwrites (test seeding); only `save` enforces the conflict.
- Tests were written together with the production code, not strictly red-first.
- Verified: backend `./gradlew test` BUILD SUCCESSFUL (incl. ArchUnit, Testcontainers); app `flutter test` 788 passed, `flutter analyze` 0 issues. The Android `FLAG_SECURE` handler (`MainActivity.kt`) is not covered by an automated test — manual emulator check still open.

## Spec Change Log

## Review Triage Log

Pass 1 (Opus, 3 layers: Blind Hunter, Edge Case Hunter, Verification Gap).

| Finding | Verdict | Route | Evidence |
|---|---|---|---|
| Retry in the conflict window can strand a member: loser's retry reuses the winner's mapping and appends first, then the winner's failed append retracts that mapping (Blind, Edge x2) | high | defer | Real, but pre-existing: the `provision`/`persist`/`retract` compensation (Story 4.2) retracts by (person, household) regardless of who else committed with that id; the fix (skip retract when the household now contains the member) adds a guard and re-read, so it is not trivial. |
| Create-household path can also hit the 409, and the copy said "Beitreten" (Blind) | low | patch | Copy and javadocs made neutral. |
| `AcceptInviteHandler.handle` javadoc missing the new 409 (Blind) | low | patch | `@throws` added. |
| `MemberMappingConflictException` drops the cause (Blind) | low | patch | Cause constructor added and passed. |
| Any `DuplicateKeyException` reported as a person collision; PK `(household_id, member_id)` (Blind, Edge) | low | reject | A `MemberId` is a freshly generated UUID, so a PK collision is unreachable; naming the constraint adds complexity. |
| In-memory `save` not atomic (Blind) | low | reject | Single-threaded test double; the conflict contract it models is the uniqueness invariant. |
| Restore screen's typed-in token field is unprotected (Blind) | medium | defer | Real exposure, excluded by the approved spec; deferred. |
| No ref counting on protect/release (Blind, Edge) | low | reject | Guarded screens never stack today (YAGNI). |
| iOS has no protection and it is not recorded (Blind) | medium | defer | Recorded in deferred-work. |
| Unawaited futures / `PlatformException` not caught (Blind, Edge) | low | patch | `unawaited`, `PlatformException` caught, test added. |
| `SecurityConfigTest` does not cover wrong issuer/audience (Blind) | low | patch | Two tests added. |
| Magic number `hasSize(3)` (Blind) | low | reject | Same style as the surrounding tests in the file. |
| Test placement next to nested helper (Blind) | low | reject | Cosmetic. |
| Widget test does not exercise `Navigator.pop` (Blind) | low | reject | `dispose` is the unit; pop disposes the same state. |
| `persist` lost-insert translation untested (Verification Gap) | medium | patch | `persist_translatesALostInsertRaceIntoAMappingConflictAndKeepsTheWinnersMapping` added. |
| 409 mapping only tested by calling the handler method (Verification Gap) | low | reject | Not reachable through the full-context controller test (real beans cannot produce the stale read); `WriteErrorAdviceTest` is the repo's established pattern for such guards. |
| Native `FLAG_SECURE` handler has no automated test (Verification Gap) | medium | defer | No Android instrumentation setup exists; manual emulator check is the gate. |
| `jwtDecoder` wiring to `tokenValidator` untested (Verification Gap, note) | low | reject | One-line wiring. |

## Verification

**Commands:**
- `cd backend && ./gradlew test` -- expected: BUILD SUCCESSFUL (incl. ArchUnit, Testcontainers)
- `cd app && flutter test && flutter analyze` -- expected: all pass, 0 issues

**Manual checks (if no CLI):**
- On the emulator, open Profil → recovery token; a screenshot of that screen is blocked, others are not.
