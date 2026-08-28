import '../../../shared/http/authenticated_http_client.dart';

/// The client's trip source — calls the backend's start-trip endpoint under a list
/// (`/api/v1/households/{householdId}/lists/{listId}/trips`) (Story 3.1). First `trips` feature —
/// data-only in 3.1 (the store-grouped trip screen is Story 3.2).
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
}
