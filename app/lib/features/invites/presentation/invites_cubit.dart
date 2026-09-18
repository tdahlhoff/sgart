import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:uuid/uuid.dart';

import '../../../shared/errors/app_error.dart';
import '../../../shared/http/app_exception.dart';
import '../data/invites_api.dart';
import '../data/pending_invite.dart';
import 'invites_state.dart';

/// Drives the invite screen (Story 7.5, AC1, AC6, AC7): loads the pending invites and creates a
/// new one on demand — no email collected anywhere. Each create is an independent invite (multiple
/// pending invites may coexist for the same household, AC2), so unlike a name/email field there is
/// no editable payload to key a retry on — every tap simply mints a fresh `inviteId`/`commandId`.
/// Depends only on the [InvitesApi] interface so tests never touch the network (CLAUDE.md §6);
/// guards every `emit` with `isClosed`. Mirrors `StoresCubit`.
class InvitesCubit extends Cubit<InvitesState> {
  InvitesCubit({required this.invitesApi, required this.householdId}) : super(const InvitesState.loading());

  final InvitesApi invitesApi;
  final String householdId;

  static const _idFactory = Uuid();

  Future<void> bootstrap() async {
    try {
      final invites = await invitesApi.listPendingInvites(householdId);
      _safeEmit(InvitesState.ready(invites: invites));
    } on Object catch (error) {
      _safeEmit(InvitesState.failure(_toAppError(error)));
    }
  }

  /// Creates a new invite (AC1). Shows its shareable code/link via
  /// [InvitesState.lastCreatedInviteId] once it succeeds.
  Future<void> createInvite() async {
    // Re-entrancy guard (Epic-2 Action 3 lesson): a second call while one is already in flight
    // (e.g. a fast double-tap slipping past the UI's disabled-while-submitting button) is a no-op,
    // never a second concurrent create.
    if (state.status != InvitesStatus.ready || state.isSubmitting) {
      return;
    }
    final inviteId = _idFactory.v4();
    final commandId = _idFactory.v4();
    // Do NOT clear lastCreatedInviteId here: the previously created invite's card must survive a
    // subsequent create that fails (the earlier invite is still valid and pending). It is only
    // replaced on the next success below.
    _safeEmit(state.copyWith(isSubmitting: true, clearActionError: true));
    try {
      await invitesApi.createInvite(householdId, inviteId: inviteId, commandId: commandId);
      final created = PendingInvite(
        inviteId: inviteId,
        invitedAt: DateTime.now().toUtc().toIso8601String(),
        invitedBy: '',
        status: 'PENDING',
      );
      _safeEmit(state.copyWith(
        invites: [...state.invites, created],
        isSubmitting: false,
        clearActionError: true,
        lastCreatedInviteId: inviteId,
      ));
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
