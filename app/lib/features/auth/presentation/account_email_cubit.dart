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

  /// Identifies the most recently started request. A status read that resolves after a newer
  /// request began is stale and must not overwrite the state that request produced.
  int _latestRequestNumber = 0;

  int _startRequest() => ++_latestRequestNumber;

  bool _isStale(int requestNumber) => isClosed || requestNumber != _latestRequestNumber;

  /// A request may resolve after the owning screen is gone; its result then has nowhere to go.
  @override
  void emit(AccountEmailState state) {
    if (!isClosed) super.emit(state);
  }

  /// Reads the confirmed recovery email's masked hint from the backend, which is the only source
  /// of truth after a relaunch. A failure leaves the state as it was and surfaces the error.
  Future<void> loadStatus() async {
    final requestNumber = _startRequest();
    try {
      final addressHint = await _accountEmailApi.fetchStatus();
      if (_isStale(requestNumber)) return;
      emit(
        addressHint == null
            ? const AccountEmailState(status: AccountEmailStatus.notAttached)
            : AccountEmailState(status: AccountEmailStatus.confirmed, addressHint: addressHint),
      );
    } on Object catch (error) {
      if (_isStale(requestNumber)) return;
      emit(state.copyWith(error: _toAppError(error)));
    }
  }

  /// @throws InvalidRecoveryEmailException-shaped [AppException] if `email` is malformed —
  /// surfaced via [AccountEmailState.error] (fail-fast, AC1).
  Future<void> attach(String email) async {
    _startRequest();
    emit(state.copyWith(isBusy: true, clearError: true));
    try {
      await _accountEmailApi.attach(email);
      emit(const AccountEmailState(status: AccountEmailStatus.pendingConfirmation));
    } on Object catch (error) {
      emit(state.copyWith(isBusy: false, error: _toAppError(error)));
    }
  }

  Future<void> confirm(String code) async {
    final requestNumber = _startRequest();
    emit(state.copyWith(isBusy: true, clearError: true));
    try {
      await _accountEmailApi.confirm(code);
    } on Object catch (error) {
      emit(state.copyWith(isBusy: false, error: _toAppError(error)));
      return;
    }
    emit(const AccountEmailState(status: AccountEmailStatus.confirmed));
    await _readBackConfirmedHint(requestNumber);
  }

  /// The hint is computed server-side at attach time, so it is read back rather than rebuilt
  /// here. The email is confirmed regardless: a failed or empty read-back never revokes that.
  Future<void> _readBackConfirmedHint(int requestNumber) async {
    try {
      final addressHint = await _accountEmailApi.fetchStatus();
      if (_isStale(requestNumber) || addressHint == null) return;
      emit(AccountEmailState(status: AccountEmailStatus.confirmed, addressHint: addressHint));
    } on Object catch (error) {
      if (_isStale(requestNumber)) return;
      emit(state.copyWith(error: _toAppError(error)));
    }
  }

  Future<void> detach() async {
    _startRequest();
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
