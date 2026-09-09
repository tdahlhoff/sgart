import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/http/authenticated_http_client.dart';
import '../../../shared/sync/household_event_stream.dart';
import '../../../shared/sync/household_live_sync_controller.dart';
import '../../../shared/sync/live_sync_status.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../../lists/data/shopping_lists_api.dart';
import '../../lists/presentation/list_overview/lists_view.dart';
import '../../lists/presentation/list_overview/shopping_lists_cubit.dart';
import '../../settings/presentation/profile_screen.dart';
import '../../trips/presentation/active_trips_cubit.dart';
import '../../trips/presentation/active_trips_view.dart';
import '../data/household_summary.dart';
import '../data/households_api.dart';
import 'household_switcher_sheet.dart';
import 'households_cubit.dart';

/// Builds the live-sync stream for one household, given the surrounding [BuildContext] (so the
/// real implementation can read [AuthenticatedHttpClient]) — mirrors `AuthGateBody`'s
/// `authenticatedBuilder` test seam (CLAUDE.md §6, "isolate external systems"). Overridden by
/// [HouseholdShell.eventStreamFactory] in tests that want deterministic control over the stream
/// without a real HTTP client.
typedef HouseholdEventStreamFactory = HouseholdEventStream? Function(BuildContext context, String householdId);

/// The persistent app shell (Story 1.7 AC1, Story 1.11 AC1; live sync Story 4.4): a header whose
/// title is the active household's name rendered as a tappable switcher chip (left) plus a live
/// connection-status indicator (right), over a three-tab body — Listen · Einkauf · Profil. Listen
/// and Einkauf are placeholders until Epics 2/3 deliver them; Profil is live. Tapping the chip
/// opens the [HouseholdSwitcherSheet].
///
/// Owns the live-sync connection's lifecycle (T9): it is keyed by the active household's id at the
/// [ShoppingListsCubit]/[ActiveTripsCubit] provider level, but this [State] object itself persists
/// across a household switch (`FirstRunRouterBody` rebuilds the same widget), so the stream is
/// started in [initState] and restarted in [didUpdateWidget] whenever the active household changes
/// — never left pointed at the previous household's id.
///
/// No `ValueKey(activeHousehold.householdId)` wrapper here (accepted deviation, Review decision
/// ④1/P9, see the story's Completion Notes): a key would force a fresh `State` — and a fresh
/// live-sync connection — on every switch, defeating the `didUpdateWidget` reuse this relies on.
class HouseholdShell extends StatefulWidget {
  const HouseholdShell({
    super.key,
    required this.activeHousehold,
    required this.households,
    this.eventStreamFactory = _defaultEventStreamFactory,
  });

  final HouseholdSummary activeHousehold;
  final List<HouseholdSummary> households;
  final HouseholdEventStreamFactory eventStreamFactory;

  /// The production wiring. Guarded: a harness that has not wired [AuthenticatedHttpClient] into
  /// this subtree (most widget tests, which have no interest in live sync) simply gets no live
  /// sync rather than a crash — the feature is additive on top of the existing polling-free CRUD
  /// flows, never load-bearing for them.
  static HouseholdEventStream? _defaultEventStreamFactory(BuildContext context, String householdId) {
    try {
      final httpClient = context.read<AuthenticatedHttpClient>();
      return HouseholdEventStream.forHousehold(httpClient: httpClient, householdId: householdId);
    } on Object {
      return null;
    }
  }

  @override
  State<HouseholdShell> createState() => _HouseholdShellState();
}

class _HouseholdShellState extends State<HouseholdShell> {
  int _selectedTabIndex = 0;
  late ShoppingListsCubit _shoppingListsCubit;
  late ActiveTripsCubit _activeTripsCubit;
  HouseholdEventStream? _eventStream;
  HouseholdLiveSyncController? _liveSyncController;

  @override
  void initState() {
    super.initState();
    _createCubits();
    _startLiveSync();
  }

  @override
  void didUpdateWidget(covariant HouseholdShell oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.activeHousehold.householdId != widget.activeHousehold.householdId) {
      unawaited(_stopLiveSync());
      unawaited(_shoppingListsCubit.close());
      unawaited(_activeTripsCubit.close());
      _createCubits();
      _startLiveSync();
    }
  }

  @override
  void dispose() {
    unawaited(_stopLiveSync());
    unawaited(_shoppingListsCubit.close());
    unawaited(_activeTripsCubit.close());
    super.dispose();
  }

  void _createCubits() {
    _shoppingListsCubit = ShoppingListsCubit(
      shoppingListsApi: context.read<ShoppingListsApi>(),
      householdId: widget.activeHousehold.householdId,
    )..bootstrap();
    _activeTripsCubit = ActiveTripsCubit(
      shoppingListsApi: context.read<ShoppingListsApi>(),
      householdId: widget.activeHousehold.householdId,
    )..bootstrap();
  }

  void _startLiveSync() {
    final eventStream = widget.eventStreamFactory(context, widget.activeHousehold.householdId);
    _eventStream = eventStream;
    if (eventStream == null) {
      return;
    }
    _liveSyncController = HouseholdLiveSyncController(
      eventStream: eventStream,
      onReconcile: () async {
        await _shoppingListsCubit.refresh();
        _shoppingListsCubit.invalidateArchive();
        await _activeTripsCubit.refresh();
      },
      // A rename by another member (Review P8): re-bootstrap so the switcher chip picks up the
      // new name — reuses the exact refetch-and-reroute HouseholdsCubit already does on launch.
      onHouseholdChanged: _rebootstrapHouseholds,
      // A `403` (AC3, this member was removed/left — Review P1/decision ①1): re-bootstrap so the
      // now-inaccessible household drops out of view, silently re-routing to selection/create.
      onRevoked: _rebootstrapHouseholds,
    );
    eventStream.start();
  }

  /// Re-fetches and re-routes through [HouseholdsCubit] — the same machinery [FirstRunRouter]
  /// bootstraps with on launch, reused here for the two live-sync hooks above. Guarded exactly like
  /// [_defaultEventStreamFactory]: a harness with no [HouseholdsCubit] ancestor (most widget tests)
  /// simply skips the re-bootstrap rather than crashing.
  Future<void> _rebootstrapHouseholds() async {
    try {
      await context.read<HouseholdsCubit>().bootstrap();
    } on Object {
      // No HouseholdsCubit ancestor — see above.
    }
  }

  Future<void> _stopLiveSync() async {
    final liveSyncController = _liveSyncController;
    final eventStream = _eventStream;
    _liveSyncController = null;
    _eventStream = null;
    await liveSyncController?.dispose();
    await eventStream?.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Scaffold(
      appBar: SgartAppBar(
        title: widget.activeHousehold.name,
        titleKey: const Key('switcher-chip'),
        onTitleTap: () => _openSwitcher(context),
        onTitleTapSemanticLabel: localizations.householdsSwitcherChipTooltip,
        actions: [
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: SgartShapes.space4),
            child: _SyncStatusIndicator(eventStream: _eventStream),
          ),
        ],
      ),
      // Built eagerly for all three tabs so state is preserved when switching (and the Profil
      // identity header can read the ancestor AuthCubit at build time, not just on tap). The
      // ShoppingListsCubit is hoisted above the IndexedStack so the Einkauf tab (ActiveTripsView)
      // can call invalidateArchive() after completion without a ProviderNotFoundException (Story 3.4
      // AC7 fix). Both cubits are owned by this State (not BlocProvider's own create/key recycling)
      // so the live-sync controller above can reconcile them directly (T8).
      body: BlocProvider<ShoppingListsCubit>.value(
        value: _shoppingListsCubit,
        child: IndexedStack(
          index: _selectedTabIndex,
          children: [
            const ListsView(),
            BlocProvider<ActiveTripsCubit>.value(
              value: _activeTripsCubit,
              child: const ActiveTripsView(),
            ),
            const ProfileScreen(),
          ],
        ),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _selectedTabIndex,
        onDestinationSelected: (index) => setState(() => _selectedTabIndex = index),
        destinations: [
          NavigationDestination(
            key: const Key('shell-tab-lists'),
            icon: const Icon(Icons.list_alt_outlined),
            selectedIcon: const Icon(Icons.list_alt),
            label: localizations.shellTabListsLabel,
          ),
          NavigationDestination(
            key: const Key('shell-tab-shopping'),
            icon: const Icon(Icons.shopping_cart_outlined),
            selectedIcon: const Icon(Icons.shopping_cart),
            label: localizations.shellTabShoppingLabel,
          ),
          NavigationDestination(
            key: const Key('shell-tab-profile'),
            icon: const Icon(Icons.person_outline),
            selectedIcon: const Icon(Icons.person),
            label: localizations.shellTabProfileLabel,
          ),
        ],
      ),
    );
  }

  void _openSwitcher(BuildContext context) {
    // Re-provide the api/cubit so the sheet (and the routes it pushes) can reach them.
    final householdsApi = context.read<HouseholdsApi>();
    final householdsCubit = context.read<HouseholdsCubit>();
    showModalBottomSheet<void>(
      context: context,
      builder: (_) => RepositoryProvider<HouseholdsApi>.value(
        value: householdsApi,
        child: BlocProvider<HouseholdsCubit>.value(
          value: householdsCubit,
          child: HouseholdSwitcherSheet(activeHousehold: widget.activeHousehold, households: widget.households),
        ),
      ),
    );
  }
}

/// The real live-sync status indicator (Story 4.4, T10) — replaces the Epic 1 placeholder icon.
/// Groups the five [LiveSyncStatus] values into three visible states (live / syncing / offline)
/// since a user doesn't need to distinguish "connecting" from "reconnecting" or "offline" from
/// "revoked" — both pairs read the same to them. `null` (no live sync wired, e.g. a harness that
/// hasn't provided `AuthenticatedHttpClient`) renders the same as offline.
class _SyncStatusIndicator extends StatelessWidget {
  const _SyncStatusIndicator({required this.eventStream});

  final HouseholdEventStream? eventStream;

  @override
  Widget build(BuildContext context) {
    final eventStream = this.eventStream;
    if (eventStream == null) {
      return _buildIcon(context, LiveSyncStatus.offline);
    }
    return StreamBuilder<LiveSyncStatus>(
      initialData: eventStream.status,
      stream: eventStream.statusStream,
      builder: (context, snapshot) => _buildIcon(context, snapshot.data ?? LiveSyncStatus.offline),
    );
  }

  Widget _buildIcon(BuildContext context, LiveSyncStatus status) {
    final localizations = AppLocalizations.of(context);
    final (icon, label) = switch (status) {
      LiveSyncStatus.live => (Icons.cloud_done_outlined, localizations.householdsSyncStatusLiveLabel),
      LiveSyncStatus.connecting ||
      LiveSyncStatus.reconnecting =>
        (Icons.cloud_sync_outlined, localizations.householdsSyncStatusConnectingLabel),
      LiveSyncStatus.offline || LiveSyncStatus.revoked => (
          Icons.cloud_off_outlined,
          localizations.householdsSyncStatusOfflineLabel,
        ),
    };
    return Tooltip(
      message: label,
      child: Icon(icon, key: const Key('sync-status-indicator')),
    );
  }
}
