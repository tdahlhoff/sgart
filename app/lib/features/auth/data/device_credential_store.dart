import 'device_credential.dart';

/// Loads the device's [DeviceCredential], generating and persisting one on first launch (Story
/// 7.1, AC1) — the 256-bit entropy is generated exactly once per device/reinstall and then reused
/// forever, so provisioning re-derives the same keypair (and Keycloak username) on every launch.
/// Abstracted so the sign-in client never touches real secure storage or randomness in tests
/// (CLAUDE.md §6).
abstract interface class DeviceCredentialStore {
  Future<DeviceCredential> loadOrCreate();
}
