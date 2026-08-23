import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';

/// The live caller identity returned by `GET /api/v1/identity/me` — read from JWT claims for
/// display only, never persisted (AD-6).
class CallerIdentity {
  const CallerIdentity({required this.keycloakUserId, required this.displayName, required this.email});

  final String keycloakUserId;
  final String displayName;
  final String email;

  /// Fails fast with a mapped [AppException] rather than a raw `TypeError` when the response is
  /// missing a field or has an unexpected shape, so callers resolve it through [AppError.code].
  factory CallerIdentity.fromJson(Map<String, dynamic> json) {
    final keycloakUserId = json['keycloakUserId'];
    final displayName = json['displayName'];
    final email = json['email'];
    if (keycloakUserId is! String || displayName is! String || email is! String) {
      throw const AppException(AppError(
        code: 'identity.malformedResponse',
        message: 'GET /api/v1/identity/me returned an unexpected shape',
      ));
    }
    return CallerIdentity(keycloakUserId: keycloakUserId, displayName: displayName, email: email);
  }
}
