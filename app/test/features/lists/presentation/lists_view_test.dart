import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/lists/data/item.dart';
import 'package:sgart/features/lists/data/items_api.dart';
import 'package:sgart/features/lists/data/shopping_list_summary.dart';
import 'package:sgart/features/lists/data/shopping_lists_api.dart';
import 'package:sgart/features/lists/presentation/lists_view.dart';
import 'package:sgart/features/lists/presentation/shopping_lists_cubit.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_items_dependencies.dart';
import '../../../support/fake_shopping_lists_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('ListsView', () {
    late FakeShoppingListsApi shoppingListsApi;
    late FakeItemsApi itemsApi;

    setUp(() {
      shoppingListsApi = FakeShoppingListsApi();
      itemsApi = FakeItemsApi();
    });

    Widget buildSubject() => wrapForTesting(
          RepositoryProvider<ItemsApi>.value(
            value: itemsApi,
            child: RepositoryProvider<ShoppingListsApi>.value(
              value: shoppingListsApi,
              child: BlocProvider(
                create: (_) => ShoppingListsCubit(
                  shoppingListsApi: shoppingListsApi,
                  householdId: 'household-1',
                )..bootstrap(),
                child: const Scaffold(body: ListsView()),
              ),
            ),
          ),
        );

    testWidgets('showsTheEmptyStateWhenThereAreNoLists', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('lists-empty-state')), findsOneWidget);
    });

    testWidgets('rendersANamedListByItsOwnName', (tester) async {
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'l1', name: 'Getränke', status: 'OPEN'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.text('Getränke'), findsOneWidget);
    });

    testWidgets('anUnnamedListRendersListeAtTheRightOrdinalCountingNamedListsToo', (tester) async {
      // A named list created first, then an unnamed one second — the unnamed one renders "Liste 2",
      // not "Liste 1" (AC2's easy-to-get-wrong case: the ordinal counts named lists too).
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'l1', name: 'Getränke', status: 'OPEN'),
        ShoppingListSummary(listId: 'l2', name: null, status: 'OPEN'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.text('Liste 2'), findsOneWidget);
      expect(find.text('Liste 1'), findsNothing);
    });

    testWidgets('creatingAListRefreshesAndShowsIt', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('lists-create-button')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('create-list-name-field')), 'Wocheneinkauf');
      await tester.tap(find.byKey(const Key('create-list-submit-button')));
      await tester.pumpAndSettle();

      expect(shoppingListsApi.lastCreatedName, 'Wocheneinkauf');
      expect(find.text('Wocheneinkauf'), findsOneWidget);
    });

    testWidgets('creatingAnUnnamedListShowsItAsListeOne', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('lists-create-button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('create-list-submit-button')));
      await tester.pumpAndSettle();

      expect(shoppingListsApi.lastCreatedName, isNull);
      expect(find.text('Liste 1'), findsOneWidget);
    });

    testWidgets('renamingAListUpdatesTheRow', (tester) async {
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'l1', name: 'Getränke', status: 'OPEN'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('list-rename-button-l1')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('rename-list-name-field')), 'Getränke 2');
      await tester.tap(find.byKey(const Key('rename-list-submit-button')));
      await tester.pumpAndSettle();

      expect(shoppingListsApi.lastRenamedListId, 'l1');
      expect(shoppingListsApi.lastRenamedName, 'Getränke 2');
      expect(find.text('Getränke 2'), findsOneWidget);
    });

    testWidgets('aBlankRenameIsBlockedClientSide', (tester) async {
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'l1', name: 'Getränke', status: 'OPEN'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('list-rename-button-l1')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('rename-list-name-field')), '   ');
      await tester.pumpAndSettle();
      // Disabled while blank — tapping does nothing (no round-trip, the sheet stays open).
      await tester.tap(find.byKey(const Key('rename-list-submit-button')));
      await tester.pumpAndSettle();

      expect(shoppingListsApi.renameCallCount, 0);
      expect(find.byKey(const Key('rename-list-name-field')), findsOneWidget);
    });

    testWidgets('aCodedErrorMapsToLocalizedCopy', (tester) async {
      shoppingListsApi.createError =
          const AppException(AppError(code: 'list.nameTooLong', message: 'debug'));
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('lists-create-button')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('create-list-name-field')), 'x' * 130);
      await tester.tap(find.byKey(const Key('create-list-submit-button')));
      await tester.pumpAndSettle();

      expect(
        find.text('Der Name der Liste ist zu lang. Bitte wähle einen kürzeren Namen.'),
        findsOneWidget,
      );
    });

    testWidgets('defaultsToOffenAndShowsTheCreateAction', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('lists-create-button')), findsOneWidget);
      expect(find.byKey(const Key('lists-archive-empty-state')), findsNothing);
    });

    testWidgets('switchingToErledigtRendersTheArchiveReadOnly', (tester) async {
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'l1', name: 'Getränke', status: 'OPEN'),
      ];
      shoppingListsApi.doneListsToReturn = const [
        ShoppingListSummary(listId: 'd1', name: 'Alte Liste', status: 'DONE'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.text('Erledigt'));
      await tester.pumpAndSettle();

      expect(find.text('Alte Liste'), findsOneWidget);
      expect(find.byKey(const Key('lists-create-button')), findsNothing);
      expect(find.byKey(const Key('list-rename-button-d1')), findsNothing);
    });

    testWidgets('anEmptyArchiveShowsTheArchiveEmptyState', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.text('Erledigt'));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('lists-archive-empty-state')), findsOneWidget);
    });

    testWidgets('switchingBackToOffenRestoresCreateAndRename', (tester) async {
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'l1', name: 'Getränke', status: 'OPEN'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.text('Erledigt'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Offen'));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('lists-create-button')), findsOneWidget);
      expect(find.byKey(const Key('list-rename-button-l1')), findsOneWidget);
    });

    testWidgets('anArchiveLoadFailureMapsToLocalizedCopyWithoutTearingDownTheOpenView', (tester) async {
      shoppingListsApi.doneListError =
          const AppException(AppError(code: 'list.nameTooLong', message: 'debug'));
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.text('Erledigt'));
      await tester.pumpAndSettle();

      expect(
        find.text('Der Name der Liste ist zu lang. Bitte wähle einen kürzeren Namen.'),
        findsOneWidget,
      );

      await tester.tap(find.text('Offen'));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('lists-create-button')), findsOneWidget);
    });

    testWidgets('theArchiveFailureOffersARetryThatReloadsTheArchive', (tester) async {
      shoppingListsApi.doneListError =
          const AppException(AppError(code: 'list.nameTooLong', message: 'debug'));
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.text('Erledigt'));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('lists-archive-error')), findsOneWidget);

      // The transient error clears; the retry affordance reloads the archive in place.
      shoppingListsApi.doneListError = null;
      shoppingListsApi.doneListsToReturn = const [
        ShoppingListSummary(listId: 'd1', name: 'Alte Liste', status: 'DONE'),
      ];
      await tester.tap(find.byKey(const Key('lists-archive-retry-button')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('lists-archive-error')), findsNothing);
      expect(find.text('Alte Liste'), findsOneWidget);
    });

    testWidgets('anOpenListRowShowsTheOffenStatusLabel', (tester) async {
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'l1', name: 'Getränke', status: 'OPEN'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('list-status-l1')), findsOneWidget);
    });

    testWidgets('theSegmentedControlExposesLocalizedLabels', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.text('Offen'), findsOneWidget);
      expect(find.text('Erledigt'), findsOneWidget);
    });

    testWidgets('anOpenRowShowsItsItemCount', (tester) async {
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'l1', name: 'Getränke', status: 'OPEN', itemCount: 3),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('list-item-count-l1')), findsOneWidget);
      expect(find.text('3 Artikel'), findsOneWidget);
    });

    testWidgets('anEmptyOpenRowShowsZeroItems', (tester) async {
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'l1', name: 'Getränke', status: 'OPEN'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.text('0 Artikel'), findsOneWidget);
    });

    testWidgets('tappingAnOpenRowNavigatesToTheListDetailScreen', (tester) async {
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'l1', name: 'Getränke', status: 'OPEN'),
      ];
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('list-row-l1')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('item-row-i1')), findsOneWidget);
      expect(find.byKey(const Key('item-add-button')), findsOneWidget);
    });

    testWidgets('tappingADoneRowNavigatesToAReadOnlyListDetailScreen', (tester) async {
      shoppingListsApi.doneListsToReturn = const [
        ShoppingListSummary(listId: 'd1', name: 'Alte Liste', status: 'DONE'),
      ];
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();
      await tester.tap(find.text('Erledigt'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('list-archive-row-d1')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('item-row-i1')), findsOneWidget);
      expect(find.byKey(const Key('item-add-button')), findsNothing);
      expect(find.byKey(const Key('item-edit-button-i1')), findsNothing);
    });
  });
}
