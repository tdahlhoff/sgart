import 'package:collection/collection.dart';

import '../../lists/data/shopping_list_summary.dart';

enum ActiveTripsStatus { loading, ready, failure }

/// One In-Trip list row (Story 3.2, AC4, Cl. 3): the list plus its `ordinal` — its 1-based position
/// in the household's full open-lists sequence (OPEN + IN_TRIP), the same sequence the Listen
/// overview numbers „Liste N" over. Carried so an unnamed In-Trip list shows the identical „Liste N"
/// here as in the overview, not a bare fallback that drops the number.
typedef ActiveTripEntry = ({int ordinal, ShoppingListSummary summary});

/// State of [ActiveTripsCubit] (Story 3.2, AC4, Cl. 3) — the household's In-Trip lists (each with its
/// „Liste N" ordinal), one row opening its trip screen.
class ActiveTripsState {
  const ActiveTripsState._(this.status, {this.entries = const []});

  const ActiveTripsState.loading() : this._(ActiveTripsStatus.loading);

  const ActiveTripsState.failure() : this._(ActiveTripsStatus.failure);

  const ActiveTripsState.ready(List<ActiveTripEntry> entries) : this._(ActiveTripsStatus.ready, entries: entries);

  final ActiveTripsStatus status;
  final List<ActiveTripEntry> entries;

  @override
  bool operator ==(Object other) =>
      other is ActiveTripsState &&
      other.status == status &&
      const ListEquality<ActiveTripEntry>().equals(other.entries, entries);

  @override
  int get hashCode => Object.hash(status, const ListEquality<ActiveTripEntry>().hash(entries));
}
