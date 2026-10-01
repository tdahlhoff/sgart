import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/account_provisioning_api.dart';
import 'package:sgart/features/auth/data/identity_api.dart';
import 'package:sgart/features/auth/data/oidc_client.dart';
import 'package:sgart/features/auth/data/oidc_tokens.dart';
import 'package:sgart/features/auth/presentation/auth_cubit.dart';
import 'package:sgart/features/auth/presentation/auth_state.dart';
import 'package:sgart/shared/http/authenticated_http_client.dart';

import '../../../support/fake_auth_dependencies.dart';
import '../../../support/fake_households_dependencies.dart';

/// A real [OidcClient.signIn] path (device-credential re-auth fallback, Story 7.1) minus the
/// Keycloak Direct-Grant exchange itself: it drives the *real* [AccountProvisioningApi] — exactly
/// as [DirectGrantOidcClient] does — so the deadlock this regresses (a `provision()` 401
/// re-entering the very [AuthCubit.tryReauthenticate] attempt it runs inside of) is reachable
/// through real production wiring, not asserted about in isolation.
class _ProvisioningOidcClient implements OidcClient {
  _ProvisioningOidcClient(this._provisioningApi);

  final AccountProvisioningApi _provisioningApi;
  int signInCallCount = 0;

  @override
  Future<OidcTokens> signIn() async {
    signInCallCount++;
    await _provisioningApi.provision('probe-public-key', 'ANDROID');
    return const OidcTokens(accessToken: 'fresh-access', refreshToken: 'fresh-refresh');
  }

  @override
  Future<OidcTokens> refresh(String refreshToken) async {
    // The scenario this regresses only arises once the OAuth-refresh fast path is dead too —
    // otherwise `tryReauthenticate` never falls through to the device-credential `signIn()` above.
    throw StateError('refresh token dead');
  }
}

/// Simulates a backend whose account store was reset independently of this device (CLAUDE.md §5's
/// exact "cannot guarantee the client resets too" scenario): any request still carrying the old,
/// now-invalid `Authorization` header 401s, on *every* endpoint — including `/api/v1/accounts`,
/// which must never receive one at all (that is the fix). The identity call succeeds once retried
/// with the fresh token the device-credential re-auth obtains.
class _StaleTokenBackendAdapter implements HttpClientAdapter {
  int identityCallCount = 0;
  int provisionCallCount = 0;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    if (options.path.contains('/api/v1/identity/me')) {
      identityCallCount++;
      if (identityCallCount == 1) {
        return _jsonResponse({'code': 'auth.unauthorized', 'message': 'stale token rejected'}, 401);
      }
      return _jsonResponse(
        {'keycloakUserId': 'sub-1', 'displayName': 'Anna Testperson', 'email': 'anna@example.test'},
        200,
      );
    }
    if (options.path.contains('/api/v1/accounts')) {
      provisionCallCount++;
      if (options.headers.containsKey('Authorization')) {
        return _jsonResponse({'code': 'auth.unauthorized', 'message': 'stale token rejected'}, 401);
      }
      return _jsonResponse(const {}, 200);
    }
    throw StateError('unexpected request path in this regression test: ${options.path}');
  }

  ResponseBody _jsonResponse(Map<String, dynamic> json, int statusCode) {
    final bytes = utf8.encode(jsonEncode(json));
    return ResponseBody.fromBytes(bytes, statusCode, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    });
  }
}

void main() {
  // Regression for the real production incident: a device that already holds a valid access token
  // hits a backend whose account store was reset independently (a fresh deploy, or a dev resetting
  // their local stack with no way to guarantee every device resets alongside it, CLAUDE.md §5). The
  // stale token 401s on `GET /me`, which starts `AuthCubit.tryReauthenticate` — whose device-
  // credential fallback re-provisions the account via the *same* shared, refresh-retrying
  // `AuthenticatedHttpClient`. Before the fix, that provisioning call still carried the stale
  // token, 401'd itself, and called back into the very `tryReauthenticate` attempt already
  // in-flight — awaiting its own ancestor `Future` forever, with no exception and no log line: a
  // permanently spinning sign-in screen. This wires the real `AuthenticatedHttpClient` +
  // `HttpIdentityApi` + `HttpAccountProvisioningApi` production assembly (mirroring
  // `AuthGate._buildAuthCubit`) so the test only passes if the whole chain genuinely completes.
  test('bootstrap_completesInsteadOfDeadlockingWhenAStaleAccessTokenSurvivesABackendReset', () async {
    final adapter = _StaleTokenBackendAdapter();
    final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
    dio.httpClientAdapter = adapter;
    final tokenStorage = FakeSecureTokenStorage()
      ..storedTokens = const OidcTokens(accessToken: 'stale-access', refreshToken: 'stale-refresh');

    late final AuthCubit cubit;
    final httpClient = AuthenticatedHttpClient(
      dio: dio,
      accessTokenProvider: () async => cubit.currentAccessToken,
      refreshTokens: () => cubit.tryReauthenticate(),
    );
    final oidcClient = _ProvisioningOidcClient(HttpAccountProvisioningApi(httpClient));
    cubit = AuthCubit(
      oidcClient: oidcClient,
      tokenStorage: tokenStorage,
      identityApi: HttpIdentityApi(httpClient),
      deviceCredentialStore: FakeDeviceCredentialStore(),
      activeHouseholdStore: FakeActiveHouseholdStore(),
    );

    // On the pre-fix code this future never resolves; the timeout turns that hang into a failing
    // test instead of a suite that never finishes.
    await cubit.bootstrap().timeout(const Duration(seconds: 5));

    expect(cubit.state, const AuthState.authenticated('Anna Testperson', 'sub-1'));
    expect(oidcClient.signInCallCount, 1);
    // Exactly one call, and it never carried the stale token in the first place (the fix) — on
    // the pre-fix code this would be 401-rejected, triggering the reentrant `tryReauthenticate`
    // call that deadlocks before ever reaching a second attempt.
    expect(adapter.provisionCallCount, 1);
    expect(adapter.identityCallCount, 2); // the rejected stale attempt, then the successful retry
    await cubit.close();
  });
}
