/// The one client-side rule for a typed quantity: German decimal comma accepted, strictly positive.
/// Returns the amount as the dot-decimal string the backend takes (kept a string — a round-trip
/// through `double` could lose precision), or `null` for a blank, non-numeric or non-positive input
/// that the server would only reject with `item.quantityRequired`/`item.quantityInvalid`.
String? normalizedPositiveAmount(String typedAmount) {
  final normalized = typedAmount.trim().replaceAll(',', '.');
  if (!_decimalNumber.hasMatch(normalized)) {
    return null;
  }
  return double.parse(normalized) > 0 ? normalized : null;
}

final RegExp _decimalNumber = RegExp(r'^\d+(\.\d+)?$');
