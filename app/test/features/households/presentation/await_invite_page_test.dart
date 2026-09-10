import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/households/data/household_summary.dart';
import 'package:sgart/features/households/presentation/await_invite_page.dart';
import 'package:sgart/features/households/presentation/households_cubit.dart';
import 'package:sgart/features/households/presentation/households_state.dart';
import 'package:sgart/features/invites/data/invite_link.dart';
import 'package:sgart/features/invites/data/invites_api.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_households_dependencies.dart';
import '../../../support/fake_invites_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('AwaitInvitePage', () {
    late FakeInvitesApi invitesApi;
    late FakeHouseholdsApi householdsApi;
    late HouseholdsCubit householdsCubit;

    setUp(() {
      invitesApi = FakeInvitesApi();
      householdsApi = FakeHouseholdsApi();
      householdsCubit =
          HouseholdsCubit(householdsApi: householdsApi, activeHouseholdStore: FakeActiveHouseholdStore());
    });

    tearDown(() => householdsCubit.close());

    Widget buildSubject({InviteLink? initialLink}) => wrapForTesting(
          Navigator(
            onGenerateRoute: (settings) => MaterialPageRoute(
              builder: (_) => RepositoryProvider<InvitesApi>.value(
                value: invitesApi,
                child: BlocProvider<HouseholdsCubit>.value(
                  value: householdsCubit,
                  child: AwaitInvitePage(initialLink: initialLink),
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
  });
}
