import 'package:collection/collection.dart';

import '../../../shared/errors/app_error.dart';
import '../data/store_chain.dart';
import '../data/store_summary.dart';

enum StoresStatus { loading, ready, failure }

/// State of [StoresCubit] (Story 1.8). [loading]/[failure] cover the initial load of the active
/// store list; once [ready] it carries the active `stores`, the cached reference `chains`, the live
/// advisory `chainSuggestion` for the current add-field input (and whether the user `chainCleared`
/// it), the `isSubmitting` flag for an in-flight add, and `actionError` for an add/archive rejection
/// shown inline (e.g. a duplicate name) — kept separate from `loadError` so a rejected add never
/// tears down the screen.
class StoresState {
  const StoresState._(
    this.status, {
    this.stores = const [],
    this.chains = const [],
    this.chainSuggestion,
    this.chainCleared = false,
    this.isSubmitting = false,
    this.loadError,
    this.actionError,
  });

  const StoresState.loading() : this._(StoresStatus.loading);

  const StoresState.failure(AppError error) : this._(StoresStatus.failure, loadError: error);

  const StoresState.ready({
    required List<StoreSummary> stores,
    required List<StoreChain> chains,
    StoreChain? chainSuggestion,
    bool chainCleared = false,
    bool isSubmitting = false,
    AppError? actionError,
  }) : this._(
          StoresStatus.ready,
          stores: stores,
          chains: chains,
          chainSuggestion: chainSuggestion,
          chainCleared: chainCleared,
          isSubmitting: isSubmitting,
          actionError: actionError,
        );

  final StoresStatus status;
  final List<StoreSummary> stores;
  final List<StoreChain> chains;
  final StoreChain? chainSuggestion;
  final bool chainCleared;
  final bool isSubmitting;
  final AppError? loadError;
  final AppError? actionError;

  /// The chain id to persist for the current input: the accepted suggestion, or `null` when the
  /// user cleared it or nothing matched (an unlinked store, AC2).
  String? get effectiveChainId => chainCleared ? null : chainSuggestion?.chainId;

  StoresState copyWith({
    List<StoreSummary>? stores,
    List<StoreChain>? chains,
    StoreChain? chainSuggestion,
    bool clearSuggestion = false,
    bool? chainCleared,
    bool? isSubmitting,
    AppError? actionError,
    bool clearActionError = false,
  }) {
    return StoresState.ready(
      stores: stores ?? this.stores,
      chains: chains ?? this.chains,
      chainSuggestion: clearSuggestion ? null : (chainSuggestion ?? this.chainSuggestion),
      chainCleared: chainCleared ?? this.chainCleared,
      isSubmitting: isSubmitting ?? this.isSubmitting,
      actionError: clearActionError ? null : (actionError ?? this.actionError),
    );
  }

  @override
  bool operator ==(Object other) =>
      other is StoresState &&
      other.status == status &&
      const ListEquality<StoreSummary>().equals(other.stores, stores) &&
      const ListEquality<StoreChain>().equals(other.chains, chains) &&
      other.chainSuggestion == chainSuggestion &&
      other.chainCleared == chainCleared &&
      other.isSubmitting == isSubmitting &&
      other.loadError == loadError &&
      other.actionError == actionError;

  @override
  int get hashCode => Object.hash(
        status,
        const ListEquality<StoreSummary>().hash(stores),
        const ListEquality<StoreChain>().hash(chains),
        chainSuggestion,
        chainCleared,
        isSubmitting,
        loadError,
        actionError,
      );
}
