import 'package:sgart/features/auth/data/account_email_api.dart';
import 'package:sgart/features/auth/data/recovery_confirmation.dart';

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
  Object? fetchStatusErrorToThrow;

  /// The masked hint [fetchStatus] reports; `null` means no confirmed recovery email.
  String? addressHintToReturn;

  /// What [confirmRecovery] answers; defaults to a plain rebound.
  RecoveryConfirmation confirmationToReturn = const RecoveryConfirmationRebound();

  final List<String> attachedEmails = [];
  final List<String> confirmedCodes = [];
  int detachCallCount = 0;
  final List<String> requestedRecoveryEmails = [];
  final List<(String email, String code)> confirmedRecoveries = [];
  final List<String?> confirmedRecoveryAccountIds = [];
  int fetchStatusCallCount = 0;

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
  Future<String?> fetchStatus() async {
    fetchStatusCallCount++;
    if (fetchStatusErrorToThrow != null) throw fetchStatusErrorToThrow!;
    return addressHintToReturn;
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
  Future<RecoveryConfirmation> confirmRecovery(String email, String code, {String? accountId}) async {
    confirmedRecoveries.add((email, code));
    confirmedRecoveryAccountIds.add(accountId);
    if (confirmRecoveryErrorToThrow != null) throw confirmRecoveryErrorToThrow!;
    return confirmationToReturn;
  }
}
