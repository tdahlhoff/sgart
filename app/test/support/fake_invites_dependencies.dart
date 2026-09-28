import 'package:sgart/features/invites/data/active_invite_code.dart';
import 'package:sgart/features/invites/data/invites_api.dart';

/// Test double for [InvitesApi] — no real network in tests (CLAUDE.md §6). Mirrors `FakeStoresApi`.
class FakeInvitesApi implements InvitesApi {
  String activeInviteIdToReturn = 'invite-1';
  bool canReplaceToReturn = false;
  Object? getActiveInviteCodeError;
  Object? acceptInviteError;

  int getActiveInviteCodeCallCount = 0;

  Object? replaceInviteCodeError;
  String? lastReplacedHouseholdId;
  String? lastReplacedNewInviteId;
  final List<String> replaceCommandIds = [];
  int replaceCallCount = 0;

  String? lastAcceptedHouseholdId;
  String? lastAcceptedInviteId;
  final List<String> acceptCommandIds = [];
  int acceptCallCount = 0;

  @override
  Future<ActiveInviteCode> getActiveInviteCode(String householdId) async {
    getActiveInviteCodeCallCount++;
    if (getActiveInviteCodeError != null) throw getActiveInviteCodeError!;
    return ActiveInviteCode(inviteId: activeInviteIdToReturn, canReplace: canReplaceToReturn);
  }

  @override
  Future<void> replaceInviteCode(String householdId, {required String newInviteId, required String commandId}) async {
    lastReplacedHouseholdId = householdId;
    lastReplacedNewInviteId = newInviteId;
    replaceCommandIds.add(commandId);
    replaceCallCount++;
    if (replaceInviteCodeError != null) throw replaceInviteCodeError!;
    activeInviteIdToReturn = newInviteId;
  }

  @override
  Future<void> acceptInvite(String householdId, {required String inviteId, required String commandId}) async {
    lastAcceptedHouseholdId = householdId;
    lastAcceptedInviteId = inviteId;
    acceptCommandIds.add(commandId);
    acceptCallCount++;
    if (acceptInviteError != null) throw acceptInviteError!;
  }
}
