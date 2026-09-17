import 'package:sgart/features/auth/data/account_email_api.dart';

/// Test double for [AccountEmailApi] (Story 7.3) — no real HTTP client in tests (CLAUDE.md §6).
/// Records every call's arguments so a test can assert exactly what left the device (e.g.
/// `recoverByEmailPath_sendsNoLocalSecretItShouldNot`: only email/code strings, never key
/// material).
class FakeAccountEmailApi implements AccountEmailApi {
  Object? attachErrorToThrow;
  Object? confirmErrorToThrow;
  Object? detachErrorToThrow;
  Object? requestRecoveryCodeErrorToThrow;
  Object? confirmRecoveryErrorToThrow;

  final List<String> attachedEmails = [];
  final List<String> confirmedCodes = [];
  int detachCallCount = 0;
  final List<String> requestedRecoveryEmails = [];
  final List<(String email, String code)> confirmedRecoveries = [];

  @override
  Future<void> attach(String email) async {
    attachedEmails.add(email);
    if (attachErrorToThrow != null) throw attachErrorToThrow!;
  }

  @override
  Future<void> confirm(String code) async {
    confirmedCodes.add(code);
    if (confirmErrorToThrow != null) throw confirmErrorToThrow!;
  }

  @override
  Future<void> detach() async {
    detachCallCount++;
    if (detachErrorToThrow != null) throw detachErrorToThrow!;
  }

  @override
  Future<void> requestRecoveryCode(String email) async {
    requestedRecoveryEmails.add(email);
    if (requestRecoveryCodeErrorToThrow != null) throw requestRecoveryCodeErrorToThrow!;
  }

  @override
  Future<void> confirmRecovery(String email, String code) async {
    confirmedRecoveries.add((email, code));
    if (confirmRecoveryErrorToThrow != null) throw confirmRecoveryErrorToThrow!;
  }
}
