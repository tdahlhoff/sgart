import '../../../shared/errors/app_error.dart';

/// Whether the caller's own account currently has a recovery email attached, and if so whether it
/// has been confirmed yet (Story 7.3, AC1). `unknown` is the seed state before the first attach —
/// the backend exposes no separate "current status" query, so [ProfileScreen] derives its best
/// guess from the live `AuthState.email` claim (non-empty ⇒ at least attached) and every in-session
/// action refines it precisely from here on.
enum AccountEmailStatus { unknown, notAttached, pendingConfirmation, confirmed }

/// State of `AccountEmailCubit`. `email` is set once an email has been (or is being) attached;
/// `error` reflects the most recent failed action and is cleared on the next attempt.
class AccountEmailState {
  const AccountEmailState({required this.status, this.email, this.isBusy = false, this.error});

  const AccountEmailState.unknown() : this(status: AccountEmailStatus.unknown);

  final AccountEmailStatus status;
  final String? email;
  final bool isBusy;
  final AppError? error;

  AccountEmailState copyWith({
    AccountEmailStatus? status,
    String? email,
    bool? isBusy,
    AppError? error,
    bool clearError = false,
  }) {
    return AccountEmailState(
      status: status ?? this.status,
      email: email ?? this.email,
      isBusy: isBusy ?? this.isBusy,
      error: clearError ? null : (error ?? this.error),
    );
  }

  @override
  bool operator ==(Object other) =>
      other is AccountEmailState &&
      other.status == status &&
      other.email == email &&
      other.isBusy == isBusy &&
      other.error == error;

  @override
  int get hashCode => Object.hash(status, email, isBusy, error);
}
