import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../../households/data/household_summary.dart';
import '../../households/data/households_api.dart';
import '../../households/presentation/create_household_cubit.dart';
import '../../households/presentation/create_household_name_field.dart';
import '../../households/presentation/create_household_state.dart';
import '../../households/presentation/households_cubit.dart';
import '../../stores/data/store_chain_reference_cache.dart';
import '../../stores/data/stores_api.dart';
import '../../stores/presentation/stores_cubit.dart';
import '../../stores/presentation/stores_management_view.dart';

/// The three wizard steps (Story 1.9, AC1). The welcome/choice (frame 1 of the mockup) stays the
/// `CreateOrAwaitChoicePage` that launches this wizard, so the counted steps are name → stores →
/// invite. `_OnboardingStep.index + 1` is the 1-based step number shown to the person.
enum _OnboardingStep { name, stores, invite }

/// The gentle, one-step-at-a-time onboarding wizard for a person creating their first household
/// (Story 1.9, UX-DR10 — the Werner path). It **reuses** the shipped paths rather than reinventing
/// them: the name step drives the Story 1.6 [CreateHouseholdCubit]; the stores step mounts the Story
/// 1.8 [StoresManagementView]/[StoresCubit] (the reusable creation path 1.8's AC4 was built for); and
/// finishing lands in the created household via [HouseholdsCubit.selectHousehold] (read-your-writes,
/// AC2), the same transition the minimal create page performs.
///
/// The invite step is **present but non-sending** (AC4, Clarification 1 Option A): „Einladung senden"
/// is disabled („folgt später") and only „Später einladen — fertig" is functional — invite creation
/// is Epic 4 (Story 4.1). The typed email is never stored, logged, or transmitted here.
///
/// Reached as a pushed route above the `FirstRunRouter` providers, so its dependencies
/// ([HouseholdsApi], [HouseholdsCubit], [StoresApi], [StoreChainReferenceCache]) are re-provided by
/// value at the push site (the Story 1.6 `ProviderNotFoundException` lesson).
class OnboardingWizardPage extends StatelessWidget {
  const OnboardingWizardPage({super.key});

  @override
  Widget build(BuildContext context) {
    // One CreateHouseholdCubit for the whole wizard: it holds a single command id, so stepping back
    // to the name step and resubmitting converges on the same household (the deterministic
    // household-id from Story 1.6) rather than creating a duplicate — one-way creation, Clarification 2.
    return BlocProvider(
      create: (_) => CreateHouseholdCubit(householdsApi: context.read<HouseholdsApi>()),
      child: const _OnboardingWizardView(),
    );
  }
}

class _OnboardingWizardView extends StatefulWidget {
  const _OnboardingWizardView();

  @override
  State<_OnboardingWizardView> createState() => _OnboardingWizardViewState();
}

class _OnboardingWizardViewState extends State<_OnboardingWizardView> {
  final TextEditingController _nameController = TextEditingController();
  _OnboardingStep _step = _OnboardingStep.name;
  HouseholdSummary? _createdHousehold;

  @override
  void dispose() {
    _nameController.dispose();
    super.dispose();
  }

  void _onHouseholdCreated(HouseholdSummary household) {
    setState(() {
      _createdHousehold = household;
      _step = _OnboardingStep.stores;
    });
  }

  void _goToStep(_OnboardingStep step) => setState(() => _step = step);

  /// „Zurück" on the name step. Before the household exists this pops back to the first-run choice
  /// (nothing was created). Once it exists, the wizard must never strand it: any exit lands the
  /// person in the created household instead (Clarification 2 „any exit lands in the created
  /// household", AC2).
  void _handleNameBack() {
    if (_createdHousehold != null) {
      _finish();
    } else {
      Navigator.of(context).pop();
    }
  }

  /// Finishes onboarding: enter the created household (read-your-writes, AC2) and pop the wizard back
  /// to the first-run router root — the same landing the minimal create page performs. Solo is
  /// first-class: skipping the invite is a full success, no nag.
  void _finish() {
    final household = _createdHousehold;
    if (household == null) {
      return;
    }
    context.read<HouseholdsCubit>().selectHousehold(household);
    Navigator.of(context).popUntil((route) => route.isFirst);
  }

  @override
  Widget build(BuildContext context) {
    // Until the household is created the wizard pops back to the first-run choice as usual. Once it
    // exists, the Android system back gesture must not drop the person back on the choice screen with
    // a household already created (it would strand it and invite a duplicate) — intercept the pop and
    // land in the created household instead (Clarification 2, AC2).
    return PopScope(
      canPop: _createdHousehold == null,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) {
          _finish();
        }
      },
      child: Scaffold(
        appBar: const SgartAppBar(title: 'SGART'),
        body: SafeArea(
          child: BlocListener<CreateHouseholdCubit, CreateHouseholdState>(
            listenWhen: (previous, current) => current.status == CreateHouseholdStatus.success,
            listener: (context, state) => _onHouseholdCreated(state.household!),
            child: switch (_step) {
              _OnboardingStep.name => _NameStep(
                  controller: _nameController,
                  onBack: _handleNameBack,
                  alreadyCreated: _createdHousehold != null,
                  onAdvance: () => _goToStep(_OnboardingStep.stores),
                ),
              _OnboardingStep.stores => _StoresStep(
                  household: _createdHousehold!,
                  onNext: () => _goToStep(_OnboardingStep.invite),
                  onBack: () => _goToStep(_OnboardingStep.name),
                ),
              _OnboardingStep.invite => _InviteStep(
                  onFinish: _finish,
                  onBack: () => _goToStep(_OnboardingStep.stores),
                ),
            },
          ),
        ),
      ),
    );
  }
}

/// Shared step chrome: the back control, the „Schritt X von 3" label, the progress track, then the
/// step title and helper copy (UX-DR10 — a visible progress indicator, one question per step).
class _OnboardingStepHeader extends StatelessWidget {
  const _OnboardingStepHeader({
    required this.step,
    required this.title,
    required this.help,
    required this.onBack,
  });

  final _OnboardingStep step;
  final String title;
  final String help;
  final VoidCallback onBack;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final totalSteps = _OnboardingStep.values.length;
    final current = step.index + 1;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            IconButton(
              key: const Key('onboarding-back-button'),
              icon: const Icon(Icons.arrow_back),
              tooltip: localizations.householdsBackButtonLabel,
              onPressed: onBack,
            ),
            Text(
              localizations.onboardingStepLabel(current, totalSteps),
              key: const Key('onboarding-step-label'),
              style: Theme.of(context).textTheme.labelMedium,
            ),
          ],
        ),
        const SizedBox(height: SgartShapes.space2),
        LinearProgressIndicator(
          key: const Key('onboarding-progress'),
          value: current / totalSteps,
        ),
        const SizedBox(height: SgartShapes.space4),
        Text(title, style: Theme.of(context).textTheme.headlineSmall),
        const SizedBox(height: SgartShapes.headingGap),
        Text(help, style: Theme.of(context).textTheme.bodyMedium),
      ],
    );
  }
}

/// Step 1 — name the household. Drives the reused [CreateHouseholdCubit] via the shared
/// [CreateHouseholdNameField]; on success the view's listener advances to the stores step. A rejected
/// name (blank/too long) shows inline without leaving the step.
///
/// Once the household has been created ([alreadyCreated]), revisiting this step is read-only and
/// „Weiter" simply advances via [onAdvance] instead of submitting again: the single reused command
/// id makes the backend keep the original name, so re-submitting an edited name would diverge the
/// shown name from what is persisted (Clarification 2 — one-way creation, never re-create/re-name).
class _NameStep extends StatelessWidget {
  const _NameStep({
    required this.controller,
    required this.onBack,
    required this.alreadyCreated,
    required this.onAdvance,
  });

  final TextEditingController controller;
  final VoidCallback onBack;
  final bool alreadyCreated;
  final VoidCallback onAdvance;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return BlocBuilder<CreateHouseholdCubit, CreateHouseholdState>(
      builder: (context, state) {
        final isSubmitting = state.status == CreateHouseholdStatus.submitting;
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _OnboardingStepHeader(
              step: _OnboardingStep.name,
              title: localizations.onboardingNameStepTitle,
              help: localizations.onboardingNameStepHelp,
              onBack: onBack,
            ),
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(SgartShapes.cardPadding),
                child: CreateHouseholdNameField(
                  controller: controller,
                  fieldKey: const Key('onboarding-name-field'),
                  errorKey: const Key('onboarding-name-error'),
                  readOnly: alreadyCreated,
                  helper: Text(
                    localizations.onboardingNameChangeLaterReassurance,
                    key: const Key('onboarding-name-reassurance'),
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(SgartShapes.cardPadding),
              child: SgartButton(
                key: const Key('onboarding-name-next-button'),
                label: localizations.onboardingNextButtonLabel,
                onPressed: isSubmitting
                    ? null
                    : alreadyCreated
                        ? onAdvance
                        : () => context.read<CreateHouseholdCubit>().submit(controller.text),
              ),
            ),
          ],
        );
      },
    );
  }
}

/// Step 2 — add stores (optional, skippable). Mounts the shared [StoresManagementView] over a
/// [StoresCubit] scoped to the just-created household — the exact reusable creation path 1.8 built
/// (AC4). „Weiter" and „Überspringen" both advance; added stores are already persisted by the cubit.
class _StoresStep extends StatelessWidget {
  const _StoresStep({required this.household, required this.onNext, required this.onBack});

  final HouseholdSummary household;
  final VoidCallback onNext;
  final VoidCallback onBack;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return BlocProvider(
      create: (_) => StoresCubit(
        storesApi: context.read<StoresApi>(),
        referenceCache: context.read<StoreChainReferenceCache>(),
        householdId: household.householdId,
      )..bootstrap(),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _OnboardingStepHeader(
            step: _OnboardingStep.stores,
            title: localizations.onboardingStoresStepTitle,
            help: localizations.onboardingStoresStepHelp,
            onBack: onBack,
          ),
          const Expanded(child: StoresManagementView()),
          Padding(
            padding: const EdgeInsets.all(SgartShapes.cardPadding),
            child: Column(
              children: [
                SgartButton(
                  key: const Key('onboarding-stores-next-button'),
                  label: localizations.onboardingNextButtonLabel,
                  onPressed: onNext,
                ),
                const SizedBox(height: SgartShapes.space2),
                SgartButton(
                  key: const Key('onboarding-stores-skip-button'),
                  label: localizations.onboardingStoresSkipButtonLabel,
                  variant: SgartButtonVariant.tonal,
                  onPressed: onNext,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// Step 3 — invite (optional). The send is **deferred to Epic 4** (AC4, Clarification 1 Option A):
/// „Einladung senden" is disabled with a „folgt später" note, and only „Später einladen — fertig"
/// finishes onboarding. The email field is inert here — its text is never stored, logged, or sent
/// (there is no invite backend until Story 4.1), so no controller is wired to it.
class _InviteStep extends StatelessWidget {
  const _InviteStep({required this.onFinish, required this.onBack});

  final VoidCallback onFinish;
  final VoidCallback onBack;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _OnboardingStepHeader(
          step: _OnboardingStep.invite,
          title: localizations.onboardingInviteStepTitle,
          help: localizations.onboardingInviteStepHelp,
          onBack: onBack,
        ),
        Expanded(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(SgartShapes.cardPadding),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                TextField(
                  key: const Key('onboarding-invite-email-field'),
                  keyboardType: TextInputType.emailAddress,
                  decoration: InputDecoration(labelText: localizations.onboardingInviteEmailFieldLabel),
                ),
                const SizedBox(height: SgartShapes.space2),
                Text(
                  localizations.onboardingInvitePrivacyNote,
                  key: const Key('onboarding-invite-privacy'),
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                const SizedBox(height: SgartShapes.space4),
                // Send is disabled — invite creation ships in Epic 4 (Story 4.1). The null callback
                // renders SgartButton in its disabled treatment.
                SgartButton(
                  key: const Key('onboarding-invite-send-button'),
                  label: localizations.onboardingInviteSendButtonLabel,
                  onPressed: null,
                ),
                const SizedBox(height: SgartShapes.space2),
                Text(
                  localizations.onboardingInviteDeferredNote,
                  key: const Key('onboarding-invite-deferred-note'),
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
            ),
          ),
        ),
        Padding(
          padding: const EdgeInsets.all(SgartShapes.cardPadding),
          child: SgartButton(
            key: const Key('onboarding-invite-finish-button'),
            label: localizations.onboardingInviteFinishButtonLabel,
            onPressed: onFinish,
          ),
        ),
      ],
    );
  }
}
