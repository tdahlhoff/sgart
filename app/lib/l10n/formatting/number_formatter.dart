import 'package:intl/intl.dart';

/// Formats plain numbers per a locale (`de-DE`: comma decimal, dot thousands separator).
class NumberFormatter {
  const NumberFormatter({this.localeName = 'de_DE'});

  final String localeName;

  String format(num value) => NumberFormat.decimalPattern(localeName).format(value);
}
