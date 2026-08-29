import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/lists/data/item.dart';
import 'package:sgart/features/lists/data/item_suggestion.dart';
import 'package:sgart/features/lists/data/item_suggestions_api.dart';
import 'package:sgart/features/lists/data/items_api.dart';
import 'package:sgart/features/lists/data/shopping_lists_api.dart';
import 'package:sgart/features/lists/presentation/list_detail/list_detail_cubit.dart';
import 'package:sgart/features/lists/presentation/list_detail/list_detail_page.dart';
import 'package:sgart/features/stores/data/store_chain_reference_cache.dart';
import 'package:sgart/features/stores/data/store_summary.dart';
import 'package:sgart/features/stores/data/stores_api.dart';
import 'package:sgart/features/trips/data/trips_api.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../../support/fake_item_suggestions_api.dart';
import '../../../../support/fake_items_dependencies.dart';
import '../../../../support/fake_shopping_lists_dependencies.dart';
import '../../../../support/fake_stores_dependencies.dart';
import '../../../../support/fake_trips_dependencies.dart';
import '../../../../support/widget_test_harness.dart';

void main() {
  group('ListDetailPage', () {
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

    Widget buildSubject({bool isReadOnly = false}) => wrapForTesting(
          RepositoryProvider<ShoppingListsApi>.value(
            value: shoppingListsApi,
            child: RepositoryProvider<StoresApi>.value(
              value: storesApi,
              child: RepositoryProvider<StoreChainReferenceCache>.value(
                value: referenceCache,
                child: RepositoryProvider<ItemsApi>.value(
                  value: itemsApi,
                  child: RepositoryProvider<TripsApi>.value(
                    value: tripsApi,
                    child: BlocProvider(
                      create: (_) => ListDetailCubit(
                        itemsApi: itemsApi,
                        itemSuggestionsApi: itemSuggestionsApi,
                        storesApi: storesApi,
                        tripsApi: tripsApi,
                        householdId: 'household-1',
                        listId: 'list-1',
                        isReadOnly: isReadOnly,
                      )..bootstrap(),
                      child: const ListDetailPage(title: 'Wocheneinkauf'),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );

    testWidgets('showsTheEmptyStateWhenThereAreNoItems', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('item-list-empty-state')), findsOneWidget);
    });

    testWidgets('rendersAnItemWithNameQuantityAndNote', (tester) async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: 'Bio', amount: '1', unit: 'PIECE'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.text('Milch'), findsOneWidget);
      expect(find.textContaining('Bio'), findsOneWidget);
      expect(find.textContaining('Stück'), findsOneWidget);
    });

    testWidgets('addingANewItemViaTheAddAsNewRowShowsItInTheListWithStory23Defaults', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('fast-add-field')));
      await tester.enterText(find.byKey(const Key('fast-add-field')), 'Milch');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('fast-add-new-row')));
      await tester.pumpAndSettle();

      expect(itemsApi.lastAddedName, 'Milch');
      expect(itemsApi.lastAddedAmount, '1');
      expect(itemsApi.lastAddedUnit, 'PIECE');
      expect(itemsApi.lastAddedNote, isNull);
      expect(find.text('Milch'), findsOneWidget);
    });

    testWidgets('addingANewItemViaKeyboardSubmitShowsItInTheList', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('fast-add-field')));
      await tester.enterText(find.byKey(const Key('fast-add-field')), 'Brot');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();

      expect(itemsApi.lastAddedName, 'Brot');
      expect(find.text('Brot'), findsOneWidget);
    });

    testWidgets('blankOrWhitespaceOnlyTextIsBlockedClientSideOnKeyboardSubmit', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('fast-add-field')));
      await tester.enterText(find.byKey(const Key('fast-add-field')), '   ');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();

      expect(itemsApi.addCallCount, 0);
    });

    testWidgets('tappingASuggestionAddsItInstantlyWithPrefilledQuantityAndNote', (tester) async {
      itemSuggestionsApi.suggestionsToReturn = const [
        ItemSuggestion(name: 'Milch', note: 'Bio', amount: '2', unit: 'LITRE'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('fast-add-field')));
      await tester.enterText(find.byKey(const Key('fast-add-field')), 'Milch');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('fast-add-suggestion-milch')));
      await tester.pumpAndSettle();

      expect(itemsApi.lastAddedName, 'Milch');
      expect(itemsApi.lastAddedNote, 'Bio');
      expect(itemsApi.lastAddedAmount, '2');
      expect(itemsApi.lastAddedUnit, 'LITRE');
      expect(find.text('Milch'), findsOneWidget);
    });

    testWidgets('addAsNewStillWorksWhenSuggestionsFailedToLoad', (tester) async {
      itemSuggestionsApi.listError = const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('fast-add-field')));
      await tester.enterText(find.byKey(const Key('fast-add-field')), 'Milch');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('fast-add-new-row')));
      await tester.pumpAndSettle();

      expect(itemsApi.lastAddedName, 'Milch');
      expect(find.text('Milch'), findsOneWidget);
    });

    testWidgets('editingAnItemUpdatesTheRow', (tester) async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('item-edit-button-i1')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('item-note-field')), 'Bio');
      await tester.tap(find.byKey(const Key('item-form-submit-button')));
      await tester.pumpAndSettle();

      expect(itemsApi.lastUpdatedItemId, 'i1');
      expect(itemsApi.lastUpdatedNote, 'Bio');
      expect(find.textContaining('Bio'), findsOneWidget);
    });

    testWidgets('aGermanCommaDecimalAmountIsNormalizedToADotBeforeSendingOnEdit', (tester) async {
      // Regression: the whole UI is de-DE (amounts render „0,5 kg"), so a member enters „1,5"; the
      // backend parses with BigDecimal, which only accepts a dot. Without client normalization the
      // valid fractional quantity is rejected 400 — fractional amounts would be unreachable. The
      // amount field lives only on the edit sheet now (Story 2.5 retired the add-sheet path).
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Hackfleisch', note: null, amount: '1', unit: 'PIECE'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('item-edit-button-i1')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('item-amount-field')), '1,5');
      await tester.tap(find.byKey(const Key('item-form-submit-button')));
      await tester.pumpAndSettle();

      expect(itemsApi.lastUpdatedAmount, '1.5');
    });

    testWidgets('aNonNumericAmountIsBlockedClientSideOnEdit', (tester) async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('item-edit-button-i1')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('item-amount-field')), 'abc');
      await tester.pump();
      await tester.tap(find.byKey(const Key('item-form-submit-button')));
      await tester.pumpAndSettle();

      // Submit stays disabled — no round-trip, the sheet stays open with the typed values.
      expect(itemsApi.updateCallCount, 0);
      expect(find.byKey(const Key('item-amount-field')), findsOneWidget);
    });

    testWidgets('editingAnItemsUnitOffersOtherUnitsAndUpdatesIt', (tester) async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Hackfleisch', note: null, amount: '1', unit: 'PIECE'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('item-edit-button-i1')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('item-unit-dropdown')));
      await tester.pumpAndSettle();
      expect(find.text('kg').hitTestable(), findsOneWidget);
      await tester.tap(find.text('kg').last);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('item-form-submit-button')));
      await tester.pumpAndSettle();

      expect(itemsApi.lastUpdatedUnit, 'KILOGRAM');
    });

    testWidgets('removingAnItemDropsItFromTheList', (tester) async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('item-remove-button-i1')));
      await tester.pumpAndSettle();

      expect(itemsApi.lastRemovedItemId, 'i1');
      expect(find.text('Milch'), findsNothing);
      expect(find.byKey(const Key('item-list-empty-state')), findsOneWidget);
    });

    testWidgets('aCodedErrorMapsToLocalizedCopy', (tester) async {
      itemsApi.addError = const AppException(AppError(code: 'item.duplicate', message: 'debug'));
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('fast-add-field')));
      await tester.enterText(find.byKey(const Key('fast-add-field')), 'Milch');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('fast-add-new-row')));
      await tester.pumpAndSettle();

      expect(find.text('Diesen Artikel mit derselben Notiz gibt es bereits auf der Liste.'), findsOneWidget);
    });

    testWidgets('aFailedLoadOffersARetryThatReloadsTheItems', (tester) async {
      itemsApi.listError = const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('item-list-load-error')), findsOneWidget);

      itemsApi.listError = null;
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      await tester.tap(find.byKey(const Key('item-list-retry-button')));
      await tester.pumpAndSettle();

      expect(find.text('Milch'), findsOneWidget);
    });

    testWidgets('aReadOnlyDoneListShowsNoFastAddFieldNorEditRemoveMoveAffordances', (tester) async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      await tester.pumpWidget(buildSubject(isReadOnly: true));
      await tester.pumpAndSettle();

      expect(find.text('Milch'), findsOneWidget);
      expect(find.byKey(const Key('fast-add-field')), findsNothing);
      expect(find.byKey(const Key('item-edit-button-i1')), findsNothing);
      expect(find.byKey(const Key('item-remove-button-i1')), findsNothing);
      expect(find.byKey(const Key('item-move-button-i1')), findsNothing);
    });

    testWidgets('anOpenListShowsTheMoveAffordance', (tester) async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('item-move-button-i1')), findsOneWidget);
    });

    testWidgets('anAssignedItemShowsItsStoreChip', (tester) async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 's1'),
      ];
      storesApi.storesToReturn = const [StoreSummary(storeId: 's1', name: 'Edeka')];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.descendant(of: find.byKey(const Key('item-store-chip-i1')), matching: find.text('Edeka')),
          findsOneWidget);
    });

    testWidgets('anUnassignedItemShowsTheGhostChip', (tester) async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(
          find.descendant(
              of: find.byKey(const Key('item-store-chip-i1')), matching: find.text('+ Geschäft')),
          findsOneWidget);
    });

    testWidgets('anItemAssignedToAnArchivedStoreFallsBackToTheGhostChip', (tester) async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'archived'),
      ];
      storesApi.storesToReturn = const []; // the assigned store is no longer active
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(
          find.descendant(
              of: find.byKey(const Key('item-store-chip-i1')), matching: find.text('+ Geschäft')),
          findsOneWidget);
    });

    testWidgets('tappingTheStoreChipOnAnOpenListOpensThePickerAndAssigns', (tester) async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      storesApi.storesToReturn = const [StoreSummary(storeId: 's1', name: 'Edeka')];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('item-store-chip-i1')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('store-picker-sheet')), findsOneWidget);

      await tester.tap(find.byKey(const Key('store-picker-option-s1')));
      await tester.pumpAndSettle();

      expect(itemsApi.lastAssignedItemId, 'i1');
      expect(itemsApi.lastAssignedStoreId, 's1');
    });

    testWidgets('aDoneListsStoreChipIsInert', (tester) async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      await tester.pumpWidget(buildSubject(isReadOnly: true));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('item-store-chip-i1')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('store-picker-sheet')), findsNothing);
    });

    group('startTrip (Story 3.1)', () {
      testWidgets('anOpenListShowsTheStartTripActionWhichOpensTheSelectionSheet', (tester) async {
        storesApi.storesToReturn = const [StoreSummary(storeId: 's1', name: 'Edeka')];
        await tester.pumpWidget(buildSubject());
        await tester.pumpAndSettle();

        expect(find.byKey(const Key('list-detail-start-trip')), findsOneWidget);

        await tester.tap(find.byKey(const Key('list-detail-start-trip')));
        await tester.pumpAndSettle();

        expect(find.byKey(const Key('trip-store-selection-sheet')), findsOneWidget);
      });

      testWidgets('aDoneListNeverShowsTheStartTripAction', (tester) async {
        await tester.pumpWidget(buildSubject(isReadOnly: true));
        await tester.pumpAndSettle();

        expect(find.byKey(const Key('list-detail-start-trip')), findsNothing);
      });

      testWidgets(
          'confirmingASelectionStartsTheTripAndNavigatesToTheTripScreen',
          (tester) async {
        // Story 3.2, AC4, Cl. 3 — starting a trip now navigates straight to the trip screen (3.1
        // deferred this navigation; it used to end at the "Einkauf gestartet" toast alone).
        itemsApi.itemsToReturn = const [
          Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
        ];
        storesApi.storesToReturn = const [StoreSummary(storeId: 's1', name: 'Edeka')];
        tripsApi.tripViewToReturn = const TripView(
          tripId: 'trip-1',
          listId: 'list-1',
          storeIds: ['s1'],
          items: [Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 's1')],
        );
        await tester.pumpWidget(buildSubject());
        await tester.pumpAndSettle();

        await tester.tap(find.byKey(const Key('list-detail-start-trip')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key('trip-store-option-s1')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key('trip-store-selection-confirm')));
        await tester.pumpAndSettle();

        expect(tripsApi.lastListId, 'list-1');
        expect(tripsApi.lastStoreIds, ['s1']);
        expect(find.byKey(const Key('trip-screen')), findsOneWidget);
        expect(find.byKey(const Key('trip-item-i1')), findsOneWidget);
      });

      testWidgets('dismissingTheSelectionSheetWithoutConfirmingStartsNoTrip', (tester) async {
        storesApi.storesToReturn = const [StoreSummary(storeId: 's1', name: 'Edeka')];
        await tester.pumpWidget(buildSubject());
        await tester.pumpAndSettle();

        await tester.tap(find.byKey(const Key('list-detail-start-trip')));
        await tester.pumpAndSettle();
        await tester.tapAt(const Offset(20, 20)); // tap the scrim to dismiss
        await tester.pumpAndSettle();

        expect(tripsApi.startCallCount, 0);
        expect(find.byKey(const Key('list-detail-start-trip')), findsOneWidget);
      });
    });

    group('push', () {
      // Drives push from a launcher button that has the three re-provided APIs in scope, then pops
      // back, so the on-return guarantee is exercised through the real navigation path.
      Future<void> pushAndReturn(
        WidgetTester tester, {
        required bool isReadOnly,
        required VoidCallback onEditableReturn,
      }) async {
        await tester.pumpWidget(wrapForTesting(
          MultiRepositoryProvider(
            providers: [
              RepositoryProvider<ItemsApi>.value(value: itemsApi),
              RepositoryProvider<ItemSuggestionsApi>.value(value: itemSuggestionsApi),
              RepositoryProvider<ShoppingListsApi>.value(value: FakeShoppingListsApi()),
              RepositoryProvider<StoresApi>.value(value: storesApi),
              RepositoryProvider<StoreChainReferenceCache>.value(value: referenceCache),
              RepositoryProvider<TripsApi>.value(value: tripsApi),
            ],
            child: Builder(
              builder: (context) => ElevatedButton(
                onPressed: () => ListDetailPage.push(
                  context,
                  householdId: 'household-1',
                  listId: 'list-1',
                  title: 'Wocheneinkauf',
                  isReadOnly: isReadOnly,
                  onEditableReturn: onEditableReturn,
                ),
                child: const Text('open'),
              ),
            ),
          ),
        ));
        await tester.tap(find.text('open'));
        await tester.pumpAndSettle();
        // The app's SgartAppBar has no default back button for pageBack() to tap, so pop the
        // pushed route directly.
        tester.state<NavigatorState>(find.byType(Navigator).first).pop();
        await tester.pumpAndSettle();
      }

      testWidgets('firesOnEditableReturnAfterPoppingAnOpenList', (tester) async {
        var returned = false;
        await pushAndReturn(tester, isReadOnly: false, onEditableReturn: () => returned = true);

        expect(returned, isTrue);
      });

      testWidgets('doesNotFireOnEditableReturnAfterPoppingAReadOnlyDoneList', (tester) async {
        var returned = false;
        await pushAndReturn(tester, isReadOnly: true, onEditableReturn: () => returned = true);

        expect(returned, isFalse);
      });
    });
  });
}
