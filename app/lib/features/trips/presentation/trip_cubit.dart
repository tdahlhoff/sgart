import 'package:collection/collection.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../shared/commands/command_intent.dart';
import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';
import '../../lists/data/item.dart';
import '../../lists/data/items_api.dart';
import '../../stores/data/store_summary.dart';
import '../../stores/data/stores_api.dart';
import '../data/trips_api.dart';
import 'trip_state.dart';

/// Drives the trip screen (Story 3.2, AC1, AC2, AC3, AC5, Cl. 2/5/7/9): loads the store-grouped
/// active-trip view + the household's active stores (for name resolution, mirroring
/// `ListDetailCubit._loadStores`), reroutes an item between trip stores, and adds a store to the
/// trip spontaneously (existing or inline-created). Depends only on
/// [TripsApi]/[ItemsApi]/[StoresApi] so tests never touch the network (CLAUDE.md §6); guards every
/// `emit` with `isClosed`. Status/progress/completion are deliberately out of scope (Cl. 2 — Stories
/// 3.3/3.4).
class TripCubit extends Cubit<TripState> {
  TripCubit({
    required this.tripsApi,
    required this.itemsApi,
    required this.storesApi,
    required this.householdId,
    required this.listId,
  }) : super(const TripState.loading());

  final TripsApi tripsApi;
  final ItemsApi itemsApi;
  final StoresApi storesApi;
  final String householdId;
  final String listId;

  /// The reroute intent's command id, keyed on `(itemId, storeId)` (Story 3.2, AC2): reused across
  /// retries of the same reroute, freshened when a different item or target store is rerouted, and
  /// freshened again after a successful reroute (the Epic-1 spent-command-id footgun).
  final CommandIntent _rerouteIntent = CommandIntent();

  /// The add-existing-store-to-trip intent's command id, keyed on `storeId` (Story 3.2, AC3). Also
  /// reused for an inline-created store (created inside the picker, then added to the trip here).
  final CommandIntent _addStoreIntent = CommandIntent();

  /// Per-item status intents, each keyed on `itemId` — check-off, uncheck, postpone-in-place (Story
  /// 3.3, AC2/AC3).
  final CommandIntent _checkOffIntent = CommandIntent();
  final CommandIntent _uncheckIntent = CommandIntent();
  final CommandIntent _postponeInPlaceIntent = CommandIntent();

  /// Postpone-to-list intent, keyed on `(itemId, targetListId)` (Story 3.3, AC4).
  final CommandIntent _postponeToListIntent = CommandIntent();

  /// Loads the trip's grouped view, then the household's active stores (best-effort — a store-load
  /// failure never fails the whole screen, mirroring `ListDetailCubit.bootstrap`). Called once,
  /// right after construction.
  Future<void> bootstrap() async {
    try {
      final trip = await tripsApi.activeTrip(householdId, listId);
      _safeEmit(TripState.ready(
        tripId: trip.tripId,
        listId: trip.listId,
        storeIds: trip.storeIds,
        items: trip.items,
      ));
    } on Object catch (error) {
      _safeEmit(TripState.failure(_toAppError(error)));
      return;
    }
    try {
      final stores = await storesApi.listStores(householdId);
      _safeEmit(state.copyWith(stores: stores));
    } on Object {
      // Degrade to an empty store list — groups still render, items just fall back to
      // „Noch nicht zugeordnet" until a retry resolves the active store set (mirrors
      // ListDetailCubit.bootstrap's stores-load degrade).
    }
  }

  /// Reloads the trip's grouped view — the failure retry affordance.
  Future<void> refresh() => bootstrap();

  /// Re-routes [itemId] to [storeId] (Story 3.2, AC2). Optimistically moves the item into the target
  /// group (read-your-writes — the projection is eventually consistent, AR3/NFR9); a rejection
  /// reverts it and surfaces an inline `actionError`. A re-entrant call while a submit is already in
  /// flight is ignored.
  Future<void> reroute(String itemId, String storeId) async {
    if (state.status != TripStatus.ready || state.isSubmitting) {
      return;
    }
    final originalItems = state.items;
    final target = originalItems.where((item) => item.itemId == itemId).firstOrNull;
    if (target == null) {
      return;
    }
    _rerouteIntent.beginAttempt((itemId, storeId));
    final commandId = _rerouteIntent.commandId;
    final updatedItems = originalItems
        .map((item) => item.itemId == itemId ? item.copyWith(storeId: storeId) : item)
        .toList();
    _safeEmit(state.copyWith(items: updatedItems, isSubmitting: true, clearActionError: true));
    try {
      await itemsApi.rerouteItem(householdId, listId, itemId, storeId: storeId, commandId: commandId);
      _safeEmit(state.copyWith(isSubmitting: false));
      _rerouteIntent.complete();
    } on Object catch (error) {
      _safeEmit(state.copyWith(items: originalItems, isSubmitting: false, actionError: _toAppError(error)));
    }
  }

  /// Adds an already-active household [store] to the trip (Story 3.2, AC3). Optimistically appends
  /// its (possibly empty) group — a rejection reverts it and surfaces an inline `actionError`. A
  /// re-entrant call while a submit is already in flight is ignored.
  Future<void> addStoreToTrip(StoreSummary store) async {
    if (state.status != TripStatus.ready || state.isSubmitting) {
      return;
    }
    _addStoreIntent.beginAttempt(store.storeId);
    final commandId = _addStoreIntent.commandId;
    final originalStoreIds = state.storeIds;
    final originalStores = state.stores;
    final updatedStoreIds = originalStoreIds.contains(store.storeId) ? originalStoreIds : [...originalStoreIds, store.storeId];
    final updatedStores = originalStores.any((existing) => existing.storeId == store.storeId)
        ? originalStores
        : [...originalStores, store];
    _safeEmit(state.copyWith(
      storeIds: updatedStoreIds,
      stores: updatedStores,
      isSubmitting: true,
      clearActionError: true,
    ));
    try {
      await tripsApi.addStoreToTrip(householdId, listId, state.tripId, storeId: store.storeId, commandId: commandId);
      _safeEmit(state.copyWith(isSubmitting: false));
      _addStoreIntent.complete();
    } on Object catch (error) {
      _safeEmit(state.copyWith(
        storeIds: originalStoreIds,
        stores: originalStores,
        isSubmitting: false,
        actionError: _toAppError(error),
      ));
    }
  }

  /// Checks off [itemId] (Story 3.3, AC2) — optimistically sets status to `DONE`; reverts on
  /// failure. A re-entrant call while a submit is in flight is ignored.
  Future<void> checkOff(String itemId) async {
    await _applyStatusChange(
      itemId: itemId,
      newStatus: ItemStatus.done,
      intent: _checkOffIntent,
      send: (commandId) => itemsApi.checkOffItem(householdId, listId, itemId, commandId: commandId),
    );
  }

  /// Unchecks [itemId] (Story 3.3, AC2) — optimistically sets status to [ItemStatus.open]; reverts
  /// on failure.
  Future<void> uncheck(String itemId) async {
    await _applyStatusChange(
      itemId: itemId,
      newStatus: ItemStatus.open,
      intent: _uncheckIntent,
      send: (commandId) => itemsApi.uncheckItem(householdId, listId, itemId, commandId: commandId),
    );
  }

  /// Postpones [itemId] in place (Story 3.3, AC3) — optimistically sets status to
  /// [ItemStatus.postponed]; reverts on failure.
  Future<void> postponeInPlace(String itemId) async {
    await _applyStatusChange(
      itemId: itemId,
      newStatus: ItemStatus.postponed,
      intent: _postponeInPlaceIntent,
      send: (commandId) => itemsApi.postponeItem(householdId, listId, itemId, commandId: commandId),
    );
  }

  Future<void> _applyStatusChange({
    required String itemId,
    required ItemStatus newStatus,
    required CommandIntent intent,
    required Future<void> Function(String commandId) send,
  }) async {
    if (state.status != TripStatus.ready || state.isSubmitting) {
      return;
    }
    final originalItems = state.items;
    final target = originalItems.where((item) => item.itemId == itemId).firstOrNull;
    if (target == null) {
      return;
    }
    intent.beginAttempt(itemId);
    final commandId = intent.commandId;
    final updatedItems = originalItems
        .map((item) => item.itemId == itemId ? item.copyWith(status: newStatus) : item)
        .toList();
    _safeEmit(state.copyWith(items: updatedItems, isSubmitting: true, clearActionError: true));
    try {
      await send(commandId);
      _safeEmit(state.copyWith(isSubmitting: false));
      intent.complete();
    } on Object catch (error) {
      _safeEmit(state.copyWith(items: originalItems, isSubmitting: false, actionError: _toAppError(error)));
    }
  }

  /// Postpones [itemId] to [targetListId] (Story 3.3, AC4) — optimistically removes the item from
  /// the list; reverts on failure. A re-entrant call while a submit is in flight is ignored.
  Future<void> postponeToList(String itemId, String targetListId) async {
    if (state.status != TripStatus.ready || state.isSubmitting) {
      return;
    }
    final originalItems = state.items;
    final target = originalItems.where((item) => item.itemId == itemId).firstOrNull;
    if (target == null) {
      return;
    }
    _postponeToListIntent.beginAttempt((itemId, targetListId));
    final commandId = _postponeToListIntent.commandId;
    final updatedItems = originalItems.where((item) => item.itemId != itemId).toList();
    _safeEmit(state.copyWith(items: updatedItems, isSubmitting: true, clearActionError: true));
    try {
      await itemsApi.postponeItemToList(householdId, listId, itemId, targetListId: targetListId, commandId: commandId);
      _safeEmit(state.copyWith(isSubmitting: false));
      _postponeToListIntent.complete();
    } on Object catch (error) {
      _safeEmit(state.copyWith(items: originalItems, isSubmitting: false, actionError: _toAppError(error)));
    }
  }

  AppError _toAppError(Object error) {
    if (error is AppException) {
      return error.error;
    }
    return AppError(code: 'trips.unknown', message: error.toString());
  }

  void _safeEmit(TripState state) {
    if (!isClosed) {
      emit(state);
    }
  }
}
