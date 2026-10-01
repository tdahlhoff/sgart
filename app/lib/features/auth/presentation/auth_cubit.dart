import 'dart:async';
import 'dart:developer' as developer;

import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';
import '../../../shared/push/push_notifications.dart';
import '../../households/data/active_household_store.dart';
import '../data/device_credential_store.dart';
import '../data/identity_api.dart';
import '../data/oidc_client.dart';
import '../data/oidc_tokens.dart';
import '../data/secure_token_storage.dart';
import 'auth_state.dart';

/// Drives sign-in (Story 7.1: silent device-account provisioning + a browserless Direct-Grant
/// exchange, superseding Story 1.4's Authorization Code + PKCE browser flow), token storage, and
/// the post-login identity call (AC1, AC2). Depends only on the
/// [OidcClient]/[SecureTokenStorage]/[IdentityApi] interfaces so tests never touch real
/// cryptography, device storage, or network.
///
/// There is deliberately no sign-out here any more: the device credential [OidcClient.signIn]
/// provisions is permanent (only an app reinstall/data wipe clears it), so a person cannot
/// meaningfully leave their session — [bootstrap] would just silently re-derive and re-sign-in as
/// the same identity on the next launch. The removed sign-out UI (Story 7.1 code review) left no
/// working "signed out" resting state for a person to land in.
///
/// [pushNotifications] is optional (Story 4.5, AC5) — most existing call sites (and most tests)
/// have no interest in push registration, mirroring `HouseholdShell`'s guarded-optional
/// `eventStreamFactory`. When present: registers the device token once sign-in resolves to an
/// authenticated session.
class AuthCubit extends Cubit<AuthState> {
  AuthCubit({
    required this._oidcClient,
    required this._tokenStorage,
    required this._identityApi,
    required this._deviceCredentialStore,
    required this._activeHouseholdStore,
    this._pushNotifications,
  }) : super(const AuthState.unauthenticated());

  final OidcClient _oidcClient;
  final SecureTokenStorage _tokenStorage;
  final IdentityApi _identityApi;

  /// The device credential's entropy/recovery-token primitives (Story 7.2, token format Story 8.5)
  /// — [recoverFromToken] is the only method that touches this; every other flow keeps going
  /// through [_oidcClient].
  final DeviceCredentialStore _deviceCredentialStore;

  /// Cleared on a successful recovery (AD-7, D-E) so a recovered identity on a shared/reused
  /// device never inherits the throwaway account's active-household selection.
  final ActiveHouseholdStore _activeHouseholdStore;
  final PushNotifications? _pushNotifications;

  OidcTokens? _tokens;

  /// The access token the bearer interceptor attaches, or `null` when signed out. This cubit owns
  /// the in-memory session, so the HTTP client reads it from here instead of decrypting secure
  /// storage on every request.
  String? get currentAccessToken => _tokens?.accessToken;

  /// Resumes a session from previously stored tokens, or — on first launch, or any relaunch with
  /// no stored session — silently provisions the device's account and signs in with no input and
  /// no browser surface (Story 7.1, AC1). Called once when the app starts; this is what makes the
  /// create/await-invite choice (Story 1.6) the very first thing a person ever sees, with no
  /// intervening sign-in screen.
  Future<void> bootstrap() async {
    final storedTokens = await _tokenStorage.read();
    if (storedTokens == null) {
      await signIn();
      return;
    }
    _tokens = storedTokens;
    await _loadCallerIdentity();
  }

  /// Runs [OidcClient.signIn] (silent provisioning + the browserless Direct-Grant exchange, Story
  /// 7.1) and reflects the outcome in the state. Called by [bootstrap] on every launch with no
  /// stored session; also the retry action a failure screen offers (e.g. after a transient network
  /// failure) — there is no separate manual "sign in" trigger, since there is nothing for a person
  /// to enter.
  Future<void> signIn() async {
    _safeEmit(const AuthState.inProgress());
    try {
      final tokens = await _oidcClient.signIn();
      await _tokenStorage.save(tokens);
      _tokens = tokens;
      await _loadCallerIdentity();
    } on Object catch (error) {
      // Logged rather than swallowed silently: sign-in is the very first thing the app does, so a
      // failure here strands a person on the generic error screen with no diagnostic trail —
      // exactly the case where field debugging otherwise has nothing to go on.
      developer.log('Silent provisioning / sign-in failed — showing the retry screen',
          name: 'sgart.auth', error: error);
      _safeEmit(AuthState.failure(_toAppError(error)));
    }
  }

  /// Imports [token] as the device's entropy and swaps to the identity it belongs to (Story 7.2,
  /// AC3, D-E; token format Story 8.5) — an identity swap through the existing `AuthGate` state
  /// machine: going `inProgress → authenticated` re-mounts `FirstRunRouter`, which re-bootstraps
  /// `HouseholdsCubit` for whichever identity is now signed in, with no bespoke re-routing.
  ///
  /// Validation happens *first*, before any state is emitted: [DeviceCredentialStore.restoreFromToken]
  /// throws `InvalidRecoveryToken` (writing nothing) on an invalid token, and this method lets it
  /// propagate untouched. So an invalid token changes neither the enclave nor the app-wide auth
  /// state — the current session stays mounted and the caller shows the error inline (AC3 "changes
  /// nothing"; code review 2026-09-14). Only a *valid* token clears the active-household selection
  /// and re-signs-in via [signIn], which emits `inProgress → authenticated`: `loadOrCreate`
  /// re-derives the *same* username/keypair the imported entropy always derived, so the Direct-Grant
  /// exchange lands back in the existing account — `provision()` is an idempotent no-op there (Story
  /// 7.1 AC3). Any non-token failure (a secure-storage write error) also propagates before an emit,
  /// so it can never strand the UI mid-swap.
  Future<void> recoverFromToken(String token) async {
    await _deviceCredentialStore.restoreFromToken(token);
    await _activeHouseholdStore.clear();
    await signIn();
  }

  /// Swaps identity after a successful server-side R1 rebind (Story 7.3, AC2/AC3, design §1.1):
  /// the caller (`RecoverByEmailPage`) has already driven `AccountEmailApi.confirmRecovery` to a
  /// `204`, which rebound the device's *existing* credential onto the recovered account — no new
  /// key handling needed here, unlike [recoverFromToken]. This just re-runs [signIn] with that
  /// same credential (now authenticating into the recovered account) and clears the active
  /// household (AD-7), mirroring [recoverFromToken]'s identity-swap sequencing exactly.
  Future<void> recoverFromEmailRebind() async {
    await _activeHouseholdStore.clear();
    await signIn();
  }

  /// Calls the backend identity endpoint and reflects the outcome in the state.
  ///
  /// A transient failure (server unreachable) keeps the stored tokens so the next launch can
  /// resume the session — only a definitive rejection wipes them (see [_isRejectedSession]). An
  /// expired access token (401) is now retried transparently by [AuthenticatedHttpClient] itself
  /// (via [tryReauthenticate], wired in as its refresh callback — [tryRefreshTokens] is just that
  /// method's fast-path step) — this method sees only the final outcome.
  Future<void> _loadCallerIdentity() async {
    try {
      final identity = await _identityApi.fetchMe();
      _safeEmit(AuthState.authenticated(identity.displayName, identity.keycloakUserId));
      // Best-effort (Story 4.5, AC5) — a failed registration must never fail the sign-in itself;
      // the app already works fully without a device token, it just misses background pushes.
      unawaited(_registerPushTokenBestEffort());
    } on Object catch (error) {
      final appError = _toAppError(error);
      if (_isRejectedSession(appError)) {
        await _tokenStorage.clear();
        _tokens = null;
      }
      _safeEmit(AuthState.failure(appError));
    }
  }

  /// Exchanges the stored refresh token for a fresh access token. Returns whether it succeeded; a
  /// failed refresh (missing/expired refresh token) leaves the caller to treat the 401 as final.
  /// The inner fast-path step of [tryReauthenticate], which is what actually gets wired into
  /// [AuthenticatedHttpClient]'s refresh callback (Story 8.2) — kept public in its own right since
  /// it stays useful/testable standalone.
  Future<bool> tryRefreshTokens() async {
    final refreshToken = _tokens?.refreshToken;
    if (refreshToken == null) {
      return false;
    }
    try {
      final refreshed = await _oidcClient.refresh(refreshToken);
      await _tokenStorage.save(refreshed);
      _tokens = refreshed;
      return true;
    } on Object {
      return false;
    }
  }

  /// The single in-flight re-auth attempt shared by concurrent callers (Story 8.2) — a not-yet-
  /// rotated refresh token can then never cause two independent callers to race the same refresh
  /// (or double-run the device sign-in). Cleared once the attempt completes, via an `identical`
  /// check so a later, separate attempt is never mistaken for a stale one.
  Future<bool>? _inFlightReauth;

  /// The silent recovery ladder wired into [AuthenticatedHttpClient]'s refresh callback (Story 8.2):
  /// [tryRefreshTokens] first (the unchanged OAuth-refresh fast path); when the refresh token
  /// itself is dead, falls back to the browserless device-credential Direct-Grant sign-in
  /// ([OidcClient.signIn], Story 7.1) to silently re-derive the *same* identity, updating [_tokens]
  /// in place with **no [AuthState] emitted** — the retried request is the only thing that notices.
  /// Only when even that sign-in throws does this return `false`, letting the caller's original
  /// `auth.unauthorized` propagate (the terminal case a failure surface then shows visibly).
  ///
  /// Deliberately not `async` itself: the in-flight [Future] is assigned to [_inFlightReauth]
  /// synchronously before this method returns, so two calls issued back-to-back (no await between
  /// them) are guaranteed to share the same attempt rather than racing to start two.
  ///
  /// Must never throw — [AuthenticatedHttpClient]'s [TokenRefresher] contract requires a failed
  /// re-auth to report itself by returning `false`.
  Future<bool> tryReauthenticate() {
    final inFlight = _inFlightReauth;
    if (inFlight != null) {
      return inFlight;
    }
    final attempt = _performReauthenticate();
    _inFlightReauth = attempt;
    unawaited(attempt.whenComplete(() {
      if (identical(_inFlightReauth, attempt)) {
        _inFlightReauth = null;
      }
    }));
    return attempt;
  }

  Future<bool> _performReauthenticate() async {
    if (await tryRefreshTokens()) {
      return true;
    }
    try {
      final tokens = await _oidcClient.signIn();
      await _tokenStorage.save(tokens);
      _tokens = tokens;
      return true;
    } on Object catch (error) {
      // Logged rather than swallowed silently — see signIn()'s identical rationale: this is the
      // one diagnostic trail a field failure of the *silent* re-auth ladder leaves behind.
      developer.log('Silent device-credential re-auth failed — the session will show as expired',
          name: 'sgart.auth', error: error);
      return false;
    }
  }

  /// Whether an identity failure means the session is definitively invalid and must be cleared:
  /// the token was rejected (401) or the caller is authenticated but not a household member. A
  /// transient or unexpected failure keeps the tokens for a later retry.
  bool _isRejectedSession(AppError error) =>
      error.code == 'auth.unauthorized' || error.code == 'identity.notAMember';

  AppError _toAppError(Object error) {
    if (error is AppException) {
      return error.error;
    }
    return AppError(code: 'auth.unknown', message: error.toString());
  }

  Future<void> _registerPushTokenBestEffort() async {
    try {
      await _pushNotifications?.register();
    } on Object {
      // See the call site's comment — never let a push-registration failure surface as a sign-in
      // failure.
    }
  }

  void _safeEmit(AuthState state) {
    if (!isClosed) {
      emit(state);
    }
  }
}
