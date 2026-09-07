import '../../../shared/http/authenticated_http_client.dart';
import 'member_view.dart';

/// The client's member-management source — calls the backend's member slice under a household
/// (`/api/v1/households/{householdId}/members`, Story 4.3). Mirrors `InvitesApi`.
abstract interface class MembersApi {
  /// Lists the household's members (AC8). No email/name in the response (AD-6).
  Future<List<MemberView>> listMembers(String householdId);

  /// Leaves the household (AC3, self-leave — no target id needed). [commandId] is the caller-minted
  /// idempotency key, reused across retries (AD-8). The last Admin surfaces `membership.lastAdmin`
  /// (409).
  Future<void> leave(String householdId, {required String commandId});

  /// Removes [memberId] from the household (AC4) — Admin-only. [commandId] is the caller-minted
  /// idempotency key, reused across retries (AD-8). A non-Admin caller surfaces
  /// `governance.notPermitted` (403); removing the last Admin surfaces `membership.lastAdmin` (409).
  Future<void> removeMember(String householdId, String memberId, {required String commandId});

  /// Promotes [memberId] to Admin (AC4) — Admin-only. [commandId] is the caller-minted idempotency
  /// key, reused across retries (AD-8).
  Future<void> promote(String householdId, String memberId, {required String commandId});

  /// Demotes [memberId] to Participant (AC4) — Admin-only. [commandId] is the caller-minted
  /// idempotency key, reused across retries (AD-8). Demoting the last Admin surfaces
  /// `membership.lastAdmin` (409).
  Future<void> demote(String householdId, String memberId, {required String commandId});
}

class HttpMembersApi implements MembersApi {
  const HttpMembersApi(this._client);

  final AuthenticatedHttpClient _client;

  @override
  Future<List<MemberView>> listMembers(String householdId) async {
    final json = await _client.getJsonList('/api/v1/households/$householdId/members');
    return json.map((entry) => MemberView.fromJson(entry as Map<String, dynamic>)).toList();
  }

  @override
  Future<void> leave(String householdId, {required String commandId}) {
    return _client.deleteJson('/api/v1/households/$householdId/members/me', {'commandId': commandId});
  }

  @override
  Future<void> removeMember(String householdId, String memberId, {required String commandId}) {
    return _client.deleteJson(
      '/api/v1/households/$householdId/members/$memberId',
      {'commandId': commandId},
    );
  }

  @override
  Future<void> promote(String householdId, String memberId, {required String commandId}) {
    return _client.postJson(
      '/api/v1/households/$householdId/members/$memberId/promote',
      {'commandId': commandId},
    );
  }

  @override
  Future<void> demote(String householdId, String memberId, {required String commandId}) {
    return _client.postJson(
      '/api/v1/households/$householdId/members/$memberId/demote',
      {'commandId': commandId},
    );
  }
}
