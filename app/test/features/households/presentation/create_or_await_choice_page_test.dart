import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/households/data/households_api.dart';
import 'package:sgart/features/households/presentation/create_or_await_choice_page.dart';
import 'package:sgart/features/households/presentation/households_cubit.dart';
import 'package:sgart/features/stores/data/store_chain_reference_cache.dart';
import 'package:sgart/features/stores/data/stores_api.dart';

import '../../../support/fake_households_dependencies.dart';
import '../../../support/fake_stores_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('CreateOrAwaitChoicePage', () {
    late FakeHouseholdsApi householdsApi;
    late HouseholdsCubit householdsCubit;
    late FakeStoresApi storesApi;
    late FakeStoreChainReferenceCache referenceCache;

    setUp(() {
      householdsApi = FakeHouseholdsApi();
      householdsCubit =
          HouseholdsCubit(householdsApi: householdsApi, activeHouseholdStore: FakeActiveHouseholdStore());
      storesApi = FakeStoresApi();
      referenceCache = FakeStoreChainReferenceCache();
    });

    tearDown(() => householdsCubit.close());

    // The providers sit *below* the MaterialApp's Navigator (as they do in production, created
    // inside FirstRunRouter under the root Navigator). Launching the onboarding wizard must still
    // reach them — a regression guard for the ProviderNotFoundException that crashed the primary
    // AC1 path when a pushed route escaped the FirstRunRouter providers.
    Widget buildSubject() => wrapForTesting(
          MultiRepositoryProvider(
            providers: [
              RepositoryProvider<HouseholdsApi>.value(value: householdsApi),
              RepositoryProvider<StoresApi>.value(value: storesApi),
              RepositoryProvider<StoreChainReferenceCache>.value(value: referenceCache),
            ],
            child: BlocProvider<HouseholdsCubit>.value(
              value: householdsCubit,
              child: const CreateOrAwaitChoicePage(),
            ),
          ),
        );

    testWidgets('statesPrivacyUpFrontOnTheWelcomeChoice', (tester) async {
      await tester.pumpWidget(buildSubject());

      expect(find.byKey(const Key('onboarding-choice-privacy')), findsOneWidget);
      expect(find.text('Deine Daten bleiben bei dir.'), findsOneWidget);
    });

    testWidgets('choosingCreateLaunchesTheOnboardingWizardWithoutEscapingItsProviders', (tester) async {
      await tester.pumpWidget(buildSubject());

      await tester.tap(find.byKey(const Key('create-household-choice-button')));
      await tester.pumpAndSettle();

      // The wizard's name step built without a ProviderNotFoundException — it reached HouseholdsApi
      // (its CreateHouseholdCubit) and, once past the name step, StoresApi/StoreChainReferenceCache.
      expect(find.byKey(const Key('onboarding-name-field')), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  });
}
