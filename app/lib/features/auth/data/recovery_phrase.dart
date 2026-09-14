import 'dart:typed_data';

import 'package:bip39/bip39.dart' as bip39;

/// Encodes the device's 256-bit entropy (the same bytes that seed [DeviceCredential]'s Ed25519
/// keypair, Story 7.1 §2) as the standard BIP39 24-word mnemonic — the *only* thing that survives
/// reinstall or a move to a new device.
///
/// This is the shared credential-model utility 7.1 builds and 7.2 (recovery-phrase reveal/re-view)
/// reuses; it is not wired into any UI in this story ("No phrase UI in this story" — the reveal
/// screen is 7.2's). Kept pure and dependency-free of storage/UI so both stories can call it.
abstract final class RecoveryPhrase {
  /// @return the 24-word mnemonic for the given 32-byte entropy, in BIP39's fixed English wordlist
  ///     order (space-separated words, ready to split for a reveal UI).
  static List<String> wordsFromEntropy(Uint8List entropy) {
    final hexEntropy = entropy.map((byte) => byte.toRadixString(16).padLeft(2, '0')).join();
    return bip39.entropyToMnemonic(hexEntropy).split(' ');
  }

  /// The inverse of [wordsFromEntropy] (Story 7.2, AC3): validates [words] as a BIP39 mnemonic —
  /// word count, wordlist membership, and checksum, all three in one call — and reconstructs the
  /// original 32-byte entropy. Throws [InvalidRecoveryPhrase] on any invalid input rather than a
  /// raw `ArgumentError`/`StateError` from the `bip39` package, so callers can fail fast with one
  /// typed error regardless of which rule was violated, and never proceed with partial state.
  static Uint8List entropyFromWords(List<String> words) {
    final mnemonic = words.join(' ');
    if (!bip39.validateMnemonic(mnemonic)) {
      throw const InvalidRecoveryPhrase();
    }
    final hexEntropy = bip39.mnemonicToEntropy(mnemonic);
    return Uint8List.fromList([
      for (var index = 0; index < hexEntropy.length; index += 2)
        int.parse(hexEntropy.substring(index, index + 2), radix: 16),
    ]);
  }
}

/// Thrown by [RecoveryPhrase.entropyFromWords] — and propagated verbatim by
/// [DeviceCredentialStore.restoreFromPhrase] — when the given words fail BIP39 validation (wrong
/// word count, a word outside the wordlist, or a failed checksum). Callers rely on this one type
/// covering every rejection case, so the recovery UI (AC3) can show a single "phrase invalid"
/// message with a plain `catch`/error-code branch instead of inspecting exception messages.
class InvalidRecoveryPhrase implements Exception {
  const InvalidRecoveryPhrase();

  @override
  String toString() => 'InvalidRecoveryPhrase';
}
