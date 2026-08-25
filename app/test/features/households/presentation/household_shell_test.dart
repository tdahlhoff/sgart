import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/presentation/auth_cubit.dart';
import 'package:sgart/features/households/data/household_summary.dart';
import 'package:sgart/features/households/data/households_api.dart';
import 'package:sgart/features/households/presentation/first_run_router.dart';
import 'package:sgart/features/households/presentation/households_cubit.dart';

import '../../../support/fake_auth_dependencies.dart';
import '../../../support/fake_households_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('HouseholdShell + switcher', () {
    late FakeHouseholdsApi householdsApi;
    late FakeActiveHouseholdStore activeHouseholdStore;
    late HouseholdsCubit cubit;
    late AuthCubit authCubit;

    const familie = HouseholdSummary(householdId: 'id-1', name: 'Familie Muster');
    const wg = HouseholdSummary(householdId: 'id-2', name: 'WG Sonnenallee');

    setUp(() async {
      householdsApi = FakeHouseholdsApi()..householdsToReturn = const [familie, wg];
      // A stored active household routes straight into the shell (skipping selection).
      activeHouseholdStore = FakeActiveHouseholdStore(activeId: 'id-1');
      cubit = HouseholdsCubit(householdsApi: householdsApi, activeHouseholdStore: activeHouseholdStore);
      // The Profil tab's identity header reads AuthCubit at build time (IndexedStack builds all
      // tabs eagerly), so the shell needs an authenticated ancestor even though this group tests
      // the switcher, not Profil.
      authCubit = await buildAuthenticatedAuthCubit();
    });

    tearDown(() async {
      await cubit.close();
      await authCubit.close();
    });

    // FirstRunRouterBody rebuilds the shell on state change, so a switch is reflected in the header.
    Widget buildSubject() => wrapForTesting(
          BlocProvider<AuthCubit>.value(
            value: authCubit,
            child: RepositoryProvider<HouseholdsApi>.value(
              value: householdsApi,
              child: BlocProvider<HouseholdsCubit>.value(value: cubit, child: const FirstRunRouterBody()),
            ),
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

  group('HouseholdShell tabs', () {
    late FakeHouseholdsApi householdsApi;
    late HouseholdsCubit cubit;
    late AuthCubit authCubit;

    const familie = HouseholdSummary(householdId: 'id-1', name: 'Familie Muster');

    setUp(() async {
      householdsApi = FakeHouseholdsApi()..householdsToReturn = const [familie];
      cubit = HouseholdsCubit(
        householdsApi: householdsApi,
        activeHouseholdStore: FakeActiveHouseholdStore(activeId: 'id-1'),
      );
      authCubit = await buildAuthenticatedAuthCubit();
    });

    tearDown(() async {
      await cubit.close();
      await authCubit.close();
    });

    Widget buildSubject() => wrapForTesting(
          BlocProvider<AuthCubit>.value(
            value: authCubit,
            child: RepositoryProvider<HouseholdsApi>.value(
              value: householdsApi,
              child: BlocProvider<HouseholdsCubit>.value(value: cubit, child: const FirstRunRouterBody()),
            ),
          ),
        );

    Future<void> pumpShell(WidgetTester tester) async {
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();
    }

    testWidgets('theShellShowsAThreeTabNavigationBarWithGermanLabels', (tester) async {
      await pumpShell(tester);

      final navigationBar = find.byType(NavigationBar);
      expect(find.descendant(of: navigationBar, matching: find.text('Listen')), findsOneWidget);
      expect(find.descendant(of: navigationBar, matching: find.text('Einkauf')), findsOneWidget);
      expect(find.descendant(of: navigationBar, matching: find.text('Profil')), findsOneWidget);
    });

    testWidgets('tappingTheProfilTabShowsTheIdentityHeaderAndPersonalSections', (tester) async {
      await pumpShell(tester);

      await tester.tap(find.byKey(const Key('shell-tab-profile')));
      await tester.pumpAndSettle();

      expect(tester.widget<NavigationBar>(find.byType(NavigationBar)).selectedIndex, 2);
      expect(find.text('Anna Testperson'), findsOneWidget);
      expect(find.text('anna@example.test'), findsOneWidget);
      expect(find.text('Sprache & Region'), findsOneWidget);
      expect(find.text('Benachrichtigungen'), findsOneWidget);
      expect(find.byKey(const Key('sign-out-button')), findsOneWidget);
    });

    testWidgets('tappingTheListenAndEinkaufTabsSelectsTheirPlaceholders', (tester) async {
      await pumpShell(tester);

      expect(tester.widget<NavigationBar>(find.byType(NavigationBar)).selectedIndex, 0);
      expect(find.text('Deine Listen kommen in einer späteren Version.'), findsOneWidget);

      await tester.tap(find.byKey(const Key('shell-tab-shopping')));
      await tester.pumpAndSettle();

      expect(tester.widget<NavigationBar>(find.byType(NavigationBar)).selectedIndex, 1);
      expect(find.text('Der Einkauf kommt in einer späteren Version.'), findsOneWidget);
    });

    testWidgets('theSwitcherChipStaysVisibleOnEveryTab', (tester) async {
      await pumpShell(tester);

      for (final tabKey in ['shell-tab-lists', 'shell-tab-shopping', 'shell-tab-profile']) {
        await tester.tap(find.byKey(Key(tabKey)));
        await tester.pumpAndSettle();
        expect(find.byKey(const Key('switcher-chip')), findsOneWidget);
      }
    });
  });
}
