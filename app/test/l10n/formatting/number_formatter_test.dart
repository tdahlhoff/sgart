import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/l10n/formatting/number_formatter.dart';

void main() {
  group('NumberFormatter', () {
    const formatter = NumberFormatter();

    test('formatsFractionalNumberWithGermanCommaDecimalAndDotThousandsSeparator', () {
      expect(formatter.format(1234.5), '1.234,5');
    });

    test('formatsWholeNumberWithoutTrailingDecimal', () {
      expect(formatter.format(3), '3');
    });

    test('formatsZeroAsZero', () {
      expect(formatter.format(0), '0');
    });
  });
}
