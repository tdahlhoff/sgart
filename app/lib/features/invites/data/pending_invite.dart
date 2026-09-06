import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';

/// A pending invite as seen by the caller (Story 4.1, AC6/AC7) — id, when, who invited, and status.
/// Deliberately carries **no email**: the read model holds none (AD-6), so the pending-invites list
/// shown to household members is privacy-first by construction. Mirrors `StoreSummary`.
class PendingInvite {
  const PendingInvite({
    required this.inviteId,
    required this.invitedAt,
    required this.invitedBy,
    required this.status,
  });

  final String inviteId;
  final String invitedAt;
  final String invitedBy;
  final String status;

  /// Fails fast with a mapped [AppException] rather than a raw `TypeError` when the response is
  /// missing a field or has an unexpected shape, so callers resolve it through [AppError.code].
  factory PendingInvite.fromJson(Map<String, dynamic> json) {
    final inviteId = json['inviteId'];
    final invitedAt = json['invitedAt'];
    final invitedBy = json['invitedBy'];
    final status = json['status'];
    if (inviteId is! String || invitedAt is! String || invitedBy is! String || status is! String) {
      throw const AppException(AppError(
        code: 'invites.malformedResponse',
        message: 'GET invites returned an unexpected shape',
      ));
    }
    return PendingInvite(inviteId: inviteId, invitedAt: invitedAt, invitedBy: invitedBy, status: status);
  }

  @override
  bool operator ==(Object other) =>
      other is PendingInvite &&
      other.inviteId == inviteId &&
      other.invitedAt == invitedAt &&
      other.invitedBy == invitedBy &&
      other.status == status;

  @override
  int get hashCode => Object.hash(inviteId, invitedAt, invitedBy, status);
}
