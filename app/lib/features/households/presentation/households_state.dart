import 'package:collection/collection.dart';

import '../../../shared/errors/app_error.dart';
import '../data/household_summary.dart';

enum HouseholdsStatus { loading, needsChoice, shell, selection, failure }

/// State of [HouseholdsCubit]. First-run routing (AC2) decides the initial status: zero households →
/// [needsChoice]; several with no stored last-active → [selection]; otherwise → [shell]. The
/// [shell] status carries both the retained `households` list (all the caller belongs to, for the
/// switcher) and the `activeHousehold` currently in the header/body (Story 1.7, AC1/AC2).
/// `households` is also set while [selection]; `error` only while [failure].
class HouseholdsState {
  const HouseholdsState.loading() : this._(HouseholdsStatus.loading);

  const HouseholdsState.needsChoice() : this._(HouseholdsStatus.needsChoice);

  const HouseholdsState.shell({
    required HouseholdSummary activeHousehold,
    required List<HouseholdSummary> households,
  }) : this._(HouseholdsStatus.shell, activeHousehold: activeHousehold, households: households);

  const HouseholdsState.selection(List<HouseholdSummary> households)
      : this._(HouseholdsStatus.selection, households: households);

  const HouseholdsState.failure(AppError error) : this._(HouseholdsStatus.failure, error: error);

  const HouseholdsState._(this.status, {this.activeHousehold, this.households, this.error});

  final HouseholdsStatus status;
  final HouseholdSummary? activeHousehold;
  final List<HouseholdSummary>? households;
  final AppError? error;

  @override
  bool operator ==(Object other) =>
      other is HouseholdsState &&
      other.status == status &&
      other.activeHousehold == activeHousehold &&
      const ListEquality<HouseholdSummary>().equals(other.households, households) &&
      other.error == error;

  @override
  int get hashCode => Object.hash(
        status,
        activeHousehold,
        households == null ? null : const ListEquality<HouseholdSummary>().hash(households),
        error,
      );
}
