import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';

/// Outcome of confirming a recovery code (`POST /api/v1/account/recovery/email/confirm`): either
/// the device was rebound onto the one matching account (`204`), or the mailbox is bound to
/// several accounts and the person must pick one (`200 {candidates}`).
sealed class RecoveryConfirmation {
  const RecoveryConfirmation();

  /// An empty body is the `204` rebind; anything else must carry a non-empty candidate list. A
  /// malformed shape fails fast with a mapped [AppException] — never silently reads as a rebind.
  factory RecoveryConfirmation.fromJson(Map<String, dynamic> json) {
    if (json.isEmpty) return const RecoveryConfirmationRebound();
    final candidates = json['candidates'];
    if (candidates is! List || candidates.isEmpty) throw malformedRecoveryConfirmation();
    return RecoveryConfirmationChooseAccount(
      candidates.map((candidate) => RecoveryCandidate.fromJson(_asJsonObject(candidate))).toList(),
    );
  }
}

AppException malformedRecoveryConfirmation() => const AppException(
  AppError(
    code: 'account.malformedResponse',
    message: 'POST /api/v1/account/recovery/email/confirm returned an unexpected shape',
  ),
);

Map<String, dynamic> _asJsonObject(Object? value) {
  if (value is! Map<String, dynamic>) throw malformedRecoveryConfirmation();
  return value;
}

class RecoveryConfirmationRebound extends RecoveryConfirmation {
  const RecoveryConfirmationRebound();
}

/// Candidates arrive sorted by household count, highest first (server-side), so the first entry is
/// the one the picker preselects.
class RecoveryConfirmationChooseAccount extends RecoveryConfirmation {
  const RecoveryConfirmationChooseAccount(this.candidates);

  final List<RecoveryCandidate> candidates;
}

/// One account the confirmed mailbox is bound to, described only by what the person recognizes:
/// its households and the nickname they use there. [accountId] is the pseudonymous account id the
/// second confirm call sends back.
class RecoveryCandidate {
  const RecoveryCandidate({required this.accountId, required this.households});

  factory RecoveryCandidate.fromJson(Map<String, dynamic> json) {
    final accountId = json['accountId'];
    if (accountId is! String) throw malformedRecoveryConfirmation();
    final households = json['households'];
    return RecoveryCandidate(
      accountId: accountId,
      households: households is List
          ? households.map((household) => RecoveryCandidateHousehold.fromJson(_asJsonObject(household))).toList()
          : const [],
    );
  }

  final String accountId;
  final List<RecoveryCandidateHousehold> households;
}

/// [householdName] is empty while the household's name is not yet projected; [nickname] is empty
/// when the account has not chosen one there.
class RecoveryCandidateHousehold {
  const RecoveryCandidateHousehold({required this.householdName, required this.nickname});

  factory RecoveryCandidateHousehold.fromJson(Map<String, dynamic> json) => RecoveryCandidateHousehold(
    householdName: json['householdName'] as String? ?? '',
    nickname: json['nickname'] as String? ?? '',
  );

  final String householdName;
  final String nickname;
}
