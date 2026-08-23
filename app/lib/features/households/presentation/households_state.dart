import 'package:collection/collection.dart';

import '../../../shared/errors/app_error.dart';
import '../data/household_summary.dart';

enum HouseholdsStatus { loading, needsChoice, home, selection, failure }

/// State of [HouseholdsCubit] — the first-run routing decision (AC2): zero households → [needsChoice];
/// exactly one → [home]; several → [selection]. `currentHousehold` is only set while [status] is
/// [HouseholdsStatus.home]; `households` only while [HouseholdsStatus.selection]; `error` only
/// while [HouseholdsStatus.failure].
class HouseholdsState {
  const HouseholdsState.loading() : this._(HouseholdsStatus.loading);

  const HouseholdsState.needsChoice() : this._(HouseholdsStatus.needsChoice);

  const HouseholdsState.home(HouseholdSummary household)
      : this._(HouseholdsStatus.home, currentHousehold: household);

  const HouseholdsState.selection(List<HouseholdSummary> households)
      : this._(HouseholdsStatus.selection, households: households);

  const HouseholdsState.failure(AppError error) : this._(HouseholdsStatus.failure, error: error);

  const HouseholdsState._(this.status, {this.currentHousehold, this.households, this.error});

  final HouseholdsStatus status;
  final HouseholdSummary? currentHousehold;
  final List<HouseholdSummary>? households;
  final AppError? error;

  @override
  bool operator ==(Object other) =>
      other is HouseholdsState &&
      other.status == status &&
      other.currentHousehold == currentHousehold &&
      const ListEquality<HouseholdSummary>().equals(other.households, households) &&
      other.error == error;

  @override
  int get hashCode => Object.hash(
        status,
        currentHousehold,
        households == null ? null : const ListEquality<HouseholdSummary>().hash(households),
        error,
      );
}
