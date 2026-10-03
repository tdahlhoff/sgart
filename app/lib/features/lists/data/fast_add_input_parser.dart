import '../../../l10n/formatting/unit.dart';
import 'amount_input.dart';

/// What the member typed into the fast-add field, split into the article [name] and the quantity
/// they spelled out — `null` for each part they left out.
class FastAddInput {
  const FastAddInput({required this.name, this.amount, this.unit});

  final String name;

  /// Dot-decimal amount (see [normalizedPositiveAmount]), or `null` when none was typed.
  final String? amount;

  /// The unit the member typed explicitly, or `null` when they left it to the household's memory.
  final Unit? unit;
}

/// Reads `5 Milch`, `0,5 l Milch`, `500g Mehl` or `2x Nudeln` into name + amount + unit; any other
/// text — including a name that merely starts with a digit, like `7up` — stays one plain name.
class FastAddInputParser {
  const FastAddInputParser();

  FastAddInput parse(String typedText) {
    final text = typedText.trim();
    final wholeTextAsName = FastAddInput(name: text);
    final numberAndRest = _leadingNumber.firstMatch(text);
    final amount = numberAndRest == null ? null : normalizedPositiveAmount(numberAndRest.group(1)!);
    if (amount == null) {
      return wholeTextAsName;
    }
    final rest = numberAndRest!.group(2)!;

    final countMarker = _countMarkerThenName.firstMatch(rest);
    if (countMarker != null) {
      return FastAddInput(name: countMarker.group(1)!.trim(), amount: amount);
    }
    final unitThenName = _wordThenName.firstMatch(rest);
    final unit = unitThenName == null ? null : _unitForAlias(unitThenName.group(1)!);
    if (unit != null) {
      return FastAddInput(name: unitThenName!.group(2)!.trim(), amount: amount, unit: unit);
    }
    final spaceThenName = _spaceThenName.firstMatch(rest);
    // `1 l` is a unit without an article, not an article called "l".
    if (spaceThenName == null || _unitForAlias(spaceThenName.group(1)!.trim()) != null) {
      return wholeTextAsName;
    }
    return FastAddInput(name: spaceThenName.group(1)!.trim(), amount: amount);
  }

  static final RegExp _leadingNumber = RegExp(r'^(\d+(?:[.,]\d+)?)(.*)$');
  static final RegExp _countMarkerThenName = RegExp(r'^\s*[x×]\s+(.+)$');
  static final RegExp _wordThenName = RegExp(r'^\s*(\p{L}+\.?)\s+(.+)$', unicode: true);
  static final RegExp _spaceThenName = RegExp(r'^\s+(.+)$');

  /// German input grammar, not display text — which is why it is not in the ARB catalog.
  static const Map<String, Unit> _unitsByAlias = {
    'g': Unit.gram,
    'gr': Unit.gram,
    'gramm': Unit.gram,
    'kg': Unit.kilogram,
    'kilo': Unit.kilogram,
    'kilogramm': Unit.kilogram,
    'ml': Unit.millilitre,
    'milliliter': Unit.millilitre,
    'l': Unit.litre,
    'liter': Unit.litre,
    'stk': Unit.piece,
    'stück': Unit.piece,
    'pack': Unit.pack,
    'packs': Unit.pack,
    'pck': Unit.pack,
    'packung': Unit.pack,
    'packungen': Unit.pack,
  };

  static Unit? _unitForAlias(String word) {
    final withoutTrailingDot = word.endsWith('.') ? word.substring(0, word.length - 1) : word;
    return _unitsByAlias[withoutTrailingDot.toLowerCase()];
  }
}
