/// The SGART Keycloak realm's public app-client coordinates (Story 1.4, Task 1). Dev-only
/// defaults match `keycloak/realm-sgart.json`; override the issuer per environment via
/// `--dart-define=SGART_KEYCLOAK_ISSUER=...` (e.g. an Android emulator reaching the host via
/// `10.0.2.2` instead of `localhost`).
abstract final class KeycloakConfig {
  static const String issuer = String.fromEnvironment(
    'SGART_KEYCLOAK_ISSUER',
    defaultValue: 'http://localhost:8080/realms/sgart',
  );

  static const String clientId = 'sgart-app';

  static const String redirectUri = 'de.sgart.app://oauth/callback';

  static const List<String> scopes = ['openid', 'profile', 'email'];
}
