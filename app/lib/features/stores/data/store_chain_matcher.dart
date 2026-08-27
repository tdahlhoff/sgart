import '../data/store_chain.dart';

/// Client-side, advisory store-chain matcher (Story 1.8, AC2, FR3). Given the typed store name and
/// the cached reference list, it returns the best chain suggestion — or `null` when nothing
/// matches. Matching is 100% client-side: no network, no server call, so it works offline once the
/// reference list is cached.
///
/// Deterministic and simple (KISS): case-insensitive; a chain matches when the typed name **starts
/// with** the chain name (e.g. „Aldi Süd" → „Aldi") or otherwise **contains** it as a whole word
/// („Edeka Schiedemann" → „Edeka"). A starts-with match wins over a contains match, and among
/// equal-kind matches the **longest** chain name wins, so „Aldi Süd" is not shadowed by a shorter
/// coincidental chain. The suggestion is never forced — the caller may accept, change, or clear it.
class StoreChainMatcher {
  const StoreChainMatcher();

  StoreChain? suggestFor(String typedName, List<StoreChain> chains) {
    final needle = typedName.trim().toLowerCase();
    if (needle.isEmpty) {
      return null;
    }

    StoreChain? bestPrefix;
    StoreChain? bestContains;
    for (final chain in chains) {
      final candidate = chain.name.toLowerCase();
      if (candidate.isEmpty) {
        continue;
      }
      if (needle.startsWith(candidate)) {
        if (bestPrefix == null || candidate.length > bestPrefix.name.length) {
          bestPrefix = chain;
        }
      } else if (_containsWord(needle, candidate)) {
        if (bestContains == null || candidate.length > bestContains.name.length) {
          bestContains = chain;
        }
      }
    }
    return bestPrefix ?? bestContains;
  }

  /// Whether [needle] contains [candidate] on a word boundary, so „Edeka Schiedemann" matches
  /// „Edeka" but „Bioladen" does not spuriously match a chain that is merely a substring of a word.
  static bool _containsWord(String needle, String candidate) {
    var from = 0;
    while (true) {
      final index = needle.indexOf(candidate, from);
      if (index < 0) {
        return false;
      }
      final atWordStart = index == 0 || needle[index - 1] == ' ';
      final endIndex = index + candidate.length;
      final atWordEnd = endIndex == needle.length || needle[endIndex] == ' ';
      if (atWordStart && atWordEnd) {
        return true;
      }
      from = index + 1;
    }
  }
}
