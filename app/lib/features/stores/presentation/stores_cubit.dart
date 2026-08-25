import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../shared/commands/command_intent.dart';
import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';
import '../data/store_chain.dart';
import '../data/store_chain_reference_cache.dart';
import '../data/store_summary.dart';
import '../data/stores_api.dart';
import '../domain/store_chain_matcher.dart';
import 'stores_state.dart';

/// Drives the manage-stores screen (Story 1.8, AC1–AC4): loads the active stores and the cached
/// chain reference list, computes the live advisory chain suggestion as the member types, adds a
/// store (with the accepted chain, or unlinked), and archives a store. Depends only on the
/// [StoresApi], [StoreChainReferenceCache], and the pure [StoreChainMatcher] interfaces so tests
/// never touch the network or device storage (CLAUDE.md §6); guards every `emit` with `isClosed`.
class StoresCubit extends Cubit<StoresState> {
  StoresCubit({
    required this.storesApi,
    required this.referenceCache,
    required this.householdId,
    this.matcher = const StoreChainMatcher(),
  }) : super(const StoresState.loading());

  final StoresApi storesApi;
  final StoreChainReferenceCache referenceCache;
  final String householdId;
  final StoreChainMatcher matcher;

  /// The add-store intent's ids: the command id plus one paired client-minted store id. Both are
  /// reused across retries of the same name (idempotent retry, AD-8 — and the optimistically-rendered
  /// store id matches the one the server persisted), freshened when the name changes (a new intent),
  /// and freshened again after a successful add (a spent command id would be deduped server-side as a
  /// silent no-op, silently dropping the store — Story 1.8).
  final CommandIntent _addIntent = CommandIntent(hasResourceId: true);

  /// The archive intent's command id, keyed on the target store id: reused across retries of
  /// archiving the same store (idempotent retry, AD-8), freshened when a different store is archived,
  /// and freshened again after a successful archive (a spent command id would be deduped server-side
  /// as a silent no-op, leaving the store un-archived).
  final CommandIntent _archiveIntent = CommandIntent();

  /// Loads the active store list (required) plus the cached chain reference list (best-effort — an
  /// offline first load with no cache simply leaves matching unavailable rather than failing the
  /// whole screen). Called once, right after construction.
  Future<void> bootstrap() async {
    try {
      final stores = await storesApi.listStores(householdId);
      final chains = await _loadChains();
      _safeEmit(StoresState.ready(stores: stores, chains: chains));
    } on Object catch (error) {
      _safeEmit(StoresState.failure(_toAppError(error)));
    }
  }

  Future<List<StoreChain>> _loadChains() async {
    try {
      return await referenceCache.load(storesApi);
    } on Object {
      // No chain reference (offline first load, no cache): the store list still shows; chain
      // suggestions are simply unavailable until the reference list can be fetched (AC2).
      return const [];
    }
  }

  /// Recomputes the advisory chain suggestion for the typed [name] (AC2). Every keystroke resets the
  /// „cleared" flag and any inline action error so the suggestion tracks the current text.
  void onNameChanged(String name) {
    if (state.status != StoresStatus.ready) {
      return;
    }
    final suggestion = matcher.suggestFor(name, state.chains);
    _safeEmit(state.copyWith(
      chainSuggestion: suggestion,
      clearSuggestion: suggestion == null,
      chainCleared: false,
      clearActionError: true,
    ));
  }

  /// Clears the current suggestion (AC2 „löschen") — the store will be added unlinked even though a
  /// chain matched the typed text. Never forced: the member decides.
  void clearSuggestion() {
    if (state.status != StoresStatus.ready) {
      return;
    }
    _safeEmit(state.copyWith(chainCleared: true, clearSuggestion: true));
  }

  /// Overrides the auto-matched suggestion with an explicitly chosen [chain] from the cached
  /// reference list (AC2 „ändern") — the member picks a different chain than the one matched from the
  /// typed text. Still advisory and still clearable; further typing re-matches.
  void selectChain(StoreChain chain) {
    if (state.status != StoresStatus.ready) {
      return;
    }
    _safeEmit(state.copyWith(chainSuggestion: chain, chainCleared: false));
  }

  /// Adds a store by free-form [name] with the accepted chain (or unlinked). On success it
  /// optimistically appends the store (read-your-writes — the id is client-minted, so no projection
  /// wait) and resets the add field's suggestion state; a rejection (e.g. a duplicate name) surfaces
  /// as an inline `actionError` without tearing down the screen.
  Future<void> addStore(String name) async {
    if (state.status != StoresStatus.ready) {
      return;
    }
    final trimmedName = name.trim();
    if (trimmedName.isEmpty) {
      // Nothing to add — the Add button is also disabled while the field is blank; guard here too so
      // a programmatic call never sends an empty name for the server to reject on a round-trip.
      return;
    }
    final chainId = state.effectiveChainId;
    _addIntent.beginAttempt(trimmedName);
    final commandId = _addIntent.commandId;
    final storeId = _addIntent.resourceId();
    _safeEmit(state.copyWith(isSubmitting: true, clearActionError: true));
    try {
      await storesApi.addStore(
        householdId,
        trimmedName,
        storeId: storeId,
        chainId: chainId,
        commandId: commandId,
      );
      final added = StoreSummary(storeId: storeId, name: trimmedName, chainId: chainId);
      _safeEmit(state.copyWith(
        stores: [...state.stores, added],
        isSubmitting: false,
        chainCleared: false,
        clearSuggestion: true,
      ));
      // A successful add completes this intent — the next add is a new intent and never reuses a
      // command id the server has already applied (which it would silently drop).
      _addIntent.complete();
    } on Object catch (error) {
      _safeEmit(state.copyWith(isSubmitting: false, actionError: _toAppError(error)));
    }
  }

  /// Archives [storeId] (AC3) and removes it from the active list on success. A failure surfaces as
  /// an inline `actionError`.
  Future<void> archiveStore(String storeId) async {
    if (state.status != StoresStatus.ready) {
      return;
    }
    _archiveIntent.beginAttempt(storeId);
    try {
      await storesApi.archiveStore(householdId, storeId, commandId: _archiveIntent.commandId);
      _safeEmit(state.copyWith(
        stores: state.stores.where((store) => store.storeId != storeId).toList(),
        clearActionError: true,
      ));
      _archiveIntent.complete();
    } on Object catch (error) {
      _safeEmit(state.copyWith(actionError: _toAppError(error)));
    }
  }

  AppError _toAppError(Object error) {
    if (error is AppException) {
      return error.error;
    }
    return AppError(code: 'stores.unknown', message: error.toString());
  }

  void _safeEmit(StoresState state) {
    if (!isClosed) {
      emit(state);
    }
  }
}
