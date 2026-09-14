import 'dart:io';
import 'dart:math';

import 'package:dio/dio.dart';

import 'account_provisioning_api.dart';
import 'device_credential.dart';
import 'device_credential_store.dart';
import 'keycloak_config.dart';
import 'oidc_client.dart';
import 'oidc_tokens.dart';

/// Real [OidcClient] backed by the custom Direct-Grant flow (Story 7.1, AC1/AC2) — supersedes
/// Story 1.4's `flutter_appauth` Authorization Code + PKCE browser flow (AC6). [signIn] never
/// opens a browser: it loads (or creates) the device credential, silently provisions its Keycloak
/// account, then proves possession of the device's private key with a signed challenge —
/// `grant_type=password` here is the OAuth2 Resource Owner Password Credentials *shape*
/// repurposed for a device-signed challenge, never an actual password (the custom authenticator
/// SPI, `backend/keycloak-authenticator`, verifies the signature instead).
class DirectGrantOidcClient implements OidcClient {
  DirectGrantOidcClient({
    required this._deviceCredentialStore,
    required this._accountProvisioningApi,
    Dio? dio,
  }) : _dio = dio ?? Dio(BaseOptions(baseUrl: KeycloakConfig.issuer));

  final DeviceCredentialStore _deviceCredentialStore;
  final AccountProvisioningApi _accountProvisioningApi;
  final Dio _dio;

  @override
  Future<OidcTokens> signIn() async {
    final credential = await _deviceCredentialStore.loadOrCreate();
    await _accountProvisioningApi.provision(credential.publicKeyBase64Url, _platform());
    return _signInWithChallenge(credential);
  }

  Future<OidcTokens> _signInWithChallenge(DeviceCredential credential) async {
    final timestamp = (DateTime.now().millisecondsSinceEpoch ~/ 1000).toString();
    final nonce = _generateNonce();
    final signature = await credential.sign('${credential.publicKeyBase64Url}|$timestamp|$nonce');

    final response = await _dio.post<Map<String, dynamic>>(
      KeycloakConfig.tokenEndpoint,
      data: {
        'grant_type': 'password',
        'client_id': KeycloakConfig.clientId,
        'username': credential.publicKeyBase64Url,
        'timestamp': timestamp,
        'nonce': nonce,
        'signed_challenge': signature,
      },
      options: Options(contentType: Headers.formUrlEncodedContentType),
    );
    return _tokensFrom(response.data!);
  }

  @override
  Future<OidcTokens> refresh(String refreshToken) async {
    final response = await _dio.post<Map<String, dynamic>>(
      KeycloakConfig.tokenEndpoint,
      data: {
        'grant_type': 'refresh_token',
        'client_id': KeycloakConfig.clientId,
        'refresh_token': refreshToken,
      },
      options: Options(contentType: Headers.formUrlEncodedContentType),
    );
    final refreshed = _tokensFrom(response.data!);
    // Keycloak rotates the refresh token; fall back to the old one if the response omits it.
    return OidcTokens(accessToken: refreshed.accessToken, refreshToken: refreshed.refreshToken ?? refreshToken);
  }

  @override
  Future<void> endSession({required String? refreshToken}) async {
    if (refreshToken == null) {
      return;
    }
    // Keycloak's logout endpoint revokes a public client's session from just its refresh_token —
    // no id_token_hint needed, since there is no browser session to end (AC6).
    await _dio.post<void>(
      KeycloakConfig.logoutEndpoint,
      data: {'client_id': KeycloakConfig.clientId, 'refresh_token': refreshToken},
      options: Options(contentType: Headers.formUrlEncodedContentType),
    );
  }

  OidcTokens _tokensFrom(Map<String, dynamic> body) {
    return OidcTokens(
      accessToken: body['access_token'] as String,
      refreshToken: body['refresh_token'] as String?,
    );
  }

  String _platform() => Platform.isIOS ? 'IOS' : 'ANDROID';

  /// A random, opaque, single-use value — not a secret; it only defends against a captured
  /// challenge being replayed (D-B). 16 bytes is comfortably collision-resistant for this purpose.
  String _generateNonce() {
    final randomBytes = List<int>.generate(16, (_) => Random.secure().nextInt(256));
    return randomBytes.map((byte) => byte.toRadixString(16).padLeft(2, '0')).join();
  }
}
