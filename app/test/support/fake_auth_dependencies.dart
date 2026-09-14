import 'package:sgart/features/auth/data/caller_identity.dart';
import 'package:sgart/features/auth/data/device_credential.dart';
import 'package:sgart/features/auth/data/device_credential_store.dart';
import 'package:sgart/features/auth/data/identity_api.dart';
import 'package:sgart/features/auth/data/oidc_client.dart';
import 'package:sgart/features/auth/data/oidc_tokens.dart';
import 'package:sgart/features/auth/data/secure_token_storage.dart';
import 'package:sgart/features/auth/presentation/auth_cubit.dart';

import 'fake_households_dependencies.dart';

/// Builds a real [AuthCubit] over fakes and drives it to an authenticated state carrying
/// [displayName]/[email] — for widget tests that need an ancestor `AuthCubit` (e.g. the Profil
/// identity header, Story 1.11) without touching real OIDC/storage/network (CLAUDE.md §6). Data is
/// synthetic (DSGVO). Neither [FakeDeviceCredentialStore] nor [FakeActiveHouseholdStore] is
/// exercised by this happy-path sign-in — they only matter to tests that call
/// [AuthCubit.recoverFromPhrase] directly.
Future<AuthCubit> buildAuthenticatedAuthCubit({
  String displayName = 'Anna Testperson',
  String keycloakUserId = 'sub-1',
  String email = 'anna@example.test',
}) async {
  final cubit = AuthCubit(
    oidcClient: FakeOidcClient()..tokensToReturn = const OidcTokens(accessToken: 'access'),
    tokenStorage: FakeSecureTokenStorage(),
    identityApi: FakeIdentityApi()
      ..identityToReturn =
          CallerIdentity(keycloakUserId: keycloakUserId, displayName: displayName, email: email),
    deviceCredentialStore: FakeDeviceCredentialStore(),
    activeHouseholdStore: FakeActiveHouseholdStore(),
  );
  await cubit.signIn();
  return cubit;
}

/// Test doubles for [AuthCubit]'s external boundaries — no real OIDC library, device storage, or
/// network in tests (CLAUDE.md §6).
class FakeOidcClient implements OidcClient {
  OidcTokens? tokensToReturn;
  OidcTokens? refreshedTokensToReturn;
  Object? signInErrorToThrow;
  Object? refreshErrorToThrow;
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

  @override
  Future<CallerIdentity> fetchMe() async {
    if (errorToThrow != null) throw errorToThrow!;
    return identityToReturn!;
  }
}

/// Test double for [DeviceCredentialStore] (Story 7.2) — no real secure storage or BIP39/Ed25519
/// work in tests that only need *an* `AuthCubit`/`DirectGrantOidcClient` to function. [credential]
/// is settable for tests that assert against a specific, known [DeviceCredential] (e.g.
/// `DirectGrantOidcClient`'s own test, which signs a challenge with it); [wordsToReturn],
/// [lastRestoredWords], and [restoreErrorToThrow] drive the recovery-phrase reveal/restore paths.
class FakeDeviceCredentialStore implements DeviceCredentialStore {
  FakeDeviceCredentialStore({DeviceCredential? credential}) : _seedCredential = credential;

  DeviceCredential? _seedCredential;

  /// Throws if read before a test sets it — callers that need [loadOrCreate]/[credential] to
  /// resolve must supply one explicitly (fail fast: a silently-generated default would hide which
  /// credential a signed challenge actually used).
  DeviceCredential get credential => _seedCredential!;
  set credential(DeviceCredential value) => _seedCredential = value;

  List<String> wordsToReturn = const [];
  List<String>? lastRestoredWords;
  Object? restoreErrorToThrow;
  Object? recoveryPhraseErrorToThrow;

  @override
  Future<DeviceCredential> loadOrCreate() async => credential;

  @override
  Future<List<String>> recoveryPhrase() async {
    if (recoveryPhraseErrorToThrow != null) throw recoveryPhraseErrorToThrow!;
    return wordsToReturn;
  }

  @override
  Future<void> restoreFromPhrase(List<String> words) async {
    lastRestoredWords = words;
    if (restoreErrorToThrow != null) throw restoreErrorToThrow!;
  }
}
