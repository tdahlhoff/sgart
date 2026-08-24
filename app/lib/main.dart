import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/date_symbol_data_local.dart';

import 'features/auth/presentation/auth_gate.dart';
import 'features/settings/data/locale_preference_store.dart';
import 'features/settings/presentation/locale_cubit.dart';
import 'features/settings/presentation/locale_state.dart';
import 'features/settings/supported_locales.dart';
import 'l10n/gen/app_localizations.dart';
import 'theme/sgart_theme.dart';

/// Entry point of the SGART Flutter client.
///
/// The app is organised feature-first (`lib/features/<feature>/…`) with a BLoC/Cubit per screen,
/// and renders through the shared design system (`lib/theme`). It follows the OS light/dark
/// setting by default; an explicit override remains possible via [ThemeMode]. Language & region
/// follow the device by default, overridable per user on the „Sprache & Region" screen (Story 1.10).
void main() async {
  // A build/framework error before this handler is installed shows the raw red error screen and
  // is captured nowhere — install it before anything else so the auth boot (Story 1.4) and every
  // screen after it is covered.
  FlutterError.onError = FlutterError.presentError;
  runZonedGuarded(
    () async {
      WidgetsFlutterBinding.ensureInitialized();
      // `intl`'s DateFormat needs each locale's symbols loaded before first use (see DateFormatter).
      // Cover every region a member can pick, or `DateFormat('…','de_CH')` throws LocaleDataException.
      for (final localeName in const ['de_DE', 'de_AT', 'de_CH']) {
        await initializeDateFormatting(localeName);
      }
      registerBundledFontLicenses();
      runApp(const SgartApp());
    },
    (error, stackTrace) {
      FlutterError.reportError(FlutterErrorDetails(exception: error, stack: stackTrace));
    },
  );
}

/// Declares the licence of every bundled font asset so it appears in `showLicensePage()`.
/// The SIL Open Font Licence requires the licence text to travel with the font wherever the
/// font is redistributed — shipping the file in the repository alone does not satisfy that.
void registerBundledFontLicenses() {
  LicenseRegistry.addLicense(() async* {
    final license = await rootBundle.loadString('assets/fonts/OFL.txt');
    yield LicenseEntryWithLineBreaks(const ['Inter'], license);
  });
}

class SgartApp extends StatelessWidget {
  const SgartApp({super.key});

  @override
  Widget build(BuildContext context) {
    // Held above MaterialApp so it can drive `MaterialApp.locale`; the auth-lifecycle bridge inside
    // AuthGate loads/clears the per-user preference (Story 1.10, provider-tree note).
    return BlocProvider(
      create: (_) => LocaleCubit(const SharedPreferencesLocalePreferenceStore()),
      child: BlocBuilder<LocaleCubit, LocaleState>(
        builder: (context, localeState) {
          return MaterialApp(
            title: 'SGART',
            theme: SgartTheme.light(),
            darkTheme: SgartTheme.dark(),
            themeMode: ThemeMode.system,
            // `null` follows the device locale (resolved below); an explicit choice pins a region.
            locale: localeState.effectiveLocale,
            localizationsDelegates: const [
              AppLocalizations.delegate,
              GlobalMaterialLocalizations.delegate,
              GlobalWidgetsLocalizations.delegate,
              GlobalCupertinoLocalizations.delegate,
            ],
            supportedLocales: supportedLocales,
            localeResolutionCallback: resolveSupportedLocale,
            // The sign-in gate is the app's single entry path (Story 1.4) — no app shell or routing
            // yet (Story 1.6). Kept `const` so a locale rebuild does not recreate AuthGate/AuthCubit.
            home: const AuthGate(),
          );
        },
      ),
    );
  }
}
