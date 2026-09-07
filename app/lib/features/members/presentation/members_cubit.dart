import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../shared/commands/command_intent.dart';
import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';
import '../../households/data/households_api.dart';
import '../../invites/data/invites_api.dart';
import '../data/member_view.dart';
import '../data/members_api.dart';
import 'members_state.dart';

/// Drives the member-management screen (Story 4.3, AC8, AC9): loads the roster + pending invites,
/// and exposes the governance intents (leave/remove/promote/demote/delete-household/revoke-invite)
/// with a single [CommandIntent] per intent kind (regenerated on payload change / after success,
/// AD-8) and an `isSubmitting` re-entrancy guard (Epic-2 Action 3 lesson). Surfaces `403`
/// (`governance.notPermitted`), `409` (`membership.lastAdmin`), and `404` (`invite.notFound`) as
/// distinct inline errors via the caller's `error_message_resolver.dart`. Depends only on the
/// [MembersApi]/[HouseholdsApi]/[InvitesApi] interfaces so tests never touch the network (CLAUDE.md
/// §6); guards every `emit` with `isClosed`. Mirrors `InvitesCubit`.
class MembersCubit extends Cubit<MembersState> {
  MembersCubit({
    required this._membersApi,
    required this._householdsApi,
    required this._invitesApi,
    required this._householdId,
  }) : super(const MembersState.loading());

  final MembersApi _membersApi;
  final HouseholdsApi _householdsApi;
  final InvitesApi _invitesApi;
  final String _householdId;

  final CommandIntent _leaveIntent = CommandIntent();
  final CommandIntent _removeIntent = CommandIntent();
  final CommandIntent _promoteIntent = CommandIntent();
  final CommandIntent _demoteIntent = CommandIntent();
  final CommandIntent _deleteIntent = CommandIntent();
  final CommandIntent _revokeIntent = CommandIntent();

  Future<void> bootstrap() async {
    _safeEmit(const MembersState.loading());
    try {
      final members = await _membersApi.listMembers(_householdId);
      final pendingInvites = await _invitesApi.listPendingInvites(_householdId);
      _safeEmit(MembersState.ready(members: members, pendingInvites: pendingInvites));
    } on Object catch (error) {
      _safeEmit(MembersState.failure(_toAppError(error)));
    }
  }

  /// Leaves the household (AC3). On success, sets [MembersState.exited] — the screen's signal to
  /// bootstrap `HouseholdsCubit` and re-route.
  Future<void> leave() async {
    if (!_canSubmit()) {
      return;
    }
    _leaveIntent.beginAttempt(_householdId);
    _safeEmit(state.copyWith(isSubmitting: true, clearActionError: true));
    try {
      await _membersApi.leave(_householdId, commandId: _leaveIntent.commandId);
      _leaveIntent.complete();
      _safeEmit(state.copyWith(isSubmitting: false, exited: true));
    } on Object catch (error) {
      _safeEmit(state.copyWith(isSubmitting: false, actionError: _toAppError(error)));
    }
  }

  /// Removes [memberId] (AC4) — Admin-only. Refreshes the roster locally on success.
  Future<void> removeMember(String memberId) async {
    if (!_canSubmit()) {
      return;
    }
    _removeIntent.beginAttempt(memberId);
    _safeEmit(state.copyWith(isSubmitting: true, clearActionError: true));
    try {
      await _membersApi.removeMember(_householdId, memberId, commandId: _removeIntent.commandId);
      _removeIntent.complete();
      final remaining = state.members.where((member) => member.memberId != memberId).toList();
      _safeEmit(state.copyWith(members: remaining, isSubmitting: false));
    } on Object catch (error) {
      _safeEmit(state.copyWith(isSubmitting: false, actionError: _toAppError(error)));
    }
  }

  /// Promotes [memberId] to Admin (AC4) — Admin-only. Updates the roster locally on success.
  Future<void> promote(String memberId) async {
    if (!_canSubmit()) {
      return;
    }
    _promoteIntent.beginAttempt(memberId);
    _safeEmit(state.copyWith(isSubmitting: true, clearActionError: true));
    try {
      await _membersApi.promote(_householdId, memberId, commandId: _promoteIntent.commandId);
      _promoteIntent.complete();
      _safeEmit(state.copyWith(members: _withRole(memberId, 'ADMIN'), isSubmitting: false));
    } on Object catch (error) {
      _safeEmit(state.copyWith(isSubmitting: false, actionError: _toAppError(error)));
    }
  }

  /// Demotes [memberId] to Participant (AC4) — Admin-only. Updates the roster locally on success.
  Future<void> demote(String memberId) async {
    if (!_canSubmit()) {
      return;
    }
    _demoteIntent.beginAttempt(memberId);
    _safeEmit(state.copyWith(isSubmitting: true, clearActionError: true));
    try {
      await _membersApi.demote(_householdId, memberId, commandId: _demoteIntent.commandId);
      _demoteIntent.complete();
      _safeEmit(state.copyWith(members: _withRole(memberId, 'PARTICIPANT'), isSubmitting: false));
    } on Object catch (error) {
      _safeEmit(state.copyWith(isSubmitting: false, actionError: _toAppError(error)));
    }
  }

  /// Deletes the household (AC7) — Admin-only, behind the screen's own hard confirmation. On
  /// success, sets [MembersState.exited] — the screen's signal to bootstrap `HouseholdsCubit` and
  /// re-route.
  Future<void> deleteHousehold() async {
    if (!_canSubmit()) {
      return;
    }
    _deleteIntent.beginAttempt(_householdId);
    _safeEmit(state.copyWith(isSubmitting: true, clearActionError: true));
    try {
      await _householdsApi.deleteHousehold(_householdId, commandId: _deleteIntent.commandId);
      _deleteIntent.complete();
      _safeEmit(state.copyWith(isSubmitting: false, exited: true));
    } on Object catch (error) {
      _safeEmit(state.copyWith(isSubmitting: false, actionError: _toAppError(error)));
    }
  }

  /// Revokes the pending invite [inviteId] (AC6) — Admin-only. Removes it from the pending list
  /// locally on success.
  Future<void> revokeInvite(String inviteId) async {
    if (!_canSubmit()) {
      return;
    }
    _revokeIntent.beginAttempt(inviteId);
    _safeEmit(state.copyWith(isSubmitting: true, clearActionError: true));
    try {
      await _invitesApi.revokeInvite(_householdId, inviteId: inviteId, commandId: _revokeIntent.commandId);
      _revokeIntent.complete();
      final remaining = state.pendingInvites.where((invite) => invite.inviteId != inviteId).toList();
      _safeEmit(state.copyWith(pendingInvites: remaining, isSubmitting: false));
    } on Object catch (error) {
      _safeEmit(state.copyWith(isSubmitting: false, actionError: _toAppError(error)));
    }
  }

  List<MemberView> _withRole(String memberId, String role) {
    return state.members
        .map((member) => member.memberId == memberId
            ? MemberView(memberId: member.memberId, role: role, isSelf: member.isSelf)
            : member)
        .toList();
  }

  /// Re-entrancy guard (Epic-2 Action 3 lesson): a second call while one is already in flight, or
  /// while the screen has already exited, is a no-op — never a second concurrent governance call.
  bool _canSubmit() => state.status == MembersStatus.ready && !state.isSubmitting && !state.exited;

  AppError _toAppError(Object error) {
    if (error is AppException) {
      return error.error;
    }
    return AppError(code: 'members.unknown', message: error.toString());
  }

  void _safeEmit(MembersState state) {
    if (!isClosed) {
      emit(state);
    }
  }
}
