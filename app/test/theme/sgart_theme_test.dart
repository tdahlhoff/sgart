import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/theme/sgart_theme.dart';
import 'package:sgart/theme/tokens/sgart_colors.dart';
import 'package:sgart/theme/tokens/sgart_shapes.dart';

void main() {
  group('SgartTheme', () {
    test('light and dark both build and carry the SgartColors extension', () {
      final light = SgartTheme.light();
      final dark = SgartTheme.dark();

      expect(light.extension<SgartColors>(), isNotNull);
      expect(dark.extension<SgartColors>(), isNotNull);
      expect(light.brightness, Brightness.light);
      expect(dark.brightness, Brightness.dark);
    });

    test('themes resolve distinct, token-matched backgrounds and text colors', () {
      final light = SgartTheme.light();
      final dark = SgartTheme.dark();

      expect(light.scaffoldBackgroundColor, const Color(0xFFF2F4FF));
      expect(dark.scaffoldBackgroundColor, const Color(0xFF191716));
      expect(light.extension<SgartColors>()!.textPrimary,
          isNot(dark.extension<SgartColors>()!.textPrimary));
    });

    test('typography uses the bundled Inter family', () {
      expect(SgartTheme.light().textTheme.bodyLarge!.fontFamily, 'Inter');
    });

    test('no Material color role is left on the baseline palette', () {
      // Overriding only a handful of roles would leave stock widgets (NavigationBar
      // indicator, Chip, SnackBar) rendering off-brand — DESIGN §1 "one hue, one meaning".
      for (final theme in [SgartTheme.light(), SgartTheme.dark()]) {
        final scheme = theme.colorScheme;
        final colors = theme.extension<SgartColors>()!;
        final onBrand = {
          colors.primary,
          colors.onPrimary,
          colors.onPrimaryTint,
          colors.success,
          colors.onSuccess,
          colors.onSuccessTint,
          colors.error,
          colors.onError,
          colors.background,
          colors.surface,
          colors.textPrimary,
          colors.textSecondary,
          colors.border,
          colors.shadow,
          Colors.transparent,
          colors.primary.withValues(alpha: SgartColors.tintAlpha),
          colors.success.withValues(alpha: SgartColors.tintAlpha),
          colors.error.withValues(alpha: SgartColors.tintAlpha),
        };

        for (final role in <Color>[
          scheme.primary,
          scheme.onPrimary,
          scheme.primaryContainer,
          scheme.onPrimaryContainer,
          scheme.secondary,
          scheme.onSecondary,
          scheme.secondaryContainer,
          scheme.onSecondaryContainer,
          scheme.tertiary,
          scheme.onTertiary,
          scheme.tertiaryContainer,
          scheme.onTertiaryContainer,
          scheme.error,
          scheme.onError,
          scheme.errorContainer,
          scheme.onErrorContainer,
          scheme.surface,
          scheme.onSurface,
          scheme.onSurfaceVariant,
          scheme.outline,
          scheme.outlineVariant,
          scheme.inverseSurface,
          scheme.onInverseSurface,
          scheme.inversePrimary,
          scheme.surfaceTint,
        ]) {
          expect(onBrand, contains(role),
              reason: 'every ColorScheme role must come from the SGART tokens');
        }
      }
    });

    test('light chrome is separated from content by a neutral-200 hairline', () {
      final shape = SgartTheme.light().appBarTheme.shape;

      expect(shape, isA<Border>());
      final bottom = (shape! as Border).bottom;
      expect(bottom.color, const Color(0xFFDED7D0));
      expect(bottom.width, SgartShapes.hairline);
    });

    test('dark chrome carries no hairline — surface and background already separate', () {
      expect(SgartTheme.dark().appBarTheme.shape, isNull);
      expect(SgartTheme.dark().appBarTheme.backgroundColor, const Color(0xFF2B2825));
    });

    test('chrome and surfaces stay flat — no Material shadow ladder', () {
      for (final theme in [SgartTheme.light(), SgartTheme.dark()]) {
        expect(theme.appBarTheme.elevation, 0);
        expect(theme.appBarTheme.scrolledUnderElevation, 0);
        expect(theme.cardTheme.elevation, 0);
        expect(theme.dialogTheme.elevation, 0);
        expect(theme.bottomSheetTheme.elevation, 0);
        expect(theme.popupMenuTheme.elevation, 0);
        expect(theme.navigationBarTheme.elevation, 0);
      }
    });

    test('dividers keep breathing room around the hairline', () {
      final dividerTheme = SgartTheme.light().dividerTheme;

      expect(dividerTheme.thickness, SgartShapes.hairline);
      expect(dividerTheme.space, greaterThan(SgartShapes.hairline));
    });
  });
}
