import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/invites/data/invites_api.dart';
import 'package:sgart/shared/http/app_exception.dart';
import 'package:sgart/shared/http/authenticated_http_client.dart';

/// Fakes Dio's transport so tests never touch a real socket (CLAUDE.md §6). Mirrors
/// `trips_api_test.dart`.
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
  group('HttpInvitesApi', () {
    test('getActiveInviteCode_getsTheCorrectPathAndParsesTheResponse', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      final adapter = _FakeHttpClientAdapter(
          (options) async => _jsonResponse(const {'inviteId': 'invite-1', 'canReplace': true}, 200));
      dio.httpClientAdapter = adapter;
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');
      final api = HttpInvitesApi(client);

      final result = await api.getActiveInviteCode('household-1');

      expect(adapter.lastRequest!.path, '/api/v1/households/household-1/invite-code');
      expect(adapter.lastRequest!.method, 'GET');
      expect(result.inviteId, 'invite-1');
      expect(result.canReplace, isTrue);
    });

    test('getActiveInviteCode_mapsAServerErrorToAnAppException', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      dio.httpClientAdapter = _FakeHttpClientAdapter(
        (options) async => _jsonResponse({'code': 'identity.notAMember', 'message': 'debug only'}, 403),
      );
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');
      final api = HttpInvitesApi(client);

      await expectLater(
        api.getActiveInviteCode('household-1'),
        throwsA(isA<AppException>().having((e) => e.error.code, 'code', 'identity.notAMember')),
      );
    });

    test('replaceInviteCode_postsTheCorrectPathAndBodyShape', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      final adapter = _FakeHttpClientAdapter((options) async => _jsonResponse(const {}, 200));
      dio.httpClientAdapter = adapter;
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');
      final api = HttpInvitesApi(client);

      await api.replaceInviteCode('household-1', newInviteId: 'invite-2', commandId: 'command-1');

      final request = adapter.lastRequest!;
      expect(request.path, '/api/v1/households/household-1/invite-code/replace');
      expect(request.method, 'POST');
      final body = request.data as Map<String, dynamic>;
      expect(body['newInviteId'], 'invite-2');
      expect(body['commandId'], 'command-1');
    });

    test('replaceInviteCode_mapsAServerErrorToAnAppException', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      dio.httpClientAdapter = _FakeHttpClientAdapter(
        (options) async => _jsonResponse({'code': 'governance.notPermitted', 'message': 'debug only'}, 403),
      );
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');
      final api = HttpInvitesApi(client);

      await expectLater(
        api.replaceInviteCode('household-1', newInviteId: 'invite-2', commandId: 'command-1'),
        throwsA(isA<AppException>().having((e) => e.error.code, 'code', 'governance.notPermitted')),
      );
    });

    test('acceptInvite_postsTheCorrectPathAndBodyShape', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      final adapter = _FakeHttpClientAdapter((options) async => _jsonResponse(const {}, 200));
      dio.httpClientAdapter = adapter;
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');
      final api = HttpInvitesApi(client);

      await api.acceptInvite('household-1', inviteId: 'invite-1', commandId: 'command-1');

      final request = adapter.lastRequest!;
      expect(request.path, '/api/v1/households/household-1/invites/invite-1/accept');
      expect(request.method, 'POST');
      final body = request.data as Map<String, dynamic>;
      expect(body['commandId'], 'command-1');
    });

    test('acceptInvite_mapsAServerErrorToAnAppException', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      dio.httpClientAdapter = _FakeHttpClientAdapter(
        (options) async => _jsonResponse({'code': 'invite.notFound', 'message': 'debug only'}, 404),
      );
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');
      final api = HttpInvitesApi(client);

      await expectLater(
        api.acceptInvite('household-1', inviteId: 'invite-1', commandId: 'command-1'),
        throwsA(isA<AppException>().having((e) => e.error.code, 'code', 'invite.notFound')),
      );
    });
  });
}
