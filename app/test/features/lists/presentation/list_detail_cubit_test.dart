import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/lists/data/item.dart';
import 'package:sgart/features/lists/presentation/list_detail_cubit.dart';
import 'package:sgart/features/lists/presentation/list_detail_state.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_items_dependencies.dart';

void main() {
  group('ListDetailCubit', () {
    late FakeItemsApi itemsApi;

    setUp(() {
      itemsApi = FakeItemsApi();
    });

    ListDetailCubit buildCubit({bool isReadOnly = false}) => ListDetailCubit(
          itemsApi: itemsApi,
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
  });
}
