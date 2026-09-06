/// Shallow client-side fail-fast check (Story 4.1, AC7) — mirrors the backend's own shallow
/// syntactic check (`NormalizedEmail`, application layer): rejects the obviously malformed before a
/// round-trip, never claims to validate deliverability. The server remains the source of truth
/// (`invite.emailInvalid`), so this only improves the inline experience.
final RegExp _emailPattern = RegExp(r'^[^\s@]+@[^\s@]+\.[^\s@]+$');

bool isPlausibleEmail(String value) => _emailPattern.hasMatch(value.trim());
