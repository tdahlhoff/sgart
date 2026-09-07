import '../../../shared/http/authenticated_http_client.dart';
import 'pending_invite.dart';

/// The client's invite-management source — calls the backend's invite slice under a household
/// (`/api/v1/households/{householdId}/invites`, Story 4.1).
abstract interface class InvitesApi {
  /// Sends an invite to [email] (AC1). [inviteId] and [commandId] are the caller-minted idempotency
  /// keys reused across retries of the *same* intent (AD-8), exactly like `addStore`'s `storeId` —
  /// the client mints [inviteId] (not this method) so a retry reuses the same id.
  Future<void> sendInvite(
    String householdId, {
    required String inviteId,
    required String email,
    required String commandId,
  });

  /// Lists the household's pending (non-expired) invites (AC6). No email in the response (AD-6).
  Future<List<PendingInvite>> listPendingInvites(String householdId);

  /// Redeems the invite [inviteId] and joins [householdId] (Story 4.2, AC1). [commandId] is the
  /// caller-minted idempotency key, reused across retries of the same attempt (AD-8). No response
  /// body — the caller already holds [householdId] and re-bootstraps to route in (AC1/AC6).
  Future<void> acceptInvite(String householdId, {required String inviteId, required String commandId});
}

class HttpInvitesApi implements InvitesApi {
  const HttpInvitesApi(this._client);

  final AuthenticatedHttpClient _client;

  @override
  Future<void> sendInvite(
    String householdId, {
    required String inviteId,
    required String email,
    required String commandId,
  }) async {
    // The caller-minted invite id is sent in the envelope, so the response needs no body
    // (read-your-writes without a projection wait) — the same rationale as `addStore`'s storeId.
    await _client.postJson('/api/v1/households/$householdId/invites', {
      'inviteId': inviteId,
      'email': email,
      'commandId': commandId,
    });
  }

  @override
  Future<List<PendingInvite>> listPendingInvites(String householdId) async {
    final json = await _client.getJsonList('/api/v1/households/$householdId/invites');
    return json.map((entry) => PendingInvite.fromJson(entry as Map<String, dynamic>)).toList();
  }

  @override
  Future<void> acceptInvite(String householdId, {required String inviteId, required String commandId}) async {
    await _client.postJson('/api/v1/households/$householdId/invites/$inviteId/accept', {
      'commandId': commandId,
    });
  }
}
