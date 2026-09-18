import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:share_plus/share_plus.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/errors/error_message_resolver.dart';
import '../../../shared/http/invite_link_config.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../data/invite_link.dart';
import '../data/pending_invite.dart';
import 'invites_cubit.dart';
import 'invites_state.dart';

/// The reusable invite body (Story 7.5, AC1, AC6): a create-invite action that, on success, shows
/// the freshly created invite's **join code** and **link** — each shareable via the OS share sheet
/// (`share_plus`) and copyable — plus the minimal pending-invites list (date + inviter + status —
/// **no email**, privacy-first, AD-6). Reads its [InvitesCubit] from the enclosing provider, so any
/// host that provides one can embed it — the onboarding wizard's invite step and the
/// manage-household hub's invite page both mount this same view (mirrors `StoresManagementView`).
class InvitesView extends StatelessWidget {
  const InvitesView({super.key});

  @override
  Widget build(BuildContext context) {
    return BlocBuilder<InvitesCubit, InvitesState>(
      builder: (context, state) {
        return switch (state.status) {
          InvitesStatus.loading =>
            const Center(child: CircularProgressIndicator(key: Key('invites-loading'))),
          InvitesStatus.failure => const _FailureBody(),
          InvitesStatus.ready => _ReadyBody(state: state, householdId: context.read<InvitesCubit>().householdId),
        };
      },
    );
  }
}

class _ReadyBody extends StatelessWidget {
  const _ReadyBody({required this.state, required this.householdId});

  final InvitesState state;
  final String householdId;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final lastCreatedInviteId = state.lastCreatedInviteId;

    return SingleChildScrollView(
      padding: const EdgeInsets.all(SgartShapes.cardPadding),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SgartButton(
            key: const Key('invite-create-button'),
            label: localizations.invitesCreateButtonLabel,
            onPressed: state.isSubmitting ? null : () => context.read<InvitesCubit>().createInvite(),
          ),
          if (state.actionError != null) ...[
            const SizedBox(height: SgartShapes.space2),
            Text(
              localizedMessageForErrorCode(localizations, state.actionError!.code),
              key: const Key('invite-action-error'),
            ),
          ],
          if (lastCreatedInviteId != null) ...[
            const SizedBox(height: SgartShapes.space4),
            _CreatedInviteCard(householdId: householdId, inviteId: lastCreatedInviteId),
          ],
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

/// The freshly created invite's two shareable representations (Story 7.5, AC1, D-A): the **join
/// code** (`householdId:inviteId`, [InviteLink.codeFor]) and the **link**
/// (`<base-url>?h=<householdId>&i=<inviteId>`, [InviteLink.linkFor]) — the same shapes the backend
/// and [InviteLink.tryParse] already agree on. Each has its own share and copy action.
class _CreatedInviteCard extends StatelessWidget {
  const _CreatedInviteCard({required this.householdId, required this.inviteId});

  final String householdId;
  final String inviteId;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final code = InviteLink.codeFor(householdId: householdId, inviteId: inviteId);
    final link = InviteLink.linkFor(baseUrl: InviteLinkConfig.baseUrl, householdId: householdId, inviteId: inviteId);

    return Card(
      key: const Key('invite-created-card'),
      child: Padding(
        padding: const EdgeInsets.all(SgartShapes.cardPadding),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(localizations.invitesCreatedHeading, style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: SgartShapes.space2),
            _ShareableRow(
              key: const Key('invite-code-row'),
              label: localizations.invitesCodeLabel,
              value: code,
              shareLabel: localizations.invitesShareCodeButtonLabel,
              copyKey: const Key('invite-code-copy-button'),
              shareKey: const Key('invite-code-share-button'),
              copiedMessage: localizations.invitesCopiedSnackBar,
            ),
            const SizedBox(height: SgartShapes.space2),
            _ShareableRow(
              key: const Key('invite-link-row'),
              label: localizations.invitesLinkLabel,
              value: link,
              shareLabel: localizations.invitesShareLinkButtonLabel,
              copyKey: const Key('invite-link-copy-button'),
              shareKey: const Key('invite-link-share-button'),
              copiedMessage: localizations.invitesCopiedSnackBar,
            ),
          ],
        ),
      ),
    );
  }
}

class _ShareableRow extends StatelessWidget {
  const _ShareableRow({
    super.key,
    required this.label,
    required this.value,
    required this.shareLabel,
    required this.copyKey,
    required this.shareKey,
    required this.copiedMessage,
  });

  final String label;
  final String value;
  final String shareLabel;
  final Key copyKey;
  final Key shareKey;
  final String copiedMessage;

  Future<void> _copy(BuildContext context) async {
    await Clipboard.setData(ClipboardData(text: value));
    if (!context.mounted) {
      return;
    }
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(copiedMessage)));
  }

  Future<void> _share() => SharePlus.instance.share(ShareParams(text: value));

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: Theme.of(context).textTheme.labelMedium),
        const SizedBox(height: SgartShapes.spaceUnit),
        SelectableText(value),
        const SizedBox(height: SgartShapes.spaceUnit),
        Row(
          children: [
            Expanded(
              child: SgartButton(
                key: shareKey,
                label: shareLabel,
                onPressed: _share,
              ),
            ),
            const SizedBox(width: SgartShapes.space2),
            IconButton(
              key: copyKey,
              icon: const Icon(Icons.copy),
              tooltip: AppLocalizations.of(context).invitesCopyButtonLabel,
              onPressed: () => _copy(context),
            ),
          ],
        ),
      ],
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
