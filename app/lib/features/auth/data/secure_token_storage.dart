import 'oidc_tokens.dart';

/// Persists [OidcTokens] to the OS Keychain/Keystore only — never plain `SharedPreferences` or
/// in-memory app state that outlives the session (AC1). Abstracted so [AuthCubit] tests never
/// touch real device storage (CLAUDE.md §6).
abstract interface class SecureTokenStorage {
  Future<void> save(OidcTokens tokens);

  Future<OidcTokens?> read();

  /// Deletes every stored token. Called on sign-out regardless of whether ending the Keycloak
  /// SSO session succeeded (AC3).
  Future<void> clear();
}
