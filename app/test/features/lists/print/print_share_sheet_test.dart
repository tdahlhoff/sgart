import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/lists/data/item.dart';
import 'package:sgart/features/lists/print/print_share_sheet.dart';
import 'package:sgart/features/stores/data/store_summary.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_print_dependencies.dart';
import '../../../support/fake_stores_dependencies.dart';
import '../../../support/widget_test_harness.dart';

/// Widget tests for the print/share bottom sheet (Story 3.5, AC1, AC2, AC3, AC5, UX-DR19): both
/// options render with the reassurance banner and carry Semantics button labels, tapping either
/// invokes the injected [FakeListPrintService] with in-memory bytes (never a path — AC3), and a
/// genuine plugin failure surfaces a brief generic error. Fakes only, no real plugin/network
/// (CLAUDE.md §6).
void main() {
  group('showPrintShareSheet', () {
    late FakeStoresApi storesApi;
    late FakeStoreChainReferenceCache referenceCache;
    late FakeListPrintService printService;

    const milk = Item(itemId: 'i1', name: 'Milch', note: null, amount: '1', unit: 'PIECE', storeId: 's1');
    const store = StoreSummary(storeId: 's1', name: 'Edeka Schiedemann', chainId: 'chain-edeka');

    setUp(() {
      storesApi = FakeStoresApi();
      referenceCache = FakeStoreChainReferenceCache();
      printService = FakeListPrintService();
    });

    Widget buildSubject({List<Item> items = const [], List<StoreSummary> stores = const []}) => wrapForTesting(
          Builder(
            builder: (context) => Scaffold(
              body: ElevatedButton(
                onPressed: () => showPrintShareSheet(
                  context,
                  title: 'Wocheneinkauf',
                  items: items,
                  stores: stores,
                  storesApi: storesApi,
                  referenceCache: referenceCache,
                  service: printService,
                ),
                child: const Text('open'),
              ),
            ),
          ),
        );

    Future<void> openSheet(WidgetTester tester, {List<Item> items = const [], List<StoreSummary> stores = const []}) async {
      await tester.pumpWidget(buildSubject(items: items, stores: stores));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();
    }

    testWidgets('showsBothOptionsAndTheReassuranceBanner', (tester) async {
      await openSheet(tester, items: [milk], stores: [store]);

      expect(find.byKey(const Key('print-share-print-option')), findsOneWidget);
      expect(find.byKey(const Key('print-share-share-option')), findsOneWidget);
      expect(find.byKey(const Key('print-share-no-file-saved')), findsOneWidget);
      expect(find.text('Drucken'), findsOneWidget);
      expect(find.text('Nach Geschäft gruppiert'), findsOneWidget);
      expect(find.text('Als PDF teilen'), findsOneWidget);
      expect(find.text('z. B. an WhatsApp senden'), findsOneWidget);
      expect(find.text('Es wird keine Datei dauerhaft gespeichert.'), findsOneWidget);
    });

    testWidgets('optionsCarrySemanticsButtonLabels', (tester) async {
      final handle = tester.ensureSemantics();
      await openSheet(tester, items: [milk], stores: [store]);

      expect(
        tester.getSemantics(find.byKey(const Key('print-share-print-option'))),
        matchesSemantics(isButton: true, label: 'Drucken'),
      );
      expect(
        tester.getSemantics(find.byKey(const Key('print-share-share-option'))),
        matchesSemantics(isButton: true, label: 'Als PDF teilen'),
      );
      handle.dispose();
    });

    testWidgets('tappingPrintInvokesPrintDocumentWithBytes', (tester) async {
      await openSheet(tester, items: [milk], stores: [store]);

      await tester.tap(find.byKey(const Key('print-share-print-option')));
      await tester.pumpAndSettle();

      expect(printService.printCallCount, 1);
      expect(printService.lastPrintedBytes, isNotNull);
      expect(printService.lastPrintedBytes, isNotEmpty);
      expect(printService.shareCallCount, 0);
    });

    testWidgets('tappingShareInvokesShareDocumentWithBytesAndAFilename', (tester) async {
      await openSheet(tester, items: [milk], stores: [store]);

      await tester.tap(find.byKey(const Key('print-share-share-option')));
      await tester.pumpAndSettle();

      expect(printService.shareCallCount, 1);
      expect(printService.lastSharedBytes, isNotNull);
      expect(printService.lastSharedBytes, isNotEmpty);
      expect(printService.lastSharedFilename, 'Wocheneinkauf.pdf');
      expect(printService.printCallCount, 0);
    });

    testWidgets('aGenuinePrintFailureSurfacesAGenericSnackBar', (tester) async {
      printService.printError = const AppException(AppError(code: 'printing.unknown', message: 'debug'));
      await openSheet(tester, items: [milk], stores: [store]);

      await tester.tap(find.byKey(const Key('print-share-print-option')));
      await tester.pumpAndSettle();

      expect(find.byType(SnackBar), findsOneWidget);
      expect(find.text('Es ist ein Fehler aufgetreten. Bitte versuche es erneut.'), findsOneWidget);
    });

    testWidgets('degradesToNoChainLabelWhenTheReferenceLoadFails', (tester) async {
      referenceCache.errorToThrow = const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      await openSheet(tester, items: [milk], stores: [store]);

      expect(find.byKey(const Key('print-share-sheet')), findsOneWidget);
    });
  });
}
