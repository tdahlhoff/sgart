import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/lists/data/item.dart';
import 'package:sgart/features/lists/data/items_api.dart';
import 'package:sgart/features/lists/data/shopping_lists_api.dart';
import 'package:sgart/features/stores/data/store_chain_reference_cache.dart';
import 'package:sgart/features/stores/data/store_summary.dart';
import 'package:sgart/features/stores/data/stores_api.dart';
import 'package:sgart/features/trips/data/trips_api.dart';
import 'package:sgart/features/trips/presentation/trip_cubit.dart';
import 'package:sgart/features/trips/presentation/trip_screen.dart';

import '../../../support/fake_items_dependencies.dart';
import '../../../support/fake_shopping_lists_dependencies.dart';
import '../../../support/fake_stores_dependencies.dart';
import '../../../support/fake_trips_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('TripScreen', () {
    late FakeTripsApi tripsApi;
    late FakeItemsApi itemsApi;
    late FakeStoresApi storesApi;
    late FakeStoreChainReferenceCache referenceCache;
    late FakeShoppingListsApi shoppingListsApi;

    setUp(() {
      tripsApi = FakeTripsApi();
      itemsApi = FakeItemsApi();
      storesApi = FakeStoresApi();
      referenceCache = FakeStoreChainReferenceCache();
      shoppingListsApi = FakeShoppingListsApi();
    });

    const edeka = StoreSummary(storeId: 'store-edeka', name: 'Edeka');
    const netto = StoreSummary(storeId: 'store-netto', name: 'Netto');

    Widget buildSubject() => wrapForTesting(
          RepositoryProvider<ShoppingListsApi>.value(
            value: shoppingListsApi,
            child: RepositoryProvider<StoresApi>.value(
              value: storesApi,
              child: RepositoryProvider<StoreChainReferenceCache>.value(
                value: referenceCache,
                child: RepositoryProvider<ItemsApi>.value(
                  value: itemsApi,
                  child: BlocProvider(
                    create: (_) => TripCubit(
                      tripsApi: tripsApi,
                      itemsApi: itemsApi,
                      storesApi: storesApi,
                      householdId: 'household-1',
                      listId: 'list-1',
                    )..bootstrap(),
                    child: const TripScreen(listTitle: 'Wocheneinkauf'),
                  ),
                ),
              ),
            ),
          ),
        );

    testWidgets('rendersStoreGroups_andTheUnassignedSection', (tester) async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka', 'store-netto'],
        items: [
          Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'store-edeka'),
          Item(itemId: 'i2', name: 'Brot', note: null, amount: '1', unit: 'PIECE', storeId: null),
        ],
      );
      storesApi.storesToReturn = const [edeka, netto];

      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('trip-screen')), findsOneWidget);
      expect(find.byKey(const Key('trip-store-group-store-edeka')), findsOneWidget);
      expect(find.byKey(const Key('trip-store-group-store-netto')), findsOneWidget);
      expect(find.byKey(const Key('trip-unassigned-group')), findsOneWidget);
      expect(find.byKey(const Key('trip-item-i1')), findsOneWidget);
      expect(find.byKey(const Key('trip-item-i2')), findsOneWidget);
      expect(find.text('Milch'), findsOneWidget);
      expect(find.text('Brot'), findsOneWidget);
    });

    testWidgets('tappingRerouteOpensThePickerScopedToTheTripsStores', (tester) async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka', 'store-netto'],
        items: [Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'store-edeka')],
      );
      storesApi.storesToReturn = const [edeka, netto];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('trip-item-reroute-i1')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('store-picker-sheet')), findsOneWidget);
      expect(find.byKey(const Key('store-picker-option-store-edeka')), findsOneWidget);
      expect(find.byKey(const Key('store-picker-option-store-netto')), findsOneWidget);
    });

    testWidgets('addStoreOpensThePicker', (tester) async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka'],
        items: [],
      );
      storesApi.storesToReturn = const [edeka];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('trip-add-store')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('store-picker-sheet')), findsOneWidget);
    });

    testWidgets('inlineCreatingAStoreInTheAddPicker_addsItToTheTripExactlyOnce', (tester) async {
      // Regression: the add-store picker must not both hook `onInlineStoreCreated` and re-add the
      // popped selection — that would fire AddStoreToTrip twice for the same store.
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka'],
        items: [],
      );
      storesApi.storesToReturn = const [edeka];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('trip-add-store')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('store-picker-new-name-field')), 'Netto');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('store-picker-add-new')));
      await tester.pumpAndSettle();

      expect(storesApi.lastAddedName, 'Netto');
      expect(tripsApi.addStoreToTripCallCount, 1);
    });

    testWidgets('showsNoCompleteAction', (tester) async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka'],
        items: [Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'store-edeka')],
      );
      storesApi.storesToReturn = const [edeka];

      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('trip-complete-action')), findsNothing);
    });

    testWidgets('rendersProgressBarWithCorrectCounts', (tester) async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka'],
        items: [
          Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'store-edeka', status: ItemStatus.done),
          Item(itemId: 'i2', name: 'Brot', note: null, amount: '1', unit: 'PIECE', storeId: 'store-edeka', status: ItemStatus.open),
        ],
      );
      storesApi.storesToReturn = const [edeka];

      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('trip-progress-bar')), findsOneWidget);
      expect(find.text('1 von 2 erledigt'), findsOneWidget);
    });

    testWidgets('tappingCheckboxChecksOffAnOpenItem', (tester) async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka'],
        items: [Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'store-edeka')],
      );
      storesApi.storesToReturn = const [edeka];

      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('trip-item-checkbox-i1')));
      await tester.pumpAndSettle();

      expect(itemsApi.checkOffCallCount, 1);
      expect(itemsApi.lastCheckedOffItemId, 'i1');
    });

    testWidgets('tappingCheckboxUnchecksADoneItem', (tester) async {
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

      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('trip-item-checkbox-i1')));
      await tester.pumpAndSettle();

      expect(itemsApi.uncheckCallCount, 1);
      expect(itemsApi.lastUncheckedItemId, 'i1');
    });

    testWidgets('aDoneItemShowsTheDoneTreatment', (tester) async {
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

      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('trip-item-done-i1')), findsOneWidget);
    });

    testWidgets('tappingPostponeOpensThePostponeSheet', (tester) async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka'],
        items: [Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'store-edeka')],
      );
      storesApi.storesToReturn = const [edeka];

      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('trip-item-postpone-i1')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('postpone-target-sheet')), findsOneWidget);
    });

    testWidgets('aDoneItemHidesPostponeAndReroute', (tester) async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka'],
        items: [
          Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'store-edeka', status: ItemStatus.done),
        ],
      );
      storesApi.storesToReturn = const [edeka];

      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('trip-item-checkbox-i1')), findsOneWidget);
      expect(find.byKey(const Key('trip-item-postpone-i1')), findsNothing);
      expect(find.byKey(const Key('trip-item-reroute-i1')), findsNothing);
    });

    testWidgets('aPostponedItemShowsUndo_andHidesPostponeAndReroute', (tester) async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka'],
        items: [
          Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'store-edeka', status: ItemStatus.postponed),
        ],
      );
      storesApi.storesToReturn = const [edeka];

      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('trip-item-undo-postpone-i1')), findsOneWidget);
      expect(find.byKey(const Key('trip-item-postpone-i1')), findsNothing);
      expect(find.byKey(const Key('trip-item-reroute-i1')), findsNothing);
    });

    testWidgets('tappingUndoOnAPostponedItemReopensIt', (tester) async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka'],
        items: [
          Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'store-edeka', status: ItemStatus.postponed),
        ],
      );
      storesApi.storesToReturn = const [edeka];

      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('trip-item-undo-postpone-i1')));
      await tester.pumpAndSettle();

      expect(itemsApi.uncheckCallCount, 1);
      expect(itemsApi.lastUncheckedItemId, 'i1');
    });

    testWidgets('tappingCheckboxOnAPostponedItemChecksItOff', (tester) async {
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: ['store-edeka'],
        items: [
          Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'store-edeka', status: ItemStatus.postponed),
        ],
      );
      storesApi.storesToReturn = const [edeka];

      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('trip-item-checkbox-i1')));
      await tester.pumpAndSettle();

      expect(itemsApi.checkOffCallCount, 1);
      expect(itemsApi.lastCheckedOffItemId, 'i1');
    });
  });
}
