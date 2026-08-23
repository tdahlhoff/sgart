import 'oidc_tokens.dart';

/// The Authorization Code + PKCE boundary against Keycloak (AC1). SGART never sees or stores a
/// password — only the resulting [OidcTokens]. Abstracted so [AuthCubit] tests never touch a real
/// OIDC library or network (CLAUDE.md §6).
abstract interface class OidcClient {
  /// Runs the Authorization Code + PKCE flow, opening the system browser for sign-in.
  Future<OidcTokens> signIn();

  /// Exchanges a stored refresh token for a fresh set of [OidcTokens] without any user
  /// interaction, so a session survives the short access-token lifespan. Throws when the refresh
  /// token is itself expired or revoked.
  Future<OidcTokens> refresh(String refreshToken);

  /// Ends the Keycloak SSO session where the library supports it. Sign-out clears the local
  /// tokens regardless of whether this call succeeds (AC3).
  Future<void> endSession({required String? idToken});
}
