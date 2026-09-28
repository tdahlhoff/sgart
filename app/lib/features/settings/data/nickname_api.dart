import '../../../shared/http/authenticated_http_client.dart';

/// Calls the backend's Story 8.3 per-household nickname endpoint. Abstracted so the cubits/pages
/// that drive nickname capture/edit never touch a real HTTP client in tests (CLAUDE.md §6),
/// mirroring [AccountEmailApi]'s shape.
abstract interface class NicknameApi {
  /// `PUT /api/v1/identity/households/{householdId}/nickname` — sets the caller's own nickname
  /// for that household (required at onboarding, editable later from Profile). The server is the
  /// validation authority (`nickname.required` / `nickname.tooLong`, 400); the caller must already
  /// be a member (`identity.notAMember`, 403).
  Future<void> setNickname(String householdId, String nickname);
}

/// The real, backend-[AuthenticatedHttpClient]-backed [NicknameApi].
class HttpNicknameApi implements NicknameApi {
  const HttpNicknameApi(this._httpClient);

  final AuthenticatedHttpClient _httpClient;

  @override
  Future<void> setNickname(String householdId, String nickname) => _httpClient.putJson(
        '/api/v1/identity/households/$householdId/nickname',
        {'nickname': nickname},
      );
}
