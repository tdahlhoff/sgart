import '../../../shared/errors/app_error.dart';

enum AuthStatus { unauthenticated, inProgress, authenticated, failure }

/// State of [AuthCubit]. `displayName`, `keycloakUserId`, and `email` are only set while [status]
/// is [AuthStatus.authenticated]; `error` only while [status] is [AuthStatus.failure].
class AuthState {
  const AuthState.unauthenticated() : this._(AuthStatus.unauthenticated);

  const AuthState.inProgress() : this._(AuthStatus.inProgress);

  const AuthState.authenticated(String displayName, String keycloakUserId, String email)
      : this._(
          AuthStatus.authenticated,
          displayName: displayName,
          keycloakUserId: keycloakUserId,
          email: email,
        );

  const AuthState.failure(AppError error) : this._(AuthStatus.failure, error: error);

  const AuthState._(this.status, {this.displayName, this.keycloakUserId, this.email, this.error});

  final AuthStatus status;
  final String? displayName;

  /// The caller's Keycloak `sub`, carried so per-user on-device state (e.g. the locale preference,
  /// Story 1.10) can be keyed and cleared per person without a second identity call.
  final String? keycloakUserId;

  /// The caller's email, read live from the identity call for the Profil identity header (Story
  /// 1.11) — never persisted (AD-6).
  final String? email;
  final AppError? error;

  @override
  bool operator ==(Object other) =>
      other is AuthState &&
      other.status == status &&
      other.displayName == displayName &&
      other.keycloakUserId == keycloakUserId &&
      other.email == email &&
      other.error == error;

  @override
  int get hashCode => Object.hash(status, displayName, keycloakUserId, email, error);
}
