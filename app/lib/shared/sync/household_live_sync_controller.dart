import 'dart:async';

import 'household_change_nudge.dart';
import 'household_event_stream.dart';
import 'live_sync_status.dart';

/// Wires a [HouseholdEventStream] to a reconcile callback (Story 4.4, T8, AC1/AC2): reconciles on
/// every (re)connect (the connection transitioning to [LiveSyncStatus.live]) and on every
/// content-free nudge — a plain refetch through the existing GET queries (LD-1), never a
/// client-side delta-apply. Debounces bursts so a flurry of near-simultaneous edits triggers at
/// most one reconcile per short window instead of one per event.
///
/// Two more, narrower hooks (Review, decisions ①1/③1): [onHouseholdChanged] fires (coalesced with
/// the same debounce window) after a `resource:"household"` nudge, so a live rename can update the
/// always-visible switcher chip without every nudge paying for it; [onRevoked] fires once the
/// connection surfaces [LiveSyncStatus.revoked] (a `403` — AC3, T7), so the removed member's
/// household can drop out of view.
class HouseholdLiveSyncController {
  // Not initializing formals: the constructor body also needs `eventStream` (a non-field
  // parameter) to wire the subscriptions below.
  HouseholdLiveSyncController({
    required HouseholdEventStream eventStream,
    required Future<void> Function() onReconcile,
    Future<void> Function()? onHouseholdChanged,
    Future<void> Function()? onRevoked,
    Duration coalesceWindow = const Duration(milliseconds: 300),
  })  : _onReconcile = onReconcile, // ignore: prefer_initializing_formals
        _onHouseholdChanged = onHouseholdChanged, // ignore: prefer_initializing_formals
        _onRevoked = onRevoked, // ignore: prefer_initializing_formals
        _coalesceWindow = coalesceWindow { // ignore: prefer_initializing_formals
    _statusSubscription = eventStream.statusStream.listen(_onStatus);
    _changeSubscription = eventStream.changes.listen(_onChange);
  }

  final Future<void> Function() _onReconcile;
  final Future<void> Function()? _onHouseholdChanged;
  final Future<void> Function()? _onRevoked;
  final Duration _coalesceWindow;

  late final StreamSubscription<LiveSyncStatus> _statusSubscription;
  late final StreamSubscription<HouseholdChangeNudge> _changeSubscription;
  Timer? _debounce;
  bool _householdChangedPending = false;

  void _onStatus(LiveSyncStatus status) {
    if (status == LiveSyncStatus.live) {
      _scheduleReconcile();
    } else if (status == LiveSyncStatus.revoked) {
      unawaited(_onRevoked?.call());
    }
  }

  void _onChange(HouseholdChangeNudge nudge) {
    if (nudge.resource == 'household') {
      _householdChangedPending = true;
    }
    _scheduleReconcile();
  }

  void _scheduleReconcile() {
    _debounce?.cancel();
    _debounce = Timer(_coalesceWindow, _fireReconcile);
  }

  void _fireReconcile() {
    unawaited(_onReconcile());
    if (_householdChangedPending) {
      _householdChangedPending = false;
      unawaited(_onHouseholdChanged?.call());
    }
  }

  Future<void> dispose() async {
    _debounce?.cancel();
    await _statusSubscription.cancel();
    await _changeSubscription.cancel();
  }
}
