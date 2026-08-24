import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import 'store_chain.dart';
import 'stores_api.dart';

/// Persists the fetched store-chain reference list on-device so chain matching works
/// **offline after its first cached load** (Story 1.8, AC2, FR3). An interface so [StoresCubit]
/// depends on an abstraction and tests inject an in-memory fake — no real device storage in a unit
/// test (CLAUDE.md §6), mirroring [ActiveHouseholdStore].
///
/// Deliberately a simple cached-JSON store: the durable offline **command queue** (Hive/SQLite) is
/// Epic 5 and is not built here (YAGNI). No personal data — chain brand names, not people.
abstract interface class StoreChainReferenceCache {
  /// The reference list, **fetched from [api] exactly once** and then served from the on-device
  /// cache on every later load — so chain matching works offline after that first cached load
  /// (AC2, FR3). Only a first-ever load with no cache and no network propagates the error; the
  /// reference data is effectively static (a seeded chain list), so not re-fetching it is correct,
  /// not a staleness bug (YAGNI on refresh — a cache-busting refresh is not an MVP need).
  Future<List<StoreChain>> load(StoresApi api);
}

/// [StoreChainReferenceCache] backed by `shared_preferences`. The only place the plugin is touched,
/// so the cubit and its tests never depend on it directly.
class SharedPreferencesStoreChainReferenceCache implements StoreChainReferenceCache {
  const SharedPreferencesStoreChainReferenceCache();

  static const String _referenceListKey = 'sgart.storeChainReference';

  @override
  Future<List<StoreChain>> load(StoresApi api) async {
    // Serve the cache if we already have one — no network on subsequent loads (offline-after-first-
    // load, AC2). Only the first-ever load hits the api; a failure there (offline, no cache)
    // propagates so the caller can decide (the cubit degrades to "matching unavailable").
    final cached = await _readCached();
    if (cached != null) {
      return cached;
    }
    final chains = await api.listStoreChains();
    await _write(chains);
    return chains;
  }

  Future<void> _write(List<StoreChain> chains) async {
    final preferences = await SharedPreferences.getInstance();
    final encoded = jsonEncode(chains.map((chain) => chain.toJson()).toList());
    await preferences.setString(_referenceListKey, encoded);
  }

  Future<List<StoreChain>?> _readCached() async {
    final preferences = await SharedPreferences.getInstance();
    final encoded = preferences.getString(_referenceListKey);
    if (encoded == null) {
      return null;
    }
    try {
      final decoded = jsonDecode(encoded) as List<dynamic>;
      final chains =
          decoded.map((entry) => StoreChain.fromJson(entry as Map<String, dynamic>)).toList();
      // An empty cached list is treated as "no cache": the first fetch may have hit an empty backend
      // (e.g. before the seed migration ran), and pinning emptiness would disable matching forever.
      return chains.isEmpty ? null : chains;
    } on Object {
      // A malformed or shape-changed cache entry (e.g. a later StoreChain field change breaking an
      // existing cache on upgrade) must not permanently disable matching — treat it as no cache so
      // load() re-fetches from the api and overwrites it.
      return null;
    }
  }
}
