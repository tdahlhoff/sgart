import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/shared/sync/sse_frame_parser.dart';

void main() {
  group('SseFrameParser', () {
    test('dispatchesAnEventNameAndDataOnABlankLine', () {
      final events = <(String, String)>[];
      final parser = SseFrameParser(onEvent: (name, data) => events.add((name, data)));

      parser.addBytes(utf8.encode('event: changed\ndata: {"householdId":"h1","resource":"list"}\n\n'));

      expect(events, [('changed', '{"householdId":"h1","resource":"list"}')]);
    });

    test('splitsAFrameArrivingAcrossMultipleChunks', () {
      final events = <(String, String)>[];
      final parser = SseFrameParser(onEvent: (name, data) => events.add((name, data)));

      parser.addBytes(utf8.encode('event: cha'));
      parser.addBytes(utf8.encode('nged\ndata: {"householdId":"h1",'));
      parser.addBytes(utf8.encode('"resource":"trip"}\n\n'));

      expect(events, [('changed', '{"householdId":"h1","resource":"trip"}')]);
    });

    test('reassemblesAMultiByteUtf8CharacterSplitAcrossAChunkBoundary', () {
      // Regression: decoding each chunk as independent UTF-8 throws a FormatException the moment a
      // multi-byte character (e.g. "ü", 2 bytes: 0xC3 0xBC) is split mid-sequence across chunks.
      final events = <(String, String)>[];
      final parser = SseFrameParser(onEvent: (name, data) => events.add((name, data)));
      final bytes = utf8.encode('data: Fü\n\n');

      parser.addBytes(bytes.sublist(0, 8)); // ends after ü's first byte, mid-2-byte-sequence
      parser.addBytes(bytes.sublist(8));

      expect(events, [('message', 'Fü')]);
    });

    test('ignoresACommentLineTheHeartbeat', () {
      final events = <(String, String)>[];
      final parser = SseFrameParser(onEvent: (name, data) => events.add((name, data)));

      parser.addBytes(utf8.encode(':\n\n'));

      expect(events, isEmpty);
    });

    test('defaultsTheEventNameToMessageWhenNoEventFieldWasSent', () {
      final events = <(String, String)>[];
      final parser = SseFrameParser(onEvent: (name, data) => events.add((name, data)));

      parser.addBytes(utf8.encode('data: hello\n\n'));

      expect(events, [('message', 'hello')]);
    });

    test('joinsMultipleDataLinesWithNewlines', () {
      final events = <(String, String)>[];
      final parser = SseFrameParser(onEvent: (name, data) => events.add((name, data)));

      parser.addBytes(utf8.encode('data: line1\ndata: line2\n\n'));

      expect(events, [('message', 'line1\nline2')]);
    });

    test('dispatchesMultipleFramesInOneChunk', () {
      final events = <(String, String)>[];
      final parser = SseFrameParser(onEvent: (name, data) => events.add((name, data)));

      parser.addBytes(utf8.encode(
          'event: changed\ndata: {"householdId":"h1","resource":"list"}\n\n'
          'event: changed\ndata: {"householdId":"h1","resource":"trip"}\n\n'));

      expect(events, [
        ('changed', '{"householdId":"h1","resource":"list"}'),
        ('changed', '{"householdId":"h1","resource":"trip"}'),
      ]);
    });
  });
}
