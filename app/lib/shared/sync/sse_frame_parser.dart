import 'dart:convert';

/// Parses `text/event-stream` byte chunks into `(event, data)` frames (Story 4.4, T7). Handles the
/// small, well-defined SSE line format across arbitrary chunk boundaries: `event:`/`data:` fields,
/// a blank line dispatches the accumulated frame, and a line starting with `:` is a comment
/// (the server's heartbeat) — ignored. Every other field (`id:`, `retry:`) is ignored; this server
/// never sends them (LD-1).
///
/// Decodes bytes through [Utf8Decoder.startChunkedConversion] rather than decoding each chunk
/// independently: today's payload is single-byte ASCII, but a naive independent decode would throw
/// a [FormatException] the moment a multi-byte UTF-8 character is ever split across a chunk
/// boundary — outside `addBytes`'s own try/catch, escaping synchronously into the zone instead of
/// the subscription's `onError` (Review). The chunked decoder buffers an incomplete trailing
/// sequence internally and only ever emits complete characters.
class SseFrameParser {
  SseFrameParser({required this.onEvent}) {
    _byteToStringSink = const Utf8Decoder().startChunkedConversion(_StringCallbackSink(_consume));
  }

  /// Called once per dispatched frame with the event name (defaulting to `"message"` per the SSE
  /// spec when no `event:` field was sent) and the joined `data:` lines.
  final void Function(String eventName, String data) onEvent;

  late final ByteConversionSink _byteToStringSink;

  String _buffer = '';
  String _pendingEvent = 'message';
  final List<String> _pendingDataLines = [];

  /// Feeds one chunk of raw stream bytes, dispatching every complete frame it now contains.
  void addBytes(List<int> bytes) => _byteToStringSink.add(bytes);

  void _consume(String decoded) {
    _buffer += decoded;
    var newlineIndex = _buffer.indexOf('\n');
    while (newlineIndex != -1) {
      final rawLine = _buffer.substring(0, newlineIndex);
      _buffer = _buffer.substring(newlineIndex + 1);
      _consumeLine(rawLine.endsWith('\r') ? rawLine.substring(0, rawLine.length - 1) : rawLine);
      newlineIndex = _buffer.indexOf('\n');
    }
  }

  void _consumeLine(String line) {
    if (line.isEmpty) {
      _dispatch();
      return;
    }
    if (line.startsWith(':')) {
      return;
    }
    if (line.startsWith('event:')) {
      _pendingEvent = line.substring('event:'.length).trim();
    } else if (line.startsWith('data:')) {
      _pendingDataLines.add(line.substring('data:'.length).trim());
    }
  }

  void _dispatch() {
    if (_pendingDataLines.isNotEmpty) {
      onEvent(_pendingEvent, _pendingDataLines.join('\n'));
    }
    _pendingEvent = 'message';
    _pendingDataLines.clear();
  }
}

/// Adapts a plain callback to the [Sink] interface [Utf8Decoder.startChunkedConversion] requires
/// as its output sink — this parser has no use for [close] (the byte stream ending is handled by
/// [HouseholdEventStream]'s own `onDone`, not by this parser).
class _StringCallbackSink implements Sink<String> {
  const _StringCallbackSink(this._onData);

  final void Function(String data) _onData;

  @override
  void add(String data) => _onData(data);

  @override
  void close() {}
}
