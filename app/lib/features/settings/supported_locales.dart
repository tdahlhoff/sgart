import 'package:flutter/widgets.dart';

/// The region variants of German the app supports (Story 1.10). Overrides the generated single-`de`
/// list so the picker can pin a region; the generated `AppLocalizations` delegate still matches on
/// language code, so all three resolve the one German catalog — no per-region ARB files.
const List<Locale> supportedLocales = [
  Locale('de', 'DE'),
  Locale('de', 'AT'),
  Locale('de', 'CH'),
];

/// Resolves the locale Flutter should use for `MaterialApp` (arch-spine: device-default → override →
/// `de-DE` fallback). Preserves the region of any `de` locale so the formatter sees `de_CH`, not the
/// language-code match `de` Flutter would otherwise collapse it to; falls back to `de-DE` for an
/// unsupported locale. [supported] is unused — resolution is fixed to the German catalog — but kept to
/// match `MaterialApp.localeResolutionCallback`'s signature.
Locale resolveSupportedLocale(Locale? locale, Iterable<Locale> supported) {
  if (locale != null && locale.languageCode == 'de') {
    return locale;
  }
  return const Locale('de', 'DE');
}
