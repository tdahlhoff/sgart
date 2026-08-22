import 'package:flutter/material.dart';

/// Typography tokens from DESIGN §2 — Inter throughout, weight-differentiated, with the
/// documented type scale (rem resolved against a 16px base) and tabular figures for digits.
abstract final class SgartTypography {
  /// Bundled family (see pubspec `fonts:`); no runtime network fetch.
  static const String fontFamily = 'Inter';

  // Type scale (rem × 16, lightly rounded to whole logical px).
  static const double sizeDisplay = 27; // ~1.7rem
  static const double sizeTitle = 21; // ~1.32rem
  static const double sizeHeading = 18; // ~1.15rem
  static const double sizeBody = 15; // ~0.92rem
  static const double sizeMeta = 13; // ~0.8rem
  static const double sizeCaption = 12; // ~0.75rem
  static const double sizeKicker = 11; // ~0.66rem

  /// Applies tabular (mono-width) figures to any style — for quantities and counts that align
  /// or update live (DESIGN §2). Prices are post-MVP.
  static TextStyle withTabularFigures(TextStyle base) =>
      base.copyWith(fontFeatures: const [FontFeature.tabularFigures()]);

  /// Inter ships as a single variable asset, so `fontWeight` alone does not move the `wght`
  /// axis — the weight has to be requested explicitly as a font variation or every role
  /// renders at the axis default (400) or synthetic bold.
  static List<FontVariation> _weightAxis(FontWeight weight) =>
      [FontVariation('wght', weight.value.toDouble())];

  /// Uppercase kicker/caption label (letter-spacing ~.12em).
  static final TextStyle kicker = _style(
    sizeKicker,
    FontWeight.w500,
    height: 1.2,
    letterSpacing: sizeKicker * 0.12,
  );

  static TextStyle _style(
    double size,
    FontWeight weight, {
    double height = 1.4,
    double letterSpacing = 0,
    Color? color,
  }) =>
      TextStyle(
        fontFamily: fontFamily,
        fontSize: size,
        fontWeight: weight,
        fontVariations: _weightAxis(weight),
        height: height,
        letterSpacing: letterSpacing,
        color: color,
      );

  /// The full [TextTheme] wired to Inter. Weight roles per DESIGN §2:
  /// display/title 600, heading 600–700, body 400, emphasis/buttons 600, caption/kicker 500.
  ///
  /// Every Material slot is populated, and only from the seven sizes of the DESIGN §2 scale —
  /// an unset slot would silently fall back to Material's own scale (an `AlertDialog` title,
  /// for instance, would render at 24sp, which is not an SGART size).
  static TextTheme textTheme(Color textColor) {
    TextStyle style(
      double size,
      FontWeight weight, {
      double height = 1.4,
      double letterSpacing = 0,
    }) =>
        _style(size, weight,
            height: height, letterSpacing: letterSpacing, color: textColor);

    return TextTheme(
      // Display — slight negative tracking (~-.01em).
      displayLarge: style(sizeDisplay, FontWeight.w600,
          height: 1.2, letterSpacing: sizeDisplay * -0.01),
      displayMedium: style(sizeDisplay, FontWeight.w600,
          height: 1.2, letterSpacing: sizeDisplay * -0.01),
      displaySmall: style(sizeTitle, FontWeight.w600,
          height: 1.25, letterSpacing: sizeTitle * -0.01),
      // Headline — Material's dialog/heading slots, held to the SGART title size.
      headlineLarge: style(sizeTitle, FontWeight.w600,
          height: 1.25, letterSpacing: sizeTitle * -0.01),
      headlineMedium: style(sizeTitle, FontWeight.w600,
          height: 1.25, letterSpacing: sizeTitle * -0.01),
      headlineSmall: style(sizeTitle, FontWeight.w600,
          height: 1.25, letterSpacing: sizeTitle * -0.01),
      // Screen title.
      titleLarge: style(sizeTitle, FontWeight.w600,
          height: 1.25, letterSpacing: sizeTitle * -0.01),
      // Section / group heading.
      titleMedium: style(sizeHeading, FontWeight.w700, height: 1.3),
      titleSmall: style(sizeHeading, FontWeight.w600, height: 1.3),
      // Body / item names (line-height ~1.5).
      bodyLarge: style(sizeBody, FontWeight.w400, height: 1.5),
      bodyMedium: style(sizeBody, FontWeight.w400, height: 1.5),
      // Meta line (dense).
      bodySmall: style(sizeMeta, FontWeight.w400, height: 1.4),
      // Emphasis (counts, values) and buttons.
      labelLarge: style(sizeBody, FontWeight.w600, height: 1.2),
      // Caption.
      labelMedium: style(sizeCaption, FontWeight.w500, height: 1.3),
      // Kicker.
      labelSmall: kicker.copyWith(color: textColor),
    );
  }
}
