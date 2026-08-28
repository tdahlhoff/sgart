import 'package:dio/dio.dart';

import '../errors/app_error.dart';
import 'app_exception.dart';

/// Supplies the current access token for the bearer interceptor, or `null` when signed out.
typedef AccessTokenProvider = Future<String?> Function();

/// A [Dio]-backed HTTP client for the SGART backend: injects `Authorization: Bearer <token>` on
/// every request and maps a `{code,message,details}` error body to the client's [AppError] shape
/// (the REST error-mapping seam deferred from Story 1.3, wired here for the `/me` call).
///
/// Never puts a token in a query, path, or log — only the `Authorization` header carries it.
class AuthenticatedHttpClient {
  AuthenticatedHttpClient({required this._dio, required AccessTokenProvider accessTokenProvider}) {
    _dio.interceptors.add(InterceptorsWrapper(
      onRequest: (options, handler) async {
        final token = await accessTokenProvider();
        if (token != null) {
          options.headers['Authorization'] = 'Bearer $token';
        }
        handler.next(options);
      },
    ));
  }

  final Dio _dio;

  Future<Map<String, dynamic>> getJson(String path) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(path);
      return response.data ?? const {};
    } on DioException catch (exception) {
      throw AppException(_mapToAppError(exception));
    }
  }

  Future<List<dynamic>> getJsonList(String path) async {
    try {
      final response = await _dio.get<List<dynamic>>(path);
      return response.data ?? const [];
    } on DioException catch (exception) {
      throw AppException(_mapToAppError(exception));
    }
  }

  Future<Map<String, dynamic>> postJson(String path, Map<String, dynamic> body) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(path, data: body);
      return response.data ?? const {};
    } on DioException catch (exception) {
      throw AppException(_mapToAppError(exception));
    }
  }

  /// Sends a `PATCH` whose success is a `204 No Content` (a command — no domain body to read).
  /// Maps a `{code,message,details}` error body to [AppError] exactly as the other verbs do.
  Future<void> patchJson(String path, Map<String, dynamic> body) async {
    try {
      await _dio.patch<void>(path, data: body);
    } on DioException catch (exception) {
      throw AppException(_mapToAppError(exception));
    }
  }

  /// Sends a `PUT` whose success is a `204 No Content` (a command — no domain body to read). Maps a
  /// `{code,message,details}` error body to [AppError] exactly as the other verbs do.
  Future<void> putJson(String path, Map<String, dynamic> body) async {
    try {
      await _dio.put<void>(path, data: body);
    } on DioException catch (exception) {
      throw AppException(_mapToAppError(exception));
    }
  }

  /// Sends a `DELETE` (carrying the command envelope as its body) whose success is a `204 No
  /// Content` (a command — no domain body to read). Maps a `{code,message,details}` error body to
  /// [AppError] exactly as the other verbs do.
  Future<void> deleteJson(String path, Map<String, dynamic> body) async {
    try {
      await _dio.delete<void>(path, data: body);
    } on DioException catch (exception) {
      throw AppException(_mapToAppError(exception));
    }
  }

  AppError _mapToAppError(DioException exception) {
    final response = exception.response;
    final body = response?.data;
    if (body is Map<String, dynamic> && body['code'] is String) {
      final details = body['details'];
      return AppError(
        code: body['code'] as String,
        message: (body['message'] as String?) ?? exception.message ?? 'unknown error',
        details: details is Map<String, dynamic> ? details : const {},
      );
    }
    // A response reached us but carried no `{code}` body: preserve the HTTP status so callers can
    // tell a definitive auth rejection (401 — clear the session) from a server we could not reach.
    final statusCode = response?.statusCode;
    if (statusCode == 401) {
      return AppError(code: 'auth.unauthorized', message: exception.message ?? 'unauthorized');
    }
    if (statusCode != null) {
      return AppError(
        code: 'http.error',
        message: exception.message ?? 'unexpected HTTP status $statusCode',
        details: {'statusCode': statusCode},
      );
    }
    return AppError(code: 'network.unreachable', message: exception.message ?? 'unknown error');
  }
}
