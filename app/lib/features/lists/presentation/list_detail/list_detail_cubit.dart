import 'dart:developer' as developer;

import 'package:collection/collection.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../shared/commands/command_intent.dart';
import '../../../../shared/errors/app_error.dart';
import '../../../../shared/http/app_exception.dart';
import '../../../stores/data/store_summary.dart';
import '../../../stores/data/stores_api.dart';
import '../../data/item.dart';
import '../../data/item_suggestion.dart';
import '../../data/item_suggestions_api.dart';
import '../../data/items_api.dart';
import 'item_suggestion_cache.dart';
import 'list_detail_state.dart';

/// Drives the list detail screen (Story 2.3, AC1, AC3, AC4, AC6; Story 2.5, AC1/AC2/AC3/AC6; Story
/// 2.6, AC1, AC4, AC6): loads the list's items, adds an item with a required quantity and optional
/// note, edits an item's name/note/quantity, removes an item, loads the household's item
/// suggestions for the fast-add field's autocomplete, loads the household's active stores for the
/// store picker, and assigns an item to a store (optionally add-then-assign from a suggestion's
/// last-used store) — the cache/filter/upsert logic itself lives in [ItemSuggestionCache]. Depends
/// only on [ItemsApi]/[ItemSuggestionsApi]/[StoresApi] so tests never touch the network (CLAUDE.md
/// §6); guards every `emit` with `isClosed`. A Done list (`isReadOnly`) never issues add/edit/
/// remove/assign nor loads suggestions/stores (AC5) — the page simply never wires the affordances,
/// but the cubit also refuses defensively.
class ListDetailCubit extends Cubit<ListDetailState> {
  ListDetailCubit({
    required this.itemsApi,
    required this.itemSuggestionsApi,
    required this.storesApi,
    required this.householdId,
    required this.listId,
    required bool isReadOnly,
    this.suggestionCache = const ItemSuggestionCache(),
  }) : super(ListDetailState.loading(isReadOnly: isReadOnly));

  final ItemsApi itemsApi;
  final ItemSuggestionsApi itemSuggestionsApi;
  final StoresApi storesApi;
  final String householdId;
  final String listId;
  final ItemSuggestionCache suggestionCache;

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

  /// The clean-move intent's command id, keyed on (itemId, targetListId) (Story 2.4, AC1): reused
  /// across retries of the same move, freshened when a different item or target is moved, and
  /// freshened again after a successful move.
  final CommandIntent _moveIntent = CommandIntent();

  /// The collision-merge's target-side update intent, keyed on (targetItemId, amount, unit) — see
  /// [mergeIntoTarget] (Story 2.4, Clarification 3).
  final CommandIntent _mergeUpdateIntent = CommandIntent();

  /// The collision-merge's source-side remove intent, keyed on the source item id — see
  /// [mergeIntoTarget] (Story 2.4, Clarification 3).
  final CommandIntent _mergeRemoveIntent = CommandIntent();

  /// The assign-to-store intent's command id, keyed on `(itemId, storeId)` (Story 2.6, AC1): reused
  /// across retries of the same assignment, freshened when a different item or store is assigned,
  /// and freshened again after a successful assign (the Epic-1 spent-command-id footgun).
  final CommandIntent _assignIntent = CommandIntent();

  /// Loads the list's items, then — on an Open list only (AC5) — the household's suggestion cache
  /// and its active stores (Story 2.6). Called once, right after construction. A suggestions- or
  /// stores-load failure never fails the whole screen: the items still render, degrading to an
  /// empty suggestion/store set (log/ignore) — the fast-add field still works for "add as new" (AC3)
  /// and item rows degrade to the „+ Geschäft" chip (AC4) even with nothing loaded.
  Future<void> bootstrap() async {
    try {
      final items = await itemsApi.listItems(householdId, listId);
      // Carry the suggestion cache/stores across: a retry (`refresh`) would otherwise blank the
      // panel for the duration of the re-fetch and throw away every optimistic upsert made since the
      // open.
      _safeEmit(ListDetailState.ready(
        items: items,
        isReadOnly: state.isReadOnly,
        suggestions: state.suggestions,
        stores: state.stores,
      ));
    } on Object catch (error) {
      _safeEmit(ListDetailState.failure(_toAppError(error), isReadOnly: state.isReadOnly));
      return;
    }
    if (state.isReadOnly) {
      return;
    }
    try {
      final suggestions = await itemSuggestionsApi.listSuggestions(householdId);
      _safeEmit(state.copyWith(suggestions: suggestionCache.mergedWithLocalUpserts(state.suggestions, suggestions)));
    } on Object catch (error) {
      // Degrade to an empty suggestion set — the items already rendered successfully (AC3 still
      // works via "add as new" with no suggestions). Logged rather than swallowed silently: a member
      // whose autocomplete is quietly broken is otherwise invisible to everyone.
      developer.log('Loading item suggestions failed — autocomplete degrades to empty',
          name: 'sgart.lists', error: error);
    }
    try {
      final stores = await storesApi.listStores(householdId);
      _safeEmit(state.copyWith(stores: stores));
    } on Object catch (error) {
      // Degrade to an empty store list — item rows still render, the store chip falls back to
      // „+ Geschäft" for every item (AC4's same resolution path as an archived store).
      developer.log('Loading stores failed — store chips degrade to unassigned',
          name: 'sgart.lists', error: error);
    }
  }

  /// Matches [query] against the cached suggestions (Story 2.5, AC1, Cl. 2/6) via [suggestionCache].
  /// Pure — filters the in-memory cache only, no network call (lag-free).
  List<ItemSuggestion> suggestionsMatching(String query) => suggestionCache.matching(state.suggestions, query);

  /// Resolves an item's [storeId] against the loaded active stores (Story 2.6, AC4): `null` for
  /// unassigned **or** an archived/absent id — both render as the „+ Geschäft" ghost chip, since the
  /// archived store is no longer offered in the picker either (Story 1.8 AC E6).
  StoreSummary? storeFor(String? storeId) {
    if (storeId == null) {
      return null;
    }
    for (final store in state.stores) {
      if (store.storeId == storeId) {
        return store;
      }
    }
    return null;
  }

  /// Reloads the list's items — the failure retry affordance.
  Future<void> refresh() => bootstrap();

  /// Adds an item by required [name] + [amount] + [unit] and an optional [note] (AC1). On success it
  /// optimistically appends the item (read-your-writes — the id is client-minted, so no projection
  /// wait) and returns `true`; a rejection (e.g. a duplicate) surfaces as an inline `actionError`
  /// without tearing down the screen and returns `false`. A re-entrant call while a submit is
  /// already in flight, or on a read-only (Done) list, is ignored (returns `false`).
  Future<bool> addItem({required String name, String? note, required String amount, required String unit}) async {
    final itemId = await _addItemInternal(name: name, note: note, amount: amount, unit: unit);
    return itemId != null;
  }

  /// Adds an item pre-filled from [suggestion] (Story 2.5, AC2) and, when the suggestion carries a
  /// last-used store that is still active (AC6), immediately assigns the just-added item to it —
  /// add-then-assign, mirroring Story 2.4's create-then-move two-step. An archived (or otherwise
  /// unresolvable) last-used store is silently skipped: the item stays unassigned (AC4), exactly as
  /// a plain "add as new" would leave it. Returns whether the add itself succeeded (the assign, if
  /// attempted, runs fire-and-forget through [assignStore] and surfaces its own `actionError`).
  Future<bool> addItemFromSuggestion(ItemSuggestion suggestion) async {
    final itemId = await _addItemInternal(
      name: suggestion.name,
      note: suggestion.note,
      amount: suggestion.amount,
      unit: suggestion.unit,
    );
    if (itemId == null) {
      return false;
    }
    final defaultStoreId = suggestion.defaultStoreId;
    if (defaultStoreId != null && storeFor(defaultStoreId) != null) {
      await assignStore(itemId, defaultStoreId);
    }
    return true;
  }

  /// Shared add path for [addItem]/[addItemFromSuggestion] — returns the added item's id on success,
  /// `null` on a client-side guard failure or a server rejection (surfaced as `actionError`).
  Future<String?> _addItemInternal({
    required String name,
    String? note,
    required String amount,
    required String unit,
  }) async {
    if (state.status != ListDetailStatus.ready || state.isSubmitting || state.isReadOnly) {
      return null;
    }
    final trimmedName = name.trim();
    final trimmedNote = note?.trim();
    final noteOrNull = (trimmedNote == null || trimmedNote.isEmpty) ? null : trimmedNote;
    if (trimmedName.isEmpty) {
      return null;
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
      _safeEmit(state.copyWith(
        items: [...state.items, added],
        isSubmitting: false,
        suggestions: suggestionCache.upserted(state.suggestions, trimmedName, noteOrNull, amount, unit),
      ));
      // A successful add completes this intent — the next add is a new intent and never reuses a
      // command id the server has already applied (which it would silently drop).
      _addIntent.complete();
      return itemId;
    } on Object catch (error) {
      _safeEmit(state.copyWith(isSubmitting: false, actionError: _toAppError(error)));
      return null;
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
              // Cl. 7 (client mirror): an edit changes name/note/quantity only — carry the existing
              // store assignment forward, never wipe it optimistically (the backend preserves it too).
              ? Item(
                  itemId: itemId,
                  name: trimmedName,
                  note: noteOrNull,
                  amount: amount,
                  unit: unit,
                  storeId: item.storeId,
                )
              : item)
          .toList();
      _safeEmit(state.copyWith(
        items: updated,
        isSubmitting: false,
        suggestions: suggestionCache.upserted(state.suggestions, trimmedName, noteOrNull, amount, unit),
      ));
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

  /// Checks [targetListId] for an item whose (name, note) key matches [item]'s (trimmed,
  /// case-insensitive — mirrors the backend aggregate's `hasItemKeyed`, Story 2.4, Clarification 3).
  /// The caller uses this to branch: no match → [moveItem] (the clean move); a match → the
  /// quantity-merge dialog, then [mergeIntoTarget]. A read failure propagates — the caller falls back
  /// to the clean-move path, which the process manager's `DuplicateItemException` swallow (Cl. 3)
  /// makes safe even on a stale/failed pre-check.
  Future<Item?> findCollisionOnTarget(Item item, String targetListId) async {
    final targetItems = await itemsApi.listItems(householdId, targetListId);
    final normalizedName = item.name.trim().toLowerCase();
    final normalizedNote = item.note?.trim().toLowerCase();
    for (final candidate in targetItems) {
      final candidateNote = candidate.note?.trim().toLowerCase();
      if (candidate.name.trim().toLowerCase() == normalizedName && candidateNote == normalizedNote) {
        return candidate;
      }
    }
    return null;
  }

  /// Moves [itemId] to [targetListId] (Story 2.4, AC1) — the clean (non-collision) move path.
  /// Optimistically removes the row from this (source) detail view; a rejection reverts it and
  /// surfaces an inline `actionError`. A re-entrant call while a submit is already in flight, or on a
  /// read-only (Done) list, is ignored.
  Future<void> moveItem(String itemId, String targetListId) async {
    if (state.status != ListDetailStatus.ready || state.isSubmitting || state.isReadOnly) {
      return;
    }
    _moveIntent.beginAttempt((itemId, targetListId));
    final commandId = _moveIntent.commandId;
    final originalItems = state.items;
    _safeEmit(state.copyWith(
      items: originalItems.where((item) => item.itemId != itemId).toList(),
      isSubmitting: true,
      clearActionError: true,
    ));
    try {
      await itemsApi.moveItem(householdId, listId, itemId, targetListId: targetListId, commandId: commandId);
      _safeEmit(state.copyWith(isSubmitting: false));
      _moveIntent.complete();
    } on Object catch (error) {
      _safeEmit(state.copyWith(items: originalItems, isSubmitting: false, actionError: _toAppError(error)));
    }
  }

  /// Resolves the target collision branch (Story 2.4, Clarification 3): the target already holds
  /// [sourceItemId]'s (name, note) key as [targetItem], so the move never adds a duplicate — instead
  /// it optionally adjusts the target's quantity, then removes the source. When [adjustedAmount] and
  /// [adjustedUnit] are both `null` the target is left unchanged (a plain merge-remove). The target
  /// update runs first, then the source remove — a failure on either leg never strands the article on
  /// neither list. Optimistically removes the source row; a rejection reverts it and surfaces an
  /// inline `actionError`. A re-entrant call while a submit is already in flight, or on a read-only
  /// (Done) list, is ignored.
  Future<void> mergeIntoTarget(
    String sourceItemId,
    String targetListId,
    Item targetItem, {
    String? adjustedAmount,
    String? adjustedUnit,
  }) async {
    if (state.status != ListDetailStatus.ready || state.isSubmitting || state.isReadOnly) {
      return;
    }
    final originalItems = state.items;
    _safeEmit(state.copyWith(
      items: originalItems.where((item) => item.itemId != sourceItemId).toList(),
      isSubmitting: true,
      clearActionError: true,
    ));
    var targetUpdated = false;
    try {
      if (adjustedAmount != null && adjustedUnit != null) {
        _mergeUpdateIntent.beginAttempt((targetItem.itemId, adjustedAmount, adjustedUnit));
        await itemsApi.updateItem(
          householdId,
          targetListId,
          targetItem.itemId,
          name: targetItem.name,
          note: targetItem.note,
          amount: adjustedAmount,
          unit: adjustedUnit,
          commandId: _mergeUpdateIntent.commandId,
        );
        _mergeUpdateIntent.complete();
        targetUpdated = true;
      }
      _mergeRemoveIntent.beginAttempt(sourceItemId);
      await itemsApi.removeItem(householdId, listId, sourceItemId, commandId: _mergeRemoveIntent.commandId);
      _mergeRemoveIntent.complete();
      _safeEmit(state.copyWith(isSubmitting: false));
    } on Object catch (error) {
      // If the target update already applied, the failure is only the source removal — say so
      // explicitly so the member re-removes the source rather than re-merging an already-summed
      // target (which would double-count the quantity).
      final actionError = targetUpdated
          ? const AppError(
              code: 'list.moveMergeRemoveFailed',
              message: 'Target quantity was updated but removing the item from the source list failed.',
            )
          : _toAppError(error);
      _safeEmit(state.copyWith(items: originalItems, isSubmitting: false, actionError: actionError));
    }
  }

  /// Assigns [itemId] to [storeId] (Story 2.6, AC1, AC5). Optimistically sets the item's `storeId`
  /// in state (read-your-writes — the projection is eventually consistent, AR3/NFR9); a rejection
  /// reverts it and surfaces an inline `actionError`. Also upserts the item's name into the local
  /// suggestion cache's `defaultStoreId` (AC6's "zuletzt" chip, read-your-writes there too). A
  /// re-entrant call while a submit is already in flight, or on a read-only (Done) list, is ignored.
  ///
  /// [store] is the picker's returned [StoreSummary] when known (the tap-an-existing or the
  /// inline-create path): a store the store cache does not yet hold — an **inline-created** one — is
  /// merged into `state.stores` so the just-assigned chip resolves its name (read-your-writes) and a
  /// re-opened picker offers it, without waiting for a `bootstrap()` refetch (Cl. 9). The
  /// add-then-assign path passes only a `storeId` (its store is already active in the cache, gated by
  /// [storeFor]).
  Future<void> assignStore(String itemId, String storeId, {StoreSummary? store}) async {
    if (state.status != ListDetailStatus.ready || state.isSubmitting || state.isReadOnly) {
      return;
    }
    final originalItems = state.items;
    final target = originalItems.where((item) => item.itemId == itemId).firstOrNull;
    if (target == null) {
      return;
    }
    _assignIntent.beginAttempt((itemId, storeId));
    final commandId = _assignIntent.commandId;
    final originalStores = state.stores;
    final updatedStores = (store != null && storeFor(store.storeId) == null)
        ? [...originalStores, store]
        : originalStores;
    final updatedItems = originalItems
        .map((item) => item.itemId == itemId
            ? Item(itemId: item.itemId, name: item.name, note: item.note, amount: item.amount, unit: item.unit, storeId: storeId)
            : item)
        .toList();
    _safeEmit(state.copyWith(items: updatedItems, stores: updatedStores, isSubmitting: true, clearActionError: true));
    try {
      await itemsApi.assignStore(householdId, listId, itemId, storeId: storeId, commandId: commandId);
      _safeEmit(state.copyWith(
        isSubmitting: false,
        suggestions: suggestionCache.withDefaultStore(state.suggestions, target.name, storeId),
      ));
      _assignIntent.complete();
    } on Object catch (error) {
      _safeEmit(state.copyWith(items: originalItems, isSubmitting: false, actionError: _toAppError(error)));
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
