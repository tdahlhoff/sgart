import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/l10n/formatting/money_formatter.dart';

void main() {
  group('MoneyFormatter', () {
    const formatter = MoneyFormatter();

    // de-DE renders the euro symbol after a non-breaking space (U+00A0), not a regular space —
    // assert the exact separator `intl` produces rather than eyeballing it.
    const nonBreakingSpace = ' ';

    test('formatsEuroAmountWithGermanCommaDecimalAndTrailingSymbol', () {
      expect(formatter.format(Money.euro(109)), '1,09$nonBreakingSpace€');
    });

    test('formatsZeroEuroAmountAsTheBoundaryCase', () {
      expect(formatter.format(Money.euro(0)), '0,00$nonBreakingSpace€');
    });

    test('formatsThousandsSeparatorForLargeAmounts', () {
      expect(formatter.format(Money.euro(123450)), '1.234,50$nonBreakingSpace€');
    });

    test('rejectsAnUnsupportedCurrencyCode', () {
      expect(() => formatter.format(const Money(100, 'USD')), throwsArgumentError);
    });
  });
}
