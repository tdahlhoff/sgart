import 'package:shared_preferences/shared_preferences.dart';

/// Persists the caller's last-active household id on-device so a relaunch returns to it, skipping
/// the ≥2 selection screen (Story 1.7, Clarification B). An interface so [HouseholdsCubit] depends
/// on an abstraction and tests inject an in-memory fake — no real device storage in a unit test
/// (CLAUDE.md §6).
///
/// **DSGVO:** the stored id references household membership (personal data), so it is cleared on
/// sign-out (see [AuthCubit.signOut]) and covered by AD-7's device-cache purge on erasure — a fresh
/// sign-in on the same device must never inherit the previous person's active household.
abstract interface class ActiveHouseholdStore {
  Future<String?> readActive();

  Future<void> writeActive(String householdId);

  Future<void> clear();
}

/// [ActiveHouseholdStore] backed by `shared_preferences`. The only place the plugin is touched, so
/// the cubit and its tests never depend on it directly.
class SharedPreferencesActiveHouseholdStore implements ActiveHouseholdStore {
  const SharedPreferencesActiveHouseholdStore();

  static const String _activeHouseholdKey = 'sgart.activeHouseholdId';

  @override
  Future<String?> readActive() async {
    final preferences = await SharedPreferences.getInstance();
    return preferences.getString(_activeHouseholdKey);
  }

  @override
  Future<void> writeActive(String householdId) async {
    final preferences = await SharedPreferences.getInstance();
    await preferences.setString(_activeHouseholdKey, householdId);
  }

  @override
  Future<void> clear() async {
    final preferences = await SharedPreferences.getInstance();
    await preferences.remove(_activeHouseholdKey);
  }
}
