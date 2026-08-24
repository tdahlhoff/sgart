import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:uuid/uuid.dart';

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

  /// The ids for the current add intent — a *specific target name*. Both the command id and the
  /// store id are reused across retries of the same name so a resubmit after a transient failure is
  /// idempotent (AD-8) and the optimistically-rendered store id matches the one the server persisted;
  /// both are regenerated when the name changes (a new intent) and after a successful add (the next
  /// add is a fresh intent — a spent command id would be deduped server-side as a silent no-op,
  /// silently dropping the store). Pattern from `RenameHouseholdCubit`, extended to carry the id too.
  String _commandId = const Uuid().v4();
  String _storeId = const Uuid().v4();
  String? _intentName;

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
    _beginIntent(trimmedName);
    final commandId = _commandId;
    final storeId = _storeId;
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
      // A successful add completes this intent — mint fresh ids so the next add is a new intent and
      // never reuses a command id the server has already applied (which it would silently drop).
      _commandId = const Uuid().v4();
      _storeId = const Uuid().v4();
      _intentName = null;
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
    try {
      await storesApi.archiveStore(householdId, storeId, commandId: const Uuid().v4());
      _safeEmit(state.copyWith(
        stores: state.stores.where((store) => store.storeId != storeId).toList(),
        clearActionError: true,
      ));
    } on Object catch (error) {
      _safeEmit(state.copyWith(actionError: _toAppError(error)));
    }
  }

  /// Aligns the per-intent ids (`_commandId` + `_storeId`) with [trimmedName]: keeps them for the
  /// first attempt or a retry of the *same* name (an idempotent retry — the server dedupes on the
  /// reused command id and the reused store id matches what it persisted), and regenerates both when
  /// the name has changed, since an edited retry is a new intent.
  void _beginIntent(String trimmedName) {
    if (_intentName == null || _intentName == trimmedName) {
      _intentName = trimmedName;
      return;
    }
    _commandId = const Uuid().v4();
    _storeId = const Uuid().v4();
    _intentName = trimmedName;
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
