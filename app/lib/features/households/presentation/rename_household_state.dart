import '../../../shared/errors/app_error.dart';

enum RenameHouseholdStatus { idle, submitting, success, failure }

/// State of [RenameHouseholdCubit] — the rename form's own submit lifecycle, kept separate from
/// [HouseholdsCubit] so a rejected rename (e.g. a blank name, or a non-Admin) shows inline on the
/// form instead of tearing down the shell. On [success], `newName` carries the trimmed name to
/// propagate everywhere it is shown (AC3).
class RenameHouseholdState {
  const RenameHouseholdState.idle() : this._(RenameHouseholdStatus.idle);

  const RenameHouseholdState.submitting() : this._(RenameHouseholdStatus.submitting);

  const RenameHouseholdState.success(String newName)
      : this._(RenameHouseholdStatus.success, newName: newName);

  const RenameHouseholdState.failure(AppError error)
      : this._(RenameHouseholdStatus.failure, error: error);

  const RenameHouseholdState._(this.status, {this.newName, this.error});

  final RenameHouseholdStatus status;
  final String? newName;
  final AppError? error;

  @override
  bool operator ==(Object other) =>
      other is RenameHouseholdState &&
      other.status == status &&
      other.newName == newName &&
      other.error == error;

  @override
  int get hashCode => Object.hash(status, newName, error);
}
