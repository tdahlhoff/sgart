import '../../../shared/http/authenticated_http_client.dart';
import 'item_suggestion.dart';

/// The client's item suggestion source — calls the backend's household-scoped suggestion endpoint
/// (`/api/v1/households/{householdId}/item-suggestions`) (Story 2.5, AC1). Household-scoped, not
/// list-scoped — the suggestion history spans the whole household (mirrors `StoresApi`'s
/// household-scoped shape rather than `ItemsApi`'s list-scoped one).
abstract interface class ItemSuggestionsApi {
  /// Fetches the household's whole suggestion set once (Cl. 2 — no `?prefix=` query); the caller
  /// caches it in memory and filters client-side per keystroke (lag-free, no per-keystroke call).
  Future<List<ItemSuggestion>> listSuggestions(String householdId);
}

class HttpItemSuggestionsApi implements ItemSuggestionsApi {
  const HttpItemSuggestionsApi(this._client);

  final AuthenticatedHttpClient _client;

  @override
  Future<List<ItemSuggestion>> listSuggestions(String householdId) async {
    final json = await _client.getJsonList('/api/v1/households/$householdId/item-suggestions');
    return json.map((entry) => ItemSuggestion.fromJson(entry as Map<String, dynamic>)).toList();
  }
}
