import 'package:sgart/features/invites/data/invites_api.dart';
import 'package:sgart/features/invites/data/pending_invite.dart';

/// Test double for [InvitesApi] — no real network in tests (CLAUDE.md §6). Mirrors `FakeStoresApi`.
class FakeInvitesApi implements InvitesApi {
  List<PendingInvite> pendingInvitesToReturn = const [];
  Object? listPendingInvitesError;
  Object? sendInviteError;

  String? lastSentEmail;
  String? lastSentInviteId;
  final List<String> sendCommandIds = [];
  final List<String> sendInviteIds = [];
  int sendCallCount = 0;

  @override
  Future<List<PendingInvite>> listPendingInvites(String householdId) async {
    if (listPendingInvitesError != null) throw listPendingInvitesError!;
    return pendingInvitesToReturn;
  }

  @override
  Future<void> sendInvite(
    String householdId, {
    required String inviteId,
    required String email,
    required String commandId,
  }) async {
    lastSentEmail = email;
    lastSentInviteId = inviteId;
    sendCommandIds.add(commandId);
    sendInviteIds.add(inviteId);
    sendCallCount++;
    if (sendInviteError != null) throw sendInviteError!;
  }
}
