import '../../../shared/errors/app_error.dart';

enum ConsentGateStatus { loading, needsConsent, accepted, failure }

/// State of [ConsentCubit] — the consent-gate screen's own lifecycle (Story 7.4, AC1/AC4), kept
/// separate from [HouseholdsCubit]/[AuthCubit] (SRP). [needsConsent] carries the notice version
/// to accept; a stale client's server-side `409 consent.required` rejection also lands here via
/// [failure] with that code, so the gate can be shown again (AC3).
class ConsentState {
  const ConsentState.loading() : this._(ConsentGateStatus.loading);

  const ConsentState.needsConsent(String currentVersion)
      : this._(ConsentGateStatus.needsConsent, currentVersion: currentVersion);

  const ConsentState.accepted() : this._(ConsentGateStatus.accepted);

  const ConsentState.failure(AppError error) : this._(ConsentGateStatus.failure, error: error);

  const ConsentState._(this.status, {this.currentVersion, this.error});

  final ConsentGateStatus status;
  final String? currentVersion;
  final AppError? error;

  @override
  bool operator ==(Object other) =>
      other is ConsentState &&
      other.status == status &&
      other.currentVersion == currentVersion &&
      other.error == error;

  @override
  int get hashCode => Object.hash(status, currentVersion, error);
}
