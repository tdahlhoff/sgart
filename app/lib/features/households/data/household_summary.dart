import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';

/// A household as seen by the caller — id + display name, the shape first-run routing needs
/// (Story 1.6, AC2).
class HouseholdSummary {
  const HouseholdSummary({required this.householdId, required this.name});

  final String householdId;
  final String name;

  /// Fails fast with a mapped [AppException] rather than a raw `TypeError` when the response is
  /// missing a field or has an unexpected shape, so callers resolve it through [AppError.code].
  factory HouseholdSummary.fromJson(Map<String, dynamic> json) {
    final householdId = json['householdId'];
    final name = json['name'];
    if (householdId is! String || name is! String) {
      throw const AppException(AppError(
        code: 'households.malformedResponse',
        message: 'GET /api/v1/households returned an unexpected shape',
      ));
    }
    return HouseholdSummary(householdId: householdId, name: name);
  }

  @override
  bool operator ==(Object other) =>
      other is HouseholdSummary && other.householdId == householdId && other.name == name;

  @override
  int get hashCode => Object.hash(householdId, name);
}
