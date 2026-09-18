import 'package:flutter/foundation.dart';

/// The base URL every shareable invite link is built from (Story 7.5, AC1) — the exact shape the
/// backend's `sgart.invite.base-url` (`InviteLinkFactory`) also uses, so link, deep link, and web
/// fallback stay one source of truth. Dev-only default matches `backend/application.yaml`'s dev
/// profile; override via `--dart-define=SGART_INVITE_BASE_URL=...` per environment.
abstract final class InviteLinkConfig {
  static const String _localhostDefault = 'http://localhost:8081/invite';

  static const String _configured = String.fromEnvironment(
    'SGART_INVITE_BASE_URL',
    defaultValue: _localhostDefault,
  );

  /// Fails fast in a release build that never supplied a real `--dart-define=SGART_INVITE_BASE_URL`
  /// rather than silently shipping invite links/QRs that point at the localhost dev default (or a
  /// blank value). Mirrors the backend's boot-time config guard. A plain `assert` would not do:
  /// asserts are stripped from release builds, exactly where this mistake matters.
  static String get baseUrl {
    if (rejectsConfiguredBaseUrl(isReleaseMode: kReleaseMode, configured: _configured)) {
      throw StateError(
        'SGART_INVITE_BASE_URL must be supplied via --dart-define in a release build; '
        'refusing to build invite links from the localhost dev default or a blank value.',
      );
    }
    return _configured;
  }

  /// The pure decision behind [baseUrl]'s release-mode guard, exposed for unit testing because
  /// `kReleaseMode` is a compile-time `false` under `flutter test` — the guarded branch is
  /// otherwise unreachable by the test runner. A release build must never ship invite links built
  /// from the localhost dev default or a blank/whitespace base URL.
  @visibleForTesting
  static bool rejectsConfiguredBaseUrl({required bool isReleaseMode, required String configured}) {
    if (!isReleaseMode) {
      return false;
    }
    final trimmed = configured.trim();
    return trimmed.isEmpty || trimmed == _localhostDefault;
  }
}
