import 'package:collection/collection.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';
import '../data/active_household_store.dart';
import '../data/household_summary.dart';
import '../data/households_api.dart';
import 'households_state.dart';

/// Drives first-run routing and the persistent shell (AC1, AC2). Fetches the caller's households
/// and, honouring the last-active household persisted on-device (Clarification B), decides:
/// 0 → [HouseholdsStatus.needsChoice] · a stored-and-still-present active → straight into the
/// [HouseholdsStatus.shell] · else 1 → shell · ≥2 → [HouseholdsStatus.selection]. Once in the
/// shell it carries the retained list + the active household and can [switchActive] between them.
/// Depends only on the [HouseholdsApi] and [ActiveHouseholdStore] interfaces so tests never touch
/// the network or device storage (CLAUDE.md §6); guards every `emit` with `isClosed`.
class HouseholdsCubit extends Cubit<HouseholdsState> {
  HouseholdsCubit({required this._householdsApi, required this._activeHouseholdStore})
      : super(const HouseholdsState.loading());

  final HouseholdsApi _householdsApi;
  final ActiveHouseholdStore _activeHouseholdStore;

  /// Fetches the caller's households and routes accordingly. Called once, right after construction.
  Future<void> bootstrap() async {
    try {
      final households = await _householdsApi.listMyHouseholds();
      await _routeForHouseholds(households);
    } on Object catch (error) {
      _safeEmit(HouseholdsState.failure(_toAppError(error)));
    }
  }

  Future<void> _routeForHouseholds(List<HouseholdSummary> households) async {
    if (households.isEmpty) {
      _safeEmit(const HouseholdsState.needsChoice());
      return;
    }
    // Restore the last-active household when it is still one the caller belongs to — otherwise it
    // silently falls back to routing (a household left/removed since is simply not found).
    final storedActiveId = await _readStoredActiveId();
    final restored = storedActiveId == null
        ? null
        : households.firstWhereOrNull((household) => household.householdId == storedActiveId);
    if (restored != null) {
      _safeEmit(HouseholdsState.shell(activeHousehold: restored, households: households));
      return;
    }
    if (households.length == 1) {
      await _enterShell(households.first, households);
    } else {
      _safeEmit(HouseholdsState.selection(households));
    }
  }

  /// Enters the shell with [household] active — either one picked from the selection screen or one
  /// just created (read-your-writes, AC3). Adds it to the retained list if it is not already there
  /// (a freshly created household), and persists it as last-active.
  Future<void> selectHousehold(HouseholdSummary household) {
    final households = state.households ?? const <HouseholdSummary>[];
    final retained = households.any((existing) => existing.householdId == household.householdId)
        ? households
        : [...households, household];
    return _enterShell(household, retained);
  }

  /// Switches the active household to another one in the retained list (AC2), persisting the change.
  Future<void> switchActive(HouseholdSummary household) {
    return _enterShell(household, state.households ?? [household]);
  }

  /// Reflects a rename of [householdId] everywhere it is shown (AC3): its entry in the switcher list
  /// and, when it is the active household, its name in the header/home. Keyed on the renamed id (not
  /// on "whatever is active") so it always updates the household that was actually renamed. No
  /// persistence change — the id is unchanged.
  void applyHouseholdRename(String householdId, String newName) {
    final active = state.activeHousehold;
    final households = state.households;
    if (active == null || households == null) {
      return;
    }
    HouseholdSummary renamedIfMatch(HouseholdSummary household) =>
        household.householdId == householdId
            ? HouseholdSummary(householdId: householdId, name: newName)
            : household;
    _safeEmit(HouseholdsState.shell(
      activeHousehold: renamedIfMatch(active),
      households: households.map(renamedIfMatch).toList(),
    ));
  }

  Future<void> _enterShell(HouseholdSummary active, List<HouseholdSummary> households) async {
    _safeEmit(HouseholdsState.shell(activeHousehold: active, households: households));
    // Persisting last-active is best-effort — a storage failure must not tear down the shell the
    // user is already in (nor become an unhandled async error on the fire-and-forget switch path);
    // the next launch simply falls back to routing.
    try {
      await _activeHouseholdStore.writeActive(active.householdId);
    } on Object {
      // Ignored: see above.
    }
  }

  /// Reads the stored last-active id, treating a device-storage read failure as "none" so a
  /// successful household load still routes normally rather than collapsing to the failure screen.
  Future<String?> _readStoredActiveId() async {
    try {
      return await _activeHouseholdStore.readActive();
    } on Object {
      return null;
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
