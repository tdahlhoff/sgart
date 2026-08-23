import 'package:flutter_appauth/flutter_appauth.dart';

import 'keycloak_config.dart';
import 'oidc_client.dart';
import 'oidc_tokens.dart';

/// Real [OidcClient] backed by `flutter_appauth` — Authorization Code + PKCE against the SGART
/// Keycloak realm, public client, no client secret on the device (AC1).
class AppAuthOidcClient implements OidcClient {
  const AppAuthOidcClient([this._appAuth = const FlutterAppAuth()]);

  final FlutterAppAuth _appAuth;

  @override
  Future<OidcTokens> signIn() async {
    final response = await _appAuth.authorizeAndExchangeCode(
      AuthorizationTokenRequest(
        KeycloakConfig.clientId,
        KeycloakConfig.redirectUri,
        issuer: KeycloakConfig.issuer,
        scopes: KeycloakConfig.scopes,
      ),
    );
    final accessToken = response.accessToken;
    if (accessToken == null) {
      throw StateError('Keycloak returned no access token');
    }
    return OidcTokens(
      accessToken: accessToken,
      refreshToken: response.refreshToken,
      idToken: response.idToken,
    );
  }

  @override
  Future<OidcTokens> refresh(String refreshToken) async {
    final response = await _appAuth.token(
      TokenRequest(
        KeycloakConfig.clientId,
        KeycloakConfig.redirectUri,
        issuer: KeycloakConfig.issuer,
        refreshToken: refreshToken,
        grantType: GrantType.refreshToken,
        scopes: KeycloakConfig.scopes,
      ),
    );
    final accessToken = response.accessToken;
    if (accessToken == null) {
      throw StateError('Keycloak returned no access token on refresh');
    }
    return OidcTokens(
      accessToken: accessToken,
      // Keycloak rotates the refresh token; fall back to the old one if the response omits it.
      refreshToken: response.refreshToken ?? refreshToken,
      idToken: response.idToken,
    );
  }

  @override
  Future<void> endSession({required String? idToken}) async {
    await _appAuth.endSession(
      EndSessionRequest(
        idTokenHint: idToken,
        issuer: KeycloakConfig.issuer,
        postLogoutRedirectUrl: KeycloakConfig.redirectUri,
      ),
    );
  }
}
