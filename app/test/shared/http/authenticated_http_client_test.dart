import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/shared/http/app_exception.dart';
import 'package:sgart/shared/http/authenticated_http_client.dart';

/// Fakes Dio's transport so tests never touch a real socket (CLAUDE.md §6 — isolate external
/// systems).
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

ResponseBody _jsonResponse(Map<String, dynamic> json, int statusCode) {
  final bytes = utf8.encode(jsonEncode(json));
  return ResponseBody.fromBytes(bytes, statusCode, headers: {
    Headers.contentTypeHeader: [Headers.jsonContentType],
  });
}

void main() {
  group('AuthenticatedHttpClient', () {
    test('getJson_attachesTheBearerTokenFromTheAccessTokenProvider', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      final adapter = _FakeHttpClientAdapter(
        (options) async => _jsonResponse({'keycloakUserId': 'sub-1'}, 200),
      );
      dio.httpClientAdapter = adapter;
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'the-access-token');

      await client.getJson('/api/v1/identity/me');

      expect(adapter.lastRequest!.headers['Authorization'], 'Bearer the-access-token');
    });

    test('getJson_omitsTheAuthorizationHeaderWhenThereIsNoToken', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      final adapter = _FakeHttpClientAdapter(
        (options) async => _jsonResponse({'keycloakUserId': 'sub-1'}, 200),
      );
      dio.httpClientAdapter = adapter;
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => null);

      await client.getJson('/api/v1/identity/me');

      expect(adapter.lastRequest!.headers.containsKey('Authorization'), isFalse);
    });

    test('getJson_returnsTheDecodedJsonBodyOnSuccess', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      dio.httpClientAdapter = _FakeHttpClientAdapter(
        (options) async => _jsonResponse({'keycloakUserId': 'sub-1', 'displayName': 'Anna'}, 200),
      );
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');

      final json = await client.getJson('/api/v1/identity/me');

      expect(json['keycloakUserId'], 'sub-1');
      expect(json['displayName'], 'Anna');
    });

    test('getJson_mapsABackendErrorBodyToAnAppException', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      dio.httpClientAdapter = _FakeHttpClientAdapter(
        (options) async => _jsonResponse({'code': 'identity.notAMember', 'message': 'debug only'}, 403),
      );
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');

      await expectLater(
        client.getJson('/api/v1/identity/me'),
        throwsA(isA<AppException>().having((e) => e.error.code, 'code', 'identity.notAMember')),
      );
    });

    test('getJson_mapsA401WithNoErrorBodyToAnUnauthorizedAppExceptionNotNetworkUnreachable', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      dio.httpClientAdapter = _FakeHttpClientAdapter((options) async => _jsonResponse(const {}, 401));
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');

      await expectLater(
        client.getJson('/api/v1/identity/me'),
        throwsA(isA<AppException>().having((e) => e.error.code, 'code', 'auth.unauthorized')),
      );
    });

    test('getJson_mapsANon401ErrorStatusToAnHttpErrorAppExceptionPreservingTheStatusCode', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      dio.httpClientAdapter = _FakeHttpClientAdapter((options) async => _jsonResponse(const {}, 503));
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');

      await expectLater(
        client.getJson('/api/v1/identity/me'),
        throwsA(isA<AppException>()
            .having((e) => e.error.code, 'code', 'http.error')
            .having((e) => e.error.details['statusCode'], 'statusCode', 503)),
      );
    });

    test('getJson_mapsATransportFailureWithNoBodyToAGenericAppException', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      dio.httpClientAdapter = _FakeHttpClientAdapter(
        (options) async => throw DioException(requestOptions: options, message: 'connection refused'),
      );
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');

      await expectLater(
        client.getJson('/api/v1/identity/me'),
        throwsA(isA<AppException>().having((e) => e.error.code, 'code', 'network.unreachable')),
      );
    });
  });
}
