import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/lists/data/item.dart';
import 'package:sgart/features/stores/data/store_summary.dart';
import 'package:sgart/features/trips/data/trips_api.dart';
import 'package:sgart/features/trips/presentation/trip_cubit.dart';
import 'package:sgart/features/trips/presentation/trip_state.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_items_dependencies.dart';
import '../../../support/fake_stores_dependencies.dart';
import '../../../support/fake_trips_dependencies.dart';

void main() {
  group('TripCubit', () {
    late FakeTripsApi tripsApi;
    late FakeItemsApi itemsApi;
    late FakeStoresApi storesApi;

    setUp(() {
      tripsApi = FakeTripsApi();
      itemsApi = FakeItemsApi();
      storesApi = FakeStoresApi();
    });

    TripCubit buildCubit() => TripCubit(
          tripsApi: tripsApi,
          itemsApi: itemsApi,
          storesApi: storesApi,
          householdId: 'household-1',
          listId: 'list-1',
        );

    const edeka = StoreSummary(storeId: 'store-edeka', name: 'Edeka');
    const netto = StoreSummary(storeId: 'store-netto', name: 'Netto');

    test('bootstrap_groupsItemsByTripStore_andBucketsUnassignedByFallback', () async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka', 'store-netto'],
        items: [
          Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'store-edeka'),
          Item(itemId: 'i2', name: 'Brot', note: null, amount: '1', unit: 'PIECE', storeId: null),
          Item(itemId: 'i3', name: 'Käse', note: null, amount: '1', unit: 'PIECE', storeId: 'store-archived'),
          Item(itemId: 'i4', name: 'Wurst', note: null, amount: '1', unit: 'PIECE', storeId: 'store-not-in-trip'),
        ],
      );
      storesApi.storesToReturn = const [edeka, netto];
      final cubit = buildCubit();

      await cubit.bootstrap();

      expect(cubit.state.status, TripStatus.ready);
      expect(cubit.state.groups, [
        const TripStoreGroup(storeId: 'store-edeka', items: [
          Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'store-edeka'),
        ]),
        const TripStoreGroup(storeId: 'store-netto', items: []),
      ]);
      expect(cubit.state.unassignedItems.map((item) => item.itemId), ['i2', 'i3', 'i4']);
      await cubit.close();
    });

    test('bootstrap_emitsFailureWhenTheLoadFails', () async {
      tripsApi.activeTripError = const AppException(AppError(code: 'trip.notFound', message: 'debug'));
      final cubit = buildCubit();

      await cubit.bootstrap();

      expect(cubit.state.status, TripStatus.failure);
      expect(cubit.state.loadError?.code, 'trip.notFound');
      await cubit.close();
    });

    test('reroute_optimisticallyMovesTheItem_andSendsTheCommand', () async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka', 'store-netto'],
        items: [Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'store-edeka')],
      );
      storesApi.storesToReturn = const [edeka, netto];
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.reroute('i1', 'store-netto');

      expect(cubit.state.items.first.storeId, 'store-netto');
      expect(itemsApi.lastReroutedItemId, 'i1');
      expect(itemsApi.lastReroutedStoreId, 'store-netto');
      expect(itemsApi.rerouteCallCount, 1);
      await cubit.close();
    });

    test('reroute_preservesTheItemStatus', () async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka', 'store-netto'],
        items: [
          Item(
              itemId: 'i1',
              name: 'Milch',
              note: null,
              amount: '1',
              unit: 'PIECE',
              storeId: 'store-edeka',
              status: ItemStatus.done),
        ],
      );
      storesApi.storesToReturn = const [edeka, netto];
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.reroute('i1', 'store-netto');

      expect(cubit.state.items.first.storeId, 'store-netto');
      expect(cubit.state.items.first.status, ItemStatus.done);
      await cubit.close();
    });

    test('reroute_revertsOnFailure_andSurfacesActionError', () async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka', 'store-netto'],
        items: [Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'store-edeka')],
      );
      storesApi.storesToReturn = const [edeka, netto];
      itemsApi.rerouteError = const AppException(AppError(code: 'item.notDuringTrip', message: 'debug'));
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.reroute('i1', 'store-netto');

      expect(cubit.state.items.first.storeId, 'store-edeka');
      expect(cubit.state.actionError?.code, 'item.notDuringTrip');
      await cubit.close();
    });

    test('reroute_freshensTheIntentOnADifferentReroute_andAfterSuccess', () async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka', 'store-netto'],
        items: [Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'store-edeka')],
      );
      storesApi.storesToReturn = const [edeka, netto];
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.reroute('i1', 'store-netto');
      await cubit.reroute('i1', 'store-edeka');

      expect(itemsApi.rerouteCommandIds.toSet().length, 2);
      await cubit.close();
    });

    test('reroute_ignoresAReentrantCallWhileSubmitting', () async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka'],
        items: [Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'store-edeka')],
      );
      storesApi.storesToReturn = const [edeka];
      final cubit = buildCubit();
      await cubit.bootstrap();

      final first = cubit.reroute('i1', 'store-edeka');
      final second = cubit.reroute('i1', 'store-edeka');
      await Future.wait([first, second]);

      expect(itemsApi.rerouteCallCount, 1);
      await cubit.close();
    });

    test('addStoreToTrip_appendsTheGroup_andSendsTheCommand', () async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka'],
        items: [],
      );
      storesApi.storesToReturn = const [edeka];
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.addStoreToTrip(netto);

      expect(cubit.state.storeIds, ['store-edeka', 'store-netto']);
      expect(cubit.state.groups.map((group) => group.storeId), ['store-edeka', 'store-netto']);
      expect(tripsApi.lastAddedStoreId, 'store-netto');
      await cubit.close();
    });

    test('anEmptyTripStoreGroupStillRenders', () async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka'],
        items: [],
      );
      storesApi.storesToReturn = const [edeka];
      final cubit = buildCubit();

      await cubit.bootstrap();

      expect(cubit.state.groups, [const TripStoreGroup(storeId: 'store-edeka', items: [])]);
      await cubit.close();
    });

    test('doneCount_andTotalCount_computeAcrossStatuses', () async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka'],
        items: [
          Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'store-edeka', status: ItemStatus.open),
          Item(itemId: 'i2', name: 'Brot', note: null, amount: '1', unit: 'PIECE', storeId: 'store-edeka', status: ItemStatus.done),
          Item(
              itemId: 'i3',
              name: 'Käse',
              note: null,
              amount: '1',
              unit: 'PIECE',
              storeId: 'store-edeka',
              status: ItemStatus.postponed),
        ],
      );
      storesApi.storesToReturn = const [edeka];
      final cubit = buildCubit();

      await cubit.bootstrap();

      expect(cubit.state.doneCount, 1);
      expect(cubit.state.totalCount, 3);
      await cubit.close();
    });

    test('checkOff_optimisticallyFlipsToDone_andSendsTheCommand', () async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka'],
        items: [Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'store-edeka')],
      );
      storesApi.storesToReturn = const [edeka];
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.checkOff('i1');

      expect(cubit.state.items.first.status, ItemStatus.done);
      expect(itemsApi.lastCheckedOffItemId, 'i1');
      expect(itemsApi.checkOffCallCount, 1);
      await cubit.close();
    });

    test('checkOff_revertsOnFailure_andSurfacesActionError', () async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka'],
        items: [Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'store-edeka')],
      );
      storesApi.storesToReturn = const [edeka];
      itemsApi.checkOffError = const AppException(AppError(code: 'item.notDuringTrip', message: 'debug'));
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.checkOff('i1');

      expect(cubit.state.items.first.status, ItemStatus.open);
      expect(cubit.state.actionError?.code, 'item.notDuringTrip');
      await cubit.close();
    });

    test('checkOff_ignoresAReentrantCallWhileSubmitting', () async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka'],
        items: [Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'store-edeka')],
      );
      storesApi.storesToReturn = const [edeka];
      final cubit = buildCubit();
      await cubit.bootstrap();

      final first = cubit.checkOff('i1');
      final second = cubit.checkOff('i1');
      await Future.wait([first, second]);

      expect(itemsApi.checkOffCallCount, 1);
      await cubit.close();
    });

    test('uncheck_optimisticallyFlipsToOpen_andSendsTheCommand', () async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka'],
        items: [
          Item(
              itemId: 'i1',
              name: 'Milch',
              note: null,
              amount: '1',
              unit: 'PIECE',
              storeId: 'store-edeka',
              status: ItemStatus.done),
        ],
      );
      storesApi.storesToReturn = const [edeka];
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.uncheck('i1');

      expect(cubit.state.items.first.status, ItemStatus.open);
      expect(itemsApi.lastUncheckedItemId, 'i1');
      await cubit.close();
    });

    test('postponeInPlace_optimisticallyFlipsToPostponed_andSendsTheCommand', () async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka'],
        items: [Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'store-edeka')],
      );
      storesApi.storesToReturn = const [edeka];
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.postponeInPlace('i1');

      expect(cubit.state.items.first.status, ItemStatus.postponed);
      expect(itemsApi.lastPostponedItemId, 'i1');
      await cubit.close();
    });

    test('postponeToList_optimisticallyRemovesTheItem_andSendsTheCommand', () async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka'],
        items: [Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'store-edeka')],
      );
      storesApi.storesToReturn = const [edeka];
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.postponeToList('i1', 'list-target');

      expect(cubit.state.items.where((i) => i.itemId == 'i1'), isEmpty);
      expect(itemsApi.lastPostponedToListItemId, 'i1');
      expect(itemsApi.lastPostponedTargetListId, 'list-target');
      await cubit.close();
    });

    test('postponeToList_revertsOnFailure_andSurfacesActionError', () async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka'],
        items: [Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'store-edeka')],
      );
      storesApi.storesToReturn = const [edeka];
      itemsApi.postponeToListError = const AppException(AppError(code: 'list.moveTargetNotOpen', message: 'debug'));
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.postponeToList('i1', 'list-target');

      expect(cubit.state.items.where((i) => i.itemId == 'i1'), isNotEmpty);
      expect(cubit.state.actionError?.code, 'list.moveTargetNotOpen');
      await cubit.close();
    });
  });
}
