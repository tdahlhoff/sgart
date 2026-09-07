import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/members/data/members_api.dart';
import 'package:sgart/shared/http/app_exception.dart';
import 'package:sgart/shared/http/authenticated_http_client.dart';

/// Fakes Dio's transport so tests never touch a real socket (CLAUDE.md §6). Mirrors
/// `invites_api_test.dart`.
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
  group('HttpMembersApi', () {
    test('listMembers_getsTheCorrectPathAndParsesTheResponse', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      final adapter = _FakeHttpClientAdapter((options) async => _jsonResponse([
            {'memberId': 'member-1', 'role': 'ADMIN', 'isSelf': true},
            {'memberId': 'member-2', 'role': 'PARTICIPANT', 'isSelf': false},
          ], 200));
      dio.httpClientAdapter = adapter;
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');
      final api = HttpMembersApi(client);

      final result = await api.listMembers('household-1');

      expect(adapter.lastRequest!.path, '/api/v1/households/household-1/members');
      expect(adapter.lastRequest!.method, 'GET');
      expect(result, hasLength(2));
      expect(result.first.memberId, 'member-1');
      expect(result.first.role, 'ADMIN');
      expect(result.first.isSelf, isTrue);
    });

    test('listMembers_mapsAServerErrorToAnAppException', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      dio.httpClientAdapter = _FakeHttpClientAdapter(
        (options) async => _jsonResponse({'code': 'identity.notAMember', 'message': 'debug only'}, 403),
      );
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');
      final api = HttpMembersApi(client);

      await expectLater(
        api.listMembers('household-1'),
        throwsA(isA<AppException>().having((e) => e.error.code, 'code', 'identity.notAMember')),
      );
    });

    test('leave_deletesTheCorrectPathAndBodyShape', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      final adapter = _FakeHttpClientAdapter((options) async => _jsonResponse(const {}, 204));
      dio.httpClientAdapter = adapter;
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');
      final api = HttpMembersApi(client);

      await api.leave('household-1', commandId: 'command-1');

      final request = adapter.lastRequest!;
      expect(request.path, '/api/v1/households/household-1/members/me');
      expect(request.method, 'DELETE');
      final body = request.data as Map<String, dynamic>;
      expect(body['commandId'], 'command-1');
    });

    test('leave_mapsALastAdminConflictToAnAppException', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      dio.httpClientAdapter = _FakeHttpClientAdapter(
        (options) async => _jsonResponse({'code': 'membership.lastAdmin', 'message': 'debug only'}, 409),
      );
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');
      final api = HttpMembersApi(client);

      await expectLater(
        api.leave('household-1', commandId: 'command-1'),
        throwsA(isA<AppException>().having((e) => e.error.code, 'code', 'membership.lastAdmin')),
      );
    });

    test('removeMember_deletesTheCorrectPathAndBodyShape', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      final adapter = _FakeHttpClientAdapter((options) async => _jsonResponse(const {}, 204));
      dio.httpClientAdapter = adapter;
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');
      final api = HttpMembersApi(client);

      await api.removeMember('household-1', 'member-2', commandId: 'command-1');

      final request = adapter.lastRequest!;
      expect(request.path, '/api/v1/households/household-1/members/member-2');
      expect(request.method, 'DELETE');
      final body = request.data as Map<String, dynamic>;
      expect(body['commandId'], 'command-1');
    });

    test('removeMember_mapsAGovernanceRejectionToAnAppException', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      dio.httpClientAdapter = _FakeHttpClientAdapter(
        (options) async => _jsonResponse({'code': 'governance.notPermitted', 'message': 'debug only'}, 403),
      );
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');
      final api = HttpMembersApi(client);

      await expectLater(
        api.removeMember('household-1', 'member-2', commandId: 'command-1'),
        throwsA(isA<AppException>().having((e) => e.error.code, 'code', 'governance.notPermitted')),
      );
    });

    test('promote_postsTheCorrectPathAndBodyShape', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      final adapter = _FakeHttpClientAdapter((options) async => _jsonResponse(const {}, 204));
      dio.httpClientAdapter = adapter;
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');
      final api = HttpMembersApi(client);

      await api.promote('household-1', 'member-2', commandId: 'command-1');

      final request = adapter.lastRequest!;
      expect(request.path, '/api/v1/households/household-1/members/member-2/promote');
      expect(request.method, 'POST');
      final body = request.data as Map<String, dynamic>;
      expect(body['commandId'], 'command-1');
    });

    test('demote_postsTheCorrectPathAndBodyShape', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      final adapter = _FakeHttpClientAdapter((options) async => _jsonResponse(const {}, 204));
      dio.httpClientAdapter = adapter;
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');
      final api = HttpMembersApi(client);

      await api.demote('household-1', 'member-2', commandId: 'command-1');

      final request = adapter.lastRequest!;
      expect(request.path, '/api/v1/households/household-1/members/member-2/demote');
      expect(request.method, 'POST');
      final body = request.data as Map<String, dynamic>;
      expect(body['commandId'], 'command-1');
    });

    test('demote_mapsALastAdminConflictToAnAppException', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      dio.httpClientAdapter = _FakeHttpClientAdapter(
        (options) async => _jsonResponse({'code': 'membership.lastAdmin', 'message': 'debug only'}, 409),
      );
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');
      final api = HttpMembersApi(client);

      await expectLater(
        api.demote('household-1', 'member-2', commandId: 'command-1'),
        throwsA(isA<AppException>().having((e) => e.error.code, 'code', 'membership.lastAdmin')),
      );
    });
  });
}
