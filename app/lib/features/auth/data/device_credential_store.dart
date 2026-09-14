import 'device_credential.dart';

/// Loads the device's [DeviceCredential], generating and persisting one on first launch (Story
/// 7.1, AC1) — the 256-bit entropy is generated exactly once per device/reinstall and then reused
/// forever, so provisioning re-derives the same keypair (and Keycloak username) on every launch.
/// Abstracted so the sign-in client never touches real secure storage or randomness in tests
/// (CLAUDE.md §6).
///
/// Story 7.2 adds the two recovery-phrase primitives. Both stay word-shaped at this boundary: the
/// raw entropy never leaves an implementation — callers (the reveal UI, [AuthCubit.recoverFromPhrase])
/// deal only in `List<String>` words (AC4).
abstract interface class DeviceCredentialStore {
  Future<DeviceCredential> loadOrCreate();

  /// The device's current entropy, BIP39-encoded as its 24-word recovery phrase (AC1/AC2) — a
  /// pure local read, never fetched from or sent to the server. The entropy must already exist by
  /// the time this is called (7.1 generates it at first launch, before any reveal UI is reachable).
  Future<List<String>> recoveryPhrase();

  /// Validates [words] as a BIP39 mnemonic and, on success, reconstructs the 32-byte entropy and
  /// writes it to the device's secure storage, **replacing** whatever entropy is currently stored
  /// (AC3) — the identity swap [AuthCubit.recoverFromPhrase] drives. Throws
  /// [InvalidRecoveryPhrase] on any invalid input and writes nothing (fail fast, CLAUDE.md §1).
  Future<void> restoreFromPhrase(List<String> words);
}
