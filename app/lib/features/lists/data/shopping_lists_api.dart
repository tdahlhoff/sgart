import '../../../shared/http/authenticated_http_client.dart';
import 'shopping_list_summary.dart';

/// The client's shopping-list source — calls the backend's list slice under a household
/// (`/api/v1/households/{householdId}/lists`) (Story 2.1; the `?filter` split, Story 2.2).
abstract interface class ShoppingListsApi {
  /// Lists the household's Open lists in creation order (AC1/AC2 — the client derives „Liste N"
  /// from the array position). Calls the default/`?filter=open` endpoint.
  Future<List<ShoppingListSummary>> listOpenLists(String householdId);

  /// Lists the household's Done lists — the read-only „Erledigt" archive (Story 2.2, AC2). Always
  /// empty in Epic 2 (no capability produces a Done list yet); calls `?filter=done`.
  Future<List<ShoppingListSummary>> listDoneLists(String householdId);

  /// Creates a list, named or unnamed (AC1). [commandId] and [listId] are the caller-minted
  /// idempotency keys reused across retries of the *same* intent (AD-8), exactly like `addStore`.
  /// The caller mints [listId] (not this method) so a retry reuses the same id, and so the caller
  /// can optimistically render it without waiting on the read model (read-your-writes).
  Future<void> createList(String householdId, {String? name, required String listId, required String commandId});

  /// Renames [listId] to [name] (AC3). [commandId] is the reused idempotency key for the rename
  /// intent. A backend that rejects a non-Open list surfaces as an [AppException] carrying
  /// `list.nameChangeNotPermitted`.
  Future<void> renameList(String householdId, String listId, String name, {required String commandId});
}

class HttpShoppingListsApi implements ShoppingListsApi {
  const HttpShoppingListsApi(this._client);

  final AuthenticatedHttpClient _client;

  @override
  Future<List<ShoppingListSummary>> listOpenLists(String householdId) async {
    final json = await _client.getJsonList('/api/v1/households/$householdId/lists');
    return json.map((entry) => ShoppingListSummary.fromJson(entry as Map<String, dynamic>)).toList();
  }

  @override
  Future<List<ShoppingListSummary>> listDoneLists(String householdId) async {
    final json = await _client.getJsonList('/api/v1/households/$householdId/lists?filter=done');
    return json.map((entry) => ShoppingListSummary.fromJson(entry as Map<String, dynamic>)).toList();
  }

  @override
  Future<void> createList(
    String householdId, {
    String? name,
    required String listId,
    required String commandId,
  }) async {
    // The caller-minted list id is sent in the envelope, so the response needs no body
    // (read-your-writes without a projection wait) — the same rationale as the store id in addStore.
    await _client.postJson('/api/v1/households/$householdId/lists', {
      'listId': listId,
      'name': name,
      'commandId': commandId,
    });
  }

  @override
  Future<void> renameList(String householdId, String listId, String name, {required String commandId}) {
    return _client.patchJson(
      '/api/v1/households/$householdId/lists/$listId',
      {'name': name, 'commandId': commandId},
    );
  }
}
