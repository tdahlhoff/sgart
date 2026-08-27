import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../shared/commands/command_intent.dart';
import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';
import '../data/item.dart';
import '../data/items_api.dart';
import 'list_detail_state.dart';

/// Drives the list detail screen (Story 2.3, AC1, AC3, AC4, AC6): loads the list's items, adds an
/// item with a required quantity and optional note, edits an item's name/note/quantity, and removes
/// an item. Depends only on the [ItemsApi] so tests never touch the network (CLAUDE.md §6); guards
/// every `emit` with `isClosed`. A Done list (`isReadOnly`) never issues add/edit/remove — the page
/// simply never wires the affordances, but the cubit also refuses defensively.
class ListDetailCubit extends Cubit<ListDetailState> {
  ListDetailCubit({
    required this.itemsApi,
    required this.householdId,
    required this.listId,
    required bool isReadOnly,
  }) : super(ListDetailState.loading(isReadOnly: isReadOnly));

  final ItemsApi itemsApi;
  final String householdId;
  final String listId;

  /// The add-item intent's ids: the command id plus one paired client-minted item id. Both are
  /// reused across retries of the same payload (idempotent retry, AD-8 — the optimistically-rendered
  /// item id matches the one the server persisted), freshened when the payload changes (a new
  /// intent), and freshened again after a successful add (a spent command id would be deduped
  /// server-side as a silent no-op, silently dropping the item — the Epic-1 retro footgun).
  final CommandIntent _addIntent = CommandIntent(hasResourceId: true);

  /// The update intent's command id, keyed on the target item id plus its new fields: reused across
  /// retries of the same edit, freshened when a different item or a different value is edited, and
  /// freshened again after a successful update.
  final CommandIntent _updateIntent = CommandIntent();

  /// The remove intent's command id, keyed on the target item id: reused across retries of removing
  /// the same item, freshened when a different item is removed, and freshened again after a
  /// successful remove (mirrors `StoresCubit._archiveIntent`).
  final CommandIntent _removeIntent = CommandIntent();

  /// Loads the list's items. Called once, right after construction.
  Future<void> bootstrap() async {
    try {
      final items = await itemsApi.listItems(householdId, listId);
      _safeEmit(ListDetailState.ready(items: items, isReadOnly: state.isReadOnly));
    } on Object catch (error) {
      _safeEmit(ListDetailState.failure(_toAppError(error), isReadOnly: state.isReadOnly));
    }
  }

  /// Reloads the list's items — the failure retry affordance.
  Future<void> refresh() => bootstrap();

  /// Adds an item by required [name] + [amount] + [unit] and an optional [note] (AC1). On success it
  /// optimistically appends the item (read-your-writes — the id is client-minted, so no projection
  /// wait) and returns `true`; a rejection (e.g. a duplicate) surfaces as an inline `actionError`
  /// without tearing down the screen and returns `false`. A re-entrant call while a submit is
  /// already in flight, or on a read-only (Done) list, is ignored (returns `false`).
  Future<bool> addItem({required String name, String? note, required String amount, required String unit}) async {
    if (state.status != ListDetailStatus.ready || state.isSubmitting || state.isReadOnly) {
      return false;
    }
    final trimmedName = name.trim();
    final trimmedNote = note?.trim();
    final noteOrNull = (trimmedNote == null || trimmedNote.isEmpty) ? null : trimmedNote;
    if (trimmedName.isEmpty) {
      return false;
    }
    _addIntent.beginAttempt((trimmedName, noteOrNull, amount, unit));
    final commandId = _addIntent.commandId;
    final itemId = _addIntent.resourceId();
    _safeEmit(state.copyWith(isSubmitting: true, clearActionError: true));
    try {
      await itemsApi.addItem(
        householdId,
        listId,
        itemId: itemId,
        name: trimmedName,
        note: noteOrNull,
        amount: amount,
        unit: unit,
        commandId: commandId,
      );
      final added = Item(itemId: itemId, name: trimmedName, note: noteOrNull, amount: amount, unit: unit);
      _safeEmit(state.copyWith(items: [...state.items, added], isSubmitting: false));
      // A successful add completes this intent — the next add is a new intent and never reuses a
      // command id the server has already applied (which it would silently drop).
      _addIntent.complete();
      return true;
    } on Object catch (error) {
      _safeEmit(state.copyWith(isSubmitting: false, actionError: _toAppError(error)));
      return false;
    }
  }

  /// Updates [itemId]'s name/note/quantity (AC3) and replaces it in place on success, returning
  /// `true`. A blank name is a client-side no-op (the form also disables submit on blank) and a
  /// rejection surfaces as an inline `actionError`; both return `false`. A re-entrant call while a
  /// submit is already in flight, or on a read-only (Done) list, is ignored (returns `false`).
  Future<bool> updateItem(
    String itemId, {
    required String name,
    String? note,
    required String amount,
    required String unit,
  }) async {
    if (state.status != ListDetailStatus.ready || state.isSubmitting || state.isReadOnly) {
      return false;
    }
    final trimmedName = name.trim();
    final trimmedNote = note?.trim();
    final noteOrNull = (trimmedNote == null || trimmedNote.isEmpty) ? null : trimmedNote;
    if (trimmedName.isEmpty) {
      return false;
    }
    _updateIntent.beginAttempt((itemId, trimmedName, noteOrNull, amount, unit));
    final commandId = _updateIntent.commandId;
    _safeEmit(state.copyWith(isSubmitting: true, clearActionError: true));
    try {
      await itemsApi.updateItem(
        householdId,
        listId,
        itemId,
        name: trimmedName,
        note: noteOrNull,
        amount: amount,
        unit: unit,
        commandId: commandId,
      );
      final updated = state.items
          .map((item) => item.itemId == itemId
              ? Item(itemId: itemId, name: trimmedName, note: noteOrNull, amount: amount, unit: unit)
              : item)
          .toList();
      _safeEmit(state.copyWith(items: updated, isSubmitting: false));
      _updateIntent.complete();
      return true;
    } on Object catch (error) {
      _safeEmit(state.copyWith(isSubmitting: false, actionError: _toAppError(error)));
      return false;
    }
  }

  /// Removes [itemId] (AC4) and drops it from the list on success. A failure surfaces as an inline
  /// `actionError`. A re-entrant call on a read-only (Done) list is ignored.
  Future<void> removeItem(String itemId) async {
    if (state.status != ListDetailStatus.ready || state.isSubmitting || state.isReadOnly) {
      return;
    }
    _removeIntent.beginAttempt(itemId);
    // Set isSubmitting like add/update: every command loads-then-appends under the list stream's
    // version, so two commands in flight at once would race that version and the loser gets a
    // spurious 409. Serializing them also keeps the single _removeIntent free of overlap.
    _safeEmit(state.copyWith(isSubmitting: true, clearActionError: true));
    try {
      await itemsApi.removeItem(householdId, listId, itemId, commandId: _removeIntent.commandId);
      _safeEmit(state.copyWith(
        items: state.items.where((item) => item.itemId != itemId).toList(),
        isSubmitting: false,
      ));
      _removeIntent.complete();
    } on Object catch (error) {
      _safeEmit(state.copyWith(isSubmitting: false, actionError: _toAppError(error)));
    }
  }

  AppError _toAppError(Object error) {
    if (error is AppException) {
      return error.error;
    }
    return AppError(code: 'items.unknown', message: error.toString());
  }

  void _safeEmit(ListDetailState state) {
    if (!isClosed) {
      emit(state);
    }
  }
}
