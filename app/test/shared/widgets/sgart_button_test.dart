import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/shared/widgets/sgart_button.dart';
import 'package:sgart/theme/sgart_theme.dart';
import 'package:sgart/theme/tokens/sgart_colors.dart';

// A MaterialApp resolves its own brightness from the platform, so passing a dark ThemeData to
// its `theme:` slot does not reliably render the tree dark. Wrapping the subtree in an explicit
// [Theme] forces exactly the given ThemeData — the reliable way to exercise a mode's tokens.
Widget _host(Widget child, {double width = 200, ThemeData? theme}) => MaterialApp(
      home: Theme(
        data: theme ?? SgartTheme.light(),
        child: Scaffold(
          body: Center(child: SizedBox(width: width, child: child)),
        ),
      ),
    );

Material _buttonSurface(WidgetTester tester) => tester.widget<Material>(
      find.descendant(of: find.byType(SgartButton), matching: find.byType(Material)),
    );

Color _labelColor(WidgetTester tester, String label) =>
    tester.widget<Text>(find.text(label)).style!.color!;

void main() {
  group('SgartButton', () {
    testWidgets('renders the label as text and invokes onPressed', (tester) async {
      var pressed = 0;
      await tester.pumpWidget(_host(
        SgartButton(label: 'Einkauf starten', onPressed: () => pressed++),
      ));

      expect(find.text('Einkauf starten'), findsOneWidget);
      // Text-only: no icon inside the button.
      expect(find.descendant(of: find.byType(SgartButton), matching: find.byType(Icon)),
          findsNothing);

      await tester.tap(find.byType(SgartButton));
      expect(pressed, 1);
    });

    testWidgets('honors the 48px minimum interactive target in both axes', (tester) async {
      await tester.pumpWidget(_host(
        SgartButton(label: '+', onPressed: () {}),
        width: 300,
      ));

      final size = tester.getSize(find.byType(SgartButton));
      expect(size.height, greaterThanOrEqualTo(48.0));
      expect(size.width, greaterThanOrEqualTo(48.0),
          reason: 'a short label must still leave a 48px-wide target');
    });

    testWidgets('a long label wraps and never shrinks or clips', (tester) async {
      const longLabel = 'Einkauf für den gesamten Haushalt jetzt abschließen';
      await tester.pumpWidget(_host(
        const SgartButton(label: longLabel, onPressed: doNothing),
        width: 160,
      ));

      final text = tester.widget<Text>(find.text(longLabel));
      expect(text.softWrap, isTrue);
      expect(text.maxLines, isNull, reason: 'no line cap — the button grows instead');
      expect(text.overflow, isNot(TextOverflow.ellipsis), reason: 'the label never clips');
      // Font size equals the theme's button style — no auto-shrink applied.
      expect(text.style!.fontSize, SgartTheme.light().textTheme.labelLarge!.fontSize);
      // No FittedBox is used to squeeze the label.
      expect(find.descendant(of: find.byType(SgartButton), matching: find.byType(FittedBox)),
          findsNothing);
    });

    testWidgets('each variant paints its own DESIGN §4 treatment', (tester) async {
      final colors = SgartColors.light();

      await tester.pumpWidget(_host(
        SgartButton(label: 'primary', onPressed: () {}),
      ));
      expect(_buttonSurface(tester).color, colors.primary);
      expect(_labelColor(tester, 'primary'), colors.onPrimary);

      await tester.pumpWidget(_host(
        SgartButton(
          label: 'secondary',
          onPressed: () {},
          variant: SgartButtonVariant.secondary,
        ),
      ));
      expect(_buttonSurface(tester).color, Colors.transparent);
      expect(_labelColor(tester, 'secondary'), colors.primary);
      final shape = _buttonSurface(tester).shape! as RoundedRectangleBorder;
      expect(shape.side.color, colors.primary, reason: 'secondary is outlined');

      await tester.pumpWidget(_host(
        SgartButton(
          label: 'tonal',
          onPressed: () {},
          variant: SgartButtonVariant.tonal,
        ),
      ));
      expect(_buttonSurface(tester).color,
          colors.primary.withValues(alpha: SgartColors.tintAlpha));
      expect(_labelColor(tester, 'tonal'), colors.onPrimaryTint,
          reason: 'the saturated hue on its own tint would fail AA');
    });

    testWidgets('a disabled button looks disabled and does not fire', (tester) async {
      await tester.pumpWidget(_host(
        const SgartButton(label: 'Abschließen', onPressed: null),
      ));

      expect(_buttonSurface(tester).color, isNot(SgartColors.light().primary),
          reason: 'a disabled button must not look fully enabled');
      expect(_labelColor(tester, 'Abschließen'), isNot(SgartColors.light().onPrimary));

      await tester.tap(find.byType(SgartButton));
      await tester.pump();
      expect(tester.takeException(), isNull);
    });

    testWidgets('renders in dark mode from the dark tokens', (tester) async {
      await tester.pumpWidget(_host(
        SgartButton(label: 'Dunkel', onPressed: () {}),
        theme: SgartTheme.dark(),
      ));

      expect(_buttonSurface(tester).color, SgartColors.dark().primary);
      expect(_labelColor(tester, 'Dunkel'), SgartColors.dark().onPrimary);
    });

    testWidgets('announces the label once, as a button', (tester) async {
      final semantics = tester.ensureSemantics();
      await tester.pumpWidget(_host(
        SgartButton(label: 'Einkauf starten', onPressed: () {}),
      ));

      final node = tester.getSemantics(find.byType(SgartButton));
      expect(
        node,
        isSemantics(label: 'Einkauf starten', isButton: true, isEnabled: true),
      );
      // The inner Text must not contribute a second node with the same label.
      expect(find.bySemanticsLabel('Einkauf starten'), findsOneWidget);

      semantics.dispose();
    });
  });
}

void doNothing() {}
