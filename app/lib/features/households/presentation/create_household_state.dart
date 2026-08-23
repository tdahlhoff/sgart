import '../../../shared/errors/app_error.dart';
import '../data/household_summary.dart';

enum CreateHouseholdStatus { idle, submitting, success, failure }

/// State of [CreateHouseholdCubit] — the create-household form's own submit lifecycle, kept
/// separate from [HouseholdsCubit] so a rejected submission (e.g. a blank name) shows inline on
/// the form instead of tearing down the whole first-run routing screen.
class CreateHouseholdState {
  const CreateHouseholdState.idle() : this._(CreateHouseholdStatus.idle);

  const CreateHouseholdState.submitting() : this._(CreateHouseholdStatus.submitting);

  const CreateHouseholdState.success(HouseholdSummary household)
      : this._(CreateHouseholdStatus.success, household: household);

  const CreateHouseholdState.failure(AppError error) : this._(CreateHouseholdStatus.failure, error: error);

  const CreateHouseholdState._(this.status, {this.household, this.error});

  final CreateHouseholdStatus status;
  final HouseholdSummary? household;
  final AppError? error;

  @override
  bool operator ==(Object other) =>
      other is CreateHouseholdState &&
      other.status == status &&
      other.household == household &&
      other.error == error;

  @override
  int get hashCode => Object.hash(status, household, error);
}
