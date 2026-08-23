import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:uuid/uuid.dart';

import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';
import '../data/household_summary.dart';
import '../data/households_api.dart';
import 'create_household_state.dart';

/// Drives the create-household form's submit (AC1). Depends only on [HouseholdsApi] so tests
/// never touch the network (CLAUDE.md §6); guards every `emit` with `isClosed` (the established
/// async-cubit pattern from `AuthCubit`).
class CreateHouseholdCubit extends Cubit<CreateHouseholdState> {
  CreateHouseholdCubit({required this._householdsApi, String? commandId})
      // One command id per create intent (this form instance): a resubmit after a transient
      // failure reuses it so the backend converges on a single household rather than creating
      // duplicates (Clarification 5). A fresh create screen mints a fresh intent.
      : _commandId = commandId ?? const Uuid().v4(),
        super(const CreateHouseholdState.idle());

  final HouseholdsApi _householdsApi;
  final String _commandId;

  Future<void> submit(String name) async {
    final trimmedName = name.trim();
    _safeEmit(const CreateHouseholdState.submitting());
    try {
      final householdId = await _householdsApi.createHousehold(trimmedName, commandId: _commandId);
      // Route in on the trimmed name so the home screen matches what the server persisted.
      _safeEmit(CreateHouseholdState.success(HouseholdSummary(householdId: householdId, name: trimmedName)));
    } on Object catch (error) {
      _safeEmit(CreateHouseholdState.failure(_toAppError(error)));
    }
  }

  AppError _toAppError(Object error) {
    if (error is AppException) {
      return error.error;
    }
    return AppError(code: 'households.unknown', message: error.toString());
  }

  void _safeEmit(CreateHouseholdState state) {
    if (!isClosed) {
      emit(state);
    }
  }
}
