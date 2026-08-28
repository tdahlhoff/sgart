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

/// Widget tests for the reusable store picker sheet (Story 2.6, AC1, AC2, UX-DR22, Cl. 8): the
/// existing-store tap path, the „+ Neues Geschäft" inline-create flow with a live chain suggestion,
/// a duplicate-name rejection, and the blank-name guard. Fakes only, no network (CLAUDE.md §6).
void main() {
  group('showStorePickerSheet', () {
    late FakeStoresApi storesApi;
    late FakeStoreChainReferenceCache referenceCache;
    StoreSummary? result;

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
                  result = await showStorePickerSheet(
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

    testWidgets('showsTheActiveStoresPassedIn', (tester) async {
      await tester.pumpWidget(buildSubject(
        stores: const [StoreSummary(storeId: 's1', name: 'Edeka'), StoreSummary(storeId: 's2', name: 'Netto')],
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('store-picker-sheet')), findsOneWidget);
      expect(find.byKey(const Key('store-picker-option-s1')), findsOneWidget);
      expect(find.byKey(const Key('store-picker-option-s2')), findsOneWidget);
    });

    testWidgets('tappingAnExistingStoreReturnsIt', (tester) async {
      await tester.pumpWidget(buildSubject(stores: const [StoreSummary(storeId: 's1', name: 'Edeka')]));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('store-picker-option-s1')));
      await tester.pumpAndSettle();

      expect(result, const StoreSummary(storeId: 's1', name: 'Edeka'));
    });

    testWidgets('addingANewStoreWithALiveChainSuggestionCreatesAndReturnsIt', (tester) async {
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
      expect(result?.name, 'Aldi Süd');
      expect(result?.chainId, 'c1');
    });

    testWidgets('clearingTheSuggestionAddsAnUnlinkedStore', (tester) async {
      referenceCache.chains = const [StoreChain(chainId: 'c1', name: 'Aldi')];
      await tester.pumpWidget(buildSubject());
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('store-picker-new-name-field')), 'Aldi Süd');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('store-picker-chain-clear-button')));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('store-picker-add-new')));
      await tester.pumpAndSettle();

      expect(storesApi.lastAddedChainId, isNull);
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
      expect(find.byKey(const Key('store-picker-sheet')), findsOneWidget);
      expect(result, isNull);
    });

    testWidgets('aBlankNameGuardsTheAddButtonClientSide', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      final addButton = tester.widget<SgartButton>(find.byKey(const Key('store-picker-add-new')));
      expect(addButton.onPressed, isNull);
      expect(storesApi.addCallCount, 0);
    });
  });
}
