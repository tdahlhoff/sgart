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
}
