import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/theme/tokens/sgart_typography.dart';

void main() {
  group('SgartTypography — DESIGN §2 scale and weight roles', () {
    final textTheme = SgartTypography.textTheme(const Color(0xFF191716));

    test('the type scale keeps its documented sizes', () {
      expect(SgartTypography.sizeDisplay, 27);
      expect(SgartTypography.sizeTitle, 21);
      expect(SgartTypography.sizeHeading, 18);
      expect(SgartTypography.sizeBody, 15);
      expect(SgartTypography.sizeMeta, 13);
      expect(SgartTypography.sizeCaption, 12);
      expect(SgartTypography.sizeKicker, 11);
    });

    test('weight roles follow DESIGN §2', () {
      expect(textTheme.displayLarge!.fontWeight, FontWeight.w600);
      expect(textTheme.titleLarge!.fontWeight, FontWeight.w600);
      expect(textTheme.titleMedium!.fontWeight, FontWeight.w700);
      expect(textTheme.bodyLarge!.fontWeight, FontWeight.w400);
      expect(textTheme.labelLarge!.fontWeight, FontWeight.w600);
      expect(textTheme.labelMedium!.fontWeight, FontWeight.w500);
    });

    test('every slot requests its weight as a wght font variation', () {
      // Inter ships as one variable asset: without the axis request the whole weight
      // hierarchy would collapse to the axis default.
      final slots = <TextStyle>[
        textTheme.displayLarge!,
        textTheme.displayMedium!,
        textTheme.displaySmall!,
        textTheme.headlineLarge!,
        textTheme.headlineMedium!,
        textTheme.headlineSmall!,
        textTheme.titleLarge!,
        textTheme.titleMedium!,
        textTheme.titleSmall!,
        textTheme.bodyLarge!,
        textTheme.bodyMedium!,
        textTheme.bodySmall!,
        textTheme.labelLarge!,
        textTheme.labelMedium!,
        textTheme.labelSmall!,
      ];

      for (final slot in slots) {
        expect(slot.fontFamily, 'Inter');
        expect(slot.fontVariations, isNotNull);
        expect(
          slot.fontVariations!.single.value,
          slot.fontWeight!.value.toDouble(),
          reason: 'the wght axis must match the requested FontWeight',
        );
      }
    });

    test('every Material slot is populated and stays on the SGART scale', () {
      // Not const: a set of doubles has no primitive equality, so it cannot be a const literal.
      final sgartSizes = <double>{
        SgartTypography.sizeDisplay,
        SgartTypography.sizeTitle,
        SgartTypography.sizeHeading,
        SgartTypography.sizeBody,
        SgartTypography.sizeMeta,
        SgartTypography.sizeCaption,
        SgartTypography.sizeKicker,
      };

      for (final slot in <TextStyle?>[
        textTheme.displayLarge,
        textTheme.displayMedium,
        textTheme.displaySmall,
        textTheme.headlineLarge,
        textTheme.headlineMedium,
        textTheme.headlineSmall,
        textTheme.titleLarge,
        textTheme.titleMedium,
        textTheme.titleSmall,
        textTheme.bodyLarge,
        textTheme.bodyMedium,
        textTheme.bodySmall,
        textTheme.labelLarge,
        textTheme.labelMedium,
        textTheme.labelSmall,
      ]) {
        expect(slot, isNotNull);
        expect(sgartSizes, contains(slot!.fontSize));
      }
    });

    test('display and title carry the slight negative tracking DESIGN §2 asks for', () {
      expect(textTheme.displayLarge!.letterSpacing, lessThan(0));
      expect(textTheme.titleLarge!.letterSpacing, lessThan(0));
    });

    test('the kicker is an uppercase-scale label with ~.12em tracking', () {
      expect(SgartTypography.kicker.fontSize, SgartTypography.sizeKicker);
      expect(SgartTypography.kicker.fontWeight, FontWeight.w500);
      expect(SgartTypography.kicker.letterSpacing,
          closeTo(SgartTypography.sizeKicker * 0.12, 0.001));
    });

    test('withTabularFigures adds tabular figures without disturbing the style', () {
      final base = textTheme.labelLarge!;
      final tabular = SgartTypography.withTabularFigures(base);

      expect(tabular.fontFeatures, contains(const FontFeature.tabularFigures()));
      expect(tabular.fontSize, base.fontSize);
      expect(tabular.fontWeight, base.fontWeight);
    });
  });
}
