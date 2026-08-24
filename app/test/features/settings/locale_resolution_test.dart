import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/settings/supported_locales.dart';
import 'package:sgart/l10n/gen/app_localizations.dart';

void main() {
  group('resolveSupportedLocale', () {
    test('preservesTheRegionOfADeLocaleSoTheFormatterSeesDeCh', () {
      expect(resolveSupportedLocale(const Locale('de', 'CH'), supportedLocales),
          const Locale('de', 'CH'));
    });

    test('keepsABareDeLocaleUnchanged', () {
      expect(resolveSupportedLocale(const Locale('de'), supportedLocales), const Locale('de'));
    });

    test('fallsBackToDeDeForAnUnsupportedLocale', () {
      expect(resolveSupportedLocale(const Locale('fr'), supportedLocales), const Locale('de', 'DE'));
    });

    test('fallsBackToDeDeWhenThereIsNoLocale', () {
      expect(resolveSupportedLocale(null, supportedLocales), const Locale('de', 'DE'));
    });
  });

  group('MaterialApp locale resolution', () {
    const delegates = <LocalizationsDelegate<Object>>[
      AppLocalizations.delegate,
      GlobalMaterialLocalizations.delegate,
      GlobalWidgetsLocalizations.delegate,
      GlobalCupertinoLocalizations.delegate,
    ];

    testWidgets('rendersTheGermanCatalogAndPreservesTheRegionUnderDeCh', (tester) async {
      late Locale resolvedLocale;
      await tester.pumpWidget(MaterialApp(
        locale: const Locale('de', 'CH'),
        localizationsDelegates: delegates,
        supportedLocales: supportedLocales,
        localeResolutionCallback: resolveSupportedLocale,
        home: Builder(builder: (context) {
          resolvedLocale = Localizations.localeOf(context);
          return Text(AppLocalizations.of(context).localeSettingsHeading);
        }),
      ));

      // Region kept (formatter will see de_CH), yet the one German catalog still resolves.
      expect(resolvedLocale, const Locale('de', 'CH'));
      expect(find.text('Sprache & Region'), findsOneWidget);
    });

    testWidgets('fallsBackToDeDeWhenTheDeviceLocaleIsUnsupported', (tester) async {
      tester.platformDispatcher.localeTestValue = const Locale('fr');
      addTearDown(tester.platformDispatcher.clearLocaleTestValue);
      late Locale resolvedLocale;
      await tester.pumpWidget(MaterialApp(
        localizationsDelegates: delegates,
        supportedLocales: supportedLocales,
        localeResolutionCallback: resolveSupportedLocale,
        home: Builder(builder: (context) {
          resolvedLocale = Localizations.localeOf(context);
          return const SizedBox();
        }),
      ));

      expect(resolvedLocale, const Locale('de', 'DE'));
    });
  });
}
