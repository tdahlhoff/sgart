import 'package:sgart/features/auth/data/caller_identity.dart';
import 'package:sgart/features/auth/data/identity_api.dart';
import 'package:sgart/features/auth/data/oidc_client.dart';
import 'package:sgart/features/auth/data/oidc_tokens.dart';
import 'package:sgart/features/auth/data/secure_token_storage.dart';

/// Test doubles for [AuthCubit]'s three external boundaries — no real OIDC library, device
/// storage, or network in tests (CLAUDE.md §6).
class FakeOidcClient implements OidcClient {
  OidcTokens? tokensToReturn;
  OidcTokens? refreshedTokensToReturn;
  Object? signInErrorToThrow;
  Object? refreshErrorToThrow;
  Object? endSessionErrorToThrow;
  bool endSessionCalled = false;
  String? lastEndSessionIdToken;
  String? lastRefreshToken;

  @override
  Future<OidcTokens> signIn() async {
    if (signInErrorToThrow != null) throw signInErrorToThrow!;
    return tokensToReturn!;
  }

  @override
  Future<OidcTokens> refresh(String refreshToken) async {
    lastRefreshToken = refreshToken;
    if (refreshErrorToThrow != null) throw refreshErrorToThrow!;
    return refreshedTokensToReturn!;
  }

  @override
  Future<void> endSession({required String? idToken}) async {
    endSessionCalled = true;
    lastEndSessionIdToken = idToken;
    if (endSessionErrorToThrow != null) throw endSessionErrorToThrow!;
  }
}

class FakeSecureTokenStorage implements SecureTokenStorage {
  OidcTokens? storedTokens;
  bool cleared = false;

  @override
  Future<void> save(OidcTokens tokens) async => storedTokens = tokens;

  @override
  Future<OidcTokens?> read() async => storedTokens;

  @override
  Future<void> clear() async {
    cleared = true;
    storedTokens = null;
  }
}

class FakeIdentityApi implements IdentityApi {
  CallerIdentity? identityToReturn;
  Object? errorToThrow;
  int fetchMeCallCount = 0;
  final List<Object> _responseQueue = [];

  /// Enqueues outcomes ([CallerIdentity] to return, anything else to throw) served in order across
  /// successive [fetchMe] calls — used to exercise the fail-then-retry refresh path. Takes
  /// precedence over [identityToReturn]/[errorToThrow] while non-empty.
  void enqueue(Object outcome) => _responseQueue.add(outcome);

  @override
  Future<CallerIdentity> fetchMe() async {
    fetchMeCallCount++;
    if (_responseQueue.isNotEmpty) {
      final outcome = _responseQueue.removeAt(0);
      if (outcome is CallerIdentity) return outcome;
      throw outcome;
    }
    if (errorToThrow != null) throw errorToThrow!;
    return identityToReturn!;
  }
}
