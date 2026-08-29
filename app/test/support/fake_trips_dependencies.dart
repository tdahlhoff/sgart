import 'package:sgart/features/trips/data/trips_api.dart';

/// Test double for [TripsApi] — no real network in tests (CLAUDE.md §6). Mirrors `FakeItemsApi`.
class FakeTripsApi implements TripsApi {
  Object? startError;
  Object? activeTripError;
  Object? addStoreToTripError;

  String? lastListId;
  String? lastTripId;
  List<String>? lastStoreIds;
  final List<String> startCommandIds = [];
  int startCallCount = 0;

  TripView? tripViewToReturn;
  final Map<String, TripView> tripViewByListId = {};
  int activeTripCallCount = 0;

  String? lastAddedStoreTripId;
  String? lastAddedStoreId;
  final List<String> addStoreToTripCommandIds = [];
  int addStoreToTripCallCount = 0;

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

  @override
  Future<TripView> activeTrip(String householdId, String listId) async {
    activeTripCallCount++;
    if (activeTripError != null) throw activeTripError!;
    final view = tripViewByListId[listId] ?? tripViewToReturn;
    if (view == null) {
      throw StateError('FakeTripsApi.activeTrip called with no tripViewToReturn/tripViewByListId set');
    }
    return view;
  }

  @override
  Future<void> addStoreToTrip(
    String householdId,
    String listId,
    String tripId, {
    required String storeId,
    required String commandId,
  }) async {
    lastAddedStoreTripId = tripId;
    lastAddedStoreId = storeId;
    addStoreToTripCommandIds.add(commandId);
    addStoreToTripCallCount++;
    if (addStoreToTripError != null) throw addStoreToTripError!;
  }
}
