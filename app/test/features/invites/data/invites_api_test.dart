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
    test('sendInvite_postsTheCorrectPathAndBodyShape', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      final adapter = _FakeHttpClientAdapter((options) async => _jsonResponse(const {}, 201));
      dio.httpClientAdapter = adapter;
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');
      final api = HttpInvitesApi(client);

      await api.sendInvite(
        'household-1',
        inviteId: 'invite-1',
        email: 'anna@example.com',
        commandId: 'command-1',
      );

      final request = adapter.lastRequest!;
      expect(request.path, '/api/v1/households/household-1/invites');
      expect(request.method, 'POST');
      final body = request.data as Map<String, dynamic>;
      expect(body['inviteId'], 'invite-1');
      expect(body['email'], 'anna@example.com');
      expect(body['commandId'], 'command-1');
    });

    test('sendInvite_mapsAServerErrorToAnAppException', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      dio.httpClientAdapter = _FakeHttpClientAdapter(
        (options) async => _jsonResponse({'code': 'invite.duplicatePending', 'message': 'debug only'}, 409),
      );
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');
      final api = HttpInvitesApi(client);

      await expectLater(
        api.sendInvite('household-1', inviteId: 'invite-1', email: 'anna@example.com', commandId: 'command-1'),
        throwsA(isA<AppException>().having((e) => e.error.code, 'code', 'invite.duplicatePending')),
      );
    });

    test('listPendingInvites_getsTheCorrectPathAndParsesTheResponse', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      final adapter = _FakeHttpClientAdapter((options) async => _jsonResponse([
            {
              'inviteId': 'invite-1',
              'invitedAt': '2026-09-06T10:00:00Z',
              'invitedBy': 'member-1',
              'status': 'PENDING',
            },
          ], 200));
      dio.httpClientAdapter = adapter;
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');
      final api = HttpInvitesApi(client);

      final result = await api.listPendingInvites('household-1');

      expect(adapter.lastRequest!.path, '/api/v1/households/household-1/invites');
      expect(adapter.lastRequest!.method, 'GET');
      expect(result, hasLength(1));
      expect(result.first.inviteId, 'invite-1');
      expect(result.first.status, 'PENDING');
    });

    test('listPendingInvites_mapsAServerErrorToAnAppException', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      dio.httpClientAdapter = _FakeHttpClientAdapter(
        (options) async => _jsonResponse({'code': 'identity.notAMember', 'message': 'debug only'}, 403),
      );
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');
      final api = HttpInvitesApi(client);

      await expectLater(
        api.listPendingInvites('household-1'),
        throwsA(isA<AppException>().having((e) => e.error.code, 'code', 'identity.notAMember')),
      );
    });
  });
}
