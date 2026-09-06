import 'package:collection/collection.dart';

import '../../../shared/errors/app_error.dart';
import '../data/pending_invite.dart';

enum InvitesStatus { loading, ready, failure }

/// State of [InvitesCubit] (Story 4.1). [loading]/[failure] cover the initial load of the pending
/// invites; once [ready] it carries the pending `invites`, the `isSubmitting` flag for an in-flight
/// send, and `actionError` for a send rejection shown inline (e.g. a duplicate pending invite or an
/// already-a-member email) — kept separate from `loadError` so a rejected send never tears down the
/// screen. Mirrors `StoresState`.
class InvitesState {
  const InvitesState._(
    this.status, {
    this.invites = const [],
    this.isSubmitting = false,
    this.loadError,
    this.actionError,
  });

  const InvitesState.loading() : this._(InvitesStatus.loading);

  const InvitesState.failure(AppError error) : this._(InvitesStatus.failure, loadError: error);

  const InvitesState.ready({
    required List<PendingInvite> invites,
    bool isSubmitting = false,
    AppError? actionError,
  }) : this._(InvitesStatus.ready, invites: invites, isSubmitting: isSubmitting, actionError: actionError);

  final InvitesStatus status;
  final List<PendingInvite> invites;
  final bool isSubmitting;
  final AppError? loadError;
  final AppError? actionError;

  InvitesState copyWith({
    List<PendingInvite>? invites,
    bool? isSubmitting,
    AppError? actionError,
    bool clearActionError = false,
  }) {
    return InvitesState.ready(
      invites: invites ?? this.invites,
      isSubmitting: isSubmitting ?? this.isSubmitting,
      actionError: clearActionError ? null : (actionError ?? this.actionError),
    );
  }

  @override
  bool operator ==(Object other) =>
      other is InvitesState &&
      other.status == status &&
      const ListEquality<PendingInvite>().equals(other.invites, invites) &&
      other.isSubmitting == isSubmitting &&
      other.loadError == loadError &&
      other.actionError == actionError;

  @override
  int get hashCode => Object.hash(
        status,
        const ListEquality<PendingInvite>().hash(invites),
        isSubmitting,
        loadError,
        actionError,
      );
}
