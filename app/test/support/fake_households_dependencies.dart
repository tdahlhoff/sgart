import 'package:sgart/features/households/data/household_summary.dart';
import 'package:sgart/features/households/data/households_api.dart';

/// Test double for [HouseholdsApi] — no real network in tests (CLAUDE.md §6).
class FakeHouseholdsApi implements HouseholdsApi {
  List<HouseholdSummary>? householdsToReturn;
  String? createdHouseholdIdToReturn;
  Object? listErrorToThrow;
  Object? createErrorToThrow;
  String? lastCreatedName;
  String? lastCommandId;
  int createCallCount = 0;

  @override
  Future<List<HouseholdSummary>> listMyHouseholds() async {
    if (listErrorToThrow != null) throw listErrorToThrow!;
    return householdsToReturn ?? const [];
  }

  @override
  Future<String> createHousehold(String name, {required String commandId}) async {
    lastCreatedName = name;
    lastCommandId = commandId;
    createCallCount++;
    if (createErrorToThrow != null) throw createErrorToThrow!;
    return createdHouseholdIdToReturn!;
  }
}
