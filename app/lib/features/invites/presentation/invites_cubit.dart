import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../shared/commands/command_intent.dart';
import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';
import '../data/invites_api.dart';
import '../data/pending_invite.dart';
import 'invite_email_validator.dart';
import 'invites_state.dart';

/// Drives the invite screen (Story 4.1, AC1–AC3, AC6, AC7): loads the pending invites, sends an
/// invite by email with client-side fail-fast validation, and surfaces `409`
/// (duplicate-pending/already-a-member) and `400` (invalid email) as distinct inline errors.
/// Depends only on the [InvitesApi] interface so tests never touch the network (CLAUDE.md §6);
/// guards every `emit` with `isClosed`. Mirrors `StoresCubit`.
class InvitesCubit extends Cubit<InvitesState> {
  InvitesCubit({required this.invitesApi, required this.householdId}) : super(const InvitesState.loading());

  final InvitesApi invitesApi;
  final String householdId;

  /// The send-invite intent's ids: the command id plus one paired client-minted invite id. Both are
  /// reused across retries of the same email (idempotent retry, AD-8), freshened when the email
  /// changes (a new intent), and freshened again after a successful send (a spent command id would
  /// be deduped server-side as a silent no-op, silently dropping the invite — the Epic 1/2 lesson
  /// `StoresCubit`'s `_addIntent` already encodes).
  final CommandIntent _sendIntent = CommandIntent(hasResourceId: true);

  Future<void> bootstrap() async {
    try {
      final invites = await invitesApi.listPendingInvites(householdId);
      _safeEmit(InvitesState.ready(invites: invites));
    } on Object catch (error) {
      _safeEmit(InvitesState.failure(_toAppError(error)));
    }
  }

  /// Sends an invite to [email] (AC1). Client-side fail-fast (AC7): an implausible address is
  /// rejected here, without a round-trip, using the same client-facing code the server would use
  /// (`invite.emailInvalid`) so the inline copy is identical either way.
  Future<void> sendInvite(String email) async {
    // Re-entrancy guard (Epic-2 Action 3 lesson): a second call while one is already in flight
    // (e.g. a fast double-tap slipping past the UI's disabled-while-submitting button) is a no-op,
    // never a second concurrent send.
    if (state.status != InvitesStatus.ready || state.isSubmitting) {
      return;
    }
    final trimmedEmail = email.trim();
    if (trimmedEmail.isEmpty) {
      return;
    }
    if (!isPlausibleEmail(trimmedEmail)) {
      _safeEmit(state.copyWith(
        actionError: const AppError(code: 'invite.emailInvalid', message: 'client-side fail-fast'),
      ));
      return;
    }
    _sendIntent.beginAttempt(trimmedEmail);
    final commandId = _sendIntent.commandId;
    final inviteId = _sendIntent.resourceId();
    _safeEmit(state.copyWith(isSubmitting: true, clearActionError: true));
    try {
      await invitesApi.sendInvite(householdId, inviteId: inviteId, email: trimmedEmail, commandId: commandId);
      final sent = PendingInvite(
        inviteId: inviteId,
        invitedAt: DateTime.now().toUtc().toIso8601String(),
        invitedBy: '',
        status: 'PENDING',
      );
      _safeEmit(state.copyWith(invites: [...state.invites, sent], isSubmitting: false, clearActionError: true));
      // A successful send completes this intent — the next send is a new intent and never reuses a
      // command id the server has already applied (which it would silently drop).
      _sendIntent.complete();
    } on Object catch (error) {
      _safeEmit(state.copyWith(isSubmitting: false, actionError: _toAppError(error)));
    }
  }

  AppError _toAppError(Object error) {
    if (error is AppException) {
      return error.error;
    }
    return AppError(code: 'invites.unknown', message: error.toString());
  }

  void _safeEmit(InvitesState state) {
    if (!isClosed) {
      emit(state);
    }
  }
}
