import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../data/households_api.dart';
import 'await_invite_page.dart';
import 'create_household_page.dart';
import 'households_cubit.dart';

/// The first-run choice for a caller with zero households (AC1): create one, or wait for an
/// invite. „Auf Einladung warten" is an informational dead-end here — invite acceptance is
/// Epic 4.
class CreateOrAwaitChoicePage extends StatelessWidget {
  const CreateOrAwaitChoicePage({super.key});

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Scaffold(
      appBar: const SgartAppBar(title: 'SGART'),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(SgartShapes.cardPadding),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              Text(localizations.householdsChoiceHeading),
              const SizedBox(height: SgartShapes.space4),
              SgartButton(
                key: const Key('create-household-choice-button'),
                label: localizations.householdsCreateChoiceButtonLabel,
                onPressed: () => _openCreateHousehold(context),
              ),
              const SizedBox(height: SgartShapes.space2),
              SgartButton(
                key: const Key('await-invite-choice-button'),
                label: localizations.householdsAwaitInviteChoiceButtonLabel,
                variant: SgartButtonVariant.secondary,
                onPressed: () => Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => const AwaitInvitePage()),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// Pushes the create-household form. The push targets the root Navigator, which sits *above* the
  /// `HouseholdsApi`/`HouseholdsCubit` providers created in `FirstRunRouter`, so the pushed route
  /// would otherwise escape them (`ProviderNotFoundException`). Re-provide both by value — the same
  /// instances this screen already reads — so the pushed subtree can reach them.
  void _openCreateHousehold(BuildContext context) {
    final householdsApi = context.read<HouseholdsApi>();
    final householdsCubit = context.read<HouseholdsCubit>();
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => RepositoryProvider<HouseholdsApi>.value(
          value: householdsApi,
          child: BlocProvider<HouseholdsCubit>.value(
            value: householdsCubit,
            child: const CreateHouseholdPage(),
          ),
        ),
      ),
    );
  }
}
