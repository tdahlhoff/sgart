import 'package:flutter_bloc/flutter_bloc.dart';

import '../data/locale_preference_store.dart';
import 'locale_state.dart';

/// Holds the active locale selection above `MaterialApp` and feeds `MaterialApp.locale` (Story 1.10,
/// AC1/AC2). Persists a member's choice per authenticated user via [LocalePreferenceStore] and
/// restores it when they sign in, so a relaunch speaks their language & region.
///
/// It remembers the current user id from [applyForUser] so later [select] calls persist against the
/// right person — the picker sits below `MaterialApp` (and so below `AuthCubit`) and cannot be handed
/// the `sub` through the provider tree (see the story's provider-tree note). Every store read/write is
/// guarded: a device-storage failure degrades to [SystemLocale] rather than crashing the app (the
/// Story 1.7 `ActiveHouseholdStore` guarding lesson).
class LocaleCubit extends Cubit<LocaleState> {
  LocaleCubit(this._store) : super(const SystemLocale());

  final LocalePreferenceStore _store;

  /// The signed-in user whose locale is currently managed, or `null` while signed out. Kept so
  /// [select] persists against the right key without threading the `sub` down to the picker.
  String? _currentUserId;

  /// Restores [userId]'s stored locale on sign-in — their tag becomes the active selection, or
  /// [SystemLocale] when they have no stored choice. A storage failure degrades to [SystemLocale].
  Future<void> applyForUser(String userId) async {
    _currentUserId = userId;
    try {
      _safeEmit(LocaleState.fromStoredTag(await _store.read(userId)));
    } on Object {
      _safeEmit(const SystemLocale());
    }
  }

  /// Applies and persists a member's pick. The whole app re-renders under [selection] immediately
  /// (state emitted first); persistence is best-effort — a storage failure leaves the running app on
  /// the new locale rather than crashing. [SystemLocale] clears the stored key (back to device
  /// default). No-op persistence while signed out (no user to key against).
  Future<void> select(LocaleState selection) async {
    _safeEmit(selection);
    final userId = _currentUserId;
    if (userId == null) {
      return;
    }
    try {
      final tag = selection.persistableTag;
      if (tag == null) {
        await _store.clear(userId);
      } else {
        await _store.write(userId, tag);
      }
    } on Object {
      // Persistence is best-effort: the selection is already applied in memory. A stale stored tag
      // is harmless — it is re-read (and can be re-selected) on the next launch.
    }
  }

  /// On sign-out: drop back to the device default **and** erase the signed-out user's persisted
  /// locale, so a later sign-in on the same device never inherits it (DSGVO / AD-7). Consolidates the
  /// reset and the per-user clear so the auth bridge needs no direct store reference.
  Future<void> resetForSignOut() async {
    final userId = _currentUserId;
    _currentUserId = null;
    _safeEmit(const SystemLocale());
    if (userId == null) {
      return;
    }
    try {
      await _store.clear(userId);
    } on Object {
      // A failed clear must not strand sign-out; the key is re-cleared on the next sign-out/launch.
    }
  }

  /// Emits only while the cubit is open — an awaited store call can complete after `close()` (e.g. the
  /// holder is torn down mid sign-in), and emitting then throws `StateError` (mirrors `AuthCubit`).
  void _safeEmit(LocaleState state) {
    if (!isClosed) {
      emit(state);
    }
  }
}
