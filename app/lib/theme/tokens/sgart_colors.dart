import 'package:flutter/material.dart';

/// Raw palette anchors from DESIGN §1. These are the only place literal hex values live;
/// everything else references them by name. One hue = one meaning.
///
/// Naming convention: the bare hue is the light-mode value. `…ForDarkMode` is the lightened
/// variant DESIGN §1 gives for dark mode. `…OnLightTint` / `…OnDarkTint` are the text colors
/// used on a [SgartColors.tintAlpha] tint of the same hue, darkened or lightened only as far
/// as WCAG AA (≥ 4.5:1 at the 11px status-label size) requires — see DESIGN §4b.
abstract final class SgartPalette {
  // Brand + semantic anchors.
  static const Color baltic = Color(0xFF456990); // primary (light)
  static const Color balticForDarkMode = Color(0xFF7BA3CC); // primary (dark), chrome accent
  static const Color balticOnLightTint = Color(0xFF3F5F83); // 5.03:1 on a light baltic tint
  static const Color balticOnDarkTint = Color(0xFF8AAED2); // 5.02:1 on a dark baltic tint
  static const Color verdigris = Color(0xFF1EA896); // success (light)
  static const Color verdigrisForDarkMode = Color(0xFF33C2AD); // success (dark)
  static const Color verdigrisOnLightTint = Color(0xFF136C60); // 5.00:1 on a light verdigris tint
  static const Color amber = Color(0xFFE0912F); // warning (light)
  static const Color amberForDarkMode = Color(0xFFF0A94E); // warning (dark)
  static const Color amberOnLightTint = Color(0xFF8A5515); // 5.04:1 on a light amber tint
  static const Color pink = Color(0xFFF45B69); // error (light)
  static const Color pinkForDarkMode = Color(0xFFF6717D); // error (dark)

  static const Color white = Color(0xFFFFFFFF); // the primary light content surface
  static const Color ghostWhite = Color(0xFFF2F4FF); // the one deliberately-cool surface
  static const Color carbonBlack = Color(0xFF191716); // warm near-black

  // Warm-neutral ramp (keyed to carbon-black's ~20° hue so greys harmonise).
  static const Color neutral900 = Color(0xFF191716);
  static const Color neutral800 = Color(0xFF2B2825);
  static const Color neutral700 = Color(0xFF423D39);
  static const Color neutral600 = Color(0xFF5C554F);
  static const Color neutral500 = Color(0xFF7A726B);
  static const Color neutral400 = Color(0xFF9C938B);
  static const Color neutral300 = Color(0xFFC2B9B1);
  static const Color neutral200 = Color(0xFFDED7D0);
  static const Color neutral100 = Color(0xFFEEE9E4);
  static const Color neutral50 = Color(0xFFF7F4F1);

  /// Faint lift for elevated surfaces (DESIGN §3). Warm near-black at 40% on light; on dark
  /// the background *is* carbon-black, so the shadow has to go deeper than the surface to
  /// read as lift at all.
  static const Color shadowOnLight = Color(0x66191716); // rgba(25,23,22,.4)
  static const Color shadowOnDark = Color(0x99000000);
}

/// Semantic color tokens for one theme mode, carried through the [ThemeData] as a
/// [ThemeExtension] so widgets read them with `context.sgartColors` and never hard-code hex.
/// Values come straight from DESIGN §1 (light + dark columns).
@immutable
class SgartColors extends ThemeExtension<SgartColors> {
  const SgartColors({
    required this.primary,
    required this.onPrimary,
    required this.onPrimaryTint,
    required this.success,
    required this.onSuccess,
    required this.onSuccessTint,
    required this.warning,
    required this.onWarning,
    required this.onWarningTint,
    required this.error,
    required this.onError,
    required this.background,
    required this.surface,
    required this.textPrimary,
    required this.textSecondary,
    required this.onNeutralTint,
    required this.border,
    required this.shadow,
  });

  /// Opacity of a semantic tint background (status labels, tonal buttons). The `onXTint`
  /// colors below are the text colors verified against exactly this alpha.
  static const double tintAlpha = 0.14;

  /// Actions, links, active nav. This is also the chrome accent DESIGN §1 describes — raw
  /// baltic on light chrome, the lightened variant on dark — so there is no separate token.
  final Color primary;

  /// Text/icon on a filled [primary]. Ghost-white on light baltic (5.2:1); carbon-black on the
  /// lightened dark accent, where ghost-white would fail AA at 2.4:1 (DESIGN §1 records the
  /// per-mode split).
  final Color onPrimary;

  /// Text on a [tintAlpha] tint of [primary] — the saturated hue itself fails AA there.
  final Color onPrimaryTint;

  /// Done / purchased.
  final Color success;
  final Color onSuccess;
  final Color onSuccessTint;

  /// Offline / sync-pending.
  final Color warning;
  final Color onWarning;
  final Color onWarningTint;

  /// Conflict / destructive.
  final Color error;
  final Color onError;

  final Color background;

  /// Primary content surface.
  final Color surface;

  final Color textPrimary;
  final Color textSecondary;

  /// Text on a [tintAlpha] tint of [textSecondary] — the neutral status-label variant.
  final Color onNeutralTint;

  /// Hairline / divider color.
  final Color border;

  /// Shadow color for the single flat-forward elevation tier (DESIGN §3).
  final Color shadow;

  factory SgartColors.light() => const SgartColors(
        primary: SgartPalette.baltic,
        onPrimary: SgartPalette.ghostWhite,
        onPrimaryTint: SgartPalette.balticOnLightTint,
        success: SgartPalette.verdigris,
        onSuccess: SgartPalette.carbonBlack,
        onSuccessTint: SgartPalette.verdigrisOnLightTint,
        warning: SgartPalette.amber,
        onWarning: SgartPalette.carbonBlack,
        onWarningTint: SgartPalette.amberOnLightTint,
        error: SgartPalette.pink,
        onError: SgartPalette.carbonBlack,
        background: SgartPalette.ghostWhite,
        surface: SgartPalette.white,
        textPrimary: SgartPalette.carbonBlack,
        textSecondary: SgartPalette.neutral600,
        onNeutralTint: SgartPalette.neutral600,
        border: SgartPalette.neutral200,
        shadow: SgartPalette.shadowOnLight,
      );

  factory SgartColors.dark() => const SgartColors(
        primary: SgartPalette.balticForDarkMode,
        onPrimary: SgartPalette.carbonBlack,
        onPrimaryTint: SgartPalette.balticOnDarkTint,
        success: SgartPalette.verdigrisForDarkMode,
        onSuccess: SgartPalette.carbonBlack,
        onSuccessTint: SgartPalette.verdigrisForDarkMode,
        warning: SgartPalette.amberForDarkMode,
        onWarning: SgartPalette.carbonBlack,
        onWarningTint: SgartPalette.amberForDarkMode,
        error: SgartPalette.pinkForDarkMode,
        onError: SgartPalette.carbonBlack,
        background: SgartPalette.carbonBlack,
        surface: SgartPalette.neutral800,
        textPrimary: SgartPalette.ghostWhite,
        textSecondary: SgartPalette.neutral300,
        onNeutralTint: SgartPalette.neutral300,
        border: SgartPalette.neutral700,
        shadow: SgartPalette.shadowOnDark,
      );

  @override
  SgartColors copyWith({
    Color? primary,
    Color? onPrimary,
    Color? onPrimaryTint,
    Color? success,
    Color? onSuccess,
    Color? onSuccessTint,
    Color? warning,
    Color? onWarning,
    Color? onWarningTint,
    Color? error,
    Color? onError,
    Color? background,
    Color? surface,
    Color? textPrimary,
    Color? textSecondary,
    Color? onNeutralTint,
    Color? border,
    Color? shadow,
  }) {
    return SgartColors(
      primary: primary ?? this.primary,
      onPrimary: onPrimary ?? this.onPrimary,
      onPrimaryTint: onPrimaryTint ?? this.onPrimaryTint,
      success: success ?? this.success,
      onSuccess: onSuccess ?? this.onSuccess,
      onSuccessTint: onSuccessTint ?? this.onSuccessTint,
      warning: warning ?? this.warning,
      onWarning: onWarning ?? this.onWarning,
      onWarningTint: onWarningTint ?? this.onWarningTint,
      error: error ?? this.error,
      onError: onError ?? this.onError,
      background: background ?? this.background,
      surface: surface ?? this.surface,
      textPrimary: textPrimary ?? this.textPrimary,
      textSecondary: textSecondary ?? this.textSecondary,
      onNeutralTint: onNeutralTint ?? this.onNeutralTint,
      border: border ?? this.border,
      shadow: shadow ?? this.shadow,
    );
  }

  @override
  SgartColors lerp(covariant SgartColors? other, double t) {
    if (other == null) return this;
    return SgartColors(
      primary: Color.lerp(primary, other.primary, t)!,
      onPrimary: Color.lerp(onPrimary, other.onPrimary, t)!,
      onPrimaryTint: Color.lerp(onPrimaryTint, other.onPrimaryTint, t)!,
      success: Color.lerp(success, other.success, t)!,
      onSuccess: Color.lerp(onSuccess, other.onSuccess, t)!,
      onSuccessTint: Color.lerp(onSuccessTint, other.onSuccessTint, t)!,
      warning: Color.lerp(warning, other.warning, t)!,
      onWarning: Color.lerp(onWarning, other.onWarning, t)!,
      onWarningTint: Color.lerp(onWarningTint, other.onWarningTint, t)!,
      error: Color.lerp(error, other.error, t)!,
      onError: Color.lerp(onError, other.onError, t)!,
      background: Color.lerp(background, other.background, t)!,
      surface: Color.lerp(surface, other.surface, t)!,
      textPrimary: Color.lerp(textPrimary, other.textPrimary, t)!,
      textSecondary: Color.lerp(textSecondary, other.textSecondary, t)!,
      onNeutralTint: Color.lerp(onNeutralTint, other.onNeutralTint, t)!,
      border: Color.lerp(border, other.border, t)!,
      shadow: Color.lerp(shadow, other.shadow, t)!,
    );
  }
}
