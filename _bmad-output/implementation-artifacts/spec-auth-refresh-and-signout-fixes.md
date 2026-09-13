---
title: 'Centralize 401 refresh-and-retry, add session-expired message, add pre-household sign-out'
type: 'bugfix'
created: '2026-09-13'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
context: []
baseline_commit: '0ec2cefa0679514d647c0c514703b85e6ef2b61c'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Found during the first Android manual test pass: (1) only the post-login `/me` call refreshes an expired access token and retries — every other authenticated call 401s outright after ~5 minutes, surfacing a generic error; (2) that 401 (and a cold-start refresh failure) shows the generic fallback instead of a clear "session expired" message; (3) there is no way to sign out on `CreateOrAwaitChoicePage`/`AwaitInvitePage` — before a household exists, `Profil`/sign-out isn't reachable at all.

**Approach:** Move refresh-and-retry-once-on-401 into `AuthenticatedHttpClient` itself (behind an optional callback) so every authenticated call benefits, not just `/me`; drop `AuthCubit`'s now-redundant ad hoc retry; add an `auth.unauthorized` entry to the error-message resolver with Timo's exact copy; add a small sign-out icon button, reusing the existing `AuthCubit.signOut()` call, to the two named pre-household screens only (not the onboarding wizard — deferred, see decision below).

**Decisions locked in:**
- Sign-out icon button scope = `CreateOrAwaitChoicePage` + `AwaitInvitePage` only. The onboarding wizard is out of scope for this slice (Timo's choice, 2026-09-13).
- `AuthenticatedHttpClient`'s new refresh callback is **optional** (nullable), not required — mirrors `AuthCubit.pushNotifications`'s existing "optional dependency" precedent so the ~40 existing `AuthenticatedHttpClient(...)` call sites in unrelated API tests (`invites_api_test.dart`, `households_api_test.dart`, `trips_api_test.dart`, `members_api_test.dart`, `backend_device_registration_client_test.dart`) need no changes.
- `openEventStream` (SSE) is untouched — it already has its own reconnect handling from the live-sync epic; a 403 there must never retry (existing invariant).

## Boundaries & Constraints

**Always:**
- Retry each request **once** on `auth.unauthorized`; a second failure (refresh itself fails, or the retried request still 401s) propagates the `AppException` exactly as today — no loop.
- Reuse `AuthCubit.signOut()` verbatim for the new icon buttons — no confirmation dialog, matching the existing Profil-tab button.
- Keep `IdentityApi.fetchMe()` going through `AuthenticatedHttpClient` unchanged; since it will now get the centralized retry too, `AuthCubit._loadCallerIdentity`'s own `allowRefresh`/`_tryRefreshTokens` wrapping becomes redundant — remove it, not leave it as dead code.

**Never:**
- Do not add a request-dedup/mutex for concurrent 401s across simultaneous calls — accepted low-risk edge case (single-user manual beta), not in scope.
- Do not touch `openEventStream`/SSE reconnection.
- Do not add the sign-out button to the onboarding wizard steps (deferred per decision above).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Expired token, valid refresh token | Any authenticated call (not just `/me`) 401s | Refreshed once, request retried once, succeeds transparently | None surfaced |
| Expired token, refresh also fails | Same, but refresh token invalid/expired | Original `auth.unauthorized` propagates once | UI shows "Deine Sitzung ist abgelaufen, melde dich neu an." |
| Cold-start resume with dead refresh token | `AuthCubit.bootstrap()` -> `fetchMe()` 401, refresh fails | Tokens cleared, lands on `SignInPage` | Same session-expired message via `sign-in-error` |
| Tap sign-out icon pre-household | User on `CreateOrAwaitChoicePage` or `AwaitInvitePage` | `AuthCubit.signOut()` runs, returns to `SignInPage` | N/A |

</frozen-after-approval>

## Code Map

- `app/lib/shared/http/authenticated_http_client.dart` -- add optional `Future<bool> Function()? refreshTokens` ctor param; wrap the 6 verb methods (`getJson`/`getJsonList`/`postJson`/`patchJson`/`putJson`/`deleteJson`) in one shared retry-once helper. Leave `openEventStream` and `_mapToAppError` untouched.
- `app/lib/features/auth/presentation/auth_cubit.dart` -- rename `_tryRefreshTokens` to public `tryRefreshTokens()` (same body); remove the `allowRefresh` param and its branch from `_loadCallerIdentity` (now dead once the transport retries `/me` itself).
- `app/lib/features/auth/presentation/auth_gate.dart:41-44` (`_buildAuthCubit`) -- pass `refreshTokens: cubit.tryRefreshTokens` into its `AuthenticatedHttpClient`.
- `app/lib/features/households/presentation/first_run_router.dart:65` (`initState`) -- same wiring for its separate `AuthenticatedHttpClient` instance, reading `context.read<AuthCubit>()`.
- `app/lib/shared/errors/error_message_resolver.dart` -- add `'auth.unauthorized' => localizations.authSessionExpiredError` to the switch (already consumed by `SignInPage`'s `sign-in-error` display — no widget change needed there).
- `app/lib/l10n/app_de.arb` -- add `authSessionExpiredError`: "Deine Sitzung ist abgelaufen, melde dich neu an." with an `@authSessionExpiredError` description entry, following the `authSignOutButtonLabel` pattern.
- `app/lib/features/households/presentation/create_or_await_choice_page.dart:27-28` -- add `actions: [IconButton(icon: Icons.logout, tooltip: localizations.authSignOutButtonLabel, onPressed: () => context.read<AuthCubit>().signOut())]` to the existing `SgartAppBar` (already supports `actions`).
- `app/lib/features/households/presentation/await_invite_page.dart:105-106` -- same addition.
- `app/lib/shared/widgets/sgart_app_bar.dart:9-19,45-51` -- reference only, no change; confirms `actions` is already supported.

## Tasks & Acceptance

**Execution:**
- [x] `app/test/shared/http/authenticated_http_client_test.dart` -- add cases: 401-then-refresh-succeeds retries transparently; 401-then-refresh-fails (and 401-again-after-retry) propagates once, no loop -- extend the existing `_FakeHttpClientAdapter` to vary its response per call count
- [x] `app/lib/shared/http/authenticated_http_client.dart` -- implement the optional refresh callback + shared retry-once wrapper
- [x] `app/test/features/auth/presentation/auth_cubit_test.dart` -- update any test relying on the removed `allowRefresh` retry branch; keep/add coverage for public `tryRefreshTokens()`
- [x] `app/lib/features/auth/presentation/auth_cubit.dart` -- expose `tryRefreshTokens()`, simplify `_loadCallerIdentity`
- [x] `app/lib/features/auth/presentation/auth_gate.dart` -- wire the callback
- [x] `app/lib/features/households/presentation/first_run_router.dart` -- wire the callback
- [x] `app/test/shared/errors/error_message_resolver_test.dart` -- add the `auth.unauthorized` case, following the file's one-`test()`-per-code pattern
- [x] `app/lib/shared/errors/error_message_resolver.dart` -- add the mapping
- [x] `app/lib/l10n/app_de.arb` -- add the ARB entry (regenerates `AppLocalizations` via `flutter gen-l10n`)
- [x] `app/test/features/households/presentation/create_or_await_choice_page_test.dart` -- add `BlocProvider<AuthCubit>.value` (via `buildAuthenticatedAuthCubit()`), assert tapping the icon signs out
- [x] `app/lib/features/households/presentation/create_or_await_choice_page.dart` -- add the icon button
- [x] `app/test/features/households/presentation/await_invite_page_test.dart` -- same test addition
- [x] `app/lib/features/households/presentation/await_invite_page.dart` -- add the icon button

**Acceptance Criteria:**
- Given a signed-in user whose access token expired but refresh token is valid, when any authenticated command/query runs, then it succeeds with no visible error (not just `/me`).
- Given a signed-in user whose refresh token is also invalid, when an authenticated call 401s, then the UI shows "Deine Sitzung ist abgelaufen, melde dich neu an." instead of the generic fallback.
- Given a user on `CreateOrAwaitChoicePage` or `AwaitInvitePage`, when they tap the sign-out icon, then they land on `SignInPage`, identical in effect to the Profil-tab sign-out.

## Implementation Notes

- All 13 tasks completed as specified; verified against the diff (not just the implementing agent's report). `AuthenticatedHttpClient`'s 6 verb methods were renamed to private `_getJson`/etc. and re-exposed as public wrappers calling `_withRefreshRetry`, exactly matching the Design Notes shape.
- `_loadCallerIdentity`'s obsolete `allowRefresh` retry branch was removed as planned; the corresponding `bootstrap_refreshesTheAccessTokenAndResumesWhenTheStoredAccessTokenIsExpired` test (which asserted the old internal retry) was removed and replaced with direct `tryRefreshTokens()` unit tests plus new transport-level retry tests in `authenticated_http_client_test.dart` — behavior coverage preserved, relocated to match where the logic now lives.
- `test/support/fake_auth_dependencies.dart`'s `FakeIdentityApi.enqueue`/`fetchMeCallCount` scaffolding (only used by the removed retry test) was deleted as dead code.
- Full suite: `flutter analyze` clean, `flutter test` 657/657 passing (verified independently after the implementation subagent's report, not just taken on its word).

## Review Triage Log

**Pass 1** (3 layers: blind-hunter, edge-case-hunter, verification-gap):

- [verification-gap] Production `refreshTokens` wiring in `auth_gate.dart`/`first_run_router.dart` never exercised by a test that constructs the real widgets. — **low**: wiring verified correct by direct reading (both lines type-check and reference the right method); fix requires a new Dio-injection seam in both files, disproportionate to a two-line risk.
- [verification-gap] `getJsonList`/`patchJson`/`putJson`/`deleteJson` have no 401-retry test (only `getJson`/`postJson` do). — **medium**: current code is correct (all 6 verbs delegate through the same `_withRefreshRetry`), but a future regression in any of the 4 untested verbs — the write path most command traffic uses — would go undetected; fix is a trivial mirror of the existing test pattern.
- [blind-hunter] Same 4/6-verb test gap as above. — **medium**: same evidence, same claim.
- [blind-hunter] `RefreshTokens` typedef reads as a verb phrase, unlike sibling `AccessTokenProvider`'s noun style. — **low**: verified via diff; trivial rename fixes it.
- [blind-hunter] Concurrent 401s each independently call `refreshTokens`, racing on a shared refresh token; spec's "Never" section may understate this given centralization widens the race window. — **maybe-false**: `keycloak/realm-sgart.json` does not set `revokeRefreshToken` (Keycloak default `false`, so reuse of a not-yet-rotated refresh token is tolerated today); could not confirm Keycloak's actual effective behavior without inspecting the running instance. If true, medium (spurious session-expired shown to the "losing" concurrent call, self-healing).
- [blind-hunter] `refreshTokens` callback's "must never throw" contract (that `_withRefreshRetry`'s no-loop guarantee depends on) isn't documented on the typedef. — **low**: verified true; trivial doc-comment fix.
- [blind-hunter] `AuthenticatedHttpClient`'s class-level doc comment doesn't mention its new refresh-and-retry responsibility. — **low**: verified true; trivial doc update.
- [blind-hunter] Cold-start bootstrap-resumes-after-refresh scenario has no `AuthCubit`-level composed test proving it end-to-end. — **low**: the transport-level `getJson_retriesTransparentlyAfterA401WhenRefreshSucceeds` test already exercises this exact scenario via the `/api/v1/identity/me` path, so the underlying behavior is proven; a further `AuthCubit`-level composed test would only re-prove the same thing via a parallel construction, not guard the actual `auth_gate.dart` wiring lines — same reasoning as the production-wiring finding above.
- [blind-hunter] No test asserts the new icon button's `tooltip` is wired to `authSignOutButtonLabel`. — **low**: verified true (only the sign-out behavior is asserted); trivial one-line test addition.
- [blind-hunter] Spec's Code Map pins line numbers that will go stale as other work touches those files. — rejected outright (fix would mean editing this build's own spec).
- [edge-case-hunter] Same concurrent-401/refresh-token-rotation race as blind-hunter's finding above. — **maybe-false**: same evidence and disposition.
- [edge-case-hunter] `signOut()` can race with an in-flight `tryRefreshTokens()`: if the refresh resolves after `signOut()` has already cleared storage/tokens, it re-persists a fresh session, silently resurrecting a session the user just explicitly ended. — **high**: verified true by tracing `tryRefreshTokens()` (no generation check) against `signOut()` (bumps `_sessionGeneration` then clears everything) — a real, if timing-dependent, privacy-relevant defect that this change's centralization meaningfully widens the exposure window for (retry can now fire from any authenticated call at any time, not just right after `signIn()`/`bootstrap()`). Fix: reuse the existing `_sessionGeneration` guard, exactly as `_registerPushTokenBestEffort` already does in the same file.
- [edge-case-hunter] Same production-wiring/removed-test concern as blind-hunter's/verification-gap's findings above (deletion of `bootstrap_refreshesTheAccessTokenAndResumesWhenTheStoredAccessTokenIsExpired`). — **low**: same disposition — already proven at the transport level, composed `AuthCubit`-level test would not add real regression protection over what exists.
- [edge-case-hunter] `first_run_router.dart`'s `_FailurePage` always shows the hardcoded `householdsLoadFailedError` text regardless of `HouseholdsState.error.code`, so a households-bootstrap 401 with a dead refresh token never shows the new session-expired copy. — **medium** (real, verified: `_FailurePage` doesn't call `localizedMessageForErrorCode` at all, for any error code), but **pre-existing and untouched by this diff** — not caused or newly exposed by this change (the pre-change behavior for this screen was identical for every error code, auth or otherwise).

**Groups after dedup, and routes:**
- 4/6-verb retry test gap → **patch**
- `RefreshTokens` naming → **patch**
- Concurrent refresh-token race → **defer** (`deferred-work.md`)
- `refreshTokens` throw-contract undocumented → **patch**
- `AuthenticatedHttpClient` class doc stale → **patch**
- `signOut()`/`tryRefreshTokens()` race → **patch**
- Production-wiring/cold-start-bootstrap not composed-tested → **rejected** (low, fix disproportionate, current code verified correct)
- Icon button "verbatim" comment → **patch**
- Icon button tooltip untested → **patch**
- Spec Code Map staleness → **rejected** (fix would edit the spec)
- `_FailurePage` hardcoded message → **defer** (`deferred-work.md`, pre-existing)

## Design Notes

Shared retry wrapper shape for `authenticated_http_client.dart` (each verb method's existing try/catch body moves inside `send`):

```dart
Future<T> _withRefreshRetry<T>(Future<T> Function() send) async {
  try {
    return await send();
  } on AppException catch (exception) {
    if (exception.error.code == 'auth.unauthorized' && refreshTokens != null && await refreshTokens!()) {
      return send(); // one retry; a second failure propagates as-is
    }
    rethrow;
  }
}
```

## Verification

**Commands:**
- `cd app && flutter test` -- expected: all pass, including new/updated cases above
- `cd app && flutter analyze` -- expected: clean
