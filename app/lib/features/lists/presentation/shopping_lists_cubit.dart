import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../shared/commands/command_intent.dart';
import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';
import '../data/shopping_list_summary.dart';
import '../data/shopping_lists_api.dart';
import 'shopping_lists_state.dart';

/// Drives the minimal lists surface (Story 2.1, AC1–AC3): loads the household's Open lists in
/// creation order, creates a list (named or unnamed), and renames a list. Depends only on the
/// [ShoppingListsApi] so tests never touch the network (CLAUDE.md §6); guards every `emit` with
/// `isClosed`.
class ShoppingListsCubit extends Cubit<ShoppingListsState> {
  ShoppingListsCubit({required this.shoppingListsApi, required this.householdId})
      : super(const ShoppingListsState.loading());

  final ShoppingListsApi shoppingListsApi;
  final String householdId;

  /// The create intent's ids: the command id plus one paired client-minted list id. Both are reused
  /// across retries of the same name (idempotent retry, AD-8 — the optimistically-rendered list id
  /// matches the one the server persisted), freshened when the name changes (a new intent), and
  /// freshened again after a successful create (a spent command id would be deduped server-side as a
  /// silent no-op, silently dropping the list — the Epic-1 retro footgun this reuses `CommandIntent`
  /// to avoid).
  final CommandIntent _createIntent = CommandIntent(hasResourceId: true);

  /// The rename intent's command id, keyed on the new name: reused across retries of the same rename
  /// (idempotent retry, AD-8), freshened when the name changes, and freshened again after a
  /// successful rename.
  final CommandIntent _renameIntent = CommandIntent();

  /// Loads the household's Open lists. Called once, right after construction.
  Future<void> bootstrap() => _load();

  /// Reloads the household's Open lists — the empty-state/failure retry affordance.
  Future<void> refresh() => _load();

  Future<void> _load() async {
    _safeEmit(const ShoppingListsState.loading());
    try {
      final lists = await shoppingListsApi.listOpenLists(householdId);
      _safeEmit(ShoppingListsState.ready(lists: lists));
    } on Object catch (error) {
      _safeEmit(ShoppingListsState.failure(_toAppError(error)));
    }
  }

  /// Creates a list by optional free-form [name] (AC1) — a blank/absent name creates a valid unnamed
  /// list (AC2), never an error. On success it optimistically appends the list (read-your-writes —
  /// the id is client-minted, so no projection wait) and returns `true`; a rejection surfaces as an
  /// inline `actionError` without tearing down the screen and returns `false` (the caller keeps the
  /// create sheet open so the typed name is not lost). A re-entrant call while a create/rename is
  /// already in flight is ignored (returns `false`).
  Future<bool> createList(String? name) async {
    if (state.status != ShoppingListsStatus.ready || state.isSubmitting) {
      return false;
    }
    final trimmedName = name?.trim();
    final nameOrNull = (trimmedName == null || trimmedName.isEmpty) ? null : trimmedName;
    _createIntent.beginAttempt(trimmedName ?? '');
    final commandId = _createIntent.commandId;
    final listId = _createIntent.resourceId();
    _safeEmit(state.copyWith(isSubmitting: true, clearActionError: true));
    try {
      await shoppingListsApi.createList(householdId, name: nameOrNull, listId: listId, commandId: commandId);
      final created = ShoppingListSummary(listId: listId, name: nameOrNull, status: 'OPEN');
      _safeEmit(state.copyWith(lists: [...state.lists, created], isSubmitting: false));
      // A successful create completes this intent — the next create is a new intent and never
      // reuses a command id the server has already applied (which it would silently drop).
      _createIntent.complete();
      return true;
    } on Object catch (error) {
      _safeEmit(state.copyWith(isSubmitting: false, actionError: _toAppError(error)));
      return false;
    }
  }

  /// Renames [listId] to [name] (AC3) and updates it in place on success, returning `true`. A
  /// blank/whitespace name is a client-side no-op (the rename affordance also disables submit on
  /// blank — no pointless round-trip) and a rejection (e.g. a Done list) surfaces as an inline
  /// `actionError`; both return `false` so the caller keeps the rename sheet open. A re-entrant call
  /// while a create/rename is already in flight is ignored (returns `false`).
  Future<bool> renameList(String listId, String name) async {
    if (state.status != ShoppingListsStatus.ready || state.isSubmitting) {
      return false;
    }
    final trimmedName = name.trim();
    if (trimmedName.isEmpty) {
      return false;
    }
    _renameIntent.beginAttempt(trimmedName);
    _safeEmit(state.copyWith(isSubmitting: true, clearActionError: true));
    try {
      await shoppingListsApi.renameList(householdId, listId, trimmedName, commandId: _renameIntent.commandId);
      final renamed = state.lists
          .map((list) => list.listId == listId
              ? ShoppingListSummary(listId: list.listId, name: trimmedName, status: list.status)
              : list)
          .toList();
      _safeEmit(state.copyWith(lists: renamed, isSubmitting: false));
      _renameIntent.complete();
      return true;
    } on Object catch (error) {
      _safeEmit(state.copyWith(isSubmitting: false, actionError: _toAppError(error)));
      return false;
    }
  }

  AppError _toAppError(Object error) {
    if (error is AppException) {
      return error.error;
    }
    return AppError(code: 'lists.unknown', message: error.toString());
  }

  void _safeEmit(ShoppingListsState state) {
    if (!isClosed) {
      emit(state);
    }
  }
}
