import 'package:shared_preferences/shared_preferences.dart';

/// Persists the caller's last-active household id on-device so a relaunch returns to it, skipping
/// the ≥2 selection screen (Story 1.7, Clarification B). An interface so [HouseholdsCubit] depends
/// on an abstraction and tests inject an in-memory fake — no real device storage in a unit test
/// (CLAUDE.md §6).
///
/// **DSGVO:** the stored id references household membership (personal data), so [clear] exists
/// for a future identity switch on the same device (e.g. Story 7.2/7.3's recovery-phrase import)
/// and is covered by AD-7's device-cache purge on erasure — a newly-recovered identity on the same
/// device must never inherit the previous identity's active household. `AuthCubit` has no sign-out
/// action (Story 7.1 code review: the device credential is permanent, so there is nothing to clear
/// on today's only trigger) and does not call [clear] itself.
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
