/// A parsed personal invite link/code (Story 4.2, AC6, decision 1) — a value, not a widget or
/// network concern, so 4.6's OS deep-link handler reuses [tryParse] unchanged.
///
/// Accepts the canonical `https` link SGART will also deep-link/web-fallback in 4.6
/// (`.../invite?h=<householdId>&i=<inviteId>`), a bare query-string code (`h=..&i=..`), or a short
/// `householdId:inviteId` code form. Anything else — including a missing/blank id — is malformed
/// and yields `null`, driving the caller's client-side fail-fast (no network round-trip for an
/// unparseable link).
class InviteLink {
  const InviteLink({required this.householdId, required this.inviteId});

  final String householdId;
  final String inviteId;

  static InviteLink? tryParse(String raw) {
    final trimmed = raw.trim();
    if (trimmed.isEmpty) {
      return null;
    }

    final colonForm = _tryParseColonForm(trimmed);
    if (colonForm != null) {
      return colonForm;
    }

    return _tryParseQueryForm(trimmed);
  }

  static InviteLink? _tryParseColonForm(String raw) {
    if (raw.contains('=') || raw.contains('/')) {
      // Not the bare code form — let the query/URL parser handle it.
      return null;
    }
    final parts = raw.split(':');
    if (parts.length != 2) {
      return null;
    }
    return _fromParts(parts[0], parts[1]);
  }

  static InviteLink? _tryParseQueryForm(String raw) {
    final uri = Uri.tryParse(raw);
    if (uri == null) {
      return null;
    }
    try {
      final queryParameters = uri.hasQuery ? uri.queryParameters : Uri.splitQueryString(raw);
      return _fromParts(queryParameters['h'], queryParameters['i']);
    } on FormatException {
      // A malformed percent-escape (e.g. "%zz") in the query string — treat as unparseable.
      return null;
    } on ArgumentError {
      // Some malformed percent-escapes surface as ArgumentError instead of FormatException.
      return null;
    }
  }

  static InviteLink? _fromParts(String? householdId, String? inviteId) {
    if (householdId == null || inviteId == null) {
      return null;
    }
    final trimmedHouseholdId = householdId.trim();
    final trimmedInviteId = inviteId.trim();
    if (trimmedHouseholdId.isEmpty || trimmedInviteId.isEmpty) {
      return null;
    }
    return InviteLink(householdId: trimmedHouseholdId, inviteId: trimmedInviteId);
  }

  @override
  bool operator ==(Object other) =>
      other is InviteLink && other.householdId == householdId && other.inviteId == inviteId;

  @override
  int get hashCode => Object.hash(householdId, inviteId);
}
