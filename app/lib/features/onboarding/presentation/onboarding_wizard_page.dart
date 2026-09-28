import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../../consent/presentation/consent_cubit.dart';
import '../../households/data/household_summary.dart';
import '../../households/data/households_api.dart';
import '../../households/presentation/create_household_cubit.dart';
import '../../households/presentation/create_household_name_field.dart';
import '../../households/presentation/create_household_state.dart';
import '../../households/presentation/households_cubit.dart';
import '../../invites/data/invites_api.dart';
import '../../invites/presentation/invites_cubit.dart';
import '../../invites/presentation/invites_view.dart';
import '../../settings/data/nickname_api.dart';
import '../../settings/presentation/nickname_cubit.dart';
import '../../settings/presentation/nickname_field.dart';
import '../../settings/presentation/nickname_state.dart';
import '../../stores/data/store_chain_reference_cache.dart';
import '../../stores/data/stores_api.dart';
import '../../stores/presentation/stores_cubit.dart';
import '../../stores/presentation/stores_management_view.dart';

/// The four wizard steps (Story 1.9, AC1; nickname added Story 8.3). The welcome/choice (frame 1 of
/// the mockup) stays the `CreateOrAwaitChoicePage` that launches this wizard, so the counted steps
/// are name → nickname → stores → invite. `_OnboardingStep.index + 1` is the 1-based step number
/// shown to the person.
enum _OnboardingStep { name, nickname, stores, invite }

/// The gentle, one-step-at-a-time onboarding wizard for a person creating their first household
/// (Story 1.9, UX-DR10 — the Werner path). It **reuses** the shipped paths rather than reinventing
/// them: the name step drives the Story 1.6 [CreateHouseholdCubit]; the stores step mounts the Story
/// 1.8 [StoresManagementView]/[StoresCubit] (the reusable creation path 1.8's AC4 was built for); and
/// finishing lands in the created household via [HouseholdsCubit.selectHousehold] (read-your-writes,
/// AC2), the same transition the minimal create page performs.
///
/// The invite step shows the household's single active invite code/link, created together with the
/// household itself (Story 8.4) — no separate create action, just share/copy (embeds the shared
/// [InvitesView]); „Später einladen — fertig" still finishes onboarding regardless — solo remains
/// first-class (AC7, unchanged from AC4/Clarification 1).
///
/// Reached as a pushed route above the `FirstRunRouter` providers, so its dependencies
/// ([HouseholdsApi], [HouseholdsCubit], [StoresApi], [StoreChainReferenceCache], [InvitesApi],
/// [NicknameApi]) are re-provided by value at the push site (the Story 1.6
/// `ProviderNotFoundException` lesson).
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
  final TextEditingController _nicknameController = TextEditingController();
  late final NicknameCubit _nicknameCubit;
  _OnboardingStep _step = _OnboardingStep.name;
  HouseholdSummary? _createdHousehold;
  bool _isNicknameSet = false;

  @override
  void initState() {
    super.initState();
    // Built eagerly here (not lazily on first access) — a wizard session that never reaches the
    // nickname step (e.g. a rejected name) must still be safe to dispose: a lazy `late final`
    // initializer reading `context` for the first time from `dispose()` hits a deactivated element.
    _nicknameCubit = NicknameCubit(nicknameApi: context.read<NicknameApi>());
  }

  @override
  void dispose() {
    _nameController.dispose();
    _nicknameController.dispose();
    _nicknameCubit.close();
    super.dispose();
  }

  void _onHouseholdCreated(HouseholdSummary household) {
    setState(() {
      _createdHousehold = household;
      _step = _OnboardingStep.nickname;
    });
  }

  void _goToStep(_OnboardingStep step) => setState(() => _step = step);

  void _onNicknameSet() {
    _isNicknameSet = true;
    _goToStep(_OnboardingStep.stores);
  }

  /// Any exit once the household exists lands the person in it (Clarification 2, AC2) — but only
  /// after the required nickname is set (Story 8.3): until then, an exit returns to the nickname
  /// step instead of entering the household unnamed.
  void _exitCreatedHousehold() {
    if (_isNicknameSet) {
      _finish();
    } else {
      _goToStep(_OnboardingStep.nickname);
    }
  }

  /// „Zurück" on the name step. Before the household exists this pops back to the first-run choice
  /// (nothing was created). Once it exists, the wizard must never strand it — see
  /// [_exitCreatedHousehold].
  void _handleNameBack() {
    if (_createdHousehold != null) {
      _exitCreatedHousehold();
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
    // land in the created household instead (Clarification 2, AC2), once the nickname is set.
    return PopScope(
      canPop: _createdHousehold == null,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) {
          _exitCreatedHousehold();
        }
      },
      child: Scaffold(
        appBar: const SgartAppBar(title: 'SGART'),
        body: SafeArea(
          child: BlocListener<CreateHouseholdCubit, CreateHouseholdState>(
            listenWhen: (previous, current) =>
                current.status == CreateHouseholdStatus.success ||
                (current.status == CreateHouseholdStatus.failure && current.error?.code == 'consent.required'),
            listener: (context, state) {
              if (state.status == CreateHouseholdStatus.success) {
                _onHouseholdCreated(state.household!);
              } else {
                // Story 7.4, AC3: a stale client (consent recorded locally but not server-side, or
                // a notice-version bump since this screen loaded) is rejected 409 consent.required.
                // Never show this as a generic inline error — reload the gate's status (guarded:
                // present only when reached through the real ConsentGatedChoicePage ancestor, see
                // CreateOrAwaitChoicePage._openOnboarding) and pop back to the first-run gateway,
                // which now renders ConsentGatePage again instead of the choice screen.
                tryReadConsentCubit(context)?.load();
                Navigator.of(context).popUntil((route) => route.isFirst);
              }
            },
            child: switch (_step) {
              _OnboardingStep.name => _NameStep(
                  controller: _nameController,
                  onBack: _handleNameBack,
                  alreadyCreated: _createdHousehold != null,
                  onAdvance: () => _goToStep(_OnboardingStep.nickname),
                ),
              _OnboardingStep.nickname => BlocProvider<NicknameCubit>.value(
                  value: _nicknameCubit,
                  child: _NicknameStep(
                    household: _createdHousehold!,
                    controller: _nicknameController,
                    onNext: _onNicknameSet,
                    onBack: () => _goToStep(_OnboardingStep.name),
                  ),
                ),
              _OnboardingStep.stores => _StoresStep(
                  household: _createdHousehold!,
                  onNext: () => _goToStep(_OnboardingStep.invite),
                  onBack: () => _goToStep(_OnboardingStep.nickname),
                ),
              _OnboardingStep.invite => _InviteStep(
                  household: _createdHousehold!,
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

    return Padding(
      // Horizontal + top only: the content below already carries its own top padding (the
      // scroll view's EdgeInsets.all), so a bottom inset here would double that gap.
      padding: const EdgeInsets.fromLTRB(
        SgartShapes.cardPadding,
        SgartShapes.cardPadding,
        SgartShapes.cardPadding,
        0,
      ),
      child: Column(
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
      ),
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

/// Step 2 — the required nickname (Story 8.3): „Wie möchtest du genannt werden?" gates advancing —
/// the household already exists at this point, so the `PUT .../nickname` call has somewhere to
/// write. A rejected nickname (blank/too long, client- or server-side) shows inline without leaving
/// the step, mirroring [_NameStep].
class _NicknameStep extends StatelessWidget {
  const _NicknameStep({
    required this.household,
    required this.controller,
    required this.onNext,
    required this.onBack,
  });

  final HouseholdSummary household;
  final TextEditingController controller;
  final VoidCallback onNext;
  final VoidCallback onBack;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return BlocListener<NicknameCubit, NicknameState>(
      listenWhen: (previous, current) => current.status == NicknameStatus.success,
      listener: (context, state) => onNext(),
      child: BlocBuilder<NicknameCubit, NicknameState>(
        builder: (context, state) {
          final isSubmitting = state.status == NicknameStatus.submitting;
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _OnboardingStepHeader(
                step: _OnboardingStep.nickname,
                title: localizations.onboardingNicknameStepTitle,
                help: localizations.onboardingNicknameStepHelp,
                onBack: onBack,
              ),
              Expanded(
                child: SingleChildScrollView(
                  padding: const EdgeInsets.all(SgartShapes.cardPadding),
                  child: NicknameField(
                    controller: controller,
                    fieldKey: const Key('onboarding-nickname-field'),
                    errorKey: const Key('onboarding-nickname-error'),
                  ),
                ),
              ),
              Padding(
                padding: const EdgeInsets.all(SgartShapes.cardPadding),
                child: SgartButton(
                  key: const Key('onboarding-nickname-next-button'),
                  label: localizations.onboardingNextButtonLabel,
                  onPressed: isSubmitting
                      ? null
                      : () => context.read<NicknameCubit>().submit(household.householdId, controller.text),
                ),
              ),
            ],
          );
        },
      ),
    );
  }
}

/// Step 3 — add stores (optional, skippable). Mounts the shared [StoresManagementView] over a
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
              crossAxisAlignment: CrossAxisAlignment.stretch,
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

/// Step 4 — invite (optional, Story 4.1/8.4): shows the household's already-active code/link, ready
/// to share immediately. „Später einladen — fertig" still finishes onboarding regardless — solo
/// remains first-class (AC7, unchanged).
class _InviteStep extends StatelessWidget {
  const _InviteStep({required this.household, required this.onFinish, required this.onBack});

  final HouseholdSummary household;
  final VoidCallback onFinish;
  final VoidCallback onBack;

  @override
  Widget build(BuildContext context) {
    return BlocProvider(
      create: (_) =>
          InvitesCubit(invitesApi: context.read<InvitesApi>(), householdId: household.householdId)..bootstrap(),
      child: _InviteStepBody(onFinish: onFinish, onBack: onBack),
    );
  }
}

class _InviteStepBody extends StatelessWidget {
  const _InviteStepBody({required this.onFinish, required this.onBack});

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
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: SgartShapes.cardPadding),
          child: Text(
            localizations.onboardingInvitePrivacyNote,
            key: const Key('onboarding-invite-privacy'),
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ),
        // Reuses the shared invite body (Story 8.4) exactly as the manage-household hub's
        // InvitePage does — the household's single active code, share/copy, no create/list — so
        // onboarding never re-implements the invite-code display.
        const Expanded(child: InvitesView()),
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
