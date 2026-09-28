import 'device_credential.dart';

/// Loads the device's [DeviceCredential], generating and persisting one on first launch (Story
/// 7.1, AC1) — the 128-bit entropy is generated exactly once per device/reinstall and then reused
/// forever, so provisioning re-derives the same keypair (and Keycloak username) on every launch.
/// Abstracted so the sign-in client never touches real secure storage or randomness in tests
/// (CLAUDE.md §6).
///
/// Both recovery-token primitives (reveal, restore) existed since Story 7.2/7.3; Story 8.5 only
/// changed the token's wire *format* (phrase → Crockford Base32, D1). Both stay token-shaped at
/// this boundary: the raw entropy never leaves an implementation — callers (the reveal UI,
/// [AuthCubit.recoverFromToken]) deal only in the formatted [String] token (AC4).
abstract interface class DeviceCredentialStore {
  Future<DeviceCredential> loadOrCreate();

  /// The device's current entropy, Crockford-Base32-encoded as its short recovery token (AC1/AC2) —
  /// a pure local read, never fetched from or sent to the server. The entropy must already exist by
  /// the time this is called (7.1 generates it at first launch, before any reveal UI is reachable).
  Future<String> recoveryToken();

  /// Validates [token] and, on success, reconstructs the 16-byte entropy and writes it to the
  /// device's secure storage, **replacing** whatever entropy is currently stored (AC3) — the
  /// identity swap [AuthCubit.recoverFromToken] drives. Throws [InvalidRecoveryToken] on any invalid
  /// input and writes nothing (fail fast, CLAUDE.md §1).
  Future<void> restoreFromToken(String token);
}
