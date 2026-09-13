import 'package:dio/dio.dart';

import '../errors/app_error.dart';
import 'app_exception.dart';

/// Supplies the current access token for the bearer interceptor, or `null` when signed out.
typedef AccessTokenProvider = Future<String?> Function();

/// Exchanges the stored refresh token for a fresh access token, returning whether it succeeded.
/// Supplied by callers that own a session (e.g. `AuthCubit.tryRefreshTokens`) — see
/// [AuthenticatedHttpClient.refreshTokens].
///
/// Must never throw — a failed refresh (missing/expired refresh token, network error) reports
/// itself by returning `false`. [AuthenticatedHttpClient._withRefreshRetry]'s no-loop guarantee
/// relies on this: an escaping exception would replace the original `auth.unauthorized` failure
/// instead of the caller seeing it propagate as-is.
typedef TokenRefresher = Future<bool> Function();

/// A [Dio]-backed HTTP client for the SGART backend: injects `Authorization: Bearer <token>` on
/// every request, maps a `{code,message,details}` error body to the client's [AppError] shape
/// (the REST error-mapping seam deferred from Story 1.3, wired here for the `/me` call), and
/// retries a request exactly once after a successful refresh when it 401s (see [refreshTokens]).
///
/// Never puts a token in a query, path, or log — only the `Authorization` header carries it.
class AuthenticatedHttpClient {
  AuthenticatedHttpClient({
    required this._dio,
    required AccessTokenProvider accessTokenProvider,
    this.refreshTokens,
  }) {
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

  /// Optional (nullable) — mirrors `AuthCubit.pushNotifications`'s existing "optional dependency"
  /// precedent, so call sites with no interest in refresh-and-retry (most existing API tests) need
  /// no changes. When present, a request that 401s is retried exactly once after a successful
  /// refresh (see [_withRefreshRetry]).
  final TokenRefresher? refreshTokens;

  Future<Map<String, dynamic>> getJson(String path) {
    return _withRefreshRetry(() => _getJson(path));
  }

  Future<List<dynamic>> getJsonList(String path) {
    return _withRefreshRetry(() => _getJsonList(path));
  }

  Future<Map<String, dynamic>> postJson(String path, Map<String, dynamic> body) {
    return _withRefreshRetry(() => _postJson(path, body));
  }

  /// Sends a `PATCH` whose success is a `204 No Content` (a command — no domain body to read).
  /// Maps a `{code,message,details}` error body to [AppError] exactly as the other verbs do.
  Future<void> patchJson(String path, Map<String, dynamic> body) {
    return _withRefreshRetry(() => _patchJson(path, body));
  }

  /// Sends a `PUT` whose success is a `204 No Content` (a command — no domain body to read). Maps a
  /// `{code,message,details}` error body to [AppError] exactly as the other verbs do.
  Future<void> putJson(String path, Map<String, dynamic> body) {
    return _withRefreshRetry(() => _putJson(path, body));
  }

  /// Sends a `DELETE` (carrying the command envelope as its body) whose success is a `204 No
  /// Content` (a command — no domain body to read). Maps a `{code,message,details}` error body to
  /// [AppError] exactly as the other verbs do.
  Future<void> deleteJson(String path, Map<String, dynamic> body) {
    return _withRefreshRetry(() => _deleteJson(path, body));
  }

  Future<Map<String, dynamic>> _getJson(String path) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(path);
      return response.data ?? const {};
    } on DioException catch (exception) {
      throw AppException(_mapToAppError(exception));
    }
  }

  Future<List<dynamic>> _getJsonList(String path) async {
    try {
      final response = await _dio.get<List<dynamic>>(path);
      return response.data ?? const [];
    } on DioException catch (exception) {
      throw AppException(_mapToAppError(exception));
    }
  }

  Future<Map<String, dynamic>> _postJson(String path, Map<String, dynamic> body) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(path, data: body);
      return response.data ?? const {};
    } on DioException catch (exception) {
      throw AppException(_mapToAppError(exception));
    }
  }

  Future<void> _patchJson(String path, Map<String, dynamic> body) async {
    try {
      await _dio.patch<void>(path, data: body);
    } on DioException catch (exception) {
      throw AppException(_mapToAppError(exception));
    }
  }

  Future<void> _putJson(String path, Map<String, dynamic> body) async {
    try {
      await _dio.put<void>(path, data: body);
    } on DioException catch (exception) {
      throw AppException(_mapToAppError(exception));
    }
  }

  Future<void> _deleteJson(String path, Map<String, dynamic> body) async {
    try {
      await _dio.delete<void>(path, data: body);
    } on DioException catch (exception) {
      throw AppException(_mapToAppError(exception));
    }
  }

  /// Retries [send] exactly once after a successful [refreshTokens] when it fails with
  /// `auth.unauthorized` — centralizes the refresh-and-retry that used to live only in
  /// `AuthCubit._loadCallerIdentity` for the `/me` call, so every authenticated call benefits
  /// (Story: centralize 401 refresh-and-retry). A second failure — the refresh itself fails, or
  /// the retried request still 401s — propagates the original [AppException] as-is; no loop.
  Future<T> _withRefreshRetry<T>(Future<T> Function() send) async {
    try {
      return await send();
    } on AppException catch (exception) {
      if (exception.error.code == 'auth.unauthorized' && refreshTokens != null && await refreshTokens!()) {
        return send();
      }
      rethrow;
    }
  }

  /// Opens a raw `text/event-stream` GET (Story 4.4's live-sync SSE stream) — bypasses the JSON
  /// helpers above since an SSE body is line-oriented text streamed indefinitely, not a single
  /// JSON response. Left un-mapped to [AppException]: the SSE client (not this shared transport)
  /// decides reconnect-vs-terminal from the raw [Response]'s status code — a `403` must never
  /// retry, unlike every JSON call's uniform error handling. `validateStatus` always accepts so a
  /// 4xx/5xx rejection reaches the caller as a readable [Response] instead of throwing a
  /// [DioException] before the status code can be inspected (Review P1). Still goes through the
  /// same bearer interceptor as every other request — the token is never duplicated or
  /// special-cased for streaming.
  Future<Response<ResponseBody>> openEventStream(String path) {
    return _dio.get<ResponseBody>(
      path,
      options: Options(
        responseType: ResponseType.stream,
        headers: {'Accept': 'text/event-stream'},
        validateStatus: (_) => true,
      ),
    );
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
