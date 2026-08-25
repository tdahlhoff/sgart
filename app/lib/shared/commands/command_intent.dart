import 'package:uuid/uuid.dart';

/// Mints an idempotency-key string. Defaults to a random UUID v4; injectable so tests can produce
/// deterministic, assertable ids.
typedef IdFactory = String Function();

String _defaultMintId() => const Uuid().v4();

/// A per-intent set of client-minted ids — a [commandId] plus any paired client-minted resource ids
/// (e.g. a store id the client mints for read-your-writes) — with the lifecycle that keeps SGART's
/// command contract safe. It exists because the same three-part rule was re-implemented (and got
/// subtly wrong) in every write cubit across Epic 1:
///
/// * **Stable across retries of the same intent** — a resubmit after a lost response reuses the same
///   [commandId], so the backend's `(stream, commandId)` dedupe *converges* instead of creating a
///   duplicate (AD-8). Any paired resource id is reused too, so the client's optimistic id matches
///   the one the server persisted.
/// * **Fresh when the payload changes** — an edited retry is a *new* intent, so it must not dedupe as
///   a silent no-op against an earlier append that may have landed server-side despite a lost
///   response (the bug that left the client showing the edited value while the server kept the first
///   — Story 1.7).
/// * **Fresh after a completed intent** — once an append has succeeded, the next command must not
///   reuse a [commandId] the server has already applied, which it would silently drop (the dropped
///   second-store bug — Story 1.8).
///
/// Usage per attempt: call [beginAttempt] with a value-equality payload key (a trimmed name, say),
/// read [commandId] / [resourceId], send the command, and call [complete] on success. A caller that
/// wants a single stable id for a whole form's lifetime (one-way creation — Story 1.9) simply reads
/// [commandId] and never calls [beginAttempt] or [complete].
class CommandIntent {
  CommandIntent({this._hasResourceId = false, IdFactory? mintId}) : _mintId = mintId ?? _defaultMintId {
    _mintAll();
  }

  final bool _hasResourceId;
  final IdFactory _mintId;

  late String _commandId;
  String? _resourceId;
  Object? _activeKey;

  /// The command id for the current intent — the idempotency key the backend dedupes on.
  String get commandId => _commandId;

  /// The paired client-minted resource id for the current intent. Throws if the intent was not
  /// created with one.
  String resourceId() {
    final resourceId = _resourceId;
    if (resourceId == null) {
      throw StateError('This CommandIntent was not created with a resource id.');
    }
    return resourceId;
  }

  /// Begins (or retries) the attempt identified by [payloadKey]. Keeps the current ids for the first
  /// attempt or a retry with the *same* key (an idempotent retry); mints a fresh set when the key
  /// differs from the current attempt (an edited retry is a new intent). [payloadKey] must have value
  /// equality.
  void beginAttempt(Object payloadKey) {
    if (_activeKey != null && _activeKey != payloadKey) {
      _mintAll();
    }
    _activeKey = payloadKey;
  }

  /// Completes the current intent: the next [beginAttempt] starts from a fresh id set, so a new
  /// command never reuses an id the server has already applied.
  void complete() {
    _mintAll();
    _activeKey = null;
  }

  void _mintAll() {
    _commandId = _mintId();
    _resourceId = _hasResourceId ? _mintId() : null;
  }
}
