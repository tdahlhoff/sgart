import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/lists/data/item_suggestion.dart';
import 'package:sgart/features/lists/presentation/list_detail/item_suggestion_cache.dart';

void main() {
  group('ItemSuggestionCache', () {
    const cache = ItemSuggestionCache();

    test('mergedWithLocalUpserts_keepsALocalEntryTheServerSnapshotDoesNotCarryYet', () {
      const local = [ItemSuggestion(name: 'Käse', note: null, amount: '1', unit: 'PIECE')];
      const fromServer = [ItemSuggestion(name: 'Milch', note: 'Bio', amount: '2', unit: 'LITRE')];

      final merged = cache.mergedWithLocalUpserts(local, fromServer);

      expect(merged.map((suggestion) => suggestion.name), ['Käse', 'Milch']);
    });

    test('mergedWithLocalUpserts_ordersCaseInsensitively', () {
      const local = <ItemSuggestion>[];
      const fromServer = [
        ItemSuggestion(name: 'Apfel', note: null, amount: '1', unit: 'PIECE'),
        ItemSuggestion(name: 'birne', note: null, amount: '1', unit: 'PIECE'),
        ItemSuggestion(name: 'Zucker', note: null, amount: '1', unit: 'PACK'),
      ];

      final merged = cache.mergedWithLocalUpserts(local, fromServer);

      expect(merged.map((suggestion) => suggestion.name), ['Apfel', 'birne', 'Zucker']);
    });

    test('matching_returnsEmptyForABlankQuery', () {
      const current = [ItemSuggestion(name: 'Milch', note: null, amount: '1', unit: 'PIECE')];

      expect(cache.matching(current, '   '), isEmpty);
    });

    test('matching_matchesByTrimmedCaseInsensitivePrefix', () {
      const current = [
        ItemSuggestion(name: 'Brot', note: null, amount: '1', unit: 'PACK'),
        ItemSuggestion(name: 'Milch', note: null, amount: '1', unit: 'LITRE'),
        ItemSuggestion(name: 'Milchreis', note: null, amount: '1', unit: 'PACK'),
      ];

      final matches = cache.matching(current, '  MIL ');

      expect(matches.map((suggestion) => suggestion.name), ['Milch', 'Milchreis']);
    });

    test('upserted_replacesAnExistingEntryWithTheSameNormalizedNameAndKeepsAlphabeticalOrder', () {
      const current = [
        ItemSuggestion(name: 'Brot', note: null, amount: '1', unit: 'PACK'),
        ItemSuggestion(name: 'Milch', note: null, amount: '1', unit: 'LITRE'),
      ];

      final upserted = cache.upserted(current, 'milch', 'Bio', '2', 'LITRE');

      expect(upserted, hasLength(2));
      final milch = upserted.singleWhere((suggestion) => suggestion.name == 'milch');
      expect(milch.note, 'Bio');
      expect(milch.amount, '2');
    });
  });
}
