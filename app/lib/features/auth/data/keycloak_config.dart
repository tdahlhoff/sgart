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

  /// Keycloak's well-known endpoint paths, given explicitly to sign-out (see
  /// `AppAuthOidcClient.endSession`) so it never triggers AppAuth-Android's issuer-discovery
  /// fetch — that path has a plugin bug (flutter_appauth 12.1.0) where `allowInsecureConnections`
  /// is read into a local variable and never applied to the discovery HTTP client, so the
  /// dev-only plain-HTTP issuer crashes with "only https connections are permitted" whenever
  /// sign-out is the first AppAuth call in the process (a session resumed from stored tokens,
  /// with no signIn()/refresh() call — those two set the flag correctly — having run first).
  static const String authorizationEndpoint = '$issuer/protocol/openid-connect/auth';
  static const String tokenEndpoint = '$issuer/protocol/openid-connect/token';
  static const String endSessionEndpoint = '$issuer/protocol/openid-connect/logout';
}
