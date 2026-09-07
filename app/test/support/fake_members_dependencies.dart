import 'package:sgart/features/members/data/member_view.dart';
import 'package:sgart/features/members/data/members_api.dart';

/// Test double for [MembersApi] — no real network in tests (CLAUDE.md §6). Mirrors `FakeInvitesApi`.
class FakeMembersApi implements MembersApi {
  List<MemberView> membersToReturn = const [];
  Object? listMembersError;
  Object? leaveError;
  Object? removeMemberError;
  Object? promoteError;
  Object? demoteError;

  final List<String> leaveCommandIds = [];
  int leaveCallCount = 0;

  String? lastRemovedMemberId;
  final List<String> removeCommandIds = [];
  int removeCallCount = 0;

  String? lastPromotedMemberId;
  final List<String> promoteCommandIds = [];
  int promoteCallCount = 0;

  String? lastDemotedMemberId;
  final List<String> demoteCommandIds = [];
  int demoteCallCount = 0;

  @override
  Future<List<MemberView>> listMembers(String householdId) async {
    if (listMembersError != null) throw listMembersError!;
    return membersToReturn;
  }

  @override
  Future<void> leave(String householdId, {required String commandId}) async {
    leaveCommandIds.add(commandId);
    leaveCallCount++;
    if (leaveError != null) throw leaveError!;
  }

  @override
  Future<void> removeMember(String householdId, String memberId, {required String commandId}) async {
    lastRemovedMemberId = memberId;
    removeCommandIds.add(commandId);
    removeCallCount++;
    if (removeMemberError != null) throw removeMemberError!;
  }

  @override
  Future<void> promote(String householdId, String memberId, {required String commandId}) async {
    lastPromotedMemberId = memberId;
    promoteCommandIds.add(commandId);
    promoteCallCount++;
    if (promoteError != null) throw promoteError!;
  }

  @override
  Future<void> demote(String householdId, String memberId, {required String commandId}) async {
    lastDemotedMemberId = memberId;
    demoteCommandIds.add(commandId);
    demoteCallCount++;
    if (demoteError != null) throw demoteError!;
  }
}
