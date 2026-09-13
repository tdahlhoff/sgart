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

ResponseBody _jsonArrayResponse(List<dynamic> json, int statusCode) {
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

    test('getJsonList_returnsTheDecodedJsonArrayOnSuccess', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      dio.httpClientAdapter = _FakeHttpClientAdapter(
        (options) async => _jsonArrayResponse([
          {'householdId': 'id-1', 'name': 'Familie Muster'},
        ], 200),
      );
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');

      final json = await client.getJsonList('/api/v1/households');

      expect(json, hasLength(1));
      expect((json.first as Map<String, dynamic>)['name'], 'Familie Muster');
    });

    test('getJsonList_mapsABackendErrorBodyToAnAppException', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      dio.httpClientAdapter = _FakeHttpClientAdapter(
        (options) async => _jsonResponse({'code': 'auth.unauthorized', 'message': 'debug only'}, 401),
      );
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');

      await expectLater(
        client.getJsonList('/api/v1/households'),
        throwsA(isA<AppException>().having((e) => e.error.code, 'code', 'auth.unauthorized')),
      );
    });

    test('postJson_attachesTheBearerTokenAndReturnsTheDecodedJsonBodyOnSuccess', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      final adapter = _FakeHttpClientAdapter(
        (options) async => _jsonResponse({'householdId': 'id-1'}, 201),
      );
      dio.httpClientAdapter = adapter;
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'the-access-token');

      final json = await client.postJson('/api/v1/households', {'name': 'Familie Muster'});

      expect(adapter.lastRequest!.headers['Authorization'], 'Bearer the-access-token');
      expect(json['householdId'], 'id-1');
    });

    test('postJson_mapsABackendErrorBodyToAnAppException', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      dio.httpClientAdapter = _FakeHttpClientAdapter(
        (options) async => _jsonResponse({'code': 'household.nameRequired', 'message': 'debug only'}, 400),
      );
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');

      await expectLater(
        client.postJson('/api/v1/households', {'name': ''}),
        throwsA(isA<AppException>().having((e) => e.error.code, 'code', 'household.nameRequired')),
      );
    });

    group('refresh-and-retry-once on 401 (optional refreshTokens callback)', () {
      test('getJson_retriesTransparentlyAfterA401WhenRefreshSucceeds', () async {
        var callCount = 0;
        final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
        dio.httpClientAdapter = _FakeHttpClientAdapter((options) async {
          callCount++;
          if (callCount == 1) {
            return _jsonResponse(const {'code': 'auth.unauthorized', 'message': 'expired'}, 401);
          }
          return _jsonResponse({'keycloakUserId': 'sub-1'}, 200);
        });
        var refreshCallCount = 0;
        final client = AuthenticatedHttpClient(
          dio: dio,
          accessTokenProvider: () async => 'token',
          refreshTokens: () async {
            refreshCallCount++;
            return true;
          },
        );

        final json = await client.getJson('/api/v1/identity/me');

        expect(json['keycloakUserId'], 'sub-1');
        expect(callCount, 2);
        expect(refreshCallCount, 1);
      });

      test('getJson_propagatesTheOriginal401OnceWhenRefreshFailsWithNoSecondRequest', () async {
        var callCount = 0;
        final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
        dio.httpClientAdapter = _FakeHttpClientAdapter((options) async {
          callCount++;
          return _jsonResponse(const {'code': 'auth.unauthorized', 'message': 'expired'}, 401);
        });
        final client = AuthenticatedHttpClient(
          dio: dio,
          accessTokenProvider: () async => 'token',
          refreshTokens: () async => false,
        );

        await expectLater(
          client.getJson('/api/v1/identity/me'),
          throwsA(isA<AppException>().having((e) => e.error.code, 'code', 'auth.unauthorized')),
        );
        expect(callCount, 1);
      });

      test('getJson_propagatesTheSecond401OnceWhenTheRetriedRequestAlso401sNoLoop', () async {
        var callCount = 0;
        final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
        dio.httpClientAdapter = _FakeHttpClientAdapter((options) async {
          callCount++;
          return _jsonResponse(const {'code': 'auth.unauthorized', 'message': 'expired'}, 401);
        });
        var refreshCallCount = 0;
        final client = AuthenticatedHttpClient(
          dio: dio,
          accessTokenProvider: () async => 'token',
          refreshTokens: () async {
            refreshCallCount++;
            return true;
          },
        );

        await expectLater(
          client.getJson('/api/v1/identity/me'),
          throwsA(isA<AppException>().having((e) => e.error.code, 'code', 'auth.unauthorized')),
        );
        // Exactly one retry: the first call, one refresh, one retried call — never a loop.
        expect(callCount, 2);
        expect(refreshCallCount, 1);
      });

      test('getJson_neverRetriesWhenNoRefreshCallbackIsSupplied', () async {
        var callCount = 0;
        final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
        dio.httpClientAdapter = _FakeHttpClientAdapter((options) async {
          callCount++;
          return _jsonResponse(const {'code': 'auth.unauthorized', 'message': 'expired'}, 401);
        });
        final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');

        await expectLater(
          client.getJson('/api/v1/identity/me'),
          throwsA(isA<AppException>().having((e) => e.error.code, 'code', 'auth.unauthorized')),
        );
        expect(callCount, 1);
      });

      test('postJson_retriesTransparentlyAfterA401WhenRefreshSucceeds', () async {
        var callCount = 0;
        final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
        dio.httpClientAdapter = _FakeHttpClientAdapter((options) async {
          callCount++;
          if (callCount == 1) {
            return _jsonResponse(const {'code': 'auth.unauthorized', 'message': 'expired'}, 401);
          }
          return _jsonResponse({'householdId': 'id-1'}, 201);
        });
        final client = AuthenticatedHttpClient(
          dio: dio,
          accessTokenProvider: () async => 'token',
          refreshTokens: () async => true,
        );

        final json = await client.postJson('/api/v1/households', {'name': 'Familie Muster'});

        expect(json['householdId'], 'id-1');
        expect(callCount, 2);
      });

      test('getJsonList_retriesTransparentlyAfterA401WhenRefreshSucceeds', () async {
        var callCount = 0;
        final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
        dio.httpClientAdapter = _FakeHttpClientAdapter((options) async {
          callCount++;
          if (callCount == 1) {
            return _jsonResponse(const {'code': 'auth.unauthorized', 'message': 'expired'}, 401);
          }
          return _jsonArrayResponse([
            {'householdId': 'id-1', 'name': 'Familie Muster'},
          ], 200);
        });
        final client = AuthenticatedHttpClient(
          dio: dio,
          accessTokenProvider: () async => 'token',
          refreshTokens: () async => true,
        );

        final json = await client.getJsonList('/api/v1/households');

        expect(json, hasLength(1));
        expect(callCount, 2);
      });

      test('patchJson_retriesTransparentlyAfterA401WhenRefreshSucceeds', () async {
        var callCount = 0;
        final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
        dio.httpClientAdapter = _FakeHttpClientAdapter((options) async {
          callCount++;
          if (callCount == 1) {
            return _jsonResponse(const {'code': 'auth.unauthorized', 'message': 'expired'}, 401);
          }
          return _jsonResponse(const {}, 204);
        });
        final client = AuthenticatedHttpClient(
          dio: dio,
          accessTokenProvider: () async => 'token',
          refreshTokens: () async => true,
        );

        await client.patchJson('/api/v1/households/id-1', {'name': 'Neuer Name'});

        expect(callCount, 2);
      });

      test('putJson_retriesTransparentlyAfterA401WhenRefreshSucceeds', () async {
        var callCount = 0;
        final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
        dio.httpClientAdapter = _FakeHttpClientAdapter((options) async {
          callCount++;
          if (callCount == 1) {
            return _jsonResponse(const {'code': 'auth.unauthorized', 'message': 'expired'}, 401);
          }
          return _jsonResponse(const {}, 204);
        });
        final client = AuthenticatedHttpClient(
          dio: dio,
          accessTokenProvider: () async => 'token',
          refreshTokens: () async => true,
        );

        await client.putJson('/api/v1/households/id-1', {'name': 'Neuer Name'});

        expect(callCount, 2);
      });

      test('deleteJson_retriesTransparentlyAfterA401WhenRefreshSucceeds', () async {
        var callCount = 0;
        final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
        dio.httpClientAdapter = _FakeHttpClientAdapter((options) async {
          callCount++;
          if (callCount == 1) {
            return _jsonResponse(const {'code': 'auth.unauthorized', 'message': 'expired'}, 401);
          }
          return _jsonResponse(const {}, 204);
        });
        final client = AuthenticatedHttpClient(
          dio: dio,
          accessTokenProvider: () async => 'token',
          refreshTokens: () async => true,
        );

        await client.deleteJson('/api/v1/households/id-1', {'reason': 'no longer needed'});

        expect(callCount, 2);
      });

      test('nonAuthUnauthorizedErrorsAreNeverRetried', () async {
        var callCount = 0;
        final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
        dio.httpClientAdapter = _FakeHttpClientAdapter((options) async {
          callCount++;
          return _jsonResponse(const {'code': 'household.nameRequired', 'message': 'debug'}, 400);
        });
        var refreshCallCount = 0;
        final client = AuthenticatedHttpClient(
          dio: dio,
          accessTokenProvider: () async => 'token',
          refreshTokens: () async {
            refreshCallCount++;
            return true;
          },
        );

        await expectLater(
          client.postJson('/api/v1/households', {'name': ''}),
          throwsA(isA<AppException>().having((e) => e.error.code, 'code', 'household.nameRequired')),
        );
        expect(callCount, 1);
        expect(refreshCallCount, 0);
      });
    });
  });
}
