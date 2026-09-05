import 'package:collection/collection.dart';

import '../../stores/data/store_summary.dart';
import '../data/item.dart';

/// One store's items in the grouped-by-store print/share layout (Story 3.5, AC1, AC4) — the store
/// (name + chain resolution happens in the document builder) plus the items assigned to it, in list
/// creation order.
class PrintStoreGroup {
  const PrintStoreGroup({required this.store, required this.items});

  final StoreSummary store;
  final List<Item> items;

  @override
  bool operator ==(Object other) =>
      other is PrintStoreGroup && other.store == store && const ListEquality<Item>().equals(other.items, items);

  @override
  int get hashCode => Object.hash(store, const ListEquality<Item>().hash(items));
}

/// The grouped-by-store projection of a list's items for print/share (Story 3.5, AC1, AC4) — one
/// [groups] entry per active store that has items (in `stores` order), then the trailing
/// [unassignedItems] (no assignment, or assigned to a store not in the household's active stores —
/// archived/absent). Mirrors the exact resolution rule `TripState.groups`/`unassignedItems` and
/// `ListDetailCubit.storeFor` express for the trip screen (DRY — one store-resolution rule, applied
/// here over the list's active stores instead of a trip's subset). A household store with no items
/// on this list is not included — an empty section would print nothing useful (AC4).
class ListPrintGrouping {
  const ListPrintGrouping({required this.groups, required this.unassignedItems});

  final List<PrintStoreGroup> groups;
  final List<Item> unassignedItems;

  /// Builds the grouping from the list's [items] (creation order preserved throughout) and the
  /// household's active [stores]. Pure — no I/O, no chain-name resolution (the document builder
  /// resolves chain names from a separately-loaded reference list, keeping this function
  /// Flutter-free and unit-testable without a fake reference cache).
  factory ListPrintGrouping.from({required List<Item> items, required List<StoreSummary> stores}) {
    final groups = <PrintStoreGroup>[];
    for (final store in stores) {
      final storeItems = items.where((item) => item.storeId == store.storeId).toList();
      if (storeItems.isNotEmpty) {
        groups.add(PrintStoreGroup(store: store, items: storeItems));
      }
    }
    final activeStoreIds = stores.map((store) => store.storeId).toSet();
    final unassignedItems =
        items.where((item) => item.storeId == null || !activeStoreIds.contains(item.storeId)).toList();
    return ListPrintGrouping(groups: groups, unassignedItems: unassignedItems);
  }

  @override
  bool operator ==(Object other) =>
      other is ListPrintGrouping &&
      const ListEquality<PrintStoreGroup>().equals(other.groups, groups) &&
      const ListEquality<Item>().equals(other.unassignedItems, unassignedItems);

  @override
  int get hashCode =>
      Object.hash(const ListEquality<PrintStoreGroup>().hash(groups), const ListEquality<Item>().hash(unassignedItems));
}
