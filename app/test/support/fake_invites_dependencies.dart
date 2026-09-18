import 'package:sgart/features/invites/data/invites_api.dart';
import 'package:sgart/features/invites/data/pending_invite.dart';

/// Test double for [InvitesApi] — no real network in tests (CLAUDE.md §6). Mirrors `FakeStoresApi`.
class FakeInvitesApi implements InvitesApi {
  List<PendingInvite> pendingInvitesToReturn = const [];
  Object? listPendingInvitesError;
  Object? createInviteError;
  Object? acceptInviteError;

  String? lastCreatedInviteId;
  final List<String> createCommandIds = [];
  final List<String> createInviteIds = [];
  int createCallCount = 0;

  String? lastAcceptedHouseholdId;
  String? lastAcceptedInviteId;
  final List<String> acceptCommandIds = [];
  int acceptCallCount = 0;

  Object? revokeInviteError;
  String? lastRevokedHouseholdId;
  String? lastRevokedInviteId;
  final List<String> revokeCommandIds = [];
  int revokeCallCount = 0;

  @override
  Future<List<PendingInvite>> listPendingInvites(String householdId) async {
    if (listPendingInvitesError != null) throw listPendingInvitesError!;
    return pendingInvitesToReturn;
  }

  @override
  Future<void> createInvite(String householdId, {required String inviteId, required String commandId}) async {
    lastCreatedInviteId = inviteId;
    createCommandIds.add(commandId);
    createInviteIds.add(inviteId);
    createCallCount++;
    if (createInviteError != null) throw createInviteError!;
  }

  @override
  Future<void> acceptInvite(String householdId, {required String inviteId, required String commandId}) async {
    lastAcceptedHouseholdId = householdId;
    lastAcceptedInviteId = inviteId;
    acceptCommandIds.add(commandId);
    acceptCallCount++;
    if (acceptInviteError != null) throw acceptInviteError!;
  }

  @override
  Future<void> revokeInvite(String householdId, {required String inviteId, required String commandId}) async {
    lastRevokedHouseholdId = householdId;
    lastRevokedInviteId = inviteId;
    revokeCommandIds.add(commandId);
    revokeCallCount++;
    if (revokeInviteError != null) throw revokeInviteError!;
  }
}
