import '../../data/item_suggestion.dart';

/// Pure, stateless transformations over the fast-add autocomplete cache (Story 2.5, AC1, AC6, Cl.
/// 2/6) — merging a freshly-fetched server snapshot, upserting a just-used name, and matching a
/// typed prefix. The cubit is the only place that holds the resulting list as state; every method
/// here takes the current list and returns a new one, mirroring the pure-collaborator pattern
/// `StoreChainMatcher` uses for chain suggestions.
class ItemSuggestionCache {
  const ItemSuggestionCache();

  /// Folds [fromServer] over [current], keeping a local entry whose name [fromServer] does not carry
  /// yet. A successful add that lands *while* the server fetch is in flight would otherwise have its
  /// optimistic upsert (see [upserted]) clobbered by the older server snapshot, silently dropping the
  /// just-added name from autocomplete.
  List<ItemSuggestion> mergedWithLocalUpserts(List<ItemSuggestion> current, List<ItemSuggestion> fromServer) {
    final serverNames = fromServer.map((suggestion) => suggestion.name.trim().toLowerCase()).toSet();
    final localOnly = current.where((suggestion) => !serverNames.contains(suggestion.name.trim().toLowerCase()));
    return _sortedByName([...fromServer, ...localOnly]);
  }

  /// Matches [query] against [current] (Story 2.5, AC1, Cl. 2/6): trimmed, case-insensitive
  /// **prefix** match on the name, alphabetical (the cache is already ordered that way), empty query
  /// yields no suggestions (the panel only shows once the member types).
  List<ItemSuggestion> matching(List<ItemSuggestion> current, String query) {
    final trimmedQuery = query.trim().toLowerCase();
    if (trimmedQuery.isEmpty) {
      return const [];
    }
    return current.where((suggestion) => suggestion.name.trim().toLowerCase().startsWith(trimmedQuery)).toList();
  }

  /// Optimistically upserts [name]'s (normalized, case-insensitive) entry in [current] with its
  /// just-used attributes (Story 2.5, AC6, Cl. 2) — read-your-writes for a just-added/edited name even
  /// though the server-side projection is eventually consistent (AR3/NFR9). Mirrors the read model's
  /// own upsert: last-used casing/attributes win, keyed by the normalized name.
  List<ItemSuggestion> upserted(List<ItemSuggestion> current, String name, String? note, String amount, String unit) {
    final normalizedName = name.trim().toLowerCase();
    final withoutExisting =
        current.where((suggestion) => suggestion.name.trim().toLowerCase() != normalizedName).toList();
    final upserted = ItemSuggestion(name: name, note: note, amount: amount, unit: unit);
    return _sortedByName([...withoutExisting, upserted]);
  }

  /// Orders the cache the way the panel reads it: alphabetically, case-insensitively. A raw
  /// `compareTo` would sort by UTF-16 code unit, putting every lower-case name after every upper-case
  /// one — an order the server's `ORDER BY name` never produces, so the panel would reshuffle after a
  /// local upsert and (with the panel's row cap) could hide a real match.
  static List<ItemSuggestion> _sortedByName(List<ItemSuggestion> suggestions) =>
      suggestions..sort((first, second) => first.name.toLowerCase().compareTo(second.name.toLowerCase()));
}
