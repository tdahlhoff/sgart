/// The tokens returned by a completed sign-in (Story 7.1: the browserless Direct-Grant exchange;
/// no id_token — sign-out revokes the Keycloak session via [refreshToken] instead).
class OidcTokens {
  const OidcTokens({required this.accessToken, this.refreshToken});

  final String accessToken;
  final String? refreshToken;

  @override
  bool operator ==(Object other) =>
      other is OidcTokens && other.accessToken == accessToken && other.refreshToken == refreshToken;

  @override
  int get hashCode => Object.hash(accessToken, refreshToken);
}
