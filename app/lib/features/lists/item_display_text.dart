import '../../l10n/formatting/quantity_formatter.dart';
import '../../l10n/gen/app_localizations.dart';
import 'data/item.dart';

/// The single representation of how an item's quantity and secondary line read to a member — reused
/// by the on-screen list-detail row and the print/share document so the two never drift (Story 3.5,
/// AC5; CLAUDE.md §1 DRY).

/// Formats an item's quantity exactly as it appears everywhere it is shown: the server's decimal
/// [Item.amount] parsed to a `double` (falling back to `0` for an unparseable value) and its
/// [Item.unit] enum name resolved to a [Unit] (falling back to [Unit.piece]).
String formatItemQuantity(Item item, AppLocalizations localizations) {
  final amount = double.tryParse(item.amount) ?? 0;
  final unit = unitFromServerName(item.unit) ?? Unit.piece;
  return const QuantityFormatter().format(amount, unit, localizations);
}

/// The item's secondary line: the quantity, with the note appended after a middle dot when the item
/// carries one.
String formatItemSubtitle(Item item, AppLocalizations localizations) {
  final quantityText = formatItemQuantity(item, localizations);
  return item.note == null ? quantityText : '$quantityText · ${item.note}';
}
