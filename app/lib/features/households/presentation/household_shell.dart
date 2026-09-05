import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
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

/// The persistent app shell (Story 1.7 AC1, Story 1.11 AC1): a header whose title is the active
/// household's name rendered as a tappable switcher chip (left) plus a sync/offline status
/// placeholder (right; the real status is Epic 4/5), over a three-tab body — Listen · Einkauf ·
/// Profil. Listen and Einkauf are placeholders until Epics 2/3 deliver them; Profil is live.
/// Tapping the chip opens the [HouseholdSwitcherSheet].
class HouseholdShell extends StatefulWidget {
  const HouseholdShell({super.key, required this.activeHousehold, required this.households});

  final HouseholdSummary activeHousehold;
  final List<HouseholdSummary> households;

  @override
  State<HouseholdShell> createState() => _HouseholdShellState();
}

class _HouseholdShellState extends State<HouseholdShell> {
  int _selectedTabIndex = 0;

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
            child: Tooltip(
              message: localizations.householdsSyncStatusPlaceholderLabel,
              child: const Icon(Icons.cloud_queue_outlined, key: Key('sync-status-placeholder')),
            ),
          ),
        ],
      ),
      // Built eagerly for all three tabs so state is preserved when switching (and the Profil
      // identity header can read the ancestor AuthCubit at build time, not just on tap). The
      // ShoppingListsCubit is hoisted above the IndexedStack so the Einkauf tab (ActiveTripsView)
      // can call invalidateArchive() after completion without a ProviderNotFoundException (Story 3.4
      // AC7 fix). Keyed on the active household's id so the provider is torn down and rebuilt on a
      // household switch — a stale cubit would otherwise linger across tab switches.
      body: BlocProvider<ShoppingListsCubit>(
        key: ValueKey(widget.activeHousehold.householdId),
        create: (context) => ShoppingListsCubit(
          shoppingListsApi: context.read<ShoppingListsApi>(),
          householdId: widget.activeHousehold.householdId,
        )..bootstrap(),
        child: IndexedStack(
          index: _selectedTabIndex,
          children: [
            const ListsView(),
            BlocProvider<ActiveTripsCubit>(
              key: ValueKey(widget.activeHousehold.householdId),
              create: (context) => ActiveTripsCubit(
                shoppingListsApi: context.read<ShoppingListsApi>(),
                householdId: widget.activeHousehold.householdId,
              )..bootstrap(),
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
