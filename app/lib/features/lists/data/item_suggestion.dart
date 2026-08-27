import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';

/// A household's last-used attributes for a previously-used item name (Story 2.5, AC1/AC2/AC6) —
/// no `itemId`: a suggestion is not an item, the client mints a fresh one when it adds. `note` is
/// `null` for a suggestion with no note. `amount` is carried as the server's decimal string
/// (never parsed to a `double` here — mirrors [Item]); `unit` is the server's enum name.
class ItemSuggestion {
  const ItemSuggestion({required this.name, required this.note, required this.amount, required this.unit});

  final String name;
  final String? note;
  final String amount;
  final String unit;

  /// Fails fast with a mapped [AppException] rather than a raw `TypeError` when the response is
  /// missing a field or has an unexpected shape (mirrors `Item.fromJson`). A missing/null `note` is
  /// valid (no note) — only a non-string, non-null value is malformed.
  factory ItemSuggestion.fromJson(Map<String, dynamic> json) {
    final name = json['name'];
    final note = json['note'];
    final amount = json['amount'];
    final unit = json['unit'];
    if (name is! String || (note != null && note is! String) || amount is! String || unit is! String) {
      throw const AppException(AppError(
        code: 'itemSuggestions.malformedResponse',
        message: 'GET item-suggestions returned an unexpected shape',
      ));
    }
    return ItemSuggestion(name: name, note: note as String?, amount: amount, unit: unit);
  }

  @override
  bool operator ==(Object other) =>
      other is ItemSuggestion &&
      other.name == name &&
      other.note == note &&
      other.amount == amount &&
      other.unit == unit;

  @override
  int get hashCode => Object.hash(name, note, amount, unit);
}
