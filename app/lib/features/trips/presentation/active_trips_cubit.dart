import 'package:flutter_bloc/flutter_bloc.dart';

import '../../lists/data/shopping_lists_api.dart';
import 'active_trips_state.dart';

/// Drives the „Einkauf" tab's active-trips index (Story 3.2, AC4, Cl. 3) — the household's In-Trip
/// lists, one row per list opening its trip screen. Reuses [ShoppingListsApi.listOpenLists] (the
/// same „Offen" set the Listen overview loads) filtered to `IN_TRIP` — no new household-scoped trip
/// endpoint (DRY, Cl. 4). Depends only on [ShoppingListsApi] so tests never touch the network
/// (CLAUDE.md §6); guards every `emit` with `isClosed`.
class ActiveTripsCubit extends Cubit<ActiveTripsState> {
  ActiveTripsCubit({required this.shoppingListsApi, required this.householdId})
      : super(const ActiveTripsState.loading());

  final ShoppingListsApi shoppingListsApi;
  final String householdId;

  /// Loads the household's In-Trip lists. Called once, right after construction.
  Future<void> bootstrap() => refresh();

  /// Reloads the household's In-Trip lists — the failure retry affordance.
  Future<void> refresh() async {
    _safeEmit(const ActiveTripsState.loading());
    try {
      final openLists = await shoppingListsApi.listOpenLists(householdId);
      // Keep each In-Trip list's 1-based position in the FULL open-lists sequence (OPEN + IN_TRIP)
      // as its „Liste N" ordinal — the same sequence the Listen overview numbers over — so an
      // unnamed list shows the identical „Liste N" on both surfaces (AC4, Cl. 3).
      final inTripEntries = openLists.indexed
          .where((entry) => entry.$2.status == 'IN_TRIP')
          .map((entry) => (ordinal: entry.$1 + 1, summary: entry.$2))
          .toList();
      _safeEmit(ActiveTripsState.ready(inTripEntries));
    } on Object {
      _safeEmit(const ActiveTripsState.failure());
    }
  }

  void _safeEmit(ActiveTripsState state) {
    if (!isClosed) {
      emit(state);
    }
  }
}
