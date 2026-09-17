import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';
import '../data/account_email_api.dart';
import 'account_email_state.dart';

/// Drives attach → confirm → detach for the caller's own account's recovery email (Story 7.3,
/// AC1/AC4) — a new small cubit, kept separate from [AuthCubit] (SRP: that one owns session state;
/// this one owns the attach/confirm/detach lifecycle). Only the recover-by-email *identity swap*
/// reaches into the auth seam, and it does so directly from `RecoverByEmailPage`, not through here.
class AccountEmailCubit extends Cubit<AccountEmailState> {
  AccountEmailCubit(this._accountEmailApi, {AccountEmailState? initialState})
      : super(initialState ?? const AccountEmailState.unknown());

  final AccountEmailApi _accountEmailApi;

  /// @throws InvalidRecoveryEmailException-shaped [AppException] if `email` is malformed —
  /// surfaced via [AccountEmailState.error] (fail-fast, AC1).
  Future<void> attach(String email) async {
    emit(state.copyWith(isBusy: true, clearError: true));
    try {
      await _accountEmailApi.attach(email);
      emit(AccountEmailState(status: AccountEmailStatus.pendingConfirmation, email: email));
    } on Object catch (error) {
      emit(state.copyWith(isBusy: false, error: _toAppError(error)));
    }
  }

  Future<void> confirm(String code) async {
    emit(state.copyWith(isBusy: true, clearError: true));
    try {
      await _accountEmailApi.confirm(code);
      emit(AccountEmailState(status: AccountEmailStatus.confirmed, email: state.email));
    } on Object catch (error) {
      emit(state.copyWith(isBusy: false, error: _toAppError(error)));
    }
  }

  Future<void> detach() async {
    emit(state.copyWith(isBusy: true, clearError: true));
    try {
      await _accountEmailApi.detach();
      emit(const AccountEmailState(status: AccountEmailStatus.notAttached));
    } on Object catch (error) {
      emit(state.copyWith(isBusy: false, error: _toAppError(error)));
    }
  }

  AppError _toAppError(Object error) {
    if (error is AppException) {
      return error.error;
    }
    return AppError(code: 'account.unknown', message: error.toString());
  }
}
