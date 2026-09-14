import '../../../shared/http/authenticated_http_client.dart';

/// Calls the backend's silent account-provisioning endpoint (Story 7.1, AC1) — the app's only
/// unauthenticated write call. Abstracted so the sign-in client's tests never touch a real HTTP
/// client (CLAUDE.md §6), mirroring [BackendDeviceRegistrationClient]'s shape.
abstract interface class AccountProvisioningApi {
  /// @param publicKey the device's base64url-encoded Ed25519 public key (also the derived
  ///     username, D-E).
  /// @param platform the raw `DevicePlatform` name (`"ANDROID"`/`"IOS"`) — reserved for future
  ///     device-attestation/telemetry; the backend does not persist it (storage-free pass-through).
  Future<void> provision(String publicKey, String platform);
}

/// The real, backend-`AuthenticatedHttpClient`-backed [AccountProvisioningApi]. Reuses the shared
/// HTTP client rather than a second one: the endpoint being unauthenticated by nature just means
/// the bearer interceptor finds no token yet to attach (harmless), and the same `{code,message}`
/// error mapping every other call already gets applies here too.
class HttpAccountProvisioningApi implements AccountProvisioningApi {
  const HttpAccountProvisioningApi(this._httpClient);

  final AuthenticatedHttpClient _httpClient;

  @override
  Future<void> provision(String publicKey, String platform) {
    return _httpClient.postJson('/api/v1/accounts', {'publicKey': publicKey, 'platform': platform});
  }
}
