import 'package:collection/collection.dart';

import '../../../shared/errors/app_error.dart';
import '../data/pending_invite.dart';

enum InvitesStatus { loading, ready, failure }

/// State of [InvitesCubit] (Story 7.5). [loading]/[failure] cover the initial load of the pending
/// invites; once [ready] it carries the pending `invites`, the `isSubmitting` flag for an
/// in-flight create, `actionError` for a create rejection shown inline — kept separate from
/// `loadError` so a rejected create never tears down the screen — and `lastCreatedInviteId`, the
/// invite the create action most recently minted, so the view can render its shareable code/link
/// until the next create replaces it. Mirrors `StoresState`.
class InvitesState {
  const InvitesState._(
    this.status, {
    this.invites = const [],
    this.isSubmitting = false,
    this.loadError,
    this.actionError,
    this.lastCreatedInviteId,
  });

  const InvitesState.loading() : this._(InvitesStatus.loading);

  const InvitesState.failure(AppError error) : this._(InvitesStatus.failure, loadError: error);

  const InvitesState.ready({
    required List<PendingInvite> invites,
    bool isSubmitting = false,
    AppError? actionError,
    String? lastCreatedInviteId,
  }) : this._(
          InvitesStatus.ready,
          invites: invites,
          isSubmitting: isSubmitting,
          actionError: actionError,
          lastCreatedInviteId: lastCreatedInviteId,
        );

  final InvitesStatus status;
  final List<PendingInvite> invites;
  final bool isSubmitting;
  final AppError? loadError;
  final AppError? actionError;
  final String? lastCreatedInviteId;

  InvitesState copyWith({
    List<PendingInvite>? invites,
    bool? isSubmitting,
    AppError? actionError,
    bool clearActionError = false,
    String? lastCreatedInviteId,
  }) {
    return InvitesState.ready(
      invites: invites ?? this.invites,
      isSubmitting: isSubmitting ?? this.isSubmitting,
      actionError: clearActionError ? null : (actionError ?? this.actionError),
      lastCreatedInviteId: lastCreatedInviteId ?? this.lastCreatedInviteId,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is InvitesState &&
      other.status == status &&
      const ListEquality<PendingInvite>().equals(other.invites, invites) &&
      other.isSubmitting == isSubmitting &&
      other.loadError == loadError &&
      other.actionError == actionError &&
      other.lastCreatedInviteId == lastCreatedInviteId;

  @override
  int get hashCode => Object.hash(
        status,
        const ListEquality<PendingInvite>().hash(invites),
        isSubmitting,
        loadError,
        actionError,
        lastCreatedInviteId,
      );
}
