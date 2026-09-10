import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/shared/sync/household_change_nudge.dart';
import 'package:sgart/shared/sync/household_event_stream.dart';
import 'package:sgart/shared/sync/household_live_sync_controller.dart';

void main() {
  group('HouseholdLiveSyncController', () {
    test('reconcilesOnceTheConnectionBecomesLive', () async {
      final controller = StreamController<List<int>>();
      final eventStream = HouseholdEventStream(connect: () async => controller.stream);
      var reconcileCount = 0;
      final liveSync = HouseholdLiveSyncController(
        eventStream: eventStream,
        onReconcile: () async => reconcileCount++,
        coalesceWindow: Duration.zero,
      );

      eventStream.start();
      await Future<void>.delayed(const Duration(milliseconds: 20));

      expect(reconcileCount, 1);

      await liveSync.dispose();
      await controller.close();
      await eventStream.dispose();
    });

    test('reconcilesOnEveryNudge', () async {
      final controller = StreamController<List<int>>();
      final eventStream = HouseholdEventStream(connect: () async => controller.stream);
      final reconcileCalls = <int>[];
      var reconcileCount = 0;
      final liveSync = HouseholdLiveSyncController(
        eventStream: eventStream,
        onReconcile: () async {
          reconcileCount++;
          reconcileCalls.add(reconcileCount);
        },
        coalesceWindow: Duration.zero,
      );
      eventStream.start();
      await Future<void>.delayed(const Duration(milliseconds: 20));
      final afterConnectCount = reconcileCount;

      controller.add(utf8.encode('event: changed\ndata: {"householdId":"h1","resource":"list"}\n\n'));
      await Future<void>.delayed(const Duration(milliseconds: 20));

      expect(reconcileCount, afterConnectCount + 1);

      await liveSync.dispose();
      await controller.close();
      await eventStream.dispose();
    });

    test('coalescesABurstOfNudgesIntoOneReconcile', () async {
      final controller = StreamController<List<int>>();
      final eventStream = HouseholdEventStream(connect: () async => controller.stream);
      var reconcileCount = 0;
      final liveSync = HouseholdLiveSyncController(
        eventStream: eventStream,
        onReconcile: () async => reconcileCount++,
        coalesceWindow: const Duration(milliseconds: 100),
      );
      eventStream.start();
      await Future<void>.delayed(const Duration(milliseconds: 20));
      final afterConnectCount = reconcileCount;

      // A flurry of near-simultaneous nudges within the coalesce window.
      for (var i = 0; i < 5; i++) {
        controller.add(utf8.encode('event: changed\ndata: {"householdId":"h1","resource":"list"}\n\n'));
        await Future<void>.delayed(const Duration(milliseconds: 10));
      }
      await Future<void>.delayed(const Duration(milliseconds: 150));

      expect(reconcileCount, afterConnectCount + 1, reason: 'the burst must coalesce into a single reconcile');

      await liveSync.dispose();
      await controller.close();
      await eventStream.dispose();
    });

    test('firesOnHouseholdChangedOnlyForAHouseholdResourceNudgeAlongsideTheReconcile', () async {
      final controller = StreamController<List<int>>();
      final eventStream = HouseholdEventStream(connect: () async => controller.stream);
      var reconcileCount = 0;
      var householdChangedCount = 0;
      final liveSync = HouseholdLiveSyncController(
        eventStream: eventStream,
        onReconcile: () async => reconcileCount++,
        onHouseholdChanged: () async => householdChangedCount++,
        coalesceWindow: Duration.zero,
      );
      eventStream.start();
      await Future<void>.delayed(const Duration(milliseconds: 20));
      final reconcileCountAfterConnect = reconcileCount;

      controller.add(utf8.encode('event: changed\ndata: {"householdId":"h1","resource":"list"}\n\n'));
      await Future<void>.delayed(const Duration(milliseconds: 20));

      expect(reconcileCount, reconcileCountAfterConnect + 1);
      expect(householdChangedCount, 0, reason: 'a list nudge must not fire the household-only hook');

      controller.add(utf8.encode('event: changed\ndata: {"householdId":"h1","resource":"household"}\n\n'));
      await Future<void>.delayed(const Duration(milliseconds: 20));

      expect(reconcileCount, reconcileCountAfterConnect + 2, reason: 'a household nudge still reconciles too');
      expect(householdChangedCount, 1);

      await liveSync.dispose();
      await controller.close();
      await eventStream.dispose();
    });

    test('firesOnRevokedOnceTheConnectionSurfacesRevoked', () async {
      final eventStream = HouseholdEventStream(connect: () async => throw const HouseholdStreamRejected(403));
      var revokedCount = 0;
      final liveSync = HouseholdLiveSyncController(
        eventStream: eventStream,
        onReconcile: () async {},
        onRevoked: () async => revokedCount++,
        coalesceWindow: Duration.zero,
      );

      eventStream.start();
      await Future<void>.delayed(const Duration(milliseconds: 20));

      expect(revokedCount, 1);

      await liveSync.dispose();
      await eventStream.dispose();
    });

    // Story 4.5 (AC1, AC5): a background push reuses this exact reconcile seam — see
    // household_shell.dart's pushNudges wiring.
    test('anIncomingPushNudgeTriggersExactlyOneReconcile', () async {
      final controller = StreamController<List<int>>();
      final eventStream = HouseholdEventStream(connect: () async => controller.stream);
      final pushController = StreamController<HouseholdChangeNudge>();
      var reconcileCount = 0;
      final liveSync = HouseholdLiveSyncController(
        eventStream: eventStream,
        onReconcile: () async => reconcileCount++,
        pushNudges: pushController.stream,
        coalesceWindow: Duration.zero,
      );
      eventStream.start();
      await Future<void>.delayed(const Duration(milliseconds: 20));
      final afterConnectCount = reconcileCount;

      pushController.add(const HouseholdChangeNudge(householdId: 'h1', resource: 'list'));
      await Future<void>.delayed(const Duration(milliseconds: 20));

      expect(reconcileCount, afterConnectCount + 1);

      await liveSync.dispose();
      await controller.close();
      await pushController.close();
      await eventStream.dispose();
    });

    test('aPushNudgeFiresOnHouseholdChangedTooForAHouseholdResourceNudge', () async {
      final controller = StreamController<List<int>>();
      final eventStream = HouseholdEventStream(connect: () async => controller.stream);
      final pushController = StreamController<HouseholdChangeNudge>();
      var householdChangedCount = 0;
      final liveSync = HouseholdLiveSyncController(
        eventStream: eventStream,
        onReconcile: () async {},
        onHouseholdChanged: () async => householdChangedCount++,
        pushNudges: pushController.stream,
        coalesceWindow: Duration.zero,
      );
      eventStream.start();
      await Future<void>.delayed(const Duration(milliseconds: 20));

      // Never reads any payload data beyond `resource` into state — this assertion is the whole
      // observable effect of a push (LD-1: wake-and-fetch, never apply push data to state).
      pushController.add(const HouseholdChangeNudge(householdId: 'h1', resource: 'household'));
      await Future<void>.delayed(const Duration(milliseconds: 20));

      expect(householdChangedCount, 1);

      await liveSync.dispose();
      await controller.close();
      await pushController.close();
      await eventStream.dispose();
    });

    test('worksWithNoPushNudgesStreamAtAll', () async {
      final controller = StreamController<List<int>>();
      final eventStream = HouseholdEventStream(connect: () async => controller.stream);
      final liveSync = HouseholdLiveSyncController(
        eventStream: eventStream,
        onReconcile: () async {},
        coalesceWindow: Duration.zero,
      );
      eventStream.start();
      await Future<void>.delayed(const Duration(milliseconds: 20));

      await liveSync.dispose();
      await controller.close();
      await eventStream.dispose();
    });
  });
}
