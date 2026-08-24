import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/stores/data/store_chain.dart';
import 'package:sgart/features/stores/data/store_chain_reference_cache.dart';
import 'package:sgart/features/stores/data/store_summary.dart';
import 'package:sgart/features/stores/data/stores_api.dart';
import 'package:sgart/features/stores/presentation/manage_stores_page.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_stores_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('ManageStoresPage', () {
    late FakeStoresApi storesApi;
    late FakeStoreChainReferenceCache referenceCache;

    const chains = [
      StoreChain(chainId: 'id-edeka', name: 'Edeka'),
      StoreChain(chainId: 'id-aldi', name: 'Aldi'),
    ];

    setUp(() {
      storesApi = FakeStoresApi()..chainsToReturn = chains;
      referenceCache = FakeStoreChainReferenceCache(chains: chains);
    });

    Widget buildSubject() => wrapForTesting(
          MultiRepositoryProvider(
            providers: [
              RepositoryProvider<StoresApi>.value(value: storesApi),
              RepositoryProvider<StoreChainReferenceCache>.value(value: referenceCache),
            ],
            child: const ManageStoresPage(householdId: 'household-1'),
          ),
        );

    testWidgets('rendersActiveStoresWithTheirChainBadge', (tester) async {
      storesApi.storesToReturn = const [
        StoreSummary(storeId: 's1', name: 'Edeka Schiedemann', chainId: 'id-edeka'),
        StoreSummary(storeId: 's2', name: 'Wochenmarkt'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.text('Edeka Schiedemann'), findsOneWidget);
      expect(find.text('Wochenmarkt'), findsOneWidget);
      // The linked store shows a chain badge; the unlinked one does not.
      expect(find.byKey(const Key('store-chain-badge-s1')), findsOneWidget);
      expect(find.byKey(const Key('store-chain-badge-s2')), findsNothing);
    });

    testWidgets('showsTheEmptyStateWhenThereAreNoStores', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('stores-empty-state')), findsOneWidget);
    });

    testWidgets('addingAStoreShowsTheChainSuggestionThenAdds', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('store-name-field')), 'Edeka Schiedemann');
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('store-chain-suggestion')), findsOneWidget);

      await tester.tap(find.byKey(const Key('store-add-button')));
      await tester.pumpAndSettle();

      expect(storesApi.lastAddedName, 'Edeka Schiedemann');
      expect(storesApi.lastAddedChainId, 'id-edeka');
    });

    testWidgets('changingTheSuggestedChainPicksADifferentChainFromThePicker', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('store-name-field')), 'Edeka Schiedemann');
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('store-chain-suggestion')), findsOneWidget);

      // Open the „ändern" picker and choose Aldi instead of the auto-matched Edeka.
      await tester.tap(find.byKey(const Key('store-chain-change-button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('store-chain-option-id-aldi')));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('store-add-button')));
      await tester.pumpAndSettle();

      expect(storesApi.lastAddedChainId, 'id-aldi');
    });

    testWidgets('showsTheArchiveHelperCopyAndArchivesOnRemove', (tester) async {
      storesApi.storesToReturn = const [StoreSummary(storeId: 's1', name: 'Edeka')];
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('stores-archive-helper')), findsOneWidget);

      await tester.tap(find.byKey(const Key('store-remove-button-s1')));
      await tester.pumpAndSettle();

      expect(storesApi.lastArchivedStoreId, 's1');
      expect(find.byKey(const Key('store-row-s1')), findsNothing);
    });

    testWidgets('showsAnInlineErrorWhenAddingADuplicateName', (tester) async {
      storesApi.addError =
          const AppException(AppError(code: 'store.duplicateName', message: 'debug'));
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('store-name-field')), 'Edeka');
      await tester.pumpAndSettle(); // let the Add button enable now the field is non-blank
      await tester.tap(find.byKey(const Key('store-add-button')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('stores-action-error')), findsOneWidget);
    });
  });
}
