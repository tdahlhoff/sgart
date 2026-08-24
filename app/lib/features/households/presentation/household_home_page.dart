import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../../auth/presentation/auth_cubit.dart';
import '../data/household_summary.dart';

/// The minimal household home content (AC2): the current household's name and a sign-out action.
/// Since Story 1.7 this is the **body** of [HouseholdShell] — the persistent header (switcher chip)
/// is the shell's, so this widget renders no chrome of its own. The lists screen is Epic 2.
class HouseholdHomePage extends StatelessWidget {
  const HouseholdHomePage({super.key, required this.household});

  final HouseholdSummary household;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(SgartShapes.cardPadding),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            Text(localizations.householdsHomeCurrentHouseholdLabel),
            const SizedBox(height: SgartShapes.headingGap),
            Text(household.name, key: const Key('current-household-name')),
            const SizedBox(height: SgartShapes.space4),
            SgartButton(
              key: const Key('sign-out-button'),
              label: localizations.authSignOutButtonLabel,
              variant: SgartButtonVariant.secondary,
              onPressed: () => context.read<AuthCubit>().signOut(),
            ),
          ],
        ),
      ),
    );
  }
}
