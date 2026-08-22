import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/shared/widgets/status_label.dart';
import 'package:sgart/theme/sgart_theme.dart';
import 'package:sgart/theme/tokens/sgart_colors.dart';

import '../../support/color_contrast.dart';

Widget _host(Widget child, {ThemeData? theme}) => MaterialApp(
      theme: theme ?? SgartTheme.light(),
      home: Scaffold(body: Center(child: child)),
    );

Color _tintBackground(WidgetTester tester) {
  final decorated = tester.widget<DecoratedBox>(
    find.descendant(of: find.byType(StatusLabel), matching: find.byType(DecoratedBox)),
  );
  return (decorated.decoration as BoxDecoration).color!;
}

Color _textColor(WidgetTester tester, String text) =>
    tester.widget<Text>(find.text(text)).style!.color!;

void main() {
  group('StatusLabel', () {
    testWidgets('renders its text uppercased', (tester) async {
      await tester.pumpWidget(_host(const StatusLabel(text: 'Admin')));

      expect(find.text('ADMIN'), findsOneWidget);
      expect(find.text('Admin'), findsNothing);
    });

    testWidgets('gives assistive technology the original, un-uppercased text',
        (tester) async {
      final semantics = tester.ensureSemantics();
      await tester.pumpWidget(_host(const StatusLabel(text: 'Ausstehend')));

      expect(find.bySemanticsLabel('Ausstehend'), findsOneWidget);

      semantics.dispose();
    });

    testWidgets('each semantic variant paints its own DESIGN §4b tint', (tester) async {
      final colors = SgartColors.light();
      final expectedTintSources = {
        StatusLabelVariant.admin: colors.primary,
        StatusLabelVariant.neutral: colors.textSecondary,
        StatusLabelVariant.pending: colors.warning,
        StatusLabelVariant.storeChain: colors.success,
      };
      final expectedTextColors = {
        StatusLabelVariant.admin: colors.onPrimaryTint,
        StatusLabelVariant.neutral: colors.onNeutralTint,
        StatusLabelVariant.pending: colors.onWarningTint,
        StatusLabelVariant.storeChain: colors.onSuccessTint,
      };

      for (final variant in StatusLabelVariant.values) {
        await tester.pumpWidget(_host(
          StatusLabel(text: variant.name, variant: variant),
        ));

        expect(
          _tintBackground(tester),
          expectedTintSources[variant]!.withValues(alpha: SgartColors.tintAlpha),
        );
        expect(_textColor(tester, variant.name.toUpperCase()), expectedTextColors[variant]);
      }
    });

    testWidgets('every variant stays legible on its own tint, in both modes',
        (tester) async {
      for (final theme in [SgartTheme.light(), SgartTheme.dark()]) {
        final background = theme.scaffoldBackgroundColor;

        for (final variant in StatusLabelVariant.values) {
          await tester.pumpWidget(_host(
            StatusLabel(text: variant.name, variant: variant),
            theme: theme,
          ));

          final composited = Color.alphaBlend(_tintBackground(tester), background);
          final textColor = _textColor(tester, variant.name.toUpperCase());

          expect(
            contrastRatio(textColor, composited),
            greaterThanOrEqualTo(minimumContrastForNormalText),
            reason: '$variant must clear WCAG AA — AC3',
          );
        }
      }
    });

    testWidgets('a long name keeps the full row width above the label', (tester) async {
      const longName = 'Alexandra Beispiel-Mustermann von und zu Testhausen';
      await tester.pumpWidget(_host(
        const SizedBox(
          width: 300,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(longName),
              StatusLabel(text: 'Admin', variant: StatusLabelVariant.admin),
            ],
          ),
        ),
      ));

      // The label sits on its own line, so it never competes for the name's width
      // (DESIGN §4b: never a trailing pill).
      final nameSize = tester.getSize(find.text(longName));
      final labelSize = tester.getSize(find.byType(StatusLabel));
      expect(nameSize.width, 300, reason: 'the name keeps the full row width');
      expect(labelSize.width, lessThan(nameSize.width));
      expect(tester.getTopLeft(find.byType(StatusLabel)).dy,
          greaterThanOrEqualTo(tester.getBottomLeft(find.text(longName)).dy),
          reason: 'the label is a second line under the name, not beside it');
    });

    testWidgets('adds minimal height to a row', (tester) async {
      await tester.pumpWidget(_host(const StatusLabel(text: 'Admin')));

      // Dense by design (DESIGN §4b): the 11px kicker plus tight vertical padding.
      expect(tester.getSize(find.byType(StatusLabel)).height, lessThan(24));
    });
  });
}
