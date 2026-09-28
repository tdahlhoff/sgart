import '../../../shared/http/authenticated_http_client.dart';
import 'active_invite_code.dart';

/// The client's invite source — calls the backend's single-invite-code slice under a household
/// (`/api/v1/households/{householdId}/invite-code`, Story 8.4) plus the accept endpoint, which
/// stays where Story 4.2 put it (`/api/v1/households/{householdId}/invites/{inviteId}/accept`).
abstract interface class InvitesApi {
  /// Returns the household's single active invite code and whether this caller may replace it
  /// (Story 8.4). No email in the response (AD-6), no list of codes.
  Future<ActiveInviteCode> getActiveInviteCode(String householdId);

  /// Replaces the household's active invite code with [newInviteId] (Story 8.4, F7) — Admin-only.
  /// [newInviteId] and [commandId] are the caller-minted idempotency keys reused across retries of
  /// the *same* intent (AD-8), exactly like `addStore`'s `storeId` — the client mints [newInviteId]
  /// (not this method) so a retry reuses the same id. No response body — the caller re-fetches via
  /// [getActiveInviteCode] (read-your-writes).
  Future<void> replaceInviteCode(String householdId, {required String newInviteId, required String commandId});

  /// Redeems the invite [inviteId] and joins [householdId] (Story 4.2, AC1). [commandId] is the
  /// caller-minted idempotency key, reused across retries of the same attempt (AD-8). No response
  /// body — the caller already holds [householdId] and re-bootstraps to route in (AC1/AC6).
  Future<void> acceptInvite(String householdId, {required String inviteId, required String commandId});
}

class HttpInvitesApi implements InvitesApi {
  const HttpInvitesApi(this._client);

  final AuthenticatedHttpClient _client;

  @override
  Future<ActiveInviteCode> getActiveInviteCode(String householdId) async {
    final json = await _client.getJson('/api/v1/households/$householdId/invite-code');
    return ActiveInviteCode.fromJson(json);
  }

  @override
  Future<void> replaceInviteCode(String householdId, {required String newInviteId, required String commandId}) async {
    await _client.postJson('/api/v1/households/$householdId/invite-code/replace', {
      'newInviteId': newInviteId,
      'commandId': commandId,
    });
  }

  @override
  Future<void> acceptInvite(String householdId, {required String inviteId, required String commandId}) async {
    await _client.postJson('/api/v1/households/$householdId/invites/$inviteId/accept', {
      'commandId': commandId,
    });
  }
}
