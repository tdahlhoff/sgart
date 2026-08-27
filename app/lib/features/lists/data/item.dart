import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';

/// An item on a shopping list as seen by the caller — id, name, optional note, and quantity
/// (Story 2.3, AC1/AC6). `note` is `null` for an item with no note. `amount` is carried as the
/// server's decimal string (never parsed to a `double` here — display formatting owns that, and a
/// round-trip through `double` could lose precision); `unit` is the server's enum name (e.g.
/// `"PIECE"`).
class Item {
  const Item({required this.itemId, required this.name, required this.note, required this.amount, required this.unit});

  final String itemId;
  final String name;
  final String? note;
  final String amount;
  final String unit;

  /// Fails fast with a mapped [AppException] rather than a raw `TypeError` when the response is
  /// missing a field or has an unexpected shape, so callers resolve it through [AppError.code]. A
  /// missing/null `note` is valid (no note) — only a non-string, non-null value is malformed.
  factory Item.fromJson(Map<String, dynamic> json) {
    final itemId = json['itemId'];
    final name = json['name'];
    final note = json['note'];
    final amount = json['amount'];
    final unit = json['unit'];
    if (itemId is! String ||
        name is! String ||
        (note != null && note is! String) ||
        amount is! String ||
        unit is! String) {
      throw const AppException(AppError(
        code: 'items.malformedResponse',
        message: 'GET items returned an unexpected shape',
      ));
    }
    return Item(itemId: itemId, name: name, note: note as String?, amount: amount, unit: unit);
  }

  @override
  bool operator ==(Object other) =>
      other is Item &&
      other.itemId == itemId &&
      other.name == name &&
      other.note == note &&
      other.amount == amount &&
      other.unit == unit;

  @override
  int get hashCode => Object.hash(itemId, name, note, amount, unit);
}
