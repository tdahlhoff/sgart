import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../data/household_summary.dart';
import '../data/households_api.dart';
import 'create_household_page.dart';
import 'households_cubit.dart';
import 'rename_household_page.dart';

/// The household switcher bottom sheet (AC1/AC2): lists all the caller's households with the active
/// one marked „Aktiv", switches to another on tap (with a brief confirmation), and hosts
/// „Haushalt umbenennen" (active household) + „Neuen Haushalt erstellen". The full „Haushalt
/// verwalten" hub (members/invites/roles/stores) is Epic 4 — not built here.
class HouseholdSwitcherSheet extends StatelessWidget {
  const HouseholdSwitcherSheet({super.key, required this.activeHousehold, required this.households});

  final HouseholdSummary activeHousehold;
  final List<HouseholdSummary> households;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(vertical: SgartShapes.cardPadding),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: SgartShapes.cardPadding),
              child: Text(localizations.householdsSwitcherHeading),
            ),
            const SizedBox(height: SgartShapes.space2),
            for (final household in households) _householdTile(context, localizations, household),
            const Divider(),
            ListTile(
              key: const Key('switcher-rename-button'),
              leading: const Icon(Icons.edit_outlined),
              title: Text(localizations.householdsSwitcherRenameButtonLabel),
              onTap: () => _openRename(context),
            ),
            ListTile(
              key: const Key('switcher-create-button'),
              leading: const Icon(Icons.add),
              title: Text(localizations.householdsSwitcherCreateButtonLabel),
              onTap: () => _openCreate(context),
            ),
          ],
        ),
      ),
    );
  }

  Widget _householdTile(
      BuildContext context, AppLocalizations localizations, HouseholdSummary household) {
    final isActive = household.householdId == activeHousehold.householdId;
    return ListTile(
      key: Key('switcher-item-${household.householdId}'),
      title: Text(household.name),
      trailing: isActive
          ? Text(localizations.householdsSwitcherActiveBadge, key: const Key('switcher-active-badge'))
          : null,
      onTap: isActive ? null : () => _switchTo(context, localizations, household),
    );
  }

  void _switchTo(BuildContext context, AppLocalizations localizations, HouseholdSummary household) {
    // Capture messenger/navigator before the sheet's context is torn down by the pop.
    final messenger = ScaffoldMessenger.of(context);
    final navigator = Navigator.of(context);
    context.read<HouseholdsCubit>().switchActive(household);
    navigator.pop();
    messenger.showSnackBar(SnackBar(
      key: const Key('switch-confirmation'),
      content: Text(localizations.householdsSwitchConfirmation(household.name)),
    ));
  }

  void _openRename(BuildContext context) {
    _pushOverProviders(context, RenameHouseholdPage(household: activeHousehold));
  }

  void _openCreate(BuildContext context) {
    _pushOverProviders(context, const CreateHouseholdPage());
  }

  /// Closes the sheet and pushes [page], re-providing the [HouseholdsApi]/[HouseholdsCubit] the
  /// pushed route depends on — a route pushed on the root navigator sits above the providers
  /// `FirstRunRouter` created, so it would otherwise escape them (`ProviderNotFoundException`,
  /// the Story 1.6 P1 lesson).
  void _pushOverProviders(BuildContext context, Widget page) {
    final householdsApi = context.read<HouseholdsApi>();
    final householdsCubit = context.read<HouseholdsCubit>();
    final navigator = Navigator.of(context);
    navigator.pop();
    navigator.push(MaterialPageRoute(
      builder: (_) => RepositoryProvider<HouseholdsApi>.value(
        value: householdsApi,
        child: BlocProvider<HouseholdsCubit>.value(value: householdsCubit, child: page),
      ),
    ));
  }
}
