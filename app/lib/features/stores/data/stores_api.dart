import '../../../shared/http/authenticated_http_client.dart';
import 'store_chain.dart';
import 'store_summary.dart';

/// The client's store-management source — calls the backend's store slice under a household
/// (`/api/v1/households/{householdId}/stores`) plus the household-less chain reference endpoint
/// (`/api/v1/store-chains`) (Story 1.8).
abstract interface class StoresApi {
  /// Lists the household's active (non-archived) stores (AC5-structural).
  Future<List<StoreSummary>> listStores(String householdId);

  /// Adds a store by free-form [name] with an optional accepted [chainId] (AC1/AC2). [commandId] and
  /// [storeId] are the caller-minted idempotency keys reused across retries of the *same* intent
  /// (AD-8), exactly like `renameHousehold`. The caller mints [storeId] (not this method) so a retry
  /// reuses the same id, matching the reused [commandId] the server dedupes on — and so the caller
  /// can optimistically render it without waiting on the read model (read-your-writes).
  Future<void> addStore(
    String householdId,
    String name, {
    required String storeId,
    String? chainId,
    required String commandId,
  });

  /// Archives (soft-removes) [storeId] (AC3) — it never row-deletes, so past trips keep their
  /// record. [commandId] is the reused idempotency key for the archive intent.
  Future<void> archiveStore(String householdId, String storeId, {required String commandId});

  /// Fetches the global store-chain reference list the client caches for offline matching (AC2).
  Future<List<StoreChain>> listStoreChains();
}

class HttpStoresApi implements StoresApi {
  const HttpStoresApi(this._client);

  final AuthenticatedHttpClient _client;

  @override
  Future<List<StoreSummary>> listStores(String householdId) async {
    final json = await _client.getJsonList('/api/v1/households/$householdId/stores');
    return json.map((entry) => StoreSummary.fromJson(entry as Map<String, dynamic>)).toList();
  }

  @override
  Future<void> addStore(
    String householdId,
    String name, {
    required String storeId,
    String? chainId,
    required String commandId,
  }) async {
    // The caller-minted store id is sent in the envelope, so the response needs no body
    // (read-your-writes without a projection wait) — the same rationale as the household id in
    // create — and a retry reuses the same id, matching the deduped commandId.
    await _client.postJson('/api/v1/households/$householdId/stores', {
      'storeId': storeId,
      'name': name,
      'chainId': chainId,
      'commandId': commandId,
    });
  }

  @override
  Future<void> archiveStore(String householdId, String storeId, {required String commandId}) {
    return _client.deleteJson(
      '/api/v1/households/$householdId/stores/$storeId',
      {'commandId': commandId},
    );
  }

  @override
  Future<List<StoreChain>> listStoreChains() async {
    final json = await _client.getJsonList('/api/v1/store-chains');
    return json.map((entry) => StoreChain.fromJson(entry as Map<String, dynamic>)).toList();
  }
}
