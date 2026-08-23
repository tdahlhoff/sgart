import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/date_symbol_data_local.dart';

import 'features/home/presentation/home_page.dart';
import 'l10n/gen/app_localizations.dart';
import 'theme/sgart_theme.dart';

/// Entry point of the SGART Flutter client.
///
/// The app is organised feature-first (`lib/features/<feature>/…`) with a BLoC/Cubit per screen,
/// and renders through the shared design system (`lib/theme`). It follows the OS light/dark
/// setting by default; an explicit override remains possible via [ThemeMode].
void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // `intl`'s DateFormat needs its locale data loaded before first use (see DateFormatter).
  await initializeDateFormatting('de_DE');
  registerBundledFontLicenses();
  runApp(const SgartApp());
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
    return MaterialApp(
      title: 'SGART',
      theme: SgartTheme.light(),
      darkTheme: SgartTheme.dark(),
      themeMode: ThemeMode.system,
      // Device-default locale, falling back to de-DE when the device locale is unsupported
      // (Flutter's default resolution falls back to the first entry of `supportedLocales`).
      // In-app locale selection is out of scope here — Story 1.10.
      localizationsDelegates: const [
        AppLocalizations.delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      supportedLocales: AppLocalizations.supportedLocales,
      home: const HomePage(),
    );
  }
}
