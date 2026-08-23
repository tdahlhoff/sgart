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

    test('householdNameRequiredResolvesToItsOwnLocalizedCopyNotTheGenericFallback', () {
      final resolved = localizedMessageForErrorCode(localizations, 'household.nameRequired');

      expect(resolved, localizations.householdsCreateNameRequiredError);
      expect(resolved, isNot(localizations.errorGenericFallback));
    });
  });
}
