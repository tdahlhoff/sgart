import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/invites/presentation/accept_invite_cubit.dart';
import 'package:sgart/features/invites/presentation/accept_invite_state.dart';
import 'package:sgart/shared/http/app_exception.dart';
import 'package:sgart/shared/errors/app_error.dart';

import '../../../support/fake_invites_dependencies.dart';

void main() {
  group('AcceptInviteCubit', () {
    late FakeInvitesApi invitesApi;

    setUp(() {
      invitesApi = FakeInvitesApi();
    });

    AcceptInviteCubit buildCubit() => AcceptInviteCubit(invitesApi: invitesApi);

    test('startsIdle', () {
      expect(buildCubit().state.status, AcceptInviteStatus.idle);
      buildCubit().close();
    });

    blocTest<AcceptInviteCubit, AcceptInviteState>(
      'accept_withAValidLink_acceptsAndEmitsSuccessWithTheHouseholdId',
      build: buildCubit,
      act: (cubit) => cubit.accept('https://sgart.example/invite?h=household-1&i=invite-1'),
      expect: () => [
        const AcceptInviteState.submitting(),
        const AcceptInviteState.success('household-1'),
      ],
      verify: (_) {
        expect(invitesApi.lastAcceptedHouseholdId, 'household-1');
        expect(invitesApi.lastAcceptedInviteId, 'invite-1');
        expect(invitesApi.acceptCallCount, 1);
      },
    );

    blocTest<AcceptInviteCubit, AcceptInviteState>(
      'accept_withAMalformedLink_isBlockedClientSideWithNoApiCall',
      build: buildCubit,
      act: (cubit) => cubit.accept('not-a-link-at-all'),
      expect: () => [
        const AcceptInviteState.failure(AppError(code: 'invite.invalidLink', message: 'client-side fail-fast')),
      ],
      verify: (_) {
        expect(invitesApi.acceptCallCount, 0);
      },
    );

    blocTest<AcceptInviteCubit, AcceptInviteState>(
      'accept_whenTheInviteIsExpired_emitsFailureWithTheExpiredCode',
      build: () {
        invitesApi.acceptInviteError = const AppException(AppError(code: 'invite.expired', message: 'debug only'));
        return buildCubit();
      },
      act: (cubit) => cubit.accept('household-1:invite-1'),
      expect: () => [
        const AcceptInviteState.submitting(),
        const AcceptInviteState.failure(AppError(code: 'invite.expired', message: 'debug only')),
      ],
    );

    blocTest<AcceptInviteCubit, AcceptInviteState>(
      'accept_whenTheInviteIsUnknown_emitsFailureWithTheNotFoundCode',
      build: () {
        invitesApi.acceptInviteError = const AppException(AppError(code: 'invite.notFound', message: 'debug only'));
        return buildCubit();
      },
      act: (cubit) => cubit.accept('household-1:invite-1'),
      expect: () => [
        const AcceptInviteState.submitting(),
        const AcceptInviteState.failure(AppError(code: 'invite.notFound', message: 'debug only')),
      ],
    );

    blocTest<AcceptInviteCubit, AcceptInviteState>(
      'accept_whenTheInviteWasAlreadyUsed_emitsFailureWithTheAlreadyUsedCode',
      build: () {
        invitesApi.acceptInviteError =
            const AppException(AppError(code: 'invite.alreadyUsed', message: 'debug only'));
        return buildCubit();
      },
      act: (cubit) => cubit.accept('household-1:invite-1'),
      expect: () => [
        const AcceptInviteState.submitting(),
        const AcceptInviteState.failure(AppError(code: 'invite.alreadyUsed', message: 'debug only')),
      ],
    );

    blocTest<AcceptInviteCubit, AcceptInviteState>(
      'accept_whileAlreadySubmitting_isANoOp',
      build: buildCubit,
      act: (cubit) async {
        final first = cubit.accept('household-1:invite-1');
        await cubit.accept('household-1:invite-1');
        await first;
      },
      expect: () => [
        const AcceptInviteState.submitting(),
        const AcceptInviteState.success('household-1'),
      ],
      verify: (_) {
        expect(invitesApi.acceptCallCount, 1);
      },
    );

    blocTest<AcceptInviteCubit, AcceptInviteState>(
      'accept_regeneratesTheCommandIdAfterASuccessfulAccept',
      build: buildCubit,
      act: (cubit) async {
        await cubit.accept('household-1:invite-1');
        await cubit.accept('household-1:invite-1');
      },
      verify: (_) {
        expect(invitesApi.acceptCommandIds, hasLength(2));
        expect(invitesApi.acceptCommandIds[0], isNot(invitesApi.acceptCommandIds[1]));
      },
    );
  });
}
