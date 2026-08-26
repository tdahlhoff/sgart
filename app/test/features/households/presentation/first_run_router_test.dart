import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/presentation/auth_cubit.dart';
import 'package:sgart/features/households/data/household_summary.dart';
import 'package:sgart/features/households/presentation/first_run_router.dart';
import 'package:sgart/features/households/presentation/households_cubit.dart';
import 'package:sgart/features/lists/data/shopping_lists_api.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_auth_dependencies.dart';
import '../../../support/fake_households_dependencies.dart';
import '../../../support/fake_shopping_lists_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('FirstRunRouterBody', () {
    late FakeHouseholdsApi householdsApi;
    late HouseholdsCubit cubit;
    late AuthCubit authCubit;

    late FakeActiveHouseholdStore activeHouseholdStore;

    setUp(() async {
      householdsApi = FakeHouseholdsApi();
      activeHouseholdStore = FakeActiveHouseholdStore();
      cubit = HouseholdsCubit(householdsApi: householdsApi, activeHouseholdStore: activeHouseholdStore);
      // The shell's Profil tab reads AuthCubit at build time (Story 1.11) — provide an
      // authenticated ancestor even for tests that only exercise the household-count routing.
      authCubit = await buildAuthenticatedAuthCubit();
    });

    tearDown(() async {
      await cubit.close();
      await authCubit.close();
    });

    Widget buildSubject() => wrapForTesting(
          BlocProvider<AuthCubit>.value(
            value: authCubit,
            child: RepositoryProvider<ShoppingListsApi>.value(
              value: FakeShoppingListsApi(),
              child: BlocProvider<HouseholdsCubit>.value(value: cubit, child: const FirstRunRouterBody()),
            ),
          ),
        );

    testWidgets('showsTheCreateOrAwaitChoiceForACallerWithZeroHouseholds', (tester) async {
      householdsApi.householdsToReturn = const [];
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pump();

      expect(find.byKey(const Key('create-household-choice-button')), findsOneWidget);
      expect(find.byKey(const Key('await-invite-choice-button')), findsOneWidget);
    });

    testWidgets('showsTheShellForACallerWithExactlyOneHousehold', (tester) async {
      householdsApi.householdsToReturn = const [
        HouseholdSummary(householdId: 'id-1', name: 'Familie Muster'),
      ];
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pump();

      final chip = find.byKey(const Key('switcher-chip'));
      expect(chip, findsOneWidget);
      expect(find.descendant(of: chip, matching: find.text('Familie Muster')), findsOneWidget);
      expect(find.byKey(const Key('create-household-choice-button')), findsNothing);
    });

    testWidgets('showsTheSelectionScreenForACallerWithSeveralHouseholds', (tester) async {
      householdsApi.householdsToReturn = const [
        HouseholdSummary(householdId: 'id-1', name: 'Familie Muster'),
        HouseholdSummary(householdId: 'id-2', name: 'WG Sonnenallee'),
      ];
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pump();

      expect(find.text('Familie Muster'), findsOneWidget);
      expect(find.text('WG Sonnenallee'), findsOneWidget);
    });

    testWidgets('selectingAHouseholdFromTheSelectionScreenRoutesIntoIt', (tester) async {
      householdsApi.householdsToReturn = const [
        HouseholdSummary(householdId: 'id-1', name: 'Familie Muster'),
        HouseholdSummary(householdId: 'id-2', name: 'WG Sonnenallee'),
      ];
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pump();

      await tester.tap(find.byKey(const Key('household-selection-item-id-2')));
      await tester.pump();

      final chip = find.byKey(const Key('switcher-chip'));
      expect(chip, findsOneWidget);
      expect(find.descendant(of: chip, matching: find.text('WG Sonnenallee')), findsOneWidget);
    });

    testWidgets('showsAFailureWithRetryWhenLoadingTheHouseholdsFails', (tester) async {
      householdsApi.listErrorToThrow =
          const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pump();

      expect(find.byKey(const Key('households-load-error')), findsOneWidget);
      expect(find.byKey(const Key('households-retry-button')), findsOneWidget);
    });
  });
}
