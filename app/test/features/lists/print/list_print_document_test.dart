import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:sgart/features/lists/data/item.dart';
import 'package:sgart/features/lists/print/list_print_document.dart';
import 'package:sgart/features/lists/print/list_print_grouping.dart';
import 'package:sgart/features/stores/data/store_chain.dart';
import 'package:sgart/features/stores/data/store_summary.dart';
import 'package:sgart/l10n/gen/app_localizations_de.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('ListPrintDocument.build', () {
    final localizations = AppLocalizationsDe();
    final now = DateTime.utc(2026, 9, 5);

    setUpAll(() async {
      await initializeDateFormatting('de_DE');
    });

    const storeA = StoreSummary(storeId: 'store-a', name: 'Edeka Schiedemann', chainId: 'chain-edeka');
    const chainReference = [StoreChain(chainId: 'chain-edeka', name: 'Edeka')];

    const milk = Item(itemId: 'i1', name: 'Milch', note: 'Bio', amount: '1', unit: 'PIECE', storeId: 'store-a');
    const batteries = Item(itemId: 'i2', name: 'Batterien', note: null, amount: '4', unit: 'PIECE');

    test('build_returnsNonEmptyBytesForARepresentativeList', () async {
      final grouping = ListPrintGrouping.from(items: [milk, batteries], stores: [storeA]);

      final bytes = await const ListPrintDocument().build(
        listTitle: 'Wocheneinkauf',
        now: now,
        grouping: grouping,
        chainReference: chainReference,
        localizations: localizations,
      );

      expect(bytes, isNotEmpty);
    });

    test('build_doesNotThrowForAnEmptyList', () async {
      final grouping = ListPrintGrouping.from(items: const [], stores: const []);

      final bytes = await const ListPrintDocument().build(
        listTitle: 'Leere Liste',
        now: now,
        grouping: grouping,
        chainReference: const [],
        localizations: localizations,
      );

      expect(bytes, isNotEmpty);
    });

    test('build_doesNotThrowWithOnlyUnassignedItems', () async {
      final grouping = ListPrintGrouping.from(items: [batteries], stores: const []);

      final bytes = await const ListPrintDocument().build(
        listTitle: 'Wocheneinkauf',
        now: now,
        grouping: grouping,
        chainReference: const [],
        localizations: localizations,
      );

      expect(bytes, isNotEmpty);
    });

    test('build_doesNotThrowForABlankListTitle', () async {
      final grouping = ListPrintGrouping.from(items: [milk], stores: [storeA]);

      final bytes = await const ListPrintDocument().build(
        listTitle: '   ',
        now: now,
        grouping: grouping,
        chainReference: chainReference,
        localizations: localizations,
      );

      expect(bytes, isNotEmpty);
    });
  });
}
