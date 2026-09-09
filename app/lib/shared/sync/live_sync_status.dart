/// The live-sync connection's observable lifecycle (Story 4.4, T7/T10): drives the app-bar status
/// indicator. `revoked` is terminal — reached only when the server rejects the connection with a
/// 4xx (most notably a `403` after the member was removed, AC3) — the client never reconnect-loops
/// from there.
enum LiveSyncStatus {
  /// The very first connection attempt is in flight.
  connecting,

  /// Connected and receiving events.
  live,

  /// A previously live (or attempted) connection dropped and a retry is scheduled/in flight.
  reconnecting,

  /// Stopped deliberately (household switch, sign-out) — not an error.
  offline,

  /// The server rejected the connection with a 4xx — most notably `403` (AC3, "mapping = access").
  /// Terminal: the client does not reconnect-loop a revoked stream.
  revoked,
}
