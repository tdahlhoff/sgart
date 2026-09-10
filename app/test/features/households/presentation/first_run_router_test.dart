import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/caller_identity.dart';
import 'package:sgart/features/auth/data/oidc_tokens.dart';
import 'package:sgart/features/auth/presentation/auth_cubit.dart';
import 'package:sgart/features/auth/presentation/auth_gate.dart';
import 'package:sgart/features/households/data/household_summary.dart';
import 'package:sgart/features/households/presentation/await_invite_page.dart';
import 'package:sgart/features/households/presentation/first_run_router.dart';
import 'package:sgart/features/households/presentation/households_cubit.dart';
import 'package:sgart/features/invites/data/invite_link.dart';
import 'package:sgart/features/invites/data/invites_api.dart';
import 'package:sgart/features/invites/presentation/pending_invite_link_cubit.dart';
import 'package:sgart/features/lists/data/shopping_lists_api.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_auth_dependencies.dart';
import '../../../support/fake_households_dependencies.dart';
import '../../../support/fake_invites_dependencies.dart';
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

  group('FirstRunRouterBody pending invite-link routing (Story 4.6, AC1/AC3)', () {
    late FakeHouseholdsApi householdsApi;
    late HouseholdsCubit cubit;
    late AuthCubit authCubit;
    late FakeActiveHouseholdStore activeHouseholdStore;
    late FakeInvitesApi invitesApi;
    late PendingInviteLinkCubit pendingInviteLinkCubit;

    setUp(() async {
      householdsApi = FakeHouseholdsApi()..householdsToReturn = const [];
      activeHouseholdStore = FakeActiveHouseholdStore();
      cubit = HouseholdsCubit(householdsApi: householdsApi, activeHouseholdStore: activeHouseholdStore);
      authCubit = await buildAuthenticatedAuthCubit();
      invitesApi = FakeInvitesApi();
      pendingInviteLinkCubit = PendingInviteLinkCubit();
    });

    tearDown(() async {
      await cubit.close();
      await authCubit.close();
      await pendingInviteLinkCubit.close();
    });

    Widget buildSubject() => wrapForTesting(
          BlocProvider<AuthCubit>.value(
            value: authCubit,
            child: RepositoryProvider<ShoppingListsApi>.value(
              value: FakeShoppingListsApi(),
              child: RepositoryProvider<InvitesApi>.value(
                value: invitesApi,
                child: BlocProvider<PendingInviteLinkCubit>.value(
                  value: pendingInviteLinkCubit,
                  child: BlocProvider<HouseholdsCubit>.value(value: cubit, child: const FirstRunRouterBody()),
                ),
              ),
            ),
          ),
        );

    testWidgets('aLinkAlreadyPendingWhenThisWidgetMountsDrivesTheAcceptFlowAndReturnsOnSuccess', (tester) async {
      pendingInviteLinkCubit.offer(const InviteLink(householdId: 'household-1', inviteId: 'invite-1'));
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();

      // Auto-accept fired with the pre-filled link (the accept intent, not a manual paste).
      expect(invitesApi.lastAcceptedHouseholdId, 'household-1');
      expect(invitesApi.lastAcceptedInviteId, 'invite-1');
      expect(pendingInviteLinkCubit.state, isNull); // consumed — no re-entrant second route
      // The accept screen pops back to the first-run route on success (AwaitInvitePage's own
      // listener) — this proves the same accept path Story 4.2 already ships (AC3, DRY).
      expect(find.byType(AwaitInvitePage), findsNothing);
    });

    testWidgets('aLinkOfferedWhileAlreadyMountedDrivesTheAcceptFlowToo', (tester) async {
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pump();
      expect(find.byKey(const Key('create-household-choice-button')), findsOneWidget);

      pendingInviteLinkCubit.offer(const InviteLink(householdId: 'household-2', inviteId: 'invite-2'));
      await tester.pumpAndSettle();

      expect(invitesApi.lastAcceptedHouseholdId, 'household-2');
      expect(invitesApi.lastAcceptedInviteId, 'invite-2');
      expect(find.byType(AwaitInvitePage), findsNothing);
    });

    testWidgets(
      'a link offered while signed out is routed to the accept flow only after sign-in completes',
      (tester) async {
        final oidcClient = FakeOidcClient()..tokensToReturn = const OidcTokens(accessToken: 'access');
        final identityApi = FakeIdentityApi()
          ..identityToReturn = const CallerIdentity(
            keycloakUserId: 'sub-1',
            displayName: 'Anna Testperson',
            email: 'anna@example.test',
          );
        final signedOutAuthCubit = AuthCubit(
          oidcClient: oidcClient,
          tokenStorage: FakeSecureTokenStorage(),
          identityApi: identityApi,
          activeHouseholdStore: activeHouseholdStore,
        );

        // Offered before sign-in — mirrors a deep link opened while the app is at the sign-in gate.
        pendingInviteLinkCubit.offer(const InviteLink(householdId: 'household-1', inviteId: 'invite-1'));

        await tester.pumpWidget(wrapForTesting(
          BlocProvider<AuthCubit>.value(
            value: signedOutAuthCubit,
            child: RepositoryProvider<ShoppingListsApi>.value(
              value: FakeShoppingListsApi(),
              child: RepositoryProvider<InvitesApi>.value(
                value: invitesApi,
                child: BlocProvider<PendingInviteLinkCubit>.value(
                  value: pendingInviteLinkCubit,
                  child: BlocProvider<HouseholdsCubit>.value(
                    value: cubit,
                    child: AuthGateBody(
                      authenticatedBuilder: (_) => const FirstRunRouterBody(),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ));
        await tester.pump();

        // Still at the sign-in gate — the pending link has not been touched yet.
        expect(find.text('Anmelden'), findsOneWidget);
        expect(invitesApi.acceptCallCount, 0);
        expect(pendingInviteLinkCubit.state, isNotNull);

        await tester.tap(find.byKey(const Key('sign-in-button')));
        await tester.pumpAndSettle();
        await cubit.bootstrap();
        await tester.pumpAndSettle();

        // FirstRunRouterBody has now mounted post-auth and drives the accept flow.
        expect(invitesApi.lastAcceptedHouseholdId, 'household-1');
        expect(invitesApi.lastAcceptedInviteId, 'invite-1');
        expect(pendingInviteLinkCubit.state, isNull);

        await signedOutAuthCubit.close();
      },
    );
  });
}
