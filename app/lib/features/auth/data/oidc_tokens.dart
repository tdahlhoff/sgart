/// The tokens returned by a completed OIDC Authorization Code + PKCE exchange.
class OidcTokens {
  const OidcTokens({required this.accessToken, this.refreshToken, this.idToken});

  final String accessToken;
  final String? refreshToken;

  /// Needed to end the Keycloak SSO session on sign-out.
  final String? idToken;

  @override
  bool operator ==(Object other) =>
      other is OidcTokens &&
      other.accessToken == accessToken &&
      other.refreshToken == refreshToken &&
      other.idToken == idToken;

  @override
  int get hashCode => Object.hash(accessToken, refreshToken, idToken);
}
