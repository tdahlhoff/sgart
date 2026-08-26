import '../../l10n/gen/app_localizations.dart';

/// Maps an [AppError.code] to localized user-facing copy, falling back to a generic message for
/// any code this catalog does not (yet) recognise. Never returns [AppError.message] — that field
/// is log/debug only.
///
/// The catalog of known codes grows per feature story as endpoints are built; Story 1.3
/// established only the mechanism and the fallback; Story 1.6 adds the create-household code.
String localizedMessageForErrorCode(AppLocalizations localizations, String code) {
  return switch (code) {
    'household.nameRequired' => localizations.householdsCreateNameRequiredError,
    'household.nameTooLong' => localizations.householdsCreateNameTooLongError,
    'household.renameNotPermitted' => localizations.householdsRenameNotPermittedError,
    'store.nameRequired' => localizations.storesNameRequiredError,
    'store.nameTooLong' => localizations.storesNameTooLongError,
    'store.duplicateName' => localizations.storesDuplicateNameError,
    'list.nameRequired' => localizations.listsNameRequiredError,
    'list.nameTooLong' => localizations.listsNameTooLongError,
    'list.nameChangeNotPermitted' => localizations.listsNameChangeNotPermittedError,
    'list.notFound' => localizations.listsNotFoundError,
    // The client never sends a bad filter — this only guards against an unmapped 4xx surfacing
    // as the generic fallback, since the resolver must cover every server code (retro DoD).
    'command.listFilterInvalid' => localizations.errorGenericFallback,
    _ => localizations.errorGenericFallback,
  };
}
