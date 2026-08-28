import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/lists/data/item.dart';
import 'package:sgart/features/lists/data/shopping_list_summary.dart';
import 'package:sgart/features/lists/data/shopping_lists_api.dart';
import 'package:sgart/features/lists/presentation/list_detail/list_detail_cubit.dart';
import 'package:sgart/features/lists/presentation/list_detail/list_detail_page.dart';
import 'package:sgart/features/stores/data/store_chain_reference_cache.dart';
import 'package:sgart/features/stores/data/stores_api.dart';

import '../../../../support/fake_item_suggestions_api.dart';
import '../../../../support/fake_items_dependencies.dart';
import '../../../../support/fake_shopping_lists_dependencies.dart';
import '../../../../support/fake_stores_dependencies.dart';
import '../../../../support/fake_trips_dependencies.dart';
import '../../../../support/widget_test_harness.dart';

/// Widget tests for the move target picker (Story 2.4, AC3, AC4, AC7), driven through the real list
/// detail page's move affordance — mirrors `list_detail_page_test.dart`'s approach for
/// `item_form_sheet`.
void main() {
  group('move target sheet', () {
    late FakeItemsApi itemsApi;
    late FakeItemSuggestionsApi itemSuggestionsApi;
    late FakeShoppingListsApi shoppingListsApi;
    late FakeStoresApi storesApi;
    late FakeStoreChainReferenceCache referenceCache;
    late FakeTripsApi tripsApi;

    setUp(() {
      itemsApi = FakeItemsApi();
      itemSuggestionsApi = FakeItemSuggestionsApi();
      shoppingListsApi = FakeShoppingListsApi();
      storesApi = FakeStoresApi();
      referenceCache = FakeStoreChainReferenceCache();
      tripsApi = FakeTripsApi();
    });

    Widget buildSubject() => wrapForTesting(
          RepositoryProvider<ShoppingListsApi>.value(
            value: shoppingListsApi,
            child: RepositoryProvider<StoresApi>.value(
              value: storesApi,
              child: RepositoryProvider<StoreChainReferenceCache>.value(
                value: referenceCache,
                child: BlocProvider(
                  create: (_) => ListDetailCubit(
                    itemsApi: itemsApi,
                    itemSuggestionsApi: itemSuggestionsApi,
                    storesApi: storesApi,
                    tripsApi: tripsApi,
                    householdId: 'household-1',
                    listId: 'source-list',
                    isReadOnly: false,
                  )..bootstrap(),
                  child: const ListDetailPage(title: 'Wocheneinkauf'),
                ),
              ),
            ),
          ),
        );

    testWidgets('listsOnlyTheOtherOpenListsExcludingTheSource', (tester) async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'source-list', name: 'Wocheneinkauf', status: 'OPEN'),
        ShoppingListSummary(listId: 'other-list', name: 'Getränke', status: 'OPEN'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('item-move-button-i1')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('move-target-row-source-list')), findsNothing);
      expect(find.byKey(const Key('move-target-row-other-list')), findsOneWidget);
      expect(find.text('Getränke'), findsOneWidget);
    });

    testWidgets('excludesInTripListsWhichAMoveWouldReject', (tester) async {
      // `listOpenLists` returns In-Trip lists too (Story 3.1, AC5), but a move to one is refused
      // (409) — so it must not be offered as a target, only the Open list may be picked.
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'source-list', name: 'Wocheneinkauf', status: 'OPEN'),
        ShoppingListSummary(listId: 'open-list', name: 'Getränke', status: 'OPEN'),
        ShoppingListSummary(listId: 'in-trip-list', name: 'Baumarkt', status: 'IN_TRIP'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('item-move-button-i1')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('move-target-row-open-list')), findsOneWidget);
      expect(find.byKey(const Key('move-target-row-in-trip-list')), findsNothing);
      expect(find.text('Baumarkt'), findsNothing);
    });

    testWidgets('showsTheEmptyStateWhenThereIsNoOtherOpenList', (tester) async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'source-list', name: 'Wocheneinkauf', status: 'OPEN'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('item-move-button-i1')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('move-target-empty-state')), findsOneWidget);
    });

    testWidgets('pickingATargetWithNoCollisionMovesTheItemCleanlyAndClosesTheSheet', (tester) async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'source-list', name: 'Wocheneinkauf', status: 'OPEN'),
        ShoppingListSummary(listId: 'other-list', name: 'Getränke', status: 'OPEN'),
      ];
      itemsApi.itemsByListId['other-list'] = const []; // the target holds nothing — no collision
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('item-move-button-i1')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('move-target-row-other-list')));
      await tester.pumpAndSettle();

      expect(itemsApi.lastMovedItemId, 'i1');
      expect(itemsApi.lastMovedTargetListId, 'other-list');
      expect(find.byKey(const Key('move-target-row-other-list')), findsNothing); // sheet closed
      expect(find.text('Milch'), findsNothing); // optimistically removed from the source
    });

    testWidgets('pickingATargetWithACollisionOpensTheMergeSheetInsteadOfMoving', (tester) async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: 'Bio', amount: '1', unit: 'PIECE'),
      ];
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'source-list', name: 'Wocheneinkauf', status: 'OPEN'),
        ShoppingListSummary(listId: 'other-list', name: 'Getränke', status: 'OPEN'),
      ];
      // The target already holds the same (name, note) key (trimmed/case-insensitive, Cl. 3).
      itemsApi.itemsByListId['other-list'] = const [
        Item(itemId: 'target-1', name: ' milch ', note: ' bio ', amount: '2', unit: 'PIECE'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('item-move-button-i1')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('move-target-row-other-list')));
      await tester.pumpAndSettle();

      expect(itemsApi.moveCallCount, 0); // no clean move — the merge sheet took over
      expect(find.byKey(const Key('move-merge-message')), findsOneWidget);
    });

    testWidgets('creatingANewListMovesTheItemToItCleanly', (tester) async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'source-list', name: 'Wocheneinkauf', status: 'OPEN'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('item-move-button-i1')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('move-target-new-list-button')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('move-target-new-list-name-field')), 'Getränke');
      await tester.tap(find.byKey(const Key('move-target-new-list-submit-button')));
      await tester.pumpAndSettle();

      expect(shoppingListsApi.lastCreatedName, 'Getränke');
      expect(itemsApi.lastMovedItemId, 'i1');
      expect(itemsApi.lastMovedTargetListId, shoppingListsApi.lastCreatedListId);
    });
  });
}
