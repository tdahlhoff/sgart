import 'package:collection/collection.dart';

import '../../../shared/errors/app_error.dart';
import '../../lists/data/item.dart';
import '../../stores/data/store_summary.dart';

enum TripStatus { loading, ready, failure }

/// One trip-store's group of items (Story 3.2, AC1) — the store id (order comes from its position
/// in [TripState.storeIds]) plus the items currently routed to it. Empty groups still render (a
/// visible reroute target, Cl. 5).
class TripStoreGroup {
  const TripStoreGroup({required this.storeId, required this.items});

  final String storeId;
  final List<Item> items;

  @override
  bool operator ==(Object other) =>
      other is TripStoreGroup && other.storeId == storeId && const ListEquality<Item>().equals(other.items, items);

  @override
  int get hashCode => Object.hash(storeId, const ListEquality<Item>().hash(items));
}

/// State of [TripCubit] (Story 3.2, AC1, AC2, AC3, AC5, Cl. 2/5/7/9). [loading]/[failure] cover the
/// initial load of the trip's grouped view; once [ready] it carries `tripId`/`listId`, `storeIds`
/// (the trip's stores in add order — the client's grouping key, Cl. 7), the flat `items` the server
/// returned, `stores` (the household's active stores, for name resolution and the Cl. 7 archived/
/// non-trip fallback), `isSubmitting` for an in-flight reroute/add-store, and `actionError` for a
/// rejection shown inline. [groups]/[unassignedItems] derive the actual grouped view — computed,
/// never stored, so there is exactly one source of truth for the bucketing rule.
class TripState {
  const TripState._(
    this.status, {
    this.tripId = '',
    this.listId = '',
    this.storeIds = const [],
    this.items = const [],
    this.stores = const [],
    this.isSubmitting = false,
    this.loadError,
    this.actionError,
  });

  const TripState.loading() : this._(TripStatus.loading);

  const TripState.failure(AppError error) : this._(TripStatus.failure, loadError: error);

  const TripState.ready({
    required String tripId,
    required String listId,
    required List<String> storeIds,
    required List<Item> items,
    List<StoreSummary> stores = const [],
    bool isSubmitting = false,
    AppError? actionError,
  }) : this._(
          TripStatus.ready,
          tripId: tripId,
          listId: listId,
          storeIds: storeIds,
          items: items,
          stores: stores,
          isSubmitting: isSubmitting,
          actionError: actionError,
        );

  final TripStatus status;
  final String tripId;
  final String listId;
  final List<String> storeIds;
  final List<Item> items;
  final List<StoreSummary> stores;
  final bool isSubmitting;
  final AppError? loadError;
  final AppError? actionError;

  /// One group per trip store, in add order (Cl. 5 — an empty group still renders). An item groups
  /// under `tripStoreId` iff its `storeId` is that trip store **and** that store resolves in the
  /// household's active stores (Cl. 7) — otherwise it falls to [unassignedItems].
  List<TripStoreGroup> get groups => storeIds
      .map((storeId) => TripStoreGroup(
            storeId: storeId,
            items: items.where((item) => item.storeId == storeId && _isActiveStore(storeId)).toList(),
          ))
      .toList();

  /// Items with no assignment, an assignment to a store not in this trip, or an assignment to an
  /// archived/absent store (Cl. 7, the 1.8 E6 fallback) — the „Noch nicht zugeordnet" section.
  List<Item> get unassignedItems =>
      items.where((item) => item.storeId == null || !storeIds.contains(item.storeId) || !_isActiveStore(item.storeId!)).toList();

  bool _isActiveStore(String storeId) => stores.any((store) => store.storeId == storeId);

  /// Resolves a trip store id to its household [StoreSummary] (name/chain) — `null` when the store
  /// no longer resolves in the active list (Cl. 7).
  StoreSummary? storeFor(String storeId) => stores.firstWhereOrNull((store) => store.storeId == storeId);

  /// Number of items with `status == 'DONE'` (Story 3.3, AC5 progress bar).
  int get doneCount => items.where((item) => item.status == 'DONE').length;

  /// Total number of items regardless of status — POSTPONED items count in the total (Cl. 7).
  int get totalCount => items.length;

  TripState copyWith({
    List<String>? storeIds,
    List<Item>? items,
    List<StoreSummary>? stores,
    bool? isSubmitting,
    AppError? actionError,
    bool clearActionError = false,
  }) {
    return TripState.ready(
      tripId: tripId,
      listId: listId,
      storeIds: storeIds ?? this.storeIds,
      items: items ?? this.items,
      stores: stores ?? this.stores,
      isSubmitting: isSubmitting ?? this.isSubmitting,
      actionError: clearActionError ? null : (actionError ?? this.actionError),
    );
  }

  @override
  bool operator ==(Object other) =>
      other is TripState &&
      other.status == status &&
      other.tripId == tripId &&
      other.listId == listId &&
      const ListEquality<String>().equals(other.storeIds, storeIds) &&
      const ListEquality<Item>().equals(other.items, items) &&
      const ListEquality<StoreSummary>().equals(other.stores, stores) &&
      other.isSubmitting == isSubmitting &&
      other.loadError == loadError &&
      other.actionError == actionError;

  @override
  int get hashCode => Object.hash(
        status,
        tripId,
        listId,
        const ListEquality<String>().hash(storeIds),
        const ListEquality<Item>().hash(items),
        const ListEquality<StoreSummary>().hash(stores),
        isSubmitting,
        loadError,
        actionError,
      );
}
