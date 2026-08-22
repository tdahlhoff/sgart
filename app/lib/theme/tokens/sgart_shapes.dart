import 'package:flutter/material.dart';

/// Shape, spacing, and elevation tokens — DESIGN §3 "System 3 · Ausgewogen".
/// Medium radius, 4px spacing base, flat-forward elevation (hairline + one faint tier).
abstract final class SgartShapes {
  // Corner radii.
  static const double radiusCard = 14;
  static const double radiusButton = 12;
  static const double radiusControl = 6; // checkbox and similar
  static const double radiusPill = 999; // badge / chip / track

  static const BorderRadius card = BorderRadius.all(Radius.circular(radiusCard));
  static const BorderRadius button = BorderRadius.all(Radius.circular(radiusButton));
  static const BorderRadius control = BorderRadius.all(Radius.circular(radiusControl));
  static const BorderRadius pill = BorderRadius.all(Radius.circular(radiusPill));

  // Spacing scale on a 4px base — every step derives from [spaceUnit] so the base cannot drift.
  static const double spaceUnit = 4;
  static const double space2 = spaceUnit * 2; // 8
  static const double space3 = spaceUnit * 3; // 12
  static const double space4 = spaceUnit * 4; // 16

  /// Half the base unit. The one deliberate sub-unit step, for the "tight padding" DESIGN §4b
  /// asks of the list-row status label so it adds minimal row height.
  static const double spaceHalfUnit = spaceUnit / 2; // 2

  /// DESIGN §3 names this 15px, one off the 4px base — deliberate in the spec (it optically
  /// balances the 14px card radius), not a typo for 16.
  static const double cardPadding = 15;

  // Semantic spacing names from DESIGN §3, expressed in terms of the scale.
  static const double rowPaddingY = space3;
  static const double headingGap = space2;

  /// Minimum interactive target, applied to width as well as height
  /// (accessibility, DESIGN §5 / UX-DR5 / NFR10).
  static const double minTapTarget = 48;

  /// Hairline stroke width for content-surface borders and chrome separators.
  static const double hairline = 1;

  /// Flat-forward lift for elevated cards/sheets: hairline (drawn separately) + one faint tier
  /// (`0 10px 26px -18px`). No multi-layer Material shadow ladder. The color is theme-dependent
  /// — pass `SgartColors.shadow` so dark mode does not paint the background color as a shadow.
  static List<BoxShadow> elevatedShadow(Color shadowColor) => [
        BoxShadow(
          color: shadowColor,
          blurRadius: 26,
          spreadRadius: -18,
          offset: const Offset(0, 10),
        ),
      ];
}
