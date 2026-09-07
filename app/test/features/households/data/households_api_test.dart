import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/households/data/households_api.dart';
import 'package:sgart/shared/http/app_exception.dart';
import 'package:sgart/shared/http/authenticated_http_client.dart';

/// Fakes Dio's transport so tests never touch a real socket (CLAUDE.md §6). Mirrors
/// `invites_api_test.dart`. Covers only `deleteHousehold` (Story 4.3, AC7) — the rest of
/// `HttpHouseholdsApi` predates this test file.
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

ResponseBody _jsonResponse(Object json, int statusCode) {
  final bytes = utf8.encode(jsonEncode(json));
  return ResponseBody.fromBytes(bytes, statusCode, headers: {
    Headers.contentTypeHeader: [Headers.jsonContentType],
  });
}

void main() {
  group('HttpHouseholdsApi.deleteHousehold', () {
    test('deletesTheCorrectPathAndBodyShape', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      final adapter = _FakeHttpClientAdapter((options) async => _jsonResponse(const {}, 204));
      dio.httpClientAdapter = adapter;
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');
      final api = HttpHouseholdsApi(client);

      await api.deleteHousehold('household-1', commandId: 'command-1');

      final request = adapter.lastRequest!;
      expect(request.path, '/api/v1/households/household-1');
      expect(request.method, 'DELETE');
      final body = request.data as Map<String, dynamic>;
      expect(body['commandId'], 'command-1');
    });

    test('mapsAGovernanceRejectionToAnAppException', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      dio.httpClientAdapter = _FakeHttpClientAdapter(
        (options) async => _jsonResponse({'code': 'governance.notPermitted', 'message': 'debug only'}, 403),
      );
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');
      final api = HttpHouseholdsApi(client);

      await expectLater(
        api.deleteHousehold('household-1', commandId: 'command-1'),
        throwsA(isA<AppException>().having((e) => e.error.code, 'code', 'governance.notPermitted')),
      );
    });
  });
}
