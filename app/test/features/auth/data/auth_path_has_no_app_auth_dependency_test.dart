import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// Regression guard for Story 7.1, AC6: `flutter_appauth` and its OIDC client are gone from the
/// auth path — the browserless Direct-Grant flow (`DirectGrantOidcClient`) is the only sign-in
/// transport now. A future accidental re-add (e.g. a merge, or copy-pasted example code) trips
/// this test rather than silently reintroducing a browser dependency.
void main() {
  test('authPath_hasNoAppAuthDependency', () {
    final pubspec = File('pubspec.yaml').readAsStringSync();
    expect(pubspec, isNot(contains('flutter_appauth')));

    expect(File('lib/features/auth/data/app_auth_oidc_client.dart').existsSync(), isFalse);
  });
}
