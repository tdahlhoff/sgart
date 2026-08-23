import '../errors/app_error.dart';

/// Thrown by [AuthenticatedHttpClient] whenever a request fails, carrying the mapped [AppError]
/// so callers resolve user-facing copy through [AppError.code] instead of a raw exception.
class AppException implements Exception {
  const AppException(this.error);

  final AppError error;

  @override
  String toString() => 'AppException(${error.code})';
}
