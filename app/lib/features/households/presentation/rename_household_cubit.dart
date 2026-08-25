import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../shared/commands/command_intent.dart';
import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';
import '../data/households_api.dart';
import 'rename_household_state.dart';

/// Drives the rename-household form's submit (AC3). Depends only on [HouseholdsApi] so tests never
/// touch the network (CLAUDE.md §6); guards every `emit` with `isClosed`.
class RenameHouseholdCubit extends Cubit<RenameHouseholdState> {
  RenameHouseholdCubit({required this._householdsApi, required this._householdId})
      : super(const RenameHouseholdState.idle());

  final HouseholdsApi _householdsApi;
  final String _householdId;

  /// The command-id lifecycle for the rename intent, keyed on the target name: reused across retries
  /// of the same name (idempotent retry, AD-8) and freshened when the name changes so an edited retry
  /// cannot dedupe against an earlier append that landed despite a lost response.
  final CommandIntent _intent = CommandIntent();

  Future<void> submit(String name) async {
    final trimmedName = name.trim();
    _intent.beginAttempt(trimmedName);
    _safeEmit(const RenameHouseholdState.submitting());
    try {
      await _householdsApi.renameHousehold(_householdId, trimmedName, commandId: _intent.commandId);
      // Propagate the trimmed name so the header/switcher/home match what the server persisted (AC3).
      _safeEmit(RenameHouseholdState.success(trimmedName));
    } on Object catch (error) {
      _safeEmit(RenameHouseholdState.failure(_toAppError(error)));
    }
  }

  AppError _toAppError(Object error) {
    if (error is AppException) {
      return error.error;
    }
    return AppError(code: 'households.unknown', message: error.toString());
  }

  void _safeEmit(RenameHouseholdState state) {
    if (!isClosed) {
      emit(state);
    }
  }
}
