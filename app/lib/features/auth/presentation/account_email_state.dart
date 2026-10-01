import '../../../shared/errors/app_error.dart';

/// Whether the caller's own account currently has a recovery email attached, and if so whether it
/// has been confirmed yet (Story 7.3, AC1). `unknown` is the seed state before the first status
/// query (`AccountEmailCubit.loadStatus`); the backend reports only a *confirmed* binding, so a
/// pending attach is known only from this session's own attach action.
enum AccountEmailStatus { unknown, notAttached, pendingConfirmation, confirmed }

/// State of `AccountEmailCubit`. `addressHint` is the masked form of the confirmed recovery email
/// (never the address itself, which the app does not keep after the attach call); `error` reflects the most recent failed action and is cleared on the next attempt.
class AccountEmailState {
  const AccountEmailState({required this.status, this.addressHint, this.isBusy = false, this.error});

  const AccountEmailState.unknown() : this(status: AccountEmailStatus.unknown);

  final AccountEmailStatus status;
  final String? addressHint;
  final bool isBusy;
  final AppError? error;

  AccountEmailState copyWith({
    AccountEmailStatus? status,
    String? addressHint,
    bool? isBusy,
    AppError? error,
    bool clearError = false,
  }) {
    return AccountEmailState(
      status: status ?? this.status,
      addressHint: addressHint ?? this.addressHint,
      isBusy: isBusy ?? this.isBusy,
      error: clearError ? null : (error ?? this.error),
    );
  }

  @override
  bool operator ==(Object other) =>
      other is AccountEmailState &&
      other.status == status &&
      other.addressHint == addressHint &&
      other.isBusy == isBusy &&
      other.error == error;

  @override
  int get hashCode => Object.hash(status, addressHint, isBusy, error);
}
