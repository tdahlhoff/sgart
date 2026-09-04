import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';
import '../../../shared/http/authenticated_http_client.dart';
import '../../lists/data/item.dart';

/// The store-grouped active-trip payload (Story 3.2, AC1) — the trip's id, its list's id, its
/// stores in add order, and the list's items. Grouping items under a store (and the „Noch nicht
/// zugeordnet" fallback, Cl. 7) is the client's job — this model carries the flat server shape.
class TripView {
  const TripView({required this.tripId, required this.listId, required this.storeIds, required this.items});

  final String tripId;
  final String listId;
  final List<String> storeIds;
  final List<Item> items;

  /// Fails fast with a mapped [AppException] rather than a raw `TypeError` when the response is
  /// missing a field or has an unexpected shape, so callers resolve it through [AppError.code].
  factory TripView.fromJson(Map<String, dynamic> json) {
    final tripId = json['tripId'];
    final listId = json['listId'];
    final storeIds = json['storeIds'];
    final items = json['items'];
    if (tripId is! String ||
        listId is! String ||
        storeIds is! List ||
        storeIds.any((entry) => entry is! String) ||
        items is! List ||
        items.any((entry) => entry is! Map)) {
      throw const AppException(AppError(
        code: 'trips.malformedResponse',
        message: 'GET trips/active returned an unexpected shape',
      ));
    }
    return TripView(
      tripId: tripId,
      listId: listId,
      storeIds: storeIds.cast<String>(),
      items: items.map((entry) => Item.fromJson(entry as Map<String, dynamic>)).toList(),
    );
  }
}

/// The client's trip source — calls the backend's trip slice under a list
/// (`/api/v1/households/{householdId}/lists/{listId}/trips`) (Story 3.1; the store-grouped view +
/// spontaneous add-store, Story 3.2).
abstract interface class TripsApi {
  /// Starts a trip against [listId] across [storeIds] (AC1, AC3 — at least one store). [tripId] and
  /// [commandId] are the caller-minted idempotency keys reused across retries of the *same* intent
  /// (AD-8), exactly like `addItem`. The caller mints [tripId] (not this method) so the response
  /// needs no body — the client already knows the id it minted (read-your-writes).
  Future<void> startTrip(
    String householdId,
    String listId, {
    required String tripId,
    required List<String> storeIds,
    required String commandId,
  });

  /// The store-grouped active-trip view for [listId] (Story 3.2, AC1). Throws `trip.notFound` when
  /// the list has no active trip.
  Future<TripView> activeTrip(String householdId, String listId);

  /// Adds [storeId] to [tripId] spontaneously (Story 3.2, AC3) — the trip's first in-trip mutation.
  /// [commandId] is the reused idempotency key for the add intent; a store already in the trip is a
  /// convergent no-op server-side.
  Future<void> addStoreToTrip(
    String householdId,
    String listId,
    String tripId, {
    required String storeId,
    required String commandId,
  });

  /// Completes the trip against [listId]/[tripId] (Story 3.4, AC4). Sweeps any remaining OPEN items
  /// to DISCARDED server-side, then transitions the list `IN_TRIP → DONE`. [commandId] is the
  /// reused idempotency key. Throws `trip.notCompletable` when the list is not In-Trip.
  Future<void> completeTrip(
    String householdId,
    String listId,
    String tripId, {
    required String commandId,
  });
}

class HttpTripsApi implements TripsApi {
  const HttpTripsApi(this._client);

  final AuthenticatedHttpClient _client;

  @override
  Future<void> startTrip(
    String householdId,
    String listId, {
    required String tripId,
    required List<String> storeIds,
    required String commandId,
  }) async {
    await _client.postJson('/api/v1/households/$householdId/lists/$listId/trips', {
      'tripId': tripId,
      'storeIds': storeIds,
      'commandId': commandId,
    });
  }

  @override
  Future<TripView> activeTrip(String householdId, String listId) async {
    final json = await _client.getJson('/api/v1/households/$householdId/lists/$listId/trips/active');
    return TripView.fromJson(json);
  }

  @override
  Future<void> addStoreToTrip(
    String householdId,
    String listId,
    String tripId, {
    required String storeId,
    required String commandId,
  }) async {
    await _client.postJson('/api/v1/households/$householdId/lists/$listId/trips/$tripId/stores', {
      'storeId': storeId,
      'commandId': commandId,
    });
  }

  @override
  Future<void> completeTrip(
    String householdId,
    String listId,
    String tripId, {
    required String commandId,
  }) async {
    await _client.postJson('/api/v1/households/$householdId/lists/$listId/trips/$tripId/complete', {
      'commandId': commandId,
    });
  }
}
