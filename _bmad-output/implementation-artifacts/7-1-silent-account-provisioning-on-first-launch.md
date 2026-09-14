---
baseline_commit: cefa63d3ebe506ed0e21c323ba43212718be0d17
---

# Story 7.1: Silent account provisioning on first launch

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a person opening SGART for the first time,
I want a working account with no setup,
so that I can start using the app immediately — with no email, username, password, or browser.

This is **Epic 7's first build story** (7.0 was a resolved spike). It replaces the manual "create the
account in the Keycloak admin console" beta stopgap and **supersedes Story 1.4's AppAuth/browser
sign-in transport** with a browserless, app-direct login. On first launch the app generates a
device-side secret in the secure enclave, the backend silently creates a Keycloak account bound to
that secret's **public** key (Admin API), and the person is signed in via a **custom Direct-Grant
authenticator SPI** that verifies a device-signed challenge — before the create/await-invite choice
(Story 1.6) is ever shown, with no intervening screen and no browser surface.

It does **not** reveal the recovery phrase (that is Story 7.2, at first household create/join) and does
**not** attach email (Story 7.3). It builds the **shared credential model and the login mechanism**
that 7.2/7.3 reuse. The authoritative design for this slice is
`_bmad-output/planning-artifacts/epic-7-story-7.1-provisioning-design.md` (Winston, 2026-09-14) — read
it first.

## Context — what already exists vs. what 7.1 adds

**Already built (reuse verbatim, do NOT recreate):**
- The `identity` bounded context with the Keycloak Admin adapter pattern —
  `KeycloakAdminFindHouseholdMemberByEmail` (Story 4.6): client-credentials token fetch, `RestClient`
  against the Admin REST API, **all Keycloak/HTTP types contained in the adapter** (AD-1/AD-2),
  config-gated in `IdentityBeansConfig` with a Deferred no-op default so CI/local need no admin
  credentials.
- The Identity ACL write port `IssueMemberIdentity` (`provision`/`persist`/`retract`, idempotent) and
  `MemberMapping = (householdId, memberId, keycloakUserId)` via `JdbcMemberMappingRepository` — the
  established precedent that **the identity ACL holds non-event-sourced JDBC state** (so a new JDBC
  account row does not violate AD-1's event-sourcing rule).
- `SecurityConfig` (identity `adapter.in.security`) — stateless JWT resource server; ordered matchers
  `.requestMatchers("/api/v1/**").authenticated()` then `.anyRequest().permitAll()`; `AudienceValidator`.
- `AuthenticatedHttpClient` (`app/lib/shared/http/authenticated_http_client.dart`) — the centralized
  **401 refresh-and-retry** seam (commit `73a3b93`). Its refresh path stays app→Keycloak and is
  **retained**; only the initial sign-in call is swapped.
- `flutter_secure_storage: ^11.0.0` (Android Keystore / iOS Keychain), `app_links: ^7.1.1` — already
  dependencies.
- Keycloak `26.7.0` (`docker-compose.yml:54`); realm `sgart-app` public client
  (`directAccessGrantsEnabled: false`) and `sgart-admin` confidential service account
  (`realm-management: ["view-users"]`, dev placeholder secret at `keycloak/realm-sgart.json:90`).

**7.1 adds:**
- A **new Gradle module in the backend build** (`backend/keycloak-authenticator/`, F2) producing a
  Keycloak **custom Direct-Grant authenticator SPI** (provider JAR), deployed into the Keycloak
  container and bound to a custom Direct Grant flow.
- A **full hexagonal provisioning slice** in `identity`: an unauthenticated `POST /api/v1/accounts`
  endpoint, a flat idempotent `ProvisionAccount` application service (no domain event; F1), a
  `KeycloakAdminCreateAccount` out-adapter, and a **minimal no-PII `ProvisionedAccount` row** with its
  JDBC repository.
- A **retention sweep** deleting never-activated accounts after 14 days.
- Realm/permission changes: `sgart-app` → `directAccessGrantsEnabled: true` + custom flow bound;
  `sgart-admin` → add `manage-users`.
- Flutter: the device-secret credential module (entropy → enclave → Ed25519 keypair → derived
  username), the provisioning call, and the browserless Direct-Grant login client that **removes**
  `flutter_appauth` + `app_auth_oidc_client.dart`.
- The Story 7.0 **acceptance integration test** (Testcontainers Keycloak with the provider JAR mounted).

## Locked Decisions (Timo, 2026-09-14)

The mechanism is **LOCKED** by the Story 7.0 spike (custom Direct-Grant authenticator SPI; do not
re-open). The design-note forks are resolved:

- **Credential model.** 256-bit entropy generated once on first launch, stored in the secure enclave
  (`flutter_secure_storage`). It is BIP39-encodable to a 24-word recovery phrase (**not shown in 7.1**),
  and deterministically derives an **Ed25519** keypair (the 32 entropy bytes are the Ed25519 seed).
  Only the **public** key is registered with Keycloak; the private key/seed never leave the device.
- **Username = deterministic encoding of the public key** (base64url of the 32-byte Ed25519 public
  key). Computed locally, so recovery (7.2) needs no server lookup and provisioning is idempotent.
- **D-A: minimal `ProvisionedAccount { keycloakUserId, provisionedAt }` row** (no PII). "Activated" is
  **derived** — an account is activated iff a `MemberMapping` exists for its `keycloakUserId` (and, from
  7.3, an email is attached). No `activatedAt` column.
- **D-B: replay guard = signed `(username|timestamp|nonce)` in one token POST.** The SPI verifies the
  Ed25519 signature + a tight time window (~60s) + a small server-side seen-cache of client nonces.
- **D-C: retention TTL = 14 days** from `provisionedAt` for never-activated shells.
- **D-D: the public key is stored as a Keycloak user attribute** (simplest for the SPI to read).
- **D-E: endpoint = `POST /api/v1/accounts`**, with a `permitAll` matcher declared **above**
  `/api/v1/**` in `SecurityConfig`; commented as the single deliberate unauthenticated write surface.
- **Rate limiting is IP-based at the TLS reverse proxy** (ADR-0002), not app code — a documented beta
  seam (the reverse proxy is not yet deployed); device attestation is a named post-beta fast-follow.
- **F1 (validation ruling, 2026-09-14): follow the identity-context convention, not the proposal's
  CQRS wording.** The `identity` ACL uses flat verb-phrase application services (`IssueMemberIdentity`,
  `RegisterDeviceToken`) with **no** command DTOs and **no** `*Handler` suffix. `ProvisionAccount` is a
  flat `identity.application` service class — **not** a `application.command` command+handler. This
  consciously diverges from `sprint-change-proposal-2026-09-13.md` §4's "ProvisionAccount command +
  handler (CQRS)" wording: the proposal author did not check the identity context's actual style, and
  CLAUDE.md §4 ("do not force CQRS onto trivial CRUD where it only adds ceremony") + §8 (consistency)
  favor the existing convention. Still emits no domain event; still idempotent.
- **F2 (validation ruling, 2026-09-14): the SPI is a module inside the backend Gradle build.** The build
  is rooted at `backend/` (`backend/gradlew`, `backend/settings.gradle.kts`, no root project). Add the
  SPI as `backend/keycloak-authenticator/` via `include(":keycloak-authenticator")` — its own provider
  JAR with Keycloak SPI deps `compileOnly`, **not** a dependency of the backend app, so
  `backend/./gradlew test` covers it in one green build.

## Acceptance Criteria

From `epics.md#Story 7.1` (BDD), with the locked decisions applied. Where end-to-end verification needs
the not-yet-deployed reverse proxy, the **code/config seam** is in scope and the **live verification**
is a documented follow-up — each AC says which.

1. **AC1 — Zero-input provisioning + browserless sign-in before any screen.** Given a fresh install
   with no stored session, when the app starts, then it (a) generates a 256-bit device secret in the
   secure enclave, (b) derives the Ed25519 keypair and the base64url public-key username, (c) calls
   `POST /api/v1/accounts` with the public key + platform, which creates a Keycloak account bound to
   that key (Admin API — no email/username shown or requested), and (d) signs in via the custom
   Direct-Grant flow (grant_type=password, `client_id=sgart-app`, `username=<derived>`,
   `signed_challenge=<sig over username|ts|nonce>`) obtaining a valid JWT — **all before** the
   create/await-invite choice (Story 1.6) is shown, with no intervening screen and **no browser
   surface** (no Custom Tab, no URL bar, no system sign-in prompt). Story 1.4's ACs ("client obtains a
   JWT; backend validates it; id taken from `sub`") stay true — only how the JWT is obtained changes.

2. **AC2 — The SPI issues an `adapter.in`-accepted JWT (the Story 7.0 acceptance, proven at build).**
   Given a Keycloak account created via the Admin API with a registered public key, when the app
   authenticates through the custom Direct-Grant flow with a valid device-signed challenge, then it
   receives a Keycloak-signed JWT accepted at `adapter.in` (signature/issuer/audience) — with no browser
   and no password/ROPC. A **wrong or absent** signature is rejected; a **replayed** challenge (reused
   nonce, or timestamp outside the window) is rejected. `sgart-app` has `directAccessGrantsEnabled:
   true` with the custom flow bound; `sgart-admin` has `manage-users` (no impersonation/token-exchange).

3. **AC3 — Provisioning is idempotent and fails fast.** Given the same device secret (same derived
   username), when `POST /api/v1/accounts` is called more than once (retry after a dropped response),
   then no second Keycloak account and no second `ProvisionedAccount` row are created — the retry
   succeeds as a no-op. Given a malformed/absent public key, then the endpoint returns a 4xx (never a
   500) and creates nothing. The endpoint returns **`204 No Content`** on success (CQRS — no domain data
   returned; the app derives the username locally and reads `sub` from the JWT).

4. **AC4 — `POST /api/v1/accounts` is the only unauthenticated write; nothing else opens up.** Given the
   `SecurityConfig` permit matcher, when any request hits `POST /api/v1/accounts`, then it is reachable
   without a JWT; when any **other** `/api/v1/**` request arrives without a valid JWT, then it is
   rejected `401` (guard against the permit matcher being too broad). The permit matcher is scoped to
   exactly that method+path and declared above `/api/v1/**`.

5. **AC5 — Never-activated shells are retention-swept (GDPR storage limitation).** Given a
   `ProvisionedAccount` whose `provisionedAt` is older than **14 days** and whose `keycloakUserId` has
   **no** `MemberMapping` (and no attached email), when the retention sweep runs, then it deletes the
   Keycloak account (Admin API) **and** the `ProvisionedAccount` row. Given an **activated** account
   (has a `MemberMapping`), when the sweep runs, then it is **not** deleted regardless of age.

6. **AC6 — The old AppAuth transport is removed, the refresh seam retained.** Given the app after this
   story, when it authenticates, then `flutter_appauth` and `app_auth_oidc_client.dart` are gone and a
   plain token-endpoint client is used instead; the `AuthenticatedHttpClient` 401 refresh-and-retry seam
   (commit `73a3b93`) still refreshes app→Keycloak. No browser dependency remains in the auth path.

7. **AC7 — Rate limiting seam documented (live verification deferred).** Given the create-account
   endpoint is unauthenticated by nature, when beta infra is built, then IP-based rate limiting lives at
   the TLS reverse proxy (ADR-0002); this story **documents** that seam and its known limits
   (CGNAT/IP-rotation → speed bump, not a wall) in `docs/first-real-world-test.md`. Device attestation
   (Play Integrity / DeviceCheck) is a named post-beta fast-follow, **not** in this story.

8. **AC8 — Green build across both suites + dependency currency.** Given the change touches backend, the
   new SPI subproject, and the app, when the story completes, then backend `./gradlew test` (incl.
   ArchUnit + Testcontainers) **and** app `flutter test` / `flutter analyze` are named and green. New
   deps (Keycloak SPI libs matched to `26.7.0`; the Flutter BIP39/Ed25519 packages) are pinned to their
   current supported majors (CLAUDE.md §7).

## Tasks / Subtasks

### Keycloak custom Direct-Grant authenticator SPI (new subproject) — AC2

- [x] Create `backend/keycloak-authenticator/` as a module in the backend build (F2): add
      `include(":keycloak-authenticator")` to `backend/settings.gradle.kts`; its own `build.gradle.kts`;
      **not** a dependency of the backend app module. `compileOnly` deps: `org.keycloak:keycloak-server-spi`,
      `keycloak-server-spi-private`, `keycloak-services` — all pinned to **`26.7.0`** (AC8, CLAUDE.md §7).
      `backend/./gradlew test` must build and test it (verify it is picked up).
- [x] Implement an `Authenticator` + `AuthenticatorFactory` (registered via
      `META-INF/services/org.keycloak.authentication.AuthenticatorFactory`) that: reads the token
      request's `username` + `signed_challenge` form params, loads the user, reads the `publicKey` user
      attribute (D-D), verifies the Ed25519 signature over `username|timestamp|nonce`, enforces the ~60s
      window, and rejects a reused nonce via a small server-side seen-cache (D-B). Success → `context.success()`.
- [x] Fail-fast on missing/invalid params, unknown user, bad signature, stale timestamp, or replayed
      nonce → `context.failure(INVALID_CREDENTIALS)` (no user enumeration difference).

### Keycloak realm + deployment config — AC2, AC7

- [x] Add the provider JAR to the Keycloak container (`docker-compose.yml` volume into
      `/opt/keycloak/providers` + `kc.sh build` step); keep `26.7.0`.
- [x] `keycloak/realm-sgart.json`: define a **custom Direct Grant flow** using the new authenticator;
      set `sgart-app` `directAccessGrantsEnabled: true` and bind that flow; add `manage-users` to
      `sgart-admin`'s `realm-management` client roles (alongside `view-users`).
- [x] Note (do not fix here, but flag): the `sgart-admin` dev placeholder secret
      (`keycloak/realm-sgart.json:90`) must be replaced with real secret management before beta ships —
      creating real accounts makes this urgent. Add/confirm the follow-up in `docs/first-real-world-test.md`.

### Backend — provisioning slice (`identity`) — AC1, AC3, AC4

- [x] `identity.application`: a flat `ProvisionAccount` service class (F1 — mirror `IssueMemberIdentity`;
      **no** `application.command` subpackage, **no** `*Handler` suffix). A `provision(publicKey, platform)`
      method, idempotent on the derived username, orchestrates the two out-ports, emits **no** domain
      event, returns nothing.
- [x] `domain`: `ProvisionedAccount` (value/entity: `keycloakUserId` + `provisionedAt`) +
      `ProvisionedAccountRepository` port. `adapter.out`: `JdbcProvisionedAccountRepository` + Flyway
      migration **`V18__provisioned_account.sql`** (V17 is current latest) for a no-PII `provisioned_account`
      table (indexed on `provisioned_at`).
- [x] `adapter.out`: `KeycloakAdminCreateAccount` implementing a `CreateAccount` port — clone the
      `KeycloakAdminFindHouseholdMemberByEmail` shape (client-credentials token, `RestClient`,
      contained types); `POST /admin/realms/{realm}/users` with the derived username + `publicKey`
      attribute (D-D); treat "user already exists" as success (idempotent). Config-gate in
      `IdentityBeansConfig` with a Deferred no-op default (CI/local need no admin creds).
- [x] `adapter.in`: `AccountController` (or extend `IdentityController`) — `POST /api/v1/accounts`,
      request DTO `{ publicKey, platform }`, `204 No Content`; error advice maps bad input → 4xx.
- [x] `SecurityConfig`: add `.requestMatchers(HttpMethod.POST, "/api/v1/accounts").permitAll()` **above**
      `/api/v1/**`.authenticated(); comment it as the single deliberate unauthenticated write surface (D-E).

### Backend — retention sweep — AC5

- [x] A scheduled server-side job (`@Scheduled`, config-gated so tests drive the sweep method directly;
      ensure `@EnableScheduling` is present — add it if not already enabled, F4) that finds
      `ProvisionedAccount` rows with `provisioned_at < now-14d` **and** no `MemberMapping` for the
      `keycloakUserId`, then deletes the Keycloak account (Admin API `DELETE …/users/{id}`) and the row.
      TTL is config (`sgart.identity.provisioning.retention-days=14`, D-C).
- [x] Erasure integration (AD-7): add `ProvisionedAccount` to the erasure checklist so Epic 6 removes it
      alongside mapping rows + read models + Keycloak account (documented; Epic 6 owns the wiring).

### Client — Flutter credential + provisioning + browserless login — AC1, AC6

- [x] Credential module: generate 256-bit entropy → `flutter_secure_storage`; derive the Ed25519 keypair
      (deterministic from the entropy) and the base64url public-key username. Add BIP39 + Ed25519 packages
      pinned to current supported majors (AC8). **No phrase UI in this story** (7.2 owns reveal).
- [x] First-launch flow: if no stored session, POST `/api/v1/accounts`, then Direct-Grant login
      (grant_type=password, `client_id=sgart-app`, `username`, `signed_challenge`) → store tokens; route
      to the existing create/await-invite choice (Story 1.6) with no intervening screen.
- [x] Replace the AppAuth client: delete `app/lib/features/auth/data/app_auth_oidc_client.dart` and remove
      `flutter_appauth` from `pubspec.yaml`; implement a plain token-endpoint client. **Retain**
      `AuthenticatedHttpClient`'s refresh seam (app→Keycloak); rewire only its initial sign-in call.
- [x] Remove the now-dead AppAuth Android/iOS redirect config **without** breaking `app_links`' `invite`
      host (Story 4.6) — the `oauth` redirect host goes; the `invite` deep link stays.

### Docs, dependency currency, full build — AC7, AC8

- [x] `docs/first-real-world-test.md`: document the reverse-proxy IP rate-limit seam + limits (AC7), the
      `sgart-admin` real-secret follow-up, and the SPI provider-JAR deploy/build step.
- [x] Confirm Keycloak stays `26.7.0` and SPI deps match; flag any newer supported major (CLAUDE.md §7).
- [x] Run and name both suites green: backend `./gradlew test` (incl. ArchUnit + Testcontainers) **and**
      app `flutter test` / `flutter analyze`.

### Definition of Done (standing, per retros)

- [x] No dead strings/fields/stale comments (the removed AppAuth path leaves nothing behind).
- [x] Fail-fast guards on the endpoint inputs; a11y labels on any new interactive widget (minimal here).
- [x] `commandId`/`basedOnVersion` N/A (provisioning issues no event-sourced command).
- [x] Both test suites named at completion; a red build blocks (CLAUDE.md §6).

## Dev Notes

### Ground truth — read these before coding
- **`_bmad-output/planning-artifacts/epic-7-story-7.1-provisioning-design.md`** — the authoritative
  design for this slice (credential model §2, the hexagonal slice §3, retention §4, decisions §7).
- `sprint-change-proposal-2026-09-13.md` (rev E) §4 — the PRD/epics/spine amendments and the credential
  model narrative; §5 the resolved decisions.
- `ARCHITECTURE-SPINE.md` — AD-1 (hexagonal + event-sourcing), AD-2 (context boundaries; Keycloak types
  contained in adapters), AD-5/AD-6/AD-7 (pseudonymous ids, no persisted PII, erasure by de-linking),
  and the rev-E "Frictionless account provisioning" beta bullet.
- `epics.md` §972+ (Epic 7, Story 7.0 resolution + Story 7.1 ACs).

### Patterns to mirror (exact files)
- `KeycloakAdminFindHouseholdMemberByEmail.java` — the **exact** shape for `KeycloakAdminCreateAccount`
  (token fetch, `RestClient`, contained records, config-gate + Deferred default).
- `IssueMemberIdentity.java` / `JdbcMemberMappingRepository` — the ACL's non-event-sourced JDBC state
  precedent; `ProvisionedAccountRepository` follows the same port/adapter split.
- `SecurityConfig.java` — ordered matchers; add the permit **above** the authenticated rule.
- `AuthenticatedHttpClient` (`app/lib/shared/http/`) — retain refresh; swap only initial sign-in.

### The credential-model crux (get this exactly right — AC1/AC2)
- The **entropy is the seed**: 32 bytes of entropy = the Ed25519 seed = BIP39 24-word source. All three
  representations (enclave bytes, phrase, keypair) are the *same* secret in different encodings. Do not
  invent a second key-derivation step.
- **Username is derived, never stored client-side and never returned by the server.** Recovery (7.2)
  depends on the app recomputing it from the phrase alone — so keep the derivation pure and stable
  (base64url(pubkey)); changing it later would strand existing accounts.
- **Nothing secret is ever sent to a server.** Keycloak stores only the public key. The provisioning
  request carries the public key; the login request carries a signature, never the seed/private key.

### Security / SecurityConfig crux (AC4)
- Spring matches matchers **in order**. The new permit MUST precede `/api/v1/**`.authenticated() or it
  is shadowed and the endpoint 401s; scope it to `POST` + the exact path so nothing else opens up. The
  AC4 test must assert both halves (endpoint open; every other `/api/v1/**` still 401 without a JWT).

### SPI / replay crux (AC2, D-B)
- Direct Grant is a single token POST — the nonce rides **inside** the signed payload
  (`username|timestamp|nonce`), not a separate round-trip. The seen-cache only needs to remember nonces
  within the ~60s window (a small TTL map), so it stays bounded. State the window + cache in the SPI.
- The acceptance test (Story 7.0, landing here) runs Testcontainers Keycloak **with the provider JAR
  mounted** and the custom flow bound — this is the only way to prove the end-to-end sign-in.

### GDPR / privacy (CLAUDE.md §5, AD-5/AD-6/AD-7)
- `ProvisionedAccount` holds only a pseudonymous `keycloakUserId` + timestamp — **no PII** (AD-6 holds).
- The silent shell's lawful basis is legitimate interest / performance of the service just requested
  (Story 7.4 note); no *household* PII is processed before the 7.4 consent moment (out of this story).
- Retention sweep (AC5) is the storage-limitation control; erasure inclusion (AD-7) is documented for
  Epic 6. Tests for the sweep + erasure inclusion are first-class (CLAUDE.md §6).

### Scope guards (KISS / YAGNI — CLAUDE.md §1)
- **Not in 7.1:** recovery-phrase reveal/re-view (7.2), recover-by-email + SMTP (7.3), consent capture
  (7.4), invite-by-code (7.5), device attestation (post-beta), the live reverse-proxy rate limiter
  (documented seam only).
- **In 7.1:** the credential module (generate/store/derive, no phrase UI), the provisioning slice, the
  SPI + realm wiring, the retention sweep, and the AppAuth removal.

### Previous-work intelligence
- Story 4.6 Dev Agent Record — the Keycloak Admin adapter, config-gate, and Deferred-default pattern in
  practice; the `app_links` host-scoping (`invite` vs `oauth`) that this story must not break.
- Commit `73a3b93` — the centralized 401 refresh-and-retry seam this story retains.

### Testing standards (CLAUDE.md §6)
- Handler unit tests (no infra) for provisioning idempotency + fail-fast; retention-sweep tests
  (activated not swept; never-activated past TTL swept; erasure includes the row); the SPI acceptance +
  negative (bad sig, replay) integration tests (Testcontainers); the `SecurityConfig` open/closed test.
- Synthetic data only; a green build names **both** suites.

## Test Manifest (task → named test)

| Task / AC | Named test |
|-----------|------------|
| AC3 idempotent provision | `provisionAccount_calledTwiceForSameDeviceKey_createsExactlyOneAccountAndRow` |
| AC3 fail-fast | `provisionAccount_withMalformedPublicKey_returns4xxAndCreatesNothing` |
| AC2 SPI accept | `directGrantWithDeviceSignedChallenge_issuesJwtAcceptedByResourceServer` |
| AC2 SPI reject | `directGrantWithWrongSignature_isRejected`, `directGrantWithReplayedNonce_isRejected` |
| AC4 security | `provisionEndpoint_isReachableUnauthenticated`, `otherApiV1Endpoint_withoutJwt_is401` |
| AC5 sweep | `retentionSweep_deletesNeverActivatedAccountPastTtl`, `retentionSweep_keepsActivatedAccount` |
| AC1/AC6 client | `firstLaunch_provisionsAndSignsInWithNoBrowserSurface`, `authPath_hasNoAppAuthDependency` |

## Project Structure Notes

- New backend files land under `de.sgart.identity` (flat `application`, `domain`, `adapter.in`,
  `adapter.out` — F1: no `application.command` subpackage) — the layer split ArchUnit's
  `HexagonalArchitectureTest` already enforces (`..domain..` / `..application..` matchers need no change).
- The SPI is a module **inside the backend build** at `backend/keycloak-authenticator/` (F2), included in
  `backend/settings.gradle.kts`, its own JAR, **not** depended on by the backend app module;
  `backend/./gradlew test` builds and tests it.
- Flyway migration: **`V18`** (V17 `V17__device_token.sql` is current latest).

## References

- `epic-7-story-7.1-provisioning-design.md` (design note) · `sprint-change-proposal-2026-09-13.md` (rev E)
- `ARCHITECTURE-SPINE.md` AD-1/2/5/6/7 + Epic-7 beta bullet · `epics.md` §972+
- `KeycloakAdminFindHouseholdMemberByEmail.java` · `IssueMemberIdentity.java` · `SecurityConfig.java`
- `app/lib/shared/http/authenticated_http_client.dart` · `keycloak/realm-sgart.json` · `docker-compose.yml`

## Questions for Timo (non-blocking — sensible defaults chosen)

- **SPI subproject name** — defaulting to `keycloak-authenticator/`. Override if you prefer another name.
- **Retention config key** — defaulting to `sgart.identity.provisioning.retention-days=14`.
- **Endpoint DTO `platform` field** — included for future device-attestation/telemetry; drop it if you'd
  rather keep the shell minimal per YAGNI (it is not used in 7.1 beyond storage-free pass-through).

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- Story 7.0 acceptance test (`DeviceSignedChallengeAcceptanceTest`) failed twice before passing, both
  found and fixed via a real Testcontainers Keycloak 26.7.0 run with the built provider JAR:
  1. `keycloak-authenticator` compiled to Java 25 class files (69.0) but Keycloak 26.7.0 ships a
     bundled JDK 21 (65.0) — fixed by dropping the module's own toolchain and compiling with
     `--release 21` instead (`backend/keycloak-authenticator/build.gradle.kts`).
  2. The SPI verified the signed challenge against `user.getUsername()`, but Keycloak
     case-normalizes stored usernames — mismatching the mixed-case base64url string the device
     actually signed. Fixed by reconstructing the signed message from the raw `username` form
     parameter instead.
  3. Keycloak 26's declarative User Profile silently drops unmanaged attributes and, separately,
     auto-triggers `VERIFY_PROFILE` for missing `required` fields even for Direct Grant —
     "Account is not fully set up". Fixed by declaring `publicKey` as an admin-only User Profile
     attribute and dropping `required` from `email`/`firstName`/`lastName` (`keycloak/realm-sgart.json`
     `components` block) — SGART never requires those fields, so nothing here regresses AD-6.

### Completion Notes List

- Backend: full hexagonal provisioning slice (`ProvisionAccount`, `CreateAccount`/`DeleteAccount`
  ports, `ProvisionedAccount`/`ProvisionedAccountRepository`, JDBC + in-memory adapters,
  `KeycloakAdminCreateAccount`, `AccountController` at the sole unauthenticated
  `POST /api/v1/accounts`, `SecurityConfig` permit-above-authenticated ordering), the retention
  sweep (`SweepNeverActivatedAccounts` + `ScheduledAccountRetentionSweep`, `@EnableScheduling`
  added to `SgartApplication`), and migration `V18__provisioned_account.sql`.
- New Gradle module `backend/keycloak-authenticator/` (F2): `DeviceSignedChallengeAuthenticator`
  (+Factory) verifying the Ed25519-signed `username|timestamp|nonce` challenge with a bounded
  `NonceSeenCache`, registered via `META-INF/services`. Deliberately compiled with `--release 21`
  (Keycloak 26.7.0's bundled JDK), not the backend app's Java 25 toolchain.
- `keycloak/realm-sgart.json`: added the `sgart-device-direct-grant` flow, bound it as the realm's
  `directGrantFlow`, flipped `sgart-app.directAccessGrantsEnabled` to `true`, added `manage-users`
  to `sgart-admin`'s service account, and declared `publicKey` in the User Profile (dropping the
  `required` flag from email/firstName/lastName — see Debug Log #3). `docker-compose.yml` mounts
  the built provider JAR into the Keycloak container (documented build-order requirement).
- Story 7.0 acceptance test (`DeviceSignedChallengeAcceptanceTest`, AC2) runs a real Testcontainers
  Keycloak 26.7.0 with the actual `keycloak/realm-sgart.json` imported and the built provider JAR
  mounted — proves the full sign-in end-to-end (JWT accepted by `SecurityConfig.jwtDecoder`), plus
  wrong-signature and replayed-nonce rejection. `backend/build.gradle.kts`'s `test` task depends on
  `:keycloak-authenticator:jar` to guarantee the JAR exists before the test runs.
- Flutter: added the device-credential module (`DeviceCredential`, `SecureEnclaveDeviceCredentialStore`,
  `RecoveryPhrase` — BIP39-encodable, not yet wired to any UI per scope guard), `AccountProvisioningApi`,
  and `DirectGrantOidcClient` (replaces `AppAuthOidcClient`); removed `flutter_appauth` and
  `app_auth_oidc_client.dart` entirely, and the dead `appAuthRedirectScheme` Android manifest
  placeholder. `AuthCubit.bootstrap()` now silently calls `signIn()` when no session is stored,
  instead of leaving the gate on an inert "unauthenticated" state — `SignInPage` is a brief loading
  screen with no button, only a retry action on failure (Story 7.1, AC1: zero input, no browser).
  `OidcClient.endSession` now takes `refreshToken` (Keycloak's public-client logout revocation),
  not `idToken` (Direct Grant issues no id_token here) — `OidcTokens.idToken` removed.
- Kept `OidcClient`'s public contract (`signIn()`/`refresh()`) intentionally unchanged so `AuthCubit`
  and its existing test suite needed no constructor changes — the device-credential/provisioning
  orchestration lives entirely inside the new `DirectGrantOidcClient` implementation.
- **Known/accepted minor DRY tradeoff:** `KeycloakAdminCreateAccount`'s client-credentials
  token-fetch duplicates `KeycloakAdminFindHouseholdMemberByEmail`'s (Story 4.6) — the design note
  explicitly says to clone that adapter's shape, and both stay small, self-contained, per-context
  adapters (AD-1/AD-2); not refactored into a shared helper to avoid touching the already-shipped
  4.6 adapter for this story.
- **Deferred / not done in this story (flagged, not silently skipped):**
  - Live verification of AC7's IP-based rate limiting is deferred to when the TLS reverse proxy
    (ADR-0002) exists — documented in `docs/first-real-world-test.md` per the story's own AC7 text.
  - `sgart-admin`'s checked-in dev placeholder secret is *not* replaced (out of scope; flagged
    urgently in `docs/first-real-world-test.md` and in the realm client's own description).
  - Epic 6 erasure wiring for `ProvisionedAccount` is documented (`ARCHITECTURE-SPINE.md` AD-7,
    this file's Erasure task) but not implemented — Epic 6 does not exist as code yet.
  - Dependency currency (CLAUDE.md §7): Keycloak 26.7.0 pinned intentionally (matches the running
    server; SPI deps must version-match the deploy target) even though 26.7.1–26.7.3 patch
    releases exist upstream — flagged for a follow-up bump, not rolled in here to avoid an
    unreviewed Keycloak version change alongside this story's other risk.

### File List

**Backend — new**
- `backend/keycloak-authenticator/build.gradle.kts`
- `backend/keycloak-authenticator/src/main/java/de/sgart/keycloak/authenticator/DeviceSignedChallengeAuthenticator.java`
- `backend/keycloak-authenticator/src/main/java/de/sgart/keycloak/authenticator/DeviceSignedChallengeVerifier.java`
- `backend/keycloak-authenticator/src/main/java/de/sgart/keycloak/authenticator/ChallengeVerificationResult.java`
- `backend/keycloak-authenticator/src/main/java/de/sgart/keycloak/authenticator/NonceSeenCache.java`
- `backend/keycloak-authenticator/src/main/resources/META-INF/services/org.keycloak.authentication.AuthenticatorFactory`
- `backend/keycloak-authenticator/src/test/java/de/sgart/keycloak/authenticator/DeviceSignedChallengeVerifierTest.java`
- `backend/src/main/java/de/sgart/identity/domain/ProvisionedAccount.java`
- `backend/src/main/java/de/sgart/identity/domain/ProvisionedAccountRepository.java`
- `backend/src/main/java/de/sgart/identity/application/CreateAccount.java`
- `backend/src/main/java/de/sgart/identity/application/DeleteAccount.java`
- `backend/src/main/java/de/sgart/identity/application/InvalidAccountProvisioningException.java`
- `backend/src/main/java/de/sgart/identity/application/ProvisionAccount.java`
- `backend/src/main/java/de/sgart/identity/application/SweepNeverActivatedAccounts.java`
- `backend/src/main/java/de/sgart/identity/adapter/out/JdbcProvisionedAccountRepository.java`
- `backend/src/main/java/de/sgart/identity/adapter/out/InMemoryProvisionedAccountRepository.java`
- `backend/src/main/java/de/sgart/identity/adapter/out/KeycloakAdminCreateAccount.java`
- `backend/src/main/java/de/sgart/identity/adapter/out/DeferredCreateAccount.java`
- `backend/src/main/java/de/sgart/identity/adapter/out/DeferredDeleteAccount.java`
- `backend/src/main/java/de/sgart/identity/adapter/in/AccountController.java`
- `backend/src/main/java/de/sgart/identity/adapter/in/AccountErrorAdvice.java`
- `backend/src/main/java/de/sgart/identity/adapter/in/ScheduledAccountRetentionSweep.java`
- `backend/src/main/resources/db/migration/V18__provisioned_account.sql`
- `backend/src/test/java/de/sgart/identity/application/ProvisionAccountTest.java`
- `backend/src/test/java/de/sgart/identity/application/SweepNeverActivatedAccountsTest.java`
- `backend/src/test/java/de/sgart/identity/adapter/out/KeycloakAdminCreateAccountTest.java`
- `backend/src/test/java/de/sgart/identity/adapter/out/JdbcProvisionedAccountRepositoryTest.java`
- `backend/src/test/java/de/sgart/identity/adapter/in/AccountControllerTest.java`
- `backend/src/test/java/de/sgart/identity/adapter/in/security/AccountProvisioningSecurityTest.java`
- `backend/src/test/java/de/sgart/identity/adapter/in/security/DeviceSignedChallengeAcceptanceTest.java`

**Backend — modified**
- `backend/settings.gradle.kts`, `backend/build.gradle.kts`
- `backend/src/main/java/de/sgart/SgartApplication.java`
- `backend/src/main/java/de/sgart/identity/adapter/in/security/SecurityConfig.java`
- `backend/src/main/java/de/sgart/identity/adapter/out/IdentityBeansConfig.java`
- `backend/src/main/resources/application.yaml`
- `backend/src/test/java/de/sgart/identity/adapter/out/IdentityBeansConfigTest.java`

**Realm / infra**
- `keycloak/realm-sgart.json`, `docker-compose.yml`, `docs/first-real-world-test.md`
- `_bmad-output/planning-artifacts/architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md`

**App — new**
- `app/lib/features/auth/data/device_credential.dart`
- `app/lib/features/auth/data/device_credential_store.dart`
- `app/lib/features/auth/data/secure_enclave_device_credential_store.dart`
- `app/lib/features/auth/data/recovery_phrase.dart`
- `app/lib/features/auth/data/account_provisioning_api.dart`
- `app/lib/features/auth/data/direct_grant_oidc_client.dart`
- `app/test/features/auth/data/device_credential_test.dart`
- `app/test/features/auth/data/recovery_phrase_test.dart`
- `app/test/features/auth/data/direct_grant_oidc_client_test.dart`
- `app/test/features/auth/data/auth_path_has_no_app_auth_dependency_test.dart`

**App — modified**
- `app/pubspec.yaml`
- `app/lib/features/auth/data/oidc_client.dart`, `oidc_tokens.dart`, `keycloak_config.dart`,
  `flutter_secure_token_storage.dart`
- `app/lib/features/auth/presentation/auth_cubit.dart`, `auth_gate.dart`, `sign_in_page.dart`
- `app/lib/shared/deeplinks/invite_deep_link_service.dart`
- `app/lib/l10n/app_de.arb` (+ generated `app/lib/l10n/gen/*`)
- `app/android/app/build.gradle.kts`, `app/android/app/src/main/AndroidManifest.xml`
- `app/ios/Runner/Info.plist`
- `app/test/features/auth/presentation/auth_cubit_test.dart`, `auth_gate_body_test.dart`,
  `sign_in_page_test.dart`
- `app/test/features/households/presentation/first_run_router_test.dart`
- `app/test/features/settings/presentation/locale_auth_bridge_test.dart`
- `app/test/support/fake_auth_dependencies.dart`

**App — deleted**
- `app/lib/features/auth/data/app_auth_oidc_client.dart`
- `app/test/features/auth/data/app_auth_oidc_client_test.dart`

### Review Findings

## Review Triage Log

Three parallel layers (blind-hunter, edge-case-hunter, verification-gap) reviewed the full diff since
`baseline_commit`. Two claims were settled empirically with a throwaway Testcontainers-style Keycloak
26.7.0 (real SPI jar + real `realm-sgart.json` for the security-bypass check) rather than by inspection
alone. Verdicts below; `patch` entries were applied, `defer` went to `deferred-work.md`, everything else
was rejected on the stated evidence and left unchanged.

**All 8 `patch` entries (#1–#8) were applied** by the implementation agent and independently re-verified:
backend `./gradlew clean test` green (ArchUnit + Testcontainers-Postgres + the Testcontainers-Keycloak
acceptance test), app `flutter analyze` clean, `flutter test` 674 passed. No `intent_gap`/`bad_spec`
entries arose (the spec has no `<frozen-after-approval>` block), so no loopback was needed.

| # | Finding (location) | Verdict | Evidence | Route |
|---|---|---|---|---|
| 1 | `DeviceSignedChallengeVerifier.verify` records the nonce as seen *before* verifying the signature | medium | An attacker who observes a device's `(username, timestamp, nonce)` in transit can replay it with a garbage signature first, burning that nonce and causing the legitimate device's real attempt (same nonce) to be rejected as a replay — a single-attempt DoS. Confirmed by reading the method body: `recordIfUnseen(...)` runs before `verifyEd25519Signature(...)`. | patch |
| 2 | `KeycloakAdminCreateAccount.findUserIdByUsername` throws a raw `IllegalStateException` when the post-409 exact-match lookup returns 0 or 2+ users, uncaught by `AccountErrorAdvice` → unmapped 500 | medium | Verified reachable via a genuine internal race with this story's own `SweepNeverActivatedAccounts` (a concurrent retention-sweep deletion of the same account mid-retry), not an external-framework contract violation. Empirically disproved the alternative theory raised by verification-gap: a fresh throwaway Keycloak 26.7.0 confirms the admin `username=…&exact=true` search **does** case-fold, so a mixed-case base64url username retry does *not* trigger this — case-mismatch is not the real trigger. | patch |
| 3 | Design note `epic-7-story-7.1-provisioning-design.md` §6 still names `ProvisionAccountHandler`, contradicting the F1 ruling recorded later in the same document | low | One stale line in a planning doc, not the frozen story contract; direct one-line fix. | patch |
| 4 | `SignInPage`'s retry button reuses `localizations.householdsRetryButtonLabel` (a household-feature string) for an auth-feature action | low | Confirmed in the diff. A future edit to household retry copy would silently change the auth retry copy too (CLAUDE.md §2 ubiquitous language). Direct fix: a dedicated auth-scoped l10n key. | patch |
| 5 | `SweepNeverActivatedAccounts.sweep()` has no per-item error isolation — one failing `deleteAccount.delete()` aborts the whole run | low | A single persistently-broken shell could block cleanup of unrelated, healthy overdue shells sorted after it for as long as it keeps failing — a real, if narrow, GDPR storage-limitation risk. Trivial fix: wrap each iteration in try/catch, log, continue. | patch |
| 6 | `ChallengeVerificationResult.reason()` is computed but never logged anywhere, contradicting its own Javadoc ("carries a reason only for server-side logging") | low | Confirmed: `DeviceSignedChallengeAuthenticator.fail()` never reads `result.reason()`. Reduces diagnosability of the project's most security-sensitive component for no benefit. Direct fix: `context.getEvent().detail(...)` before `context.failure(...)` (server-side audit log only, never leaks to the client — no user-enumeration regression). | patch |
| 7 | `KeycloakConfig.tokenEndpoint`/`logoutEndpoint` are defined but never referenced — `DirectGrantOidcClient` hardcodes the same paths itself | low | Confirmed by grep: zero references anywhere in `app/lib`. Directly contradicts this story's own DoD line ("no dead strings/fields... left behind"). Direct fix: wire `DirectGrantOidcClient` to the existing constants. | patch |
| 8 | `FlutterSecureTokenStorage.clear()` no longer deletes the legacy `sgart.auth.idToken` key removed from read/write, so pre-7.1 installs keep an orphaned stored id token forever | low | Confirmed in the diff — the key constant and its read/write/delete calls were removed together, but `clear()` never explicitly purges the old key for upgrading devices. Small beta userbase and device-local-only exposure keep the real-world impact low, but the fix is a one-line addition. | patch |
| 9 | `ProvisionAccount.provision()` has no compensation between `createAccount.create()` succeeding and `provisionedAccountRepository.recordIfAbsent()` failing — a resulting orphaned Keycloak account has no row and is invisible to the retention sweep | low | Real but self-healing in virtually every real scenario: `AuthCubit.bootstrap()` retries `signIn()` → `provision()` on every subsequent launch (Story 7.1's own zero-input design), and the retry's `createAccount.create()` idempotently reuses the same account while `recordIfAbsent()` succeeds once the transient failure clears. A truly permanent orphan needs the device to never again complete a successful retry. Fix would add a `DeleteAccount` dependency/compensation to `ProvisionAccount` — more than a direct correction, and unlikely to be hit in practice. | rejected (low; uncommon + fix is more than a direct correction) |
| 10 | No validation that a well-formed-length `publicKey` is an actual valid Ed25519 curve point | false | A structurally-invalid key is caught by `KeyFactory.generatePublic`/`GeneralSecurityException` in `DeviceSignedChallengeVerifier.verifyEd25519Signature` and treated as a normal verification failure — the submitting device just can never sign in, a self-limiting, non-security outcome, not a defect. | rejected (false) |
| 11 | `SweepNeverActivatedAccounts.sweep()` calls `householdIdsFor` once per row — an unbatched N+1 pattern | low/false | No concrete harm named beyond "as the shell table grows" at explicitly-acknowledged beta scale; a vague performance concern with no demonstrated trouble scenario. | rejected (low; no named harm at current scale, fix is more than a direct correction) |
| 12 | No test exercises the Direct-Grant flow for a user with no `publicKey` attribute — is this authenticator's absence-handling actually fail-closed? | false | Empirically verified against a real Keycloak 26.7.0 with the built SPI jar and the actual `realm-sgart.json` imported: created a user with no `publicKey` attribute, attempted Direct Grant sign-in — result: `401 invalid_grant`, not a bypass. `configuredFor()`/`isUserSetupAllowed()` do not gate execution for this flow; the authenticator's own null/blank checks fail closed correctly. | rejected (false; verified fail-closed) |
| 13 | `docker-compose.yml` mounts the whole `backend/keycloak-authenticator/build/libs` directory rather than a single jar | low | Currently produces exactly one jar (no sources/javadoc jar task configured) — no stray-jar risk today; the "directory doesn't exist before first build" case is already explicitly documented in `docs/first-real-world-test.md`. | rejected (low; unlikely today, fix is more than a direct correction) |
| 14 | `JdbcProvisionedAccountRepositoryTest` declares `PostgreSQLContainer` as a raw type | false | Confirmed identical to the already-shipped `JdbcMemberMappingRepositoryTest`, which this story was explicitly told to mirror — an established codebase convention, not a regression. | rejected (false; matches existing precedent) |
| 15 | `AuthCubit.bootstrap()` removes the only reachable "signed out" resting state — the still-visible "Abmelden" (sign out) button in `profile_screen.dart` no longer meaningfully protects a shared device, since the next launch silently re-authenticates the same identity with zero input | low | Real and verified (confirmed `signOut()` is still wired to a visible button), and a deliberate, already-documented consequence of moving to a device-bound, password-free credential (`secure_enclave_device_credential_store.dart`'s own doc comment states this explicitly). Judged low for this app's actual threat model (a household grocery list, not high-sensitivity data; the real security boundary is the OS lock screen). This is a genuine product/UX question (should "sign out" be relabeled, or gain a "forget this device" action?) rather than a code defect — **flagged to Timo for awareness**, not fixed here. | rejected (low; deliberate model consequence, product decision not a code defect) |
| 16 | `AccountErrorAdvice` doesn't map Spring's `HttpMessageNotReadableException` (malformed JSON body) to the canonical `{code,message}` envelope | false | Confirmed via grep: no `*ErrorAdvice` class anywhere in the codebase (`DeviceErrorAdvice`, `WriteErrorAdvice`) handles this either — a pre-existing, codebase-wide convention this story faithfully replicates, not a regression it introduced. | rejected (false; matches existing precedent) |
| 17 | `KeycloakAdminCreateAccount.userIdFromLocation` throws an uncaught `NullPointerException` (via `Objects.requireNonNull`) if Keycloak's 201 response omits the `Location` header | false | Would require Keycloak to violate its own stable, documented Admin REST contract (always returns `Location` on successful user creation) — trusting a well-behaved external framework's guarantee per CLAUDE.md §1 ("don't add error handling for scenarios that can't happen"), consistent with how the precedent adapter (`KeycloakAdminFindHouseholdMemberByEmail`) already trusts the same contract. | rejected (false; trusts a stable, documented external contract) |
| 18 | `KeycloakAdminCreateAccount.create()`/`delete()` have no catch for `RestClientException` if the Keycloak Admin API is unreachable | false | Confirmed via grep that the precedent adapter this story was explicitly told to clone (`KeycloakAdminFindHouseholdMemberByEmail`, Story 4.6) has the identical posture — no special handling for a downstream outage. A genuine infra failure surfacing as 5xx is standard, correct behavior, not a defect this story introduced. | rejected (false; matches existing precedent, standard behavior for a downstream outage) |
| 19 (verification-gap) | No test exercises a real Flutter `cryptography`-signed Ed25519 challenge verified by the JVM path — every test round-trips within one implementation's own crypto stack | defer (pre-verified by that layer) | Filed pre-verified by the verification-gap layer with its own disposition of `defer`: closing it needs either a hardcoded Dart-generated test vector or a real Flutter-driven E2E run against Testcontainers Keycloak — both real but non-trivial, and Ed25519's RFC 8032 wire format is standardized enough that practical risk is low. | defer → `deferred-work.md` |

## Change Log

| Date | Change | By |
|------|--------|-----|
| 2026-09-14 | Story drafted from the 7.1 design note (forks A–E resolved) | Winston (architect) |
| 2026-09-14 | validate-create-story: F1 (flat identity service, not command+handler), F2 (SPI as backend/keycloak-authenticator module), F3 (migration V18), F4 (@EnableScheduling) applied; status → ready for dev | Winston (architect) |
| 2026-09-14 | Implemented: backend provisioning slice + retention sweep, `keycloak-authenticator` SPI module + realm wiring, Flutter device-credential/Direct-Grant client (AppAuth removed); Story 7.0 acceptance test green against real Testcontainers Keycloak. Both suites green (`backend/./gradlew test`, `app/flutter test` + `flutter analyze`). Status → done | Dev Agent (Claude Sonnet 5) |
| 2026-09-14 | Orchestrator task/AC verification (bmad-build step 3): independently re-ran both suites from a clean diff — backend `./gradlew clean test` (1002 tests, 0 failures/errors, incl. ArchUnit + Testcontainers-Postgres + the Testcontainers-Keycloak acceptance test) and app `flutter analyze` (clean) + `flutter test` (674 passed). Found and fixed one DoD gap: `keycloak/realm-sgart.json`'s `sgart-app` client still carried dead AppAuth-path config (`standardFlowEnabled: true`, the `oauth/callback` `redirectUris`, and the `pkce.code.challenge.method`/`post.logout.redirect.uris` attributes) even though the Authorization Code + PKCE flow is fully superseded — removed all four (client now: `standardFlowEnabled: false`, empty `redirectUris`, no PKCE/logout-redirect attributes); re-ran the security + acceptance tests and the full suite green after the fix. Status → in-review (review, step 4, still to run). | Dev Agent (Claude Sonnet 5) |
| 2026-09-14 | bmad-build step 4 review: 3 parallel layers (blind-hunter, edge-case-hunter, verification-gap) found 19 candidate issues; verified each (2 settled empirically against a real throwaway Keycloak 26.7.0 with the built SPI jar — refuted a feared username-case-mismatch retry bug and a feared auth-bypass-on-missing-publicKey bug, both fail safe). 8 routed to `patch` and applied by the dev agent: nonce-recorded-before-signature-verified in `DeviceSignedChallengeVerifier` (self-DoS fix), `KeycloakAdminCreateAccount`'s ambiguous-lookup now throws a mapped `InvalidAccountProvisioningException` instead of an unmapped 500, a stale `ProvisionAccountHandler` reference in the design note, a new `authRetryButtonLabel` l10n key replacing a borrowed household string in `SignInPage`, per-item try/catch isolation in `SweepNeverActivatedAccounts`, server-side rejection-reason logging in `DeviceSignedChallengeAuthenticator`, wiring the previously-dead `KeycloakConfig.tokenEndpoint`/`logoutEndpoint` into `DirectGrantOidcClient`, and a legacy `sgart.auth.idToken` cleanup in `FlutterSecureTokenStorage.clear()`. 1 routed to `defer` (no cross-implementation Dart↔JVM Ed25519 signature test) → `deferred-work.md`. 10 rejected as `false`/`low` with recorded evidence (see Review Triage Log), including one flagged separately for Timo's awareness rather than fixed: `AuthCubit.bootstrap()`'s zero-input auto-signin means the still-visible "Abmelden" (sign out) button no longer leaves a persisted unauthenticated state — a deliberate, already-documented consequence of the device-bound credential model, judged low-risk for this app's threat model but a genuine product/UX question worth a conscious decision. Full re-verification after patches: backend `./gradlew clean test` green, app `flutter analyze` clean + `flutter test` 674 passed. No `intent_gap`/`bad_spec` (no frozen spec section exists), so no loopback. Status → in-review (step 5 presentation next). | Dev Agent (Claude Sonnet 5) |
