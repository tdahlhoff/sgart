import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/settings/data/nickname_api.dart';
import 'package:sgart/shared/http/app_exception.dart';
import 'package:sgart/shared/http/authenticated_http_client.dart';

/// Fakes Dio's transport so tests never touch a real socket (CLAUDE.md §6). Mirrors
/// `members_api_test.dart`.
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

HttpNicknameApi _apiOver(_FakeHttpClientAdapter adapter) {
  final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'))..httpClientAdapter = adapter;
  return HttpNicknameApi(AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token'));
}

void main() {
  group('HttpNicknameApi', () {
    test('setNickname_putsTheNicknameToTheHouseholdScopedPath', () async {
      final adapter = _FakeHttpClientAdapter((options) async => _jsonResponse(const {}, 204));

      await _apiOver(adapter).setNickname('household-1', 'Papa');

      final request = adapter.lastRequest!;
      expect(request.path, '/api/v1/identity/households/household-1/nickname');
      expect(request.method, 'PUT');
      expect((request.data as Map<String, dynamic>)['nickname'], 'Papa');
    });

    test('setNickname_mapsAServerRejectionToAnAppException', () async {
      final adapter = _FakeHttpClientAdapter(
        (options) async => _jsonResponse({'code': 'nickname.tooLong', 'message': 'debug only'}, 400),
      );

      await expectLater(
        _apiOver(adapter).setNickname('household-1', 'Papa'),
        throwsA(isA<AppException>().having((e) => e.error.code, 'code', 'nickname.tooLong')),
      );
    });
  });
}
