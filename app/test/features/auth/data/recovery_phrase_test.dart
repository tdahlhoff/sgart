import 'dart:typed_data';

import 'package:bip39/bip39.dart' as bip39;
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/recovery_phrase.dart';

void main() {
  group('RecoveryPhrase', () {
    test('wordsFromEntropy_encodesToExactlyTwentyFourWords', () {
      final entropy = Uint8List.fromList(List<int>.generate(32, (index) => index));

      final words = RecoveryPhrase.wordsFromEntropy(entropy);

      expect(words, hasLength(24));
    });

    test('wordsFromEntropy_isDeterministic_theSameEntropyAlwaysEncodesToTheSamePhrase', () {
      final entropy = Uint8List.fromList(List<int>.generate(32, (index) => 255 - index));

      final first = RecoveryPhrase.wordsFromEntropy(entropy);
      final second = RecoveryPhrase.wordsFromEntropy(Uint8List.fromList(entropy));

      expect(first, second);
    });

    test('wordsFromEntropy_producesAValidBip39Mnemonic', () {
      final entropy = Uint8List(32);

      final words = RecoveryPhrase.wordsFromEntropy(entropy);

      expect(bip39.validateMnemonic(words.join(' ')), isTrue);
    });

    test('wordsFromEntropy_differentEntropyProducesADifferentPhrase', () {
      final first = RecoveryPhrase.wordsFromEntropy(Uint8List(32));
      final second = RecoveryPhrase.wordsFromEntropy(Uint8List(32)..[31] = 1);

      expect(first, isNot(second));
    });
  });

  group('RecoveryPhrase.entropyFromWords (Story 7.2, AC3)', () {
    test('entropyFromWords_roundTripsWithWordsFromEntropy', () {
      final entropy = Uint8List.fromList(List<int>.generate(32, (index) => index));
      final words = RecoveryPhrase.wordsFromEntropy(entropy);

      final reconstructedEntropy = RecoveryPhrase.entropyFromWords(words);

      expect(reconstructedEntropy, entropy);
    });

    test('entropyFromWords_withInvalidChecksum_throwsInvalidRecoveryPhrase', () {
      final words = RecoveryPhrase.wordsFromEntropy(Uint8List(32));
      // Swapping the last (checksum-bearing) word for another valid wordlist word almost always
      // breaks the checksum without changing the word count or introducing an unknown word — an
      // isolated test of the checksum rule, distinct from the wrong-length/unknown-word cases.
      final tamperedWords = [...words.sublist(0, 23), 'zoo'];

      expect(() => RecoveryPhrase.entropyFromWords(tamperedWords), throwsA(isA<InvalidRecoveryPhrase>()));
    });

    test('entropyFromWords_withWrongWordCount_throwsInvalidRecoveryPhrase', () {
      final tooFewWords = RecoveryPhrase.wordsFromEntropy(Uint8List(32)).sublist(0, 12);

      expect(() => RecoveryPhrase.entropyFromWords(tooFewWords), throwsA(isA<InvalidRecoveryPhrase>()));
    });

    test('entropyFromWords_withAnUnknownWord_throwsInvalidRecoveryPhrase', () {
      final words = RecoveryPhrase.wordsFromEntropy(Uint8List(32));
      final wordsWithUnknownWord = [...words.sublist(0, 23), 'notabip39word'];

      expect(() => RecoveryPhrase.entropyFromWords(wordsWithUnknownWord), throwsA(isA<InvalidRecoveryPhrase>()));
    });
  });
}
