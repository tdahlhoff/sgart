import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'device_credential.dart';
import 'device_credential_store.dart';
import 'recovery_token.dart';

/// Real [DeviceCredentialStore] backed by `flutter_secure_storage` (Android Keystore / iOS
/// Keychain, Story 7.1 §2) — the 128-bit entropy is the device's actual secret, generated once
/// with a cryptographically secure random source and then never regenerated. Deliberately a
/// separate storage key from [FlutterSecureTokenStorage]'s session tokens: the device identity
/// outlives sign-out (a re-launch after sign-out re-derives and re-uses the *same* Keycloak
/// account, never a new one) and is only ever cleared by an app reinstall/data wipe.
class SecureEnclaveDeviceCredentialStore implements DeviceCredentialStore {
  const SecureEnclaveDeviceCredentialStore([this._storage = const FlutterSecureStorage()]);

  /// Story 8.5: a new key, deliberately distinct from the old (dropped) 32-byte
  /// `sgart.auth.deviceEntropy` entry — no real beta ever ran (emulator-only, no persisted data),
  /// so a stale old entry is simply ignored rather than migrated.
  static const _entropyKey = 'sgart.auth.recoveryTokenEntropy';

  /// 128 bits — the stored-entropy floor a short recovery token (Story 8.5, D1) encodes; the
  /// Ed25519 seed is HKDF-derived from it ([DeviceCredential.fromEntropy]), never a raw seed.
  static const _entropyLengthBytes = 16;

  final FlutterSecureStorage _storage;

  @override
  Future<DeviceCredential> loadOrCreate() async {
    final entropy = await _loadOrGenerateEntropy();
    return DeviceCredential.fromEntropy(entropy);
  }

  @override
  Future<String> recoveryToken() async {
    final entropy = await _loadOrGenerateEntropy();
    return RecoveryToken.format(entropy);
  }

  @override
  Future<void> restoreFromToken(String token) async {
    // Validate before writing anything — an InvalidRecoveryToken here propagates straight to the
    // caller with the current entropy left untouched (AC3, fail fast).
    final entropy = await RecoveryToken.parse(token);
    await _storage.write(key: _entropyKey, value: base64Encode(entropy));
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
