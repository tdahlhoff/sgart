import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/caller_identity.dart';
import 'package:sgart/shared/http/app_exception.dart';

void main() {
  group('CallerIdentity.fromJson', () {
    test('readsTheIdentityFromAWellFormedResponse', () {
      final identity = CallerIdentity.fromJson(const {
        'keycloakUserId': 'sub-1',
        'displayName': 'Anna Testperson',
        'email': 'anna@example.test',
      });

      expect(identity.keycloakUserId, 'sub-1');
      expect(identity.displayName, 'Anna Testperson');
      expect(identity.email, 'anna@example.test');
    });

    test('throwsAMappedAppExceptionWhenAFieldIsMissingInsteadOfARawTypeError', () {
      expect(
        () => CallerIdentity.fromJson(const {'keycloakUserId': 'sub-1', 'displayName': 'Anna'}),
        throwsA(isA<AppException>().having((e) => e.error.code, 'code', 'identity.malformedResponse')),
      );
    });

    test('throwsAMappedAppExceptionWhenAFieldIsNull', () {
      expect(
        () => CallerIdentity.fromJson(const {
          'keycloakUserId': 'sub-1',
          'displayName': null,
          'email': 'anna@example.test',
        }),
        throwsA(isA<AppException>().having((e) => e.error.code, 'code', 'identity.malformedResponse')),
      );
    });
  });
}
