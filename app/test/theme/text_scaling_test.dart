import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/shared/widgets/sgart_button.dart';
import 'package:sgart/shared/widgets/status_label.dart';
import 'package:sgart/theme/sgart_theme.dart';

const String _longLabel = 'Einkauf für den Haushalt abschließen';

Widget _host(Widget child, {required double textScale, double width = 180}) => MaterialApp(
      theme: SgartTheme.light(),
      home: Builder(
        builder: (context) => MediaQuery(
          // copyWith, not a bare MediaQueryData: replacing it would zero the screen size and
          // padding, so the components would be measured against an unreal viewport.
          data: MediaQuery.of(context).copyWith(
            textScaler: TextScaler.linear(textScale),
          ),
          child: Scaffold(
            body: Center(child: SizedBox(width: width, child: child)),
          ),
        ),
      ),
    );

double _renderedHeight(WidgetTester tester, Type widgetType) =>
    tester.getSize(find.byType(widgetType)).height;

void main() {
  group('OS text scaling (AC2, DESIGN §5)', () {
    testWidgets('a button label grows instead of truncating at 2x scale', (tester) async {
      await tester.pumpWidget(_host(
        const SgartButton(label: _longLabel, onPressed: doNothing),
        textScale: 2.0,
      ));

      expect(tester.takeException(), isNull);

      // Asserting the configuration, not just the absence of an exception: a truncating
      // button throws nothing, so only these can fail if the ellipsis comes back.
      final label = tester.widget<Text>(find.text(_longLabel));
      expect(label.overflow, isNot(TextOverflow.ellipsis),
          reason: 'AC2: the label must never clip');
      expect(label.maxLines, isNull,
          reason: 'AC2: the label wraps as far as it needs to; the button grows');
      expect(find.descendant(of: find.byType(SgartButton), matching: find.byType(FittedBox)),
          findsNothing,
          reason: 'AC2: the label must never shrink');

      final paragraph = tester.renderObject<RenderParagraph>(find.text(_longLabel));
      expect(paragraph.text.style!.fontSize,
          SgartTheme.light().textTheme.labelLarge!.fontSize,
          reason: 'the font size must not shrink to fit');
      expect(paragraph.computeLineMetrics().length, greaterThan(1),
          reason: 'a long label at 2x in a 180px box must actually wrap');
      expect(paragraph.size.height,
          greaterThanOrEqualTo(paragraph.computeLineMetrics().first.height * 2),
          reason: 'every wrapped line must be laid out, not cut off');
    });

    testWidgets('the button grows taller as the text scale grows', (tester) async {
      await tester.pumpWidget(_host(
        const SgartButton(label: _longLabel, onPressed: doNothing),
        textScale: 1.0,
      ));
      final unscaledHeight = _renderedHeight(tester, SgartButton);

      await tester.pumpWidget(_host(
        const SgartButton(label: _longLabel, onPressed: doNothing),
        textScale: 2.0,
      ));
      final scaledHeight = _renderedHeight(tester, SgartButton);

      expect(scaledHeight, greaterThan(unscaledHeight));
    });

    testWidgets('a status label reflows instead of truncating at 2x scale', (tester) async {
      await tester.pumpWidget(_host(
        const StatusLabel(text: 'Ausstehend', variant: StatusLabelVariant.pending),
        textScale: 2.0,
        width: 90,
      ));

      expect(tester.takeException(), isNull);

      final label = tester.widget<Text>(find.text('AUSSTEHEND'));
      expect(label.overflow, isNot(TextOverflow.ellipsis));
      expect(label.maxLines, isNull);

      final paragraph = tester.renderObject<RenderParagraph>(find.text('AUSSTEHEND'));
      expect(paragraph.size.height,
          greaterThanOrEqualTo(paragraph.computeLineMetrics().first.height),
          reason: 'the tag must lay out every line it wraps to');

    });
  });
}

void doNothing() {}
