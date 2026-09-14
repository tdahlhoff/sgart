import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/account_provisioning_api.dart';
import 'package:sgart/features/auth/data/device_credential.dart';
import 'package:sgart/features/auth/data/device_credential_store.dart';
import 'package:sgart/features/auth/data/direct_grant_oidc_client.dart';
import 'package:sgart/features/auth/data/keycloak_config.dart';

void main() {
  group('DirectGrantOidcClient', () {
    late FakeDeviceCredentialStore deviceCredentialStore;
    late FakeAccountProvisioningApi accountProvisioningApi;
    late FakeHttpClientAdapter adapter;
    late DirectGrantOidcClient client;

    setUp(() async {
      deviceCredentialStore = FakeDeviceCredentialStore(
        await DeviceCredential.fromEntropy(Uint8List(32)),
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

    test('endSession_postsToTheLogoutEndpointWithTheRefreshToken', () async {
      adapter.responseJson = '';

      await client.endSession(refreshToken: 'a-refresh-token');

      expect(adapter.lastRequestPath, KeycloakConfig.logoutEndpoint);
      final sentForm = adapter.lastRequestBody as Map;
      expect(sentForm['refresh_token'], 'a-refresh-token');
      expect(sentForm['client_id'], 'sgart-app');
    });

    test('endSession_isANoOpWhenThereIsNoRefreshToken', () async {
      await client.endSession(refreshToken: null);

      expect(adapter.fetchCallCount, 0);
    });
  });
}

class FakeDeviceCredentialStore implements DeviceCredentialStore {
  FakeDeviceCredentialStore(this.credential);

  final DeviceCredential credential;

  @override
  Future<DeviceCredential> loadOrCreate() async => credential;
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
