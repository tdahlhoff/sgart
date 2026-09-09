import 'dart:async';
import 'dart:convert';
import 'dart:math';

import '../http/authenticated_http_client.dart';
import 'household_change_nudge.dart';
import 'live_sync_status.dart';
import 'sse_frame_parser.dart';

/// Thrown by a [HouseholdStreamConnector] when the backend rejects the connection outright (the
/// HTTP request completed with a 4xx/5xx before any bytes streamed) — carries the status code so
/// the caller can tell the terminal `403` (AC3, the member was removed) from every other status —
/// `401`/`408`/`429`/5xx included — which is transient and worth retrying (Review P1).
class HouseholdStreamRejected implements Exception {
  const HouseholdStreamRejected(this.statusCode);

  final int statusCode;

  @override
  String toString() => 'HouseholdStreamRejected($statusCode)';
}

/// Opens one attempt at the raw SSE byte stream, or throws (a [HouseholdStreamRejected] for an
/// HTTP-level rejection, anything else for a network-level failure). Kept as an injectable seam
/// (CLAUDE.md §6, "isolate external systems") so [HouseholdEventStream]'s status/backoff/parsing
/// logic is unit-testable without a real Dio/HTTP round trip.
typedef HouseholdStreamConnector = Future<Stream<List<int>>> Function();

/// The per-household live-sync SSE client (Story 4.4, T7/T8): connects, auto-reconnects with
/// capped exponential backoff + jitter (AC2), and exposes a [statusStream] plus a [changes] stream
/// of content-free nudges (LD-1). Only a `403` is treated as terminal (AC3) — the removed member's
/// client never reconnect-loops a revoked stream, it surfaces [LiveSyncStatus.revoked] instead so
/// the caller can drop the household from view (mirrors Story 4.3's cascade). Every other rejection
/// (`401`/`408`/`429`/5xx) is transient and reconnects (Review P1) — a `401` in particular can be a
/// momentarily stale access token, never a removed-member signal.
class HouseholdEventStream {
  // Not `this._connect` (initializing formal): that would make the public constructor's named
  // parameter itself private (`_connect:`), unusable from any call site outside this file —
  // including the tests exercising this seam directly (CLAUDE.md §6).
  HouseholdEventStream({
    required HouseholdStreamConnector connect,
    Random? random,
    Duration Function(int attempt)? backoff,
  })  : _connect = connect, // ignore: prefer_initializing_formals
        _random = random ?? Random(),
        _backoff = backoff ?? _defaultBackoff;

  /// Builds the real client over the backend's SSE endpoint via [AuthenticatedHttpClient] — the
  /// bearer interceptor supplies the token exactly as every other request (never in path/query).
  factory HouseholdEventStream.forHousehold({
    required AuthenticatedHttpClient httpClient,
    required String householdId,
  }) {
    return HouseholdEventStream(
      connect: () async {
        final response = await httpClient.openEventStream('/api/v1/households/$householdId/stream');
        final statusCode = response.statusCode;
        if (statusCode != null && statusCode >= 400) {
          throw HouseholdStreamRejected(statusCode);
        }
        return response.data!.stream;
      },
    );
  }

  static Duration _defaultBackoff(int attempt) {
    final cappedAttempt = min(attempt, 6);
    final exponentialMs = 500 * pow(2, cappedAttempt).toInt();
    return Duration(milliseconds: min(exponentialMs, 30000));
  }

  final HouseholdStreamConnector _connect;
  final Random _random;
  final Duration Function(int attempt) _backoff;

  final StreamController<LiveSyncStatus> _statusController = StreamController.broadcast();
  final StreamController<HouseholdChangeNudge> _changeController = StreamController.broadcast();

  LiveSyncStatus _status = LiveSyncStatus.offline;
  bool _stopped = true;
  bool _disposed = false;
  int _attempt = 0;
  StreamSubscription<List<int>>? _subscription;
  Timer? _reconnectTimer;

  LiveSyncStatus get status => _status;
  Stream<LiveSyncStatus> get statusStream => _statusController.stream;
  Stream<HouseholdChangeNudge> get changes => _changeController.stream;

  /// Starts (or restarts, after [stop]) the connection. A no-op while already running.
  void start() {
    if (!_stopped) {
      return;
    }
    _stopped = false;
    _attempt = 0;
    unawaited(_connectOnce());
  }

  /// Stops deliberately (household switch, sign-out) — cancels any in-flight connection/backoff
  /// timer and settles on [LiveSyncStatus.offline], never `reconnecting`.
  Future<void> stop() async {
    _stopped = true;
    _reconnectTimer?.cancel();
    _reconnectTimer = null;
    await _subscription?.cancel();
    _subscription = null;
    _setStatus(LiveSyncStatus.offline);
  }

  /// Releases the underlying streams — call once the owner (the household shell) is disposed.
  /// Idempotent: a shell rebuild/teardown racing a caller that also holds a reference (e.g. a
  /// test double shared between the widget and its `tearDown`) must not double-close the
  /// broadcast controllers, which would otherwise hang `close()` or throw.
  Future<void> dispose() async {
    if (_disposed) {
      return;
    }
    _disposed = true;
    await stop();
    await _statusController.close();
    await _changeController.close();
  }

  Future<void> _connectOnce() async {
    if (_stopped) {
      return;
    }
    _setStatus(_attempt == 0 ? LiveSyncStatus.connecting : LiveSyncStatus.reconnecting);
    try {
      final byteStream = await _connect();
      if (_stopped) {
        // A stop()/dispose() raced this connect (household switch, sign-out): never leave the
        // just-opened socket dangling — cancelling an unlistened subscription drains/closes it.
        await byteStream.listen(null).cancel();
        return;
      }
      _attempt = 0;
      _setStatus(LiveSyncStatus.live);
      final parser = SseFrameParser(onEvent: _handleFrame);
      _subscription = byteStream.listen(
        parser.addBytes,
        onDone: _handleStreamEnded,
        onError: (Object _, StackTrace _) => _handleStreamEnded(),
        cancelOnError: true,
      );
    } on HouseholdStreamRejected catch (rejection) {
      if (_stopped) {
        return;
      }
      if (rejection.statusCode == 403) {
        // Terminal (AC3): never reconnect-loop a revoked stream. Every other status —
        // 401/408/429/5xx included — is transient and reconnects (Review P1).
        _setStatus(LiveSyncStatus.revoked);
      } else {
        _scheduleReconnect();
      }
    } on Object {
      if (!_stopped) {
        _scheduleReconnect();
      }
    }
  }

  void _handleFrame(String eventName, String data) {
    if (eventName != 'changed') {
      return;
    }
    try {
      final decoded = jsonDecode(data);
      if (decoded is! Map<String, dynamic>) {
        return;
      }
      final householdId = decoded['householdId'];
      final resource = decoded['resource'];
      if (householdId is String && resource is String) {
        _changeController.add(HouseholdChangeNudge(householdId: householdId, resource: resource));
      }
    } on FormatException {
      // A malformed frame is not this client's problem to solve — ignore it; the next nudge (or
      // the next reconnect-reconcile, AC2) self-heals any state a missed one would have fixed.
    }
  }

  void _handleStreamEnded() {
    _subscription = null;
    if (!_stopped) {
      _scheduleReconnect();
    }
  }

  void _scheduleReconnect() {
    _setStatus(LiveSyncStatus.reconnecting);
    final delay = _backoff(_attempt) + Duration(milliseconds: _random.nextInt(500));
    _attempt++;
    _reconnectTimer = Timer(delay, () => unawaited(_connectOnce()));
  }

  void _setStatus(LiveSyncStatus status) {
    _status = status;
    if (!_statusController.isClosed) {
      _statusController.add(status);
    }
  }
}
