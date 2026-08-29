import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/lists/data/item.dart';
import 'package:sgart/features/lists/data/items_api.dart';
import 'package:sgart/features/lists/data/shopping_list_summary.dart';
import 'package:sgart/features/lists/data/shopping_lists_api.dart';
import 'package:sgart/features/stores/data/store_chain_reference_cache.dart';
import 'package:sgart/features/stores/data/stores_api.dart';
import 'package:sgart/features/trips/data/trips_api.dart';
import 'package:sgart/features/trips/presentation/active_trips_cubit.dart';
import 'package:sgart/features/trips/presentation/active_trips_view.dart';

import '../../../support/fake_items_dependencies.dart';
import '../../../support/fake_shopping_lists_dependencies.dart';
import '../../../support/fake_stores_dependencies.dart';
import '../../../support/fake_trips_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('ActiveTripsView', () {
    late FakeShoppingListsApi shoppingListsApi;
    late FakeTripsApi tripsApi;
    late FakeItemsApi itemsApi;
    late FakeStoresApi storesApi;
    late FakeStoreChainReferenceCache referenceCache;

    setUp(() {
      shoppingListsApi = FakeShoppingListsApi();
      tripsApi = FakeTripsApi();
      itemsApi = FakeItemsApi();
      storesApi = FakeStoresApi();
      referenceCache = FakeStoreChainReferenceCache();
    });

    Widget buildSubject() => wrapForTesting(
          RepositoryProvider<ShoppingListsApi>.value(
            value: shoppingListsApi,
            child: RepositoryProvider<TripsApi>.value(
              value: tripsApi,
              child: RepositoryProvider<ItemsApi>.value(
                value: itemsApi,
                child: RepositoryProvider<StoresApi>.value(
                  value: storesApi,
                  child: RepositoryProvider<StoreChainReferenceCache>.value(
                    value: referenceCache,
                    child: BlocProvider(
                      create: (_) => ActiveTripsCubit(
                        shoppingListsApi: shoppingListsApi,
                        householdId: 'household-1',
                      )..bootstrap(),
                      child: const Scaffold(body: ActiveTripsView()),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );

    testWidgets('showsOneRowPerInTripList_withItsNameAndItemCount', (tester) async {
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'l1', name: 'Getränke', status: 'OPEN'),
        ShoppingListSummary(listId: 'l2', name: 'Wocheneinkauf', status: 'IN_TRIP', itemCount: 3, activeTripId: 'trip-1'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('active-trip-row-l2')), findsOneWidget);
      expect(find.byKey(const Key('active-trip-row-l1')), findsNothing);
      expect(find.text('Wocheneinkauf'), findsOneWidget);
    });

    testWidgets('tappingARowNavigatesToTheTripScreen', (tester) async {
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'l2', name: 'Wocheneinkauf', status: 'IN_TRIP', activeTripId: 'trip-1'),
      ];
      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'l2',
        storeIds: ['store-1'],
        items: [Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'store-1')],
      );
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('active-trip-row-l2')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('trip-screen')), findsOneWidget);
      expect(find.byKey(const Key('trip-item-i1')), findsOneWidget);
    });

    testWidgets('showsTheListeNOrdinalForAnUnnamedInTripList_matchingTheOverviewSequence', (tester) async {
      // The ordinal is the list's position in the FULL open-lists sequence (OPEN + IN_TRIP), the
      // same sequence the overview numbers over — so the unnamed In-Trip list at index 1 is „Liste 2",
      // not a bare „Liste" (AC4, Cl. 3).
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'l1', name: 'Getränke', status: 'OPEN'),
        ShoppingListSummary(listId: 'l2', name: null, status: 'IN_TRIP', itemCount: 2, activeTripId: 'trip-1'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.text('Liste 2'), findsOneWidget);
    });

    testWidgets('showsTheEmptyStateWhenNoListIsInTrip', (tester) async {
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'l1', name: 'Getränke', status: 'OPEN'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('active-trips-empty-state')), findsOneWidget);
    });
  });
}
