import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/stores/data/store_chain.dart';
import 'package:sgart/features/stores/data/store_summary.dart';
import 'package:sgart/features/stores/presentation/stores_cubit.dart';
import 'package:sgart/features/stores/presentation/stores_state.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_stores_dependencies.dart';

void main() {
  group('StoresCubit', () {
    late FakeStoresApi storesApi;
    late FakeStoreChainReferenceCache referenceCache;

    const chains = [
      StoreChain(chainId: 'id-edeka', name: 'Edeka'),
      StoreChain(chainId: 'id-aldi', name: 'Aldi'),
    ];

    setUp(() {
      storesApi = FakeStoresApi();
      referenceCache = FakeStoreChainReferenceCache(chains: chains);
    });

    StoresCubit buildCubit() => StoresCubit(
          storesApi: storesApi,
          referenceCache: referenceCache,
          householdId: 'household-1',
        );

    test('bootstrap_loadsActiveStoresAndTheReferenceList', () async {
      storesApi.storesToReturn = const [StoreSummary(storeId: 's1', name: 'Edeka', chainId: 'id-edeka')];
      final cubit = buildCubit();

      await cubit.bootstrap();

      expect(cubit.state.status, StoresStatus.ready);
      expect(cubit.state.stores, hasLength(1));
      expect(cubit.state.chains, chains);
      await cubit.close();
    });

    test('bootstrap_emitsFailureWhenTheStoreListLoadFails', () async {
      storesApi.listStoresError =
          const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      final cubit = buildCubit();

      await cubit.bootstrap();

      expect(cubit.state.status, StoresStatus.failure);
      expect(cubit.state.loadError?.code, 'network.unreachable');
      await cubit.close();
    });

    test('bootstrap_staysReadyWithNoChainsWhenTheReferenceLoadFails', () async {
      referenceCache.errorToThrow = Exception('offline first load, no cache');
      final cubit = buildCubit();

      await cubit.bootstrap();

      expect(cubit.state.status, StoresStatus.ready);
      expect(cubit.state.chains, isEmpty);
      await cubit.close();
    });

    test('addStore_withNoChainOptimisticallyAppendsAnUnlinkedStore', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.addStore('  Wochenmarkt  ');

      expect(storesApi.lastAddedName, 'Wochenmarkt');
      expect(storesApi.lastAddedChainId, isNull);
      expect(cubit.state.stores.map((store) => store.name), contains('Wochenmarkt'));
      await cubit.close();
    });

    test('addStore_sendsTheAcceptedChainWhenOneWasSuggested', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();

      cubit.onNameChanged('Edeka Schiedemann');
      await cubit.addStore('Edeka Schiedemann');

      expect(storesApi.lastAddedChainId, 'id-edeka');
      await cubit.close();
    });

    test('addStore_sendsNoChainWhenTheSuggestionWasCleared', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();

      cubit.onNameChanged('Edeka Schiedemann');
      cubit.clearSuggestion();
      await cubit.addStore('Edeka Schiedemann');

      expect(storesApi.lastAddedChainId, isNull);
      await cubit.close();
    });

    test('addStore_surfacesADuplicateAsAnInlineErrorWithoutLeavingReady', () async {
      storesApi.addError =
          const AppException(AppError(code: 'store.duplicateName', message: 'debug'));
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.addStore('Edeka');

      expect(cubit.state.status, StoresStatus.ready); // no screen teardown
      expect(cubit.state.actionError?.code, 'store.duplicateName');
      await cubit.close();
    });

    test('archiveStore_removesTheStoreFromTheActiveList', () async {
      storesApi.storesToReturn = const [
        StoreSummary(storeId: 's1', name: 'Edeka'),
        StoreSummary(storeId: 's2', name: 'Rewe'),
      ];
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.archiveStore('s1');

      expect(storesApi.lastArchivedStoreId, 's1');
      expect(cubit.state.stores.map((store) => store.storeId), ['s2']);
      await cubit.close();
    });

    test('addStore_reusesOneCommandIdAcrossRetriesOfTheSameName', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();
      storesApi.addError =
          const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      await cubit.addStore('Edeka');
      storesApi.addError = null;
      await cubit.addStore('Edeka');

      expect(storesApi.addCommandIds, hasLength(2));
      expect(storesApi.addCommandIds.first, storesApi.addCommandIds.last);
      await cubit.close();
    });

    test('addStore_usesAFreshCommandIdWhenTheNameChangesBetweenAttempts', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();
      storesApi.addError =
          const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      await cubit.addStore('Edeka');
      await cubit.addStore('Rewe');

      expect(storesApi.addCommandIds, hasLength(2));
      expect(storesApi.addCommandIds.first, isNot(storesApi.addCommandIds.last));
      await cubit.close();
    });

    test('addStore_regeneratesTheCommandIdAfterASuccessfulAddSoTheNextStoreIsNotDeduped', () async {
      // Regression (review 2026-08-24): a successful add must not leave the spent command id in place
      // — the backend dedupes a reused command id per stream as a silent no-op, dropping the store.
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.addStore('Edeka');
      await cubit.addStore('Rewe');

      expect(storesApi.addCommandIds, hasLength(2));
      expect(storesApi.addCommandIds.first, isNot(storesApi.addCommandIds.last));
      expect(cubit.state.stores.map((store) => store.name), containsAll(['Edeka', 'Rewe']));
      await cubit.close();
    });

    test('addStore_reusesTheStoreIdAcrossRetriesOfTheSameName', () async {
      // The store id is minted per intent (like the command id), so a retry reuses it and the
      // optimistically-rendered id matches the one the server persisted.
      final cubit = buildCubit();
      await cubit.bootstrap();
      storesApi.addError =
          const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      await cubit.addStore('Edeka');
      storesApi.addError = null;
      await cubit.addStore('Edeka');

      expect(storesApi.addStoreIds, hasLength(2));
      expect(storesApi.addStoreIds.first, storesApi.addStoreIds.last);
      // The optimistic row carries that same reused id.
      expect(cubit.state.stores.single.storeId, storesApi.addStoreIds.last);
      await cubit.close();
    });

    test('addStore_ignoresABlankName', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.addStore('   ');

      expect(storesApi.addCallCount, 0);
      expect(cubit.state.stores, isEmpty);
      await cubit.close();
    });

    test('selectChain_overridesTheAutoMatchedSuggestion', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();

      cubit.onNameChanged('Edeka Schiedemann'); // auto-matches Edeka
      cubit.selectChain(const StoreChain(chainId: 'id-aldi', name: 'Aldi'));
      await cubit.addStore('Edeka Schiedemann');

      expect(storesApi.lastAddedChainId, 'id-aldi');
      await cubit.close();
    });

    test('doesNotEmitAfterClose', () async {
      final cubit = buildCubit();
      await cubit.close();

      // Must not throw despite the cubit being closed (every emit is isClosed-guarded).
      await cubit.bootstrap();
    });
  });
}
