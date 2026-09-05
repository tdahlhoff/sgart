import 'dart:typed_data';

import 'package:sgart/features/lists/print/list_print_service.dart';

/// Test double for [ListPrintService] — no real `printing` plugin call in tests (CLAUDE.md §6).
/// Records exactly what it was handed: only ever [Uint8List] bytes (plus a filename hint on share),
/// so a test can assert the AC3 "bytes, never a path" contract structurally — there is no path
/// parameter on this interface to record in the first place.
class FakeListPrintService implements ListPrintService {
  Object? printError;
  Object? shareError;

  int printCallCount = 0;
  Uint8List? lastPrintedBytes;

  int shareCallCount = 0;
  Uint8List? lastSharedBytes;
  String? lastSharedFilename;

  @override
  Future<void> printDocument(Uint8List bytes) async {
    printCallCount++;
    lastPrintedBytes = bytes;
    if (printError != null) throw printError!;
  }

  @override
  Future<void> shareDocument(Uint8List bytes, {required String filename}) async {
    shareCallCount++;
    lastSharedBytes = bytes;
    lastSharedFilename = filename;
    if (shareError != null) throw shareError!;
  }
}
