import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/settings/presentation/locale_cubit.dart';
import 'package:sgart/features/settings/presentation/locale_settings_page.dart';
import 'package:sgart/features/settings/presentation/locale_state.dart';
import 'package:sgart/features/settings/supported_locales.dart';
import 'package:sgart/l10n/gen/app_localizations.dart';

import '../../../support/fake_settings_dependencies.dart';

void main() {
  group('LocaleSettingsPage', () {
    late FakeLocalePreferenceStore store;
    late LocaleCubit localeCubit;

    // Exact separators intl 0.20.2 emits (probed, never eyeballed — Story 1.3 lesson):
    // de-DE dot group + comma decimal; de-AT non-breaking-space group; de-CH U+2019 group + dot.
    const germanNumber = '1.234,5';
    const austrianNumber = '1 234,5';
    const swissNumber = '1’234.5';

    setUp(() {
      store = FakeLocalePreferenceStore();
      localeCubit = LocaleCubit(store);
    });

    // Mirrors the app root: LocaleCubit above MaterialApp, its selection driving MaterialApp.locale,
    // so the preview genuinely reformats under the effective locale the way it does in production.
    Widget buildApp() => BlocProvider<LocaleCubit>.value(
          value: localeCubit,
          child: BlocBuilder<LocaleCubit, LocaleState>(
            builder: (context, state) => MaterialApp(
              locale: state.effectiveLocale,
              localizationsDelegates: const [
                AppLocalizations.delegate,
                GlobalMaterialLocalizations.delegate,
                GlobalWidgetsLocalizations.delegate,
                GlobalCupertinoLocalizations.delegate,
              ],
              supportedLocales: supportedLocales,
              localeResolutionCallback: resolveSupportedLocale,
              home: const LocaleSettingsPage(),
            ),
          ),
        );

    String previewNumber(WidgetTester tester) =>
        tester.widget<Text>(find.byKey(const Key('locale-preview-number'))).data!;

    testWidgets('rendersTheFourGermanRegionOptions', (tester) async {
      await tester.pumpWidget(buildApp());

      expect(find.text('Systemstandard (Gerät)'), findsOneWidget);
      expect(find.text('Deutsch (Deutschland)'), findsOneWidget);
      expect(find.text('Deutsch (Österreich)'), findsOneWidget);
      expect(find.text('Deutsch (Schweiz)'), findsOneWidget);
    });

    testWidgets('marksTheCurrentlyEffectiveOptionAsSelected', (tester) async {
      await localeCubit.applyForUser('user-1');
      await localeCubit.select(const ExplicitLocale(Locale('de', 'CH')));
      await tester.pumpWidget(buildApp());

      final swissTile = tester.widget<ListTile>(
        find.ancestor(of: find.text('Deutsch (Schweiz)'), matching: find.byType(ListTile)),
      );
      expect(swissTile.selected, isTrue);
      // Exactly one option is marked active.
      expect(find.byIcon(Icons.radio_button_checked), findsOneWidget);
    });

    testWidgets('selectingSwitzerlandFlipsThePreviewToSwissGroupingAndConfirms', (tester) async {
      // An unsupported device locale falls back to de-DE, so the pre-tap preview is German grouping.
      tester.platformDispatcher.localeTestValue = const Locale('en');
      addTearDown(tester.platformDispatcher.clearLocaleTestValue);
      await localeCubit.applyForUser('user-1');
      await tester.pumpWidget(buildApp());

      expect(previewNumber(tester), germanNumber);

      await tester.tap(find.text('Deutsch (Schweiz)'));
      await tester.pumpAndSettle();

      expect(previewNumber(tester), swissNumber);
      expect(find.byKey(const Key('locale-change-confirmation')), findsOneWidget);
      expect(store.storedTagFor('user-1'), 'de-CH');
    });

    testWidgets('tappingTheAlreadyActiveOptionShowsNoConfirmation', (tester) async {
      // Fresh state follows the device, so „Systemstandard" is already the active option.
      await tester.pumpWidget(buildApp());

      await tester.tap(find.text('Systemstandard (Gerät)'));
      await tester.pump();

      // A no-op selection must not flash the „aktualisiert" confirmation.
      expect(find.byKey(const Key('locale-change-confirmation')), findsNothing);
    });

    testWidgets('selectingAustriaUsesANonBreakingSpaceGroupingDistinctFromGermany', (tester) async {
      await localeCubit.applyForUser('user-1');
      await tester.pumpWidget(buildApp());

      await tester.tap(find.text('Deutsch (Österreich)'));
      await tester.pumpAndSettle();

      expect(previewNumber(tester), austrianNumber);
      expect(austrianNumber, isNot(germanNumber));
    });
  });
}
