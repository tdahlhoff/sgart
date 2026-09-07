import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/errors/error_message_resolver.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../../households/data/household_summary.dart';
import '../../households/presentation/households_cubit.dart';
import '../../invites/data/pending_invite.dart';
import '../data/member_view.dart';
import 'members_cubit.dart';
import 'members_state.dart';

/// The member-management screen (Story 4.3, AC9): the roster (caller marked „Sie", others by
/// role). **Admin** sees per-member promote/demote/remove (with confirmations), a **revoke**
/// action on each pending invite, and a **delete-household** action behind a hard (type-to-confirm)
/// confirmation. **Participant** sees only „Haushalt verlassen". On a successful self-leave/removal
/// or household deletion, re-routes via `HouseholdsCubit.bootstrap()`.
class MembersPage extends StatelessWidget {
  const MembersPage({super.key, required this.household});

  final HouseholdSummary household;

  @override
  Widget build(BuildContext context) {
    return BlocProvider(
      create: (_) => MembersCubit(
        membersApi: context.read(),
        householdsApi: context.read(),
        invitesApi: context.read(),
        householdId: household.householdId,
      )..bootstrap(),
      child: _MembersView(household: household),
    );
  }
}

class _MembersView extends StatelessWidget {
  const _MembersView({required this.household});

  final HouseholdSummary household;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return BlocListener<MembersCubit, MembersState>(
      listenWhen: (previous, current) => !previous.exited && current.exited,
      listener: (context, state) {
        // Re-bootstrap the household list (the left/deleted household drops out) and pop back to
        // the shell, which re-routes on the next HouseholdsState emission (AC3, AC7).
        context.read<HouseholdsCubit>().bootstrap();
        Navigator.of(context).popUntil((route) => route.isFirst);
      },
      child: Scaffold(
        appBar: SgartAppBar(title: localizations.membersHeading),
        body: SafeArea(
          child: BlocBuilder<MembersCubit, MembersState>(
            builder: (context, state) {
              return switch (state.status) {
                MembersStatus.loading =>
                  const Center(child: CircularProgressIndicator(key: Key('members-loading'))),
                MembersStatus.failure => _FailureBody(errorText: localizations.membersLoadFailedError),
                MembersStatus.ready => _ReadyBody(state: state, household: household),
              };
            },
          ),
        ),
      ),
    );
  }
}

class _ReadyBody extends StatelessWidget {
  const _ReadyBody({required this.state, required this.household});

  final MembersState state;
  final HouseholdSummary household;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final self = state.self;
    final isAdmin = self?.role == 'ADMIN';

    return SingleChildScrollView(
      padding: const EdgeInsets.all(SgartShapes.cardPadding),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (state.actionError != null) ...[
            Text(
              localizedMessageForErrorCode(localizations, state.actionError!.code),
              key: const Key('members-action-error'),
            ),
            const SizedBox(height: SgartShapes.space4),
          ],
          for (final member in state.members) _MemberRow(member: member, isCallerAdmin: isAdmin),
          if (isAdmin) ...[
            const Divider(height: SgartShapes.space4),
            Text(localizations.invitesPendingHeading, style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: SgartShapes.space2),
            for (final invite in state.pendingInvites) _PendingInviteRow(invite: invite),
          ],
          const SizedBox(height: SgartShapes.space4),
          if (self != null && !isAdmin)
            SgartButton(
              key: const Key('members-leave-button'),
              label: localizations.membersLeaveAction,
              onPressed: state.isSubmitting ? null : () => _confirmAndLeave(context),
            ),
          if (isAdmin) ...[
            SgartButton(
              key: const Key('members-delete-household-button'),
              label: localizations.membersDeleteHouseholdAction,
              variant: SgartButtonVariant.secondary,
              onPressed: state.isSubmitting ? null : () => _confirmAndDeleteHousehold(context),
            ),
          ],
        ],
      ),
    );
  }

  Future<void> _confirmAndLeave(BuildContext context) async {
    final localizations = AppLocalizations.of(context);
    final confirmed = await _showYesNoDialog(
      context,
      title: localizations.membersLeaveConfirmTitle,
      message: localizations.membersLeaveConfirmMessage,
    );
    if (confirmed && context.mounted) {
      await context.read<MembersCubit>().leave();
    }
  }

  Future<void> _confirmAndDeleteHousehold(BuildContext context) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => _DeleteHouseholdConfirmDialog(householdName: household.name),
    );
    if (confirmed == true && context.mounted) {
      await context.read<MembersCubit>().deleteHousehold();
    }
  }
}

/// The hard, type-to-confirm delete-household dialog (AC7) — its own [StatefulWidget] so the
/// [TextEditingController] it owns is disposed by the normal widget lifecycle (on the dialog route's
/// own removal), never manually right after `showDialog` returns, which raced the dialog's still-
/// animating exit transition and threw "used after being disposed".
class _DeleteHouseholdConfirmDialog extends StatefulWidget {
  const _DeleteHouseholdConfirmDialog({required this.householdName});

  final String householdName;

  @override
  State<_DeleteHouseholdConfirmDialog> createState() => _DeleteHouseholdConfirmDialogState();
}

class _DeleteHouseholdConfirmDialogState extends State<_DeleteHouseholdConfirmDialog> {
  final TextEditingController _controller = TextEditingController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return AlertDialog(
      title: Text(localizations.membersDeleteHouseholdConfirmTitle),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(localizations.membersDeleteHouseholdConfirmMessage(widget.householdName)),
          const SizedBox(height: SgartShapes.space4),
          TextField(
            key: const Key('members-delete-household-confirm-field'),
            controller: _controller,
            decoration: InputDecoration(labelText: localizations.membersDeleteHouseholdConfirmFieldLabel),
          ),
        ],
      ),
      actions: [
        TextButton(
          key: const Key('members-delete-household-cancel-button'),
          onPressed: () => Navigator.of(context).pop(false),
          child: Text(localizations.membersCancelButtonLabel),
        ),
        ValueListenableBuilder<TextEditingValue>(
          valueListenable: _controller,
          builder: (context, value, _) {
            final matches = value.text.trim() == widget.householdName;
            return TextButton(
              key: const Key('members-delete-household-confirm-button'),
              onPressed: matches ? () => Navigator.of(context).pop(true) : null,
              child: Text(localizations.membersConfirmButtonLabel),
            );
          },
        ),
      ],
    );
  }
}

class _MemberRow extends StatelessWidget {
  const _MemberRow({required this.member, required this.isCallerAdmin});

  final MemberView member;
  final bool isCallerAdmin;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final roleLabel =
        member.role == 'ADMIN' ? localizations.membersRoleAdmin : localizations.membersRoleParticipant;
    final titleLabel = member.isSelf ? localizations.membersSelfLabel : roleLabel;

    return ListTile(
      key: Key('member-row-${member.memberId}'),
      contentPadding: EdgeInsets.zero,
      leading: const Icon(Icons.person_outline),
      title: Text(titleLabel),
      subtitle: member.isSelf ? Text(roleLabel) : null,
      trailing: isCallerAdmin && !member.isSelf
          ? PopupMenuButton<_MemberAction>(
              key: Key('member-row-${member.memberId}-menu'),
              onSelected: (action) => _handleAction(context, action),
              itemBuilder: (context) => [
                if (member.role == 'PARTICIPANT')
                  PopupMenuItem(
                    key: Key('member-row-${member.memberId}-promote'),
                    value: _MemberAction.promote,
                    child: Text(localizations.membersPromoteAction),
                  ),
                if (member.role == 'ADMIN')
                  PopupMenuItem(
                    key: Key('member-row-${member.memberId}-demote'),
                    value: _MemberAction.demote,
                    child: Text(localizations.membersDemoteAction),
                  ),
                PopupMenuItem(
                  key: Key('member-row-${member.memberId}-remove'),
                  value: _MemberAction.remove,
                  child: Text(localizations.membersRemoveAction),
                ),
              ],
            )
          : null,
    );
  }

  Future<void> _handleAction(BuildContext context, _MemberAction action) async {
    final localizations = AppLocalizations.of(context);
    switch (action) {
      case _MemberAction.promote:
        final confirmed = await _showYesNoDialog(context, title: localizations.membersPromoteConfirmTitle);
        if (confirmed && context.mounted) {
          await context.read<MembersCubit>().promote(member.memberId);
        }
      case _MemberAction.demote:
        final confirmed = await _showYesNoDialog(context, title: localizations.membersDemoteConfirmTitle);
        if (confirmed && context.mounted) {
          await context.read<MembersCubit>().demote(member.memberId);
        }
      case _MemberAction.remove:
        final confirmed = await _showYesNoDialog(
          context,
          title: localizations.membersRemoveConfirmTitle,
          message: localizations.membersRemoveConfirmMessage,
        );
        if (confirmed && context.mounted) {
          await context.read<MembersCubit>().removeMember(member.memberId);
        }
    }
  }
}

enum _MemberAction { promote, demote, remove }

class _PendingInviteRow extends StatelessWidget {
  const _PendingInviteRow({required this.invite});

  final PendingInvite invite;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return ListTile(
      key: Key('pending-invite-row-${invite.inviteId}'),
      contentPadding: EdgeInsets.zero,
      leading: const Icon(Icons.mail_outline),
      title: Text(localizations.invitesPendingRowLabel(invite.invitedAt)),
      trailing: TextButton(
        key: Key('pending-invite-row-${invite.inviteId}-revoke'),
        onPressed: () => _confirmAndRevoke(context),
        child: Text(localizations.membersRevokeInviteAction),
      ),
    );
  }

  Future<void> _confirmAndRevoke(BuildContext context) async {
    final localizations = AppLocalizations.of(context);
    final confirmed = await _showYesNoDialog(context, title: localizations.membersRevokeInviteConfirmTitle);
    if (confirmed && context.mounted) {
      await context.read<MembersCubit>().revokeInvite(invite.inviteId);
    }
  }
}

/// Shared confirm/cancel dialog for every governance action except delete-household (which needs
/// its own hard, type-to-confirm dialog, AC7).
Future<bool> _showYesNoDialog(BuildContext context, {required String title, String? message}) async {
  final localizations = AppLocalizations.of(context);
  final confirmed = await showDialog<bool>(
    context: context,
    builder: (dialogContext) => AlertDialog(
      title: Text(title),
      content: message == null ? null : Text(message),
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
  return confirmed ?? false;
}

class _FailureBody extends StatelessWidget {
  const _FailureBody({required this.errorText});

  final String errorText;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Center(
      child: Padding(
        padding: const EdgeInsets.all(SgartShapes.cardPadding),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(errorText, key: const Key('members-load-error')),
            const SizedBox(height: SgartShapes.space4),
            SgartButton(
              key: const Key('members-retry-button'),
              label: localizations.householdsRetryButtonLabel,
              onPressed: () => context.read<MembersCubit>().bootstrap(),
            ),
          ],
        ),
      ),
    );
  }
}
