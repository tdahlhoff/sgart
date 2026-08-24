import 'package:sgart/features/households/data/active_household_store.dart';
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

  Object? renameErrorToThrow;
  String? lastRenamedHouseholdId;
  String? lastRenamedName;
  final List<String> renameCommandIds = [];
  int renameCallCount = 0;

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

  @override
  Future<void> renameHousehold(String householdId, String name, {required String commandId}) async {
    lastRenamedHouseholdId = householdId;
    lastRenamedName = name;
    renameCommandIds.add(commandId);
    renameCallCount++;
    if (renameErrorToThrow != null) throw renameErrorToThrow!;
  }
}

/// In-memory [ActiveHouseholdStore] — no real device storage in tests (CLAUDE.md §6).
class FakeActiveHouseholdStore implements ActiveHouseholdStore {
  FakeActiveHouseholdStore({this.activeId});

  String? activeId;
  bool cleared = false;
  final List<String> writes = [];

  @override
  Future<String?> readActive() async => activeId;

  @override
  Future<void> writeActive(String householdId) async {
    activeId = householdId;
    writes.add(householdId);
  }

  @override
  Future<void> clear() async {
    activeId = null;
    cleared = true;
  }
}
