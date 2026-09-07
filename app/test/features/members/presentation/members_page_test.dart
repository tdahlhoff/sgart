import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/households/data/household_summary.dart';
import 'package:sgart/features/households/data/households_api.dart';
import 'package:sgart/features/households/presentation/households_cubit.dart';
import 'package:sgart/features/invites/data/invites_api.dart';
import 'package:sgart/features/invites/data/pending_invite.dart';
import 'package:sgart/features/members/data/member_view.dart';
import 'package:sgart/features/members/data/members_api.dart';
import 'package:sgart/features/members/presentation/members_page.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_households_dependencies.dart';
import '../../../support/fake_invites_dependencies.dart';
import '../../../support/fake_members_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('MembersPage', () {
    late FakeMembersApi membersApi;
    late FakeHouseholdsApi householdsApi;
    late FakeInvitesApi invitesApi;
    late FakeActiveHouseholdStore activeHouseholdStore;
    late HouseholdsCubit householdsCubit;

    const household = HouseholdSummary(householdId: 'household-1', name: 'Familie Muster');
    const admin = MemberView(memberId: 'member-admin', role: 'ADMIN', isSelf: true);
    const participant = MemberView(memberId: 'member-participant', role: 'PARTICIPANT', isSelf: false);

    setUp(() async {
      membersApi = FakeMembersApi();
      householdsApi = FakeHouseholdsApi()..householdsToReturn = const [household];
      invitesApi = FakeInvitesApi();
      activeHouseholdStore = FakeActiveHouseholdStore(activeId: 'household-1');
      householdsCubit = HouseholdsCubit(householdsApi: householdsApi, activeHouseholdStore: activeHouseholdStore);
      await householdsCubit.bootstrap();
    });

    tearDown(() => householdsCubit.close());

    Widget buildSubject() => wrapForTesting(
          Builder(
            builder: (context) => Scaffold(
              body: ElevatedButton(
                key: const Key('open-members'),
                onPressed: () => Navigator.of(context).push(MaterialPageRoute(
                  builder: (_) => MultiRepositoryProvider(
                    providers: [
                      RepositoryProvider<MembersApi>.value(value: membersApi),
                      RepositoryProvider<HouseholdsApi>.value(value: householdsApi),
                      RepositoryProvider<InvitesApi>.value(value: invitesApi),
                    ],
                    child: BlocProvider<HouseholdsCubit>.value(
                      value: householdsCubit,
                      child: const MembersPage(household: household),
                    ),
                  ),
                )),
                child: const Text('open'),
              ),
            ),
          ),
        );

    Future<void> openMembersPage(WidgetTester tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.tap(find.byKey(const Key('open-members')));
      await tester.pumpAndSettle();
    }

    testWidgets('anAdminSeesGovernanceControlsForOtherMembers', (tester) async {
      membersApi.membersToReturn = const [admin, participant];
      await openMembersPage(tester);

      expect(find.byKey(const Key('member-row-member-participant-menu')), findsOneWidget);
      expect(find.byKey(const Key('members-delete-household-button')), findsOneWidget);
      expect(find.byKey(const Key('members-leave-button')), findsNothing);
    });

    testWidgets('aParticipantSeesOnlyLeave', (tester) async {
      membersApi.membersToReturn = const [
        MemberView(memberId: 'member-admin', role: 'ADMIN', isSelf: false),
        MemberView(memberId: 'member-self', role: 'PARTICIPANT', isSelf: true),
      ];
      await openMembersPage(tester);

      expect(find.byKey(const Key('members-leave-button')), findsOneWidget);
      expect(find.byKey(const Key('members-delete-household-button')), findsNothing);
      expect(find.byKey(const Key('member-row-member-admin-menu')), findsNothing);
    });

    testWidgets('anAdminCanRemoveAMemberAfterConfirming', (tester) async {
      membersApi.membersToReturn = const [admin, participant];
      await openMembersPage(tester);

      await tester.tap(find.byKey(const Key('member-row-member-participant-menu')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('member-row-member-participant-remove')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Bestätigen'));
      await tester.pumpAndSettle();

      expect(membersApi.lastRemovedMemberId, 'member-participant');
      expect(find.byKey(const Key('member-row-member-participant')), findsNothing);
    });

    testWidgets('anAdminCanPromoteAParticipantAfterConfirming', (tester) async {
      membersApi.membersToReturn = const [admin, participant];
      await openMembersPage(tester);

      await tester.tap(find.byKey(const Key('member-row-member-participant-menu')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('member-row-member-participant-promote')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Bestätigen'));
      await tester.pumpAndSettle();

      expect(membersApi.lastPromotedMemberId, 'member-participant');
    });

    testWidgets('leavingRoutesBackToTheShellAndRebootstrapsHouseholds', (tester) async {
      membersApi.membersToReturn = const [
        MemberView(memberId: 'member-admin', role: 'ADMIN', isSelf: false),
        MemberView(memberId: 'member-self', role: 'PARTICIPANT', isSelf: true),
      ];
      await openMembersPage(tester);

      await tester.tap(find.byKey(const Key('members-leave-button')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Bestätigen'));
      await tester.pumpAndSettle();

      expect(membersApi.leaveCallCount, 1);
      // The page popped back to the host route.
      expect(find.byKey(const Key('members-leave-button')), findsNothing);
      expect(find.byKey(const Key('open-members')), findsOneWidget);
    });

    testWidgets('leavingAsTheLastAdminShowsTheInlineLastAdminError', (tester) async {
      membersApi.membersToReturn = const [
        MemberView(memberId: 'member-admin', role: 'ADMIN', isSelf: false),
        MemberView(memberId: 'member-self', role: 'PARTICIPANT', isSelf: true),
      ];
      membersApi.leaveError = const AppException(AppError(code: 'membership.lastAdmin', message: 'debug'));
      await openMembersPage(tester);

      await tester.tap(find.byKey(const Key('members-leave-button')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Bestätigen'));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('members-action-error')), findsOneWidget);
      expect(
        find.text('Der letzte Admin kann den Haushalt nicht verlassen, entfernt oder degradiert werden.'),
        findsOneWidget,
      );
    });

    testWidgets('deleteHouseholdRequiresTypingTheHouseholdNameBeforeConfirmIsEnabled', (tester) async {
      membersApi.membersToReturn = const [admin];
      await openMembersPage(tester);

      await tester.tap(find.byKey(const Key('members-delete-household-button')));
      await tester.pumpAndSettle();
      final confirmButtonBeforeTyping =
          tester.widget<TextButton>(find.byKey(const Key('members-delete-household-confirm-button')));
      expect(confirmButtonBeforeTyping.onPressed, isNull);

      await tester.enterText(
        find.byKey(const Key('members-delete-household-confirm-field')),
        'Familie Muster',
      );
      await tester.pumpAndSettle();
      final confirmButtonAfterTyping =
          tester.widget<TextButton>(find.byKey(const Key('members-delete-household-confirm-button')));
      expect(confirmButtonAfterTyping.onPressed, isNotNull);

      await tester.tap(find.byKey(const Key('members-delete-household-confirm-button')));
      await tester.pumpAndSettle();

      expect(householdsApi.deleteCallCount, 1);
    });

    testWidgets('pendingInvitesShowARevokeActionForAnAdmin', (tester) async {
      membersApi.membersToReturn = const [admin];
      invitesApi.pendingInvitesToReturn = const [
        PendingInvite(inviteId: 'invite-1', invitedAt: '2026-09-06T10:00:00Z', invitedBy: 'member-admin', status: 'PENDING'),
      ];
      await openMembersPage(tester);

      expect(find.byKey(const Key('pending-invite-row-invite-1')), findsOneWidget);
      await tester.tap(find.byKey(const Key('pending-invite-row-invite-1-revoke')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Bestätigen'));
      await tester.pumpAndSettle();

      expect(invitesApi.lastRevokedInviteId, 'invite-1');
    });
  });
}
