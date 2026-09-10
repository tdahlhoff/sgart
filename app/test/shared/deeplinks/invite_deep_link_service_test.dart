import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/invites/data/invite_link.dart';
import 'package:sgart/shared/deeplinks/invite_deep_link_service.dart';

void main() {
  group('InviteDeepLinkService', () {
    test('an incoming invite deep link is parsed and emitted', () async {
      final uriLinkStreamController = StreamController<Uri>();
      final service = InviteDeepLinkService(
        getInitialLink: () async => null,
        uriLinkStream: uriLinkStreamController.stream,
      );

      final emitted = service.inviteLinkStream.first;
      uriLinkStreamController.add(Uri.parse('de.sgart.app://invite?h=household-1&i=invite-1'));

      expect(
        await emitted,
        const InviteLink(householdId: 'household-1', inviteId: 'invite-1'),
      );

      await uriLinkStreamController.close();
    });

    test('an oauth callback link is ignored (host-scoping)', () async {
      final uriLinkStreamController = StreamController<Uri>();
      final service = InviteDeepLinkService(
        getInitialLink: () async => null,
        uriLinkStream: uriLinkStreamController.stream,
      );

      final emittedLinks = <InviteLink>[];
      final subscription = service.inviteLinkStream.listen(emittedLinks.add);

      uriLinkStreamController.add(Uri.parse('de.sgart.app://oauth/callback?code=abc&state=xyz'));
      uriLinkStreamController.add(Uri.parse('de.sgart.app://invite?h=household-1&i=invite-1'));
      await Future<void>.delayed(Duration.zero);

      expect(emittedLinks, [const InviteLink(householdId: 'household-1', inviteId: 'invite-1')]);

      await subscription.cancel();
      await uriLinkStreamController.close();
    });

    test('a malformed invite link is dropped without emitting or throwing', () async {
      final uriLinkStreamController = StreamController<Uri>();
      final service = InviteDeepLinkService(
        getInitialLink: () async => null,
        uriLinkStream: uriLinkStreamController.stream,
      );

      final emittedLinks = <InviteLink>[];
      final subscription = service.inviteLinkStream.listen(emittedLinks.add);

      uriLinkStreamController.add(Uri.parse('de.sgart.app://invite?h=&i='));
      uriLinkStreamController.add(Uri.parse('de.sgart.app://invite?h=household-1&i=invite-1'));
      await Future<void>.delayed(Duration.zero);

      expect(emittedLinks, [const InviteLink(householdId: 'household-1', inviteId: 'invite-1')]);

      await subscription.cancel();
      await uriLinkStreamController.close();
    });

    test('the cold-start initial link is surfaced', () async {
      final service = InviteDeepLinkService(
        getInitialLink: () async => Uri.parse('de.sgart.app://invite?h=household-1&i=invite-1'),
        uriLinkStream: const Stream<Uri>.empty(),
      );

      expect(
        await service.initialInviteLink(),
        const InviteLink(householdId: 'household-1', inviteId: 'invite-1'),
      );
    });

    test('no cold-start link surfaces as null', () async {
      final service = InviteDeepLinkService(
        getInitialLink: () async => null,
        uriLinkStream: const Stream<Uri>.empty(),
      );

      expect(await service.initialInviteLink(), isNull);
    });

    test('a cold-start oauth callback link is ignored', () async {
      final service = InviteDeepLinkService(
        getInitialLink: () async => Uri.parse('de.sgart.app://oauth/callback?code=abc'),
        uriLinkStream: const Stream<Uri>.empty(),
      );

      expect(await service.initialInviteLink(), isNull);
    });
  });
}
