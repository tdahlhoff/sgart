import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/l10n/gen/app_localizations_de.dart';
import 'package:sgart/shared/errors/error_message_resolver.dart';

void main() {
  group('localizedMessageForErrorCode', () {
    final localizations = AppLocalizationsDe();

    test('unknownErrorCodeResolvesToTheGenericFallback', () {
      final resolved = localizedMessageForErrorCode(localizations, 'household.not_found');

      expect(resolved, localizations.errorGenericFallback);
    });

    test('resolvedMessageIsNeverTheRawDebugMessage', () {
      const rawDebugMessage = 'Household 7f3c not found in repository';

      final resolved = localizedMessageForErrorCode(localizations, 'household.not_found');

      expect(resolved, isNot(rawDebugMessage));
    });

    test('itemNotDuringTripResolvesToItsOwnLocalizedCopyNotTheGenericFallback', () {
      final resolved = localizedMessageForErrorCode(localizations, 'item.notDuringTrip');

      expect(resolved, localizations.itemNotDuringTripError);
      expect(resolved, isNot(localizations.errorGenericFallback));
    });

    test('itemTransferInProgressResolvesToItsOwnLocalizedCopyNotTheGenericFallback', () {
      final resolved = localizedMessageForErrorCode(localizations, 'item.transferInProgress');

      expect(resolved, localizations.itemTransferInProgressError);
      expect(resolved, isNot(localizations.errorGenericFallback));
    });

    test('householdNameRequiredResolvesToItsOwnLocalizedCopyNotTheGenericFallback', () {
      final resolved = localizedMessageForErrorCode(localizations, 'household.nameRequired');

      expect(resolved, localizations.householdsCreateNameRequiredError);
      expect(resolved, isNot(localizations.errorGenericFallback));
    });

    test('householdRenameNotPermittedResolvesToTheAdminOnlyCopy', () {
      final resolved = localizedMessageForErrorCode(localizations, 'household.renameNotPermitted');

      expect(resolved, localizations.householdsRenameNotPermittedError);
      expect(resolved, isNot(localizations.errorGenericFallback));
    });

    test('storeNameRequiredResolvesToItsOwnLocalizedCopy', () {
      final resolved = localizedMessageForErrorCode(localizations, 'store.nameRequired');

      expect(resolved, localizations.storesNameRequiredError);
      expect(resolved, isNot(localizations.errorGenericFallback));
    });

    test('storeNameTooLongResolvesToItsOwnLocalizedCopy', () {
      final resolved = localizedMessageForErrorCode(localizations, 'store.nameTooLong');

      expect(resolved, localizations.storesNameTooLongError);
      expect(resolved, isNot(localizations.errorGenericFallback));
    });

    test('storeDuplicateNameResolvesToItsOwnLocalizedCopy', () {
      final resolved = localizedMessageForErrorCode(localizations, 'store.duplicateName');

      expect(resolved, localizations.storesDuplicateNameError);
      expect(resolved, isNot(localizations.errorGenericFallback));
    });
  });
}
