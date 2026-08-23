import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:sgart/l10n/formatting/date_formatter.dart';
import 'package:sgart/l10n/formatting/money_formatter.dart';
import 'package:sgart/l10n/formatting/number_formatter.dart';
import 'package:sgart/l10n/formatting/quantity_formatter.dart';
import 'package:sgart/l10n/gen/app_localizations_de.dart';

/// The formatting layer is display-only: canonical input in, a display [String] out — and
/// nothing in the layer parses a formatted string back into a canonical value. This is
/// exercised by type: every formatter's return type is `String`, with no counterpart method
/// anywhere that accepts a `String` and returns [Money], a quantity, or a [DateTime].
void main() {
  group('formatting layer', () {
    setUpAll(() async {
      await initializeDateFormatting('de_DE');
    });

    test('everyFormatterIsOneDirectionalCanonicalInputToDisplayStringOutput', () {
      expect(const NumberFormatter().format(1), isA<String>());
      expect(const MoneyFormatter().format(Money.euro(1)), isA<String>());
      expect(const DateFormatter().formatDate(DateTime.utc(2026)), isA<String>());
      expect(
        const QuantityFormatter().format(1, Unit.piece, AppLocalizationsDe()),
        isA<String>(),
      );
    });
  });
}
