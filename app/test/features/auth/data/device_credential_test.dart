import 'dart:convert';
import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/device_credential.dart';

void main() {
  group('DeviceCredential', () {
    test('fromEntropy_derivesTheSamePublicKeyForTheSameEntropy_deterministic', () async {
      final entropy = Uint8List.fromList(List<int>.generate(32, (index) => index));

      final first = await DeviceCredential.fromEntropy(entropy);
      final second = await DeviceCredential.fromEntropy(Uint8List.fromList(entropy));

      expect(first.publicKeyBase64Url, second.publicKeyBase64Url);
    });

    test('fromEntropy_differentEntropyProducesADifferentPublicKey', () async {
      final first = await DeviceCredential.fromEntropy(Uint8List(32));
      final second = await DeviceCredential.fromEntropy(Uint8List(32)..[0] = 1);

      expect(first.publicKeyBase64Url, isNot(second.publicKeyBase64Url));
    });

    test('publicKeyBase64Url_isUrlSafeAndUnpadded', () async {
      final credential = await DeviceCredential.fromEntropy(Uint8List(32));

      expect(credential.publicKeyBase64Url, isNot(contains('=')));
      expect(credential.publicKeyBase64Url, isNot(contains('+')));
      expect(credential.publicKeyBase64Url, isNot(contains('/')));
    });

    test('sign_producesASignatureThatVerifiesAgainstTheDerivedPublicKey', () async {
      final credential = await DeviceCredential.fromEntropy(Uint8List(32));
      const message = 'username|1700000000|nonce-1';

      final signatureBase64Url = await credential.sign(message);

      final signatureBytes = base64Url.decode(base64Url.normalize(signatureBase64Url));
      final publicKeyBytes = base64Url.decode(base64Url.normalize(credential.publicKeyBase64Url));
      final isValid = await Ed25519().verify(
        utf8.encode(message),
        signature: Signature(signatureBytes, publicKey: SimplePublicKey(publicKeyBytes, type: KeyPairType.ed25519)),
      );
      expect(isValid, isTrue);
    });

    test('sign_producesADifferentSignatureForADifferentMessage', () async {
      final credential = await DeviceCredential.fromEntropy(Uint8List(32));

      final first = await credential.sign('message-one');
      final second = await credential.sign('message-two');

      expect(first, isNot(second));
    });
  });
}
