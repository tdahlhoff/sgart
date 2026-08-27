import '../gen/app_localizations.dart';
import 'number_formatter.dart';

/// Controlled, extensible vocabulary of measurement units — mirrors the backend `Unit` enum
/// (AD-9). Free-text units are never accepted; a quantity can only carry a value from here.
enum Unit { piece, gram, kilogram, millilitre, litre, pack }

/// Maps the backend's `Unit` enum name (`PIECE`, `LITRE`, …) onto its client [Unit], or `null` when
/// the two vocabularies disagree — the single representation of that mapping (every surface that
/// renders or edits a server quantity needs it; callers decide their own fallback).
Unit? unitFromServerName(String? serverName) {
  if (serverName == null) {
    return null;
  }
  for (final unit in Unit.values) {
    if (unit.name.toUpperCase() == serverName) {
      return unit;
    }
  }
  return null;
}

/// Formats an amount + [Unit] as `de-DE` display text, e.g. `(0.5, Unit.kilogram)` → "0,5 kg".
///
/// The unit label comes from the localization catalog ([AppLocalizations]), not a hard-coded
/// string, so it stays translatable alongside the rest of the app's copy.
class QuantityFormatter {
  const QuantityFormatter({this.localeName = 'de_DE'});

  final String localeName;

  String format(num amount, Unit unit, AppLocalizations localizations) {
    final formattedAmount = NumberFormatter(localeName: localeName).format(amount);
    return '$formattedAmount ${_unitLabel(unit, localizations)}';
  }

  String _unitLabel(Unit unit, AppLocalizations localizations) => switch (unit) {
    Unit.piece => localizations.unitPiece,
    Unit.gram => localizations.unitGram,
    Unit.kilogram => localizations.unitKilogram,
    Unit.millilitre => localizations.unitMillilitre,
    Unit.litre => localizations.unitLitre,
    Unit.pack => localizations.unitPack,
  };
}
