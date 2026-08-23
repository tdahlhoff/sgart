import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/home/presentation/home_page.dart';
import 'package:sgart/theme/sgart_theme.dart';

import '../../support/widget_test_harness.dart';

void main() {
  group('HomePage', () {
    testWidgets('renders and the BLoC updates the probe count on tap', (tester) async {
      // HomePage renders shared design-system components, which require the SgartColors
      // theme extension, and reads every string via AppLocalizations — both need the app's
      // theme + localization delegates (Story 1.2 / 1.3).
      await tester.pumpWidget(wrapForTesting(const HomePage()));

      expect(find.text('Gerüst bereit'), findsOneWidget);
      expect(find.byKey(const Key('probe-count')), findsOneWidget);
      expect(find.text('Proben: 0'), findsOneWidget);

      await tester.tap(find.byKey(const Key('probe-button')));
      await tester.pump();

      expect(find.text('Proben: 1'), findsOneWidget);
    });

    testWidgets('renders the live count with tabular figures', (tester) async {
      await tester.pumpWidget(wrapForTesting(const HomePage()));

      final count = tester.widget<Text>(find.byKey(const Key('probe-count')));
      expect(count.style!.fontFeatures, contains(const FontFeature.tabularFigures()));
    });

    testWidgets('renders in dark mode without needing a light theme', (tester) async {
      await tester.pumpWidget(wrapForTesting(const HomePage(), theme: SgartTheme.dark()));

      expect(tester.takeException(), isNull);
      expect(find.text('Gerüst bereit'), findsOneWidget);
    });

    testWidgets('reflows without overflowing on a short, narrow viewport', (tester) async {
      tester.view.physicalSize = const Size(320, 400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);

      await tester.pumpWidget(wrapForTesting(const HomePage()));

      expect(tester.takeException(), isNull);
    });

    testWidgets('renders every string through AppLocalizations, not a literal', (tester) async {
      await tester.pumpWidget(wrapForTesting(const HomePage()));

      expect(find.text('Gerüst bereit'), findsOneWidget);
      // StatusLabel visually uppercases its text (DESIGN §4b) — the localized value is "Admin".
      expect(find.text('ADMIN'), findsOneWidget);
      expect(find.text('Probe'), findsOneWidget);
      expect(find.textContaining('Beispielmenge'), findsOneWidget);
      expect(find.byKey(const Key('formatting-demo')), findsOneWidget);
    });

    testWidgets('falls back to de-DE when the device locale is unsupported', (tester) async {
      await tester.pumpWidget(wrapForTesting(const HomePage(), locale: const Locale('en')));

      expect(find.text('Gerüst bereit'), findsOneWidget);
    });
  });
}
