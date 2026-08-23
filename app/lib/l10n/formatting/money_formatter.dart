import 'package:intl/intl.dart';

/// Money as integer minor units plus an ISO 4217 currency code — mirrors the backend `Money`
/// record (AD-9) field-for-field so it stays wire-compatible once REST endpoints ship.
///
/// Client-side, this is formatter input only: nothing derives a [Money] from a formatted
/// display string.
class Money {
  const Money(this.amountMinor, this.currencyCode);

  final int amountMinor;
  final String currencyCode;

  static Money euro(int amountMinor) => Money(amountMinor, 'EUR');
}

/// Formats a [Money] value as `de-DE` currency text, e.g. `Money.euro(109)` → "1,09 €".
///
/// The MVP only ever produces EUR amounts (see backend `Money` Dev Notes), so the minor-to-major
/// conversion is fixed at 2 fraction digits rather than looked up from a currency registry —
/// building that registry now would be speculative (YAGNI).
class MoneyFormatter {
  const MoneyFormatter({this.localeName = 'de_DE'});

  final String localeName;

  static const _supportedCurrencyCode = 'EUR';
  static const _currencySymbol = '€';
  static const _fractionDigits = 2;
  static const _minorUnitsPerMajorUnit = 100;

  String format(Money money) {
    if (money.currencyCode != _supportedCurrencyCode) {
      throw ArgumentError.value(
        money.currencyCode,
        'money.currencyCode',
        'Only $_supportedCurrencyCode is supported',
      );
    }

    final majorAmount = money.amountMinor / _minorUnitsPerMajorUnit;
    return NumberFormat.currency(
      locale: localeName,
      symbol: _currencySymbol,
      decimalDigits: _fractionDigits,
    ).format(majorAmount);
  }
}
