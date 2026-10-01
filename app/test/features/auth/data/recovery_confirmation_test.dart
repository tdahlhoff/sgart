import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/recovery_confirmation.dart';
import 'package:sgart/shared/http/app_exception.dart';

void main() {
  group('RecoveryConfirmation.fromJson', () {
    Matcher throwsMalformedResponse() =>
        throwsA(isA<AppException>().having((error) => error.error.code, 'code', 'account.malformedResponse'));

    test('anEmptyBodyMeansTheDeviceWasRebound', () {
      expect(RecoveryConfirmation.fromJson(const {}), isA<RecoveryConfirmationRebound>());
    });

    test('readsTheCandidatesWithTheirHouseholdsAndNicknames', () {
      final confirmation = RecoveryConfirmation.fromJson(const {
        'candidates': [
          {
            'accountId': 'account-one',
            'households': [
              {'householdName': 'Familie Beispiel', 'nickname': 'Anna'},
            ],
          },
        ],
      });

      final candidates = (confirmation as RecoveryConfirmationChooseAccount).candidates;
      expect(candidates.single.accountId, 'account-one');
      expect(candidates.single.households.single.householdName, 'Familie Beispiel');
      expect(candidates.single.households.single.nickname, 'Anna');
    });

    test('defaultsMissingHouseholdsAndMissingNamesToEmptyValues', () {
      final confirmation = RecoveryConfirmation.fromJson(const {
        'candidates': [
          {'accountId': 'account-one'},
          {
            'accountId': 'account-two',
            'households': [<String, dynamic>{}],
          },
        ],
      });

      final candidates = (confirmation as RecoveryConfirmationChooseAccount).candidates;
      expect(candidates.first.households, isEmpty);
      expect(candidates.last.households.single.householdName, '');
      expect(candidates.last.households.single.nickname, '');
    });

    test('aNonEmptyBodyWithoutCandidatesIsMalformedAndNotARebound', () {
      expect(() => RecoveryConfirmation.fromJson(const {'unexpected': true}), throwsMalformedResponse());
    });

    test('candidatesThatAreNotAListAreMalformed', () {
      expect(() => RecoveryConfirmation.fromJson(const {'candidates': 'account-one'}), throwsMalformedResponse());
    });

    test('anEmptyCandidateListIsMalformed', () {
      expect(() => RecoveryConfirmation.fromJson(const {'candidates': []}), throwsMalformedResponse());
    });

    test('aCandidateThatIsNotAMapIsMalformed', () {
      expect(
        () => RecoveryConfirmation.fromJson(const {
          'candidates': ['account-one'],
        }),
        throwsMalformedResponse(),
      );
    });

    test('aCandidateWithoutAStringAccountIdIsMalformed', () {
      expect(
        () => RecoveryConfirmation.fromJson(const {
          'candidates': [
            {'households': []},
          ],
        }),
        throwsMalformedResponse(),
      );
      expect(
        () => RecoveryConfirmation.fromJson(const {
          'candidates': [
            {'accountId': 42},
          ],
        }),
        throwsMalformedResponse(),
      );
    });

    test('aHouseholdThatIsNotAMapIsMalformed', () {
      expect(
        () => RecoveryConfirmation.fromJson(const {
          'candidates': [
            {
              'accountId': 'account-one',
              'households': ['Familie Beispiel'],
            },
          ],
        }),
        throwsMalformedResponse(),
      );
    });
  });
}
