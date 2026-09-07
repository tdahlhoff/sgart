import '../../../shared/errors/app_error.dart';

enum AcceptInviteStatus { idle, submitting, success, failure }

/// State of [AcceptInviteCubit] (Story 4.2, AC6) — the accept-invite form's own submit lifecycle.
/// Mirrors `CreateHouseholdState`: [success] carries the joined `householdId` so the screen can
/// re-bootstrap `HouseholdsCubit` and route into it (read-your-writes), [failure] carries the
/// inline error (malformed link / expired / not-found / already-used) shown on the form.
class AcceptInviteState {
  const AcceptInviteState.idle() : this._(AcceptInviteStatus.idle);

  const AcceptInviteState.submitting() : this._(AcceptInviteStatus.submitting);

  const AcceptInviteState.success(String householdId)
      : this._(AcceptInviteStatus.success, householdId: householdId);

  const AcceptInviteState.failure(AppError error) : this._(AcceptInviteStatus.failure, error: error);

  const AcceptInviteState._(this.status, {this.householdId, this.error});

  final AcceptInviteStatus status;
  final String? householdId;
  final AppError? error;

  @override
  bool operator ==(Object other) =>
      other is AcceptInviteState &&
      other.status == status &&
      other.householdId == householdId &&
      other.error == error;

  @override
  int get hashCode => Object.hash(status, householdId, error);
}
