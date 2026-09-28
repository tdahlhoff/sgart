import 'oidc_tokens.dart';

/// The browserless sign-in boundary against Keycloak (Story 7.1, AC1 — supersedes Story 1.4's
/// Authorization Code + PKCE browser flow). SGART never sees or stores a password, or shows a
/// browser: [signIn] silently provisions the device's Keycloak account and signs in via the
/// custom Direct-Grant flow, proving possession of the device credential with a signed challenge
/// instead. Abstracted so [AuthCubit] tests never touch real cryptography, device storage, or
/// network (CLAUDE.md §6).
abstract interface class OidcClient {
  /// Loads or creates the device credential, silently provisions its Keycloak account if needed,
  /// and signs in via the custom Direct-Grant flow (AC1/AC2) — no input, no browser surface.
  ///
  /// Runs *inside* `AuthCubit.tryReauthenticate`'s device-credential fallback, so implementations
  /// must never issue a request through a refresh-retrying `AuthenticatedHttpClient` (one built
  /// with a `refreshTokens` callback) — doing so would call back into the very
  /// `tryReauthenticate` attempt this method is already running under, which shares one in-flight
  /// `Future` per attempt and would await itself forever. [DirectGrantOidcClient] keeps every
  /// request on this path off that client for exactly this reason: `postJsonUnauthenticated` for
  /// provisioning, and its own plain `Dio` (no interceptors, no refresh callback) for the token
  /// exchange itself.
  Future<OidcTokens> signIn();

  /// Exchanges a stored refresh token for a fresh set of [OidcTokens] without any user
  /// interaction, so a session survives the short access-token lifespan. Throws when the refresh
  /// token is itself expired or revoked.
  ///
  /// Also runs inside `AuthCubit.tryReauthenticate` — the same constraint as [signIn] applies.
  Future<OidcTokens> refresh(String refreshToken);
}
