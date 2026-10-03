import 'package:flutter/material.dart';

import '../../theme/sgart_theme_access.dart';

/// Gives every second row of a list a faint neutral background, so rows are easy to tell apart.
/// Wrap each row of a list in one, passing its position; even rows stay bare. The band is as wide as
/// the space the list offers, so lists that use it let their rows run edge to edge and inset their
/// own content.
class StripedRow extends StatelessWidget {
  const StripedRow({super.key, required this.index, required this.child});

  /// Opacity of the neutral tint — enough to separate rows, quiet enough that the text stays the hero.
  static const double tintAlpha = 0.06;

  final int index;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    if (index.isEven) {
      return child;
    }
    // A Material, not a ColoredBox: a tappable ListTile paints its ripple on the nearest Material,
    // which a plain coloured box in between would hide.
    return Material(
      key: const Key('striped-row-tint'),
      color: context.sgartColors.textSecondary.withValues(alpha: tintAlpha),
      child: child,
    );
  }
}
