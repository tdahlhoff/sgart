import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/theme/tokens/sgart_colors.dart';

import '../support/color_contrast.dart';

void main() {
  group('SgartColors — exact DESIGN §1 token values (guards against drift)', () {
    test('light primary is baltic and its on-color is ghost-white', () {
      final light = SgartColors.light();
      expect(light.primary, const Color(0xFF456990));
      expect(light.onPrimary, const Color(0xFFF2F4FF));
    });

    test('dark primary is the lightened accent with a contrast-safe dark on-color', () {
      final dark = SgartColors.dark();
      expect(dark.primary, const Color(0xFF7BA3CC));
      expect(dark.onPrimary, const Color(0xFF191716));
    });

    test('backgrounds flip between ghost-white and carbon-black', () {
      expect(SgartColors.light().background, const Color(0xFFF2F4FF));
      expect(SgartColors.dark().background, const Color(0xFF191716));
    });

    test('surfaces, text and borders match the DESIGN §1 columns per mode', () {
      final light = SgartColors.light();
      expect(light.surface, const Color(0xFFFFFFFF));
      expect(light.textPrimary, const Color(0xFF191716));
      expect(light.textSecondary, const Color(0xFF5C554F));
      expect(light.border, const Color(0xFFDED7D0));

      final dark = SgartColors.dark();
      expect(dark.surface, const Color(0xFF2B2825));
      expect(dark.textPrimary, const Color(0xFFF2F4FF));
      expect(dark.textSecondary, const Color(0xFFC2B9B1));
      expect(dark.border, const Color(0xFF423D39));
    });

    test('semantic roles keep one-hue-one-meaning across modes', () {
      expect(SgartColors.light().success, const Color(0xFF1EA896));
      expect(SgartColors.light().warning, const Color(0xFFE0912F));
      expect(SgartColors.light().error, const Color(0xFFF45B69));
      expect(SgartColors.dark().success, const Color(0xFF33C2AD));
      expect(SgartColors.dark().warning, const Color(0xFFF0A94E));
      expect(SgartColors.dark().error, const Color(0xFFF6717D));
    });

    test('semantic fills carry the carbon-black on-color DESIGN §1 prescribes', () {
      for (final colors in [SgartColors.light(), SgartColors.dark()]) {
        expect(colors.onSuccess, const Color(0xFF191716));
        expect(colors.onWarning, const Color(0xFF191716));
        expect(colors.onError, const Color(0xFF191716));
      }
    });

    test('the warm-neutral ramp holds every documented step', () {
      expect(SgartPalette.neutral900, const Color(0xFF191716));
      expect(SgartPalette.neutral700, const Color(0xFF423D39));
      expect(SgartPalette.neutral600, const Color(0xFF5C554F));
      expect(SgartPalette.neutral500, const Color(0xFF7A726B));
      expect(SgartPalette.neutral400, const Color(0xFF9C938B));
      expect(SgartPalette.neutral300, const Color(0xFFC2B9B1));
      expect(SgartPalette.neutral200, const Color(0xFFDED7D0));
      expect(SgartPalette.neutral100, const Color(0xFFEEE9E4));
      expect(SgartPalette.neutral50, const Color(0xFFF7F4F1));
    });

    test('copyWith replaces only the token it is given', () {
      final light = SgartColors.light();
      final recolored = light.copyWith(primary: const Color(0xFF001122));

      expect(recolored.primary, const Color(0xFF001122));
      expect(recolored.onPrimary, light.onPrimary);
      expect(recolored.background, light.background);
      expect(recolored.border, light.border);
      expect(recolored.shadow, light.shadow);
    });

    test('lerp interpolates every token and returns the endpoints unchanged', () {
      final light = SgartColors.light();
      final dark = SgartColors.dark();

      expect(light.lerp(dark, 0).primary, light.primary);
      expect(light.lerp(dark, 1).primary, dark.primary);
      expect(light.lerp(dark, 1).background, dark.background);
      expect(light.lerp(dark, 1).surface, dark.surface);
      expect(light.lerp(dark, 1).textPrimary, dark.textPrimary);
      expect(light.lerp(dark, 1).textSecondary, dark.textSecondary);
      expect(light.lerp(dark, 1).border, dark.border);
      expect(light.lerp(dark, 1).shadow, dark.shadow);
      expect(light.lerp(dark, 1).onPrimaryTint, dark.onPrimaryTint);
      expect(light.lerp(dark, 1).onSuccessTint, dark.onSuccessTint);
      expect(light.lerp(dark, 1).onWarningTint, dark.onWarningTint);
      expect(light.lerp(dark, 1).onNeutralTint, dark.onNeutralTint);
      expect(light.lerp(null, 1), light);
    });
  });

  group('SgartColors — contrast guarantees (AC3, DESIGN §1 "Contrast ≥ AA")', () {
    test('on-colors clear AA on the fills they sit on, in both modes', () {
      for (final colors in [SgartColors.light(), SgartColors.dark()]) {
        expect(contrastRatio(colors.onPrimary, colors.primary),
            greaterThanOrEqualTo(minimumContrastForNormalText));
        expect(contrastRatio(colors.onSuccess, colors.success),
            greaterThanOrEqualTo(minimumContrastForNormalText));
        expect(contrastRatio(colors.onWarning, colors.warning),
            greaterThanOrEqualTo(minimumContrastForNormalText));
        expect(contrastRatio(colors.onError, colors.error),
            greaterThanOrEqualTo(minimumContrastForNormalText));
      }
    });

    test('tint text colors clear AA on their own tint, on every surface of their mode', () {
      final modes = {
        SgartColors.light(): [SgartColors.light().background, SgartColors.light().surface],
        SgartColors.dark(): [SgartColors.dark().background, SgartColors.dark().surface],
      };

      modes.forEach((colors, backgrounds) {
        final pairings = {
          colors.onPrimaryTint: colors.primary,
          colors.onSuccessTint: colors.success,
          colors.onWarningTint: colors.warning,
          colors.onNeutralTint: colors.textSecondary,
        };

        for (final background in backgrounds) {
          pairings.forEach((foreground, tintSource) {
            final tint = tintOver(tintSource, SgartColors.tintAlpha, background);
            expect(
              contrastRatio(foreground, tint),
              greaterThanOrEqualTo(minimumContrastForNormalText),
              reason: 'tint text must stay legible on its own tint',
            );
          });
        }
      });
    });

    test('body text clears AA on the surfaces of its mode', () {
      for (final colors in [SgartColors.light(), SgartColors.dark()]) {
        for (final background in [colors.background, colors.surface]) {
          expect(contrastRatio(colors.textPrimary, background),
              greaterThanOrEqualTo(minimumContrastForNormalText));
          expect(contrastRatio(colors.textSecondary, background),
              greaterThanOrEqualTo(minimumContrastForNormalText));
        }
      }
    });
  });
}
