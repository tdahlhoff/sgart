import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/households/data/households_api.dart';
import 'package:sgart/features/households/presentation/households_cubit.dart';
import 'package:sgart/features/households/presentation/households_state.dart';
import 'package:sgart/features/onboarding/presentation/onboarding_wizard_page.dart';
import 'package:sgart/features/stores/data/store_chain.dart';
import 'package:sgart/features/stores/data/store_chain_reference_cache.dart';
import 'package:sgart/features/stores/data/stores_api.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';
import 'package:sgart/shared/widgets/sgart_button.dart';

import '../../../support/fake_households_dependencies.dart';
import '../../../support/fake_stores_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('OnboardingWizardPage', () {
    late FakeHouseholdsApi householdsApi;
    late HouseholdsCubit householdsCubit;
    late FakeStoresApi storesApi;
    late FakeStoreChainReferenceCache referenceCache;

    const chains = [StoreChain(chainId: 'id-edeka', name: 'Edeka')];

    setUp(() {
      householdsApi = FakeHouseholdsApi()..createdHouseholdIdToReturn = 'hh-1';
      householdsCubit =
          HouseholdsCubit(householdsApi: householdsApi, activeHouseholdStore: FakeActiveHouseholdStore());
      storesApi = FakeStoresApi()..chainsToReturn = chains;
      referenceCache = FakeStoreChainReferenceCache(chains: chains);
    });

    tearDown(() => householdsCubit.close());

    Widget buildSubject() => wrapForTesting(
          MultiRepositoryProvider(
            providers: [
              RepositoryProvider<HouseholdsApi>.value(value: householdsApi),
              RepositoryProvider<StoresApi>.value(value: storesApi),
              RepositoryProvider<StoreChainReferenceCache>.value(value: referenceCache),
            ],
            child: BlocProvider<HouseholdsCubit>.value(
              value: householdsCubit,
              child: const OnboardingWizardPage(),
            ),
          ),
        );

    Future<void> nameAndAdvance(WidgetTester tester, {String name = 'Rita & Werner'}) async {
      await tester.enterText(find.byKey(const Key('onboarding-name-field')), name);
      await tester.tap(find.byKey(const Key('onboarding-name-next-button')));
      await tester.pumpAndSettle();
    }

    testWidgets('namingTheHouseholdCreatesItAndAdvancesToTheStoresStepWithoutLandingInTheApp',
        (tester) async {
      await tester.pumpWidget(buildSubject());

      await nameAndAdvance(tester);

      // The household was created via the reused create path, and we advanced to the stores step…
      expect(householdsApi.createCallCount, 1);
      expect(householdsApi.lastCreatedName, 'Rita & Werner');
      expect(find.byKey(const Key('onboarding-stores-next-button')), findsOneWidget);
      // …but we have NOT landed in the app yet (selectHousehold not called — still not the shell).
      expect(householdsCubit.state.status, isNot(HouseholdsStatus.shell));
    });

    testWidgets('theWizardShowsAThreeStepProgressIndicatorAcrossTheSteps', (tester) async {
      await tester.pumpWidget(buildSubject());
      expect(find.text('Schritt 1 von 3'), findsOneWidget);

      await nameAndAdvance(tester);
      expect(find.text('Schritt 2 von 3'), findsOneWidget);

      await tester.tap(find.byKey(const Key('onboarding-stores-next-button')));
      await tester.pumpAndSettle();
      expect(find.text('Schritt 3 von 3'), findsOneWidget);
    });

    testWidgets('skippingStoresAndInviteLandsSoloInTheCreatedHousehold', (tester) async {
      await tester.pumpWidget(buildSubject());

      await nameAndAdvance(tester);
      await tester.tap(find.byKey(const Key('onboarding-stores-skip-button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('onboarding-invite-finish-button')));
      await tester.pumpAndSettle();

      // No store was added (skipped), and the person landed in the created household.
      expect(storesApi.addCallCount, 0);
      expect(householdsCubit.state.status, HouseholdsStatus.shell);
      expect(householdsCubit.state.activeHousehold!.householdId, 'hh-1');
      expect(householdsCubit.state.activeHousehold!.name, 'Rita & Werner');
    });

    testWidgets('addingAStoreDuringOnboardingReusesTheStoreCreationPath', (tester) async {
      await tester.pumpWidget(buildSubject());
      await nameAndAdvance(tester);

      // The reused StoresManagementView keys (Story 1.8) are present on the stores step.
      await tester.enterText(find.byKey(const Key('store-name-field')), 'Edeka Schiedemann');
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('store-chain-suggestion')), findsOneWidget);

      await tester.tap(find.byKey(const Key('store-add-button')));
      await tester.pumpAndSettle();

      expect(storesApi.lastAddedName, 'Edeka Schiedemann');
      expect(storesApi.lastAddedChainId, 'id-edeka');
    });

    testWidgets('aRejectedNameShowsInlineAndDoesNotAdvance', (tester) async {
      householdsApi.createErrorToThrow =
          const AppException(AppError(code: 'household.nameRequired', message: 'debug'));
      await tester.pumpWidget(buildSubject());

      await tester.enterText(find.byKey(const Key('onboarding-name-field')), 'x');
      await tester.tap(find.byKey(const Key('onboarding-name-next-button')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('onboarding-name-error')), findsOneWidget);
      // Still on step 1 — the stores step never appeared.
      expect(find.text('Schritt 1 von 3'), findsOneWidget);
      expect(find.byKey(const Key('onboarding-stores-next-button')), findsNothing);
    });

    testWidgets('theInviteStepIsPresentButItsSendIsDeferredAndStatesPrivacy', (tester) async {
      await tester.pumpWidget(buildSubject());
      await nameAndAdvance(tester);
      await tester.tap(find.byKey(const Key('onboarding-stores-next-button')));
      await tester.pumpAndSettle();

      // Privacy stated up front on the invite step (AC3), including the actual copy.
      expect(find.byKey(const Key('onboarding-invite-privacy')), findsOneWidget);
      expect(find.text('Nur du und Eingeladene sehen euren Haushalt.'), findsOneWidget);
      // The send affordance is present but non-functional — deferred to Epic 4 (AC4).
      expect(find.byKey(const Key('onboarding-invite-deferred-note')), findsOneWidget);
      final sendButton =
          tester.widget<SgartButton>(find.byKey(const Key('onboarding-invite-send-button')));
      expect(sendButton.onPressed, isNull);

      // Tapping the disabled send does nothing — no landing, no invite mechanism exists here.
      await tester.tap(find.byKey(const Key('onboarding-invite-send-button')));
      await tester.pumpAndSettle();
      expect(householdsCubit.state.status, isNot(HouseholdsStatus.shell));
    });

    testWidgets('goingBackFromTheStoresStepReturnsToTheNameStepWithoutCreatingASecondHousehold',
        (tester) async {
      await tester.pumpWidget(buildSubject());
      await nameAndAdvance(tester);
      expect(householdsApi.createCallCount, 1);

      await tester.tap(find.byKey(const Key('onboarding-back-button')));
      await tester.pumpAndSettle();
      expect(find.text('Schritt 1 von 3'), findsOneWidget);

      // Once the household exists the name step is one-way: re-advancing does NOT create a second
      // household — the wizard already holds the created summary, so „Weiter" just moves forward
      // without re-submitting (Clarification 2 — never re-create/re-name).
      await tester.tap(find.byKey(const Key('onboarding-name-next-button')));
      await tester.pumpAndSettle();
      expect(find.text('Schritt 2 von 3'), findsOneWidget);
      expect(householdsApi.createCallCount, 1);
      expect(householdsCubit.state.status, isNot(HouseholdsStatus.shell));
    });

    testWidgets('backingOutOfTheNameStepAfterCreationLandsInTheCreatedHouseholdRatherThanStranding',
        (tester) async {
      await tester.pumpWidget(buildSubject());
      await nameAndAdvance(tester); // household created, now on the stores step
      await tester.tap(find.byKey(const Key('onboarding-back-button'))); // stores → name
      await tester.pumpAndSettle();
      expect(find.text('Schritt 1 von 3'), findsOneWidget);

      // „Zurück" from the name step after creation must not drop the person back on the choice
      // screen with the household stranded — it lands them in the created household (Clarification 2).
      await tester.tap(find.byKey(const Key('onboarding-back-button')));
      await tester.pumpAndSettle();
      expect(householdsCubit.state.status, HouseholdsStatus.shell);
      expect(householdsCubit.state.activeHousehold!.householdId, 'hh-1');
    });

    testWidgets('theSystemBackGestureAfterCreationLandsInTheCreatedHouseholdRatherThanStranding',
        (tester) async {
      await tester.pumpWidget(buildSubject());
      await nameAndAdvance(tester); // household created, now on the stores step

      // The Android system back gesture is intercepted by PopScope once the household exists, so it
      // finishes into the created household instead of stranding it behind the first-run choice.
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(householdsCubit.state.status, HouseholdsStatus.shell);
      expect(householdsCubit.state.activeHousehold!.householdId, 'hh-1');
    });
  });
}
