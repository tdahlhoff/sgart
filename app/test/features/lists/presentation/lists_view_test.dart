import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/lists/data/shopping_list_summary.dart';
import 'package:sgart/features/lists/presentation/lists_view.dart';
import 'package:sgart/features/lists/presentation/shopping_lists_cubit.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_shopping_lists_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('ListsView', () {
    late FakeShoppingListsApi shoppingListsApi;

    setUp(() {
      shoppingListsApi = FakeShoppingListsApi();
    });

    Widget buildSubject() => wrapForTesting(
          BlocProvider(
            create: (_) => ShoppingListsCubit(
              shoppingListsApi: shoppingListsApi,
              householdId: 'household-1',
            )..bootstrap(),
            child: const Scaffold(body: ListsView()),
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
  });
}
