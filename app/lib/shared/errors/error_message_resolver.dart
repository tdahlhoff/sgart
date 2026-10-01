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
    'item.nameRequired' => localizations.itemNameRequiredError,
    'item.nameTooLong' => localizations.itemNameTooLongError,
    'item.noteTooLong' => localizations.itemNoteTooLongError,
    'item.quantityRequired' => localizations.itemQuantityRequiredError,
    'item.quantityInvalid' => localizations.itemQuantityInvalidError,
    'item.duplicate' => localizations.itemDuplicateError,
    'item.notFound' => localizations.itemNotFoundError,
    'item.changeNotPermitted' => localizations.itemChangeNotPermittedError,
    'item.notDuringTrip' => localizations.itemNotDuringTripError,
    'item.transferInProgress' => localizations.itemTransferInProgressError,
    'list.moveTargetNotOpen' => localizations.listsMoveTargetNotOpenError,
    'list.moveTargetSameAsSource' => localizations.listsMoveTargetSameAsSourceError,
    'list.moveMergeRemoveFailed' => localizations.listsMoveMergeRemoveFailedError,
    'invite.invalidLink' => localizations.householdsAwaitInviteInvalidLinkError,
    // The household's invite code is single and reusable (Story 8.4, F7): a 404 always means the
    // code is unknown or was replaced by a newer one — there is no separate expired/already-used
    // outcome any more.
    'invite.notFound' => localizations.householdsAwaitInviteNotFoundError,
    'governance.notPermitted' => localizations.membersGovernanceNotPermittedError,
    'membership.lastAdmin' => localizations.membersLastAdminError,
    'membership.mappingConflict' => localizations.membershipMappingConflictError,
    'account.recoveryRebindFailed' => localizations.accountRecoveryRebindFailedError,
    'auth.unauthorized' => localizations.authSessionExpiredError,
    'auth.invalidRecoveryToken' => localizations.recoveryTokenRestoreInvalidTokenError,
    'account.recoveryEmailRequired' => localizations.accountRecoveryEmailRequiredError,
    'account.recoveryEmailInvalid' => localizations.accountRecoveryEmailInvalidError,
    'account.recoveryCodeInvalid' => localizations.accountRecoveryCodeInvalidError,
    'account.recoveryCodeRateLimited' => localizations.accountRecoveryCodeRateLimitedError,
    'consent.required' => localizations.consentRequiredError,
    'nickname.required' => localizations.nicknameRequiredError,
    'nickname.tooLong' => localizations.nicknameTooLongError,
    _ => localizations.errorGenericFallback,
  };
}
