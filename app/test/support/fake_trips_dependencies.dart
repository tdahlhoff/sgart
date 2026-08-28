import 'package:sgart/features/trips/data/trips_api.dart';

/// Test double for [TripsApi] — no real network in tests (CLAUDE.md §6). Mirrors `FakeItemsApi`.
class FakeTripsApi implements TripsApi {
  Object? startError;

  String? lastListId;
  String? lastTripId;
  List<String>? lastStoreIds;
  final List<String> startCommandIds = [];
  int startCallCount = 0;

  @override
  Future<void> startTrip(
    String householdId,
    String listId, {
    required String tripId,
    required List<String> storeIds,
    required String commandId,
  }) async {
    lastListId = listId;
    lastTripId = tripId;
    lastStoreIds = storeIds;
    startCommandIds.add(commandId);
    startCallCount++;
    if (startError != null) throw startError!;
  }
}
