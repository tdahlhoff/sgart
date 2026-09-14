import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'oidc_tokens.dart';
import 'secure_token_storage.dart';

/// Real [SecureTokenStorage] backed by `flutter_secure_storage` (Keychain on iOS, Keystore on
/// Android).
class FlutterSecureTokenStorage implements SecureTokenStorage {
  const FlutterSecureTokenStorage([this._storage = const FlutterSecureStorage()]);

  static const _accessTokenKey = 'sgart.auth.accessToken';
  static const _refreshTokenKey = 'sgart.auth.refreshToken';

  /// Story 7.1 removed the id_token (Direct Grant issues none) — this key is never written
  /// anymore, but [clear] still deletes it once so a device that stored one before this story
  /// does not keep it in secure storage forever.
  static const _legacyIdTokenKey = 'sgart.auth.idToken';

  final FlutterSecureStorage _storage;

  @override
  Future<void> save(OidcTokens tokens) async {
    await _storage.write(key: _accessTokenKey, value: tokens.accessToken);
    await _storage.write(key: _refreshTokenKey, value: tokens.refreshToken);
  }

  @override
  Future<OidcTokens?> read() async {
    final accessToken = await _storage.read(key: _accessTokenKey);
    if (accessToken == null) {
      return null;
    }
    return OidcTokens(accessToken: accessToken, refreshToken: await _storage.read(key: _refreshTokenKey));
  }

  @override
  Future<void> clear() async {
    await _storage.delete(key: _accessTokenKey);
    await _storage.delete(key: _refreshTokenKey);
    await _storage.delete(key: _legacyIdTokenKey);
  }
}
