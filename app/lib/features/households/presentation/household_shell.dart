import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../data/household_summary.dart';
import '../data/households_api.dart';
import 'household_home_page.dart';
import 'household_switcher_sheet.dart';
import 'households_cubit.dart';

/// The persistent app shell (Story 1.7, AC1): a header whose title is the active household's name
/// rendered as a tappable switcher chip (left) plus a sync/offline status placeholder (right; the
/// real status is Epic 4/5), over the minimal home body. Tapping the chip opens the
/// [HouseholdSwitcherSheet]. Tabs (Listen/Einkauf/Profil) are Epic 2/3.
class HouseholdShell extends StatelessWidget {
  const HouseholdShell({super.key, required this.activeHousehold, required this.households});

  final HouseholdSummary activeHousehold;
  final List<HouseholdSummary> households;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Scaffold(
      appBar: SgartAppBar(
        title: activeHousehold.name,
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
      body: HouseholdHomePage(household: activeHousehold),
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
          child: HouseholdSwitcherSheet(activeHousehold: activeHousehold, households: households),
        ),
      ),
    );
  }
}
