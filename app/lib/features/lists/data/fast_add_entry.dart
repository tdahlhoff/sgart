import 'item_suggestion.dart';

/// Everything the fast-add field sends for one article: the typed name plus whatever the household
/// remembers about it, with the quantity the member typed laid over the top. [unit] is the
/// backend's `Unit` enum name.
class FastAddEntry {
  const FastAddEntry({
    required this.name,
    required this.amount,
    required this.unit,
    this.note,
    this.defaultStoreId,
  });

  /// A suggestion exactly as last used — what tapping it adds when nothing was typed on top.
  factory FastAddEntry.fromSuggestion(ItemSuggestion suggestion) => FastAddEntry(
        name: suggestion.name,
        amount: suggestion.amount,
        unit: suggestion.unit,
        note: suggestion.note,
        defaultStoreId: suggestion.defaultStoreId,
      );

  final String name;
  final String amount;
  final String unit;
  final String? note;
  final String? defaultStoreId;
}
