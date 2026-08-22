import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/home/presentation/home_page.dart';
import 'package:sgart/theme/sgart_theme.dart';

void main() {
  group('HomePage', () {
    testWidgets('renders and the BLoC updates the probe count on tap', (tester) async {
      // HomePage renders shared design-system components, which require the SgartColors
      // theme extension — pump it through SgartTheme (updated in Story 1.2).
      await tester.pumpWidget(MaterialApp(theme: SgartTheme.light(), home: const HomePage()));

      expect(find.text('Scaffold ready'), findsOneWidget);
      expect(find.byKey(const Key('probe-count')), findsOneWidget);
      expect(find.text('probes: 0'), findsOneWidget);

      await tester.tap(find.byKey(const Key('probe-button')));
      await tester.pump();

      expect(find.text('probes: 1'), findsOneWidget);
    });

    testWidgets('renders the live count with tabular figures', (tester) async {
      await tester.pumpWidget(MaterialApp(theme: SgartTheme.light(), home: const HomePage()));

      final count = tester.widget<Text>(find.byKey(const Key('probe-count')));
      expect(count.style!.fontFeatures, contains(const FontFeature.tabularFigures()));
    });

    testWidgets('renders in dark mode without needing a light theme', (tester) async {
      await tester.pumpWidget(MaterialApp(theme: SgartTheme.dark(), home: const HomePage()));

      expect(tester.takeException(), isNull);
      expect(find.text('Scaffold ready'), findsOneWidget);
    });

    testWidgets('reflows without overflowing on a short, narrow viewport', (tester) async {
      tester.view.physicalSize = const Size(320, 400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);

      await tester.pumpWidget(MaterialApp(theme: SgartTheme.light(), home: const HomePage()));

      expect(tester.takeException(), isNull);
    });
  });
}
