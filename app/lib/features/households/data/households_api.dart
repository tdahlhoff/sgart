import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';
import '../../../shared/http/authenticated_http_client.dart';
import 'household_summary.dart';

/// The client's household routing/creation source — calls the backend's `GET`/`POST
/// /api/v1/households` slice (Story 1.6).
abstract interface class HouseholdsApi {
  Future<List<HouseholdSummary>> listMyHouseholds();

  /// Creates a household. [commandId] is the caller-supplied idempotency key for the create
  /// intent: a retry of the *same* intent must pass the *same* [commandId] so the backend
  /// converges on one household instead of creating duplicates (Story 1.6 Clarification 5).
  ///
  /// @return the new household's id (read-your-writes — the response carries it so the caller
  ///     can route straight in without waiting for the read model to catch up, AC3).
  Future<String> createHousehold(String name, {required String commandId});

  /// Renames the household to [name] (Story 1.7, AC3). [commandId] is the caller-supplied
  /// idempotency key for the rename intent, reused across retries (AD-8). A backend that rejects a
  /// non-Admin surfaces as an [AppException] carrying `household.renameNotPermitted`.
  Future<void> renameHousehold(String householdId, String name, {required String commandId});
}

class HttpHouseholdsApi implements HouseholdsApi {
  const HttpHouseholdsApi(this._client);

  final AuthenticatedHttpClient _client;

  @override
  Future<List<HouseholdSummary>> listMyHouseholds() async {
    final json = await _client.getJsonList('/api/v1/households');
    return json.map((entry) => HouseholdSummary.fromJson(entry as Map<String, dynamic>)).toList();
  }

  @override
  Future<String> createHousehold(String name, {required String commandId}) async {
    final json = await _client.postJson('/api/v1/households', {'name': name, 'commandId': commandId});
    final householdId = json['householdId'];
    if (householdId is! String) {
      throw const AppException(AppError(
        code: 'households.malformedResponse',
        message: 'POST /api/v1/households returned an unexpected shape',
      ));
    }
    return householdId;
  }

  @override
  Future<void> renameHousehold(String householdId, String name, {required String commandId}) {
    return _client.patchJson('/api/v1/households/$householdId', {'name': name, 'commandId': commandId});
  }
}
