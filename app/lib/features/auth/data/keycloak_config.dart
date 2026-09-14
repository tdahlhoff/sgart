/// The SGART Keycloak realm's public app-client coordinates (Story 1.4, Task 1; Story 7.1 dropped
/// the browser Authorization Code + PKCE endpoints — the app now signs in via the token endpoint's
/// custom Direct-Grant flow only). Dev-only defaults match `keycloak/realm-sgart.json`; override
/// the issuer per environment via `--dart-define=SGART_KEYCLOAK_ISSUER=...` (e.g. an Android
/// emulator reaching the host via `10.0.2.2` instead of `localhost`).
abstract final class KeycloakConfig {
  static const String issuer = String.fromEnvironment(
    'SGART_KEYCLOAK_ISSUER',
    defaultValue: 'http://localhost:8080/realms/sgart',
  );

  static const String clientId = 'sgart-app';

  static const String tokenEndpoint = '$issuer/protocol/openid-connect/token';
  static const String logoutEndpoint = '$issuer/protocol/openid-connect/logout';
}
