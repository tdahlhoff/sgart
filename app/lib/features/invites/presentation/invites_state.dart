import '../../../shared/errors/app_error.dart';

enum InvitesStatus { loading, ready, failure }

/// State of [InvitesCubit] (Story 8.4). [loading]/[failure] cover the initial load of the
/// household's active invite code; once [ready] it carries the `inviteId` to share, `canReplace`
/// (whether this caller — an Admin — may replace it), the `isSubmitting` flag for an in-flight
/// replace, and `actionError` for a replace rejection shown inline — kept separate from `loadError`
/// so a rejected replace never tears down the screen. Mirrors `MembersState`.
class InvitesState {
  const InvitesState._(
    this.status, {
    this.inviteId = '',
    this.canReplace = false,
    this.isSubmitting = false,
    this.loadError,
    this.actionError,
  });

  const InvitesState.loading() : this._(InvitesStatus.loading);

  const InvitesState.failure(AppError error) : this._(InvitesStatus.failure, loadError: error);

  const InvitesState.ready({
    required String inviteId,
    required bool canReplace,
    bool isSubmitting = false,
    AppError? actionError,
  }) : this._(
          InvitesStatus.ready,
          inviteId: inviteId,
          canReplace: canReplace,
          isSubmitting: isSubmitting,
          actionError: actionError,
        );

  final InvitesStatus status;
  final String inviteId;
  final bool canReplace;
  final bool isSubmitting;
  final AppError? loadError;
  final AppError? actionError;

  InvitesState copyWith({
    String? inviteId,
    bool? canReplace,
    bool? isSubmitting,
    AppError? actionError,
    bool clearActionError = false,
  }) {
    return InvitesState.ready(
      inviteId: inviteId ?? this.inviteId,
      canReplace: canReplace ?? this.canReplace,
      isSubmitting: isSubmitting ?? this.isSubmitting,
      actionError: clearActionError ? null : (actionError ?? this.actionError),
    );
  }

  @override
  bool operator ==(Object other) =>
      other is InvitesState &&
      other.status == status &&
      other.inviteId == inviteId &&
      other.canReplace == canReplace &&
      other.isSubmitting == isSubmitting &&
      other.loadError == loadError &&
      other.actionError == actionError;

  @override
  int get hashCode => Object.hash(status, inviteId, canReplace, isSubmitting, loadError, actionError);
}
