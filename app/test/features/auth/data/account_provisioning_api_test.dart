import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/account_provisioning_api.dart';
import 'package:sgart/shared/http/authenticated_http_client.dart';

/// Fakes Dio's transport so tests never touch a real socket (CLAUDE.md §6).
class _FakeHttpClientAdapter implements HttpClientAdapter {
  RequestOptions? lastRequest;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    lastRequest = options;
    final bytes = utf8.encode(jsonEncode(<String, dynamic>{}));
    return ResponseBody.fromBytes(bytes, 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    });
  }
}

void main() {
  group('HttpAccountProvisioningApi', () {
    // Regression: provision() used to go through the normal, bearer-token-attaching,
    // refresh-retrying `postJson` — which is exactly what let a stale access token deadlock the
    // sign-in flow (see AuthenticatedHttpClient's postJsonUnauthenticated docs). It must always
    // carry no Authorization header, token available or not.
    test('provision_sendsNoAuthorizationHeaderEvenWhenAnAccessTokenIsAvailable', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      final adapter = _FakeHttpClientAdapter();
      dio.httpClientAdapter = adapter;
      final httpClient = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'a-stale-access-token');
      final api = HttpAccountProvisioningApi(httpClient);

      await api.provision('a-public-key', 'ANDROID');

      expect(adapter.lastRequest!.path, '/api/v1/accounts');
      expect(adapter.lastRequest!.headers.containsKey('Authorization'), isFalse);
      expect(adapter.lastRequest!.data, {'publicKey': 'a-public-key', 'platform': 'ANDROID'});
    });
  });
}
