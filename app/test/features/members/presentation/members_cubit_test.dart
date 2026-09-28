import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/members/data/member_view.dart';
import 'package:sgart/features/members/presentation/members_cubit.dart';
import 'package:sgart/features/members/presentation/members_state.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_households_dependencies.dart';
import '../../../support/fake_members_dependencies.dart';

void main() {
  group('MembersCubit', () {
    late FakeMembersApi membersApi;
    late FakeHouseholdsApi householdsApi;

    setUp(() {
      membersApi = FakeMembersApi();
      householdsApi = FakeHouseholdsApi();
    });

    MembersCubit buildCubit() => MembersCubit(
          membersApi: membersApi,
          householdsApi: householdsApi,
          householdId: 'household-1',
        );

    const admin = MemberView(memberId: 'member-admin', role: 'ADMIN', isSelf: true);
    const participant = MemberView(memberId: 'member-participant', role: 'PARTICIPANT', isSelf: false);

    test('bootstrap_loadsMembers', () async {
      membersApi.membersToReturn = const [admin, participant];
      final cubit = buildCubit();

      await cubit.bootstrap();

      expect(cubit.state.status, MembersStatus.ready);
      expect(cubit.state.members, hasLength(2));
      await cubit.close();
    });

    test('bootstrap_emitsFailureWhenTheLoadFails', () async {
      membersApi.listMembersError = const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      final cubit = buildCubit();

      await cubit.bootstrap();

      expect(cubit.state.status, MembersStatus.failure);
      expect(cubit.state.loadError?.code, 'network.unreachable');
      await cubit.close();
    });

    test('leave_onSuccessSetsExited', () async {
      membersApi.membersToReturn = const [participant];
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.leave();

      expect(membersApi.leaveCallCount, 1);
      expect(cubit.state.exited, isTrue);
      await cubit.close();
    });

    test('leave_surfacesALastAdminRejectionAsAnInlineActionError', () async {
      membersApi.leaveError = const AppException(AppError(code: 'membership.lastAdmin', message: 'debug'));
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.leave();

      expect(cubit.state.actionError?.code, 'membership.lastAdmin');
      expect(cubit.state.exited, isFalse);
      await cubit.close();
    });

    test('removeMember_onSuccessDropsTheMemberFromTheRoster', () async {
      membersApi.membersToReturn = const [admin, participant];
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.removeMember('member-participant');

      expect(membersApi.lastRemovedMemberId, 'member-participant');
      expect(cubit.state.members, hasLength(1));
      expect(cubit.state.members.first.memberId, 'member-admin');
      await cubit.close();
    });

    test('removeMember_surfacesAGovernanceRejectionAsAnInlineActionError', () async {
      membersApi.membersToReturn = const [participant];
      membersApi.removeMemberError =
          const AppException(AppError(code: 'governance.notPermitted', message: 'debug'));
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.removeMember('member-participant');

      expect(cubit.state.actionError?.code, 'governance.notPermitted');
      await cubit.close();
    });

    test('promote_onSuccessFlipsTheMembersRoleLocally', () async {
      membersApi.membersToReturn = const [admin, participant];
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.promote('member-participant');

      expect(membersApi.lastPromotedMemberId, 'member-participant');
      final promoted = cubit.state.members.firstWhere((m) => m.memberId == 'member-participant');
      expect(promoted.role, 'ADMIN');
      await cubit.close();
    });

    test('demote_onSuccessFlipsTheMembersRoleLocally', () async {
      const secondAdmin = MemberView(memberId: 'member-2', role: 'ADMIN', isSelf: false);
      membersApi.membersToReturn = const [admin, secondAdmin];
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.demote('member-2');

      final demoted = cubit.state.members.firstWhere((m) => m.memberId == 'member-2');
      expect(demoted.role, 'PARTICIPANT');
      await cubit.close();
    });

    test('demote_surfacesALastAdminRejectionAsAnInlineActionError', () async {
      membersApi.membersToReturn = const [admin];
      membersApi.demoteError = const AppException(AppError(code: 'membership.lastAdmin', message: 'debug'));
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.demote('member-admin');

      expect(cubit.state.actionError?.code, 'membership.lastAdmin');
      await cubit.close();
    });

    test('deleteHousehold_onSuccessSetsExited', () async {
      membersApi.membersToReturn = const [admin];
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.deleteHousehold();

      expect(householdsApi.deleteCallCount, 1);
      expect(householdsApi.lastDeletedHouseholdId, 'household-1');
      expect(cubit.state.exited, isTrue);
      await cubit.close();
    });

    test('isSubmittingGuardIgnoresASecondCallWhileTheFirstIsInFlight', () async {
      membersApi.membersToReturn = const [admin, participant];
      final cubit = buildCubit();
      await cubit.bootstrap();

      final firstRemove = cubit.removeMember('member-participant');
      final secondRemove = cubit.removeMember('member-participant');
      await Future.wait([firstRemove, secondRemove]);

      expect(membersApi.removeCallCount, 1);
      await cubit.close();
    });

    test('removeMember_regeneratesTheCommandIdAfterASuccessfulCall', () async {
      membersApi.membersToReturn = const [
        admin,
        MemberView(memberId: 'member-2', role: 'PARTICIPANT', isSelf: false),
        MemberView(memberId: 'member-3', role: 'PARTICIPANT', isSelf: false),
      ];
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.removeMember('member-2');
      await cubit.removeMember('member-3');

      expect(membersApi.removeCommandIds.toSet(), hasLength(2));
      await cubit.close();
    });
  });
}
