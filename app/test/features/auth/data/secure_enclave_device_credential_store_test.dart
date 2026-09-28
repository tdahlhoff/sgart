import 'dart:typed_data';

import 'package:flutter_secure_storage/test/test_flutter_secure_storage_platform.dart';
import 'package:flutter_secure_storage_platform_interface/flutter_secure_storage_platform_interface.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/device_credential.dart';
import 'package:sgart/features/auth/data/recovery_token.dart';
import 'package:sgart/features/auth/data/secure_enclave_device_credential_store.dart';

void main() {
  group('SecureEnclaveDeviceCredentialStore', () {
    late Map<String, String> backingStore;
    late SecureEnclaveDeviceCredentialStore store;

    setUp(() {
      // The package's own in-memory test platform — no real Keychain/Keystore access (CLAUDE.md
      // §6) — swapped in for the plugin's platform-channel singleton.
      backingStore = <String, String>{};
      FlutterSecureStoragePlatform.instance = TestFlutterSecureStoragePlatform(backingStore);
      store = const SecureEnclaveDeviceCredentialStore();
    });

    test('recoveryToken_returnsATokenEncodingTheStoredEntropy', () async {
      final credential = await store.loadOrCreate(); // generates + persists the device's entropy

      final token = await store.recoveryToken();

      final reDerivedCredential = await DeviceCredential.fromEntropy(await RecoveryToken.parse(token));
      expect(reDerivedCredential.publicKeyBase64Url, credential.publicKeyBase64Url);
    });

    test('restoreFromToken_replacesStoredEntropy_soCredentialReDerivesSameUsername', () async {
      await store.loadOrCreate(); // seeds throwaway entropy first, like a freshly provisioned device
      final importedEntropy = Uint8List.fromList(List<int>.generate(16, (index) => index));
      final importedToken = await RecoveryToken.format(importedEntropy);
      final expectedCredential = await DeviceCredential.fromEntropy(importedEntropy);

      await store.restoreFromToken(importedToken);
      final restoredCredential = await store.loadOrCreate();

      expect(restoredCredential.publicKeyBase64Url, expectedCredential.publicKeyBase64Url);
    });

    test('restoreFromToken_withInvalidToken_writesNothing', () async {
      final originalCredential = await store.loadOrCreate();

      await expectLater(
        () => store.restoreFromToken('not-a-valid-token'),
        throwsA(isA<InvalidRecoveryToken>()),
      );
      final unchangedCredential = await store.loadOrCreate();

      expect(unchangedCredential.publicKeyBase64Url, originalCredential.publicKeyBase64Url);
    });
  });
}
