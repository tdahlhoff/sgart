import '../../../shared/http/authenticated_http_client.dart';

/// The caller's consent status — the exact shape `GET /api/v1/consent` returns (Story 7.4, AC2):
/// whether a notice was ever accepted, which version (`null` if none), and the deployment's
/// current notice version (D-E) — the single source the client compares against, never a
/// hard-coded app-side constant.
class ConsentStatus {
  const ConsentStatus({required this.accepted, required this.acceptedVersion, required this.currentVersion});

  factory ConsentStatus.fromJson(Map<String, dynamic> json) => ConsentStatus(
        accepted: json['accepted'] as bool,
        acceptedVersion: json['acceptedVersion'] as String?,
        currentVersion: json['currentVersion'] as String,
      );

  final bool accepted;
  final String? acceptedVersion;
  final String currentVersion;

  /// Whether the caller must (re-)consent before proceeding (AC1/AC4): no row yet, or the
  /// accepted version is stale against the current one (F1: plain equality, no ordered parsing).
  bool get needsConsent => !accepted || acceptedVersion != currentVersion;
}

/// Calls the backend's Story 7.4 consent-capture endpoints. Abstracted so [ConsentCubit] never
/// touches a real HTTP client in tests (CLAUDE.md §6), mirroring [AccountEmailApi]'s shape.
abstract interface class ConsentApi {
  /// `GET /api/v1/consent` — side-effect-free (AC2).
  Future<ConsentStatus> getStatus();

  /// `POST /api/v1/consent` — records the caller's acceptance of the deployment's current notice
  /// version (AC1). Carries no body: the server stamps its own current version rather than
  /// trusting a client-supplied one (Story 7.4 review).
  Future<void> accept();
}

/// The real, backend-[AuthenticatedHttpClient]-backed [ConsentApi].
class HttpConsentApi implements ConsentApi {
  const HttpConsentApi(this._httpClient);

  final AuthenticatedHttpClient _httpClient;

  @override
  Future<ConsentStatus> getStatus() async {
    final json = await _httpClient.getJson('/api/v1/consent');
    return ConsentStatus.fromJson(json);
  }

  @override
  Future<void> accept() => _httpClient.postJson('/api/v1/consent', const {});
}
