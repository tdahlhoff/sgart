import '../../../shared/http/authenticated_http_client.dart';
import 'item.dart';

/// The client's item source — calls the backend's item slice under a list
/// (`/api/v1/households/{householdId}/lists/{listId}/items`) (Story 2.3).
abstract interface class ItemsApi {
  /// Lists the list's items in creation order (AC6).
  Future<List<Item>> listItems(String householdId, String listId);

  /// Adds an item by [name], required [amount] + [unit], and an optional [note] (AC1). [commandId]
  /// and [itemId] are the caller-minted idempotency keys reused across retries of the *same* intent
  /// (AD-8), exactly like `addStore`/`createList`. The caller mints [itemId] (not this method) so a
  /// retry reuses the same id, and so the caller can optimistically render it without waiting on the
  /// read model (read-your-writes).
  Future<void> addItem(
    String householdId,
    String listId, {
    required String itemId,
    required String name,
    String? note,
    required String amount,
    required String unit,
    required String commandId,
  });

  /// Updates [itemId]'s name, optional note, and quantity (AC3). [commandId] is the reused
  /// idempotency key for the update intent.
  Future<void> updateItem(
    String householdId,
    String listId,
    String itemId, {
    required String name,
    String? note,
    required String amount,
    required String unit,
    required String commandId,
  });

  /// Removes [itemId] (AC4, idempotent — a retry/unknown id is a silent success). [commandId] is
  /// the reused idempotency key for the remove intent.
  Future<void> removeItem(String householdId, String listId, String itemId, {required String commandId});

  /// Moves [itemId] from [listId] (the source) to [targetListId] (Story 2.4, AC1). [commandId] is
  /// the reused idempotency key for the move intent — a client retry is deduped server-side; the
  /// target-side add is the backend's process manager's job, not this call's.
  Future<void> moveItem(
    String householdId,
    String listId,
    String itemId, {
    required String targetListId,
    required String commandId,
  });

  /// Assigns [itemId] to [storeId] (Story 2.6, AC1). [commandId] is the reused idempotency key for
  /// the assign intent — re-assigning the same store again is a convergent no-op server-side.
  Future<void> assignStore(
    String householdId,
    String listId,
    String itemId, {
    required String storeId,
    required String commandId,
  });

  /// Re-routes [itemId] to [storeId] during a trip (Story 3.2, AC2). [commandId] is the reused
  /// idempotency key for the reroute intent — rerouting to the item's current store is a
  /// convergent no-op server-side. Throws `item.notReroutable` when the list is no longer In-Trip.
  Future<void> rerouteItem(
    String householdId,
    String listId,
    String itemId, {
    required String storeId,
    required String commandId,
  });

  /// Checks off [itemId] during a trip (Story 3.3, AC2) — marks it `DONE`. Already-DONE is a
  /// convergent no-op server-side. Throws `item.notDuringTrip` when the list is no longer In-Trip.
  Future<void> checkOffItem(
    String householdId,
    String listId,
    String itemId, {
    required String commandId,
  });

  /// Unchecks [itemId] during a trip (Story 3.3, AC2) — returns it to `OPEN`. Already-OPEN is a
  /// convergent no-op server-side.
  Future<void> uncheckItem(
    String householdId,
    String listId,
    String itemId, {
    required String commandId,
  });

  /// Postpones [itemId] in place during a trip (Story 3.3, AC3) — marks it `POSTPONED`. Already-
  /// POSTPONED is a convergent no-op server-side.
  Future<void> postponeItem(
    String householdId,
    String listId,
    String itemId, {
    required String commandId,
  });

  /// Postpones [itemId] to [targetListId] during a trip (Story 3.3, AC4) — removes the item from
  /// the source list; the backend's process manager adds it to the target. [commandId] is the reused
  /// idempotency key.
  Future<void> postponeItemToList(
    String householdId,
    String listId,
    String itemId, {
    required String targetListId,
    required String commandId,
  });
}

class HttpItemsApi implements ItemsApi {
  const HttpItemsApi(this._client);

  final AuthenticatedHttpClient _client;

  @override
  Future<List<Item>> listItems(String householdId, String listId) async {
    final json = await _client.getJsonList('/api/v1/households/$householdId/lists/$listId/items');
    return json.map((entry) => Item.fromJson(entry as Map<String, dynamic>)).toList();
  }

  @override
  Future<void> addItem(
    String householdId,
    String listId, {
    required String itemId,
    required String name,
    String? note,
    required String amount,
    required String unit,
    required String commandId,
  }) async {
    // The caller-minted item id is sent in the envelope, so the response needs no body
    // (read-your-writes without a projection wait) — the same rationale as the store id in addStore.
    await _client.postJson('/api/v1/households/$householdId/lists/$listId/items', {
      'itemId': itemId,
      'name': name,
      'note': note,
      'amount': amount,
      'unit': unit,
      'commandId': commandId,
    });
  }

  @override
  Future<void> updateItem(
    String householdId,
    String listId,
    String itemId, {
    required String name,
    String? note,
    required String amount,
    required String unit,
    required String commandId,
  }) {
    return _client.patchJson('/api/v1/households/$householdId/lists/$listId/items/$itemId', {
      'name': name,
      'note': note,
      'amount': amount,
      'unit': unit,
      'commandId': commandId,
    });
  }

  @override
  Future<void> removeItem(String householdId, String listId, String itemId, {required String commandId}) {
    return _client.deleteJson(
      '/api/v1/households/$householdId/lists/$listId/items/$itemId',
      {'commandId': commandId},
    );
  }

  @override
  Future<void> moveItem(
    String householdId,
    String listId,
    String itemId, {
    required String targetListId,
    required String commandId,
  }) {
    return _client.postJson('/api/v1/households/$householdId/lists/$listId/items/$itemId/move', {
      'targetListId': targetListId,
      'commandId': commandId,
    });
  }

  @override
  Future<void> assignStore(
    String householdId,
    String listId,
    String itemId, {
    required String storeId,
    required String commandId,
  }) {
    return _client.putJson('/api/v1/households/$householdId/lists/$listId/items/$itemId/store', {
      'storeId': storeId,
      'commandId': commandId,
    });
  }

  @override
  Future<void> rerouteItem(
    String householdId,
    String listId,
    String itemId, {
    required String storeId,
    required String commandId,
  }) async {
    await _client.postJson('/api/v1/households/$householdId/lists/$listId/items/$itemId/reroute', {
      'storeId': storeId,
      'commandId': commandId,
    });
  }

  @override
  Future<void> checkOffItem(
    String householdId,
    String listId,
    String itemId, {
    required String commandId,
  }) async {
    await _client.postJson(
        '/api/v1/households/$householdId/lists/$listId/items/$itemId/check-off', {'commandId': commandId});
  }

  @override
  Future<void> uncheckItem(
    String householdId,
    String listId,
    String itemId, {
    required String commandId,
  }) async {
    await _client.postJson(
        '/api/v1/households/$householdId/lists/$listId/items/$itemId/uncheck', {'commandId': commandId});
  }

  @override
  Future<void> postponeItem(
    String householdId,
    String listId,
    String itemId, {
    required String commandId,
  }) async {
    await _client.postJson(
        '/api/v1/households/$householdId/lists/$listId/items/$itemId/postpone', {'commandId': commandId});
  }

  @override
  Future<void> postponeItemToList(
    String householdId,
    String listId,
    String itemId, {
    required String targetListId,
    required String commandId,
  }) async {
    await _client.postJson('/api/v1/households/$householdId/lists/$listId/items/$itemId/postpone-to-list', {
      'targetListId': targetListId,
      'commandId': commandId,
    });
  }
}
