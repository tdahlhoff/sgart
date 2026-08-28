import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/trips/data/trips_api.dart';
import 'package:sgart/shared/http/app_exception.dart';
import 'package:sgart/shared/http/authenticated_http_client.dart';

/// Fakes Dio's transport so tests never touch a real socket (CLAUDE.md §6 — isolate external
/// systems). Mirrors `authenticated_http_client_test.dart`.
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
  group('HttpTripsApi', () {
    test('startTrip_postsTheCorrectPathAndBodyShape', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      final adapter = _FakeHttpClientAdapter((options) async => _jsonResponse(const {}, 201));
      dio.httpClientAdapter = adapter;
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');
      final api = HttpTripsApi(client);

      await api.startTrip(
        'household-1',
        'list-1',
        tripId: 'trip-1',
        storeIds: ['store-1', 'store-2'],
        commandId: 'command-1',
      );

      final request = adapter.lastRequest!;
      expect(request.path, '/api/v1/households/household-1/lists/list-1/trips');
      expect(request.method, 'POST');
      final body = request.data as Map<String, dynamic>;
      expect(body['tripId'], 'trip-1');
      expect(body['storeIds'], ['store-1', 'store-2']);
      expect(body['commandId'], 'command-1');
    });

    test('startTrip_mapsAServerErrorToAnAppException', () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://backend.example.test'));
      dio.httpClientAdapter = _FakeHttpClientAdapter(
        (options) async => _jsonResponse({'code': 'trip.storeSelectionRequired', 'message': 'debug only'}, 400),
      );
      final client = AuthenticatedHttpClient(dio: dio, accessTokenProvider: () async => 'token');
      final api = HttpTripsApi(client);

      await expectLater(
        api.startTrip('household-1', 'list-1', tripId: 'trip-1', storeIds: const [], commandId: 'command-1'),
        throwsA(isA<AppException>().having((e) => e.error.code, 'code', 'trip.storeSelectionRequired')),
      );
    });
  });
}
