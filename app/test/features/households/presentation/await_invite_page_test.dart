import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/presentation/auth_cubit.dart';
import 'package:sgart/features/consent/presentation/consent_cubit.dart';
import 'package:sgart/features/households/data/household_summary.dart';
import 'package:sgart/features/households/presentation/await_invite_page.dart';
import 'package:sgart/features/households/presentation/households_cubit.dart';
import 'package:sgart/features/households/presentation/households_state.dart';
import 'package:sgart/features/invites/data/invite_link.dart';
import 'package:sgart/features/invites/data/invites_api.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_auth_dependencies.dart';
import '../../../support/fake_consent_dependencies.dart';
import '../../../support/fake_households_dependencies.dart';
import '../../../support/fake_invites_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('AwaitInvitePage', () {
    late FakeInvitesApi invitesApi;
    late FakeHouseholdsApi householdsApi;
    late HouseholdsCubit householdsCubit;
    late AuthCubit authCubit;

    setUp(() async {
      invitesApi = FakeInvitesApi();
      householdsApi = FakeHouseholdsApi();
      householdsCubit =
          HouseholdsCubit(householdsApi: householdsApi, activeHouseholdStore: FakeActiveHouseholdStore());
      authCubit = await buildAuthenticatedAuthCubit();
    });

    tearDown(() async {
      await householdsCubit.close();
      await authCubit.close();
    });

    Widget buildSubject({InviteLink? initialLink}) => wrapForTesting(
          Navigator(
            onGenerateRoute: (settings) => MaterialPageRoute(
              builder: (_) => RepositoryProvider<InvitesApi>.value(
                value: invitesApi,
                child: BlocProvider<HouseholdsCubit>.value(
                  value: householdsCubit,
                  child: BlocProvider<AuthCubit>.value(
                    value: authCubit,
                    child: AwaitInvitePage(initialLink: initialLink),
                  ),
                ),
              ),
            ),
          ),
        );

    testWidgets('pastingAValidLinkAndJoiningRoutesOnSuccess', (tester) async {
      householdsApi.householdsToReturn = [const HouseholdSummary(householdId: 'household-1', name: 'Familie Muster')];
      await tester.pumpWidget(buildSubject());

      await tester.enterText(
        find.byKey(const Key('await-invite-link-field')),
        'https://sgart.example/invite?h=household-1&i=invite-1',
      );
      await tester.tap(find.byKey(const Key('await-invite-join-button')));
      await tester.pumpAndSettle();

      expect(invitesApi.lastAcceptedHouseholdId, 'household-1');
      expect(invitesApi.lastAcceptedInviteId, 'invite-1');
      // bootstrap() was called to re-derive routing state after the join.
      expect(householdsCubit.state.status, HouseholdsStatus.shell);
    });

    testWidgets('aMalformedLinkShowsAnInlineErrorWithoutCallingTheApi', (tester) async {
      await tester.pumpWidget(buildSubject());

      await tester.enterText(find.byKey(const Key('await-invite-link-field')), 'not-a-link-at-all');
      await tester.tap(find.byKey(const Key('await-invite-join-button')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('await-invite-error')), findsOneWidget);
      expect(invitesApi.acceptCallCount, 0);
    });

    testWidgets('anExpiredInviteShowsAnInlineError', (tester) async {
      invitesApi.acceptInviteError = const AppException(AppError(code: 'invite.expired', message: 'debug only'));
      await tester.pumpWidget(buildSubject());

      await tester.enterText(find.byKey(const Key('await-invite-link-field')), 'household-1:invite-1');
      await tester.tap(find.byKey(const Key('await-invite-join-button')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('await-invite-error')), findsOneWidget);
      expect(find.text('Diese Einladung ist abgelaufen.'), findsOneWidget);
    });

    testWidgets('anInitialLinkPreFillsTheFieldAndAutoTriggersTheAccept', (tester) async {
      householdsApi.householdsToReturn = [const HouseholdSummary(householdId: 'household-1', name: 'Familie Muster')];
      await tester.pumpWidget(buildSubject(
        initialLink: const InviteLink(householdId: 'household-1', inviteId: 'invite-1'),
      ));
      await tester.pumpAndSettle();

      expect(find.text('household-1:invite-1'), findsOneWidget);
      expect(invitesApi.lastAcceptedHouseholdId, 'household-1');
      expect(invitesApi.lastAcceptedInviteId, 'invite-1');
      expect(householdsCubit.state.status, HouseholdsStatus.shell);
    });

    testWidgets('backButtonStillPops', (tester) async {
      await tester.pumpWidget(buildSubject());

      await tester.tap(find.byKey(const Key('await-invite-back-button')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('await-invite-link-field')), findsNothing);
    });

    // Story 7.4 review: the accept-side mirror of the create-side stale-client recovery. A join
    // rejected with 409 consent.required must reload the gate's status and pop back to it — never
    // fall through to the generic inline error the other failures show.
    testWidgets('aStaleClientConsentRequired409ReloadsTheGateAndPopsBackInsteadOfShowingAnInlineError',
        (tester) async {
      final consentApi = FakeConsentApi();
      final consentCubit = ConsentCubit(consentApi: consentApi);
      addTearDown(consentCubit.close);
      invitesApi.acceptInviteError =
          const AppException(AppError(code: 'consent.required', message: 'debug only'));

      // A first route (the placeholder) so the pushed accept screen has somewhere to pop back to,
      // with the ConsentCubit provided as a shared ancestor — exactly what production's
      // ConsentGatedChoicePage does above openAwaitInvitePage's push boundary.
      await tester.pumpWidget(
        wrapForTesting(
          BlocProvider<ConsentCubit>.value(
            value: consentCubit,
            child: RepositoryProvider<InvitesApi>.value(
              value: invitesApi,
              child: BlocProvider<HouseholdsCubit>.value(
                value: householdsCubit,
                child: BlocProvider<AuthCubit>.value(
                  value: authCubit,
                  child: Navigator(
                    onGenerateRoute: (_) => MaterialPageRoute(
                      builder: (context) => Scaffold(
                        body: Center(
                          child: ElevatedButton(
                            key: const Key('open-await'),
                            onPressed: () => openAwaitInvitePage(
                              context,
                              initialLink: const InviteLink(householdId: 'household-1', inviteId: 'invite-1'),
                            ),
                            child: const Text('open'),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      );

      await tester.tap(find.byKey(const Key('open-await')));
      await tester.pumpAndSettle();

      expect(invitesApi.acceptCallCount, 1);
      expect(consentApi.getStatusCallCount, 1); // the gate's status was reloaded
      expect(find.byType(AwaitInvitePage), findsNothing); // popped back to the gateway
      expect(find.byKey(const Key('open-await')), findsOneWidget);
      expect(find.byKey(const Key('await-invite-error')), findsNothing); // not the generic inline error
    });
  });
}
