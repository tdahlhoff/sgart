import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';
import '../data/household_summary.dart';
import '../data/households_api.dart';
import 'households_state.dart';

/// Drives first-run routing (AC2): fetches the caller's households and decides 0 → [needsChoice] ·
/// 1 → [home] · ≥2 → [selection]. Depends only on the [HouseholdsApi] interface so tests never
/// touch the network (CLAUDE.md §6). Mirrors [AuthCubit]'s async pattern: guard every `emit` with
/// `isClosed`.
class HouseholdsCubit extends Cubit<HouseholdsState> {
  HouseholdsCubit({required this._householdsApi}) : super(const HouseholdsState.loading());

  final HouseholdsApi _householdsApi;

  /// Fetches the caller's households and routes accordingly. Called once, right after
  /// construction — the cubit already starts in [HouseholdsStatus.loading], so this does not
  /// re-emit it (mirrors `AuthCubit.bootstrap()`, which likewise emits only the resolved state).
  Future<void> bootstrap() async {
    try {
      final households = await _householdsApi.listMyHouseholds();
      _emitForHouseholds(households);
    } on Object catch (error) {
      _safeEmit(HouseholdsState.failure(_toAppError(error)));
    }
  }

  /// Routes straight into a household — either one the caller just picked from the selection
  /// screen, or one they just created (read-your-writes, AC3: no need to re-fetch the list).
  void selectHousehold(HouseholdSummary household) {
    _safeEmit(HouseholdsState.home(household));
  }

  void _emitForHouseholds(List<HouseholdSummary> households) {
    if (households.isEmpty) {
      _safeEmit(const HouseholdsState.needsChoice());
    } else if (households.length == 1) {
      _safeEmit(HouseholdsState.home(households.first));
    } else {
      _safeEmit(HouseholdsState.selection(households));
    }
  }

  AppError _toAppError(Object error) {
    if (error is AppException) {
      return error.error;
    }
    return AppError(code: 'households.unknown', message: error.toString());
  }

  void _safeEmit(HouseholdsState state) {
    if (!isClosed) {
      emit(state);
    }
  }
}
