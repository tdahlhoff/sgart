import 'dart:convert';
import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';

/// The device's silent-provisioning identity (Story 7.1, §2 of the design note): a device-bound
/// Ed25519 keypair, deterministically derived from 32 bytes of entropy generated once on first
/// launch. The private key never leaves this object — only [publicKeyBase64Url] (registered with
/// Keycloak at provisioning) and [sign] (used to prove possession at sign-in) are exposed.
///
/// [publicKeyBase64Url] doubles as the Keycloak username (D-E, "deterministic encoding of the
/// public key") — computed purely from the keypair, so it never needs to be stored separately or
/// looked up from a server.
class DeviceCredential {
  DeviceCredential._(this._keyPair, this.publicKeyBase64Url);

  /// Derives the (deterministic) Ed25519 keypair from 32 bytes of entropy — the same entropy that
  /// is BIP39-encodable to the 24-word recovery phrase (not shown in this story, see
  /// [RecoveryPhrase]). All three representations are the *same* secret in different encodings.
  static Future<DeviceCredential> fromEntropy(Uint8List entropy) async {
    final keyPair = await Ed25519().newKeyPairFromSeed(entropy);
    final publicKey = await keyPair.extractPublicKey();
    return DeviceCredential._(keyPair, base64UrlEncode(publicKey.bytes).replaceAll('=', ''));
  }

  final SimpleKeyPair _keyPair;

  /// Base64url (no padding) encoding of the raw 32-byte Ed25519 public key — used verbatim as the
  /// Keycloak username (never shown to the person, never a server-issued identifier).
  final String publicKeyBase64Url;

  /// Signs `message` with the device's private key, returning a base64url (no padding) signature.
  /// The private key bytes never leave this call — [Ed25519.sign] operates on the in-memory
  /// [SimpleKeyPair] and returns only the signature.
  Future<String> sign(String message) async {
    final signature = await Ed25519().sign(utf8.encode(message), keyPair: _keyPair);
    return base64UrlEncode(signature.bytes).replaceAll('=', '');
  }
}
