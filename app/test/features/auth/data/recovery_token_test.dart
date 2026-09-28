import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/recovery_token.dart';

void main() {
  group('RecoveryToken.format', () {
    test('format_producesTwentyEightDataAndChecksumCharsAsSevenHyphenGroupsOfFour', () async {
      final entropy = Uint8List.fromList(List<int>.generate(16, (index) => index));

      final token = await RecoveryToken.format(entropy);

      final groups = token.split('-');
      expect(groups, hasLength(7));
      for (final group in groups) {
        expect(group, hasLength(4));
      }
      expect(token.replaceAll('-', ''), hasLength(28));
    });

    test('format_isDeterministic_theSameEntropyAlwaysEncodesToTheSameToken', () async {
      final entropy = Uint8List.fromList(List<int>.generate(16, (index) => 255 - index));

      final first = await RecoveryToken.format(entropy);
      final second = await RecoveryToken.format(Uint8List.fromList(entropy));

      expect(first, second);
    });

    test('format_differentEntropyProducesADifferentToken', () async {
      final first = await RecoveryToken.format(Uint8List(16));
      final second = await RecoveryToken.format(Uint8List(16)..[15] = 1);

      expect(first, isNot(second));
    });

    test('format_forAFixedEntropy_producesThePinnedKnownToken', () async {
      final entropy = Uint8List.fromList(List<int>.generate(16, (index) => index));

      final token = await RecoveryToken.format(entropy);

      expect(token, '000G-40R4-0M30-E209-185G-R38E-1WQS');
    });

    test('format_rejectsEntropyOfTheWrongLength_throwsArgumentError', () async {
      final tooShortEntropy = Uint8List(15);

      expect(() => RecoveryToken.format(tooShortEntropy), throwsA(isA<ArgumentError>()));
    });
  });

  group('RecoveryToken.parse (round trip + forgiving normalization)', () {
    test('parse_roundTripsWithFormat_reconstructsTheSameEntropy', () async {
      final entropy = Uint8List.fromList(List<int>.generate(16, (index) => index));
      final token = await RecoveryToken.format(entropy);

      final reconstructedEntropy = await RecoveryToken.parse(token);

      expect(reconstructedEntropy, entropy);
    });

    test('parse_ofThePinnedKnownToken_reconstructsTheFixedEntropy', () async {
      final reconstructedEntropy = await RecoveryToken.parse('000G-40R4-0M30-E209-185G-R38E-1WQS');

      expect(reconstructedEntropy, Uint8List.fromList(List<int>.generate(16, (index) => index)));
    });

    test('parse_isDeterministic_theSameTokenAlwaysDecodesToTheSameEntropy', () async {
      final entropy = Uint8List.fromList(List<int>.generate(16, (index) => index * 3 % 256));
      final token = await RecoveryToken.format(entropy);

      final first = await RecoveryToken.parse(token);
      final second = await RecoveryToken.parse(token);

      expect(first, second);
    });

    test('parse_isForgiving_acceptsLowercaseWithSpacesAndNoHyphens', () async {
      final entropy = Uint8List.fromList(List<int>.generate(16, (index) => index));
      final token = await RecoveryToken.format(entropy);
      final messyInput = token.toLowerCase().replaceAll('-', ' ');

      final reconstructedEntropy = await RecoveryToken.parse(messyInput);

      expect(reconstructedEntropy, entropy);
    });

    test('parse_acceptsCrockfordAliases_oAsZeroAndIOrLAsOne', () async {
      // Constructed so the token's data portion is guaranteed to contain '0' and '1' characters:
      // all-zero entropy encodes to an all-'0' data portion.
      final entropy = Uint8List(16);
      final token = await RecoveryToken.format(entropy);
      final aliased = token.replaceAll('0', 'O').replaceAll('1', 'I');
      expect(aliased, isNot(token)); // sanity: the substitution actually did something

      final reconstructedEntropy = await RecoveryToken.parse(aliased);

      expect(reconstructedEntropy, entropy);
    });

    test('parse_isForgiving_acceptsUnicodeEnDashesInPlaceOfHyphens', () async {
      final entropy = Uint8List.fromList(List<int>.generate(16, (index) => index));
      final token = await RecoveryToken.format(entropy);
      final enDashSeparated = token.replaceAll('-', '–'); // en dash, not the ASCII hyphen

      final reconstructedEntropy = await RecoveryToken.parse(enDashSeparated);

      expect(reconstructedEntropy, entropy);
    });
  });

  group('RecoveryToken.parse (rejection)', () {
    test('parse_rejectsWrongLength_throwsInvalidRecoveryToken', () async {
      final entropy = Uint8List.fromList(List<int>.generate(16, (index) => index));
      final token = await RecoveryToken.format(entropy);
      final tooShort = token.substring(0, token.length - 2); // drop exactly 2 characters

      await expectLater(() => RecoveryToken.parse(tooShort), throwsA(isA<InvalidRecoveryToken>()));
    });

    test('parse_rejectsIllegalCharacter_throwsInvalidRecoveryToken', () async {
      final entropy = Uint8List.fromList(List<int>.generate(16, (index) => index));
      final token = await RecoveryToken.format(entropy);
      // 'U' is deliberately excluded from the Crockford alphabet (not an alias target either).
      final withIllegalChar = 'U${token.substring(1)}';

      await expectLater(() => RecoveryToken.parse(withIllegalChar), throwsA(isA<InvalidRecoveryToken>()));
    });

    test('parse_rejectsAMistypedChecksumCharacter_throwsInvalidRecoveryToken', () async {
      final entropy = Uint8List.fromList(List<int>.generate(16, (index) => index));
      final token = await RecoveryToken.format(entropy);
      final normalized = token.replaceAll('-', '');
      // Mutate the final (checksum) character to a different, definitely-distinct alphabet symbol —
      // this can never accidentally still satisfy the checksum, unlike mutating a data character.
      final lastChar = normalized[normalized.length - 1];
      const alphabet = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
      final replacement = alphabet.split('').firstWhere((char) => char != lastChar);
      final tampered = normalized.substring(0, normalized.length - 1) + replacement;

      await expectLater(() => RecoveryToken.parse(tampered), throwsA(isA<InvalidRecoveryToken>()));
    });

    test('parse_rejectsANonZeroPaddingBitInTheLastDataCharacter_throwsInvalidRecoveryToken', () async {
      // The last (26th) data character encodes 3 entropy bits plus 2 always-zero padding bits.
      // [RecoveryToken.format] never sets those 2 low bits, so flipping only the low bit here
      // — leaving the 3 entropy bits untouched — must be rejected rather than silently discarded.
      final entropy = Uint8List.fromList(List<int>.generate(16, (index) => index));
      final token = await RecoveryToken.format(entropy);
      final normalized = token.replaceAll('-', '');
      const alphabet = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
      final lastDataChar = normalized[25];
      final lastDataCharIndex = alphabet.indexOf(lastDataChar);
      expect(lastDataCharIndex & 0x3, 0); // sanity: format's padding bits are indeed zero
      final mutatedIndex = lastDataCharIndex | 0x1; // flip only a padding bit, not an entropy bit
      final mutatedLastDataChar = alphabet[mutatedIndex];
      final tampered = '${normalized.substring(0, 25)}$mutatedLastDataChar${normalized.substring(26)}';

      await expectLater(() => RecoveryToken.parse(tampered), throwsA(isA<InvalidRecoveryToken>()));
    });

    test('parse_rejectsAdjacentlyTransposedDataCharacters_throwsInvalidRecoveryToken', () async {
      // Entropy whose first two encoded characters are guaranteed to differ (a high first byte vs
      // a low second byte), so transposing them changes the decoded entropy and — with the
      // checksum recomputed over the now-wrong entropy — is caught.
      final entropy = Uint8List(16)
        ..[0] = 0xF0
        ..[1] = 0x0F;
      final token = await RecoveryToken.format(entropy);
      final normalized = token.replaceAll('-', '');
      final first = normalized[0];
      final second = normalized[1];
      expect(first, isNot(second)); // sanity: a genuine transposition
      final transposed = second + first + normalized.substring(2);

      await expectLater(() => RecoveryToken.parse(transposed), throwsA(isA<InvalidRecoveryToken>()));
    });
  });
}
