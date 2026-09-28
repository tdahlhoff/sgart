import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/errors/error_message_resolver.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../../consent/presentation/consent_cubit.dart';
import '../../invites/data/invite_link.dart';
import '../../invites/data/invites_api.dart';
import '../../invites/presentation/accept_invite_cubit.dart';
import '../../invites/presentation/accept_invite_state.dart';
import '../../settings/data/nickname_api.dart';
import '../../settings/presentation/nickname_cubit.dart';
import '../../settings/presentation/nickname_field.dart';
import '../../settings/presentation/nickname_state.dart';
import 'households_cubit.dart';

/// Pushes [AwaitInvitePage] with the `InvitesApi`/`HouseholdsCubit` re-provided across the pushed
/// route boundary (the `ProviderNotFoundException` lesson, Story 4.2) — the one call site both
/// [CreateOrAwaitChoicePage]'s manual "I have an invite" choice and [FirstRunRouterBody]'s Story
/// 4.6 deep-link routing use, so the two entry points can never drift into separate accept paths
/// (AC3, DRY). Also re-provides the ancestor [ConsentCubit] when one exists (mirrors
/// `CreateOrAwaitChoicePage._openOnboarding`), so a stale-client `409 consent.required` on accept
/// can reload the gate and pop back to it (Story 7.4 review) — guarded-optional because a
/// standalone test harness, or the already-gated deep-link path with no `ConsentCubit` ancestor,
/// must not crash.
void openAwaitInvitePage(BuildContext context, {InviteLink? initialLink}) {
  final invitesApi = context.read<InvitesApi>();
  final householdsCubit = context.read<HouseholdsCubit>();
  final nicknameApi = context.read<NicknameApi>();
  final consentCubit = tryReadConsentCubit(context);
  Navigator.of(context).push(
    MaterialPageRoute(
      builder: (_) => MultiRepositoryProvider(
        providers: [
          RepositoryProvider<InvitesApi>.value(value: invitesApi),
          // The required nickname step after a successful join reads this (Story 8.3).
          RepositoryProvider<NicknameApi>.value(value: nicknameApi),
        ],
        child: BlocProvider<HouseholdsCubit>.value(
          value: householdsCubit,
          child: consentCubit == null
              ? AwaitInvitePage(initialLink: initialLink)
              : BlocProvider<ConsentCubit>.value(
                  value: consentCubit,
                  child: AwaitInvitePage(initialLink: initialLink),
                ),
        ),
      ),
    ),
  );
}

/// The accept-invite screen (Story 4.2, AC6, Epic-1 retro Action 5): the invitee pastes their
/// personal invite link/code and joins the household. Replaces the informational dead-end that
/// stood here since Story 1.9. Also the Story 4.6 routing target for the OS deep link and the
/// "I have an invite" choice: when [initialLink] is given (the deep-link/pending-link case, AC1/
/// AC3), the field is pre-filled and the join is triggered automatically — the same
/// `AcceptInviteCubit`/`AcceptInvite` path either way (DRY).
class AwaitInvitePage extends StatelessWidget {
  const AwaitInvitePage({super.key, this.initialLink});

  final InviteLink? initialLink;

  @override
  Widget build(BuildContext context) {
    return BlocProvider(
      create: (_) => AcceptInviteCubit(invitesApi: context.read<InvitesApi>()),
      child: _AwaitInviteView(initialLink: initialLink),
    );
  }
}

class _AwaitInviteView extends StatefulWidget {
  const _AwaitInviteView({this.initialLink});

  final InviteLink? initialLink;

  @override
  State<_AwaitInviteView> createState() => _AwaitInviteViewState();
}

class _AwaitInviteViewState extends State<_AwaitInviteView> {
  late final _linkController = TextEditingController(text: _rawFormOf(widget.initialLink));
  late final NicknameCubit _nicknameCubit;
  final _nicknameController = TextEditingController();

  /// Set once the invite is redeemed (Story 8.3): the join flow's own required nickname step then
  /// replaces the link form — mirrors the onboarding wizard's stores/invite steps gating on
  /// `_createdHousehold`. Finishing/bootstrap is deferred until the nickname is set (I/O matrix:
  /// "Same required nickname step gates the join").
  String? _joinedHouseholdId;

  static String? _rawFormOf(InviteLink? link) => link == null ? null : '${link.householdId}:${link.inviteId}';

  @override
  void initState() {
    super.initState();
    // Built eagerly here (not lazily on first access) — see the mirrored comment in
    // OnboardingWizardPage: a lazy `late final` initializer reading `context` for the first time
    // from `dispose()` (a session that never reaches the nickname step) hits a deactivated element.
    _nicknameCubit = NicknameCubit(nicknameApi: context.read<NicknameApi>());
    final link = widget.initialLink;
    if (link != null) {
      // Deferred to the first frame: `context.read<AcceptInviteCubit>()` needs the BlocProvider
      // ancestor built by AwaitInvitePage, which is not yet mounted while this State initializes.
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) {
          context.read<AcceptInviteCubit>().accept(_rawFormOf(link)!);
        }
      });
    }
  }

  @override
  void dispose() {
    _linkController.dispose();
    _nicknameController.dispose();
    _nicknameCubit.close();
    super.dispose();
  }

  /// Finishes the join: read-your-writes bootstrap + pop back to the first-run router root — the
  /// same landing the onboarding wizard's `_finish` performs.
  void _finishJoin() {
    context.read<HouseholdsCubit>().bootstrap();
    Navigator.of(context).popUntil((route) => route.isFirst);
  }

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final joinedHouseholdId = _joinedHouseholdId;
    if (joinedHouseholdId != null) {
      // The membership already exists here, so leaving (system/app-bar back) would drop the person
      // on the first-run choice, unnamed and without the bootstrap — the nickname step is required
      // and only its own submit finishes the join.
      return PopScope(
        canPop: false,
        child: BlocProvider<NicknameCubit>.value(
          value: _nicknameCubit,
          child: _JoinNicknameStep(
            householdId: joinedHouseholdId,
            controller: _nicknameController,
            onFinish: _finishJoin,
          ),
        ),
      );
    }

    return BlocListener<AcceptInviteCubit, AcceptInviteState>(
      listener: (context, state) {
        if (state.status == AcceptInviteStatus.success) {
          setState(() => _joinedHouseholdId = state.householdId);
        } else if (state.status == AcceptInviteStatus.failure && state.error?.code == 'consent.required') {
          // Story 7.4 review: a stale client (consent recorded locally but not server-side, or a
          // notice-version bump since this screen was reached) is rejected 409 consent.required.
          // Never show this as a generic inline error — reload the gate's status when reachable
          // (guarded-optional, see openAwaitInvitePage) and pop back so the gate is shown again
          // (mirrors OnboardingWizardPage's create-side recovery).
          tryReadConsentCubit(context)?.load();
          Navigator.of(context).popUntil((route) => route.isFirst);
        }
      },
      child: Scaffold(
        appBar: const SgartAppBar(title: 'SGART'),
        body: SafeArea(
          child: BlocBuilder<AcceptInviteCubit, AcceptInviteState>(
            builder: (context, state) {
              final isSubmitting = state.status == AcceptInviteStatus.submitting;
              return Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Padding(
                    padding: SgartShapes.screenHeaderPadding,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Text(
                          localizations.householdsAwaitInviteHeading,
                          style: Theme.of(context).textTheme.headlineSmall,
                          textAlign: TextAlign.center,
                        ),
                        const SizedBox(height: SgartShapes.headingGap),
                        Text(localizations.householdsAwaitInviteBody, textAlign: TextAlign.center),
                      ],
                    ),
                  ),
                  Expanded(
                    child: SingleChildScrollView(
                      padding: const EdgeInsets.all(SgartShapes.cardPadding),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          TextField(
                            key: const Key('await-invite-link-field'),
                            controller: _linkController,
                            decoration:
                                InputDecoration(labelText: localizations.householdsAwaitInviteLinkFieldLabel),
                          ),
                          if (state.status == AcceptInviteStatus.failure && state.error != null) ...[
                            const SizedBox(height: SgartShapes.space2),
                            Text(
                              localizedMessageForErrorCode(localizations, state.error!.code),
                              key: const Key('await-invite-error'),
                              textAlign: TextAlign.center,
                            ),
                          ],
                        ],
                      ),
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.all(SgartShapes.cardPadding),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        SgartButton(
                          key: const Key('await-invite-join-button'),
                          label: localizations.householdsAwaitInviteJoinButtonLabel,
                          onPressed: isSubmitting
                              ? null
                              : () => context.read<AcceptInviteCubit>().accept(_linkController.text),
                        ),
                        const SizedBox(height: SgartShapes.space2),
                        SgartButton(
                          key: const Key('await-invite-back-button'),
                          label: localizations.householdsBackButtonLabel,
                          variant: SgartButtonVariant.secondary,
                          onPressed: () => Navigator.of(context).pop(),
                        ),
                      ],
                    ),
                  ),
                ],
              );
            },
          ),
        ),
      ),
    );
  }
}

/// The join flow's required nickname step (Story 8.3): shown once the invite is redeemed, before
/// the person actually lands in the household — mirrors the onboarding wizard's step chrome
/// without the multi-step progress indicator (a join is a single gate, not a multi-step wizard).
class _JoinNicknameStep extends StatelessWidget {
  const _JoinNicknameStep({required this.householdId, required this.controller, required this.onFinish});

  final String householdId;
  final TextEditingController controller;
  final VoidCallback onFinish;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return BlocListener<NicknameCubit, NicknameState>(
      listenWhen: (previous, current) => current.status == NicknameStatus.success,
      listener: (context, state) => onFinish(),
      child: Scaffold(
        appBar: const SgartAppBar(title: 'SGART'),
        body: SafeArea(
          child: BlocBuilder<NicknameCubit, NicknameState>(
            builder: (context, state) {
              final isSubmitting = state.status == NicknameStatus.submitting;
              return Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Padding(
                    padding: SgartShapes.screenHeaderPadding,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Text(
                          localizations.onboardingNicknameStepTitle,
                          style: Theme.of(context).textTheme.headlineSmall,
                          textAlign: TextAlign.center,
                        ),
                        const SizedBox(height: SgartShapes.headingGap),
                        Text(localizations.onboardingNicknameStepHelp, textAlign: TextAlign.center),
                      ],
                    ),
                  ),
                  Expanded(
                    child: SingleChildScrollView(
                      padding: const EdgeInsets.all(SgartShapes.cardPadding),
                      child: NicknameField(
                        controller: controller,
                        fieldKey: const Key('join-nickname-field'),
                        errorKey: const Key('join-nickname-error'),
                      ),
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.all(SgartShapes.cardPadding),
                    child: SgartButton(
                      key: const Key('join-nickname-submit-button'),
                      label: localizations.onboardingNextButtonLabel,
                      onPressed: isSubmitting
                          ? null
                          : () => context.read<NicknameCubit>().submit(householdId, controller.text),
                    ),
                  ),
                ],
              );
            },
          ),
        ),
      ),
    );
  }
}
