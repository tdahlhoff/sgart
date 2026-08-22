import 'package:flutter/material.dart';

import 'tokens/sgart_colors.dart';
import 'tokens/sgart_shapes.dart';
import 'tokens/sgart_typography.dart';

/// Builds the light and dark [ThemeData] entirely from SGART design tokens. Widgets read
/// semantic colors via `context.sgartColors`; stock Material widgets inherit on-brand colors
/// through the [ColorScheme]. Material 3, flat-forward.
abstract final class SgartTheme {
  static ThemeData light() => _build(Brightness.light, SgartColors.light());

  static ThemeData dark() => _build(Brightness.dark, SgartColors.dark());

  static ThemeData _build(Brightness brightness, SgartColors colors) {
    final colorScheme = _colorScheme(brightness, colors);
    final textTheme = SgartTypography.textTheme(colors.textPrimary);
    final isLight = brightness == Brightness.light;

    final surfaceShape = RoundedRectangleBorder(
      borderRadius: SgartShapes.card,
      side: BorderSide(color: colors.border, width: SgartShapes.hairline),
    );

    return ThemeData(
      useMaterial3: true,
      brightness: brightness,
      colorScheme: colorScheme,
      scaffoldBackgroundColor: colors.background,
      canvasColor: colors.background,
      // fontFamily is not set here: every style in the TextTheme already carries it, along
      // with the wght font variation that a variable font needs.
      textTheme: textTheme,
      shadowColor: colors.shadow,
      dividerColor: colors.border,
      dividerTheme: DividerThemeData(
        color: colors.border,
        thickness: SgartShapes.hairline,
        // `space` is the divider's total vertical extent, not its stroke — at the hairline
        // width content would sit flush against it, against DESIGN §3's comfortable-compact.
        space: SgartShapes.space3,
      ),
      // Chrome follows the theme (light chrome in light mode, dark in dark) — not a persistent
      // dark frame. No gradient, flat elevation. DESIGN §1 asks for a neutral-200 hairline
      // between light chrome and content; in dark, surface and background already separate.
      appBarTheme: AppBarTheme(
        backgroundColor: colors.surface,
        foregroundColor: colors.textPrimary,
        surfaceTintColor: Colors.transparent,
        shadowColor: colors.shadow,
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: false,
        titleTextStyle: textTheme.titleLarge,
        shape: isLight
            ? Border(
                bottom: BorderSide(
                  color: colors.border,
                  width: SgartShapes.hairline,
                ),
              )
            : null,
      ),
      cardTheme: CardThemeData(
        color: colors.surface,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        shape: surfaceShape,
      ),
      // Modals keep the flat-forward system: hairline + the single faint tier, never the
      // Material shadow ladder (DESIGN §3).
      dialogTheme: DialogThemeData(
        backgroundColor: colors.surface,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        shape: surfaceShape,
        titleTextStyle: textTheme.headlineSmall,
        contentTextStyle: textTheme.bodyLarge,
      ),
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: colors.surface,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: const BorderRadius.vertical(
            top: Radius.circular(SgartShapes.radiusCard),
          ),
          side: BorderSide(color: colors.border, width: SgartShapes.hairline),
        ),
      ),
      popupMenuTheme: PopupMenuThemeData(
        color: colors.surface,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        shape: surfaceShape,
        textStyle: textTheme.bodyLarge,
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: colors.surface,
        surfaceTintColor: Colors.transparent,
        indicatorColor: colors.primary.withValues(alpha: SgartColors.tintAlpha),
        elevation: 0,
        labelTextStyle: WidgetStatePropertyAll(textTheme.labelMedium),
      ),
      extensions: [colors],
    );
  }

  /// Every Material color role is derived from the SGART tokens. Starting from
  /// `ColorScheme.light()/.dark()` and overriding a handful of roles would leave the rest on
  /// Material's own baseline, so a stock `NavigationBar` indicator, `Chip` or `SnackBar` would
  /// render off-brand — against DESIGN §1's "one hue, one meaning".
  static ColorScheme _colorScheme(Brightness brightness, SgartColors colors) {
    final tint = colors.primary.withValues(alpha: SgartColors.tintAlpha);
    return ColorScheme(
      brightness: brightness,
      primary: colors.primary,
      onPrimary: colors.onPrimary,
      primaryContainer: tint,
      onPrimaryContainer: colors.onPrimaryTint,
      secondary: colors.primary,
      onSecondary: colors.onPrimary,
      secondaryContainer: tint,
      onSecondaryContainer: colors.onPrimaryTint,
      tertiary: colors.success,
      onTertiary: colors.onSuccess,
      tertiaryContainer: colors.success.withValues(alpha: SgartColors.tintAlpha),
      onTertiaryContainer: colors.onSuccessTint,
      error: colors.error,
      onError: colors.onError,
      errorContainer: colors.error.withValues(alpha: SgartColors.tintAlpha),
      onErrorContainer: colors.textPrimary,
      surface: colors.surface,
      onSurface: colors.textPrimary,
      onSurfaceVariant: colors.textSecondary,
      outline: colors.border,
      outlineVariant: colors.border,
      shadow: colors.shadow,
      scrim: colors.shadow,
      inverseSurface: colors.textPrimary,
      onInverseSurface: colors.background,
      inversePrimary: colors.onPrimaryTint,
      surfaceTint: Colors.transparent,
    );
  }
}
