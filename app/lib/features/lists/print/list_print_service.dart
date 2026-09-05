import 'dart:typed_data';

import 'package:printing/printing.dart';

/// A thin injectable port over the `printing` plugin (Story 3.5, AC1, AC2, AC3) — the **only** file
/// in this codebase that imports `package:printing`. [print_share_sheet.dart] and its tests depend
/// on this abstraction instead, so a fake can assert "invoked with bytes, never a path" (isolate the
/// external system at the boundary, CLAUDE.md §6).
abstract interface class ListPrintService {
  const ListPrintService();

  /// Opens the native OS print dialog for [bytes] (AC1). Never writes [bytes] to disk itself —
  /// `Printing.layoutPdf` hands the platform the in-memory document directly.
  Future<void> printDocument(Uint8List bytes);

  /// Offers [bytes] to the OS share sheet (AC2) under the given [filename] hint — a share target
  /// only, never a save path (AC3). Never writes [bytes] to app-owned persistent storage itself.
  Future<void> shareDocument(Uint8List bytes, {required String filename});
}

/// [ListPrintService] backed by the real `printing` plugin.
class PluginListPrintService implements ListPrintService {
  const PluginListPrintService();

  @override
  Future<void> printDocument(Uint8List bytes) async {
    await Printing.layoutPdf(onLayout: (_) => bytes);
  }

  @override
  Future<void> shareDocument(Uint8List bytes, {required String filename}) async {
    await Printing.sharePdf(bytes: bytes, filename: filename);
  }
}
