import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../data/household_summary.dart';
import 'households_cubit.dart';

/// The minimal selection screen (AC2) for a caller who belongs to several households: a list of
/// names, tap to enter. The always-visible switcher is Story 1.7 — this is only the routing list.
class HouseholdSelectionPage extends StatelessWidget {
  const HouseholdSelectionPage({super.key, required this.households});

  final List<HouseholdSummary> households;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Scaffold(
      appBar: SgartAppBar(title: localizations.householdsSelectionHeading),
      body: SafeArea(
        child: ListView.builder(
          padding: const EdgeInsets.all(SgartShapes.cardPadding),
          itemCount: households.length,
          itemBuilder: (context, index) {
            final household = households[index];
            return ListTile(
              key: Key('household-selection-item-${household.householdId}'),
              title: Text(household.name),
              onTap: () => context.read<HouseholdsCubit>().selectHousehold(household),
            );
          },
        ),
      ),
    );
  }
}
