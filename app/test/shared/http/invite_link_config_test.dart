import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/shared/http/invite_link_config.dart';

void main() {
  group('InviteLinkConfig.rejectsConfiguredBaseUrl', () {
    const localhostDefault = 'http://localhost:8081/invite';
    const realOverride = 'https://sgart.example/invite';

    test('outsideReleaseMode_acceptsAnyValue_soDevBuildsUseTheLocalhostDefault', () {
      expect(
        InviteLinkConfig.rejectsConfiguredBaseUrl(isReleaseMode: false, configured: localhostDefault),
        isFalse,
      );
      expect(
        InviteLinkConfig.rejectsConfiguredBaseUrl(isReleaseMode: false, configured: ''),
        isFalse,
      );
    });

    test('inReleaseMode_rejectsTheUnoverriddenLocalhostDefault', () {
      expect(
        InviteLinkConfig.rejectsConfiguredBaseUrl(isReleaseMode: true, configured: localhostDefault),
        isTrue,
      );
    });

    test('inReleaseMode_rejectsABlankOrWhitespaceOverride', () {
      expect(InviteLinkConfig.rejectsConfiguredBaseUrl(isReleaseMode: true, configured: ''), isTrue);
      expect(InviteLinkConfig.rejectsConfiguredBaseUrl(isReleaseMode: true, configured: '   '), isTrue);
    });

    test('inReleaseMode_acceptsARealConfiguredBaseUrl', () {
      expect(
        InviteLinkConfig.rejectsConfiguredBaseUrl(isReleaseMode: true, configured: realOverride),
        isFalse,
      );
    });
  });
}
