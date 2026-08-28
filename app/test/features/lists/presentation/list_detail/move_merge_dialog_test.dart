import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/lists/data/item.dart';
import 'package:sgart/features/lists/presentation/list_detail/list_detail_cubit.dart';
import 'package:sgart/features/lists/presentation/list_detail/move_merge_dialog.dart';

import '../../../../support/fake_item_suggestions_api.dart';
import '../../../../support/fake_items_dependencies.dart';
import '../../../../support/fake_stores_dependencies.dart';
import '../../../../support/fake_trips_dependencies.dart';
import '../../../../support/widget_test_harness.dart';

/// Widget tests for the quantity-merge sheet (Story 2.4, AC4, Clarification 3): pre-fill rules and
/// that each confirm action calls the right cubit path.
void main() {
  group('move merge dialog', () {
    late FakeItemsApi itemsApi;
    late FakeItemSuggestionsApi itemSuggestionsApi;
    late FakeStoresApi storesApi;
    late FakeTripsApi tripsApi;
    late ListDetailCubit cubit;

    setUp(() {
      itemsApi = FakeItemsApi();
      itemSuggestionsApi = FakeItemSuggestionsApi();
      storesApi = FakeStoresApi();
      tripsApi = FakeTripsApi();
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Milch', note: 'Bio', amount: '1', unit: 'PIECE'),
      ];
      cubit = ListDetailCubit(
        itemsApi: itemsApi,
        itemSuggestionsApi: itemSuggestionsApi,
        storesApi: storesApi,
        tripsApi: tripsApi,
        householdId: 'household-1',
        listId: 'source-list',
        isReadOnly: false,
      );
    });

    tearDown(() => cubit.close());

    Widget buildSubject({required Item sourceItem, required Item targetItem}) => wrapForTesting(
          BlocProvider<ListDetailCubit>.value(
            value: cubit,
            child: Builder(
              builder: (context) => Scaffold(
                body: ElevatedButton(
                  onPressed: () => showMoveMergeDialog(
                    context,
                    cubit: cubit,
                    sourceItem: sourceItem,
                    targetItem: targetItem,
                    targetListId: 'target-list',
                    targetListName: 'Getränke',
                  ),
                  child: const Text('open'),
                ),
              ),
            ),
          ),
        );

    testWidgets('preFillsTheSumWhenTheUnitsMatch', (tester) async {
      await cubit.bootstrap();
      const sourceItem = Item(itemId: 'i1', name: 'Milch', note: 'Bio', amount: '1', unit: 'PIECE');
      const targetItem = Item(itemId: 'target-1', name: 'Milch', note: 'Bio', amount: '2', unit: 'PIECE');
      await tester.pumpWidget(buildSubject(sourceItem: sourceItem, targetItem: targetItem));
      await tester.tap(find.byType(ElevatedButton));
      await tester.pumpAndSettle();

      final field = tester.widget<TextField>(find.byKey(const Key('move-merge-amount-field')));
      expect(field.controller?.text, '3');
    });

    testWidgets('preFillsAndSubmitsASumOfAtLeast1000WithoutAThousandsSeparatorCorruption', (tester) async {
      // Regression (code review 2026-08-27): a sum ≥ 1000 must not be grouped with the German
      // thousands `.` ("1.600"), which the confirm parser would read back as 1.6 — a 1000× loss.
      await cubit.bootstrap();
      const sourceItem = Item(itemId: 'i1', name: 'Mehl', note: null, amount: '800', unit: 'GRAM');
      const targetItem = Item(itemId: 'target-1', name: 'Mehl', note: null, amount: '800', unit: 'GRAM');
      await tester.pumpWidget(buildSubject(sourceItem: sourceItem, targetItem: targetItem));
      await tester.tap(find.byType(ElevatedButton));
      await tester.pumpAndSettle();

      final field = tester.widget<TextField>(find.byKey(const Key('move-merge-amount-field')));
      expect(field.controller?.text, '1600');

      await tester.tap(find.byKey(const Key('move-merge-confirm-button')));
      await tester.pumpAndSettle();

      expect(itemsApi.lastUpdatedAmount, '1600');
    });

    testWidgets('preFillsTheTargetQuantityWhenTheUnitsDiffer', (tester) async {
      await cubit.bootstrap();
      const sourceItem = Item(itemId: 'i1', name: 'Milch', note: 'Bio', amount: '500', unit: 'GRAM');
      const targetItem = Item(itemId: 'target-1', name: 'Milch', note: 'Bio', amount: '2', unit: 'PIECE');
      await tester.pumpWidget(buildSubject(sourceItem: sourceItem, targetItem: targetItem));
      await tester.tap(find.byType(ElevatedButton));
      await tester.pumpAndSettle();

      final field = tester.widget<TextField>(find.byKey(const Key('move-merge-amount-field')));
      expect(field.controller?.text, '2');
    });

    testWidgets('confirmingWithAnAdjustmentUpdatesTheTargetThenRemovesTheSource', (tester) async {
      await cubit.bootstrap();
      const sourceItem = Item(itemId: 'i1', name: 'Milch', note: 'Bio', amount: '1', unit: 'PIECE');
      const targetItem = Item(itemId: 'target-1', name: 'Milch', note: 'Bio', amount: '2', unit: 'PIECE');
      await tester.pumpWidget(buildSubject(sourceItem: sourceItem, targetItem: targetItem));
      await tester.tap(find.byType(ElevatedButton));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('move-merge-confirm-button')));
      await tester.pumpAndSettle();

      expect(itemsApi.lastUpdatedItemId, 'target-1');
      expect(itemsApi.lastUpdatedAmount, '3');
      expect(itemsApi.lastRemovedItemId, 'i1');
    });

    testWidgets('confirmingUnchangedOnlyRemovesTheSource', (tester) async {
      await cubit.bootstrap();
      const sourceItem = Item(itemId: 'i1', name: 'Milch', note: 'Bio', amount: '1', unit: 'PIECE');
      const targetItem = Item(itemId: 'target-1', name: 'Milch', note: 'Bio', amount: '2', unit: 'PIECE');
      await tester.pumpWidget(buildSubject(sourceItem: sourceItem, targetItem: targetItem));
      await tester.tap(find.byType(ElevatedButton));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('move-merge-unchanged-button')));
      await tester.pumpAndSettle();

      expect(itemsApi.updateCallCount, 0);
      expect(itemsApi.lastRemovedItemId, 'i1');
    });

    testWidgets('dismissingTheSheetCallsNeitherCubitPath', (tester) async {
      await cubit.bootstrap();
      const sourceItem = Item(itemId: 'i1', name: 'Milch', note: 'Bio', amount: '1', unit: 'PIECE');
      const targetItem = Item(itemId: 'target-1', name: 'Milch', note: 'Bio', amount: '2', unit: 'PIECE');
      await tester.pumpWidget(buildSubject(sourceItem: sourceItem, targetItem: targetItem));
      await tester.tap(find.byType(ElevatedButton));
      await tester.pumpAndSettle();

      // Tap outside the sheet to dismiss it — a cancel.
      await tester.tapAt(const Offset(20, 20));
      await tester.pumpAndSettle();

      expect(itemsApi.updateCallCount, 0);
      expect(itemsApi.removeCallCount, 0);
    });
  });
}
