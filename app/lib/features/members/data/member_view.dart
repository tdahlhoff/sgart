import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';

/// A household member as seen by the caller (Story 4.3, AC8) — id, role, whether this row is the
/// caller themselves, and (Story 8.3) the resolved self-chosen [nickname]. Deliberately carries
/// **no Keycloak name/email** (AD-6, decision 5): [nickname] is the one documented AD-6 exception
/// (a freely-chosen, low-sensitivity in-household display name, AD-6 rev F) — `null` when that
/// member has not set one yet, never the raw credential/member id. Mirrors `PendingInvite`.
class MemberView {
  const MemberView({required this.memberId, required this.role, required this.isSelf, this.nickname});

  final String memberId;
  final String role;
  final bool isSelf;
  final String? nickname;

  /// Fails fast with a mapped [AppException] rather than a raw `TypeError` when the response is
  /// missing a field or has an unexpected shape, so callers resolve it through [AppError.code].
  factory MemberView.fromJson(Map<String, dynamic> json) {
    final memberId = json['memberId'];
    final role = json['role'];
    final isSelf = json['isSelf'];
    final nickname = json['nickname'];
    if (memberId is! String || role is! String || isSelf is! bool || (nickname != null && nickname is! String)) {
      throw const AppException(AppError(
        code: 'members.malformedResponse',
        message: 'GET members returned an unexpected shape',
      ));
    }
    return MemberView(memberId: memberId, role: role, isSelf: isSelf, nickname: nickname as String?);
  }

  @override
  bool operator ==(Object other) =>
      other is MemberView &&
      other.memberId == memberId &&
      other.role == role &&
      other.isSelf == isSelf &&
      other.nickname == nickname;

  @override
  int get hashCode => Object.hash(memberId, role, isSelf, nickname);
}
