import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/lists/data/item.dart';
import 'package:sgart/features/lists/data/item_suggestion.dart';
import 'package:sgart/features/lists/presentation/list_detail/list_detail_cubit.dart';
import 'package:sgart/features/lists/presentation/list_detail/list_detail_state.dart';
import 'package:sgart/features/stores/data/store_summary.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../../support/fake_item_suggestions_api.dart';
import '../../../../support/fake_items_dependencies.dart';
import '../../../../support/fake_stores_dependencies.dart';

void main() {
  group('ListDetailCubit', () {
    late FakeItemsApi itemsApi;
    late FakeItemSuggestionsApi itemSuggestionsApi;
    late FakeStoresApi storesApi;

    setUp(() {
      itemsApi = FakeItemsApi();
      itemSuggestionsApi = FakeItemSuggestionsApi();
      storesApi = FakeStoresApi();
    });

    ListDetailCubit buildCubit({bool isReadOnly = false}) => ListDetailCubit(
          itemsApi: itemsApi,
          itemSuggestionsApi: itemSuggestionsApi,
          storesApi: storesApi,
          householdId: 'household-1',
          listId: 'list-1',
          isReadOnly: isReadOnly,
        );

    test('bootstrap_loadsTheItemsInCreationOrder', () async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: 'Bio', amount: '1', unit: 'PIECE'),
        Item(itemId: 'i2', name: 'Brot', note: null, amount: '2', unit: 'PACK'),
      ];
      final cubit = buildCubit();

      await cubit.bootstrap();

      expect(cubit.state.status, ListDetailStatus.ready);
      expect(cubit.state.items.map((item) => item.itemId), ['i1', 'i2']);
      await cubit.close();
    });

    test('bootstrap_emitsFailureWhenTheLoadFails', () async {
      itemsApi.listError = const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      final cubit = buildCubit();

      await cubit.bootstrap();

      expect(cubit.state.status, ListDetailStatus.failure);
      expect(cubit.state.loadError?.code, 'network.unreachable');
      await cubit.close();
    });

    test('addItem_optimisticallyAppendsTheItem', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();

      final succeeded = await cubit.addItem(name: 'Milch', note: 'Bio', amount: '1', unit: 'PIECE');

      expect(succeeded, isTrue);
      expect(itemsApi.lastAddedName, 'Milch');
      expect(itemsApi.lastAddedNote, 'Bio');
      expect(cubit.state.items, hasLength(1));
      expect(cubit.state.items.single.name, 'Milch');
      await cubit.close();
    });

    test('addItem_withABlankOrAbsentNoteAddsAnItemWithoutANote', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.addItem(name: 'Milch', note: '  ', amount: '1', unit: 'PIECE');

      expect(itemsApi.lastAddedNote, isNull);
      expect(cubit.state.items.single.note, isNull);
      await cubit.close();
    });

    test('addItem_rejectsABlankNameWithoutCallingTheApi', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();

      final succeeded = await cubit.addItem(name: '   ', amount: '1', unit: 'PIECE');

      expect(succeeded, isFalse);
      expect(itemsApi.addCallCount, 0);
      await cubit.close();
    });

    test('addItem_isANoOpOnAReadOnlyList', () async {
      final cubit = buildCubit(isReadOnly: true);
      await cubit.bootstrap();

      final succeeded = await cubit.addItem(name: 'Milch', amount: '1', unit: 'PIECE');

      expect(succeeded, isFalse);
      expect(itemsApi.addCallCount, 0);
      await cubit.close();
    });

    test('addItem_reusesOneCommandIdAndItemIdAcrossRetriesOfTheSamePayload', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();
      itemsApi.addError = const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      await cubit.addItem(name: 'Milch', amount: '1', unit: 'PIECE');
      itemsApi.addError = null;
      await cubit.addItem(name: 'Milch', amount: '1', unit: 'PIECE');

      expect(itemsApi.addCommandIds, hasLength(2));
      expect(itemsApi.addCommandIds.first, itemsApi.addCommandIds.last);
      await cubit.close();
    });

    test('addItem_usesAFreshCommandIdWhenThePayloadChangesBetweenAttempts', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();
      itemsApi.addError = const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      await cubit.addItem(name: 'Milch', amount: '1', unit: 'PIECE');
      await cubit.addItem(name: 'Brot', amount: '1', unit: 'PIECE');

      expect(itemsApi.addCommandIds, hasLength(2));
      expect(itemsApi.addCommandIds.first, isNot(itemsApi.addCommandIds.last));
      await cubit.close();
    });

    test('addItem_regeneratesTheCommandIdAfterASuccessfulAddSoTheNextItemIsNotDeduped', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.addItem(name: 'Milch', amount: '1', unit: 'PIECE');
      await cubit.addItem(name: 'Brot', amount: '1', unit: 'PIECE');

      expect(itemsApi.addCommandIds, hasLength(2));
      expect(itemsApi.addCommandIds.first, isNot(itemsApi.addCommandIds.last));
      expect(cubit.state.items.map((item) => item.name), containsAll(['Milch', 'Brot']));
      await cubit.close();
    });

    test('addItem_surfacesARejectionAsAnInlineErrorWithoutLeavingReady', () async {
      itemsApi.addError = const AppException(AppError(code: 'item.duplicate', message: 'debug'));
      final cubit = buildCubit();
      await cubit.bootstrap();

      final succeeded = await cubit.addItem(name: 'Milch', amount: '1', unit: 'PIECE');

      expect(succeeded, isFalse);
      expect(cubit.state.status, ListDetailStatus.ready);
      expect(cubit.state.actionError?.code, 'item.duplicate');
      await cubit.close();
    });

    test('updateItem_replacesTheMatchingItemInPlace', () async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      final cubit = buildCubit();
      await cubit.bootstrap();

      final succeeded = await cubit.updateItem('i1', name: 'Milch', note: 'Bio', amount: '2', unit: 'PIECE');

      expect(succeeded, isTrue);
      expect(itemsApi.lastUpdatedItemId, 'i1');
      expect(cubit.state.items.single.note, 'Bio');
      expect(cubit.state.items.single.amount, '2');
      await cubit.close();
    });

    test('updateItem_preservesTheItemsStoreAssignmentOptimistically', () async {
      // Cl. 7 (client mirror): an edit changes name/note/quantity only — it must not wipe the store
      // chip optimistically (the backend preserves store_id too). Story 2.6 review patch.
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 's1'),
      ];
      storesApi.storesToReturn = const [StoreSummary(storeId: 's1', name: 'Edeka')];
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.updateItem('i1', name: 'Milch', note: 'Bio', amount: '2', unit: 'PIECE');

      expect(cubit.state.items.single.storeId, 's1');
      expect(cubit.storeFor(cubit.state.items.single.storeId)?.name, 'Edeka');
      await cubit.close();
    });

    test('updateItem_rejectsABlankNameWithoutCallingTheApi', () async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      final cubit = buildCubit();
      await cubit.bootstrap();

      final succeeded = await cubit.updateItem('i1', name: '   ', amount: '1', unit: 'PIECE');

      expect(succeeded, isFalse);
      expect(itemsApi.updateCallCount, 0);
      await cubit.close();
    });

    test('updateItem_isANoOpOnAReadOnlyList', () async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      final cubit = buildCubit(isReadOnly: true);
      await cubit.bootstrap();

      final succeeded = await cubit.updateItem('i1', name: 'Milch 2', amount: '1', unit: 'PIECE');

      expect(succeeded, isFalse);
      expect(itemsApi.updateCallCount, 0);
      await cubit.close();
    });

    test('updateItem_surfacesARejectionAsAnInlineErrorWithoutLeavingReady', () async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      itemsApi.updateError = const AppException(AppError(code: 'item.notFound', message: 'debug'));
      final cubit = buildCubit();
      await cubit.bootstrap();

      final succeeded = await cubit.updateItem('i1', name: 'Milch 2', amount: '1', unit: 'PIECE');

      expect(succeeded, isFalse);
      expect(cubit.state.status, ListDetailStatus.ready);
      expect(cubit.state.actionError?.code, 'item.notFound');
      await cubit.close();
    });

    test('removeItem_dropsTheMatchingItem', () async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.removeItem('i1');

      expect(itemsApi.lastRemovedItemId, 'i1');
      expect(cubit.state.items, isEmpty);
      await cubit.close();
    });

    test('removeItem_isANoOpOnAReadOnlyList', () async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      final cubit = buildCubit(isReadOnly: true);
      await cubit.bootstrap();

      await cubit.removeItem('i1');

      expect(itemsApi.removeCallCount, 0);
      expect(cubit.state.items, hasLength(1));
      await cubit.close();
    });

    test('removeItem_surfacesARejectionAsAnInlineErrorWithoutLeavingReady', () async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      itemsApi.removeError = const AppException(AppError(code: 'item.changeNotPermitted', message: 'debug'));
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.removeItem('i1');

      expect(cubit.state.status, ListDetailStatus.ready);
      expect(cubit.state.actionError?.code, 'item.changeNotPermitted');
      expect(cubit.state.items, hasLength(1)); // no optimistic removal on failure
      await cubit.close();
    });

    test('removeItem_reusesOneCommandIdAcrossRetriesOfTheSameItem', () async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      final cubit = buildCubit();
      await cubit.bootstrap();
      itemsApi.removeError = const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      await cubit.removeItem('i1');
      itemsApi.removeError = null;
      await cubit.removeItem('i1');

      expect(itemsApi.removeCommandIds, hasLength(2));
      expect(itemsApi.removeCommandIds.first, itemsApi.removeCommandIds.last);
      await cubit.close();
    });

    test('moveItem_optimisticallyRemovesTheSourceRowAndCallsTheApi', () async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.moveItem('i1', 'list-2');

      expect(itemsApi.lastMovedItemId, 'i1');
      expect(itemsApi.lastMovedTargetListId, 'list-2');
      expect(cubit.state.items, isEmpty);
      expect(cubit.state.isSubmitting, isFalse);
      await cubit.close();
    });

    test('moveItem_revertsTheOptimisticRemovalOnFailure', () async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      itemsApi.moveError = const AppException(AppError(code: 'list.moveTargetNotOpen', message: 'debug'));
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.moveItem('i1', 'list-2');

      expect(cubit.state.items, hasLength(1));
      expect(cubit.state.items.single.itemId, 'i1');
      expect(cubit.state.actionError?.code, 'list.moveTargetNotOpen');
      await cubit.close();
    });

    test('moveItem_isANoOpOnAReadOnlyList', () async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      final cubit = buildCubit(isReadOnly: true);
      await cubit.bootstrap();

      await cubit.moveItem('i1', 'list-2');

      expect(itemsApi.moveCallCount, 0);
      expect(cubit.state.items, hasLength(1));
      await cubit.close();
    });

    test('moveItem_reusesOneCommandIdAcrossRetriesOfTheSameMove', () async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      final cubit = buildCubit();
      await cubit.bootstrap();
      itemsApi.moveError = const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      await cubit.moveItem('i1', 'list-2');
      itemsApi.moveError = null;
      await cubit.moveItem('i1', 'list-2');

      expect(itemsApi.moveCommandIds, hasLength(2));
      expect(itemsApi.moveCommandIds.first, itemsApi.moveCommandIds.last);
      await cubit.close();
    });

    test('moveItem_usesAFreshCommandIdWhenTheTargetChangesBetweenAttempts', () async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      final cubit = buildCubit();
      await cubit.bootstrap();
      itemsApi.moveError = const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      await cubit.moveItem('i1', 'list-2');
      await cubit.moveItem('i1', 'list-3');

      expect(itemsApi.moveCommandIds, hasLength(2));
      expect(itemsApi.moveCommandIds.first, isNot(itemsApi.moveCommandIds.last));
      await cubit.close();
    });

    test('findCollisionOnTarget_returnsTheMatchingItemTrimmedAndCaseInsensitively', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();
      itemsApi.itemsToReturn = const [
        Item(itemId: 'target-1', name: ' milch ', note: ' Bio ', amount: '1', unit: 'PIECE'),
      ];
      const moved = Item(itemId: 'i1', name: 'Milch', note: 'bio', amount: '2', unit: 'PIECE');

      final collision = await cubit.findCollisionOnTarget(moved, 'list-2');

      expect(collision?.itemId, 'target-1');
      await cubit.close();
    });

    test('findCollisionOnTarget_treatsAnAbsentNoteAsDistinctFromAPresentNote', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();
      itemsApi.itemsToReturn = const [
        Item(itemId: 'target-1', name: 'Milch', note: 'Bio', amount: '1', unit: 'PIECE'),
      ];
      const moved = Item(itemId: 'i1', name: 'Milch', note: null, amount: '2', unit: 'PIECE');

      final collision = await cubit.findCollisionOnTarget(moved, 'list-2');

      expect(collision, isNull);
      await cubit.close();
    });

    test('findCollisionOnTarget_returnsNullWhenNoItemMatches', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();
      itemsApi.itemsToReturn = const [
        Item(itemId: 'target-1', name: 'Brot', note: null, amount: '1', unit: 'PIECE'),
      ];
      const moved = Item(itemId: 'i1', name: 'Milch', note: null, amount: '2', unit: 'PIECE');

      final collision = await cubit.findCollisionOnTarget(moved, 'list-2');

      expect(collision, isNull);
      await cubit.close();
    });

    test('mergeIntoTarget_withAnAdjustedQuantityUpdatesTheTargetThenRemovesTheSource', () async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: 'Bio', amount: '1', unit: 'PIECE'),
      ];
      final cubit = buildCubit();
      await cubit.bootstrap();
      const targetItem = Item(itemId: 'target-1', name: 'Milch', note: 'Bio', amount: '2', unit: 'PIECE');

      await cubit.mergeIntoTarget('i1', 'list-2', targetItem, adjustedAmount: '3', adjustedUnit: 'PIECE');

      expect(itemsApi.lastUpdatedItemId, 'target-1');
      expect(itemsApi.lastUpdatedAmount, '3');
      expect(itemsApi.lastUpdatedName, 'Milch');
      expect(itemsApi.lastUpdatedNote, 'Bio');
      expect(itemsApi.lastRemovedItemId, 'i1');
      expect(cubit.state.items, isEmpty); // the source item is gone; the target is never re-added here
      await cubit.close();
    });

    test('mergeIntoTarget_withNoAdjustmentOnlyRemovesTheSource', () async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: 'Bio', amount: '1', unit: 'PIECE'),
      ];
      final cubit = buildCubit();
      await cubit.bootstrap();
      const targetItem = Item(itemId: 'target-1', name: 'Milch', note: 'Bio', amount: '2', unit: 'PIECE');

      await cubit.mergeIntoTarget('i1', 'list-2', targetItem);

      expect(itemsApi.updateCallCount, 0);
      expect(itemsApi.lastRemovedItemId, 'i1');
      expect(cubit.state.items, isEmpty);
      await cubit.close();
    });

    test('mergeIntoTarget_revertsTheOptimisticRemovalWhenTheUpdateLegFails', () async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: 'Bio', amount: '1', unit: 'PIECE'),
      ];
      itemsApi.updateError = const AppException(AppError(code: 'item.notFound', message: 'debug'));
      final cubit = buildCubit();
      await cubit.bootstrap();
      const targetItem = Item(itemId: 'target-1', name: 'Milch', note: 'Bio', amount: '2', unit: 'PIECE');

      await cubit.mergeIntoTarget('i1', 'list-2', targetItem, adjustedAmount: '3', adjustedUnit: 'PIECE');

      expect(itemsApi.removeCallCount, 0); // the target update failed before the source remove ran
      expect(cubit.state.items, hasLength(1));
      expect(cubit.state.actionError?.code, 'item.notFound');
      await cubit.close();
    });

    test('mergeIntoTarget_revertsTheOptimisticRemovalWhenTheRemoveLegFails', () async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: 'Bio', amount: '1', unit: 'PIECE'),
      ];
      itemsApi.removeError = const AppException(AppError(code: 'item.changeNotPermitted', message: 'debug'));
      final cubit = buildCubit();
      await cubit.bootstrap();
      const targetItem = Item(itemId: 'target-1', name: 'Milch', note: 'Bio', amount: '2', unit: 'PIECE');

      await cubit.mergeIntoTarget('i1', 'list-2', targetItem);

      expect(cubit.state.items, hasLength(1));
      expect(cubit.state.actionError?.code, 'item.changeNotPermitted');
      await cubit.close();
    });

    test('mergeIntoTarget_surfacesADistinctErrorWhenTheTargetUpdatedButTheSourceRemoveFailed', () async {
      // Regression (code review 2026-08-27): once the target quantity was adjusted, a failed source
      // removal must report the merge-specific code — not the raw remove error — so the member
      // re-removes the source rather than re-merging an already-summed target (double-count).
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: 'Bio', amount: '1', unit: 'PIECE'),
      ];
      itemsApi.removeError = const AppException(AppError(code: 'item.changeNotPermitted', message: 'debug'));
      final cubit = buildCubit();
      await cubit.bootstrap();
      const targetItem = Item(itemId: 'target-1', name: 'Milch', note: 'Bio', amount: '2', unit: 'PIECE');

      await cubit.mergeIntoTarget('i1', 'list-2', targetItem, adjustedAmount: '3', adjustedUnit: 'PIECE');

      expect(itemsApi.updateCallCount, 1); // the target update did apply
      expect(cubit.state.items, hasLength(1)); // the source row is restored
      expect(cubit.state.actionError?.code, 'list.moveMergeRemoveFailed');
      await cubit.close();
    });

    test('mergeIntoTarget_isANoOpOnAReadOnlyList', () async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: 'Bio', amount: '1', unit: 'PIECE'),
      ];
      final cubit = buildCubit(isReadOnly: true);
      await cubit.bootstrap();
      const targetItem = Item(itemId: 'target-1', name: 'Milch', note: 'Bio', amount: '2', unit: 'PIECE');

      await cubit.mergeIntoTarget('i1', 'list-2', targetItem, adjustedAmount: '3', adjustedUnit: 'PIECE');

      expect(itemsApi.updateCallCount, 0);
      expect(itemsApi.removeCallCount, 0);
      await cubit.close();
    });

    test('doesNotEmitAfterClose', () async {
      final cubit = buildCubit();
      await cubit.close();

      // Must not throw despite the cubit being closed (every emit is isClosed-guarded).
      await cubit.bootstrap();
    });

    test('bootstrap_loadsAndExposesSuggestionsOnAnOpenList', () async {
      itemSuggestionsApi.suggestionsToReturn = const [
        ItemSuggestion(name: 'Milch', note: 'Bio', amount: '2', unit: 'LITRE'),
      ];
      final cubit = buildCubit();

      await cubit.bootstrap();

      expect(cubit.state.suggestions, hasLength(1));
      expect(cubit.state.suggestions.single.name, 'Milch');
      await cubit.close();
    });

    test('bootstrap_neverFetchesSuggestionsOnAReadOnlyDoneList', () async {
      itemSuggestionsApi.suggestionsToReturn = const [
        ItemSuggestion(name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      final cubit = buildCubit(isReadOnly: true);

      await cubit.bootstrap();

      expect(cubit.state.suggestions, isEmpty);
      await cubit.close();
    });

    test('bootstrap_stillRendersItemsWhenTheSuggestionsLoadFails', () async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      itemSuggestionsApi.listError = const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      final cubit = buildCubit();

      await cubit.bootstrap();

      expect(cubit.state.status, ListDetailStatus.ready);
      expect(cubit.state.items, hasLength(1));
      expect(cubit.state.suggestions, isEmpty);
      await cubit.close();
    });

    test('bootstrap_keepsAnOptimisticUpsertTheServerSetDoesNotCarryYet', () async {
      // The projection is eventually consistent: a name added while the suggestion fetch was still
      // in flight is not in the returned snapshot. Replacing the cache wholesale would drop it back
      // out of autocomplete right after the member created it.
      final cubit = buildCubit();
      await cubit.bootstrap();
      await cubit.addItem(name: 'Käse', note: null, amount: '1', unit: 'PIECE');
      itemSuggestionsApi.suggestionsToReturn = const [
        ItemSuggestion(name: 'Milch', note: 'Bio', amount: '2', unit: 'LITRE'),
      ];

      await cubit.bootstrap();

      expect(cubit.state.suggestions.map((suggestion) => suggestion.name), ['Käse', 'Milch']);
      await cubit.close();
    });

    test('suggestionsMatching_ordersCaseInsensitivelySoALocalUpsertKeepsTheServersOrder', () async {
      itemSuggestionsApi.suggestionsToReturn = const [
        ItemSuggestion(name: 'Apfel', note: null, amount: '1', unit: 'PIECE'),
        ItemSuggestion(name: 'Zucker', note: null, amount: '1', unit: 'PACK'),
      ];
      final cubit = buildCubit();
      await cubit.bootstrap();

      // A lower-case name would land after every upper-case one under a raw UTF-16 `compareTo`.
      await cubit.addItem(name: 'birne', note: null, amount: '1', unit: 'PIECE');

      expect(cubit.state.suggestions.map((suggestion) => suggestion.name), ['Apfel', 'birne', 'Zucker']);
      await cubit.close();
    });

    test('suggestionsMatching_matchesByTrimmedCaseInsensitivePrefixAndOrdersAlphabetically', () async {
      itemSuggestionsApi.suggestionsToReturn = const [
        ItemSuggestion(name: 'Brot', note: null, amount: '1', unit: 'PACK'),
        ItemSuggestion(name: 'Milch', note: null, amount: '1', unit: 'LITRE'),
        ItemSuggestion(name: 'Milchreis', note: null, amount: '1', unit: 'PACK'),
      ];
      final cubit = buildCubit();
      await cubit.bootstrap();

      final matches = cubit.suggestionsMatching('  MIL ');

      expect(matches.map((suggestion) => suggestion.name), ['Milch', 'Milchreis']);
      await cubit.close();
    });

    test('suggestionsMatching_returnsEmptyForABlankQuery', () async {
      itemSuggestionsApi.suggestionsToReturn = const [
        ItemSuggestion(name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      final cubit = buildCubit();
      await cubit.bootstrap();

      expect(cubit.suggestionsMatching('   '), isEmpty);
      await cubit.close();
    });

    test('addItem_optimisticallyUpsertsTheSuggestionCacheWithTheNewName', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.addItem(name: 'Milch', note: 'Bio', amount: '2', unit: 'LITRE');

      expect(cubit.state.suggestions, hasLength(1));
      expect(cubit.state.suggestions.single.name, 'Milch');
      expect(cubit.state.suggestions.single.amount, '2');
      await cubit.close();
    });

    test('updateItem_refreshesTheSuggestionCacheWithTheEditedAttributes', () async {
      itemSuggestionsApi.suggestionsToReturn = const [
        ItemSuggestion(name: 'Milch', note: null, amount: '1', unit: 'LITRE'),
      ];
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'LITRE'),
      ];
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.updateItem('i1', name: 'Milch', note: 'Bio', amount: '2', unit: 'LITRE');

      expect(cubit.state.suggestions, hasLength(1));
      expect(cubit.state.suggestions.single.note, 'Bio');
      expect(cubit.state.suggestions.single.amount, '2');
      await cubit.close();
    });

    test('bootstrap_loadsTheHouseholdsActiveStores', () async {
      storesApi.storesToReturn = const [StoreSummary(storeId: 's1', name: 'Edeka')];
      final cubit = buildCubit();

      await cubit.bootstrap();

      expect(cubit.state.stores.map((store) => store.storeId), ['s1']);
      await cubit.close();
    });

    test('bootstrap_neverFetchesStoresOnAReadOnlyDoneList', () async {
      storesApi.storesToReturn = const [StoreSummary(storeId: 's1', name: 'Edeka')];
      final cubit = buildCubit(isReadOnly: true);

      await cubit.bootstrap();

      expect(cubit.state.stores, isEmpty);
      await cubit.close();
    });

    test('bootstrap_stillRendersItemsWhenTheStoresLoadFails', () async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      storesApi.listStoresError = const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      final cubit = buildCubit();

      await cubit.bootstrap();

      expect(cubit.state.status, ListDetailStatus.ready);
      expect(cubit.state.items, hasLength(1));
      expect(cubit.state.stores, isEmpty);
      await cubit.close();
    });

    group('storeFor', () {
      test('resolvesAnActiveStoreById', () async {
        storesApi.storesToReturn = const [StoreSummary(storeId: 's1', name: 'Edeka')];
        final cubit = buildCubit();
        await cubit.bootstrap();

        expect(cubit.storeFor('s1')?.name, 'Edeka');
      });

      test('returnsNullForAnAbsentId', () async {
        storesApi.storesToReturn = const [StoreSummary(storeId: 's1', name: 'Edeka')];
        final cubit = buildCubit();
        await cubit.bootstrap();

        expect(cubit.storeFor('archived-store'), isNull);
      });

      test('returnsNullForANullId', () async {
        final cubit = buildCubit();
        await cubit.bootstrap();

        expect(cubit.storeFor(null), isNull);
      });
    });

    group('assignStore', () {
      test('optimisticallySetsTheItemsStoreId', () async {
        itemsApi.itemsToReturn = const [
          Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
        ];
        storesApi.storesToReturn = const [StoreSummary(storeId: 's1', name: 'Edeka')];
        final cubit = buildCubit();
        await cubit.bootstrap();

        await cubit.assignStore('i1', 's1');

        expect(cubit.state.items.single.storeId, 's1');
        expect(itemsApi.lastAssignedItemId, 'i1');
        expect(itemsApi.lastAssignedStoreId, 's1');
        await cubit.close();
      });

      test('revertsOnFailureAndSurfacesAnInlineActionError', () async {
        itemsApi.itemsToReturn = const [
          Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
        ];
        storesApi.storesToReturn = const [StoreSummary(storeId: 's1', name: 'Edeka')];
        itemsApi.assignStoreError = const AppException(AppError(code: 'item.notFound', message: 'debug'));
        final cubit = buildCubit();
        await cubit.bootstrap();

        await cubit.assignStore('i1', 's1');

        expect(cubit.state.items.single.storeId, isNull);
        expect(cubit.state.actionError?.code, 'item.notFound');
        await cubit.close();
      });

      test('reusesOneCommandIdAcrossRetriesOfTheSameAssignmentAndFreshensAfterSuccess', () async {
        itemsApi.itemsToReturn = const [
          Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
        ];
        storesApi.storesToReturn = const [StoreSummary(storeId: 's1', name: 'Edeka')];
        itemsApi.assignStoreError = const AppException(AppError(code: 'network.unreachable', message: 'debug'));
        final cubit = buildCubit();
        await cubit.bootstrap();
        await cubit.assignStore('i1', 's1');
        itemsApi.assignStoreError = null;
        await cubit.assignStore('i1', 's1');

        expect(itemsApi.assignStoreCommandIds, hasLength(2));
        expect(itemsApi.assignStoreCommandIds.first, itemsApi.assignStoreCommandIds.last);

        // A third, same-store assign is a fresh intent (the second succeeded) — a new command id,
        // never the completed one (which the server would silently drop).
        await cubit.assignStore('i1', 's1');
        expect(itemsApi.assignStoreCommandIds, hasLength(3));
        expect(itemsApi.assignStoreCommandIds.last, isNot(itemsApi.assignStoreCommandIds[1]));
        await cubit.close();
      });

      test('isANoOpOnAReadOnlyList', () async {
        final cubit = buildCubit(isReadOnly: true);
        await cubit.bootstrap();

        await cubit.assignStore('i1', 's1');

        expect(itemsApi.assignStoreCallCount, 0);
        await cubit.close();
      });

      test('upsertsTheSuggestionCachesDefaultStoreForTheItemsName', () async {
        itemsApi.itemsToReturn = const [
          Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
        ];
        storesApi.storesToReturn = const [StoreSummary(storeId: 's1', name: 'Edeka')];
        itemSuggestionsApi.suggestionsToReturn = const [
          ItemSuggestion(name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
        ];
        final cubit = buildCubit();
        await cubit.bootstrap();

        await cubit.assignStore('i1', 's1');

        expect(cubit.state.suggestions.single.defaultStoreId, 's1');
        await cubit.close();
      });

      test('registersAnInlineCreatedStoreSoTheAssignedChipResolves', () async {
        // The picker returns a store the bootstrap cache does not hold — an inline-created one. It
        // must land in state.stores so storeFor resolves the just-assigned chip (read-your-writes),
        // rather than falling back to the „+ Geschäft" ghost chip (Story 2.6 review patch).
        itemsApi.itemsToReturn = const [
          Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
        ];
        storesApi.storesToReturn = const [StoreSummary(storeId: 's1', name: 'Edeka')];
        final cubit = buildCubit();
        await cubit.bootstrap();

        const created = StoreSummary(storeId: 's2', name: 'Netto');
        await cubit.assignStore('i1', 's2', store: created);

        expect(cubit.state.items.single.storeId, 's2');
        expect(cubit.storeFor('s2')?.name, 'Netto');
        await cubit.close();
      });
    });

    group('addItemFromSuggestion', () {
      test('assignsTheJustAddedItemWhenTheLastUsedStoreIsStillActive', () async {
        storesApi.storesToReturn = const [StoreSummary(storeId: 's1', name: 'Edeka')];
        const suggestion =
            ItemSuggestion(name: 'Milch', note: null, amount: '1', unit: 'PIECE', defaultStoreId: 's1');
        final cubit = buildCubit();
        await cubit.bootstrap();

        final succeeded = await cubit.addItemFromSuggestion(suggestion);

        expect(succeeded, isTrue);
        expect(cubit.state.items.single.storeId, 's1');
        expect(itemsApi.lastAssignedStoreId, 's1');
        await cubit.close();
      });

      test('skipsTheAssignWhenTheLastUsedStoreIsArchived', () async {
        storesApi.storesToReturn = const []; // s1 no longer active
        const suggestion =
            ItemSuggestion(name: 'Milch', note: null, amount: '1', unit: 'PIECE', defaultStoreId: 's1');
        final cubit = buildCubit();
        await cubit.bootstrap();

        final succeeded = await cubit.addItemFromSuggestion(suggestion);

        expect(succeeded, isTrue);
        expect(cubit.state.items.single.storeId, isNull);
        expect(itemsApi.assignStoreCallCount, 0);
        await cubit.close();
      });

      test('addsUnassignedWhenTheSuggestionHasNoLastUsedStore', () async {
        const suggestion = ItemSuggestion(name: 'Milch', note: null, amount: '1', unit: 'PIECE');
        final cubit = buildCubit();
        await cubit.bootstrap();

        final succeeded = await cubit.addItemFromSuggestion(suggestion);

        expect(succeeded, isTrue);
        expect(cubit.state.items.single.storeId, isNull);
        expect(itemsApi.assignStoreCallCount, 0);
        await cubit.close();
      });
    });
  });
}
