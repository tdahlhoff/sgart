import 'package:shared_preferences/shared_preferences.dart';

/// Persists a member's chosen locale on-device, **keyed by the authenticated user's id** (Keycloak
/// `sub`), so a relaunch restores the language & region they picked (Story 1.10, AC1). Stores the
/// canonical locale *tag* only (e.g. `de-CH`) — never a locale-formatted value (arch-spine
/// §Dates & formatting). An interface so [LocaleCubit] depends on an abstraction and tests inject an
/// in-memory fake — no real device storage in a unit test (CLAUDE.md §6).
///
/// **DSGVO:** the stored locale is personal display data. It is keyed per user and cleared on
/// sign-out (see [LocaleCubit.resetForSignOut]) and covered by AD-7's device-cache purge on erasure
/// — a later sign-in on the same device must never inherit the previous person's locale.
abstract interface class LocalePreferenceStore {
  Future<String?> read(String userId);

  Future<void> write(String userId, String localeTag);

  Future<void> clear(String userId);
}

/// [LocalePreferenceStore] backed by `shared_preferences`. The only place the plugin is touched for
/// locale, so the cubit and its tests never depend on it directly. Per-user keying (`sgart.locale.<userId>`)
/// gives stronger isolation than a single shared key: two people signing in on one device never see
/// each other's key even before the sign-out clear runs.
class SharedPreferencesLocalePreferenceStore implements LocalePreferenceStore {
  const SharedPreferencesLocalePreferenceStore();

  static const String _keyPrefix = 'sgart.locale.';

  String _keyForUser(String userId) => '$_keyPrefix$userId';

  @override
  Future<String?> read(String userId) async {
    final preferences = await SharedPreferences.getInstance();
    return preferences.getString(_keyForUser(userId));
  }

  @override
  Future<void> write(String userId, String localeTag) async {
    final preferences = await SharedPreferences.getInstance();
    await preferences.setString(_keyForUser(userId), localeTag);
  }

  @override
  Future<void> clear(String userId) async {
    final preferences = await SharedPreferences.getInstance();
    await preferences.remove(_keyForUser(userId));
  }
}
