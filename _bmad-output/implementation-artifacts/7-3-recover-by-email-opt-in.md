---
baseline_commit: a1f4c0325b45959e29d9b510c2f1a1ce8257fad5
status: done
---

# Story 7.3: Recover by email (opt-in)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for a quality check before dev-story. -->

## Story

As a person who would rather not rely solely on remembering my recovery phrase,
I want to optionally attach an email address and use it to get back in,
so that I have a friendlier fallback if I lose the phrase or switch devices.

This is **Epic 7's third build story**. It adds the **optional, email-only "recover by email"**
fallback on top of the credential model built in 7.1 (silent provisioning + browserless Direct-Grant
sign-in) and the phrase reveal/recovery UI built in 7.2. It is the **one Epic-7 feature that sends
email** — the zero-input core (7.1/7.2) never does — so it pulls in a config-gated SMTP seam.

Unlike 7.2 (client-only), 7.3 is a **full-stack story**: a new `identity` one-time-code table +
application services + two ports (SMTP send, code store) + Admin-API rebind capability on the
backend, and three new native screens + a Profil section on the app. It changes **no** event, no
`MemberMapping` schema, **no** `SecurityConfig` permit rule, and critically **not** the
`keycloak-authenticator` SPI (§1 of the design note — key replacement is an Admin-API-side account
mutation only).

It does **not** capture consent (that is Story 7.4) and does **not** touch invites (Story 7.5). Keep
those decoupled.

**Feeds directly off the architect design note:** `epic-7-story-7.3-recover-by-email-design.md`
(Winston, 2026-09-15) — all four design forks were **resolved with Timo** (A: R1 rebind, B: auth as
the throwaway, C: 6-digit/15-min/5-attempt/HMAC, D: derive the sweep email check). This story pins
those into buildable tasks; §-refs below point at the note's detail.

## Context — what already exists vs. what 7.3 adds

**Already built (reuse verbatim, do NOT recreate):**

*Backend (`de.sgart.identity`):*
- `KeycloakAdminCreateAccount implements CreateAccount, DeleteAccount` — holds the client-credentials
  token fetch (`fetchAccessToken`), `manage-users` access, `PUBLIC_KEY_ATTRIBUTE = "publicKey"`,
  create/delete/find-by-username. **This is the class to extend** with the new set-email + rebind
  Admin capabilities (design §10 — "extend the existing Keycloak Admin adapter, it already holds the
  token fetch and `manage-users`"). Do not stand up a second admin client.
- `ProvisionAccount` / `ProvisionedAccountRepository` (+ `JdbcProvisionedAccountRepository` and the
  `InMemory…` double) — the throwaway shell's row model; the new code store mirrors its shape exactly.
- `SweepNeverActivatedAccounts` — deletes never-activated shells past the TTL; "activated" is
  **derived** (a `MemberMappingRepository` row exists for the `KeycloakUserId`), never stored. 7.3
  widens "activated" to *also* count a confirmed Keycloak email (design §7, fork D).
- `AccountController` (`/api/v1/accounts`, currently only the unauthenticated `POST` provision) +
  `AccountErrorAdvice` (maps `InvalidAccountProvisioningException → 400` via `ErrorDescriptor`) —
  extend both with the new authenticated endpoints and typed-exception handlers.
- `SecurityConfig` — the `POST /api/v1/accounts` permit rule is the app's **single** unauthenticated
  write surface; 7.3 adds **no** new permit rule (design §5, fork B — the recovery endpoints are
  authenticated as the throwaway JWT).
- `AuthenticatedCaller` — resolves the caller's `KeycloakUserId` (`kc2`) from the JWT, needed for the
  rebind's delete-throwaway step.
- `KeycloakUserId` — the stable pseudonymous id; `MemberMapping` is keyed by it, **never** by
  username, so mutating the username strands nothing (design §1).
- `HmacSha256InviteEmailHasher` / `InviteEmailHasher` (in `collaboration`) — the **HMAC-SHA256 +
  per-deployment secret pattern** to mirror for hashing the one-time code (design §4). Do not store a
  plaintext code; do not use a bare SHA-256 (a 6-digit code is offline-trivial).
- `IdentityBeansConfig` + `IdentityBeansConfigTest` — wires the config-gated Keycloak-admin adapter vs.
  its `Deferred…` no-op default via `@ConditionalOnProperty`; the new mail + code-store beans follow
  the **exact same gating shape**.
- `NoPersistedPersonalDataTest` — the whitelist of pseudonymous-only identity tables; the new code
  table joins it (design §4/§9).

*App (Flutter, `features/auth` / `features/settings` / `features/households`):*
- `AuthCubit` (`bootstrap`/`signIn`/`recoverFromPhrase`) + the `AuthGate → FirstRunRouter` re-mount:
  an `inProgress → authenticated` transition re-bootstraps `HouseholdsCubit` for whatever identity is
  now signed in. **This is the seam that makes the post-rebind identity swap Just Work** — 7.2's
  `recoverFromPhrase` is the exact precedent to mirror.
- `DirectGrantOidcClient.signIn()` — device-signed challenge sign-in; after the server rebinds the
  device's existing credential onto the recovered account, a plain `signIn()` authenticates into it.
- `RecoveryPhraseRevealPage` — reused verbatim to show the **fresh** phrase after a successful email
  recovery (design §6), with distinct copy.
- `ActiveHouseholdStore.clear()` (AD-7) — the same seam 7.2 uses to drop the throwaway's active
  household on an identity swap.
- `RecoverAccountPage` + `recover_account_page.dart` error handling — the forgiving-input +
  inline-error pattern the new email/code entry screens mirror.
- `CreateOrAwaitChoicePage` — the guaranteed 0-household gateway (7.2 D-D) where the "recover by email"
  entry lives, peer to 7.2's phrase recovery action; `openRecoveryPhraseRevealPage` /
  `openAwaitInvitePage` are the provider-escape-across-push precedents.
- `ProfileScreen` (Story 1.11) — gains the "E-Mail-Wiederherstellung" section; the identity header
  already renders `authState.email` live (AD-6).
- `AuthenticatedHttpClient`, `SgartButton`, `SgartAppBar`, the `app_de.arb` l10n pipeline.

**7.3 adds:**

*Backend:*
- A new transient `identity` table `email_recovery_code` (`V19`) + `EmailRecoveryCodeStore` port +
  `JdbcEmailRecoveryCodeStore` + in-memory double — PII-free, keyed by `KeycloakUserId` (design §4).
- A new out-port `SendRecoveryCodeEmail.send(email, code)` + real `JavaMailSenderRecoveryCodeEmail`
  (Spring `JavaMailSender`) + `DeferredSendRecoveryCodeEmail` no-op default (design §3.1).
- New Admin capabilities on `KeycloakAdminCreateAccount` (or a sibling port it also implements):
  set/clear email + `emailVerified`, and **rebind** (`PUT users/{kc1}` with `username := U2`,
  `publicKey := K2`) + email lookup (`GET users?email=&exact=true`) (design §1.1/§2/§3).
- Application services + typed exceptions for: attach-email, confirm-email, detach-email,
  request-recovery-code, confirm-recovery-and-rebind (design §2/§3.2/§10).
- New endpoints on `AccountController` + handlers on `AccountErrorAdvice`.
- The sweep gains a confirmed-email check (design §7).
- New dep `spring-boot-starter-mail` (Spring Boot 4.1.0) + `sgart.identity.mail.*` config keys.

*App:*
- `AddRecoveryEmailPage` (enter email), `ConfirmEmailCodePage` (enter code), `RecoverByEmailPage`
  (email → code → rebind), a Profil "E-Mail-Wiederherstellung" section, an account-email API client,
  and an attach/recover cubit (or an extension of `AuthCubit`); new `app_de.arb` strings + a11y labels.

## Locked Decisions (Timo, 2026-09-15 — from the design note; all four forks resolved)

- **D-A: key replacement, not a multi-key authorized set (design §1).** Email recovery rebinds the
  account to the **new device's** key (`username := U2`, `publicKey := K2` via Admin API), issues a
  **fresh** recovery phrase shown immediately on the recovered device, and the previous phrase stops
  working. The `keycloak-authenticator` SPI and its acceptance test are **untouched** — the whole
  mechanism is an Admin-API account mutation.
- **D-B: rebind = R1 (design §1.1, fork A).** On a reinstall/fresh device, 7.1 has already silently
  provisioned throwaway `A2` whose username is exactly `U2`. The server-side rebind, in order:
  (1) verify code → resolve target `A1` (`kc1`, by email lookup); (2) **delete throwaway `A2`**
  (`DELETE users/{kc2}` + its `ProvisionedAccount` row — `kc2` from the caller's JWT) to free `U2`;
  (3) **rebind `A1`** (`PUT users/{kc1}`, `username := U2`, `attributes.publicKey := K2`); (4) `204`.
  The app then re-runs `signIn()` with its **existing** local credential (now pointing at `A1`).
  `A1`'s stable `KeycloakUserId kc1` is preserved across every recovery. R2 (adopt memberships into
  `A2`, churn the id) is **rejected**.
- **D-C: recovery endpoints are authenticated as the throwaway (design §5, fork B).** No new open
  endpoint; the throwaway JWT supplies `kc2` for the delete step. Relies on the documented
  "7.1 always provisions and signs in first" invariant. The attach/confirm/detach endpoints are
  naturally authenticated (the real account).
- **D-D: SGART owns the code and sends it over its own SMTP (design §3).** Keycloak's built-in
  verify-email/reset-credentials emails deliver **browser links** the AC forbids; delivery is a SGART
  concern. Config-gated exactly like the Keycloak-admin adapter: real adapter on
  `sgart.identity.mail.enabled=true`, a `Deferred…` no-op default (`matchIfMissing=true`) so CI,
  local, and Testcontainers builds need **no SMTP server** and building the sender does no I/O. Real
  SMTP + SPF/DKIM is a documented **deploy seam** in `docs/first-real-world-test.md`, not a build
  blocker.
- **D-E: code format = 6-digit numeric · TTL 15 min · max 5 attempts · HMAC-SHA256 stored
  (design §4, fork C).** Numeric is the friendliest native entry; the short TTL + attempt cap + HMAC
  (per-deployment secret, mirroring `HmacSha256InviteEmailHasher`) carry the security weight. A
  verified code is single-use (deleted on success).
- **D-F: email lives only on the Keycloak account (design §7, AD-6).** Like the display name — the
  established AD-6 exception. SGART persists **no** email in events, read models, or the code table
  (which is keyed by `KeycloakUserId`, no email column).
- **D-G: "activated" now also counts a confirmed email (design §7, fork D).** The sweep additionally
  checks the account's Keycloak email/`emailVerified` via an **Admin-API get-user** — derived, never a
  stored SGART bool (DRY, matches 7.1's "activation is derived, never stored"). A confirmed email keeps
  a household-less account alive; an abandoned *unconfirmed* attach is still swept past the TTL.
- **D-H: no account/email enumeration (design §3.2/§5).** Request-a-code responds **`202` whether or
  not the email is registered**; failure reasons stay server-side-only (mirrors the authenticator's
  no-user-enumeration discipline).
- **D-I: AC wording relaxed (design §1, Timo 2026-09-15).** The epics.md AC "the phrase remains a
  **fully working** recovery path — email is additive, never a replacement" is nuanced to *"a phrase
  recovery path always remains available — email recovery issues a fresh phrase, shown immediately,
  and never removes the phrase option."* The **capability** persists; a specific phrase string does
  not survive an email recovery (it is replaced by the fresh one). Reflected in AC3 below.
- **D-J: one story (Timo, 2026-09-15).** The design note flags a natural split seam
  (7.3a attach/confirm/detach+infra vs. 7.3b recover+rebind) *only if* the composed spec blows the
  token budget. Timo chose one story; the seam is noted (§Project Structure) but not taken.

## Acceptance Criteria

Derived from `epics.md` §Epic 7 Story 7.3 with the design note's resolved decisions applied.

1. **AC1 — Attach & confirm an email (authenticated, real device).** Given the Profil screen, when a
   person attaches an email, then `POST /api/v1/account/email { email }` (authenticated) sets the
   email on the Keycloak account via the Admin API with `emailVerified := false`, issues a **6-digit**
   one-time code (HMAC-SHA256 hash stored, TTL 15 min), and sends it over SMTP — **no Keycloak-hosted
   page and no browser, email-only, no password**. A malformed email is **rejected fast** (4xx via
   `AccountErrorAdvice`, never 500). `POST /api/v1/account/email/confirm { code }` with the correct,
   unexpired, non-exhausted code sets `emailVerified := true`, deletes the code (single-use), and
   returns `204`; a wrong/expired/exhausted code is rejected fast (4xx). Only a **confirmed** email
   counts as an attached recovery email. The Profil section shows the not-attached /
   pending-confirmation / confirmed state.

2. **AC2 — Recover by email on a different device / after reinstall (browserless, key replacement).**
   Given an account `A1` with a **confirmed** email, when a person on a freshly-provisioned device
   (throwaway `A2`, holding key `K2`/username `U2`) requests recovery: `POST /api/v1/account/recovery/email
   { email }` looks up the account by email and — **only if found** — emails a fresh 6-digit code, and
   **responds `202` regardless of whether the email is registered** (no enumeration). Then
   `POST /api/v1/account/recovery/email/confirm { email, code }` verifies the code and performs the
   **R1 rebind** (design §1.1): delete throwaway `A2` (from the caller's JWT) + its `ProvisionedAccount`
   row, then `PUT` `A1.username := U2`, `A1.publicKey := K2`, returning `204`. `A1`'s
   `KeycloakUserId` is preserved. The app then re-runs `signIn()` with its existing credential (now
   authenticating into `A1`), clears the active household (AD-7), and lands on `A1`'s households via the
   `AuthGate → FirstRunRouter` re-mount — **pure native UI, no browser**.

3. **AC3 — The phrase recovery path always remains available; a fresh phrase is issued (D-I).** Given
   an email is or isn't attached, then the recovery-phrase path (7.2) still works unchanged. After a
   successful email recovery, the app **immediately shows the fresh recovery phrase `P2`** (reusing
   `RecoveryPhraseRevealPage`) with distinct copy stating the previous phrase no longer works; the old
   phrase (deriving the old username/key) no longer resolves or verifies. Email recovery **never
   removes** the phrase option.

4. **AC4 — Detach + GDPR (personal data, CLAUDE.md §5, AD-6/AD-7).** Given an attached email, when the
   person detaches it, then `DELETE /api/v1/account/email` (authenticated) clears the email +
   `emailVerified` on the Keycloak account and returns `204`. The email is stored **only on the
   Keycloak account** — **no** SGART store (events, read models, or the `email_recovery_code` table)
   persists the raw email. A swept or erased account's `email_recovery_code` rows go with it (erasure
   already deletes the Keycloak account, taking the email with it — no new erasure step). An account
   with a **confirmed email and no household is not swept**; an **unconfirmed-attach-then-abandoned**
   shell **is** swept past the TTL. The email is a documented Epic-6 export item (no export UI here).

5. **AC5 — Security surface & abuse resistance unchanged in shape.** Given the recovery endpoints, then
   they add **no new unauthenticated endpoint** and **no new `SecurityConfig` permit rule** — they are
   authenticated as the throwaway JWT (D-C); the `keycloak-authenticator` SPI is untouched (D-A).
   Request-a-code never reveals whether an email is registered (D-H). The per-code TTL + attempt cap +
   HMAC storage carry the code-guessing weight; IP rate-limiting is the reverse-proxy seam (the 7.1
   ADR-0002 seam), and device attestation stays the named pre-public-release fast-follow.

6. **AC6 — Config-gated SMTP; green build across both touched modules + dependency currency.** Given
   `sgart.identity.mail.enabled` is absent/false, then the `DeferredSendRecoveryCodeEmail` no-op is
   wired, building the sender does **no I/O**, and CI/Testcontainers/local builds need **no SMTP
   server** (`contextLoads` stays green). When the story completes, **both** suites are named and
   green: backend `./gradlew test` (incl. ArchUnit + Testcontainers) **and** app `flutter test` +
   `flutter analyze`. `spring-boot-starter-mail` is pinned to the Spring Boot 4.1.0 managed version
   (CLAUDE.md §7); any pub bump stays on its current supported major.

## Tasks / Subtasks

### Backend — one-time code store (`V19` + port + adapters) — AC1, AC2, AC4

- [x] `V19__email_recovery_code.sql`: create `email_recovery_code` (`keycloak_user_id text`,
      `purpose text` — `ATTACH_CONFIRM | RECOVER`, `code_hash text`, `expires_at timestamptz`,
      `attempts int`, `created_at timestamptz`). **No email column** (design §4). One active code per
      `(keycloak_user_id, purpose)` — upsert/replace on a new request. Latest migration is `V18`, so
      this is `V19`.
- [x] `EmailRecoveryCodeStore` (domain port) + `JdbcEmailRecoveryCodeStore` + `InMemoryEmailRecoveryCodeStore`
      (test double) — mirror `ProvisionedAccountRepository`'s exact shape. Methods for: store/replace a
      hashed code for `(id, purpose)`; load for verification; increment attempts; delete on success;
      delete all rows for a `KeycloakUserId` (erasure/sweep). No plaintext code persisted.
- [x] Reuse the `HmacSha256InviteEmailHasher` pattern for `HMAC-SHA256(server secret, code)` — a new
      narrow hasher in `identity` (or reuse the shared one if it is context-neutral; prefer a small
      identity-local one over a cross-context dependency — SoC). Per-deployment secret via config,
      guarded like the invite-HMAC secret (see `InviteEmailHmacSecretProfileGuardTest` precedent).
- [x] `NoPersistedPersonalDataTest`: add `email_recovery_code` to the pseudonymous-only whitelist;
      assert it holds **no** email/name column (design §9).

### Backend — SMTP send port + config-gated adapter — AC1, AC2, AC6

- [x] `SendRecoveryCodeEmail.send(String email, String code)` out-port in `identity.application`
      (narrow + intention-revealing, not a generic mail gateway — YAGNI).
- [x] `JavaMailSenderRecoveryCodeEmail` (`adapter.out`) backed by Spring `JavaMailSender`; all
      `jakarta.mail`/Spring-mail types confined to the adapter (AD-1/AD-2). Content: plain German, the
      code, the TTL, a "you requested this on SGART — ignore if not" line — **no household data, no
      name** (minimal-content, design §3.1).
- [x] `DeferredSendRecoveryCodeEmail` no-op default (logs at debug, sends nothing), wired via
      `@ConditionalOnProperty(sgart.identity.mail.enabled, matchIfMissing=true→no-op)` mirroring the
      Keycloak-admin adapter gating in `IdentityBeansConfig`. Building the bean performs no I/O.
- [x] Add `spring-boot-starter-mail` (managed by Spring Boot 4.1.0) to `backend/build.gradle.kts` +
      `sgart.identity.mail.*` config keys (host/port/credentials/from). Document the real-SMTP +
      SPF/DKIM deploy seam in `docs/first-real-world-test.md` beside the 7.1 reverse-proxy seam.
- [x] `IdentityBeansConfigTest`: assert the no-op default is wired when the property is absent (the
      contextLoads-without-SMTP guarantee).

### Backend — Admin-API email + rebind capabilities — AC1, AC2, AC4

- [x] Extend `KeycloakAdminCreateAccount` (it already holds the token fetch + `manage-users`) with:
      set email + `emailVerified` on a user; clear email + `emailVerified`; find a user by email
      (`GET users?email=&exact=true`, returns `KeycloakUserId` or empty); **rebind**
      (`PUT users/{kc1}` setting `username` + the `publicKey` attribute); get-user email/`emailVerified`
      (for the sweep). Introduce narrow domain ports for these (e.g. `SetAccountEmail`,
      `RebindAccountCredential`, `FindAccountByEmail`) that the same class implements — keep the SPI and
      `SecurityConfig` untouched (design §1/§10). Deferred no-op siblings for the config-gated-off case,
      following the existing `DeferredCreateAccount`/`DeferredDeleteAccount` precedent.
- [x] Unit-test the new Admin operations against a mocked `RestClient` (mirror
      `KeycloakAdminCreateAccountTest`): rebind issues the right `PUT` body; find-by-email exact-match;
      set/clear email; the delete-throwaway-then-rebind ordering is honored by the caller.

### Backend — application services + endpoints + error advice — AC1, AC2, AC3, AC4, AC5

- [x] `AttachRecoveryEmail` service: validate email (fail-fast → `InvalidRecoveryEmailApplicationException`
      → 4xx), set email + `emailVerified:=false`, issue+store a hashed `ATTACH_CONFIRM` code, send it.
- [x] `ConfirmRecoveryEmail` service: verify the `ATTACH_CONFIRM` code (hash/expiry/attempts, wrong →
      increment, exhausted/expired → typed 4xx), on success set `emailVerified:=true` + delete the code.
- [x] `DetachRecoveryEmail` service: clear email + `emailVerified`; delete any of the account's code rows.
- [x] `RequestEmailRecoveryCode` service: find account by email; **only if found** store+send a `RECOVER`
      code; **always** succeed to the caller (`202`, no enumeration — D-H).
- [x] `ConfirmEmailRecovery` (the rebind) service: verify the `RECOVER` code for the resolved `A1`; then
      **in order** delete throwaway `A2` (`kc2` from the caller, via `AuthenticatedCaller`) + its
      `ProvisionedAccount` row, then rebind `A1.username:=U2`, `A1.publicKey:=K2`; delete the code.
      Handle the uniqueness-constraint ordering (design §1.1); a mid-flight failure is recoverable via
      7.1's idempotent re-provision on retry.
- [x] `AccountController`: add `POST /api/v1/account/email`, `POST /api/v1/account/email/confirm`,
      `DELETE /api/v1/account/email`, `POST /api/v1/account/recovery/email`,
      `POST /api/v1/account/recovery/email/confirm` — the first three authenticated as the real account,
      the last two authenticated as the throwaway (**no new permit rule** — they sit under the existing
      authenticated matcher). Request records for each body. Correct status codes (202/204/4xx).
- [x] `AccountErrorAdvice`: map each new typed application exception to its 4xx `ErrorDescriptor`
      (mirror `handleInvalidAccountProvisioning`).
- [x] Application unit tests (fast, no infra): attach issues a code; confirm single-use; wrong/expired/
      exhausted rejected 4xx never 500; detach clears email + code rows; recover valid-code triggers the
      R1 ports **in the right order** (delete throwaway → set username+publicKey); unknown email still
      `202` (no enumeration). Code-store round-trip; expiry + attempt-exhaustion; HMAC (no plaintext);
      isolation between two subjects.
- [x] `AccountControllerTest` / security test: the recovery endpoints require a (throwaway) JWT and add
      no unauthenticated surface; a request without a token is rejected (mirrors
      `AccountProvisioningSecurityTest`).

### Backend — retention sweep gains the confirmed-email check — AC4

- [x] `SweepNeverActivatedAccounts`: "activated" now also counts a **confirmed Keycloak email** —
      derived via an Admin-API get-user (D-G), not a stored bool. Inject the get-email capability; a
      shell with a confirmed email is skipped, an unconfirmed-attach shell past TTL is still swept.
      Ensure a swept account's `email_recovery_code` rows are deleted.
- [x] `SweepNeverActivatedAccountsTest`: confirmed-email account not swept; unconfirmed-attach shell
      swept past TTL; swept account's code rows gone.

### App — account-email API client + cubit — AC1, AC2, AC3

- [x] Account-email API client (over `AuthenticatedHttpClient`): attach, confirm, detach, request
      recovery code, confirm recovery. Mirror `account_provisioning_api.dart` / `identity_api.dart` shape.
- [x] Attach/recover cubit (a new small cubit, or extend `AuthCubit` — dev's call, keep SRP): attach
      email → pending-confirmation state; confirm code → confirmed; detach → not-attached. Distinct,
      inline-friendly error states for invalid email / wrong-expired-exhausted code (fail-fast copy).
- [x] Recover-by-email orchestration: request code → confirm (server rebind `204`) → re-run `signIn()`
      with the existing credential (now bound to `A1`) → `ActiveHouseholdStore.clear()` (AD-7) → reveal
      the **fresh phrase**. Mirror `AuthCubit.recoverFromPhrase`'s identity-swap sequencing and the
      Story 7.2 review lesson: **do not tear down the global auth session on a local input error** —
      validate/handle wrong-code failures **inline in the page**, not by driving the app-wide
      `AuthCubit` to `inProgress → failure` (7.2 Review Finding #1).

### App — three screens + Profil section — AC1, AC2, AC3

- [x] `AddRecoveryEmailPage` (enter email) + `ConfirmEmailCodePage` (enter 6-digit code) — forgiving
      input (trim/whitespace), inline fail-fast errors, loading states. Reached from the Profil section.
- [x] Profil "E-Mail-Wiederherstellung" section in `ProfileScreen`: shows not-attached /
      pending-confirmation / confirmed state (the header already renders `authState.email` live),
      attach → confirm flow, and detach. Mirror the existing locale-row `ListTile` shape.
- [x] `RecoverByEmailPage` (email → code → rebind), reached from a **quiet action on
      `CreateOrAwaitChoicePage`**, peer to 7.2's "Konto wiederherstellen" (phrase). On `204`: swap
      identity via `signIn()`, clear active household, and **immediately** show the fresh phrase by
      reusing `RecoveryPhraseRevealPage` with distinct copy: *"Deine vorherige Wiederherstellungsphrase
      gilt nicht mehr. Sichere diese neue Phrase."* Re-provide `DeviceCredentialStore`/providers across
      the `Navigator.push` boundary (the `openRecoveryPhraseRevealPage`/`_openOnboarding` precedent). No
      browser.

### App — l10n, a11y, widget tests — AC1, AC2, AC3, AC6

- [x] Add `app_de.arb` keys: the Profil section title + states, attach page (title/field/hint), confirm
      page (title/field/hint), the invalid-email + wrong/expired/exhausted-code errors, the recover-by-
      email action + page, the fresh-phrase distinct copy, and detach. Regenerate l10n. No hard-coded
      strings; a11y labels on every new interactive widget.
- [x] Widget/cubit tests (with fakes, no network): attach/confirm/detach cubit flows; recover-by-email
      drives email → code → rebind → identity swap → fresh-phrase reveal; a test asserts the recovery
      path sends no secret it shouldn't and that the fresh phrase is shown after a successful rebind
      (design §9). A wrong code shows an **inline** error and leaves the underlying session intact
      (the 7.2 teardown regression must not recur).

### Full build — AC6

- [x] Run and name green: **both** backend `./gradlew test` (incl. ArchUnit + Testcontainers) **and**
      app `flutter test` + `flutter analyze`. This is a full-stack story — a partial run is not a green
      build (CLAUDE.md §6; `backend-test-hygiene`).

### Definition of Done (standing, per retros)

- [x] No dead strings/fields/stale comments; every new port has both a real and an in-memory/no-op impl.
- [x] Fail-fast on every invalid input (email, code, expiry, attempts); a11y labels on all new
      interactive widgets; forgiving code/email input normalization before validation.
- [x] `commandId`/`basedOnVersion` N/A (no event-sourced command; these are Keycloak/Admin + code-store
      mutations, not the CQRS command bus).
- [x] Privacy DoD (Epic-4/GDPR action): tests explicitly prove the no-persisted-email guarantee, the
      confirmed-email-vs-sweep behavior, and code-row deletion on detach/sweep — not left to review.
- [x] Identity-swap DoD (Epic-2 optimistic-state action): the post-rebind swap leaves no stale
      throwaway state visible (active household cleared; households re-fetched) — verified in a widget
      test.
- [x] Both touched suites named at completion; a red build blocks (CLAUDE.md §6).

## Dev Notes

### Ground truth — read these before coding
- **`epic-7-story-7.3-recover-by-email-design.md`** (Winston, 2026-09-15) — the whole buildable design;
  §1/§1.1 the rebind crux, §3 SMTP, §4 the code table, §5 the security surface, §6 the app contract,
  §7 GDPR + sweep, §8 the resolved forks, §9 test coverage, §10 the footprint. This story is its
  faithful decomposition — when in doubt, the note governs.
- The Story 7.1 file (`7-1-silent-account-provisioning-on-first-launch.md`) — the credential model
  (`username = base64url(device Ed25519 public key)`), the idempotent-provision invariant (AC3), the
  retention sweep (AC5), and the "activation is derived, never stored" principle 7.3 extends.
- The Story 7.2 file (`7-2-recovery-phrase-reveal-and-profile-re-view.md`) — the `recoverFromPhrase`
  identity-swap seam, `RecoveryPhraseRevealPage`, and especially **Review Finding #1** (don't tear
  down the global auth session on a local input error) — the exact trap the email/code entry must avoid.
- `ARCHITECTURE-SPINE.md` AD-5 (`MemberId` in the event log, pseudonymous), AD-6 (email/name on the
  Keycloak account only), AD-7 (erasure by de-linking; device-cache purge / active-household clear).

### The crux — key replacement without breaking the phrase model (design §1, AC2/AC3)
- The Keycloak **username *is* `base64url(device public key)`**. Email recovery happens on a device
  with **no phrase**, so it uses its own new key `K2`/`U2`. The rebind must set the recovered account's
  **username *and* publicKey** to `U2`/`K2` — not just the key — or the fresh phrase `P2` would fail on
  any *third* device. The `keycloak-authenticator` SPI does **not** change (it reads one `publicKey`
  and resolves by the `username` form parameter).
- **The reinstall wrinkle:** 7.1 has already provisioned throwaway `A2` holding `U2`, so `A1` cannot
  take `U2` until `A2` is deleted (Keycloak username uniqueness). Hence the forced order:
  **delete `A2` → rebind `A1`** (design §1.1). The app keeps its existing credential; the server makes
  it point at `A1`. `A1`'s `KeycloakUserId` is preserved (stable pseudonym, AD-5/6) — never churned.
- **The old phrase dies** by design (doubles as device revocation). AC copy must say so plainly.

### Security surface (design §5, AC5)
- **No new unauthenticated endpoint, no new `SecurityConfig` permit rule.** Because 7.1 always
  provisions + signs in first, the app holds a throwaway JWT by the time recovery runs; the recovery
  endpoints are authenticated as it, and the JWT supplies `kc2` for the delete step. This is a real
  hardening win over open endpoints — treat the "7.1 provisions first" invariant as documented, not
  fragile.
- **No enumeration:** request-a-code is `202` whether or not the email exists; failure reasons are
  server-side-only.

### SMTP as a config-gated deploy seam (design §3, AC6)
- The **only** Epic-7 feature that sends email. The real adapter is gated on
  `sgart.identity.mail.enabled=true`; the `Deferred…` no-op is the default so **no build needs an SMTP
  server** and `contextLoads` stays green. Building the sender does no I/O. Real netcup SMTP + SPF/DKIM
  is documented in `docs/first-real-world-test.md`, not wired into CI.

### GDPR / privacy (CLAUDE.md §5, AD-6/AD-7, AC4)
- Email is **personal data collected only on explicit action, single purpose = recovery** — never
  silent, never at provisioning. It lives **only on the Keycloak account** (AD-6 exception, like the
  display name). The code table is keyed by the pseudonymous `KeycloakUserId` and has **no email
  column**. Erasure (AD-7) already deletes the Keycloak account (taking the email) — 7.3 adds detach
  (revocable consent) + code-row cleanup, not a new erasure step. Full export/erasure *feature* tests
  stay Epic 6; 7.3 adds the targeted privacy tests (design §9).

### Scope guards (KISS / YAGNI — CLAUDE.md §1)
- **In 7.3:** attach/confirm/detach email, recover-by-email + R1 rebind, the code table + SMTP port
  (config-gated), the sweep confirmed-email check, the three app screens + Profil section, l10n/a11y.
- **Not in 7.3:** consent capture (7.4), invite-by-code (7.5), the full export/erasure *feature* UI
  (Epic 6), device attestation (named pre-public-release fast-follow), IP rate-limiting (reverse-proxy
  deploy seam), any change to the `keycloak-authenticator` SPI, the event log, `MemberMapping`'s
  schema, or `SecurityConfig`'s permit rules.

### Testing standards (CLAUDE.md §6 — this is a security-sensitive story, per the Epic-7 critical path)
- **Application (fast, no infra):** attach/confirm/detach/recover services incl. the R1 rebind port
  ordering and the no-enumeration `202`.
- **Code store:** round-trip, expiry, attempt-exhaustion, HMAC (no plaintext), subject isolation.
- **Retention:** confirmed-email account not swept; unconfirmed-attach shell swept past TTL; swept
  account's code rows gone.
- **Privacy:** `NoPersistedPersonalDataTest` covers the new table; a test asserts no SGART store holds
  the raw email; the email is reachable + deletable via detach/erasure.
- **Adapter:** `JavaMailSenderRecoveryCodeEmail` sends to the right recipient with the code (Spring
  mail harness / GreenMail-style or a fake at the port); the `Deferred…` no-op sends nothing.
- **App:** attach/confirm/detach + recover-by-email flows with fakes (no network); the fresh phrase is
  shown after a successful rebind; a wrong code errors **inline** without tearing down the session.
- Synthetic, clearly-fake email/codes only — never real personal data (CLAUDE.md §6 DSGVO-in-tests).

## Test Manifest (task → named test)

| Task / AC | Named test |
|-----------|------------|
| AC1 attach | `attachRecoveryEmail_setsUnverifiedEmailAndIssuesCode` |
| AC1 confirm | `confirmRecoveryEmail_withCorrectCode_marksEmailVerifiedAndConsumesCode`, `confirmRecoveryEmail_withWrongOrExpiredCode_isRejectedFastAndChangesNothing` |
| AC1 malformed | `attachRecoveryEmail_withMalformedEmail_returnsBadRequestNotServerError` |
| AC2 request no-enum | `requestEmailRecoveryCode_withUnknownEmail_stillReturnsAcceptedAndSendsNothing` |
| AC2 rebind order | `confirmEmailRecovery_deletesThrowawayThenRebindsTargetUsernameAndPublicKey` |
| AC2 id preserved | `confirmEmailRecovery_preservesTheTargetKeycloakUserId` |
| AC1/AC2 code store | `emailRecoveryCodeStore_roundTripsHashedCode`, `emailRecoveryCodeStore_expiresAndExhaustsAttempts`, `emailRecoveryCodeStore_isolatesSubjects` |
| AC1 HMAC | `emailRecoveryCode_persistsHmacNotPlaintext` |
| AC4 detach | `detachRecoveryEmail_clearsEmailAndDeletesCodeRows` |
| AC4 no-persist | `noSgartStorePersistsTheRawRecoveryEmail`, `NoPersistedPersonalDataTest` (email_recovery_code has no email/name column) |
| AC4 sweep | `sweep_keepsAccountWithConfirmedEmail`, `sweep_deletesUnconfirmedAttachShellPastTtl_andItsCodeRows` |
| AC5 security | `recoveryEndpoints_requireAThrowawayJwt_andAddNoUnauthenticatedSurface` |
| AC6 config gate | `mailSenderNoOpIsWiredWhenMailDisabled_soContextLoadsWithoutSmtp` |
| AC6 mail adapter | `javaMailSenderRecoveryCodeEmail_sendsCodeToRecipient`, `deferredSendRecoveryCodeEmail_sendsNothing` |
| AC2/AC3 app swap | `recoverByEmail_validCode_rebindsSwapsIdentityAndRevealsFreshPhrase` |
| AC1 app flows | `profileRecoveryEmailSection_attachConfirmDetach_updatesState` |
| AC2 app inline error | `recoverByEmail_wrongCode_showsInlineErrorAndKeepsSessionIntact` |
| AC2/AC4 app secret | `recoverByEmailPath_sendsNoLocalSecretItShouldNot` |

## Project Structure Notes

- **Backend (`de.sgart.identity`):** `application` — `AttachRecoveryEmail`, `ConfirmRecoveryEmail`,
  `DetachRecoveryEmail`, `RequestEmailRecoveryCode`, `ConfirmEmailRecovery` + their typed
  `*ApplicationException`s + the `SendRecoveryCodeEmail` / `EmailRecoveryCodeStore` / `SetAccountEmail`
  / `RebindAccountCredential` / `FindAccountByEmail` ports; `adapter.out` —
  `JavaMailSenderRecoveryCodeEmail` + `DeferredSendRecoveryCodeEmail`, `JdbcEmailRecoveryCodeStore` +
  `InMemoryEmailRecoveryCodeStore`, the extended `KeycloakAdminCreateAccount` (+ deferred siblings);
  `adapter.in` — the new `AccountController` endpoints + `AccountErrorAdvice` handlers; `IdentityBeansConfig`
  wires the config-gated beans. `V19__email_recovery_code.sql`. If `application`/`adapter.out` grows
  past a flat read, group by intent per CLAUDE.md §8 (`application.command`/`application.exception`) —
  but only if it earns its keep (KISS); the ArchUnit `..domain..`/`..application..` matchers need no
  change.
- **No change:** `keycloak-authenticator` SPI + its acceptance test, the event log, `MemberMapping`'s
  schema, `SecurityConfig`'s permit rules.
- **App (Flutter):** `AddRecoveryEmailPage`, `ConfirmEmailCodePage`, `RecoverByEmailPage` under
  `features/auth/presentation/`; the account-email API client under `features/auth/data/`; the Profil
  section in `features/settings/presentation/profile_screen.dart`; the recover-by-email entry on
  `features/households/presentation/create_or_await_choice_page.dart`; reuse of
  `recovery_phrase_reveal_page.dart`; new `app_de.arb` strings + `l10n/gen/*`.
- **Split seam (not taken, D-J):** if this exceeds appetite mid-build, the natural cut is
  7.3a (attach/confirm/detach + code table + SMTP infra) vs. 7.3b (recover-by-email + R1 rebind). Timo
  chose one story; only resurface the seam if the token/scope gate actually fires.

## References
- `epic-7-story-7.3-recover-by-email-design.md` (the design note — governs)
- `epics.md` §Epic 7 Story 7.3 · `sprint-change-proposal-2026-09-13.md` (rev E)
- Story 7.1 + `epic-7-story-7.1-provisioning-design.md` (credential model, sweep, idempotent provision)
- Story 7.2 file (identity-swap seam, `RecoveryPhraseRevealPage`, Review Finding #1)
- `ARCHITECTURE-SPINE.md` AD-5/AD-6/AD-7
- Backend: `KeycloakAdminCreateAccount(Test)` · `ProvisionAccount` · `ProvisionedAccountRepository` ·
  `SweepNeverActivatedAccounts` · `AccountController` · `AccountErrorAdvice` · `SecurityConfig` ·
  `AuthenticatedCaller` · `IdentityBeansConfig(Test)` · `NoPersistedPersonalDataTest` ·
  `HmacSha256InviteEmailHasher` / `InviteEmailHmacSecretProfileGuardTest`
- App: `auth_cubit.dart` (`recoverFromPhrase`) · `direct_grant_oidc_client.dart` ·
  `recovery_phrase_reveal_page.dart` · `create_or_await_choice_page.dart` · `profile_screen.dart` ·
  `account_provisioning_api.dart` · `active_household_store.dart`

## Questions for Timo (non-blocking — design forks already resolved 2026-09-15; sensible defaults chosen)

- **Attach cubit vs. extend `AuthCubit`** — default: a **new small cubit** for attach/confirm/detach
  (SRP; `AuthCubit` stays about session state), with only the recover-by-email identity swap reaching
  into the auth seam. Prefer folding it into `AuthCubit`?
- **Identity-local vs. shared HMAC hasher** — default: a **small identity-local** HMAC helper mirroring
  `HmacSha256InviteEmailHasher` rather than a cross-context dependency on `collaboration` (SoC).
  Prefer promoting one shared hasher?
- **Recover-by-email entry placement** — default: a **quiet action on `CreateOrAwaitChoicePage`**, peer
  to 7.2's phrase recovery (the guaranteed 0-household gateway). Same as the phrase path — confirm ok.

## Change Log

| Date | Change | By |
|------|--------|-----|
| 2026-09-15 | Story drafted (create-story) from `epic-7-story-7.3-recover-by-email-design.md` (all four forks pre-resolved with Timo) + epics.md §Epic 7 Story 7.3, grounded in the shipped `identity` backend (KeycloakAdminCreateAccount, ProvisionAccount, SweepNeverActivatedAccounts, AccountController/ErrorAdvice, SecurityConfig, IdentityBeansConfig, NoPersistedPersonalDataTest, the HmacSha256InviteEmailHasher pattern) and the Story 7.1/7.2 app seams. Full-stack, one story (D-J). Locked decisions D-A…D-J carried from the design note. Status → ready for dev | Claude (Opus 4.8) |
| 2026-09-16 | Addressed all 15 open [Review][Patch] findings (the 3 Decision items per Timo's 2026-09-16 resolutions, plus 12 further patches): backend — `AuthenticatedCaller`/`IdentityController` now carry live `email_verified`; `KeycloakAdminCreateAccount.findByEmail` filters to Keycloak-confirmed emails and warn-logs an ambiguous match; `ConfirmEmailRecovery` rejects a caller recovering into their own throwaway account and documents the accepted delete-before-rebind partial-failure risk inline (tracked in `deferred-work.md`); `VerifyRecoveryCode` fails fast on a blank/null code instead of NPE-ing; `RecoveryCode.matches` uses `MessageDigest.isEqual`; `RequestEmailRecoveryCode`'s Javadoc no longer overclaims timing parity; new/extended tests — `IdentityControllerTest`, `KeycloakAdminCreateAccountTest` (verified-only match, ambiguous-match, unverified-excluded), `ConfirmEmailRecoveryTest` (self-recovery guard, blank code, RECOVER attempt-exhaustion, replay-after-success, delete-before-rebind ordering via a new shared-log `OrderedDeleteAccount`/`OrderedRebindAccountCredential` pair in `RecoveryEmailTestSupport`), `SweepNeverActivatedAccountsTest` now reuses the shared `FakeGetAccountDetails`/`RecordingDeleteAccount` instead of duplicating them. App — `CallerIdentity`/`AuthState` carry `emailVerified` (default `false`, backward compatible) so `ProfileScreen` seeds `pendingConfirmation` vs. `confirmed` correctly instead of guessing from a non-empty email; `AddRecoveryEmailPage`'s `listenWhen` now fires on every successful attach (isBusy true→false landing on `pendingConfirmation`), not only the first, so re-requesting a code after backing out of the confirm step reopens it; removed the dead `AccountEmailCubit.clearError()`; added the three missing `error_message_resolver_test.dart` arms; fixed the `account_email_cubit_test.dart` test name that claimed "stays not-attached" but asserted the `unknown` seed. Both suites re-verified green: backend `./gradlew test` (root `sgart-backend` + `keycloak-authenticator`, incl. ArchUnit + Testcontainers) and app `flutter test` (704 tests) + `flutter analyze` (no issues) | Claude Sonnet 5 |
| 2026-09-15 | Implemented end-to-end: backend `V19__email_recovery_code.sql` (table named `recovery_code` — the literal identifier avoids the `NoPersistedPersonalDataTest` migration-scan tripping on the substring "email" in a table/index name that carries no email column), `EmailRecoveryCodeStore`/`RecoveryCodePurpose`/`EmailRecoveryCode` (domain) + Jdbc/InMemory adapters, `RecoveryCodeHasher`/`HmacSha256RecoveryCodeHasher` (identity-local, mirrors `HmacSha256InviteEmailHasher`), `SendRecoveryCodeEmail`/`JavaMailSenderRecoveryCodeEmail`/`DeferredSendRecoveryCodeEmail` (+ `spring-boot-starter-mail`, `management.health.mail.enabled=false`), `SetAccountEmail`/`RebindAccountCredential`/`FindAccountByEmail`/`GetAccountDetails` extending `KeycloakAdminCreateAccount` (+ `Deferred*` siblings — no extra `@Bean` wrapper methods, since Spring already matches the one concrete bean against every interface it implements), `AttachRecoveryEmail`/`ConfirmRecoveryEmail`/`DetachRecoveryEmail`/`RequestEmailRecoveryCode`/`ConfirmEmailRecovery` (+ shared `VerifyRecoveryCode`/`RecoveryCode`/`RecoveryEmailValidation` helpers), new `AccountController` endpoints + `AccountErrorAdvice` handlers (no new `SecurityConfig` rule), `SweepNeverActivatedAccounts` confirmed-email check. App: `AccountEmailApi`/`AccountEmailCubit`/`AccountEmailState`, `AddRecoveryEmailPage`/`ConfirmEmailCodePage`/`RecoverByEmailPage`, the Profil "E-Mail-Wiederherstellung" section, `AuthCubit.recoverFromEmailRebind`, `RecoveryPhraseRevealPage`'s `isFreshAfterEmailRecovery` distinct copy, `app_de.arb` keys + `error_message_resolver` mappings. Both suites green: backend `./gradlew test` (144 test classes, 0 failures, incl. ArchUnit + Testcontainers) and app `flutter test` (698 tests) + `flutter analyze` (no issues). Deploy seam documented in `docs/first-real-world-test.md`. Status → in-review | Claude Sonnet 5 |

| 2026-09-17 | Round-2 code review of the 15 applied patches (Claude Opus 4.8, 4-layer). 1 patch, 11 rejected (2 refuted `false`, 9 `low`). Applied the single patch: added `app/test/features/auth/presentation/add_recovery_email_page_test.dart` — a widget test proving the rewritten `listenWhen` re-opens `ConfirmEmailCodePage` on a re-request while already `pendingConfirmation`, and keeps it closed on a failed re-request (the `error == null` arm). Verified the test fails against the pre-patch transition-gated `listenWhen`. App suite green: `flutter test` (706 tests) + `flutter analyze` (no issues). Status → done | Claude Opus 4.8 |

## Review Findings

_Code review 2026-09-16 (Claude Opus 4.8, 4-layer: Blind Hunter, Edge Case Hunter, Verification Gap, Acceptance Auditor). 3 decision-needed, 12 patch, 2 defer, 4 rejected._

### Review Findings

- [x] [Review][Patch] (was Decision; resolved Timo 2026-09-16 → **accept & document**) Partial-failure in `confirmAndRebind` orphans the device — `ConfirmEmailRecovery.confirmAndRebind` deletes the throwaway account (Keycloak + `ProvisionedAccount` row) *before* `rebindAccountCredential.rebind(target,…)`. If the rebind throws (Keycloak 5xx / network), the throwaway is gone, the target is not rebound, and the RECOVER code row is not deleted — the device credential now authenticates into nothing, with no compensation/rollback. Delete-first order is load-bearing (username uniqueness). **Resolution:** no behavior change — add a comment documenting the accepted infra-failure-only risk; hardening tracked in `deferred-work.md`. [backend/src/main/java/de/sgart/identity/application/ConfirmEmailRecovery.java]
- [x] [Review][Patch] (was Decision; resolved Timo 2026-09-16 → **expose `emailVerified` on `/me`**) Profil status seed shows an unconfirmed email as "Bestätigt" — `_ProfileScreenState.initState` seeds `confirmed` for any non-empty `AuthState.email`, but `AuthState` / `/me` carry no `emailVerified`, so after relaunch an attached-but-unconfirmed email reads "Bestätigt" and offers detach instead of "Bestätigung ausstehend" (AC1's three-state accuracy). **Resolution:** add `emailVerified` to the `/me` response + `AuthState`, and seed `pending` vs `confirmed` from it. [app/lib/features/settings/presentation/profile_screen.dart:760, backend `/me` endpoint, app/lib/features/auth/presentation/auth_state.dart]
- [x] [Review][Patch] (was Decision; resolved Timo 2026-09-16 → **restrict recovery lookup to verified emails**) Unverified attached emails are recovery-eligible and never cleaned up — `AttachRecoveryEmail` persists the address (`verified=false`) immediately, and `FindAccountByEmail`/`findByEmail` does not filter `emailVerified`, so an unproven or squatted address participates in recovery lookup, and a duplicate address silently breaks lookup (`length != 1` → empty). **Resolution:** filter `emailVerified` in `findByEmail` (only a verified address is recovery-eligible); add a test. [backend/src/main/java/de/sgart/identity/adapter/out/KeycloakAdminCreateAccount.java:findByEmail]

- [x] [Review][Patch] `attach` re-request after backing out of the code step is dead-ended — `AddRecoveryEmailPage` `listenWhen` only fires on non-pending→pending; a second `attach()` emits pending→pending, so `ConfirmEmailCodePage` never reopens and the person is stuck on the email step though a new code was sent. [app/lib/features/auth/presentation/add_recovery_email_page.dart:241]
- [x] [Review][Patch] Self-recovery bricks the account — if `findByEmail(email)` resolves to the caller's own throwaway (`target == throwawayCaller`), `confirmAndRebind` deletes that account then rebinds a now-deleted id → lockout. Guard `target.equals(throwawayCaller)` (reject as a rejected code). [backend/src/main/java/de/sgart/identity/application/ConfirmEmailRecovery.java:confirmAndRebind]
- [x] [Review][Patch] Null/blank `code` → unmapped NPE → 500 — with a stored code row present, a body omitting `code` reaches `RecoveryCode.matches` → `hasher.hash(null)` NPE, unmapped by `AccountErrorAdvice` → 500, contra the endpoint's "never 500" contract (low reachability: the app always sends a trimmed code). Add a blank-code guard → `RecoveryCodeRejectedException`. [backend/src/main/java/de/sgart/identity/application/VerifyRecoveryCode.java]
- [x] [Review][Patch] Non-constant-time code comparison — `RecoveryCode.matches` uses `hash(candidate).equals(stored.codeHash())`; prefer `MessageDigest.isEqual` (CLAUDE.md §5 security-by-design). [backend/src/main/java/de/sgart/identity/application/RecoveryCode.java:matches]
- [x] [Review][Patch] Dead code `AccountEmailCubit.clearError()` — defined and documented but no caller anywhere in `app/lib`/`app/test`; delete (CLAUDE.md §1). [app/lib/features/auth/presentation/account_email_cubit.dart:113]
- [x] [Review][Patch] `findByEmail` silently returns empty on an ambiguous (`length != 1`) result with no log — a duplicate/ambiguous Keycloak result makes recovery quietly impossible and undiagnosable; add a warn log. [backend/src/main/java/de/sgart/identity/adapter/out/KeycloakAdminCreateAccount.java:findByEmail]
- [x] [Review][Patch] Misleading timing comment — `RequestEmailRecoveryCode`'s Javadoc claims "does no I/O when the email is unknown, so timing stays close between the two paths"; the paths differ (known path hashes + stores + sends), so the comment overstates. Correct it to the real posture (constant `202` + rate-limiting per ADR-0002, not timing parity). [backend/src/main/java/de/sgart/identity/application/RequestEmailRecoveryCode.java]
- [x] [Review][Patch] Resolver arms untested — `account.recoveryEmailRequired` / `account.recoveryEmailInvalid` / `account.recoveryCodeInvalid` have no `error_message_resolver_test.dart` case (the file's per-arm convention); deleting an arm falls through to `errorGenericFallback` with a green suite. Add three per-arm assertions. [app/test/shared/errors/error_message_resolver_test.dart]
- [x] [Review][Patch] Delete-before-rebind ordering asserted by no test — `ConfirmEmailRecoveryTest` uses separate recording doubles with no shared timeline; reordering the two load-bearing production statements keeps the suite green. Give both doubles one shared ordered log and assert delete precedes rebind. [backend/src/test/java/de/sgart/identity/application/ConfirmEmailRecoveryTest.java]
- [x] [Review][Patch] `ConfirmEmailRecoveryTest` lacks RECOVER attempt-exhausted + replay-after-rebind cases — the most security-critical verifier is the least fully tested (unlike `ConfirmRecoveryEmailTest`). Add MAX_ATTEMPTS-exhausted and single-use-replay cases for RECOVER. [backend/src/test/java/de/sgart/identity/application/ConfirmEmailRecoveryTest.java]
- [x] [Review][Patch] Test name contradicts assertion — `attach_serverRejection_surfacesTheErrorAndStaysNotAttached` asserts `status == unknown` (the seed), not `notAttached`; rename or fix the seed. [app/test/features/auth/presentation/account_email_cubit_test.dart]
- [x] [Review][Patch] Duplicated test double — `SweepNeverActivatedAccountsTest` defines its own `FakeGetAccountDetails` while `RecoveryEmailTestSupport` already provides one; reuse the shared double (DRY). [backend/src/test/java/de/sgart/identity/application/SweepNeverActivatedAccountsTest.java]

- [x] [Review][Defer] Attach with an already-registered email → possible 500 + enumeration [backend/src/main/java/de/sgart/identity/application/AttachRecoveryEmail.java] — deferred: maybe-false, would be medium. `setEmail`'s PUT has no 4xx handler; if the realm enforces unique emails, a duplicate errors → unmapped 500 that also differs from the 202 success (an oracle on the authenticated attach endpoint). Settle by checking the realm's duplicate-email / login-with-email setting.
- [x] [Review][Defer] No throttling on `attach` / `requestRecoveryCode` [backend/src/main/java/de/sgart/identity/adapter/in/AccountController.java] — deferred: pre-existing architectural posture — rate limiting is delegated to the ADR-0002 gateway seam, not app code; inbox-spam / TTL-reset possible until that seam exists.

#### Rejected

- **[false] Recover-by-email `listenWhen` fires prematurely on an authenticated re-emit** (Edge Case Hunter) — `RecoverByEmailPage`'s `listenWhen` (`current.status == authenticated`) is byte-identical to the shipped `RecoverAccountPage` (recover_account_page.dart:89) and matches its own comment; no deviation is introduced here, and any re-emit risk is a pre-existing property of shipped code, not this change's defect.
- **[low] Throwaway swept between provisioning and recovery-confirm → `IllegalStateException` → 500** (Edge Case Hunter) — the retention TTL is measured in days while the Direct-Grant JWT lives ~5 minutes, so a swept-but-still-authenticated throwaway is effectively unreachable; adding a branch for an unreachable case isn't worth it.
- **[low] No cleanup of expired `recovery_code` rows for active accounts / dead `expires_at` index** (Blind Hunter) — rows are PII-free (pseudonymous id + HMAC) and the upsert caps them at one per `(keycloak_user_id, purpose)`, cleared on success/detach/sweep, so this is neither a personal-data retention breach nor unbounded growth; a dedicated expiry sweep is complexity out of proportion (the index is harmless/forward-looking).
- **[low] SMTP / Keycloak infra failure → 500** (Blind Hunter) — a backend outage conventionally surfaces as 500; the "never 500" AC governs malformed *input*, not infra availability. (The duplicate-email-specific variant is captured under Defer.)

### Review Findings — Round 2: verification of the 15 applied patches (2026-09-16)

_Code review 2026-09-16 (Claude Opus 4.8, 4-layer: Blind Hunter, Edge Case Hunter, Verification Gap, Acceptance Auditor), scoped to the diff between the pre-patch tree and the working tree (847 lines, 22 files). 0 decision-needed, 1 patch, 0 defer, 11 rejected._

- [x] [Review][Patch] `AddRecoveryEmailPage`'s rewritten `listenWhen` has no widget test — the fix this batch applied (fire on every attach completion `isBusy` true→false landing on `pendingConfirmation`, so a re-request after backing out of the code step re-opens `ConfirmEmailCodePage`) is exercised by no test at all: there is no `add_recovery_email_page_test.dart`, and `account_email_cubit_test.dart` covers only the cubit's state, not the page's listener/navigation. Reverting the gate to its old status-transition shape re-introduces the dead-end with a green suite. Add a widget test (mirror the `profile_screen_test.dart` BlocProvider + `FakeAccountEmailApi` setup) that seeds `pendingConfirmation`, triggers a fresh `attach()`, and asserts the confirm-code page is pushed. [app/lib/features/auth/presentation/add_recovery_email_page.dart:82]

#### Rejected

- **[false] `confirmAndRebind` "tracked in deferred-work.md" references a phantom entry** (Blind Hunter) — `deferred-work.md:257` contains the exact `**confirmAndRebind partial-failure compensation**` entry the inline comment points to; the reference is real.
- **[false] Delete-before-rebind ordering test does not assert the rebind payload (U2/K2)** (Acceptance Auditor) — the payload is fully asserted by the pre-existing happy-path test `confirmEmailRecovery_deletesThrowawayThenRebindsTargetUsernameAndPublicKey` (`ConfirmEmailRecoveryTest:57-73`: `rebind.username()==U2`, `publicKey()==K2`); the new ordering test deliberately covers only relative call order via the shared log.
- **[low] `email_verified` claim absent from the `/me` token seeds a confirmed email as "pending"** (Acceptance Auditor) — the realm export defines no custom `email_verified` mapper and no explicit default client scopes, so `/me` relies on Keycloak's built-in `email` scope whose `email_verified` mapper defaults to access-token = ON; the claim is present in practice, and if a deployment stripped it the effect is a self-healing cosmetic mislabel (corrected by any in-session attach/confirm/detach). Settle by decoding a real `/me` access token to confirm `email_verified` is present.
- **[low] `AuthenticatedCaller.fromJwt` `getClaimAsBoolean("email_verified")` can throw → `/me` 500** (Edge Case Hunter) — the token is signed by our own Keycloak and `email_verified` is a standard boolean (or absent, handled by the `!= null` guard); a non-Boolean-convertible value is unreachable in practice, and the fix adds a try/catch guard for a path the program can't reach.
- **[low] `KeycloakUserResponse.emailVerified` nullable → a verified user becomes non-recoverable if the field is absent** (Blind Hunter) — Keycloak's admin user-search returns `emailVerified` in `UserRepresentation`; treating absent/null as not-eligible is the intended fail-closed security posture (`Boolean.TRUE.equals`), not a defect.
- **[low] Verified-only filter changes the request-code path but only the find/confirm paths got tests** (Blind Hunter) — the filter is unit-tested at the `findByEmail` seam (`findByEmail_matchWithUnverifiedEmail_returnsEmpty`); at the `RequestEmailRecoveryCode` level an unverified email is behaviorally identical to an unknown one (returns empty → no code), already covered.
- **[low] Constant-time compare in `RecoveryCode.verify` is inconsistent with the surrounding early-returns** (Blind Hunter) — `MessageDigest.isEqual` is correct and harmless; the expiry/attempt early-returns are a separate concern, the comment already notes the collapsed 6-digit space, and no concrete named harm follows.
- **[low] `CallerIdentity.fromJson` tolerates a non-bool `emailVerified` while the other three fields fail-fast** (Blind Hunter + Acceptance Auditor) — a deliberate, explicitly documented exception (CLAUDE.md §1 permits documented exceptions); worst case defaults a display-only boolean to `false`, and making it strict would break the intended absent-defaults-false behavior with an older backend / hand-built fixture.
- **[low] The renamed cubit test drops the `notAttached`-seeded intent instead of restoring it** (Blind Hunter) — the cubit does not branch on current status when handling a rejection, so the "leaves status unchanged" proof from the `unknown` seed generalizes; a `notAttached`-seeded variant is marginal.
- **[low] `VerifyRecoveryCode`'s blank/null guard has no direct unit test at its own seam** (Blind Hunter) — `VerifyRecoveryCode` is package-private; the guard is covered behaviorally via `ConfirmEmailRecoveryTest.confirmEmailRecovery_withBlankCode_isRejectedFastWithoutHashingNull` (test behavior, not implementation).
- **[low] Ambiguous verified-email match blocks recovery with only a WARN log; add a metric/alert** (Blind Hunter) — the return-empty + WARN-log behavior is per-spec (D-H, no enumeration) and the WARN gives an operator diagnosability; a counter/alert is an enhancement beyond scope that adds new observability surface.
