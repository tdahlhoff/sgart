import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/presentation/auth_cubit.dart';
import 'package:sgart/features/households/data/household_summary.dart';
import 'package:sgart/features/households/data/households_api.dart';
import 'package:sgart/features/households/presentation/first_run_router.dart';
import 'package:sgart/features/households/presentation/households_cubit.dart';
import 'package:sgart/features/invites/data/invites_api.dart';
import 'package:sgart/features/lists/data/shopping_list_summary.dart';
import 'package:sgart/features/lists/data/shopping_lists_api.dart';
import 'package:sgart/features/members/data/member_view.dart';
import 'package:sgart/features/members/data/members_api.dart';
import 'package:sgart/features/settings/data/nickname_api.dart';
import 'package:sgart/features/stores/data/store_chain_reference_cache.dart';
import 'package:sgart/features/stores/data/stores_api.dart';
import 'package:sgart/features/stores/presentation/manage_stores_page.dart';

import '../../../support/fake_auth_dependencies.dart';
import '../../../support/fake_households_dependencies.dart';
import '../../../support/fake_invites_dependencies.dart';
import '../../../support/fake_members_dependencies.dart';
import '../../../support/fake_nickname_api.dart';
import '../../../support/fake_shopping_lists_dependencies.dart';
import '../../../support/fake_stores_dependencies.dart';
import '../../../support/widget_test_harness.dart';

/// Builds the shell under the same provider set [FirstRunRouter] gives it in production —
/// `HouseholdsApi`/`HouseholdsCubit`, `ShoppingListsApi`, and the `StoresApi`/
/// `StoreChainReferenceCache`/`InvitesApi`/`MembersApi` the switcher's „Haushalt verwalten" seam
/// (and everything it pushes) reads across the route boundary. Story 8.1: the switcher used to be
/// tested under a narrower scope than production actually gives it, which is exactly how the
/// `ProviderNotFoundException` crash went unnoticed — every group in this file now reproduces the
/// real scope so a re-provide seam that drops a dependency fails here, not on a device.
Widget _buildShellHarness({
  required AuthCubit authCubit,
  required HouseholdsApi householdsApi,
  required HouseholdsCubit householdsCubit,
  required ShoppingListsApi shoppingListsApi,
  InvitesApi? invitesApi,
  MembersApi? membersApi,
}) =>
    wrapForTesting(
      BlocProvider<AuthCubit>.value(
        value: authCubit,
        child: MultiRepositoryProvider(
          providers: [
            RepositoryProvider<HouseholdsApi>.value(value: householdsApi),
            RepositoryProvider<ShoppingListsApi>.value(value: shoppingListsApi),
            RepositoryProvider<StoresApi>.value(value: FakeStoresApi()),
            RepositoryProvider<StoreChainReferenceCache>.value(value: FakeStoreChainReferenceCache()),
            RepositoryProvider<InvitesApi>.value(value: invitesApi ?? FakeInvitesApi()),
            RepositoryProvider<MembersApi>.value(value: membersApi ?? FakeMembersApi()),
            RepositoryProvider<NicknameApi>.value(value: FakeNicknameApi()),
          ],
          child: BlocProvider<HouseholdsCubit>.value(value: householdsCubit, child: const FirstRunRouterBody()),
        ),
      ),
    );

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
    Widget buildSubject() => _buildShellHarness(
          authCubit: authCubit,
          householdsApi: householdsApi,
          householdsCubit: cubit,
          shoppingListsApi: FakeShoppingListsApi(),
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

  group('HouseholdShell + manage household hub reachability', () {
    // Story 8.1 regression: reproduces the route-boundary escape by building the shell under the
    // full FirstRunRouter-equivalent provider set, then driving the real top-bar selector →
    // „Haushalt verwalten" path — the only way to catch a re-provide seam that silently drops a
    // dependency.
    late FakeHouseholdsApi householdsApi;
    late FakeInvitesApi invitesApi;
    late FakeMembersApi membersApi;
    late HouseholdsCubit cubit;
    late AuthCubit authCubit;

    const familie = HouseholdSummary(householdId: 'id-1', name: 'Familie Muster');
    const selfParticipant = MemberView(memberId: 'member-1', role: 'PARTICIPANT', isSelf: true);

    setUp(() async {
      householdsApi = FakeHouseholdsApi()..householdsToReturn = const [familie];
      invitesApi = FakeInvitesApi();
      membersApi = FakeMembersApi()..membersToReturn = const [selfParticipant];
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

    Widget buildSubject() => _buildShellHarness(
          authCubit: authCubit,
          householdsApi: householdsApi,
          householdsCubit: cubit,
          shoppingListsApi: FakeShoppingListsApi(),
          invitesApi: invitesApi,
          membersApi: membersApi,
        );

    Future<void> pumpShell(WidgetTester tester) async {
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();
    }

    Future<void> openHub(WidgetTester tester) async {
      await tester.tap(find.byKey(const Key('switcher-chip')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('switcher-manage-button')));
      await tester.pumpAndSettle();
    }

    testWidgets('openingTheManageHouseholdHubFromTheTopBarSelectorRendersTheHubWithoutThrowing',
        (tester) async {
      await pumpShell(tester);

      await openHub(tester);

      expect(tester.takeException(), isNull);
      expect(find.byKey(const Key('manage-invites-row')), findsOneWidget);
      expect(find.byKey(const Key('manage-members-row')), findsOneWidget);
      expect(find.byKey(const Key('manage-stores-row')), findsOneWidget);
    });

    testWidgets('theInvitesRowOpensWithoutThrowingAfterReachingTheHubFromTheTopBarSelector', (tester) async {
      await pumpShell(tester);
      await openHub(tester);

      await tester.tap(find.byKey(const Key('manage-invites-row')));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(find.byKey(const Key('invite-code-row')), findsOneWidget);
    });

    testWidgets('theMembersRowOpensWithoutThrowingAfterReachingTheHubFromTheTopBarSelector', (tester) async {
      await pumpShell(tester);
      await openHub(tester);

      await tester.tap(find.byKey(const Key('manage-members-row')));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(find.byKey(const Key('member-row-member-1')), findsOneWidget);
    });

    testWidgets('theStoresRowOpensWithoutThrowingAfterReachingTheHubFromTheTopBarSelector', (tester) async {
      await pumpShell(tester);
      await openHub(tester);

      await tester.tap(find.byKey(const Key('manage-stores-row')));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(find.byType(ManageStoresPage), findsOneWidget);
    });

    testWidgets(
        'leavingTheHouseholdFromTheMembersPageReachedThroughTheHubReturnsToTheShellWithoutThrowing',
        (tester) async {
      await pumpShell(tester);
      await openHub(tester);
      await tester.tap(find.byKey(const Key('manage-members-row')));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('members-leave-button')));
      await tester.pumpAndSettle();
      // The confirmation dialog's "confirm" action is the second TextButton rendered.
      await tester.tap(find.text('Bestätigen'));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(membersApi.leaveCallCount, 1);
      // The exit listener re-bootstraps HouseholdsCubit and pops back to the shell — no
      // ProviderNotFoundException from the missing HouseholdsCubit re-provide (Story 8.1).
      expect(find.byKey(const Key('switcher-chip')), findsOneWidget);
    });
  });

  group('HouseholdShell tabs', () {
    late FakeHouseholdsApi householdsApi;
    late FakeShoppingListsApi shoppingListsApi;
    late HouseholdsCubit cubit;
    late AuthCubit authCubit;

    const familie = HouseholdSummary(householdId: 'id-1', name: 'Familie Muster');

    setUp(() async {
      householdsApi = FakeHouseholdsApi()..householdsToReturn = const [familie];
      shoppingListsApi = FakeShoppingListsApi();
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

    Widget buildSubject() => _buildShellHarness(
          authCubit: authCubit,
          householdsApi: householdsApi,
          householdsCubit: cubit,
          shoppingListsApi: shoppingListsApi,
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
      // Story 8.3: the header shows the household nickname (here the unset fallback), never the JWT name.
      expect(find.byKey(const Key('profile-display-name')), findsOneWidget);
      expect(find.text('Anna Testperson'), findsNothing);
      expect(find.text('anna@example.test'), findsOneWidget);
      expect(find.text('Sprache & Region'), findsOneWidget);
      // Story 8.3's nickname section pushed the notifications section further down the Profil
      // ListView, past the default test viewport — scroll it into view before asserting.
      await tester.scrollUntilVisible(find.text('Benachrichtigungen'), 200, scrollable: find.byType(Scrollable));
      expect(find.text('Benachrichtigungen'), findsOneWidget);
    });

    testWidgets('theListenTabShowsTheRealListsViewAndEinkaufShowsTheActiveTripsIndex', (tester) async {
      // Story 3.2, AC4, Cl. 3 — the Einkauf tab is now the active-trips index, not a placeholder.
      shoppingListsApi.listsToReturn = const [
        ShoppingListSummary(listId: 'list-1', name: 'Wocheneinkauf', status: 'OPEN'),
      ];
      await pumpShell(tester);

      expect(tester.widget<NavigationBar>(find.byType(NavigationBar)).selectedIndex, 0);
      expect(find.text('Wocheneinkauf'), findsOneWidget);

      await tester.tap(find.byKey(const Key('shell-tab-shopping')));
      await tester.pumpAndSettle();

      expect(tester.widget<NavigationBar>(find.byType(NavigationBar)).selectedIndex, 1);
      // No In-Trip list yet — the calm empty state renders.
      expect(find.byKey(const Key('active-trips-empty-state')), findsOneWidget);
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
