import 'package:intl/intl.dart';

/// Formats plain numbers per a locale (`de-DE`: comma decimal, dot thousands separator).
class NumberFormatter {
  const NumberFormatter({this.localeName = 'de_DE', this.groupThousands = true});

  final String localeName;

  /// When `false`, the thousands separator is omitted. Required whenever the result is fed back into
  /// a decimal input field: the German thousands `.` would otherwise be read as a decimal point
  /// (e.g. a sum of `1600` formats as `"1.600"` and parses back as `1.6` — a 1000× corruption).
  final bool groupThousands;

  String format(num value) {
    final formatter = NumberFormat.decimalPattern(localeName);
    if (!groupThousands) {
      formatter.turnOffGrouping();
    }
    return formatter.format(value);
  }
}
