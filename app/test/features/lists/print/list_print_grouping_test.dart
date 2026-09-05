import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/lists/data/item.dart';
import 'package:sgart/features/lists/print/list_print_grouping.dart';
import 'package:sgart/features/stores/data/store_summary.dart';

void main() {
  group('ListPrintGrouping.from', () {
    const storeA = StoreSummary(storeId: 'store-a', name: 'Edeka Schiedemann', chainId: 'chain-edeka');
    const storeB = StoreSummary(storeId: 'store-b', name: 'Netto');

    const milk = Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 'store-a');
    const bread = Item(itemId: 'i2', name: 'Brot', note: null, amount: '1', unit: 'PIECE', storeId: 'store-a');
    const coffee = Item(itemId: 'i3', name: 'Kaffee', note: null, amount: '1', unit: 'PACK', storeId: 'store-b');
    const batteries = Item(itemId: 'i4', name: 'Batterien', note: null, amount: '4', unit: 'PIECE');
    const archived = Item(itemId: 'i5', name: 'Alt', note: null, amount: '1', unit: 'PIECE', storeId: 'store-archived');

    test('from_groupsItemsUnderTheirAssignedActiveStoreInStoresOrder', () {
      final grouping = ListPrintGrouping.from(
        items: [milk, bread, coffee],
        stores: [storeA, storeB],
      );

      expect(grouping.groups, [
        const PrintStoreGroup(store: storeA, items: [milk, bread]),
        const PrintStoreGroup(store: storeB, items: [coffee]),
      ]);
      expect(grouping.unassignedItems, isEmpty);
    });

    test('from_fallsBackToUnassignedForAnArchivedOrAbsentStore', () {
      final grouping = ListPrintGrouping.from(
        items: [milk, batteries, archived],
        stores: [storeA],
      );

      expect(grouping.groups, [
        const PrintStoreGroup(store: storeA, items: [milk]),
      ]);
      expect(grouping.unassignedItems, [batteries, archived]);
    });

    test('from_returnsNoGroupsForAnEmptyList', () {
      final grouping = ListPrintGrouping.from(items: const [], stores: [storeA, storeB]);

      expect(grouping.groups, isEmpty);
      expect(grouping.unassignedItems, isEmpty);
    });

    test('from_returnsOnlyTheUnassignedGroupWhenNoItemResolvesAStore', () {
      final grouping = ListPrintGrouping.from(items: [batteries, archived], stores: [storeA, storeB]);

      expect(grouping.groups, isEmpty);
      expect(grouping.unassignedItems, [batteries, archived]);
    });

    test('from_omitsAnActiveStoreThatHasNoItemsOnThisList', () {
      final grouping = ListPrintGrouping.from(items: [milk], stores: [storeA, storeB]);

      expect(grouping.groups, [
        const PrintStoreGroup(store: storeA, items: [milk]),
      ]);
    });

    test('from_preservesListCreationOrderWithinAGroup', () {
      final grouping = ListPrintGrouping.from(items: [bread, milk], stores: [storeA]);

      expect(grouping.groups.single.items, [bread, milk]);
    });
  });
}
