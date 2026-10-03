import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/lists/data/fast_add_input_parser.dart';
import 'package:sgart/l10n/formatting/unit.dart';

void main() {
  const parser = FastAddInputParser();

  FastAddInput parse(String typedText) => parser.parse(typedText);

  void expectParsed(String typedText, {required String name, String? amount, Unit? unit}) {
    final input = parse(typedText);
    expect((input.name, input.amount, input.unit), (name, amount, unit), reason: 'for "$typedText"');
  }

  group('FastAddInputParser', () {
    test('parse_plainNameCarriesNoQuantity', () {
      expectParsed('Butter', name: 'Butter');
    });

    test('parse_leadingNumberBecomesTheAmountWithoutAUnit', () {
      expectParsed('5 Milch', name: 'Milch', amount: '5');
    });

    test('parse_germanDecimalCommaWithALitreAlias', () {
      expectParsed('0,5 l Milch', name: 'Milch', amount: '0.5', unit: Unit.litre);
    });

    test('parse_unitAliasDirectlyAfterTheNumber', () {
      expectParsed('500g Mehl', name: 'Mehl', amount: '500', unit: Unit.gram);
    });

    test('parse_litreAliasDirectlyAfterTheNumberInAnyCase', () {
      expectParsed('5l Milch', name: 'Milch', amount: '5', unit: Unit.litre);
      expectParsed('5L Milch', name: 'Milch', amount: '5', unit: Unit.litre);
    });

    test('parse_multiplicationMarkerCountsPiecesWithoutAUnit', () {
      expectParsed('2x Nudeln', name: 'Nudeln', amount: '2');
      expectParsed('2 × Nudeln', name: 'Nudeln', amount: '2');
      expectParsed('2 x Nudeln', name: 'Nudeln', amount: '2');
    });

    test('parse_unitAliasesAreCaseInsensitive', () {
      expectParsed('3 LITER Saft', name: 'Saft', amount: '3', unit: Unit.litre);
      expectParsed('2 Packungen Eier', name: 'Eier', amount: '2', unit: Unit.pack);
      expectParsed('4 Stk. Brötchen', name: 'Brötchen', amount: '4', unit: Unit.piece);
    });

    test('parse_unitAliasMustBeAWholeWord', () {
      expectParsed('5 Gurken', name: 'Gurken', amount: '5');
      expectParsed('5 Gurken Salat', name: 'Gurken Salat', amount: '5');
    });

    test('parse_nameStartingWithADigitStaysAName', () {
      expectParsed('7up', name: '7up');
      expectParsed('7up Zero', name: '7up Zero');
    });

    test('parse_numberWithoutANameStaysAName', () {
      expectParsed('5', name: '5');
      expectParsed('1 l', name: '1 l');
    });

    test('parse_nonPositiveAmountStaysAName', () {
      expectParsed('0 Milch', name: '0 Milch');
    });

    test('parse_surroundingWhitespaceIsIgnored', () {
      expectParsed('  5   Milch  ', name: 'Milch', amount: '5');
    });
  });
}
