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
}
