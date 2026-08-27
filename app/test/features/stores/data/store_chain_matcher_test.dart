import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/stores/data/store_chain.dart';
import 'package:sgart/features/stores/data/store_chain_matcher.dart';

void main() {
  group('StoreChainMatcher', () {
    const matcher = StoreChainMatcher();
    const chains = [
      StoreChain(chainId: 'id-edeka', name: 'Edeka'),
      StoreChain(chainId: 'id-aldi', name: 'Aldi'),
      StoreChain(chainId: 'id-rewe', name: 'Rewe'),
      StoreChain(chainId: 'id-dm', name: 'dm'),
    ];

    test('suggestsTheChainWhenTheTypedNameStartsWithIt', () {
      final suggestion = matcher.suggestFor('Aldi Süd', chains);

      expect(suggestion?.chainId, 'id-aldi');
    });

    test('suggestsTheChainWhenTheTypedNameContainsItAsAWord', () {
      final suggestion = matcher.suggestFor('Edeka Schiedemann', chains);

      expect(suggestion?.chainId, 'id-edeka');
    });

    test('matchesCaseInsensitively', () {
      final suggestion = matcher.suggestFor('edeka schiedemann', chains);

      expect(suggestion?.chainId, 'id-edeka');
    });

    test('returnsNullWhenNothingMatches', () {
      final suggestion = matcher.suggestFor('Wochenmarkt', chains);

      expect(suggestion, isNull);
    });

    test('returnsNullForABlankInput', () {
      expect(matcher.suggestFor('   ', chains), isNull);
    });

    test('doesNotMatchAChainThatIsMerelyASubstringOfAWord', () {
      // "dm" must not match inside "Bodmarkt" — a word-boundary contains match, not a raw substring.
      final suggestion = matcher.suggestFor('Bodmarkt', chains);

      expect(suggestion, isNull);
    });
  });
}
