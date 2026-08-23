import 'package:intl/intl.dart';

/// Formats a canonical UTC instant into `de-DE` display text in the device's local time zone.
///
/// Input is always the canonical UTC `DateTime` — what would be persisted/sent. The instant is
/// converted to the device's local time zone (`toLocal()`) before formatting so the user sees the
/// wall-clock date/time of their own zone, per the "store UTC, format per user Locale client-side"
/// convention (AR10). Output is display-only: there is no reverse path that parses a formatted
/// string back into a `DateTime`.
class DateFormatter {
  const DateFormatter({this.localeName = 'de_DE'});

  final String localeName;

  String formatDate(DateTime utcInstant) {
    _requireUtc(utcInstant);
    return DateFormat.yMd(localeName).format(utcInstant.toLocal());
  }

  String formatDateAndTime(DateTime utcInstant) {
    _requireUtc(utcInstant);
    return DateFormat('d.M.y, HH:mm', localeName).format(utcInstant.toLocal());
  }

  void _requireUtc(DateTime instant) {
    if (!instant.isUtc) {
      throw ArgumentError.value(instant, 'utcInstant', 'must be a UTC instant');
    }
  }
}
