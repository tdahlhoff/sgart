import 'package:app_links/app_links.dart';

import '../../features/invites/data/invite_link.dart';

/// Surfaces the OS deep link `de.sgart.app://invite?h=<householdId>&i=<inviteId>` as parsed
/// [InviteLink]s (Story 4.6, D2/AC1): the cold-start initial link and a stream of subsequent ones.
///
/// Host-scoped to `invite` (the crux, AC1/AC8): `flutter_appauth` registers a redirect receiver for
/// the whole `de.sgart.app` scheme, using host `oauth` (`de.sgart.app://oauth/callback`). Every link
/// this service sees is filtered by `uri.host == 'invite'` before being handed to
/// [InviteLink.tryParse], so an OAuth callback link is dropped here even if the platform ever
/// surfaces it through the same channel — the two receivers never contend in Dart. A malformed
/// invite link (unparseable/missing ids) is silently dropped too — no crash, no spurious accept.
class InviteDeepLinkService {
  InviteDeepLinkService({
    required Future<Uri?> Function() getInitialLink,
    required Stream<Uri> uriLinkStream,
  })  : _getInitialLink = getInitialLink, // ignore: prefer_initializing_formals
        _uriLinkStream = uriLinkStream; // ignore: prefer_initializing_formals

  /// The real, `app_links`-backed instance — wired once at app startup.
  factory InviteDeepLinkService.appLinks() {
    final appLinks = AppLinks();
    return InviteDeepLinkService(
      getInitialLink: appLinks.getInitialLink,
      uriLinkStream: appLinks.uriLinkStream,
    );
  }

  final Future<Uri?> Function() _getInitialLink;
  final Stream<Uri> _uriLinkStream;

  /// The link the app was cold-started with, or `null` if none/not an invite link.
  Future<InviteLink?> initialInviteLink() async {
    final uri = await _getInitialLink();
    if (uri == null) {
      return null;
    }
    return _toInviteLink(uri);
  }

  /// Invite links received while the app is already running (or resumed).
  Stream<InviteLink> get inviteLinkStream => _uriLinkStream
      .map(_toInviteLink)
      .where((link) => link != null)
      .cast<InviteLink>();

  InviteLink? _toInviteLink(Uri uri) {
    if (uri.host != 'invite') {
      return null;
    }
    return InviteLink.tryParse(uri.toString());
  }
}
