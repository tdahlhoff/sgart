import '../../../shared/errors/app_error.dart';

enum AuthStatus { unauthenticated, inProgress, authenticated, failure }

/// State of [AuthCubit]. `displayName` is only set while [status] is [AuthStatus.authenticated];
/// `error` only while [status] is [AuthStatus.failure].
class AuthState {
  const AuthState.unauthenticated() : this._(AuthStatus.unauthenticated);

  const AuthState.inProgress() : this._(AuthStatus.inProgress);

  const AuthState.authenticated(String displayName)
      : this._(AuthStatus.authenticated, displayName: displayName);

  const AuthState.failure(AppError error) : this._(AuthStatus.failure, error: error);

  const AuthState._(this.status, {this.displayName, this.error});

  final AuthStatus status;
  final String? displayName;
  final AppError? error;

  @override
  bool operator ==(Object other) =>
      other is AuthState &&
      other.status == status &&
      other.displayName == displayName &&
      other.error == error;

  @override
  int get hashCode => Object.hash(status, displayName, error);
}
