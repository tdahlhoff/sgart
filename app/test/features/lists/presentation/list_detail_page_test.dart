import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/lists/data/item.dart';
import 'package:sgart/features/lists/presentation/list_detail_cubit.dart';
import 'package:sgart/features/lists/presentation/list_detail_page.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_items_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('ListDetailPage', () {
    late FakeItemsApi itemsApi;

    setUp(() {
      itemsApi = FakeItemsApi();
    });

    Widget buildSubject({bool isReadOnly = false}) => wrapForTesting(
          BlocProvider(
            create: (_) => ListDetailCubit(
              itemsApi: itemsApi,
              householdId: 'household-1',
              listId: 'list-1',
              isReadOnly: isReadOnly,
            )..bootstrap(),
            child: const ListDetailPage(title: 'Wocheneinkauf'),
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

    testWidgets('addingAnItemShowsItInTheList', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('item-add-button')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('item-name-field')), 'Milch');
      await tester.pump();
      await tester.tap(find.byKey(const Key('item-form-submit-button')));
      await tester.pumpAndSettle();

      expect(itemsApi.lastAddedName, 'Milch');
      expect(itemsApi.lastAddedAmount, '1');
      expect(itemsApi.lastAddedUnit, 'PIECE');
      expect(find.text('Milch'), findsOneWidget);
    });

    testWidgets('aGermanCommaDecimalAmountIsNormalizedToADotBeforeSending', (tester) async {
      // Regression: the whole UI is de-DE (amounts render „0,5 kg"), so a member enters „1,5"; the
      // backend parses with BigDecimal, which only accepts a dot. Without client normalization the
      // valid fractional quantity is rejected 400 — fractional amounts would be unreachable.
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('item-add-button')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('item-name-field')), 'Hackfleisch');
      await tester.enterText(find.byKey(const Key('item-amount-field')), '1,5');
      await tester.pump();
      await tester.tap(find.byKey(const Key('item-form-submit-button')));
      await tester.pumpAndSettle();

      expect(itemsApi.lastAddedAmount, '1.5');
    });

    testWidgets('aNonNumericAmountIsBlockedClientSide', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('item-add-button')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('item-name-field')), 'Milch');
      await tester.enterText(find.byKey(const Key('item-amount-field')), 'abc');
      await tester.pump();
      await tester.tap(find.byKey(const Key('item-form-submit-button')));
      await tester.pumpAndSettle();

      // Submit stays disabled — no round-trip, the sheet stays open with the typed values.
      expect(itemsApi.addCallCount, 0);
      expect(find.byKey(const Key('item-amount-field')), findsOneWidget);
    });

    testWidgets('theUnitDropdownDefaultsToStueckAndOffersTheOtherUnits', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('item-add-button')));
      await tester.pumpAndSettle();

      expect(find.text('Stück'), findsOneWidget);

      await tester.tap(find.byKey(const Key('item-unit-dropdown')));
      await tester.pumpAndSettle();

      expect(find.text('kg').hitTestable(), findsOneWidget);
      await tester.tap(find.text('kg').last);
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('item-name-field')), 'Hackfleisch');
      await tester.pump();
      await tester.tap(find.byKey(const Key('item-form-submit-button')));
      await tester.pumpAndSettle();

      expect(itemsApi.lastAddedUnit, 'KILOGRAM');
    });

    testWidgets('aBlankNameIsBlockedClientSide', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('item-add-button')));
      await tester.pumpAndSettle();
      // Name field starts blank — submit disabled — tapping does nothing (no round-trip).
      await tester.tap(find.byKey(const Key('item-form-submit-button')));
      await tester.pumpAndSettle();

      expect(itemsApi.addCallCount, 0);
      expect(find.byKey(const Key('item-name-field')), findsOneWidget);
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

      await tester.tap(find.byKey(const Key('item-add-button')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('item-name-field')), 'Milch');
      await tester.pump();
      await tester.tap(find.byKey(const Key('item-form-submit-button')));
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

    testWidgets('aReadOnlyDoneListShowsNoAddEditOrRemoveAffordances', (tester) async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      await tester.pumpWidget(buildSubject(isReadOnly: true));
      await tester.pumpAndSettle();

      expect(find.text('Milch'), findsOneWidget);
      expect(find.byKey(const Key('item-add-button')), findsNothing);
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
  });
}
