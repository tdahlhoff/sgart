import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../../onboarding/presentation/onboarding_wizard_page.dart';
import '../../stores/data/store_chain_reference_cache.dart';
import '../../stores/data/stores_api.dart';
import '../data/households_api.dart';
import 'await_invite_page.dart';
import 'households_cubit.dart';

/// The first-run choice for a caller with zero households (AC1): create one, or wait for an
/// invite. This is frame 1 of the onboarding mockup — privacy is stated up front (AC3, Story 1.9),
/// no account/marketing pressure. „Haushalt erstellen" launches the guided onboarding wizard;
/// „Auf Einladung warten" is an informational dead-end (invite acceptance is Epic 4).
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
                onPressed: () => _openOnboarding(context),
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
              const SizedBox(height: SgartShapes.space4),
              Text(
                localizations.onboardingChoicePrivacyNote,
                key: const Key('onboarding-choice-privacy'),
                style: Theme.of(context).textTheme.bodySmall,
                textAlign: TextAlign.center,
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// Pushes the guided onboarding wizard (Story 1.9). The push targets the root Navigator, which
  /// sits *above* the providers created in `FirstRunRouter`, so the pushed route would otherwise
  /// escape them (`ProviderNotFoundException`, the Story 1.6 lesson). Re-provide the four the wizard
  /// reads — `HouseholdsApi`/`HouseholdsCubit` (name step + landing) and `StoresApi`/
  /// `StoreChainReferenceCache` (stores step) — by value, the same instances this screen already reads.
  void _openOnboarding(BuildContext context) {
    final householdsApi = context.read<HouseholdsApi>();
    final householdsCubit = context.read<HouseholdsCubit>();
    final storesApi = context.read<StoresApi>();
    final storeChainReferenceCache = context.read<StoreChainReferenceCache>();
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => MultiRepositoryProvider(
          providers: [
            RepositoryProvider<HouseholdsApi>.value(value: householdsApi),
            RepositoryProvider<StoresApi>.value(value: storesApi),
            RepositoryProvider<StoreChainReferenceCache>.value(value: storeChainReferenceCache),
          ],
          child: BlocProvider<HouseholdsCubit>.value(
            value: householdsCubit,
            child: const OnboardingWizardPage(),
          ),
        ),
      ),
    );
  }
}
