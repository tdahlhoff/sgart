import '../sync/household_change_nudge.dart';

/// Abstract port over the device's push-notification transport (Story 4.5, D2, AC1/AC5):
/// obtaining/refreshing this device's token and registering it with the backend, and surfacing
/// incoming content-free pushes as a stream of wake-and-fetch nudges (LD-1) — the exact same
/// content-free shape as the 4.4 SSE nudge ([HouseholdChangeNudge]: `householdId` + `resource`
/// only), never item/list/receipt content.
///
/// The concrete `firebase_messaging`-backed implementation and native platform configuration
/// (`google-services.json`/APNs) are a manual follow-up (D2, documented in
/// `docs/first-real-world-test`) — deliberately not wired in this story (no live Firebase
/// credentials, un-unit-testable). Only this port, [BackendDeviceRegistrationClient], and a fake
/// exist so `flutter test`/`flutter analyze` stay green without external accounts.
abstract class PushNotifications {
  /// Obtains (or refreshes) this device's token and registers it with the backend
  /// (`POST /api/v1/devices`). Call on sign-in and whenever the transport reports a refreshed
  /// token.
  Future<void> register();

  /// Unregisters this device's token (`DELETE /api/v1/devices/{token}`) — call on sign-out (AC5).
  Future<void> unregister();

  /// Incoming content-free pushes, surfaced while the app is foregrounded. A backgrounded/killed
  /// app's push is handled by the OS/native layer, out of scope for this port (D2 follow-up).
  Stream<HouseholdChangeNudge> get incomingPushes;
}
