import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/stores/data/store_chain.dart';
import 'package:sgart/features/stores/data/store_summary.dart';
import 'package:sgart/features/stores/presentation/store_picker_sheet.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';
import 'package:sgart/shared/widgets/sgart_button.dart';

import '../../../support/fake_stores_dependencies.dart';
import '../../../support/widget_test_harness.dart';

/// Widget tests for the trip store-selection sheet (Story 3.1, AC1, AC3, AC4, UX-DR22, Cl. 4): the
/// multi-select checkbox path, the ≥1-confirm gate, the „+ Neues Geschäft" inline-create flow
/// adding a store to the selection, a duplicate-name rejection, and a regression proving the
/// existing 2.6 single-select `showStorePickerSheet` still returns one store on tap. Fakes only, no
/// network (CLAUDE.md §6).
void main() {
  group('showTripStoreSelectionSheet', () {
    late FakeStoresApi storesApi;
    late FakeStoreChainReferenceCache referenceCache;
    List<StoreSummary>? result;

    setUp(() {
      storesApi = FakeStoresApi();
      referenceCache = FakeStoreChainReferenceCache();
      result = null;
    });

    Widget buildSubject({List<StoreSummary> stores = const []}) => wrapForTesting(
          Builder(
            builder: (context) => Scaffold(
              body: ElevatedButton(
                onPressed: () async {
                  result = await showTripStoreSelectionSheet(
                    context,
                    stores: stores,
                    storesApi: storesApi,
                    referenceCache: referenceCache,
                    householdId: 'household-1',
                  );
                },
                child: const Text('open'),
              ),
            ),
          ),
        );

    testWidgets('showsTheActiveStoresAsCheckboxes', (tester) async {
      await tester.pumpWidget(buildSubject(
        stores: const [StoreSummary(storeId: 's1', name: 'Edeka'), StoreSummary(storeId: 's2', name: 'Netto')],
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('trip-store-selection-sheet')), findsOneWidget);
      expect(find.byKey(const Key('trip-store-option-s1')), findsOneWidget);
      expect(find.byKey(const Key('trip-store-option-s2')), findsOneWidget);
    });

    testWidgets('confirmIsDisabledWithZeroSelected', (tester) async {
      await tester.pumpWidget(buildSubject(
        stores: const [StoreSummary(storeId: 's1', name: 'Edeka')],
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      final confirmButton =
          tester.widget<SgartButton>(find.byKey(const Key('trip-store-selection-confirm')));
      expect(confirmButton.onPressed, isNull);
    });

    testWidgets('selectingOneOrMoreEnablesConfirmAndReturnsThem', (tester) async {
      await tester.pumpWidget(buildSubject(
        stores: const [StoreSummary(storeId: 's1', name: 'Edeka'), StoreSummary(storeId: 's2', name: 'Netto')],
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('trip-store-option-s1')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('trip-store-option-s2')));
      await tester.pumpAndSettle();

      final confirmButton =
          tester.widget<SgartButton>(find.byKey(const Key('trip-store-selection-confirm')));
      expect(confirmButton.onPressed, isNotNull);

      await tester.tap(find.byKey(const Key('trip-store-selection-confirm')));
      await tester.pumpAndSettle();

      expect(result, const [
        StoreSummary(storeId: 's1', name: 'Edeka'),
        StoreSummary(storeId: 's2', name: 'Netto'),
      ]);
    });

    testWidgets('togglingAStoreOffRemovesItFromTheSelection', (tester) async {
      await tester.pumpWidget(buildSubject(
        stores: const [StoreSummary(storeId: 's1', name: 'Edeka')],
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('trip-store-option-s1')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('trip-store-option-s1')));
      await tester.pumpAndSettle();

      final confirmButton =
          tester.widget<SgartButton>(find.byKey(const Key('trip-store-selection-confirm')));
      expect(confirmButton.onPressed, isNull);
    });

    testWidgets('addingANewStoreWithALiveChainSuggestionCreatesItAndAddsItToTheSelection', (tester) async {
      referenceCache.chains = const [StoreChain(chainId: 'c1', name: 'Aldi')];
      await tester.pumpWidget(buildSubject());
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('store-picker-new-name-field')), 'Aldi Süd');
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('store-picker-chain-suggestion')), findsOneWidget);

      await tester.tap(find.byKey(const Key('store-picker-add-new')));
      await tester.pumpAndSettle();

      expect(storesApi.lastAddedName, 'Aldi Süd');
      expect(storesApi.lastAddedChainId, 'c1');
      expect(find.byKey(Key('trip-store-option-${storesApi.lastAddedStoreId}')), findsOneWidget);

      final confirmButton =
          tester.widget<SgartButton>(find.byKey(const Key('trip-store-selection-confirm')));
      expect(confirmButton.onPressed, isNotNull);

      await tester.tap(find.byKey(const Key('trip-store-selection-confirm')));
      await tester.pumpAndSettle();

      expect(result, hasLength(1));
      expect(result!.single.name, 'Aldi Süd');
    });

    testWidgets('aDuplicateNameSurfacesAnInlineErrorWithoutClosing', (tester) async {
      storesApi.addError = const AppException(AppError(code: 'store.duplicateName', message: 'debug'));
      await tester.pumpWidget(buildSubject());
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('store-picker-new-name-field')), 'Edeka');
      await tester.pump();
      await tester.tap(find.byKey(const Key('store-picker-add-new')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('store-picker-error')), findsOneWidget);
      expect(find.byKey(const Key('trip-store-selection-sheet')), findsOneWidget);
      expect(result, isNull);
    });

    testWidgets('theExisting2Point6SingleSelectPickerStillReturnsOneStoreOnTap', (tester) async {
      // Regression (Cl. 4): the single-select sheet's behavior must stay unchanged.
      StoreSummary? singleSelectResult;
      await tester.pumpWidget(wrapForTesting(
        Builder(
          builder: (context) => Scaffold(
            body: ElevatedButton(
              onPressed: () async {
                singleSelectResult = await showStorePickerSheet(
                  context,
                  stores: const [StoreSummary(storeId: 's1', name: 'Edeka')],
                  storesApi: storesApi,
                  referenceCache: referenceCache,
                  householdId: 'household-1',
                );
              },
              child: const Text('open'),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('store-picker-option-s1')));
      await tester.pumpAndSettle();

      expect(singleSelectResult, const StoreSummary(storeId: 's1', name: 'Edeka'));
    });
  });
}
