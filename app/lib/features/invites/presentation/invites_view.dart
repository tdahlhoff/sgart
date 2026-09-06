import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/errors/error_message_resolver.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../data/pending_invite.dart';
import 'invites_cubit.dart';
import 'invites_state.dart';

/// The reusable invite body (Story 4.1, AC7): an email field that sends a real invite, inline
/// error surfacing for `409`/`400`, and the minimal pending-invites list (date + inviter + status —
/// **no email**, privacy-first, AD-6). Reads its [InvitesCubit] from the enclosing provider, so any
/// host that provides one can embed it — the onboarding wizard's invite step and the
/// manage-household hub's invite page both mount this same view (mirrors `StoresManagementView`).
class InvitesView extends StatefulWidget {
  const InvitesView({super.key});

  @override
  State<InvitesView> createState() => _InvitesViewState();
}

class _InvitesViewState extends State<InvitesView> {
  final TextEditingController _emailController = TextEditingController();

  @override
  void dispose() {
    _emailController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final cubit = context.read<InvitesCubit>();
    await cubit.sendInvite(_emailController.text);
    if (!mounted) {
      return;
    }
    if (cubit.state.actionError == null && !cubit.state.isSubmitting) {
      _emailController.clear();
    }
  }

  @override
  Widget build(BuildContext context) {
    return BlocBuilder<InvitesCubit, InvitesState>(
      builder: (context, state) {
        return switch (state.status) {
          InvitesStatus.loading =>
            const Center(child: CircularProgressIndicator(key: Key('invites-loading'))),
          InvitesStatus.failure => const _FailureBody(),
          InvitesStatus.ready => _ReadyBody(state: state, emailController: _emailController, onSubmit: _submit),
        };
      },
    );
  }
}

class _ReadyBody extends StatelessWidget {
  const _ReadyBody({required this.state, required this.emailController, required this.onSubmit});

  final InvitesState state;
  final TextEditingController emailController;
  final Future<void> Function() onSubmit;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return SingleChildScrollView(
      padding: const EdgeInsets.all(SgartShapes.cardPadding),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TextField(
            key: const Key('invite-email-field'),
            controller: emailController,
            keyboardType: TextInputType.emailAddress,
            decoration: InputDecoration(labelText: localizations.invitesEmailFieldLabel),
          ),
          if (state.actionError != null) ...[
            const SizedBox(height: SgartShapes.space2),
            Text(
              localizedMessageForErrorCode(localizations, state.actionError!.code),
              key: const Key('invite-action-error'),
            ),
          ],
          const SizedBox(height: SgartShapes.space4),
          ValueListenableBuilder<TextEditingValue>(
            valueListenable: emailController,
            builder: (context, value, _) {
              final isBlank = value.text.trim().isEmpty;
              return SgartButton(
                key: const Key('invite-send-button'),
                label: localizations.invitesSendButtonLabel,
                onPressed: state.isSubmitting || isBlank ? null : () => onSubmit(),
              );
            },
          ),
          const Divider(height: SgartShapes.space4),
          Text(localizations.invitesPendingHeading, style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: SgartShapes.space2),
          if (state.invites.isEmpty)
            Text(localizations.invitesPendingEmptyStateLabel, key: const Key('invites-pending-empty-state'))
          else
            for (final invite in state.invites) _PendingInviteRow(invite: invite),
        ],
      ),
    );
  }
}

class _PendingInviteRow extends StatelessWidget {
  const _PendingInviteRow({required this.invite});

  final PendingInvite invite;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return ListTile(
      key: Key('invite-row-${invite.inviteId}'),
      contentPadding: EdgeInsets.zero,
      leading: const Icon(Icons.mail_outline),
      // No email shown — the read model carries none (AD-6, privacy-first).
      title: Text(localizations.invitesPendingRowLabel(invite.invitedAt)),
      subtitle: Text(_localizedInviteStatus(localizations, invite.status)),
    );
  }
}

/// Maps the read model's raw status ("PENDING"/"EXPIRED") to German copy — the backend enum name
/// must never leak into an otherwise-German UI. Falls back to the raw value for any status this
/// catalog does not (yet) recognise, mirroring [localizedMessageForErrorCode]'s fallback shape.
String _localizedInviteStatus(AppLocalizations localizations, String status) {
  return switch (status) {
    'PENDING' => localizations.invitesStatusPending,
    'EXPIRED' => localizations.invitesStatusExpired,
    _ => status,
  };
}

class _FailureBody extends StatelessWidget {
  const _FailureBody();

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Center(
      child: Padding(
        padding: const EdgeInsets.all(SgartShapes.cardPadding),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(localizations.errorGenericFallback, key: const Key('invites-load-error')),
            const SizedBox(height: SgartShapes.space4),
            SgartButton(
              key: const Key('invites-retry-button'),
              label: localizations.householdsRetryButtonLabel,
              onPressed: () => context.read<InvitesCubit>().bootstrap(),
            ),
          ],
        ),
      ),
    );
  }
}
