/// Outcome of confirming a recovery code (`POST /api/v1/account/recovery/email/confirm`): either
/// the device was rebound onto the one matching account (`204`), or the mailbox is bound to
/// several accounts and the person must pick one (`200 {candidates}`).
sealed class RecoveryConfirmation {
  const RecoveryConfirmation();

  factory RecoveryConfirmation.fromJson(Map<String, dynamic> json) {
    final candidates = json['candidates'];
    if (candidates is! List) return const RecoveryConfirmationRebound();
    return RecoveryConfirmationChooseAccount(
      candidates.map((candidate) => RecoveryCandidate.fromJson(candidate as Map<String, dynamic>)).toList(),
    );
  }
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
    final households = json['households'] as List<dynamic>? ?? const [];
    return RecoveryCandidate(
      accountId: json['accountId'] as String,
      households: households
          .map((household) => RecoveryCandidateHousehold.fromJson(household as Map<String, dynamic>))
          .toList(),
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
