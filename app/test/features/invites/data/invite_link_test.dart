import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/invites/data/invite_link.dart';

void main() {
  group('InviteLink.tryParse', () {
    test('parses the canonical https URL form', () {
      final link = InviteLink.tryParse('https://sgart.example/invite?h=household-1&i=invite-1');

      expect(link, const InviteLink(householdId: 'household-1', inviteId: 'invite-1'));
    });

    test('parses a bare query-string code form', () {
      final link = InviteLink.tryParse('h=household-1&i=invite-1');

      expect(link, const InviteLink(householdId: 'household-1', inviteId: 'invite-1'));
    });

    test('parses the short householdId:inviteId code form', () {
      final link = InviteLink.tryParse('household-1:invite-1');

      expect(link, const InviteLink(householdId: 'household-1', inviteId: 'invite-1'));
    });

    test('trims surrounding whitespace', () {
      final link = InviteLink.tryParse('  household-1:invite-1  ');

      expect(link, const InviteLink(householdId: 'household-1', inviteId: 'invite-1'));
    });

    test('rejects an empty input', () {
      expect(InviteLink.tryParse(''), isNull);
      expect(InviteLink.tryParse('   '), isNull);
    });

    test('rejects a malformed input with no recognizable structure', () {
      expect(InviteLink.tryParse('not-a-link-at-all'), isNull);
    });

    test('rejects a URL missing the inviteId parameter', () {
      expect(InviteLink.tryParse('https://sgart.example/invite?h=household-1'), isNull);
    });

    test('rejects a URL missing the householdId parameter', () {
      expect(InviteLink.tryParse('https://sgart.example/invite?i=invite-1'), isNull);
    });

    test('rejects a colon-form code with too many segments', () {
      expect(InviteLink.tryParse('a:b:c'), isNull);
    });

    test('rejects a bare query string with a malformed percent-escape', () {
      // The bare-query path routes through Uri.splitQueryString, which throws on a bad escape; the
      // parser must swallow that and return null rather than let it crash the caller (F2). (The
      // canonical URL path goes through Uri.queryParameters, which tolerates "%zz" and yields it as
      // an opaque id value — left to server validation, consistent with not validating id content.)
      expect(InviteLink.tryParse('h=%zz&i=invite-1'), isNull);
    });
  });
}
