import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/shared/sync/household_change_nudge.dart';
import 'package:sgart/shared/sync/household_event_stream.dart';
import 'package:sgart/shared/sync/live_sync_status.dart';

/// A deterministic `Random` double: every jitter draw is `0`, so reconnect-timing assertions never
/// race against the real jitter window (CLAUDE.md §6, "isolate external systems" — randomness is
/// one of the named boundaries).
class _ZeroRandom implements Random {
  @override
  int nextInt(int max) => 0;

  @override
  double nextDouble() => 0;

  @override
  bool nextBool() => false;
}

void main() {
  group('HouseholdEventStream', () {
    test('start_connectsAndTransitionsToLiveThenDeliversAParsedNudge', () async {
      final controller = StreamController<List<int>>();
      var connectCount = 0;
      final stream = HouseholdEventStream(
        connect: () async {
          connectCount++;
          return controller.stream;
        },
        backoff: (_) => Duration.zero,
        random: _ZeroRandom(),
      );
      final statuses = <LiveSyncStatus>[];
      final nudges = <HouseholdChangeNudge>[];
      stream.statusStream.listen(statuses.add);
      stream.changes.listen(nudges.add);

      stream.start();
      await pumpEventQueue();

      expect(connectCount, 1);
      expect(statuses, [LiveSyncStatus.connecting, LiveSyncStatus.live]);

      controller.add(utf8.encode('event: changed\ndata: {"householdId":"h1","resource":"list"}\n\n'));
      await pumpEventQueue();

      expect(nudges, [const HouseholdChangeNudge(householdId: 'h1', resource: 'list')]);

      await controller.close();
      await stream.dispose();
    });

    test('a403RejectionSurfacesRevokedAndNeverReconnects', () async {
      var connectCount = 0;
      final stream = HouseholdEventStream(
        connect: () async {
          connectCount++;
          throw const HouseholdStreamRejected(403);
        },
        backoff: (_) => Duration.zero,
        random: _ZeroRandom(),
      );
      final statuses = <LiveSyncStatus>[];
      stream.statusStream.listen(statuses.add);

      stream.start();
      await pumpEventQueue();
      // Give any (incorrect) reconnect timer a chance to fire before asserting it didn't.
      await Future<void>.delayed(const Duration(milliseconds: 50));

      expect(connectCount, 1);
      expect(statuses.last, LiveSyncStatus.revoked);

      await stream.dispose();
    });

    test('a401RejectionSchedulesAReconnectInsteadOfRevoking', () async {
      // Regression (Review P1): only 403 is terminal — 401/408/429/5xx must reconnect. A 401 in
      // particular can be a momentarily stale access token, never a removed-member signal.
      var connectCount = 0;
      final controller = StreamController<List<int>>();
      final stream = HouseholdEventStream(
        connect: () async {
          connectCount++;
          if (connectCount == 1) {
            throw const HouseholdStreamRejected(401);
          }
          return controller.stream;
        },
        backoff: (_) => Duration.zero,
        random: _ZeroRandom(),
      );
      final statuses = <LiveSyncStatus>[];
      stream.statusStream.listen(statuses.add);

      stream.start();
      await Future<void>.delayed(const Duration(milliseconds: 50));

      expect(connectCount, 2);
      expect(statuses, isNot(contains(LiveSyncStatus.revoked)));
      expect(statuses.last, LiveSyncStatus.live);

      await controller.close();
      await stream.dispose();
    });

    test('aServerErrorRejectionSchedulesAReconnectAndEventuallySucceeds', () async {
      var connectCount = 0;
      final controller = StreamController<List<int>>();
      final stream = HouseholdEventStream(
        connect: () async {
          connectCount++;
          if (connectCount == 1) {
            throw const HouseholdStreamRejected(503);
          }
          return controller.stream;
        },
        backoff: (_) => Duration.zero,
        random: _ZeroRandom(),
      );
      final statuses = <LiveSyncStatus>[];
      stream.statusStream.listen(statuses.add);

      stream.start();
      await Future<void>.delayed(const Duration(milliseconds: 50));

      expect(connectCount, 2);
      expect(statuses, contains(LiveSyncStatus.reconnecting));
      expect(statuses.last, LiveSyncStatus.live);

      await controller.close();
      await stream.dispose();
    });

    test('aDroppedByteStreamReconnectsWhileRunning', () async {
      var connectCount = 0;
      final firstController = StreamController<List<int>>();
      final secondController = StreamController<List<int>>();
      final stream = HouseholdEventStream(
        connect: () async {
          connectCount++;
          return connectCount == 1 ? firstController.stream : secondController.stream;
        },
        backoff: (_) => Duration.zero,
        random: _ZeroRandom(),
      );
      stream.start();
      await pumpEventQueue();
      expect(connectCount, 1);

      await firstController.close();
      await Future<void>.delayed(const Duration(milliseconds: 50));

      expect(connectCount, 2);

      await secondController.close();
      await stream.dispose();
    });

    test('stop_settlesOnOfflineAndCancelsAnyPendingReconnect', () async {
      var connectCount = 0;
      final stream = HouseholdEventStream(
        connect: () async {
          connectCount++;
          throw const HouseholdStreamRejected(503);
        },
        backoff: (_) => const Duration(milliseconds: 500),
      );
      final statuses = <LiveSyncStatus>[];
      stream.statusStream.listen(statuses.add);

      stream.start();
      await pumpEventQueue();
      await stream.stop();

      final countAtStop = connectCount;
      await Future<void>.delayed(const Duration(milliseconds: 600));

      expect(connectCount, countAtStop, reason: 'no reconnect attempt should fire after stop()');
      expect(statuses.last, LiveSyncStatus.offline);

      await stream.dispose();
    });

    test('aConnectStopRaceDrainsTheJustOpenedByteStreamInsteadOfLeakingIt', () async {
      // Regression: stop()/dispose() (household switch, sign-out) can land while a connect() is
      // still in flight. Before the fix, the resolved byte stream was discarded un-listened,
      // leaking the underlying socket. A single-subscription StreamController's close() future
      // only completes once a listener has drained the done event — an un-listened close() hangs
      // forever, so this test times out if the leak regresses.
      final connectStarted = Completer<void>();
      final releaseConnect = Completer<void>();
      final controller = StreamController<List<int>>();
      final stream = HouseholdEventStream(
        connect: () async {
          connectStarted.complete();
          await releaseConnect.future;
          return controller.stream;
        },
      );

      stream.start();
      await connectStarted.future;
      await stream.stop();
      releaseConnect.complete();
      await pumpEventQueue();

      await controller.close().timeout(const Duration(seconds: 2));

      await stream.dispose();
    });

    test('dispose_isIdempotentWhenCalledConcurrentlyByTwoOwners', () async {
      // Regression: a shell that owns the stream and a caller sharing the same instance (e.g. a
      // test double) may both dispose() it around teardown. A second concurrent dispose() must
      // not hang or throw on the already-closing broadcast controllers.
      final controller = StreamController<List<int>>();
      final stream = HouseholdEventStream(connect: () async => controller.stream);
      stream.start();
      await pumpEventQueue();

      await Future.wait([stream.dispose(), stream.dispose()]).timeout(const Duration(seconds: 5));

      await controller.close();
    });
  });
}
