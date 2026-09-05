import 'dart:typed_data';

import 'package:collection/collection.dart';
import 'package:flutter/services.dart' show rootBundle;
import 'package:pdf/pdf.dart';
import 'package:pdf/widgets.dart' as pw;

import '../../../l10n/formatting/date_formatter.dart';
import '../../../l10n/gen/app_localizations.dart';
import '../../stores/data/store_chain.dart';
import '../data/item.dart';
import '../item_display_text.dart';
import 'list_print_grouping.dart';

/// Builds the grouped-by-store print/share PDF (Story 3.5, AC1, AC2, AC3, AC4) — the same document
/// underlies both the native print dialog and the share-sheet PDF, since both are just different
/// consumers of the same in-memory bytes. Pure with respect to the filesystem: [build] only ever
/// returns bytes from `doc.save()` and never touches `path_provider`/`File` (AC3) — the guarantee is
/// enforced by keeping this file free of those imports.
///
/// Loads the bundled Inter font itself (the only bundled PDF-capable font, already declared in
/// `pubspec.yaml`) rather than taking it as a parameter — one less thing every caller/test has to
/// wire. **Variable-font caveat:** the `pdf` package does not apply `wght` axes, so Inter always
/// renders its default instance here; visual hierarchy therefore comes from font *size*, not weight.
/// `pw.FontWeight.bold` is still set on headers (faux-bold on the same face) per the story's explicit
/// allowance, even though it renders visually identical to the normal weight.
class ListPrintDocument {
  const ListPrintDocument();

  /// [now] must be a UTC instant (mirrors [DateFormatter]'s contract) — the header date is formatted
  /// in the device's local time zone. [chainReference] is the household's cached chain reference
  /// list; a store's `chainId` resolves to its display name here (the only chain-name resolution
  /// point — [ListPrintGrouping] stays pure, per Task 2). A blank [listTitle] falls back to the
  /// generic list-name localization rather than printing an empty header (fail-fast guard, DoD).
  Future<Uint8List> build({
    required String listTitle,
    required DateTime now,
    required ListPrintGrouping grouping,
    required List<StoreChain> chainReference,
    required AppLocalizations localizations,
  }) async {
    final fontData = await rootBundle.load('assets/fonts/Inter-Variable.ttf');
    final font = pw.Font.ttf(fontData);
    final doc = pw.Document(theme: pw.ThemeData.withFont(base: font, bold: font));

    final resolvedTitle = listTitle.trim().isEmpty ? localizations.printShareFilename : listTitle.trim();
    final dateText = const DateFormatter().formatDate(now);

    doc.addPage(pw.MultiPage(
      build: (context) => [
        pw.Text(resolvedTitle, style: pw.TextStyle(fontSize: 20, fontWeight: pw.FontWeight.bold)),
        pw.SizedBox(height: 4),
        pw.Text(dateText, style: const pw.TextStyle(fontSize: 10, color: PdfColors.grey700)),
        pw.SizedBox(height: 16),
        for (final group in grouping.groups) ...[
          _groupHeader(group.store.name, _chainNameFor(group.store.chainId, chainReference)),
          for (final item in group.items) _itemRow(item, localizations),
          pw.SizedBox(height: 12),
        ],
        if (grouping.unassignedItems.isNotEmpty) ...[
          _groupHeader(localizations.tripUnassignedGroupLabel, null),
          for (final item in grouping.unassignedItems) _itemRow(item, localizations),
        ],
      ],
    ));

    return doc.save();
  }

  pw.Widget _groupHeader(String storeName, String? chainLabel) {
    return pw.Padding(
      padding: const pw.EdgeInsets.only(top: 10, bottom: 6),
      child: pw.Row(
        crossAxisAlignment: pw.CrossAxisAlignment.end,
        children: [
          pw.Text(storeName, style: pw.TextStyle(fontSize: 13, fontWeight: pw.FontWeight.bold)),
          if (chainLabel != null) ...[
            pw.SizedBox(width: 6),
            pw.Text(chainLabel, style: const pw.TextStyle(fontSize: 10, color: PdfColors.grey700)),
          ],
        ],
      ),
    );
  }

  pw.Widget _itemRow(Item item, AppLocalizations localizations) {
    final subtitle = formatItemSubtitle(item, localizations);
    return pw.Padding(
      padding: const pw.EdgeInsets.symmetric(vertical: 4),
      child: pw.Row(
        crossAxisAlignment: pw.CrossAxisAlignment.start,
        children: [
          pw.Container(
            width: 12,
            height: 12,
            margin: const pw.EdgeInsets.only(top: 2, right: 8),
            decoration: pw.BoxDecoration(border: pw.Border.all(width: 1, color: PdfColors.black)),
          ),
          pw.Expanded(
            child: pw.Column(
              crossAxisAlignment: pw.CrossAxisAlignment.start,
              children: [
                pw.Text(item.name, style: const pw.TextStyle(fontSize: 12)),
                pw.Text(subtitle, style: const pw.TextStyle(fontSize: 10, color: PdfColors.grey700)),
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// Resolves a store's chain id to its display name from the reference list (mirrors
  /// `store_picker_sheet._chainNameFor` — single source of chain names, DRY).
  String? _chainNameFor(String? chainId, List<StoreChain> chainReference) {
    if (chainId == null) {
      return null;
    }
    return chainReference.firstWhereOrNull((chain) => chain.chainId == chainId)?.name;
  }
}
