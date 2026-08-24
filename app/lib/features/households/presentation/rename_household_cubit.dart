import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:uuid/uuid.dart';

import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';
import '../data/households_api.dart';
import 'rename_household_state.dart';

/// Drives the rename-household form's submit (AC3). Depends only on [HouseholdsApi] so tests never
/// touch the network (CLAUDE.md §6); guards every `emit` with `isClosed`.
class RenameHouseholdCubit extends Cubit<RenameHouseholdState> {
  RenameHouseholdCubit({required this._householdsApi, required this._householdId, String? commandId})
      : _commandId = commandId ?? const Uuid().v4(),
        super(const RenameHouseholdState.idle());

  final HouseholdsApi _householdsApi;
  final String _householdId;

  /// The command id for the current rename intent — a *specific target name*. Reused across retries
  /// of the same name so a resubmit after a transient failure is idempotent (AD-8); regenerated when
  /// the name changes so an edited retry is a new intent that cannot dedupe against an earlier append
  /// that may have landed server-side despite a lost response (which would otherwise leave the client
  /// showing the edited name while the server kept the first one).
  String _commandId;
  String? _commandIdName;

  Future<void> submit(String name) async {
    final trimmedName = name.trim();
    final commandId = _commandIdFor(trimmedName);
    _safeEmit(const RenameHouseholdState.submitting());
    try {
      await _householdsApi.renameHousehold(_householdId, trimmedName, commandId: commandId);
      // Propagate the trimmed name so the header/switcher/home match what the server persisted (AC3).
      _safeEmit(RenameHouseholdState.success(trimmedName));
    } on Object catch (error) {
      _safeEmit(RenameHouseholdState.failure(_toAppError(error)));
    }
  }

  /// Returns the command id to use for [trimmedName]: the current one for the first attempt or a
  /// retry of the same name (idempotent retry), or a fresh one when the name has changed.
  String _commandIdFor(String trimmedName) {
    if (_commandIdName == null || _commandIdName == trimmedName) {
      _commandIdName = trimmedName;
      return _commandId;
    }
    _commandId = const Uuid().v4();
    _commandIdName = trimmedName;
    return _commandId;
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
