import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:uuid/uuid.dart';

import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';
import '../data/invites_api.dart';
import 'invites_state.dart';

/// Drives the invite screen (Story 8.4): loads the household's single active invite code and
/// replaces it on demand (Admin-only, „Code ersetzen") — no email collected anywhere, no create, no
/// list. Depends only on the [InvitesApi] interface so tests never touch the network (CLAUDE.md
/// §6); guards every `emit` with `isClosed`. Mirrors `StoresCubit`.
class InvitesCubit extends Cubit<InvitesState> {
  InvitesCubit({required this.invitesApi, required this.householdId}) : super(const InvitesState.loading());

  final InvitesApi invitesApi;
  final String householdId;

  static const _idFactory = Uuid();

  Future<void> bootstrap() async {
    try {
      final activeCode = await invitesApi.getActiveInviteCode(householdId);
      _safeEmit(InvitesState.ready(inviteId: activeCode.inviteId, canReplace: activeCode.canReplace));
    } on Object catch (error) {
      _safeEmit(InvitesState.failure(_toAppError(error)));
    }
  }

  /// Replaces the active invite code with a fresh one (Story 8.4, F7) — Admin-only; the UI hides
  /// the action for a Participant, but the domain enforces it regardless.
  Future<void> replaceCode() async {
    // Re-entrancy guard (Epic-2 Action 3 lesson): a second call while one is already in flight
    // (e.g. a fast double-tap slipping past the UI's disabled-while-submitting button) is a no-op.
    if (state.status != InvitesStatus.ready || state.isSubmitting) {
      return;
    }
    final newInviteId = _idFactory.v4();
    final commandId = _idFactory.v4();
    _safeEmit(state.copyWith(isSubmitting: true, clearActionError: true));
    try {
      await invitesApi.replaceInviteCode(householdId, newInviteId: newInviteId, commandId: commandId);
      _safeEmit(state.copyWith(inviteId: newInviteId, isSubmitting: false, clearActionError: true));
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
