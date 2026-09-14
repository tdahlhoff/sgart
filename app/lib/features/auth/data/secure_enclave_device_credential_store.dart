import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'device_credential.dart';
import 'device_credential_store.dart';

/// Real [DeviceCredentialStore] backed by `flutter_secure_storage` (Android Keystore / iOS
/// Keychain, Story 7.1 §2) — the 256-bit entropy is the device's actual secret, generated once
/// with a cryptographically secure random source and then never regenerated. Deliberately a
/// separate storage key from [FlutterSecureTokenStorage]'s session tokens: the device identity
/// outlives sign-out (a re-launch after sign-out re-derives and re-uses the *same* Keycloak
/// account, never a new one) and is only ever cleared by an app reinstall/data wipe.
class SecureEnclaveDeviceCredentialStore implements DeviceCredentialStore {
  const SecureEnclaveDeviceCredentialStore([this._storage = const FlutterSecureStorage()]);

  static const _entropyKey = 'sgart.auth.deviceEntropy';

  /// 256 bits (RFC-typical for a strong symmetric secret; also the exact size BIP39 encodes to a
  /// 24-word mnemonic, D-A) — the entropy IS the Ed25519 seed, never a second derivation step.
  static const _entropyLengthBytes = 32;

  final FlutterSecureStorage _storage;

  @override
  Future<DeviceCredential> loadOrCreate() async {
    final entropy = await _loadOrGenerateEntropy();
    return DeviceCredential.fromEntropy(entropy);
  }

  Future<Uint8List> _loadOrGenerateEntropy() async {
    final stored = await _storage.read(key: _entropyKey);
    if (stored != null) {
      return base64Decode(stored);
    }
    final freshEntropy = _generateEntropy();
    await _storage.write(key: _entropyKey, value: base64Encode(freshEntropy));
    return freshEntropy;
  }

  Uint8List _generateEntropy() {
    final random = Random.secure();
    return Uint8List.fromList(List<int>.generate(_entropyLengthBytes, (_) => random.nextInt(256)));
  }
}
