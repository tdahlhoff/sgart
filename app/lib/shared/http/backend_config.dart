/// The SGART backend's base URL. Dev-only default matches `backend/application.yaml`'s `:8081`
/// (Keycloak already owns `:8080` locally); override via
/// `--dart-define=SGART_BACKEND_BASE_URL=...` per environment (e.g. an Android emulator reaching
/// the host via `10.0.2.2`).
abstract final class BackendConfig {
  static const String baseUrl = String.fromEnvironment(
    'SGART_BACKEND_BASE_URL',
    defaultValue: 'http://localhost:8081',
  );
}
