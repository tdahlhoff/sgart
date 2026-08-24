import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';

/// A store as seen by the caller — id, display name, and the optional accepted chain id (Story 1.8,
/// AC1/AC2). `chainId` is `null` for an unlinked store; the client resolves a present id to a
/// display name from its cached reference list (single source of chain names, DRY). Mirrors
/// `HouseholdSummary`.
class StoreSummary {
  const StoreSummary({required this.storeId, required this.name, this.chainId});

  final String storeId;
  final String name;
  final String? chainId;

  /// Fails fast with a mapped [AppException] rather than a raw `TypeError` when the response is
  /// missing a field or has an unexpected shape, so callers resolve it through [AppError.code].
  /// A missing/null `chainId` is valid (an unlinked store) — only a non-string, non-null value is
  /// a malformed shape.
  factory StoreSummary.fromJson(Map<String, dynamic> json) {
    final storeId = json['storeId'];
    final name = json['name'];
    final chainId = json['chainId'];
    if (storeId is! String || name is! String || (chainId != null && chainId is! String)) {
      throw const AppException(AppError(
        code: 'stores.malformedResponse',
        message: 'GET stores returned an unexpected shape',
      ));
    }
    return StoreSummary(storeId: storeId, name: name, chainId: chainId as String?);
  }

  @override
  bool operator ==(Object other) =>
      other is StoreSummary &&
      other.storeId == storeId &&
      other.name == name &&
      other.chainId == chainId;

  @override
  int get hashCode => Object.hash(storeId, name, chainId);
}
