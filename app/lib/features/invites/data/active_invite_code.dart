import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';

/// The household's single active invite code as seen by the caller (Story 8.4) — the id to share
/// (code/link, built via [InviteLink]) and whether this caller (an Admin) may replace it. No email,
/// no status, no list (the growing pending-invites model is retired).
class ActiveInviteCode {
  const ActiveInviteCode({required this.inviteId, required this.canReplace});

  final String inviteId;
  final bool canReplace;

  /// Fails fast with a mapped [AppException] rather than a raw `TypeError` when the response is
  /// missing a field or has an unexpected shape, so callers resolve it through [AppError.code].
  factory ActiveInviteCode.fromJson(Map<String, dynamic> json) {
    final inviteId = json['inviteId'];
    final canReplace = json['canReplace'];
    if (inviteId is! String || canReplace is! bool) {
      throw const AppException(AppError(
        code: 'invites.malformedResponse',
        message: 'GET invite-code returned an unexpected shape',
      ));
    }
    return ActiveInviteCode(inviteId: inviteId, canReplace: canReplace);
  }

  @override
  bool operator ==(Object other) =>
      other is ActiveInviteCode && other.inviteId == inviteId && other.canReplace == canReplace;

  @override
  int get hashCode => Object.hash(inviteId, canReplace);
}
