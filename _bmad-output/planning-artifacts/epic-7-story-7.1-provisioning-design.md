# Story 7.1 — Silent Account Provisioning: Architect Design Note

**Author:** Winston (System Architect) · **Date:** 2026-09-14 · **Status:** draft for Timo's review
**Feeds:** `create-story` 7.1 · **Scope agreed with Timo:** the 7.1 provisioning slice **plus** the
shared device-seed → signing-key → recovery-phrase credential model (7.2/7.3 depend on it).

**Ground truth:** `sprint-change-proposal-2026-09-13.md` (rev E), `epics.md` §972+ (Epic 7),
`ARCHITECTURE-SPINE.md` (AD-1/2/5/6/7, the rev-E Epic-7 beta bullet), and the existing `identity`
context (`KeycloakAdminFindHouseholdMemberByEmail`, `IssueMemberIdentity`, `MemberMapping`,
`SecurityConfig`).

**Mechanism is LOCKED** (Story 7.0 spike): custom Keycloak Direct-Grant authenticator SPI verifying a
device-signed challenge. This note does *not* re-open it — it pins the pieces the spike left at the
"candidate (b)" level down to buildable detail, and flags the genuine forks for Timo.

---

## 1. The crux: account-level identity state is new

Today the Identity ACL persists **only** `MemberMapping = (householdId, memberId, keycloakUserId)` —
household-scoped, written when a person creates or accepts into a household (`IssueMemberIdentity`).
There is **no account-level row** because, until now, an account only ever existed *because* it was
already in a household (accounts were created manually in the Keycloak admin console).

Story 7.1 breaks that: a Keycloak account is created **before any household exists**. Two consequences
ripple through the whole design:

1. **The retention sweep needs to find "never-activated" accounts** — accounts that have a Keycloak
   user but no `MemberMapping` (and, from 7.3 on, no attached email). Something must enumerate them.
2. **"Activation" becomes a defined lifecycle transition:** a silent shell → activated the moment the
   person creates or joins their first household (a `MemberMapping` appears) *or* attaches a recovery
   email (7.3). This transition is not modelled anywhere yet.

Everything below follows from getting this right without violating **AD-6** (SGART persists no PII —
`keycloakUserId` is a pseudonym, already held in `MemberMapping`, so it stays permissible).

---

## 2. Credential model (shared spine for 7.1/7.2/7.3)

Pin this once; 7.2 (recovery phrase reveal/re-view) and 7.3 (recover-by-email re-bind) build on it.

```
256-bit entropy  ─(generated once, first launch)─►  stored in secure enclave
   │                                                 (flutter_secure_storage: Android Keystore /
   │                                                  iOS Keychain — device-bound, actually secret)
   ├─ BIP39 encode ───────────────────►  24-word recovery phrase  (the ONLY thing that survives
   │                                       reinstall / moves to a new device; client-only, never
   │                                       sent to any server)
   └─ derive Ed25519 keypair (seed = the 32 entropy bytes, deterministic)
          ├─ PRIVATE key: stays on device, signs login challenges, never leaves the enclave
          └─ PUBLIC key:  registered with Keycloak at provisioning; the authenticator verifies
                          challenge signatures against it
```

**Decisions pinned (PROPOSED — confirm in §7):**

- **Signature scheme: Ed25519.** Boring, modern, tiny keys/signatures; native in the JVM (`EdDSA`,
  JDK 15+) so the Keycloak SPI verifies with no third-party crypto lib; well-supported in Flutter
  (`cryptography` / `ed25519_edwards`). Deterministic: the Ed25519 private key *is* the 32 seed bytes,
  so the phrase alone reconstructs the identical keypair on any device — no server round-trip.
- **Recovery phrase = BIP39 24-word (256-bit) mnemonic** of the entropy. Human-readable, checksummed,
  a universally understood recovery UX. (Flutter `bip39`; pin the current supported major per
  CLAUDE.md §7.)
- **Keycloak username = deterministic encoding of the public key** (e.g. base64url of the 32-byte
  Ed25519 public key, or `SHA-256(pubkey)` base64url — a fixed, opaque, never-shown string). This is
  the keystone choice: on a *fresh* device, phrase → seed → keypair → **username**, all computed
  locally, so **recovery needs no "find my account" lookup** and no server-issued identifier. It also
  makes provisioning naturally **idempotent** (a retry re-derives the same username → Keycloak reports
  "already exists" → treat as success).
- **Nothing secret is ever server-side.** Keycloak stores only the *public* key. The phrase/seed/
  private key are device-only. "Re-view from local secure storage, never re-fetched" (7.2) falls out
  for free.

> **Consequence to state wherever the phrase is shown (7.2):** losing the phrase before it is written
> down — and before a recovery email is attached (7.3) — makes the account and its household data
> unrecoverable. This is inherent to a zero-knowledge, no-PII credential, not a gap.

---

## 3. The provisioning slice (hexagonal, `identity` context)

CQRS: provisioning is a **command with a side effect but no domain event** (identity is delegated to
Keycloak; AD-5/6/7 untouched). This is consistent with the existing ACL, which is *already*
non-event-sourced state (`MemberMapping` is a JDBC table via `JdbcMemberMappingRepository`, not an
event stream) — so a JDBC-backed account row does not violate AD-1's event-sourcing rule; the ACL is
the established exception, and ArchUnit already tolerates it for `MemberMapping`.

### 3.1 `adapter.in` — the unauthenticated endpoint

- **The only unauthenticated *write* endpoint in the app.** Path (PROPOSED):
  `POST /api/v1/accounts` (or a deliberately-separate `POST /api/provisioning/accounts` to keep the
  permit rule visually obvious — see §7). Request body: `{ publicKey, platform }`. Response:
  **`204 No Content`** — CQRS returns no domain data; the app already has everything it needs
  (username is derived locally, `sub` arrives in the JWT after login).
- **`SecurityConfig` change (fail-fast, ordered):** Spring matches matchers in declaration order, so an
  explicit permit for exactly this path+method must sit **above** `/api/v1/**`.authenticated():
  ```java
  .requestMatchers(HttpMethod.POST, "/api/v1/accounts").permitAll()   // <-- new, FIRST
  .requestMatchers("/api/v1/**").authenticated()
  .anyRequest().permitAll()
  ```
  A `package-info`/comment must flag this as the single deliberate unauthenticated write surface, with
  the abuse-surface NFR (below) as its mitigation.
- New `AccountController` (or extend `IdentityController`) + error advice mapping bad input → 4xx
  (fail-fast; never 500), matching the existing `DeviceErrorAdvice` pattern.

### 3.2 `application` — `ProvisionAccount` service (flat; F1)

> **F1 (validation, 2026-09-14):** follow the identity-context convention — a **flat verb-phrase
> application service** (like `IssueMemberIdentity`, `RegisterDeviceToken`), **not** a
> `application.command` command+handler. The identity ACL has no command DTOs and no `*Handler`; forcing
> CQRS ceremony here contradicts CLAUDE.md §4 and the context's own style. (This overrides the
> proposal §4 "command + handler" wording.)

- `ProvisionAccount` service with a `provision(publicKey, platform)` method, **idempotent** on the
  derived username (a retry never creates a second account, echoing `IssueMemberIdentity`'s
  find-or-create ethos), orchestrating two out-ports:
  1. `KeycloakAdminCreateAccount.create(publicKey)` → creates the Keycloak user (or no-ops if it
     exists) and registers the public key.
  2. `ProvisionedAccountRepository.record(keycloakUserId)` → writes the account-lifecycle row (§4).
- Emits **no** event. Returns nothing beyond success.

### 3.3 `adapter.out` — `KeycloakAdminCreateAccount`

- Follows `KeycloakAdminFindHouseholdMemberByEmail` (Story 4.6) **exactly**: client-credentials token
  fetch, `RestClient` against the Admin REST API, all Keycloak/HTTP types contained in the adapter
  (AD-1/AD-2). Same config-gate discipline (`IdentityBeansConfig`) with a Deferred no-op default so CI
  and local builds need no admin credentials.
- Admin calls: `POST /admin/realms/{realm}/users` (username = derived; `enabled: true`; no email/name),
  then register the public key so the authenticator can read it — **where the pubkey lives is a fork,
  see §7** (user attribute vs. a custom credential).
- **Realm/permission changes:** `sgart-admin` gains `manage-users` (currently `view-users` only); its
  checked-in dev placeholder secret (`realm-sgart.json:90`) **must** be replaced with real secret
  management before this ships to testers — creating real accounts is what makes that urgent.

### 3.4 The Keycloak custom authenticator SPI (separate build artifact)

- A **Gradle module inside the backend build** at `backend/keycloak-authenticator/` (F2 —
  `include(":keycloak-authenticator")` in `backend/settings.gradle.kts`; the build is rooted at
  `backend/`, so a "sibling of backend" would need a new root build) producing a provider JAR —
  **not** a dependency of the backend app module (different classpath, lifecycle, and deploy target),
  but covered by `backend/./gradlew test`. Depends, `compileOnly`, on `keycloak-server-spi` /
  `keycloak-server-spi-private` / `keycloak-services` **version-matched to Keycloak 26.7** (CLAUDE.md §7).
- Deployed into `/opt/keycloak/providers` (docker-compose volume mount) + a build step (`kc.sh build`).
  The realm binds a **custom Direct Grant flow** using this authenticator; `sgart-app` gets
  `directAccessGrantsEnabled: true` with that flow bound.
- **What it does:** on the token request it reads the username + a signed-challenge form field, loads
  the user, reads the registered public key, verifies the Ed25519 signature, and (on success) lets the
  flow issue tokens — no password, no browser. **Replay protection is a fork, see §7.**

### 3.5 App-side contract (informs create-story; app detail lands in the story)

Remove `flutter_appauth` + `app_auth_oidc_client.dart`; replace with a plain token-endpoint client.
First launch: generate entropy → enclave; derive keypair + username; `POST /api/v1/accounts`; then
Direct-Grant login (`grant_type=password`, `client_id=sgart-app`, `username=<derived>`,
`signed_challenge=<sig>`) → JWT. The **401 refresh-and-retry seam (commit `73a3b93`) is retained** —
only the initial sign-in call changes; refresh stays app→Keycloak.

---

## 4. Account lifecycle state & the retention sweep

**Recommended (PROPOSED, §7 fork A): a minimal `ProvisionedAccount` row in the `identity` ACL.**

```
ProvisionedAccount { keycloakUserId (pseudonym), provisionedAt }
```

- Holds **no PII** — only the pseudonymous `keycloakUserId` (already held in `MemberMapping`) + a
  timestamp. AD-6 respected.
- **Activation is derived, not stored** (DRY/YAGNI): an account is *activated* iff a `MemberMapping`
  exists for its `keycloakUserId` (7.1) — later also iff an email is attached (7.3). No `activatedAt`
  column to keep in sync.
- **Retention sweep** (scheduled server-side job): delete every `ProvisionedAccount` older than the TTL
  whose `keycloakUserId` has **no** `MemberMapping` (and, from 7.3, no attached email) → delete the
  Keycloak account (`DELETE /admin/realms/{realm}/users/{id}`) **and** the row. This is the GDPR
  storage-limitation control (AD-7 storage limitation; a clean, indexable query rather than a
  Keycloak-wide enumeration).
- **Erasure integration (AD-7):** add the `ProvisionedAccount` row to the erasure checklist alongside
  the existing "destroy mapping rows + scrub read models + purge caches + delete Keycloak account".

The alternative (§7 fork A, option 2) keeps **zero** new SGART state and makes the sweep enumerate
Keycloak users cross-referenced against `MemberMapping`. Leaner persistence, gnarlier sweep, and it
leans on Keycloak's `createdTimestamp` as the retention clock. Trade-off, not a verdict — my lean is
the row, because it turns the sweep into a trivial indexed query and gives 7.3/erasure a clear anchor.

---

## 5. Abuse surface & NFRs

- **Rate limiting: IP-based, at the TLS reverse proxy** (ADR-0002 stack), *not* app code — beta scope,
  KISS. Honest limits: carrier-grade NAT → shared-IP false lockouts; attackers rotate IPs → it is a
  speed bump, not a wall. Device attestation (Play Integrity / DeviceCheck) is a named fast-follow
  before public release, explicitly **not** in beta.
- **Retention TTL: 14 days** (§7 decision C) — the GDPR storage-limitation value for never-activated
  shells (no household, no email); measured from `provisionedAt`.
- **Lawful basis** for the silent shell (a pseudonymous id + device-bound credential, no PII):
  legitimate interest / performance of the service the person just requested by opening the app
  (Story 7.4 note). No *household* PII is processed before the 7.4 consent moment.

---

## 6. Test coverage (CLAUDE.md §6 — this is the project's most security-sensitive change)

- **Domain/application:** `ProvisionAccount` — creates account + records row; **idempotent**
  retry does not double-create; bad input fails fast (4xx, never 500).
- **Retention:** a never-activated account past TTL is swept; an activated one (has `MemberMapping`)
  is **not**; erasure includes the `ProvisionedAccount` row.
- **Auth integration (the 7.0 acceptance test, proven at build):** Testcontainers Keycloak **with the
  provider JAR mounted** + the custom flow bound; an Admin-API-created account signs in via a
  device-signed challenge and receives a JWT accepted at `adapter.in` — no browser, no password.
  Negative: a wrong/absent signature is rejected; a replayed challenge is rejected.
- **Security config:** `POST /api/v1/accounts` is reachable unauthenticated; every other `/api/v1/**`
  still requires a JWT (guard against the permit matcher being too broad).
- **A green build names both suites:** backend `./gradlew test` (incl. ArchUnit + Testcontainers)
  **and** app `flutter test` / `flutter analyze`.

---

## 7. Decisions (RESOLVED 2026-09-14, Timo)

| # | Decision | Resolution |
|---|----------|------------|
| **A** | Account-lifecycle state | **Minimal `ProvisionedAccount` row** (`keycloakUserId` + `provisionedAt`, no PII); activation *derived* from `MemberMapping` existence (+ attached email from 7.3). Sweep = trivial indexed query. (§4) |
| **B** | Replay protection | **Signed timestamp + client nonce in one POST.** The app signs `(username\|timestamp\|nonce)`; the SPI verifies signature + a tight time window (≈60s) + a small server-side seen-cache of nonces. One round-trip. (§3.4) |
| **C** | Retention TTL | **14 days.** Never-activated shells (no household, no email) are swept 14 days after `provisionedAt` — tighter storage-limitation posture (GDPR §5). (§4/§5) |
| **D** | Public key location in Keycloak | **User attribute** — simplest for the SPI to read; revisit only if Keycloak-native credential semantics are later needed. (§3.3) |
| **E** | Endpoint path | **`POST /api/v1/accounts`**, permit matcher declared **above** `/api/v1/**` in `SecurityConfig`; the `package-info`/comment flags it as the single deliberate unauthenticated write surface. (§3.1) |

All forks are closed. The credential model (§2) and the hexagonal slice (§3) were stable regardless.

**Validation rulings (2026-09-14, from validate-create-story 7.1):**

| # | Finding | Resolution |
|---|---------|------------|
| **F1** | Identity context uses flat verb-phrase services, not CQRS command+handler (proposal §4 disagreed) | **Flat `ProvisionAccount` service** in `identity.application`; no `application.command`, no `*Handler` (§3.2). Overrides proposal §4 wording. |
| **F2** | No root Gradle build; rooted at `backend/` | SPI = **`backend/keycloak-authenticator/`** module in the backend build (§3.4). |
| **F3** | Migration number | **`V18`** (V17 is latest). |
| **F4** | Retention `@Scheduled` | Ensure `@EnableScheduling` present (§3/§ story tasks). |

Story `7-1-silent-account-provisioning-on-first-launch.md` is **ready for dev**.
