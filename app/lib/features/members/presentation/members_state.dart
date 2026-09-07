import 'package:collection/collection.dart';

import '../../../shared/errors/app_error.dart';
import '../../invites/data/pending_invite.dart';
import '../data/member_view.dart';

enum MembersStatus { loading, ready, failure }

/// State of [MembersCubit] (Story 4.3, AC9). [loading]/[failure] cover the initial roster load;
/// once [ready] it carries the current `members` + `pendingInvites` (for the Admin's per-invite
/// revoke action), the `isSubmitting` flag for an in-flight governance action, `actionError` for a
/// rejection shown inline (kept separate from `loadError` so a rejected action never tears down the
/// screen), and `exited` — set once a successful **leave / self-removal / delete** fires, the signal
/// the screen uses to `HouseholdsCubit.bootstrap()` and re-route (consumed once, then cleared).
/// Mirrors `InvitesState`.
class MembersState {
  const MembersState._(
    this.status, {
    this.members = const [],
    this.pendingInvites = const [],
    this.isSubmitting = false,
    this.loadError,
    this.actionError,
    this.exited = false,
  });

  const MembersState.loading() : this._(MembersStatus.loading);

  const MembersState.failure(AppError error) : this._(MembersStatus.failure, loadError: error);

  const MembersState.ready({
    required List<MemberView> members,
    List<PendingInvite> pendingInvites = const [],
    bool isSubmitting = false,
    AppError? actionError,
    bool exited = false,
  }) : this._(
          MembersStatus.ready,
          members: members,
          pendingInvites: pendingInvites,
          isSubmitting: isSubmitting,
          actionError: actionError,
          exited: exited,
        );

  final MembersStatus status;
  final List<MemberView> members;
  final List<PendingInvite> pendingInvites;
  final bool isSubmitting;
  final AppError? loadError;
  final AppError? actionError;
  final bool exited;

  MemberView? get self => members.firstWhereOrNull((member) => member.isSelf);

  MembersState copyWith({
    List<MemberView>? members,
    List<PendingInvite>? pendingInvites,
    bool? isSubmitting,
    AppError? actionError,
    bool clearActionError = false,
    bool? exited,
  }) {
    return MembersState.ready(
      members: members ?? this.members,
      pendingInvites: pendingInvites ?? this.pendingInvites,
      isSubmitting: isSubmitting ?? this.isSubmitting,
      actionError: clearActionError ? null : (actionError ?? this.actionError),
      exited: exited ?? this.exited,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is MembersState &&
      other.status == status &&
      const ListEquality<MemberView>().equals(other.members, members) &&
      const ListEquality<PendingInvite>().equals(other.pendingInvites, pendingInvites) &&
      other.isSubmitting == isSubmitting &&
      other.loadError == loadError &&
      other.actionError == actionError &&
      other.exited == exited;

  @override
  int get hashCode => Object.hash(
        status,
        const ListEquality<MemberView>().hash(members),
        const ListEquality<PendingInvite>().hash(pendingInvites),
        isSubmitting,
        loadError,
        actionError,
        exited,
      );
}
