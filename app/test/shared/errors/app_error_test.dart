import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/shared/errors/app_error.dart';

void main() {
  group('AppError', () {
    test('exposesCodeMessageAndDetailsMirroringTheSharedErrorDescriptorShape', () {
      const error = AppError(
        code: 'household.not_found',
        message: 'Household 7f3c not found in repository',
        details: {'householdId': '7f3c'},
      );

      expect(error.code, 'household.not_found');
      expect(error.message, 'Household 7f3c not found in repository');
      expect(error.details, {'householdId': '7f3c'});
    });

    test('defaultsDetailsToAnEmptyMapWhenOmitted', () {
      const error = AppError(code: 'unknown', message: 'debug only');

      expect(error.details, isEmpty);
    });

    test('twoErrorsWithEqualCodeMessageAndDetailsAreEqual', () {
      const first = AppError(
        code: 'household.not_found',
        message: 'debug only',
        details: {'householdId': '7f3c'},
      );
      const second = AppError(
        code: 'household.not_found',
        message: 'debug only',
        details: {'householdId': '7f3c'},
      );

      expect(first, second);
      expect(first.hashCode, second.hashCode);
    });

    test('errorsWithDifferentDetailsAreNotEqual', () {
      const base = AppError(code: 'household.not_found', message: 'debug only');
      const withDetails = AppError(
        code: 'household.not_found',
        message: 'debug only',
        details: {'householdId': '7f3c'},
      );

      expect(base, isNot(withDetails));
    });
  });
}
