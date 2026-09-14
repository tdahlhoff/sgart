import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/account_provisioning_api.dart';
import 'package:sgart/features/auth/data/device_credential.dart';
import 'package:sgart/features/auth/data/direct_grant_oidc_client.dart';
import 'package:sgart/features/auth/data/keycloak_config.dart';

import '../../../support/fake_auth_dependencies.dart';

void main() {
  group('DirectGrantOidcClient', () {
    late FakeDeviceCredentialStore deviceCredentialStore;
    late FakeAccountProvisioningApi accountProvisioningApi;
    late FakeHttpClientAdapter adapter;
    late DirectGrantOidcClient client;

    setUp(() async {
      deviceCredentialStore = FakeDeviceCredentialStore(
        credential: await DeviceCredential.fromEntropy(Uint8List(32)),
      );
      accountProvisioningApi = FakeAccountProvisioningApi();
      adapter = FakeHttpClientAdapter();
      final dio = Dio(BaseOptions(baseUrl: 'http://keycloak.example/realms/sgart'));
      dio.httpClientAdapter = adapter;
      client = DirectGrantOidcClient(
        deviceCredentialStore: deviceCredentialStore,
        accountProvisioningApi: accountProvisioningApi,
        dio: dio,
      );
    });

    test('signIn_provisionsTheAccountThenSendsADirectGrantRequestWithASignedChallenge', () async {
      adapter.responseJson = '{"access_token":"access-1","refresh_token":"refresh-1"}';

      final tokens = await client.signIn();

      expect(accountProvisioningApi.provisionedPublicKey, deviceCredentialStore.credential.publicKeyBase64Url);
      expect(tokens.accessToken, 'access-1');
      expect(tokens.refreshToken, 'refresh-1');

      final sentForm = adapter.lastRequestBody as Map;
      expect(sentForm['grant_type'], 'password');
      expect(sentForm['client_id'], 'sgart-app');
      expect(sentForm['username'], deviceCredentialStore.credential.publicKeyBase64Url);
      expect(sentForm['timestamp'], isNotNull);
      expect(sentForm['nonce'], isNotNull);
      expect(sentForm['signed_challenge'], isNotNull);
      expect(adapter.lastRequestPath, KeycloakConfig.tokenEndpoint);
    });

    test('signIn_signsExactlyThePublicKeyTimestampNonceMessage', () async {
      adapter.responseJson = '{"access_token":"access-1"}';

      await client.signIn();

      final sentForm = adapter.lastRequestBody as Map;
      final expectedMessage =
          '${deviceCredentialStore.credential.publicKeyBase64Url}|${sentForm['timestamp']}|${sentForm['nonce']}';
      final expectedSignature = await deviceCredentialStore.credential.sign(expectedMessage);
      expect(sentForm['signed_challenge'], expectedSignature);
    });

    test('refresh_exchangesTheRefreshTokenAndKeepsTheOldOneIfNoneIsReturned', () async {
      adapter.responseJson = '{"access_token":"fresh-access"}';

      final tokens = await client.refresh('old-refresh');

      final sentForm = adapter.lastRequestBody as Map;
      expect(sentForm['grant_type'], 'refresh_token');
      expect(sentForm['refresh_token'], 'old-refresh');
      expect(tokens.accessToken, 'fresh-access');
      expect(tokens.refreshToken, 'old-refresh');
    });

    test('refresh_usesTheRotatedRefreshTokenWhenTheResponseIncludesOne', () async {
      adapter.responseJson = '{"access_token":"fresh-access","refresh_token":"rotated-refresh"}';

      final tokens = await client.refresh('old-refresh');

      expect(tokens.refreshToken, 'rotated-refresh');
    });

    // Test Manifest: recoveryPaths_sendNoEntropyOrPrivateKeyOverTheNetwork (Story 7.2, AC4) — the
    // recovery half of the guarantee. This is DirectGrantOidcClient's own request-assembly, run
    // unchanged after a recovery import (Dev Notes crux: recovery reuses signIn() verbatim), so
    // proving it here covers the sign-in DirectGrantOidcClient issues on the recovery path too. The
    // reveal half needs no test here: RecoveryPhraseRevealPage never constructs a Dio/HTTP client
    // at all (see recovery_phrase_reveal_page_test.dart), so it cannot make a network call by
    // construction.
    test('signIn_sendsOnlyThePublicKeyAndSignedChallenge_neverTheEntropyOrPhraseWords', () async {
      adapter.responseJson = '{"access_token":"access-1"}';

      await client.signIn();

      final sentForm = adapter.lastRequestBody as Map;
      // The wire contract itself has no field for entropy/phrase words — DeviceCredential's own
      // API makes leaking them structurally impossible (only publicKeyBase64Url/sign() are
      // exposed). Pinning the exact key set is the regression guard: a future field could only be
      // added here by a deliberate change, not by an accidental new export off DeviceCredential.
      expect(
        sentForm.keys,
        unorderedEquals(<String>['grant_type', 'client_id', 'username', 'timestamp', 'nonce', 'signed_challenge']),
      );
      expect(sentForm['username'], deviceCredentialStore.credential.publicKeyBase64Url);
    });
  });
}

class FakeAccountProvisioningApi implements AccountProvisioningApi {
  String? provisionedPublicKey;
  String? provisionedPlatform;

  @override
  Future<void> provision(String publicKey, String platform) async {
    provisionedPublicKey = publicKey;
    provisionedPlatform = platform;
  }
}

/// A minimal [HttpClientAdapter] test double — captures the last request's decoded form body and
/// returns a canned JSON response, with no real network call (CLAUDE.md §6).
class FakeHttpClientAdapter implements HttpClientAdapter {
  String responseJson = '{}';
  Object? lastRequestBody;
  String? lastRequestPath;
  int fetchCallCount = 0;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    fetchCallCount++;
    lastRequestPath = options.path;
    lastRequestBody = options.data;
    return ResponseBody.fromString(
      responseJson,
      200,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}
