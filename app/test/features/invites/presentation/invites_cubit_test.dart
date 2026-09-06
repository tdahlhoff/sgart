import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/invites/data/pending_invite.dart';
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

    test('bootstrap_loadsPendingInvites', () async {
      invitesApi.pendingInvitesToReturn = const [
        PendingInvite(inviteId: 'invite-1', invitedAt: '2026-09-06T10:00:00Z', invitedBy: 'member-1', status: 'PENDING'),
      ];
      final cubit = buildCubit();

      await cubit.bootstrap();

      expect(cubit.state.status, InvitesStatus.ready);
      expect(cubit.state.invites, hasLength(1));
      await cubit.close();
    });

    test('bootstrap_emitsFailureWhenTheLoadFails', () async {
      invitesApi.listPendingInvitesError =
          const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      final cubit = buildCubit();

      await cubit.bootstrap();

      expect(cubit.state.status, InvitesStatus.failure);
      expect(cubit.state.loadError?.code, 'network.unreachable');
      await cubit.close();
    });

    test('sendInvite_onSuccessOptimisticallyAppendsAPendingInviteAndSendsTheTrimmedEmail', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.sendInvite('  anna@example.com  ');

      expect(invitesApi.lastSentEmail, 'anna@example.com');
      expect(cubit.state.invites, hasLength(1));
      expect(cubit.state.isSubmitting, isFalse);
      expect(cubit.state.actionError, isNull);
      await cubit.close();
    });

    test('sendInvite_surfacesADuplicatePendingRejectionAsAnInlineActionError', () async {
      invitesApi.sendInviteError =
          const AppException(AppError(code: 'invite.duplicatePending', message: 'debug'));
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.sendInvite('anna@example.com');

      expect(cubit.state.actionError?.code, 'invite.duplicatePending');
      expect(cubit.state.isSubmitting, isFalse);
      expect(cubit.state.invites, isEmpty);
      await cubit.close();
    });

    test('sendInvite_surfacesAnAlreadyAMemberRejectionAsAnInlineActionError', () async {
      invitesApi.sendInviteError =
          const AppException(AppError(code: 'invite.alreadyAMember', message: 'debug'));
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.sendInvite('berta@example.com');

      expect(cubit.state.actionError?.code, 'invite.alreadyAMember');
      await cubit.close();
    });

    test('sendInvite_blocksAnImplausibleEmailClientSideWithoutCallingTheApi', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.sendInvite('not-an-email');

      expect(invitesApi.sendCallCount, 0);
      expect(cubit.state.actionError?.code, 'invite.emailInvalid');
      await cubit.close();
    });

    test('sendInvite_isSubmittingGuardIgnoresASecondCallWhileTheFirstIsInFlight', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();

      // Both calls start synchronously; the first sets isSubmitting before yielding at its first
      // await, so the second observes isSubmitting=true and is a no-op (Epic-2 Action 3 lesson) —
      // never a second concurrent send.
      final firstSend = cubit.sendInvite('anna@example.com');
      final secondSend = cubit.sendInvite('anna@example.com');
      await Future.wait([firstSend, secondSend]);

      expect(invitesApi.sendCallCount, 1);
      await cubit.close();
    });

    test('sendInvite_regeneratesTheCommandIdAfterASuccessfulSend', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.sendInvite('anna@example.com');
      await cubit.sendInvite('berta@example.com');

      expect(invitesApi.sendCommandIds.toSet(), hasLength(2));
      await cubit.close();
    });
  });
}
