import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/households/data/household_summary.dart';
import 'package:sgart/features/households/data/households_api.dart';
import 'package:sgart/features/households/presentation/first_run_router.dart';
import 'package:sgart/features/households/presentation/households_cubit.dart';

import '../../../support/fake_households_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('HouseholdShell + switcher', () {
    late FakeHouseholdsApi householdsApi;
    late FakeActiveHouseholdStore activeHouseholdStore;
    late HouseholdsCubit cubit;

    const familie = HouseholdSummary(householdId: 'id-1', name: 'Familie Muster');
    const wg = HouseholdSummary(householdId: 'id-2', name: 'WG Sonnenallee');

    setUp(() {
      householdsApi = FakeHouseholdsApi()..householdsToReturn = const [familie, wg];
      // A stored active household routes straight into the shell (skipping selection).
      activeHouseholdStore = FakeActiveHouseholdStore(activeId: 'id-1');
      cubit = HouseholdsCubit(householdsApi: householdsApi, activeHouseholdStore: activeHouseholdStore);
    });

    tearDown(() => cubit.close());

    // FirstRunRouterBody rebuilds the shell on state change, so a switch is reflected in the header.
    Widget buildSubject() => wrapForTesting(
          RepositoryProvider<HouseholdsApi>.value(
            value: householdsApi,
            child: BlocProvider<HouseholdsCubit>.value(value: cubit, child: const FirstRunRouterBody()),
          ),
        );

    Future<void> pumpShell(WidgetTester tester) async {
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();
    }

    testWidgets('theHeaderShowsTheActiveHouseholdName', (tester) async {
      await pumpShell(tester);

      final chip = find.byKey(const Key('switcher-chip'));
      expect(chip, findsOneWidget);
      expect(find.descendant(of: chip, matching: find.text('Familie Muster')), findsOneWidget);
    });

    testWidgets('theSwitcherChipCarriesAnAccessibleSwitchHouseholdLabel', (tester) async {
      await pumpShell(tester);

      // The chip announces itself as an actionable "switch household" control (tooltip + semantics),
      // not just the household name — otherwise a screen-reader user gets no hint it is interactive.
      expect(find.byTooltip('Haushalt wechseln'), findsOneWidget);
    });

    testWidgets('tappingTheChipOpensTheSwitcherListingAllHouseholdsWithTheActiveOneMarked', (tester) async {
      await pumpShell(tester);

      await tester.tap(find.byKey(const Key('switcher-chip')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('switcher-item-id-1')), findsOneWidget);
      expect(find.byKey(const Key('switcher-item-id-2')), findsOneWidget);
      // The active household (id-1) is marked „Aktiv".
      final activeBadge = find.byKey(const Key('switcher-active-badge'));
      expect(activeBadge, findsOneWidget);
      expect(
        find.ancestor(of: activeBadge, matching: find.byKey(const Key('switcher-item-id-1'))),
        findsOneWidget,
      );
    });

    testWidgets('pickingAnotherHouseholdSwitchesTheActiveOneAndShowsConfirmation', (tester) async {
      await pumpShell(tester);

      await tester.tap(find.byKey(const Key('switcher-chip')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('switcher-item-id-2')));
      await tester.pumpAndSettle();

      // Header switched to the newly active household…
      expect(
        find.descendant(of: find.byKey(const Key('switcher-chip')), matching: find.text('WG Sonnenallee')),
        findsOneWidget,
      );
      // …a brief confirmation is shown…
      expect(find.byKey(const Key('switch-confirmation')), findsOneWidget);
      // …and the new active household is persisted.
      expect(activeHouseholdStore.writes.last, 'id-2');
    });
  });
}
