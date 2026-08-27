import 'package:sgart/features/lists/data/item_suggestion.dart';
import 'package:sgart/features/lists/data/item_suggestions_api.dart';

/// Test double for [ItemSuggestionsApi] — no real network in tests (CLAUDE.md §6). Mirrors
/// `FakeItemsApi`.
class FakeItemSuggestionsApi implements ItemSuggestionsApi {
  List<ItemSuggestion> suggestionsToReturn = const [];
  Object? listError;

  @override
  Future<List<ItemSuggestion>> listSuggestions(String householdId) async {
    if (listError != null) throw listError!;
    return suggestionsToReturn;
  }
}
