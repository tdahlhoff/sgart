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

    test('doesNotEmitAfterClose', () async {
      final cubit = buildCubit();
      await cubit.close();

      // Must not throw despite the cubit being closed (every emit is isClosed-guarded).
      await cubit.bootstrap();
    });
  });
}
