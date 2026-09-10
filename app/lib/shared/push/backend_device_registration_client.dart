import '../http/authenticated_http_client.dart';

/// Talks to the backend's device-token endpoints (Story 4.5, AC5) — the thin HTTP shape a
/// concrete [PushNotifications] implementation composes with the platform's actual token/platform
/// values (mirrors [AuthenticatedHttpClient]'s other thin per-resource wrappers, e.g.
/// `IdentityApi`). `keycloakUserId` is never sent — the backend takes it from the JWT `sub`
/// (AR10).
class BackendDeviceRegistrationClient {
  BackendDeviceRegistrationClient({required this._httpClient});

  final AuthenticatedHttpClient _httpClient;

  /// Registers or refreshes this device's token — an upsert keyed by the token itself.
  Future<void> register({required String token, required String platform}) async {
    await _httpClient.postJson('/api/v1/devices', {'token': token, 'platform': platform});
  }

  /// Unregisters this device's token — the sign-out path. The token goes in the request body, not
  /// the URL path: it is an opaque, person-linked identifier (AD-6) that must not leak into
  /// server/proxy access logs, and a raw token in a path segment would also break on tokens
  /// containing `/`.
  Future<void> unregister(String token) => _httpClient.deleteJson('/api/v1/devices', {'token': token});
}
