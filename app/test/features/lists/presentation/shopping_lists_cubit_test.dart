import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/lists/data/shopping_list_summary.dart';
import 'package:sgart/features/lists/presentation/shopping_lists_cubit.dart';
import 'package:sgart/features/lists/presentation/shopping_lists_state.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_shopping_lists_dependencies.dart';

void main() {
  group('ShoppingListsCubit', () {
    late FakeShoppingListsApi shoppingListsApi;

    setUp(() {
      shoppingListsApi = FakeShoppingListsApi();
    });

    ShoppingListsCubit buildCubit() =>
        ShoppingListsCubit(shoppingListsApi: shoppingListsApi, householdId: 'household-1');

    test('bootstrap_loadsTheOpenListsInCreationOrder', () async {
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'l1', name: 'Getränke', status: 'OPEN'),
        ShoppingListSummary(listId: 'l2', name: null, status: 'OPEN'),
      ];
      final cubit = buildCubit();

      await cubit.bootstrap();

      expect(cubit.state.status, ShoppingListsStatus.ready);
      expect(cubit.state.lists.map((list) => list.listId), ['l1', 'l2']);
      await cubit.close();
    });

    test('bootstrap_emitsFailureWhenTheLoadFails', () async {
      shoppingListsApi.listError =
          const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      final cubit = buildCubit();

      await cubit.bootstrap();

      expect(cubit.state.status, ShoppingListsStatus.failure);
      expect(cubit.state.loadError?.code, 'network.unreachable');
      await cubit.close();
    });

    test('createList_withANameOptimisticallyAppendsTheNamedList', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.createList('Wocheneinkauf');

      expect(shoppingListsApi.lastCreatedName, 'Wocheneinkauf');
      expect(cubit.state.lists.map((list) => list.name), contains('Wocheneinkauf'));
      await cubit.close();
    });

    test('createList_withABlankOrAbsentNameCreatesAnUnnamedList', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.createList(null);

      expect(shoppingListsApi.lastCreatedName, isNull);
      expect(cubit.state.lists.single.name, isNull);
      await cubit.close();
    });

    test('createList_reusesOneCommandIdAndListIdAcrossRetriesOfTheSameName', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();
      shoppingListsApi.createError =
          const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      await cubit.createList('Getränke');
      shoppingListsApi.createError = null;
      await cubit.createList('Getränke');

      expect(shoppingListsApi.createCommandIds, hasLength(2));
      expect(shoppingListsApi.createCommandIds.first, shoppingListsApi.createCommandIds.last);
      expect(shoppingListsApi.createListIds.first, shoppingListsApi.createListIds.last);
      await cubit.close();
    });

    test('createList_usesAFreshCommandIdWhenTheNameChangesBetweenAttempts', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();
      shoppingListsApi.createError =
          const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      await cubit.createList('Getränke');
      await cubit.createList('Wocheneinkauf');

      expect(shoppingListsApi.createCommandIds, hasLength(2));
      expect(shoppingListsApi.createCommandIds.first, isNot(shoppingListsApi.createCommandIds.last));
      await cubit.close();
    });

    test('createList_regeneratesTheCommandIdAfterASuccessfulCreateSoTheNextListIsNotDeduped', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.createList('Getränke');
      await cubit.createList('Wocheneinkauf');

      expect(shoppingListsApi.createCommandIds, hasLength(2));
      expect(shoppingListsApi.createCommandIds.first, isNot(shoppingListsApi.createCommandIds.last));
      expect(cubit.state.lists.map((list) => list.name), containsAll(['Getränke', 'Wocheneinkauf']));
      await cubit.close();
    });

    test('createList_surfacesARejectionAsAnInlineErrorWithoutLeavingReady', () async {
      shoppingListsApi.createError =
          const AppException(AppError(code: 'list.nameTooLong', message: 'debug'));
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.createList('x' * 200);

      expect(cubit.state.status, ShoppingListsStatus.ready); // no screen teardown
      expect(cubit.state.actionError?.code, 'list.nameTooLong');
      await cubit.close();
    });

    test('renameList_updatesTheMatchingListInPlace', () async {
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'l1', name: 'Getränke', status: 'OPEN'),
      ];
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.renameList('l1', 'Getränke 2');

      expect(shoppingListsApi.lastRenamedListId, 'l1');
      expect(shoppingListsApi.lastRenamedName, 'Getränke 2');
      expect(cubit.state.lists.single.name, 'Getränke 2');
      await cubit.close();
    });

    test('renameList_isAClientSideNoOpForABlankName', () async {
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'l1', name: 'Getränke', status: 'OPEN'),
      ];
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.renameList('l1', '   ');

      expect(shoppingListsApi.renameCallCount, 0);
      expect(cubit.state.lists.single.name, 'Getränke');
      await cubit.close();
    });

    test('renameList_surfacesARejectionAsAnInlineErrorWithoutLeavingReady', () async {
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'l1', name: 'Getränke', status: 'OPEN'),
      ];
      shoppingListsApi.renameError =
          const AppException(AppError(code: 'list.nameChangeNotPermitted', message: 'debug'));
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.renameList('l1', 'Neuer Name');

      expect(cubit.state.status, ShoppingListsStatus.ready);
      expect(cubit.state.actionError?.code, 'list.nameChangeNotPermitted');
      await cubit.close();
    });

    test('renameList_reusesOneCommandIdAcrossRetriesOfTheSameName', () async {
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'l1', name: 'Getränke', status: 'OPEN'),
      ];
      final cubit = buildCubit();
      await cubit.bootstrap();
      shoppingListsApi.renameError =
          const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      await cubit.renameList('l1', 'Getränke 2');
      shoppingListsApi.renameError = null;
      await cubit.renameList('l1', 'Getränke 2');

      expect(shoppingListsApi.renameCommandIds, hasLength(2));
      expect(shoppingListsApi.renameCommandIds.first, shoppingListsApi.renameCommandIds.last);
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
