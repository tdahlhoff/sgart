import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';

/// One entry in the cached store-chain reference list (Story 1.8, AC2) — an id and a display name.
/// The client matches a typed store name against these entries entirely client-side (never
/// server-decided) and stores only the accepted [chainId] on a store; the name is always resolved
/// from this reference list (DRY).
class StoreChain {
  const StoreChain({required this.chainId, required this.name});

  final String chainId;
  final String name;

  /// Fails fast with a mapped [AppException] on an unexpected shape so callers resolve it through
  /// [AppError.code] instead of a raw `TypeError`.
  factory StoreChain.fromJson(Map<String, dynamic> json) {
    final chainId = json['chainId'];
    final name = json['name'];
    if (chainId is! String || name is! String) {
      throw const AppException(AppError(
        code: 'stores.malformedResponse',
        message: 'GET /api/v1/store-chains returned an unexpected shape',
      ));
    }
    return StoreChain(chainId: chainId, name: name);
  }

  Map<String, dynamic> toJson() => {'chainId': chainId, 'name': name};

  @override
  bool operator ==(Object other) =>
      other is StoreChain && other.chainId == chainId && other.name == name;

  @override
  int get hashCode => Object.hash(chainId, name);
}
