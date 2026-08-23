import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/l10n/formatting/quantity_formatter.dart';
import 'package:sgart/l10n/gen/app_localizations_de.dart';

void main() {
  group('QuantityFormatter', () {
    const formatter = QuantityFormatter();
    final localizations = AppLocalizationsDe();

    test('formatsAFractionalKilogramAmountWithTheGermanUnitLabel', () {
      expect(formatter.format(0.5, Unit.kilogram, localizations), '0,5 kg');
    });

    test('formatsAWholePieceAmountWithTheGermanUnitLabel', () {
      expect(formatter.format(3, Unit.piece, localizations), '3 Stück');
    });

    test('formatsEveryControlledUnitWithItsGermanLabel', () {
      expect(formatter.format(1, Unit.gram, localizations), '1 g');
      expect(formatter.format(1, Unit.millilitre, localizations), '1 ml');
      expect(formatter.format(1, Unit.litre, localizations), '1 l');
      expect(formatter.format(1, Unit.pack, localizations), '1 Pack');
    });
  });
}
