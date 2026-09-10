import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/invites/data/invite_link.dart';
import 'package:sgart/features/invites/presentation/pending_invite_link_cubit.dart';
import 'package:sgart/shared/deeplinks/invite_deep_link_service.dart';

void main() {
  group('PendingInviteLinkCubit', () {
    test('offer records the link as pending state', () {
      final cubit = PendingInviteLinkCubit();

      cubit.offer(const InviteLink(householdId: 'household-1', inviteId: 'invite-1'));

      expect(cubit.state, const InviteLink(householdId: 'household-1', inviteId: 'invite-1'));
      cubit.close();
    });

    test('consume returns the pending link and clears it', () {
      final cubit = PendingInviteLinkCubit();
      cubit.offer(const InviteLink(householdId: 'household-1', inviteId: 'invite-1'));

      final consumed = cubit.consume();

      expect(consumed, const InviteLink(householdId: 'household-1', inviteId: 'invite-1'));
      expect(cubit.state, isNull);
      cubit.close();
    });

    test('consuming with nothing pending returns null and stays null', () {
      final cubit = PendingInviteLinkCubit();

      expect(cubit.consume(), isNull);
      expect(cubit.state, isNull);
      cubit.close();
    });

    test('a later offer overwrites an unconsumed link', () {
      final cubit = PendingInviteLinkCubit();
      cubit.offer(const InviteLink(householdId: 'household-1', inviteId: 'invite-1'));

      cubit.offer(const InviteLink(householdId: 'household-2', inviteId: 'invite-2'));

      expect(cubit.state, const InviteLink(householdId: 'household-2', inviteId: 'invite-2'));
      cubit.close();
    });

    test(
      'wired to InviteDeepLinkService (the main.dart composition, Story 4.6 AC8), '
      'an OAuth callback link never reaches the cubit while an invite link does',
      () async {
        final uriLinkStreamController = StreamController<Uri>();
        final deepLinkService = InviteDeepLinkService(
          getInitialLink: () async => null,
          uriLinkStream: uriLinkStreamController.stream,
        );
        final cubit = PendingInviteLinkCubit();
        final subscription = deepLinkService.inviteLinkStream.listen(cubit.offer);

        uriLinkStreamController.add(Uri.parse('de.sgart.app://oauth/callback?code=abc&state=xyz'));
        await Future<void>.delayed(Duration.zero);
        expect(cubit.state, isNull);

        uriLinkStreamController.add(Uri.parse('de.sgart.app://invite?h=household-1&i=invite-1'));
        await Future<void>.delayed(Duration.zero);
        expect(cubit.state, const InviteLink(householdId: 'household-1', inviteId: 'invite-1'));

        await subscription.cancel();
        await uriLinkStreamController.close();
        await cubit.close();
      },
    );
  });
}
