import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/main.dart';
import 'package:sgart/theme/sgart_theme.dart';
import 'package:sgart/theme/tokens/sgart_colors.dart';

void main() {
  group('SgartApp theme resolution (AC3)', () {
    testWidgets('offers both themes and follows the OS setting', (tester) async {
      await tester.pumpWidget(const SgartApp());

      final app = tester.widget<MaterialApp>(find.byType(MaterialApp));
      expect(app.themeMode, ThemeMode.system);
      expect(app.theme, isNotNull);
      expect(app.darkTheme, isNotNull);
      expect(app.theme!.extension<SgartColors>(), isNotNull);
      expect(app.darkTheme!.extension<SgartColors>(), isNotNull);
      expect(app.theme!.scaffoldBackgroundColor,
          isNot(app.darkTheme!.scaffoldBackgroundColor));
    });

    testWidgets('renders the light theme when the OS is light', (tester) async {
      tester.platformDispatcher.platformBrightnessTestValue = Brightness.light;
      addTearDown(tester.platformDispatcher.clearPlatformBrightnessTestValue);

      await tester.pumpWidget(const SgartApp());

      expect(Theme.of(tester.element(find.byType(Scaffold))).brightness, Brightness.light);
      expect(Theme.of(tester.element(find.byType(Scaffold))).scaffoldBackgroundColor,
          SgartTheme.light().scaffoldBackgroundColor);
    });

    testWidgets('renders the dark theme when the OS is dark', (tester) async {
      tester.platformDispatcher.platformBrightnessTestValue = Brightness.dark;
      addTearDown(tester.platformDispatcher.clearPlatformBrightnessTestValue);

      await tester.pumpWidget(const SgartApp());

      expect(Theme.of(tester.element(find.byType(Scaffold))).brightness, Brightness.dark);
      expect(Theme.of(tester.element(find.byType(Scaffold))).scaffoldBackgroundColor,
          SgartTheme.dark().scaffoldBackgroundColor);
    });
  });

  group('SgartApp localization (Story 1.3, AC1)', () {
    // The sign-in gate (Story 1.4) is the app's entry point, replacing the Story 1.1/1.3
    // placeholder home screen — these assertions moved from "Gerüst bereit" to the gate's copy.
    testWidgets('renders German copy sourced from AppLocalizations, defaulting to de-DE', (
      tester,
    ) async {
      await tester.pumpWidget(const SgartApp());
      await tester.pump();

      expect(find.text('Willkommen bei SGART'), findsOneWidget);

      final app = tester.widget<MaterialApp>(find.byType(MaterialApp));
      expect(app.supportedLocales, contains(const Locale('de')));
    });

    testWidgets('falls back to German when the device locale is unsupported', (tester) async {
      tester.platformDispatcher.localeTestValue = const Locale('fr');
      addTearDown(tester.platformDispatcher.clearLocaleTestValue);

      await tester.pumpWidget(const SgartApp());
      await tester.pump();

      expect(find.text('Willkommen bei SGART'), findsOneWidget);
    });
  });
}
