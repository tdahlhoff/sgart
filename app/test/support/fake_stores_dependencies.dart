import 'package:sgart/features/stores/data/store_chain.dart';
import 'package:sgart/features/stores/data/store_chain_reference_cache.dart';
import 'package:sgart/features/stores/data/store_summary.dart';
import 'package:sgart/features/stores/data/stores_api.dart';

/// Test double for [StoresApi] — no real network in tests (CLAUDE.md §6). Mirrors
/// `FakeHouseholdsApi`.
class FakeStoresApi implements StoresApi {
  List<StoreSummary> storesToReturn = const [];
  List<StoreChain> chainsToReturn = const [];
  Object? listStoresError;
  Object? listChainsError;
  Object? addError;
  Object? archiveError;

  String? lastAddedName;
  String? lastAddedChainId;
  String? lastAddedStoreId;
  final List<String> addCommandIds = [];
  final List<String> addStoreIds = [];
  int addCallCount = 0;

  String? lastArchivedStoreId;
  final List<String> archiveCommandIds = [];
  int archiveCallCount = 0;

  @override
  Future<List<StoreSummary>> listStores(String householdId) async {
    if (listStoresError != null) throw listStoresError!;
    return storesToReturn;
  }

  @override
  Future<void> addStore(
    String householdId,
    String name, {
    required String storeId,
    String? chainId,
    required String commandId,
  }) async {
    lastAddedName = name;
    lastAddedStoreId = storeId;
    lastAddedChainId = chainId;
    addCommandIds.add(commandId);
    addStoreIds.add(storeId);
    addCallCount++;
    if (addError != null) throw addError!;
  }

  @override
  Future<void> archiveStore(String householdId, String storeId, {required String commandId}) async {
    lastArchivedStoreId = storeId;
    archiveCommandIds.add(commandId);
    archiveCallCount++;
    if (archiveError != null) throw archiveError!;
  }

  @override
  Future<List<StoreChain>> listStoreChains() async {
    if (listChainsError != null) throw listChainsError!;
    return chainsToReturn;
  }
}

/// In-memory [StoreChainReferenceCache] — serves a preset list, or fails on demand, with no real
/// device storage (CLAUDE.md §6).
class FakeStoreChainReferenceCache implements StoreChainReferenceCache {
  FakeStoreChainReferenceCache({this.chains = const [], this.errorToThrow});

  List<StoreChain> chains;
  Object? errorToThrow;
  int loadCallCount = 0;

  @override
  Future<List<StoreChain>> load(StoresApi api) async {
    loadCallCount++;
    if (errorToThrow != null) throw errorToThrow!;
    return chains;
  }
}
