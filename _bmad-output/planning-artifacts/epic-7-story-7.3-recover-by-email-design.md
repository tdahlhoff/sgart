# Story 7.3 — Recover by Email (opt-in): Architect Design Note

**Author:** Winston (System Architect) · **Date:** 2026-09-15 · **Status:** draft for Timo's review
**Feeds:** `create-story` 7.3 · **Scope agreed with Timo (2026-09-15):** one story covering
attach-email + confirm + detach + recover-by-email + erasure/export inclusion + the SMTP/one-time-code
infrastructure it pulls in.

**Ground truth:** `epics.md` §Epic 7 (Story 7.3), `sprint-change-proposal-2026-09-13.md` (rev E),
`epic-7-story-7.1-provisioning-design.md` (the credential model this extends), the Story 7.1/7.2
files, `ARCHITECTURE-SPINE.md` (AD-5/6/7), and the shipped `identity` context
(`KeycloakAdminCreateAccount`, `ProvisionAccount`, `SweepNeverActivatedAccounts`, `SecurityConfig`,
`IdentityBeansConfig`, the `keycloak-authenticator` SPI).

**Mechanism decided with Timo (2026-09-15):** **key replacement**, not a multi-key authorized set.
Email recovery rebinds the account to the new device's key, issues a **fresh** recovery phrase shown
prominently on the recovered device, and the previous phrase stops working — with clear user copy.
The recovery phrase stays the primary method; email is the optional secondary one. This note pins the
buildable detail and flags the genuine remaining forks (§8).

---

## 1. The crux: key replacement without breaking the phrase model

7.1/7.2 rest on one invariant: **the Keycloak username *is* `base64url(device Ed25519 public key)`**,
so a fresh device can derive the username locally from the recovery phrase with **no server lookup**
(7.2's whole point), and the authenticator SPI verifies the signed challenge against the account's
single stored `publicKey` attribute.

Email recovery happens on a device with **no phrase**, so it must generate a **new** keypair
(new entropy → `K2` → `U2 = base64url(K2)`). To keep the invariant — and therefore keep the newly
issued phrase `P2` a real, portable recovery phrase — the rebind must set the recovered account's
**username *and* public key** to the new device's, not just swap the key. If only the key were
swapped and the old username kept, `P2` would sign this one device in but would fail on any *third*
device (it would derive `U2`, which wouldn't resolve to the account). So:

> **Rebind = set the recovered account's `username := U2` and `publicKey := K2` (Admin API).**

Consequences that fall out cleanly:

- **The authenticator SPI does not change.** It still reads one `publicKey` attribute and resolves the
  user by the `username` form parameter. Key replacement is entirely an Admin-API-side account
  mutation. (This is the main reason key replacement is lower-risk than multi-key — the
  security-critical `keycloak-authenticator` module and its acceptance test are untouched.)
- **Household data is never stranded.** Memberships live in the Identity ACL keyed by Keycloak's
  stable internal **user UUID** (`KeycloakUserId`), never the username. Mutating the username leaves
  `MemberMapping` — and every `MemberId` the event log references (AD-5) — untouched.
- **The old phrase dies.** After rebind the account trusts `K2`; the previous phrase (deriving the old
  username/key) no longer resolves or verifies. This is the agreed key-replacement semantics and
  doubles as **device revocation** — a lost/stolen phone or an old written phrase loses access on
  recovery.
- **AC wording relaxed (Timo, 2026-09-15):** the epics.md AC "the phrase remains a fully working
  recovery path — email is additive, never a replacement" is nuanced to *"a phrase recovery path
  always remains available — email recovery issues a fresh phrase, shown immediately, and never
  removes the phrase option."* The capability persists; a specific phrase string does not survive an
  email recovery.

### 1.1 The reinstall wrinkle: a throwaway already holds `U2`

On a reinstall/fresh device, Story 7.1 has **already** silently provisioned a throwaway account
`A2` whose username is exactly `U2 = base64url(K2)` (the device's own new key), and the app is
already signed in as `A2`. So the rebind cannot naively `PUT` the recovered account `A1.username := U2`
— Keycloak enforces username uniqueness and `A2` holds it.

**Resolved approach (R1, §8 fork A):** the rebind operation, server-side, in order:

1. Verify the one-time code → resolves the target account `A1` (its `KeycloakUserId` `kc1`, found by
   email lookup, §3).
2. **Delete the throwaway `A2`** (Keycloak `DELETE users/{kc2}` + its `ProvisionedAccount` row) — it is
   an empty, never-activated shell, this device's own; deleting it frees `U2`. `kc2` comes from the
   caller's throwaway JWT (§5).
3. **Rebind `A1`:** Admin API `PUT users/{kc1}` with `username := U2`, `attributes.publicKey := K2`.
4. `204`. The app re-runs `signIn()` with its **existing** local credential (`K2`/`U2`) — which now
   authenticates into `A1` — then reveals the fresh phrase `P2` (§6).

The device needs **no new key handling**: it keeps the credential it provisioned with; the server
simply makes that credential point at `A1`. `A1`'s stable `KeycloakUserId` `kc1` is preserved across
every recovery (a stable pseudonymous id, AD-5/6). The delete-then-rebind ordering is forced by the
uniqueness constraint; a mid-flight failure is recoverable because the app re-provisions `U2`
idempotently on retry (7.1 AC3).

The considered alternative (R2 — adopt `A1`'s memberships into `A2` and delete `A1`) is **rejected**:
it re-keys the ACL and **churns the pseudonymous `KeycloakUserId`** on every recovery, weakening the
stable-id posture, for no user-visible gain. See §8 fork A.

---

## 2. Attach & confirm an email (authenticated, on the person's real device)

No throwaway complication here — the caller is the real, signed-in account.

- **Attach:** `POST /api/v1/account/email { email }` (authenticated). Sets the email on the Keycloak
  account via Admin API with `emailVerified := false`, generates a one-time code, stores its hash
  (§4), and sends it over SMTP (§3). Fail-fast validation on a malformed email → 4xx, never 500
  (mirrors `AccountErrorAdvice`). Response `202 Accepted` (a code was sent; ownership not yet proven).
- **Confirm:** `POST /api/v1/account/email/confirm { code }` (authenticated). Checks the code (hash,
  not expired, attempts remaining), on success sets `emailVerified := true` and clears the code.
  `204`. Only a **confirmed** email counts as an attached recovery email (§4 sweep, §7 erasure).
- **Detach:** `DELETE /api/v1/account/email` (authenticated). Clears the email + `emailVerified` on
  the Keycloak account via Admin API. `204`.

Email is **personal data collected only on explicit action, for the single purpose of recovery**
(CLAUDE.md §5, purpose limitation) — never requested silently, never at provisioning.

---

## 3. Email delivery & the recover-by-email flow

**SGART owns the code and sends it over its own SMTP** (Timo, 2026-09-15). Keycloak's built-in
verify-email / reset-credentials emails deliver **links to Keycloak-hosted browser pages**, which the
AC forbids ("no Keycloak-hosted page and no browser"). So delivery is a SGART concern.

### 3.1 `adapter.out` — the SMTP notifier

- New domain-owned out-port in `identity.application` — `SendRecoveryCodeEmail.send(email, code)` (a
  narrow, intention-revealing port, not a generic mail gateway — YAGNI).
- Real adapter `JavaMailSenderRecoveryCodeEmail` backed by Spring `JavaMailSender`; all
  `jakarta.mail`/Spring-mail types contained in the adapter (AD-1/AD-2).
- **New dependency:** `spring-boot-starter-mail` (Spring Boot 4.1's supported version, CLAUDE.md §7).
- **Config-gated exactly like the Keycloak-admin adapter:** active on
  `sgart.identity.mail.enabled=true`; a **Deferred no-op default** (`DeferredSendRecoveryCodeEmail`,
  logs at debug, sends nothing) wired for the `false`/absent case via `@ConditionalOnProperty`
  (`matchIfMissing=true`), so CI, local, and Testcontainers builds need **no SMTP server**. Building
  the sender performs no I/O (contextLoads stays green).
- **Real SMTP is a documented deploy seam**, not a build blocker: netcup SMTP credentials + **SPF/DKIM**
  recorded in `docs/first-real-world-test.md` alongside the 7.1 reverse-proxy rate-limit seam
  (ADR-0002). The zero-input core (7.1/7.2) sends no email; this is the one feature that needs it.
- **Content:** plain-language German, the code, the TTL, and a "you requested this on SGART — ignore
  if not" line. No household data, no name — just the code to the recipient (minimal-content).

### 3.2 Recover-by-email (authenticated as the throwaway, §5)

- **Request a code:** `POST /api/v1/account/recovery/email { email }`. Server looks up the account by
  email via Admin API (`GET users?email=&exact=true`), and — **only if found** — stores a code hash
  keyed by that account's `kc1` and emails it. Responds **`202` regardless of whether the email is
  registered** (no account/email enumeration).
- **Confirm & rebind:** `POST /api/v1/account/recovery/email/confirm { email, code }`. Verifies the
  code, then performs the **R1 rebind** of §1.1 (delete throwaway `A2` from the caller's JWT; set
  `A1.username := U2`, `A1.publicKey := K2`). `204`. The app re-signs-in with its existing credential
  and reveals `P2`.

---

## 4. One-time code store (new `identity` table, V19)

A small, transient, PII-free table — **no email column** (AD-6: the email lives only on the Keycloak
account; the code row is keyed by the pseudonymous `KeycloakUserId`).

```
email_recovery_code {
  keycloak_user_id  text     -- the subject account (pseudonym; from lookup or JWT)
  purpose           text     -- ATTACH_CONFIRM | RECOVER  (one active code per (id, purpose))
  code_hash         text     -- HMAC-SHA256(server secret, code) — never the plaintext code
  expires_at        timestamptz
  attempts          int      -- incremented per wrong guess; exhausted → invalidate
  created_at        timestamptz
}
```

- **Port + adapter:** `EmailRecoveryCodeStore` (domain port) + `JdbcEmailRecoveryCodeStore` + an
  in-memory test double — mirrors `ProvisionedAccountRepository`'s shape exactly.
- **Hash, don't store plaintext:** `HMAC-SHA256` with a stable per-deployment secret — reuses the
  `InviteEmailHasher` pattern, so a leaked table can't be brute-forced back to a live code without the
  server secret (a bare SHA-256 of a 6-digit code is offline-trivial).
- **Fail-fast checks (application service):** wrong/expired/exhausted → a typed application exception
  mapped to 4xx by `AccountErrorAdvice`; a verified code is single-use (deleted on success).
- **Proposed defaults (§8 fork C):** 6-digit numeric code · TTL 15 min · max 5 attempts. Numeric is
  the friendlier native-entry UX; the short TTL + attempt cap carry the security weight.
- **`NoPersistedPersonalDataTest`:** add this table to the whitelist as a pseudonymous-only store
  (like `member_mapping`/`provisioned_account`); assert it holds no email/name column.

---

## 5. Security surface — no new *unauthenticated* endpoint

7.1 deliberately left `POST /api/v1/accounts` as the app's **single** unauthenticated write surface.
7.3 keeps it that way: because Story 7.1 **always silently provisions and signs in first**, the app
holds a (throwaway) JWT by the time the user reaches recover-by-email. So the recovery endpoints are
**authenticated as the throwaway** — the JWT identifies `A2`/`kc2` for the rebind's delete step (§1.1),
and no new permit rule is added to `SecurityConfig`. The attach/confirm/detach endpoints are naturally
authenticated (the real account). This is a real hardening win over adding open endpoints; the
coupling to "7.1 provisions first" is a documented invariant, not a fragile assumption.

- **Enumeration:** request-a-code responds `202` whether or not the email is registered; failure
  reasons are server-side-only (mirrors the authenticator's no-user-enumeration discipline).
- **Abuse / bombing:** IP-based rate limiting at the reverse proxy (the 7.1 seam) + the per-code TTL
  and attempt cap. Device attestation stays the named pre-public-release fast-follow.

---

## 6. App-side contract (informs create-story; app detail lands in the story)

- **Profil (Story 1.11):** a "Wiederherstellung per E-Mail" section — attach (enter email → enter
  code), showing not-attached / pending-confirmation / confirmed state, and detach. New
  `AddRecoveryEmailPage` + `ConfirmEmailCodePage`; reuses the `AuthenticatedHttpClient`, `SgartButton`,
  and `app_de.arb` pipeline. The identity header already renders `authState.email` live (AD-6).
- **Recover-by-email entry:** a quiet action on `CreateOrAwaitChoicePage`, peer to 7.2's "Konto
  wiederherstellen" (phrase) — "Per E-Mail wiederherstellen" → a `RecoverByEmailPage` (enter email →
  enter code). The choice screen is the guaranteed 0-household gateway (7.2 D-D).
- **The swap + fresh phrase:** on `204`, the flow re-runs the existing `signIn()` with the device's
  current credential (now bound to `A1`), clears the active household (`ActiveHouseholdStore.clear()`,
  AD-7, the same seam 7.2 uses), and **immediately shows the fresh phrase** by reusing 7.2's
  `RecoveryPhraseRevealPage` — with distinct copy: *"Deine vorherige Wiederherstellungsphrase gilt
  nicht mehr. Sichere diese neue Phrase."* Forgiving code input (trim/whitespace); fail-fast inline
  errors for invalid/expired code, mirroring `recover_account_page.dart`.
- Reuses 7.2's `AuthGate → FirstRunRouter` re-mount for the identity swap; no bespoke routing.

---

## 7. GDPR — email as personal data (CLAUDE.md §5, AD-6/AD-7)

- **Where it lives:** the email is stored **only on the Keycloak account** (like display name — the
  established AD-6 exception). SGART persists **no** email in events, read models, or the code table.
- **Retention sweep change (`SweepNeverActivatedAccounts`):** "never-activated" expands — a shell is
  activated by a `MemberMapping` **or a confirmed email**. The sweep additionally checks the account's
  Keycloak email/`emailVerified` (derived via an Admin-API get-user, not a stored SGART bool — keeps
  the 7.1 "activation is derived, never stored" principle, DRY). A confirmed email keeps the account
  alive; an abandoned *unconfirmed* attach still gets swept after the TTL (and its code expires).
- **Right to erasure (AD-7):** erasure already deletes the Keycloak account, which takes the email with
  it — no new erasure step for the email itself. 7.3 adds the **detach** operation (revocable consent
  / purpose limitation) and ensures a swept/erased account's `email_recovery_code` rows go too. Full
  export/erasure *feature* tests remain Epic 6; 7.3 adds the targeted privacy tests below.
- **Export (Epic 6 hook):** the account's email is part of the person's exportable data; 7.3 records
  that requirement for Epic 6, no export UI here.

---

## 8. Decisions (proposed — Timo to confirm; §-refs point to the detail)

**Already agreed with Timo (2026-09-15):** key replacement over multi-key (§1); fresh phrase shown on
recovery, old phrase dies, AC wording relaxed (§1); SGART-owned code + SGART SMTP, config-gated with a
Deferred default and a deploy seam (§3); email on the Keycloak account only (§7); confirmed email
counts as activation (§7); one story, architect-note-first.

**Forks RESOLVED (Timo, 2026-09-15 — went with all four recommendations):**

| # | Fork | Resolution |
|---|------|------------|
| **A** | Rebind strategy | **R1** — rebind the real account's username+key to the new device, delete the throwaway (§1.1). Preserves the account's stable pseudonymous `KeycloakUserId` across recoveries; no ACL re-keying; authenticator untouched. (R2 — adopt memberships into the throwaway — rejected: churns the pseudonymous id every recovery.) |
| **B** | Recovery endpoint auth | **Authenticated as the throwaway** — no new open endpoint; the JWT gives `kc2` for the rebind's delete step. Relies on the documented "7.1 always provisions first" invariant. (§5) |
| **C** | Code format · TTL · attempts | **6-digit numeric · 15 min · 5 attempts · HMAC-SHA256 stored** (§4). Numeric = friendliest native entry; short TTL + attempt cap + HMAC carry the security. |
| **D** | Sweep email check | **Derive** via Admin-API get-user — no second source of truth (DRY, matches 7.1's "activation is derived, never stored"). (§7) |

All forks closed. The note is **ready to feed create-story 7.3**.

---

## 9. Test coverage (CLAUDE.md §6 — security-sensitive, per the Epic-7 critical-path action item)

- **Application (fast, no infra):** attach sets email + issues a code; confirm with the right code
  verifies and is single-use; wrong/expired/exhausted code rejected fast (4xx, never 500); detach
  clears the email. Recover-by-email: valid code triggers the R1 rebind ports in the right order
  (delete throwaway → set username+publicKey); unknown email still responds `202` (no enumeration).
- **Code store:** `EmailRecoveryCodeStore` round-trip; expiry and attempt-exhaustion behavior;
  HMAC hashing (no plaintext persisted); isolation between two subjects.
- **Retention:** an account with a **confirmed email** and no household is **not** swept; an
  unconfirmed-attach-then-abandoned shell **is** swept past TTL; a swept account's code rows are gone.
- **Privacy (AD-6):** `NoPersistedPersonalDataTest` covers the new table (no email/name column); a
  test asserts no SGART store (events/read models/code table) persists the raw email; the email is
  reachable and deletable via detach/erasure.
- **Adapter:** `JavaMailSenderRecoveryCodeEmail` sends to the right recipient with the code (Spring
  mail test harness / GreenMail-style, or a fake at the port); the Deferred no-op sends nothing.
- **App:** attach/confirm/detach cubit + widget flows; recover-by-email drives email→code→rebind→
  identity swap→fresh-phrase reveal (with fakes, no network); a test asserts the recovery path sends
  no secret it shouldn't and that the fresh phrase is shown after a successful rebind.
- **A green build names both suites:** backend `./gradlew test` (incl. ArchUnit + Testcontainers) **and**
  app `flutter test` / `flutter analyze`.

---

## 10. Footprint summary (for create-story)

- **Backend (`identity`):** `attach/confirm/detach/recover` application services + typed exceptions;
  new ports `SendRecoveryCodeEmail`, `EmailRecoveryCodeStore`, and an Admin-side `SetAccountEmail` /
  `RebindAccountCredential` capability (extend the existing Keycloak Admin adapter — it already holds
  the client-credentials token fetch and `manage-users`); `JavaMailSenderRecoveryCodeEmail` +
  `Deferred…` no-op; `JdbcEmailRecoveryCodeStore` (+ in-memory double); `V19__email_recovery_code.sql`;
  `SweepNeverActivatedAccounts` gains the confirmed-email check; `AccountController` +
  `AccountErrorAdvice` gain the new endpoints; `IdentityBeansConfig` wires the new beans (config-gated);
  new `spring-boot-starter-mail` + `sgart.identity.mail.*` config keys.
- **No change:** the `keycloak-authenticator` SPI, the event log, `MemberMapping`'s schema,
  `SecurityConfig`'s permit rules.
- **App (Flutter):** `AddRecoveryEmailPage`, `ConfirmEmailCodePage`, `RecoverByEmailPage`; a Profil
  "E-Mail-Wiederherstellung" section; an account-email API client; an attach/recover cubit (or extend
  `AuthCubit`); reuse of `RecoveryPhraseRevealPage` for the post-recovery fresh-phrase reveal; new
  `app_de.arb` strings + a11y labels.
- **Realm/deploy:** no new Keycloak client or flow (Admin `manage-users` already granted); real SMTP
  + SPF/DKIM documented as a deploy follow-up.

---

*If create-story finds the composed spec exceeds the ~1600-token scope target, the natural seam is
attach/confirm/detach + infra (7.3a) vs. recover-by-email rebind (7.3b); Timo chose one story, so the
token gate resurfaces that choice only if it fires.*
