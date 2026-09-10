import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/shared/http/authenticated_http_client.dart';
import 'package:sgart/shared/push/backend_device_registration_client.dart';

/// Fakes Dio's transport so tests never touch a real socket (CLAUDE.md §6 — isolate external
/// systems), mirroring `authenticated_http_client_test.dart`.
class _FakeHttpClientAdapter implements HttpClientAdapter {
  _FakeHttpClientAdapter(this.handle);

  final Future<ResponseBody> Function(RequestOptions options) handle;
  RequestOptions? lastRequest;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) {
    lastRequest = options;
    return handle(options);
  }
}

ResponseBody _emptyResponse(int statusCode) => ResponseBody.fromBytes(const [], statusCode);

void main() {
  group('BackendDeviceRegistrationClient', () {
    test('register_postsTheTokenAndPlatformToTheDevicesEndpoint', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      final adapter = _FakeHttpClientAdapter((options) async => _emptyResponse(204));
      dio.httpClientAdapter = adapter;
      final httpClient = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');
      final client = BackendDeviceRegistrationClient(httpClient: httpClient);

      await client.register(token: 'fcm-token-1', platform: 'ANDROID');

      expect(adapter.lastRequest!.method, 'POST');
      expect(adapter.lastRequest!.path, '/api/v1/devices');
      expect(adapter.lastRequest!.data, {'token': 'fcm-token-1', 'platform': 'ANDROID'});
    });

    test('unregister_deletesWithTheTokenInTheBodyNotThePath', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      final adapter = _FakeHttpClientAdapter((options) async => _emptyResponse(204));
      dio.httpClientAdapter = adapter;
      final httpClient = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');
      final client = BackendDeviceRegistrationClient(httpClient: httpClient);

      await client.unregister('fcm-token-1');

      expect(adapter.lastRequest!.method, 'DELETE');
      expect(adapter.lastRequest!.path, '/api/v1/devices');
      expect(adapter.lastRequest!.data, {'token': 'fcm-token-1'});
    });
  });
}
