import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/invites/presentation/invites_cubit.dart';
import 'package:sgart/features/invites/presentation/invites_state.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_invites_dependencies.dart';

void main() {
  group('InvitesCubit', () {
    late FakeInvitesApi invitesApi;

    setUp(() {
      invitesApi = FakeInvitesApi();
    });

    InvitesCubit buildCubit() => InvitesCubit(invitesApi: invitesApi, householdId: 'household-1');

    test('bootstrap_loadsTheActiveInviteCode', () async {
      invitesApi.activeInviteIdToReturn = 'invite-1';
      invitesApi.canReplaceToReturn = true;
      final cubit = buildCubit();

      await cubit.bootstrap();

      expect(cubit.state.status, InvitesStatus.ready);
      expect(cubit.state.inviteId, 'invite-1');
      expect(cubit.state.canReplace, isTrue);
      await cubit.close();
    });

    test('bootstrap_emitsFailureWhenTheLoadFails', () async {
      invitesApi.getActiveInviteCodeError =
          const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      final cubit = buildCubit();

      await cubit.bootstrap();

      expect(cubit.state.status, InvitesStatus.failure);
      expect(cubit.state.loadError?.code, 'network.unreachable');
      await cubit.close();
    });

    test('replaceCode_onSuccessSwapsInTheNewInviteId', () async {
      invitesApi.activeInviteIdToReturn = 'invite-1';
      invitesApi.canReplaceToReturn = true;
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.replaceCode();

      expect(invitesApi.replaceCallCount, 1);
      expect(cubit.state.inviteId, isNot('invite-1'));
      expect(cubit.state.inviteId, invitesApi.lastReplacedNewInviteId);
      expect(cubit.state.isSubmitting, isFalse);
      expect(cubit.state.actionError, isNull);
      await cubit.close();
    });

    test('replaceCode_surfacesARejectionAsAnInlineActionError', () async {
      invitesApi.canReplaceToReturn = true;
      invitesApi.replaceInviteCodeError =
          const AppException(AppError(code: 'governance.notPermitted', message: 'debug'));
      final cubit = buildCubit();
      await cubit.bootstrap();
      final inviteIdBeforeReplace = cubit.state.inviteId;

      await cubit.replaceCode();

      expect(cubit.state.actionError?.code, 'governance.notPermitted');
      expect(cubit.state.isSubmitting, isFalse);
      expect(cubit.state.inviteId, inviteIdBeforeReplace);
      await cubit.close();
    });

    test('replaceCode_isSubmittingGuardIgnoresASecondCallWhileTheFirstIsInFlight', () async {
      invitesApi.canReplaceToReturn = true;
      final cubit = buildCubit();
      await cubit.bootstrap();

      // Both calls start synchronously; the first sets isSubmitting before yielding at its first
      // await, so the second observes isSubmitting=true and is a no-op (Epic-2 Action 3 lesson) —
      // never a second concurrent replace.
      final firstReplace = cubit.replaceCode();
      final secondReplace = cubit.replaceCode();
      await Future.wait([firstReplace, secondReplace]);

      expect(invitesApi.replaceCallCount, 1);
      await cubit.close();
    });

    test('replaceCode_mintsAFreshCommandIdAndInviteIdEveryCall', () async {
      invitesApi.canReplaceToReturn = true;
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.replaceCode();
      await cubit.replaceCode();

      expect(invitesApi.replaceCommandIds.toSet(), hasLength(2));
    });
  });
}
