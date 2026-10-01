import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';

/// The live caller identity returned by `GET /api/v1/identity/me` — read from JWT claims for
/// display only, never persisted (AD-6). Carries no email: the recovery email lives in the
/// backend's digest index and is shown only as a masked hint (`AccountEmailApi.fetchStatus`).
class CallerIdentity {
  const CallerIdentity({required this.keycloakUserId, required this.displayName});

  final String keycloakUserId;
  final String displayName;

  /// Fails fast with a mapped [AppException] rather than a raw `TypeError` when the response is
  /// missing a field or has an unexpected shape, so callers resolve it through [AppError.code].
  factory CallerIdentity.fromJson(Map<String, dynamic> json) {
    final keycloakUserId = json['keycloakUserId'];
    final displayName = json['displayName'];
    if (keycloakUserId is! String || displayName is! String) {
      throw const AppException(
        AppError(code: 'identity.malformedResponse', message: 'GET /api/v1/identity/me returned an unexpected shape'),
      );
    }
    return CallerIdentity(keycloakUserId: keycloakUserId, displayName: displayName);
  }
}
