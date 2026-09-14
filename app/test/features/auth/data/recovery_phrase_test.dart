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
}
