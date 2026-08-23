import '../../l10n/gen/app_localizations.dart';

/// Maps an [AppError.code] to localized user-facing copy, falling back to a generic message for
/// any code this catalog does not (yet) recognise. Never returns [AppError.message] — that field
/// is log/debug only.
///
/// The catalog of known codes grows per feature story as endpoints are built; this story
/// establishes only the mechanism and the fallback, not an exhaustive code list.
String localizedMessageForErrorCode(AppLocalizations localizations, String code) {
  return localizations.errorGenericFallback;
}
