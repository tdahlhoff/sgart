import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/stores/data/store_chain.dart';
import 'package:sgart/features/stores/data/store_chain_reference_cache.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../../support/fake_stores_dependencies.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('SharedPreferencesStoreChainReferenceCache', () {
    const cache = SharedPreferencesStoreChainReferenceCache();
    const chains = [
      StoreChain(chainId: 'id-edeka', name: 'Edeka'),
      StoreChain(chainId: 'id-aldi', name: 'Aldi'),
    ];

    setUp(() => SharedPreferences.setMockInitialValues({}));

    test('fetchesFromTheApiOnFirstLoadAndCachesTheResult', () async {
      final api = FakeStoresApi()..chainsToReturn = chains;

      final result = await cache.load(api);

      expect(result, chains);
    });

    test('servesFromCacheOnSubsequentLoadsWithoutCallingTheApiAgain', () async {
      final api = FakeStoresApi()..chainsToReturn = chains;
      await cache.load(api); // first load populates the cache

      // A second load with the api now failing must still return the cached list (offline-after-
      // first-load, AC2) — proving it did not re-hit the network.
      api.listChainsError = Exception('offline');
      final result = await cache.load(api);

      expect(result, chains);
    });

    test('propagatesTheErrorOnAFirstEverLoadWithNoCacheAndNoNetwork', () async {
      final api = FakeStoresApi()..listChainsError = Exception('offline');

      expect(() => cache.load(api), throwsA(isA<Exception>()));
    });
  });
}
