import '../../../shared/http/authenticated_http_client.dart';
import 'caller_identity.dart';

/// The client's post-login identity source — calls the backend's canonical `GET
/// /api/v1/identity/me` slice (Story 1.4).
abstract interface class IdentityApi {
  Future<CallerIdentity> fetchMe();
}

class HttpIdentityApi implements IdentityApi {
  const HttpIdentityApi(this._client);

  final AuthenticatedHttpClient _client;

  @override
  Future<CallerIdentity> fetchMe() async {
    final json = await _client.getJson('/api/v1/identity/me');
    return CallerIdentity.fromJson(json);
  }
}
