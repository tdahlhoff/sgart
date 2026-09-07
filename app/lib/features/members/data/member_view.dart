import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';

/// A household member as seen by the caller (Story 4.3, AC8) — id, role, and whether this row is
/// the caller themselves. Deliberately carries **no email/name** (AD-6, decision 5): the roster
/// shown to household members is privacy-first by construction. Mirrors `PendingInvite`.
class MemberView {
  const MemberView({required this.memberId, required this.role, required this.isSelf});

  final String memberId;
  final String role;
  final bool isSelf;

  /// Fails fast with a mapped [AppException] rather than a raw `TypeError` when the response is
  /// missing a field or has an unexpected shape, so callers resolve it through [AppError.code].
  factory MemberView.fromJson(Map<String, dynamic> json) {
    final memberId = json['memberId'];
    final role = json['role'];
    final isSelf = json['isSelf'];
    if (memberId is! String || role is! String || isSelf is! bool) {
      throw const AppException(AppError(
        code: 'members.malformedResponse',
        message: 'GET members returned an unexpected shape',
      ));
    }
    return MemberView(memberId: memberId, role: role, isSelf: isSelf);
  }

  @override
  bool operator ==(Object other) =>
      other is MemberView && other.memberId == memberId && other.role == role && other.isSelf == isSelf;

  @override
  int get hashCode => Object.hash(memberId, role, isSelf);
}
