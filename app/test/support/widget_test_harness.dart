import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:sgart/l10n/gen/app_localizations.dart';
import 'package:sgart/theme/sgart_theme.dart';

/// Wraps [child] in a [MaterialApp] with the app's theme and localization delegates, so widget
/// tests exercise `AppLocalizations.of` the same way the real app does.
Widget wrapForTesting(Widget child, {ThemeData? theme, Locale? locale}) {
  return MaterialApp(
    theme: theme ?? SgartTheme.light(),
    locale: locale,
    localizationsDelegates: const [
      AppLocalizations.delegate,
      GlobalMaterialLocalizations.delegate,
      GlobalWidgetsLocalizations.delegate,
      GlobalCupertinoLocalizations.delegate,
    ],
    supportedLocales: AppLocalizations.supportedLocales,
    home: child,
  );
}
