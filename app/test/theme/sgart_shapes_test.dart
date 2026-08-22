import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/theme/tokens/sgart_shapes.dart';

void main() {
  group('SgartShapes — exact DESIGN §3 token values (guards against drift)', () {
    test('radii match the System 3 scale', () {
      expect(SgartShapes.radiusCard, 14);
      expect(SgartShapes.radiusButton, 12);
      expect(SgartShapes.radiusControl, 6);
      expect(SgartShapes.radiusPill, 999);
    });

    test('the border radii are built from those values', () {
      expect(SgartShapes.card, BorderRadius.circular(14));
      expect(SgartShapes.button, BorderRadius.circular(12));
      expect(SgartShapes.control, BorderRadius.circular(6));
      expect(SgartShapes.pill, BorderRadius.circular(999));
    });

    test('the spacing scale derives from the 4px base', () {
      expect(SgartShapes.spaceUnit, 4);
      expect(SgartShapes.space2, 8);
      expect(SgartShapes.space3, 12);
      expect(SgartShapes.space4, 16);
      expect(SgartShapes.spaceHalfUnit, 2);
    });

    test('the named DESIGN §3 spacings keep their documented values', () {
      expect(SgartShapes.cardPadding, 15);
      expect(SgartShapes.rowPaddingY, 12);
      expect(SgartShapes.headingGap, 8);
    });

    test('the accessibility minimum target is 48px', () {
      expect(SgartShapes.minTapTarget, 48);
    });

    test('elevation is a single faint tier that takes its color from the theme', () {
      const shadowColor = Color(0x66191716);
      final shadow = SgartShapes.elevatedShadow(shadowColor);

      expect(shadow, hasLength(1));
      expect(shadow.single.color, shadowColor);
      expect(shadow.single.blurRadius, 26);
      expect(shadow.single.spreadRadius, -18);
      expect(shadow.single.offset, const Offset(0, 10));
    });
  });
}
