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
import 'package:sgart/features/trips/presentation/trip_cubit.dart';
import 'package:sgart/features/trips/presentation/trip_screen.dart';

import '../../../support/fake_items_dependencies.dart';
import '../../../support/fake_shopping_lists_dependencies.dart';
import '../../../support/fake_stores_dependencies.dart';
import '../../../support/fake_trips_dependencies.dart';
import '../../../support/widget_test_harness.dart';

/// Widget tests for the postpone target sheet (Story 3.3, AC3/AC4), driven through the trip
/// screen's postpone affordance — mirrors the move target sheet test's approach.
void main() {
  group('postpone target sheet', () {
    late FakeTripsApi tripsApi;
    late FakeItemsApi itemsApi;
    late FakeStoresApi storesApi;
    late FakeStoreChainReferenceCache referenceCache;
    late FakeShoppingListsApi shoppingListsApi;

    const openItem = Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: null);
    const otherList = ShoppingListSummary(listId: 'list-other', name: 'Getränke', status: 'OPEN');

    setUp(() {
      tripsApi = FakeTripsApi();
      itemsApi = FakeItemsApi();
      storesApi = FakeStoresApi();
      referenceCache = FakeStoreChainReferenceCache();
      shoppingListsApi = FakeShoppingListsApi();

      tripsApi.tripViewToReturn = const TripView(
        tripId: 'trip-1',
        listId: 'list-1',
        storeIds: [],
        items: [openItem],
      );
    });

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

    Future<void> openSheet(WidgetTester tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('trip-item-postpone-i1')));
      await tester.pumpAndSettle();
    }

    testWidgets('showsHierVormerken_andOpenLists_andNeueListeButton', (tester) async {
      shoppingListsApi.listsToReturn = const [otherList];

      await openSheet(tester);

      expect(find.byKey(const Key('postpone-target-sheet')), findsOneWidget);
      expect(find.byKey(const Key('postpone-target-in-place')), findsOneWidget);
      expect(find.byKey(const Key('postpone-target-row-list-other')), findsOneWidget);
      expect(find.byKey(const Key('postpone-target-new-list-button')), findsOneWidget);
    });

    testWidgets('pickingAnExistingList_postponesToIt', (tester) async {
      shoppingListsApi.listsToReturn = const [otherList];

      await openSheet(tester);
      await tester.tap(find.byKey(const Key('postpone-target-row-list-other')));
      await tester.pumpAndSettle();

      expect(itemsApi.lastPostponedToListItemId, 'i1');
      expect(itemsApi.lastPostponedTargetListId, 'list-other');
    });

    testWidgets('pickingHierVormerken_postponesInPlace', (tester) async {
      await openSheet(tester);
      await tester.tap(find.byKey(const Key('postpone-target-in-place')));
      await tester.pumpAndSettle();

      expect(itemsApi.lastPostponedItemId, 'i1');
      expect(itemsApi.postponeCallCount, 1);
    });

    testWidgets('excludesInTripListsAndTheSourceList_fromTargets', (tester) async {
      // A source list (list-1) and an IN_TRIP list must not appear as postpone targets.
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'list-1', name: 'Quelle', status: 'IN_TRIP'),
        ShoppingListSummary(listId: 'list-in-trip', name: 'Einkauf läuft', status: 'IN_TRIP'),
        otherList,
      ];

      await openSheet(tester);

      expect(find.byKey(const Key('postpone-target-row-list-1')), findsNothing);
      expect(find.byKey(const Key('postpone-target-row-list-in-trip')), findsNothing);
      expect(find.byKey(const Key('postpone-target-row-list-other')), findsOneWidget);
    });

    testWidgets('creatingANewList_thatFails_showsAnInlineError_andDoesNotPostpone', (tester) async {
      shoppingListsApi.createError = Exception('create failed');

      await openSheet(tester);
      await tester.tap(find.byKey(const Key('postpone-target-new-list-button')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('postpone-target-new-list-name-field')), 'Getränke');
      await tester.tap(find.byKey(const Key('postpone-target-new-list-submit-button')));
      await tester.pumpAndSettle();

      // The failure is surfaced inline, the sheet stays open, and no postpone was issued.
      expect(find.byKey(const Key('postpone-target-create-error')), findsOneWidget);
      expect(find.byKey(const Key('postpone-target-sheet')), findsOneWidget);
      expect(itemsApi.lastPostponedToListItemId, isNull);
    });
  });
}
