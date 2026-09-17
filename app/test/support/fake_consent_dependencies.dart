import 'package:sgart/features/consent/data/consent_api.dart';

/// Test double for [ConsentApi] — no real network in tests (CLAUDE.md §6). Mirrors `FakeHouseholdsApi`.
class FakeConsentApi implements ConsentApi {
  ConsentStatus statusToReturn = const ConsentStatus(accepted: false, acceptedVersion: null, currentVersion: '2026-beta-1');
  Object? getStatusError;
  Object? acceptError;

  int getStatusCallCount = 0;
  String? lastAcceptedVersion;
  int acceptCallCount = 0;

  @override
  Future<ConsentStatus> getStatus() async {
    getStatusCallCount++;
    if (getStatusError != null) throw getStatusError!;
    return statusToReturn;
  }

  @override
  Future<void> accept() async {
    // Mirrors the real server: it stamps its own current version, never a client-supplied one.
    final acceptedVersion = statusToReturn.currentVersion;
    lastAcceptedVersion = acceptedVersion;
    acceptCallCount++;
    if (acceptError != null) throw acceptError!;
    statusToReturn = ConsentStatus(
      accepted: true,
      acceptedVersion: acceptedVersion,
      currentVersion: statusToReturn.currentVersion,
    );
  }
}
