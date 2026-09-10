import 'dart:async';

import 'package:sgart/shared/push/push_notifications.dart';
import 'package:sgart/shared/sync/household_change_nudge.dart';

/// Test double for [PushNotifications] (CLAUDE.md §6) — no real transport, no native platform
/// config, no external accounts (D2). Counts register/unregister calls and lets a test push an
/// incoming nudge through [emitPush].
class FakePushNotifications implements PushNotifications {
  int registerCallCount = 0;
  int unregisterCallCount = 0;
  final StreamController<HouseholdChangeNudge> _incomingPushesController = StreamController.broadcast();

  @override
  Future<void> register() async {
    registerCallCount++;
  }

  @override
  Future<void> unregister() async {
    unregisterCallCount++;
  }

  @override
  Stream<HouseholdChangeNudge> get incomingPushes => _incomingPushesController.stream;

  /// Simulates an incoming content-free push arriving while the app is foregrounded.
  void emitPush(HouseholdChangeNudge nudge) => _incomingPushesController.add(nudge);

  Future<void> dispose() => _incomingPushesController.close();
}
