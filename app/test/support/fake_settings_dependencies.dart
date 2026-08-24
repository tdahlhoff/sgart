import 'package:sgart/features/settings/data/locale_preference_store.dart';

/// In-memory [LocalePreferenceStore] for fast unit/widget tests — no real device storage (CLAUDE.md
/// §6). Keyed per user like the production store so per-user isolation can be asserted. Set
/// [throwOnAccess] to simulate a device-storage failure and prove the cubit degrades gracefully.
class FakeLocalePreferenceStore implements LocalePreferenceStore {
  FakeLocalePreferenceStore();

  final Map<String, String> _tagsByUser = {};
  bool throwOnAccess = false;
  final List<String> clearedUsers = [];

  /// Seeds a stored tag for [userId] (test arrange helper).
  void seed(String userId, String localeTag) => _tagsByUser[userId] = localeTag;

  String? storedTagFor(String userId) => _tagsByUser[userId];

  @override
  Future<String?> read(String userId) async {
    if (throwOnAccess) {
      throw StateError('locale store unavailable');
    }
    return _tagsByUser[userId];
  }

  @override
  Future<void> write(String userId, String localeTag) async {
    if (throwOnAccess) {
      throw StateError('locale store unavailable');
    }
    _tagsByUser[userId] = localeTag;
  }

  @override
  Future<void> clear(String userId) async {
    clearedUsers.add(userId);
    if (throwOnAccess) {
      throw StateError('locale store unavailable');
    }
    _tagsByUser.remove(userId);
  }
}
