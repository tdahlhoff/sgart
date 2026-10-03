import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/lists/data/amount_input.dart';

void main() {
  group('normalizedPositiveAmount', () {
    test('acceptsAGermanDecimalCommaAndReturnsItAsDot', () {
      expect(normalizedPositiveAmount('0,5'), '0.5');
    });

    test('keepsAWholeNumberAndTrimsSurroundingWhitespace', () {
      expect(normalizedPositiveAmount(' 12 '), '12');
    });

    test('rejectsBlankZeroNegativeAndNonNumericInput', () {
      expect(normalizedPositiveAmount(''), isNull);
      expect(normalizedPositiveAmount('0'), isNull);
      expect(normalizedPositiveAmount('-2'), isNull);
      expect(normalizedPositiveAmount('abc'), isNull);
    });

    test('rejectsInfinityWhichDoubleParsingWouldAccept', () {
      expect(normalizedPositiveAmount('Infinity'), isNull);
    });
  });
}
