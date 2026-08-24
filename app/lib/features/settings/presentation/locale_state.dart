import 'package:flutter/widgets.dart';

/// The active locale selection held by [LocaleCubit] and fed to `MaterialApp.locale`.
///
/// A sealed choice: [SystemLocale] follows the device (arch-spine: device-default → override →
/// `de-DE` fallback, realized by returning `null` so Flutter resolves the device locale itself),
/// while [ExplicitLocale] pins a specific region variant chosen by the member. Only the canonical
/// locale *tag* is ever persisted (see [persistableTag]); no locale-formatted value is stored
/// (arch-spine §Dates & formatting).
sealed class LocaleState {
  const LocaleState();

  /// Rebuilds a state from a previously stored tag (`null`/absent → [SystemLocale]).
  factory LocaleState.fromStoredTag(String? tag) {
    if (tag == null) {
      return const SystemLocale();
    }
    return ExplicitLocale(_localeFromTag(tag));
  }

  /// The locale to give `MaterialApp.locale`: `null` for [SystemLocale] so Flutter follows the
  /// device and applies the `de-DE` fallback, or the pinned [Locale] for [ExplicitLocale].
  Locale? get effectiveLocale;

  /// The canonical tag to persist (e.g. `de-CH`), or `null` when nothing should be stored because
  /// the member follows the device default.
  String? get persistableTag;
}

/// Follow the device locale — no explicit override, nothing persisted.
final class SystemLocale extends LocaleState {
  const SystemLocale();

  @override
  Locale? get effectiveLocale => null;

  @override
  String? get persistableTag => null;

  @override
  bool operator ==(Object other) => other is SystemLocale;

  @override
  int get hashCode => (SystemLocale).hashCode;
}

/// Pin a specific locale (a German region variant in this MVP: `de-DE` / `de-AT` / `de-CH`).
final class ExplicitLocale extends LocaleState {
  const ExplicitLocale(this.locale);

  final Locale locale;

  @override
  Locale? get effectiveLocale => locale;

  @override
  String? get persistableTag => locale.toLanguageTag();

  @override
  bool operator ==(Object other) => other is ExplicitLocale && other.locale == locale;

  @override
  int get hashCode => locale.hashCode;
}

/// Parses a canonical BCP-47 tag (`language` or `language-REGION`, e.g. `de-CH`) into a [Locale].
/// Kept minimal on purpose: the MVP only ever stores `de`, `de-DE`, `de-AT`, `de-CH`.
Locale _localeFromTag(String tag) {
  final parts = tag.split('-');
  if (parts.length >= 2 && parts[1].isNotEmpty) {
    return Locale(parts[0], parts[1]);
  }
  return Locale(parts[0]);
}
