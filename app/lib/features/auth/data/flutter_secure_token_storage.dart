import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'oidc_tokens.dart';
import 'secure_token_storage.dart';

/// Real [SecureTokenStorage] backed by `flutter_secure_storage` (Keychain on iOS, Keystore on
/// Android).
class FlutterSecureTokenStorage implements SecureTokenStorage {
  const FlutterSecureTokenStorage([this._storage = const FlutterSecureStorage()]);

  static const _accessTokenKey = 'sgart.auth.accessToken';
  static const _refreshTokenKey = 'sgart.auth.refreshToken';
  static const _idTokenKey = 'sgart.auth.idToken';

  final FlutterSecureStorage _storage;

  @override
  Future<void> save(OidcTokens tokens) async {
    await _storage.write(key: _accessTokenKey, value: tokens.accessToken);
    await _storage.write(key: _refreshTokenKey, value: tokens.refreshToken);
    await _storage.write(key: _idTokenKey, value: tokens.idToken);
  }

  @override
  Future<OidcTokens?> read() async {
    final accessToken = await _storage.read(key: _accessTokenKey);
    if (accessToken == null) {
      return null;
    }
    return OidcTokens(
      accessToken: accessToken,
      refreshToken: await _storage.read(key: _refreshTokenKey),
      idToken: await _storage.read(key: _idTokenKey),
    );
  }

  @override
  Future<void> clear() async {
    await _storage.delete(key: _accessTokenKey);
    await _storage.delete(key: _refreshTokenKey);
    await _storage.delete(key: _idTokenKey);
  }
}
