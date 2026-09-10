import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/errors/error_message_resolver.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../../invites/data/invite_link.dart';
import '../../invites/data/invites_api.dart';
import '../../invites/presentation/accept_invite_cubit.dart';
import '../../invites/presentation/accept_invite_state.dart';
import 'households_cubit.dart';

/// Pushes [AwaitInvitePage] with the `InvitesApi`/`HouseholdsCubit` re-provided across the pushed
/// route boundary (the `ProviderNotFoundException` lesson, Story 4.2) — the one call site both
/// [CreateOrAwaitChoicePage]'s manual "I have an invite" choice and [FirstRunRouterBody]'s Story
/// 4.6 deep-link routing use, so the two entry points can never drift into separate accept paths
/// (AC3, DRY).
void openAwaitInvitePage(BuildContext context, {InviteLink? initialLink}) {
  final invitesApi = context.read<InvitesApi>();
  final householdsCubit = context.read<HouseholdsCubit>();
  Navigator.of(context).push(
    MaterialPageRoute(
      builder: (_) => RepositoryProvider<InvitesApi>.value(
        value: invitesApi,
        child: BlocProvider<HouseholdsCubit>.value(
          value: householdsCubit,
          child: AwaitInvitePage(initialLink: initialLink),
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

  static String? _rawFormOf(InviteLink? link) => link == null ? null : '${link.householdId}:${link.inviteId}';

  @override
  void initState() {
    super.initState();
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
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return BlocListener<AcceptInviteCubit, AcceptInviteState>(
      listener: (context, state) {
        if (state.status == AcceptInviteStatus.success) {
          // Read-your-writes: the household appears via the Identity ACL's member-mapping written
          // by mint, without waiting on the projector (Dev Notes — mirrors CreateHousehold's
          // route-on-response approach).
          context.read<HouseholdsCubit>().bootstrap();
          Navigator.of(context).popUntil((route) => route.isFirst);
        }
      },
      child: Scaffold(
        appBar: const SgartAppBar(title: 'SGART'),
        body: SafeArea(
          child: BlocBuilder<AcceptInviteCubit, AcceptInviteState>(
            builder: (context, state) {
              final isSubmitting = state.status == AcceptInviteStatus.submitting;
              return SingleChildScrollView(
                padding: const EdgeInsets.all(SgartShapes.cardPadding),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    Text(localizations.householdsAwaitInviteHeading),
                    const SizedBox(height: SgartShapes.headingGap),
                    Text(localizations.householdsAwaitInviteBody, textAlign: TextAlign.center),
                    const SizedBox(height: SgartShapes.space4),
                    TextField(
                      key: const Key('await-invite-link-field'),
                      controller: _linkController,
                      decoration: InputDecoration(labelText: localizations.householdsAwaitInviteLinkFieldLabel),
                    ),
                    if (state.status == AcceptInviteStatus.failure && state.error != null) ...[
                      const SizedBox(height: SgartShapes.space2),
                      Text(
                        localizedMessageForErrorCode(localizations, state.error!.code),
                        key: const Key('await-invite-error'),
                      ),
                    ],
                    const SizedBox(height: SgartShapes.space4),
                    SgartButton(
                      key: const Key('await-invite-join-button'),
                      label: localizations.householdsAwaitInviteJoinButtonLabel,
                      onPressed:
                          isSubmitting ? null : () => context.read<AcceptInviteCubit>().accept(_linkController.text),
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
              );
            },
          ),
        ),
      ),
    );
  }
}
