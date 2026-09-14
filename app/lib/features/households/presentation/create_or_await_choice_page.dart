import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../../auth/presentation/auth_cubit.dart';
import '../../auth/presentation/recover_account_page.dart';
import '../../auth/presentation/recovery_phrase_reveal_page.dart';
import '../../invites/data/invites_api.dart';
import '../../onboarding/presentation/onboarding_wizard_page.dart';
import '../../stores/data/store_chain_reference_cache.dart';
import '../../stores/data/stores_api.dart';
import '../data/households_api.dart';
import 'await_invite_page.dart';
import 'households_cubit.dart';

/// The first-run choice for a caller with zero households (AC1): create one, or accept a personal
/// invite and join theirs (Story 4.2). This is frame 1 of the onboarding mockup — privacy is
/// stated up front (AC3, Story 1.9), no account/marketing pressure. „Haushalt erstellen" launches
/// the guided onboarding wizard; „Auf Einladung warten" opens the accept-invite screen.
class CreateOrAwaitChoicePage extends StatelessWidget {
  const CreateOrAwaitChoicePage({super.key});

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Scaffold(
      appBar: const SgartAppBar(title: 'SGART'),
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: SgartShapes.screenHeaderPadding,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    localizations.householdsChoiceHeading,
                    style: Theme.of(context).textTheme.headlineSmall,
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: SgartShapes.headingGap),
                  Text(
                    localizations.onboardingChoicePrivacyNote,
                    key: const Key('onboarding-choice-privacy'),
                    style: Theme.of(context).textTheme.bodySmall,
                    textAlign: TextAlign.center,
                  ),
                ],
              ),
            ),
            const Spacer(),
            Padding(
              padding: const EdgeInsets.all(SgartShapes.cardPadding),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
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
                    onPressed: () => _openAwaitInvite(context),
                  ),
                  const SizedBox(height: SgartShapes.space4),
                  // Two quiet, non-competing actions (D-A/D-B/D-D): saving the phrase now (the
                  // guaranteed first sight, since both choices above start here) and, for a
                  // reinstalling person, restoring an existing account instead of starting fresh.
                  SgartButton(
                    key: const Key('save-recovery-phrase-choice-button'),
                    label: localizations.householdsSaveRecoveryPhraseActionLabel,
                    variant: SgartButtonVariant.tonal,
                    onPressed: () => openRecoveryPhraseRevealPage(context),
                  ),
                  const SizedBox(height: SgartShapes.space2),
                  SgartButton(
                    key: const Key('recover-account-choice-button'),
                    label: localizations.householdsRecoverAccountActionLabel,
                    variant: SgartButtonVariant.tonal,
                    onPressed: () => _openRecoverAccount(context),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// Pushes the guided onboarding wizard (Story 1.9). The push targets the root Navigator, which
  /// sits *above* the providers created in `FirstRunRouter`, so the pushed route would otherwise
  /// escape them (`ProviderNotFoundException`, the Story 1.6 lesson). Re-provide the five the wizard
  /// reads — `HouseholdsApi`/`HouseholdsCubit` (name step + landing), `StoresApi`/
  /// `StoreChainReferenceCache` (stores step), and `InvitesApi` (invite step) — by value, the same
  /// instances this screen already reads.
  void _openOnboarding(BuildContext context) {
    final householdsApi = context.read<HouseholdsApi>();
    final householdsCubit = context.read<HouseholdsCubit>();
    final storesApi = context.read<StoresApi>();
    final storeChainReferenceCache = context.read<StoreChainReferenceCache>();
    final invitesApi = context.read<InvitesApi>();
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => MultiRepositoryProvider(
          providers: [
            RepositoryProvider<HouseholdsApi>.value(value: householdsApi),
            RepositoryProvider<StoresApi>.value(value: storesApi),
            RepositoryProvider<StoreChainReferenceCache>.value(value: storeChainReferenceCache),
            RepositoryProvider<InvitesApi>.value(value: invitesApi),
          ],
          child: BlocProvider<HouseholdsCubit>.value(
            value: householdsCubit,
            child: const OnboardingWizardPage(),
          ),
        ),
      ),
    );
  }

  /// Pushes the accept-invite screen (Story 4.2) — see [openAwaitInvitePage].
  void _openAwaitInvite(BuildContext context) => openAwaitInvitePage(context);

  /// Pushes the recovery-phrase entry form (Story 7.2, AC3, D-D), re-providing [AuthCubit] across
  /// the push boundary the same way. (The reveal-page save action uses the shared
  /// [openRecoveryPhraseRevealPage] helper directly.)
  void _openRecoverAccount(BuildContext context) {
    final authCubit = context.read<AuthCubit>();
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => BlocProvider<AuthCubit>.value(
          value: authCubit,
          child: const RecoverAccountPage(),
        ),
      ),
    );
  }
}
