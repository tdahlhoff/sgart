import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'features/home/presentation/home_page.dart';
import 'theme/sgart_theme.dart';

/// Entry point of the SGART Flutter client.
///
/// The app is organised feature-first (`lib/features/<feature>/…`) with a BLoC/Cubit per screen,
/// and renders through the shared design system (`lib/theme`). It follows the OS light/dark
/// setting by default; an explicit override remains possible via [ThemeMode].
void main() {
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
      home: const HomePage(),
    );
  }
}
