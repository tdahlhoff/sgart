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
import 'invites_cubit.dart';
import 'invites_state.dart';

/// The reusable invite body (Story 8.4): shows the household's single active invite **code** and
/// **link** — each shareable via the OS share sheet (`share_plus`) and copyable — and, for an
/// Admin, a „Code ersetzen" action that invalidates the old code and issues a fresh one (behind a
/// confirmation dialog). No create button, no list of codes (F7). Reads its [InvitesCubit] from
/// the enclosing provider, so any host that provides one can embed it — the household switcher's
/// promoted „Mitglieder einladen" row and the manage-household hub's „Einladen" row both open the
/// same screen (mirrors `StoresManagementView`).
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
    final code = InviteLink.codeFor(householdId: householdId, inviteId: state.inviteId);
    final link =
        InviteLink.linkFor(baseUrl: InviteLinkConfig.baseUrl, householdId: householdId, inviteId: state.inviteId);

    return SingleChildScrollView(
      padding: const EdgeInsets.all(SgartShapes.cardPadding),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (state.actionError != null) ...[
            Text(
              localizedMessageForErrorCode(localizations, state.actionError!.code),
              key: const Key('invite-action-error'),
            ),
            const SizedBox(height: SgartShapes.space4),
          ],
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
          if (state.canReplace) ...[
            const SizedBox(height: SgartShapes.space4),
            SgartButton(
              key: const Key('invite-replace-button'),
              label: localizations.invitesReplaceCodeButtonLabel,
              variant: SgartButtonVariant.secondary,
              onPressed: state.isSubmitting ? null : () => _confirmAndReplace(context),
            ),
          ],
        ],
      ),
    );
  }

  Future<void> _confirmAndReplace(BuildContext context) async {
    final localizations = AppLocalizations.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(localizations.invitesReplaceCodeConfirmTitle),
        content: Text(localizations.invitesReplaceCodeConfirmMessage),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: Text(localizations.membersCancelButtonLabel),
          ),
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: Text(localizations.membersConfirmButtonLabel),
          ),
        ],
      ),
    );
    if (confirmed == true && context.mounted) {
      await context.read<InvitesCubit>().replaceCode();
    }
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
