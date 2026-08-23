/// Client mirror of the shared `ErrorDescriptor` shape `{ code, message, details }`
/// (ARCHITECTURE-SPINE Consistency Conventions). Field names match the backend record exactly
/// so this deserializes 1:1 once REST endpoints ship (Stories 1.4/1.5).
///
/// `message` is log/debug only and must never be shown to a user — see
/// `localizedMessageForErrorCode` in `error_message_resolver.dart` for the user-facing copy.
///
/// This is an immutable value type: two errors carrying the same `code`, `message`, and `details`
/// are equal.
class AppError {
  const AppError({required this.code, required this.message, this.details = const {}});

  final String code;
  final String message;
  final Map<String, Object?> details;

  @override
  bool operator ==(Object other) =>
      other is AppError &&
      other.code == code &&
      other.message == message &&
      _detailsEqual(other.details, details);

  @override
  int get hashCode => Object.hash(code, message, Object.hashAllUnordered(details.keys));

  static bool _detailsEqual(Map<String, Object?> a, Map<String, Object?> b) {
    if (a.length != b.length) return false;
    for (final entry in a.entries) {
      if (!b.containsKey(entry.key) || b[entry.key] != entry.value) return false;
    }
    return true;
  }
}
