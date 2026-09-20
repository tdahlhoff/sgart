---
title: 'Story 8.2: Session never expires on a trusted device (silent device-credential re-auth)'
type: 'bugfix'
created: '2026-09-20'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
context: []
baseline_commit: '945caee66eaaaee9be69c9cbac50e8403331ae6b'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** On a trusted device the session still visibly dies. The shipped
`spec-auth-refresh-and-signout-fixes` makes an *expired access token* transparent (refresh + retry
once), but when the OAuth **refresh token** itself is dead (e.g. after a Keycloak restart, or once
the refresh token outlives its own lifespan), `AuthCubit.tryRefreshTokens()` returns `false`, the
`auth.unauthorized` propagates, and the person is shown „Deine Sitzung ist abgelaufen…" — even
though the device still holds the credential SGART could silently re-authenticate with forever
(manual test 2026-09-20, F1/BH-2). Timo's explicit requirement: on a trusted device the app should
behave like Instagram — keep working, no „session expired" message and no „try again" button on the
normal path; a visible re-auth prompt is reserved for the genuine special case where even the
device credential can no longer authenticate.

**Approach:** Layer a **silent device-credential re-auth beneath the OAuth refresh**, inside
`AuthCubit`, wired in as `AuthenticatedHttpClient`'s existing refresh callback. A new
`tryReauthenticate()` first tries `tryRefreshTokens()` (unchanged fast path); if that fails it runs
the browserless device-credential Direct-Grant sign-in (`OidcClient.signIn()` — load device key →
idempotent provision → signed challenge → fresh tokens), updates the in-memory `_tokens` **without
emitting any `AuthState`**, and returns `true`, so the in-flight request retries transparently and
the person sees nothing. Only when even the device sign-in throws does it return `false`, letting
the original `auth.unauthorized` propagate — and *then* the surfaces that show it (the shell's
error copy and the households-bootstrap `_FailurePage`) present the localized session-expired copy
plus a **working** re-auth action that re-runs `AuthCubit.signIn()` (which reaches the
create/await-invite choice screen with its recovery-phrase / recover-by-email options). Concurrent
401s share a single in-flight re-auth `Future` so a not-yet-rotated refresh token can never cause a
spurious session-expired (folds in the deferred concurrent-refresh-race item).

**Decisions locked in (Timo, 2026-09-20):**
- **AC3 terminal path = propagate + reuse `signIn()` retry** (not a forced global route-to-sign-in).
  Smallest change; a transient network failure during device re-auth never nukes a still-valid
  session; the recovery paths stay reachable via the sign-in/choice screen.
- **Reactive-only** refresh. Proactive/pre-expiry refresh is explicitly **out of scope** for 8.2 —
  the epics.md AC is reactive, and reactive refresh+retry already makes expiry invisible
  (KISS/YAGNI). Do not add a timer or pre-flight refresh.
- **Client-only (Flutter).** The device-credential Direct-Grant SPI (`backend/keycloak-authenticator`)
  and the unauthenticated provisioning endpoint already exist from Epic 7; no backend, domain,
  event, read-model, or migration change.

## Boundaries & Constraints

**Always:**
- On the normal path (access token expired and/or refresh token dead, device credential present),
  recover **silently**: no `AuthState` churn to `inProgress`/`unauthenticated`, no navigation flash,
  no „session expired" message, no „try again" button — the failed request just retries and
  succeeds.
- Retry each request **exactly once** on `auth.unauthorized` (the existing `_withRefreshRetry`
  no-loop guarantee): one callback call now does refresh-then-device-signin internally, then one
  retry. A second failure propagates the `AppException` as-is.
- The refresh callback (now `tryReauthenticate`) **must never throw** — a failed re-auth reports
  itself by returning `false`, exactly as `TokenRefresher`'s documented contract requires.
- Concurrent 401s from parallel calls share **one** in-flight re-auth: the second caller awaits the
  same `Future<bool>`, never starting a second refresh/sign-in against the same refresh token.
- The device-credential sign-in must re-derive the **same** identity (`OidcClient.signIn()` is
  idempotent for an existing account, Story 7.1 AC3) — never mint a new throwaway identity on the
  silent path.
- All user-facing strings stay in the `de-DE` localization layer; the failure surfaces resolve copy
  via `localizedMessageForErrorCode(localizations, error.code)`, never hard-coded text.

**Never:**
- No proactive/timer-based refresh (deferred per decision above).
- No new product scope: no new screens, no new backend/Keycloak/SPI work, no new domain/events.
- Do not touch `openEventStream`/SSE reconnection (it has its own reconnect handling; a 403 there
  must never retry — existing invariant).
- Do not add a forced global sign-out/route on terminal re-auth failure — AC3 reuses the existing
  `signIn()` retry surface (locked decision).
- Do not emit `AuthState` from the silent success path — only the terminal-failure surfaces react.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Expired access token, valid refresh token | Any authenticated call 401s | `tryRefreshTokens()` succeeds; request retried once; succeeds transparently (preserved from the shipped spec) | None surfaced |
| Dead refresh token, device credential present | Authenticated call 401s; `tryRefreshTokens()` returns false | Silent device-credential Direct-Grant sign-in refreshes `_tokens` with no `AuthState` emit; request retried once; succeeds | None surfaced |
| Concurrent 401s (parallel calls) | Several authenticated calls 401 at once | A single shared in-flight re-auth `Future` resolves once; all callers retry after it | No spurious session-expired |
| Device re-auth also fails | Refresh dead AND `signIn()` throws (credential gone/invalid, or Keycloak rejects) | `tryReauthenticate()` returns false; original `auth.unauthorized` propagates once (no loop) | Shell shows „Deine Sitzung ist abgelaufen…"; action re-runs `AuthCubit.signIn()` (working re-auth → choice/recovery) |
| Households bootstrap 401 with terminal re-auth failure | `HouseholdsCubit.bootstrap()` → `_FailurePage` with `error.code == auth.unauthorized` | `_FailurePage` shows the session-expired copy (not the generic „couldn't load households") and offers a working re-auth action | Action calls `AuthCubit.signIn()`; non-auth errors keep the existing `HouseholdsCubit.bootstrap()` retry |

</frozen-after-approval>

## Code Map

- `app/lib/features/auth/presentation/auth_cubit.dart` -- add `Future<bool> tryReauthenticate()`:
  try `tryRefreshTokens()` first; on failure run `_oidcClient.signIn()` → `_tokenStorage.save` →
  set `_tokens` (no `_safeEmit`), returning `true`; catch-and-log → return `false`. Add the single
  in-flight guard (`Future<bool>? _inFlightReauth`, cleared on completion with an `identical` check
  so a later call can start fresh). `tryRefreshTokens()` stays as the inner fast-path step. This is
  the whole silent-recovery layer — deliberately does **not** touch `AuthState`.
- `app/lib/features/auth/presentation/auth_gate.dart:56` -- change the wired callback from
  `refreshTokens: () => cubit.tryRefreshTokens()` to `refreshTokens: () => cubit.tryReauthenticate()`.
- `app/lib/features/households/presentation/first_run_router.dart:73` -- same one-line rewire on
  `FirstRunRouter`'s separate `AuthenticatedHttpClient` instance.
- `app/lib/features/households/presentation/first_run_router.dart:274` (`_FailurePage`) -- read the
  failed `HouseholdsState.error` and resolve copy via
  `localizedMessageForErrorCode(localizations, error.code)` instead of the hardcoded
  `householdsLoadFailedError`; when `error.code == 'auth.unauthorized'`, the action runs
  `context.read<AuthCubit>().signIn()` (working re-auth path), otherwise it keeps
  `context.read<HouseholdsCubit>().bootstrap()`. `_FailurePage` needs the state, so read it from the
  `HouseholdsCubit` at this seam (the switch at line ~171 already sits under the cubit).
- `app/lib/shared/http/authenticated_http_client.dart` -- **reference only, no change.**
  `_withRefreshRetry` already calls the optional `refreshTokens` callback once and retries once;
  pointing it at `tryReauthenticate` needs no transport change. Confirm the class/typedef docs still
  read true (the callback now also does device re-auth).
- `app/lib/shared/errors/error_message_resolver.dart:43` -- reference only: `'auth.unauthorized' =>
  localizations.authSessionExpiredError` already exists; `_FailurePage` reuses it.
- `app/lib/features/auth/data/oidc_client.dart` / `direct_grant_oidc_client.dart` -- reference only:
  `signIn()` (loadOrCreate → idempotent `provision` → signed challenge) is the silent re-auth
  primitive; provisioning does not require the dead access token.
- `app/test/support/fake_auth_dependencies.dart` -- existing fakes (`FakeOidcClient`,
  `FakeSecureTokenStorage`, `FakeIdentityApi`, `buildAuthenticatedAuthCubit()`); extend the fake
  OIDC client so `signIn()` can be made to succeed-with-fresh-tokens or throw, and `refresh()` to
  fail, to drive the layered paths.
- `app/test/shared/http/authenticated_http_client_test.dart` -- existing retry-once transport tests
  (`_FakeHttpClientAdapter` varies response per call count); the transport is unchanged, but assert
  the callback is invoked once and the retry uses the refreshed token.
- `app/test/features/auth/presentation/auth_cubit_test.dart` -- home for the new
  `tryReauthenticate()` unit tests (refresh-succeeds fast path; refresh-fails→device-signin-succeeds
  silently with no state emit; both fail→false; concurrent callers share one in-flight future).
- `app/test/features/households/presentation/first_run_router_test.dart` -- home for the
  `_FailurePage` copy/action tests (auth.unauthorized → session-expired copy + `signIn()` action;
  non-auth error → generic copy + `bootstrap()` action).

## Tasks & Acceptance

**Execution:**
- [x] `app/test/features/auth/presentation/auth_cubit_test.dart` -- add failing tests:
  `tryReauthenticate_returnsTrueViaRefreshWhenTheRefreshTokenIsValid`;
  `tryReauthenticate_silentlyReAuthenticatesViaTheDeviceCredentialWhenTheRefreshTokenIsDead`
  (asserts fresh tokens are stored **and no new `AuthState` is emitted** — the session stays
  authenticated);
  `tryReauthenticate_returnsFalseWhenBothTheRefreshAndTheDeviceSignInFail`;
  `tryReauthenticate_sharesASingleInFlightAttemptForConcurrentCallers` (two concurrent calls trigger
  exactly one `signIn()`/`refresh`).
- [x] `app/lib/features/auth/presentation/auth_cubit.dart` -- implement `tryReauthenticate()` (refresh
  fast path → silent device sign-in fallback → in-flight de-dup guard), never throwing, never
  emitting on success.
- [x] `app/lib/features/auth/presentation/auth_gate.dart` -- rewire the refresh callback to
  `tryReauthenticate`.
- [x] `app/lib/features/households/presentation/first_run_router.dart` -- rewire the refresh callback
  to `tryReauthenticate`.
- [x] `app/test/features/households/presentation/first_run_router_test.dart` -- add failing tests:
  `theFailurePageShowsTheSessionExpiredCopyAndOffersAWorkingReAuthWhenTheErrorIsUnauthorized`;
  `theFailurePageShowsTheGenericCopyAndRetriesBootstrapForANonAuthError`.
- [x] `app/lib/features/households/presentation/first_run_router.dart` -- update `_FailurePage` to
  resolve copy via `localizedMessageForErrorCode` and branch its action on `auth.unauthorized`
  (`signIn()`) vs otherwise (`bootstrap()`).
- [x] `app/test/shared/http/authenticated_http_client_test.dart` -- confirm/extend that the retry uses
  the refreshed token and the callback fires once (transport unchanged; guard against regression).
  No change needed: the existing suite already asserts this against the generic `refreshTokens`
  callback shape, which is unaffected by what `AuthCubit` now passes in.
- [x] Update the two folded-in `deferred-work.md` entries (concurrent-refresh race; `_FailurePage`
  hardcoded copy) to resolved, pointing at this story.

**Acceptance Criteria:**
- Given an expired access token with a still-valid refresh token, when any authenticated call runs,
  then it refreshes and retries transparently with no visible message (preserved from the shipped
  spec).
- Given a dead OAuth refresh token on a trusted device (device credential still present), when an
  authenticated call fails auth, then the app silently re-authenticates via the device-credential
  Direct-Grant challenge and retries the original request — no visible message, no „try again"
  button, no navigation flash (no `AuthState` churn).
- Given even device re-authentication fails (credential gone/invalid), when auth cannot be restored,
  then — and only then — the person is shown the localized session-expired copy with a working
  re-auth action (`AuthCubit.signIn()`) that reaches the choice/recovery surface — no dead-end, no
  repeated-tap no-op.
- Given concurrent 401s from parallel calls, when they trigger re-auth at once, then a single
  in-flight attempt is shared, so a not-yet-rotated refresh token never causes a spurious
  session-expired.
- Given the households-bootstrap `_FailurePage`, when it shows an auth error, then it uses the
  correct localized copy and offers a working re-auth path (not the generic „couldn't load
  households" text with a dead-end retry).
- Given story completion, when the build is called green, then it names both suites: backend
  `./gradlew test` (incl. ArchUnit) and `flutter test` / `flutter analyze` (CLAUDE.md §6). This is a
  Flutter presentation/auth-wiring change with no backend/domain/event/read-model file touched, so
  the app suite is the meaningful gate — state that explicitly.

## Implementation Notes

`AuthCubit.tryReauthenticate()` is intentionally **not** `async`: it returns `_performReauthenticate()`'s
`Future<bool>` directly, assigning it to `_inFlightReauth` before returning. Because a non-`async`
function runs synchronously up to its `return`, two back-to-back calls with no `await` between them
(the concurrent-401 case) are guaranteed to see the guard already populated on the second call — no
race window. `_inFlightReauth` is cleared via `whenComplete` with an `identical` check so a later,
separate attempt is never mistaken for the just-finished one.

`_FailurePage` was a `StatelessWidget` with no state read at all (it never showed
`HouseholdsState.error`); it now reads `context.watch<HouseholdsCubit>().state.error` to resolve
copy/action. The `_LoadingPage` sibling was left untouched.

No changes were needed to `AuthenticatedHttpClient`, its typedef usage sites, or
`authenticated_http_client_test.dart`'s behavior — only the `TokenRefresher` typedef's doc comment
was updated to describe the now-layered contract; the transport itself is agnostic to what the
callback does internally.

`FakeOidcClient` (test support) gained `signInCallCount`/`refreshCallCount` and optional
`signInGate`/`refreshGate` `Completer`s so the concurrency test can hold a `refresh()` call open long
enough to prove a second concurrent caller never starts a second one.

## Spec Change Log

## Review Triage Log

Review round 1 (Opus 4.8; Blind Hunter + Edge-Case Hunter + Verification-Gap, 2026-09-20).

| # | Finding | Verdict | Route | Evidence |
|---|---------|---------|-------|----------|
| 1 | `auth_cubit.dart:136-138` — `_loadCallerIdentity`'s doc still says the 401 is retried "via `tryRefreshTokens`, wired in as its refresh callback"; the wired callback is now `tryReauthenticate`. | low | patch | Confirmed stale at the cited lines — both wiring sites (`auth_gate.dart:56`, `first_run_router.dart:74`) pass `tryReauthenticate`. Boy-Scout/accuracy. |
| 2 | Positive `_FailurePage` test can't tell `signIn()` from `bootstrap()` — asserts only `authCubit.state.status == authenticated`, which holds for both branches (cubit is pre-authenticated in setUp). | medium | patch | Confirmed: reverting the branch to `bootstrap()` still leaves `authCubit` authenticated, so the test stays green on the regressed behavior. `FakeHouseholdsApi.listCallCount` (already exists) discriminates: `signIn()`→stays 1, `bootstrap()`→2. |
| 3 | `fake_auth_dependencies.dart` adds `signInGate` but no test uses it; the in-flight-sharing test only gates the refresh fast path, never the device-sign-in fallback (the expensive path the guard exists to protect). | low | patch | Confirmed dead field (CLAUDE.md no-dead-code) + real coverage gap. Fix closes both: add a concurrent-callers test that gates `signIn()` and asserts `signInCallCount == 1`. |
| 4 | Magic string `'auth.unauthorized'` duplicated again in `first_run_router.dart:290`; no shared constant (also in `auth_cubit`, `authenticated_http_client` ×2, resolver). | low | defer | Pre-existing DRY issue across 4+ sites; this story adds one line consistent with the existing pattern. Extracting a shared constant is a cross-cutting refactor not caused by this change. |
| 5 | Both new `_FailurePage` tests assert the literal German copy `'Deine Sitzung ist abgelaufen…'` rather than resolving `localizations.authSessionExpiredError`. | low | reject | Matches existing test style in the repo (literal-copy assertions are used elsewhere); everyday harm nil (only a copy tweak breaks it) and the fix adds test plumbing. Rejected per low-rule. |
| 6 | Successful silent re-auth logs nothing (only the failure branch logs); CLAUDE.md §5 auditability. | low | reject | A client-side `developer.log` is not the GDPR audit mechanism (that is server-side access logging); the normal `signIn()` success path logs nothing either, so a success log only here would be inconsistent. |
| 7 | Device fallback swaps tokens without re-running `_loadCallerIdentity` (no identity/membership re-verification), unlike `signIn()`. | false | reject | Not reachable as described: `OidcClient.signIn()` is idempotent over the fixed device key → re-derives the *same* identity (7.1 AC3), so identity can't switch; membership is surfaced by the retried request per the locked Design Notes (reactive, no `AuthState` emit). |
| 8 | `deferred-work.md` puts "✅ RESOLVED (…)" into the structured `summary:` field. | false | reject | Matches the file's own established convention — existing resolved entries (lines 33/114/118) all lead the summary with "✅ RESOLVED (date, story) —". |
| 9 | The `tryReauthenticate` rewiring at the two `AuthenticatedHttpClient` construction sites is unverified — no test drives a 401 through the wired client to prove the device fallback is reachable. | medium (unverified) | defer | Real regression gap (reverting either line to `tryRefreshTokens` fails no test), but closing it needs an integration-style widget test pumping a 401→200 through a real Dio MockAdapter — heavier than this change's unit-level style. VG layer itself dispositioned defer. |
| 10 | Race: session cleared (`_isRejectedSession`) while device `signIn()` is in flight → re-auth resurrects `_tokens`/storage after invalidation. | maybe-false | reject | Reachability undemonstrated: a `/me` 401 goes through the same de-duped `tryReauthenticate`, so a concurrent independent clear+successful-signIn interleaving is not shown reachable; resurrecting storage for the *same* idempotent identity is self-healing on next launch. Guarding it adds complexity for unshown state. |
| 11 | Retry tap could throw `ProviderNotFoundException` if `AuthCubit` is not provided above `_FailurePage`. | false | reject | `_FirstRunRouterState.initState` (`first_run_router.dart:69`) already does `context.read<AuthCubit>()`, so `AuthCubit` (provided by `AuthGate` at `auth_gate.dart:37`) is a proven ancestor of the whole subtree, `_FailurePage` included. |
| 12 | Non-auth bootstrap failures now resolve to `errorGenericFallback` instead of the households-specific `householdsLoadFailedError` (which becomes practically unreachable since failures always carry a non-null error). | low | reject | Spec-intended: the Code Map directs resolving all codes via `localizedMessageForErrorCode`, and `errorGenericFallback` for an unmapped code is the app-wide resolver convention. Copy stays sensible; retry still works. Cosmetic. |

Routing: no intent_gap/bad_spec → no loopback. Patches 1–3 applied; findings 4 and 9 appended to `deferred-work.md`.

## Design Notes

The recovery ladder is **refresh → silent device re-auth → (only then) visible re-auth**, and the
crux is that the first two rungs never touch `AuthState`. The bearer interceptor reads
`cubit.currentAccessToken` (`_tokens?.accessToken`), so replacing `_tokens` in place is all the
retried request needs — no re-mount of `FirstRunRouter`, no flip to `SignInPage`, hence „the person
sees nothing." Reusing the existing `AuthenticatedHttpClient` refresh-callback seam (rather than a
new transport concept) keeps the single-retry/no-loop guarantee intact: one callback invocation now
encapsulates *both* the OAuth refresh and the device-credential fallback, then the transport retries
exactly once.

Silent device re-auth uses `OidcClient.signIn()` directly (not `AuthCubit.signIn()`, which emits
`inProgress`/`authenticated`) — the difference between a seamless retry and a visible reset. It is
safe to call under a dead session because provisioning is the *unauthenticated* Epic-7 endpoint and
`signIn()` is idempotent for an existing account, so it re-derives the same identity rather than
minting a throwaway.

The single in-flight guard (a cached `Future<bool>` cleared on completion with an `identical` check)
folds in the deferred concurrent-401 race: it also defuses the not-yet-rotated-refresh-token hazard
noted in `deferred-work.md`, since parallel callers no longer each POST the same refresh token.

`_FailurePage` is the one place a *terminal* auth failure now lands after the silent ladder is
exhausted (a households-bootstrap 401 that even device re-auth could not fix), so it is exactly where
AC5's „correct copy + working path" belongs — resolve via `localizedMessageForErrorCode` and branch
the action on the error code, reusing the already-mapped `auth.unauthorized → authSessionExpiredError`
entry. No new ARB key is required.

Explicitly **not** built: proactive/pre-expiry refresh (out of scope per the locked decision — the
reactive ladder already makes expiry invisible), any backend/SPI change, and any forced global
sign-out on terminal failure.

## Verification

**Commands run (2026-09-20):**
- `cd app && flutter test test/features/auth/ test/features/households/ test/shared/http/` --
  PASS (172 tests), including the new `tryReauthenticate` (4), `_FailurePage` (2), and existing
  transport-retry tests.
- `cd app && flutter test` -- PASS, full app suite green (743 tests, 0 failures).
- `cd app && flutter analyze` -- PASS, 0 issues.
- Backend untouched (no backend/domain/event/read-model/migration file changed) — per AC6 the app
  suite above is the meaningful gate; `./gradlew test` was **not** run since no backend/shared file
  was touched (confirmed via the diff: only `app/lib`, `app/test`, and this artifact changed).

**Manual checks (emulator) -- NOT performed in this session** (no emulator/device attached to this
run); flagged as open verification below.

- Sign in, sit on a household-scoped screen, restart the backend/Keycloak (killing the refresh
  token), then perform a read and a write (e.g. rename a list): both must succeed silently with no
  „Sitzung abgelaufen" message and no navigation flash.
- Simulate a genuinely lost device credential (fresh install / cleared app data): confirm the
  visible re-auth path actually re-authenticates and reaches the choice/recovery screen (no
  dead-end, no repeated-tap no-op).
