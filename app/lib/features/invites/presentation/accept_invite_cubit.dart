import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../shared/commands/command_intent.dart';
import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';
import '../data/invite_link.dart';
import '../data/invites_api.dart';
import 'accept_invite_state.dart';

/// Drives the accept-invite form (Story 4.2, AC6): parses the pasted link/code client-side
/// (fail-fast on malformed input — no network round-trip), redeems it, and surfaces `410`/`404`/
/// `409` as distinct inline errors. Depends only on the [InvitesApi] interface so tests never touch
/// the network (CLAUDE.md §6); guards every `emit` with `isClosed`. Mirrors `CreateHouseholdCubit`.
class AcceptInviteCubit extends Cubit<AcceptInviteState> {
  AcceptInviteCubit({required this._invitesApi}) : super(const AcceptInviteState.idle());

  final InvitesApi _invitesApi;

  /// The accept intent's command id: reused across retries of the *same* pasted link (idempotent
  /// retry, AD-8), freshened when the link changes (a new intent), and freshened again after a
  /// successful accept (a spent command id would be deduped server-side as a silent no-op).
  final CommandIntent _intent = CommandIntent();

  /// Redeems [rawLink] (AC1, AC6). Client-side fail-fast: a link that does not parse is rejected
  /// here, without a round-trip, and never reaches the network.
  Future<void> accept(String rawLink) async {
    // Re-entrancy guard (Epic-2 Action 3 lesson): a second call while one is already in flight is a
    // no-op, never a second concurrent accept.
    if (state.status == AcceptInviteStatus.submitting) {
      return;
    }
    final link = InviteLink.tryParse(rawLink);
    if (link == null) {
      _safeEmit(const AcceptInviteState.failure(
        AppError(code: 'invite.invalidLink', message: 'client-side fail-fast'),
      ));
      return;
    }
    _intent.beginAttempt(link);
    final commandId = _intent.commandId;
    _safeEmit(const AcceptInviteState.submitting());
    try {
      await _invitesApi.acceptInvite(link.householdId, inviteId: link.inviteId, commandId: commandId);
      _safeEmit(AcceptInviteState.success(link.householdId));
      // A successful accept completes this intent — the next accept is a new intent and never
      // reuses a command id the server has already applied (which it would silently drop).
      _intent.complete();
    } on Object catch (error) {
      _safeEmit(AcceptInviteState.failure(_toAppError(error)));
    }
  }

  AppError _toAppError(Object error) {
    if (error is AppException) {
      return error.error;
    }
    return AppError(code: 'invite.unknown', message: error.toString());
  }

  void _safeEmit(AcceptInviteState state) {
    if (!isClosed) {
      emit(state);
    }
  }
}
