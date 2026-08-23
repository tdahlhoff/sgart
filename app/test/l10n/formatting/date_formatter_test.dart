import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:sgart/l10n/formatting/date_formatter.dart';

void main() {
  group('DateFormatter', () {
    const formatter = DateFormatter();

    setUpAll(() async {
      await initializeDateFormatting('de_DE');
    });

    // Feeding a UTC instant derived from a known LOCAL wall-clock lets the assertion stay exact
    // and deterministic across time zones: local -> toUtc() -> the formatter's toLocal() round-trips
    // back to the same local wall-clock the test built.
    test('formatsAUtcInstantAsTheLocalDeDeDate', () {
      final utcInstant = DateTime(2026, 3, 5).toUtc();

      expect(formatter.formatDate(utcInstant), '5.3.2026');
    });

    test('formatsAUtcInstantWithTheLocalDeDeDateAndTime', () {
      final utcInstant = DateTime(2026, 3, 5, 14, 30).toUtc();

      expect(formatter.formatDateAndTime(utcInstant), '5.3.2026, 14:30');
    });

    test('rendersTheLocalWallClockRatherThanTheRawUtcWallClock', () {
      final utcInstant = DateTime.utc(2026, 3, 5, 14, 30);
      final localReference =
          DateFormat('d.M.y, HH:mm', 'de_DE').format(utcInstant.toLocal());

      expect(formatter.formatDateAndTime(utcInstant), localReference);
      // Off UTC, the local rendering must differ from the naive UTC wall-clock string — this is
      // the property that would regress if the toLocal() conversion were dropped.
      if (utcInstant.toLocal().timeZoneOffset != Duration.zero) {
        expect(formatter.formatDateAndTime(utcInstant), isNot('5.3.2026, 14:30'));
      }
    });

    test('rejectsANonUtcInstant', () {
      final localInstant = DateTime(2026, 3, 5);

      expect(() => formatter.formatDate(localInstant), throwsArgumentError);
    });
  });
}
