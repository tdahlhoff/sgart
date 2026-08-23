import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';
import '../data/identity_api.dart';
import '../data/oidc_client.dart';
import '../data/oidc_tokens.dart';
import '../data/secure_token_storage.dart';
import 'auth_state.dart';

/// Drives sign-in (Authorization Code + PKCE), token storage, the post-login identity call, and
/// sign-out (AC1, AC2, AC3). Depends only on the [OidcClient]/[SecureTokenStorage]/[IdentityApi]
/// interfaces so tests never touch a real OIDC library, device storage, or network.
class AuthCubit extends Cubit<AuthState> {
  AuthCubit({required this._oidcClient, required this._tokenStorage, required this._identityApi})
      : super(const AuthState.unauthenticated());

  final OidcClient _oidcClient;
  final SecureTokenStorage _tokenStorage;
  final IdentityApi _identityApi;

  OidcTokens? _tokens;

  /// The access token the bearer interceptor attaches, or `null` when signed out. This cubit owns
  /// the in-memory session, so the HTTP client reads it from here instead of decrypting secure
  /// storage on every request.
  String? get currentAccessToken => _tokens?.accessToken;

  /// Resumes a session from previously stored tokens, if any. Called once when the app starts.
  Future<void> bootstrap() async {
    final storedTokens = await _tokenStorage.read();
    if (storedTokens == null) {
      return;
    }
    _tokens = storedTokens;
    await _loadCallerIdentity();
  }

  Future<void> signIn() async {
    _safeEmit(const AuthState.inProgress());
    try {
      final tokens = await _oidcClient.signIn();
      await _tokenStorage.save(tokens);
      _tokens = tokens;
      await _loadCallerIdentity();
    } on Object catch (error) {
      _safeEmit(AuthState.failure(_toAppError(error)));
    }
  }

  /// Wipes local tokens and returns to the unauthenticated gate even when ending the Keycloak SSO
  /// session fails (AC3) — a subsequent protected call then has no bearer and is rejected.
  Future<void> signOut() async {
    try {
      await _oidcClient.endSession(idToken: _tokens?.idToken);
    } on Object {
      // Local sign-out must still succeed.
    }
    await _tokenStorage.clear();
    _tokens = null;
    _safeEmit(const AuthState.unauthenticated());
  }

  /// Calls the backend identity endpoint and reflects the outcome in the state.
  ///
  /// A transient failure (server unreachable) keeps the stored tokens so the next launch can
  /// resume the session — only a definitive rejection wipes them (see [_isRejectedSession]). An
  /// expired access token (401) is retried once against a fresh token obtained with the stored
  /// refresh token before it is treated as a rejection.
  Future<void> _loadCallerIdentity({bool allowRefresh = true}) async {
    try {
      final identity = await _identityApi.fetchMe();
      _safeEmit(AuthState.authenticated(identity.displayName));
    } on Object catch (error) {
      final appError = _toAppError(error);
      if (allowRefresh && appError.code == 'auth.unauthorized' && await _tryRefreshTokens()) {
        await _loadCallerIdentity(allowRefresh: false);
        return;
      }
      if (_isRejectedSession(appError)) {
        await _tokenStorage.clear();
        _tokens = null;
      }
      _safeEmit(AuthState.failure(appError));
    }
  }

  /// Exchanges the stored refresh token for a fresh access token. Returns whether it succeeded; a
  /// failed refresh (missing/expired refresh token) leaves the caller to treat the 401 as final.
  Future<bool> _tryRefreshTokens() async {
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

  void _safeEmit(AuthState state) {
    if (!isClosed) {
      emit(state);
    }
  }
}
