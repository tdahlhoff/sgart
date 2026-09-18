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

    test('createInvite_onSuccessOptimisticallyAppendsAPendingInviteAndSurfacesItsCode', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.createInvite();

      expect(invitesApi.createCallCount, 1);
      expect(cubit.state.invites, hasLength(1));
      expect(cubit.state.lastCreatedInviteId, isNotNull);
      expect(cubit.state.lastCreatedInviteId, invitesApi.lastCreatedInviteId);
      expect(cubit.state.isSubmitting, isFalse);
      expect(cubit.state.actionError, isNull);
      await cubit.close();
    });

    test('createInvite_sameHouseholdTwice_createsTwoIndependentInvites', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.createInvite();
      await cubit.createInvite();

      expect(invitesApi.createCallCount, 2);
      expect(cubit.state.invites, hasLength(2));
      expect(invitesApi.createInviteIds.toSet(), hasLength(2));
      await cubit.close();
    });

    test('createInvite_surfacesARejectionAsAnInlineActionError', () async {
      invitesApi.createInviteError = const AppException(AppError(code: 'identity.notAMember', message: 'debug'));
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.createInvite();

      expect(cubit.state.actionError?.code, 'identity.notAMember');
      expect(cubit.state.isSubmitting, isFalse);
      expect(cubit.state.invites, isEmpty);
      await cubit.close();
    });

    test('createInvite_afterASuccess_aFailedCreatePreservesLastCreatedInviteId', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.createInvite();
      final firstInviteId = cubit.state.lastCreatedInviteId;
      expect(firstInviteId, isNotNull);

      // A subsequent create that fails must not wipe the earlier invite's shareable card — the
      // earlier invite is still valid and pending.
      invitesApi.createInviteError = const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      await cubit.createInvite();

      expect(cubit.state.lastCreatedInviteId, firstInviteId);
      expect(cubit.state.actionError?.code, 'network.unreachable');
      expect(cubit.state.isSubmitting, isFalse);
      await cubit.close();
    });

    test('createInvite_isSubmittingGuardIgnoresASecondCallWhileTheFirstIsInFlight', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();

      // Both calls start synchronously; the first sets isSubmitting before yielding at its first
      // await, so the second observes isSubmitting=true and is a no-op (Epic-2 Action 3 lesson) —
      // never a second concurrent create.
      final firstCreate = cubit.createInvite();
      final secondCreate = cubit.createInvite();
      await Future.wait([firstCreate, secondCreate]);

      expect(invitesApi.createCallCount, 1);
      await cubit.close();
    });

    test('createInvite_mintsAFreshCommandIdAndInviteIdEveryCall', () async {
      final cubit = buildCubit();
      await cubit.bootstrap();

      await cubit.createInvite();
      await cubit.createInvite();

      expect(invitesApi.createCommandIds.toSet(), hasLength(2));
      expect(invitesApi.createInviteIds.toSet(), hasLength(2));
      await cubit.close();
    });
  });
}
