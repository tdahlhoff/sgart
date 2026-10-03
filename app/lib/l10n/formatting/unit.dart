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

/// The backend's `Unit` enum name for this unit (`Unit.litre` → `LITRE`) — the inverse of
/// [unitFromServerName].
extension UnitServerName on Unit {
  String get serverName => name.toUpperCase();
}
