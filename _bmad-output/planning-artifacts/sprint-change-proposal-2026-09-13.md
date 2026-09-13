---
title: Sprint Change Proposal — Native Frictionless Account Provisioning Replaces Keycloak-Hosted Registration
date: 2026-09-13
status: approved (rev E, 2026-09-13)
supersedes: sprint-change-proposal-2026-09-10.md (Sections 4 and its same-day Addendum — mechanism + beta-timing only; the underlying requirement, FR-29/FR15/Epic 7, still stands)
revision-history:
  - 2026-09-13 (rev A): first draft — silent provisioning + device-side Direct Access Grant (ROPC).
  - 2026-09-13 (rev B): mechanism corrected. Device-side ROPC rejected as a deprecated,
    less-secure OAuth flow (RFC 9700 "MUST NOT"; removed in OAuth 2.1; discouraged by Keycloak).
    Replaced with browserless token issuance and a device-generated secret held in the platform secure
    enclave. Goal (zero-input, no-browser, beta-required) unchanged; several previously-glossed gaps
    (credential model, orphaned-account retention, consent lawful basis, the adapter.in slice,
    reset-flow-vs-NFR4, test coverage) corrected.
  - 2026-09-13 (rev C): Story 7.0 spike run against Keycloak 26.7 docs. Impersonation token
    exchange (candidate (a)) rejected — subject impersonation is legacy `token-exchange:v1`, which is
    Preview *and* Deprecated (Standard Token Exchange v2 is GA but cannot change subject). Chosen:
    candidate (b), a custom Direct-Grant authenticator SPI (device-signed challenge). Login is
    app→Keycloak (not backend-mediated), so `sgart-app` keeps `directAccessGrantsEnabled` and token
    refresh stays app→Keycloak; `sgart-admin` needs only `manage-users`. Mechanism passages updated
    throughout to match.
  - 2026-09-13 (rev D): the email/password recovery path is promoted from "optional/later" to a
    committed beta capability (Story 7.3).
  - 2026-09-13 (rev E, this doc): recovery-by-email reshaped per Timo — it is an **opt-in, additional,
    email-only** recovery path (no password anywhere; the password-login and password-reset material is
    dropped). A person may attach an email at any time; recovery is an emailed one-time code entered
    natively (browserless) that re-binds a device key. Consequence: **email-sending infrastructure is now
    a beta requirement**, isolated to this feature; the recovery phrase remains the primary path.
---

# Sprint Change Proposal — Native Frictionless Account Provisioning

## 1. Issue Summary

**Trigger:** Not a story defect — surfaced 2026-09-13 while walking through the actual first-run
experience end to end with Timo, three days after the 2026-09-10 proposal approved Epic 7's mechanism
(Keycloak-hosted magic-link authentication reached through the AppAuth in-app-browser surface, a
forced "Update Password" required action, and Keycloak's hosted self-service password reset).

**Core problems raised:**

1. **No browser surface, anywhere, for auth.** Timo rejected the AppAuth surface (Android Custom Tab /
   iOS `ASWebAuthenticationSession`) outright for all three of login, registration, and forgot-password
   — not a style preference, a hard requirement. That surface is not a full app-switch, but it shows a
   browser panel with a URL bar (and, on iOS, an Apple "wants to use \<domain\> to sign in" prompt) and
   renders Keycloak's HTML rather than the app's own UI. The approved Epic 7 mechanism relies on exactly
   that surface for all three flows.
2. **Zero required input on first launch.** Even a magic link still requires typing an email before the
   app can be used at all. Timo wants the app usable with *no* information provided up front —
   email/login name should be optional, offered only for cross-device recovery.
3. **Timing.** The 2026-09-10 proposal explicitly scoped Epic 7 as "does not block beta." Timo now
   considers self-registration a **beta requirement**: "an important part of the app that has to be
   implemented from the beginning."

**Why the two requirements force a non-hosted mechanism (rev B analysis):** "Zero input" and
"Keycloak-hosted browser pages" are mutually exclusive. The hosted pages are interactive by definition
(the person types at least an email and taps through), and the AppAuth browser panel is a sandboxed
surface the app cannot pre-fill or auto-submit — so a silently-created account cannot be logged in
through it. Silent, zero-input onboarding therefore *requires* (a) creating the account via the Keycloak
Admin API and (b) obtaining the JWT without any hosted page. Rev A did (b) with a device-side **Direct
Access Grant (ROPC)** — rejected. Rev C does (b) with a **custom Direct-Grant authenticator SPI**: the
app calls Keycloak's token endpoint directly, but a Keycloak-side authenticator verifies a device-signed
challenge instead of a password (Story 7.0 spike outcome, see §4).

**Category:** Refinement of a very recent (3-day-old) correct-course decision. The underlying
requirement (FR15/FR-29 — a person can become an authenticated user without an existing member's
involvement) is unchanged and still valid; its **mechanism and its scheduling** were wrong.

**Evidence:**
- This conversation (2026-09-13), working through the first-run flow screen by screen with Timo.
- `keycloak/realm-sgart.json`: the `sgart-admin` confidential client already exists
  (`serviceAccountsEnabled: true`, `clientRoles: {"realm-management": ["view-users"]}`) — a
  backend-mediated Keycloak Admin API integration is already an established pattern, not new territory.
- `KeycloakAdminFindHouseholdMemberByEmail.java` (Story 4.6, `identity` bounded context) is a working
  precedent for exactly this kind of adapter (client-credentials token fetch, `RestClient` against the
  Admin REST API, all Keycloak/HTTP types kept inside the adapter per AD-1/AD-2).
- `AcceptInviteHandler.java` javadoc: "accept needs no already-a-member seam and no email — the
  joiner's identity comes entirely from their own JWT" — invite acceptance is *already*, in practice, a
  bearer-credential design. It never checked the invitee's email against anything.
- OAuth 2.0 Security BCP (RFC 9700) and the OAuth 2.1 draft: the Resource Owner Password Credentials
  grant (ROPC / Keycloak "Direct Access Grants") MUST NOT be used; Keycloak's own docs discourage it.
  This is why rev A's device-side Direct Access Grant is rejected in rev B.

## 2. Impact Analysis

Wider blast radius than the 2026-09-10 proposal: that one was purely additive (new Epic 7, nothing
shipped touched). This one revises the **mechanism every person authenticates with**, so it also
touches already-shipped Epic 1 (Story 1.4) and Epic 4 (Story 4.1), not only the not-yet-built Epic 7.

**Epic 1 — Story 1.4 (Sign in with Keycloak, shipped):** the transport changes from Authorization
Code + PKCE via AppAuth (browser/custom-tab) to a **browserless custom Direct-Grant login** (Story 7.0
outcome (b)). *Account creation* is backend-mediated (the `sgart-admin` service account calls the Admin
API); *login* is app-direct — the app calls Keycloak's token endpoint via a custom Direct Grant flow
whose authenticator SPI verifies a device-signed challenge (no browser, no password/ROPC). The story's
existing acceptance criteria ("the client obtains a JWT... the backend validates it... takes the id from
`sub`") stay true — only *how* the JWT is obtained changes. `app_auth_oidc_client.dart` and the
`flutter_appauth` dependency are removed and replaced by a plain token-endpoint client. Because login
stays app→Keycloak, token *refresh* also stays app→Keycloak: `AuthenticatedHttpClient`'s 401
refresh-and-retry seam (commit `73a3b93`) is largely **retained**, only its initial-sign-in call is
swapped (this corrects rev B's pre-spike assumption that refresh would route through the backend).

**Epic 1 — Story 1.11 (Personal profile screen, shipped):** gains a "Recovery phrase" row, read back
from secure local device storage (not re-fetched from the server — see §4, Architecture, on why).

**Epic 4 — Story 4.1 (Invite a person by email, shipped):** invite creation drops the email/HMAC path
entirely for beta (§5, resolved) and becomes invite-by-**join code**, with a link as a second
representation of the same `InviteId` (same aggregate, same 7-day TTL, same single-use "already
consumed" semantics — no new domain concept). `EmailHmac` and `InviteEmailSideStore` are retired along
with it — no email is collected or hashed anywhere in the invite path.

**Epic 4 — Story 4.2 (Accept an invite and join, shipped):** gains a "type a code" entry path
alongside the existing link/deep-link path. No handler changes — `AcceptInviteHandler` already accepts
purely on `(householdId, inviteId)` plus the caller's own JWT.

**Epic 7 (not yet built):** mechanism fully replaced (see §4). Scheduling changes from "gate before
public app-store release, not required for beta" to **required for beta** — recommend resequencing it
to run immediately after Epic 1 (every later epic already assumes an authenticated person exists), not
after Epic 6.

**New, small addition (fits under Epic 1 or 4's UI work):** the invite screen gets a "Share" action
using the OS-native share sheet (`share_plus` — not currently a dependency; pin the current supported
major per CLAUDE.md §7), not per-service buttons — matches KISS/YAGNI and stays correct as installed
apps change without SGART maintaining integrations.

**PRD impact (`prd.md`):** FR-29 is rewritten — mechanism (silent native provisioning + browserless
app-direct login + a locally-held recovery phrase, no Keycloak-hosted pages, no ROPC) and scheduling
(required for beta, not gated to public release). §2.2/§5/§6.2's "manual Admin Console account creation
continues through beta" framing is no longer accurate and needs rewording.

**Architecture impact (`ARCHITECTURE-SPINE.md`):** larger than the 2026-09-10 proposal's "None" —
concretely:
- The `sgart-app` Keycloak client **needs `directAccessGrantsEnabled: true`** and a bound custom Direct
  Grant flow (Story 7.0 outcome (b)) — the app obtains and refreshes tokens directly against Keycloak's
  token endpoint via that flow; its JWTs remain Keycloak-issued and validated at `adapter.in` exactly as
  today (AD unchanged). This is the Direct Grant *flow container*, not password ROPC — the authenticator
  verifies a device signature.
- `sgart-admin`'s service account needs `manage-users` added to its `realm-management` client roles
  (currently `view-users` only) for account creation — and, per the Story 7.0 outcome, **no**
  impersonation/token-exchange permission (candidate (a) is rejected). Since it now **creates real
  accounts**, its checked-in dev placeholder secret (`realm-sgart.json:90`) must be replaced with real
  secret management before beta ships to actual testers (already flagged as a documented follow-up in
  the realm file; this is what makes it urgent rather than someday).
- **Full hexagonal slice in `identity` (rev A named only the out-adapters):**
  - `adapter.in` — a new **unauthenticated** REST endpoint (the only unauthenticated write endpoint in
    the app; must be added to `identity/adapter/in/security/SecurityConfig.java`'s permit list) that
    accepts a provisioning request from a fresh device.
  - `application.command` — a `ProvisionAccount` command + handler (CQRS: provisioning is a
    state-changing command; it emits no domain event because identity is delegated to Keycloak, so
    AD-5/AD-6/AD-7 stay untouched — but the application slice is real and must exist).
  - `adapter.out` — `KeycloakAdminCreateAccount` (Admin API `POST /users` + register the device's
    public key so the custom authenticator can verify its signatures), following the
    `KeycloakAdminFindHouseholdMemberByEmail` pattern (Story 4.6). No backend token-mint adapter — login
    is app-direct (Story 7.0 outcome (b)). Separately, the Keycloak **custom authenticator SPI** (Java,
    deployed into Keycloak and bound to a Direct Grant flow) is a new build/deploy artifact living
    outside the app and backend modules.
- **AD-5/AD-6/AD-7 remain untouched** — still no new domain event or aggregate; provisioning still
  happens entirely before any SGART *domain* command is issued.
- **New NFR (abuse surface):** the create-account endpoint is unauthenticated by nature (anyone can call
  it to farm Keycloak accounts) → needs **IP-based rate limiting** for beta (note its known limits:
  carrier-grade NAT causes shared-IP false lockouts, and attackers rotate IPs — so it is a speed bump,
  not a wall; state where it lives, e.g. the TLS reverse proxy from ADR-0002). Device attestation
  (Play Integrity / DeviceCheck) is a named fast-follow before public release (§5, resolved).
- **New NFR (retention / GDPR storage limitation, CLAUDE.md §5) — rev B addition:** every fresh install
  that never creates/joins a household and never adds a recovery backup leaves an orphaned Keycloak
  account. This is *organic* sprawl (reinstalls, tyre-kickers), not covered by rate limiting. A
  never-activated silently-provisioned account gets a retention TTL and a server-side sweep. This is a
  hard requirement, not a nicety — indefinite retention of pseudonymous account rows violates §5.
- The `collaboration` context's invite email side-store (`InviteEmailSideStore`, `EmailHmac`) is
  retired for beta scope (§5, resolved) — no email is collected or hashed in the invite path.
- **New infra dependency (email sending) — rev E:** the opt-in "recover by email" path (Story 7.3)
  requires **transactional-email/SMTP delivery** (with SPF/DKIM) added to the ADR-0002 netcup stack. It
  is isolated to that one feature — the zero-input core (Stories 7.1/7.2) never sends email. Sending a
  one-time code and verifying it in-app uses no Keycloak-hosted page, so it stays within the no-browser
  constraint and NFR4 (Keycloak remains the identity store; SGART only triggers the mail and re-binds
  the device key via the Admin API). The attached email is personal data (§5): explicit action, single
  purpose (recovery), covered by erasure/export, and detachable by the person.

**UX impact:**
- First launch: **zero screens** for account creation — the existing create/await-invite choice
  (Story 1.6) is still the very first thing a person sees.
- Login/second-device sign-in is **pure Flutter** — no browser panel, no URL bar, no iOS system prompt.
- Recovery phrase: shown once at the point it first becomes meaningful (recommend: right after first
  creating or joining a household, not at the silent-provisioning instant itself — see §5), plus a
  permanent, re-viewable row in the profile screen (Story 1.11), read from secure local storage only.
- Invite/manage-household screen: shows the join code (large, tap-to-copy) plus a "Share" button
  opening the OS share sheet with the code and link pre-filled as text.
- Second-device / reinstall sign-in: a single native field asking for the recovery phrase — no
  separate "username" is ever surfaced to the person; the phrase alone reconstructs the device secret
  and authenticates (see §4, Credential model).
- Needs a new UX-DR note for the recovery-phrase reveal/re-view pattern when this is actually designed.

## 3. Recommended Approach

**Option 1 — Direct Adjustment, selected.** This corrects a 3-day-old decision before any Epic 7 story
has been built (nothing to roll back) and before beta ships (no live users affected by the mechanism
change).

- **Effort: Medium.** A new backend provisioning slice (unauthenticated endpoint + command/handler +
  a create-account Admin adapter) following an existing pattern, a Keycloak custom authenticator SPI, a
  sign-in transport swap that *replaces* (not reuses) the AppAuth client with a custom Direct-Grant
  token-endpoint client, a small join-code addition to Epic 4, and a `share_plus` integration. The one
  open variable — the token mechanism — is now resolved by the Story 7.0 spike (candidate (b)).
- **Risk: Low–Medium.** Two new risks: (1) the unauthenticated create-account endpoint as an abuse
  surface — mitigated by the rate-limiting + retention NFRs (§2); (2) the token mechanism — **resolved
  by the Story 7.0 spike:** impersonation token exchange (candidate (a)) is rejected (legacy
  `token-exchange:v1`, Preview + Deprecated in 26.7; Standard Token Exchange v2 is GA but cannot change
  subject), and a custom Direct-Grant authenticator SPI (candidate (b)) is chosen — no preview feature,
  but it adds a small Java SPI as a maintained Keycloak artifact. Neither risk is architecturally novel
  for this codebase.
- **Timeline: blocks beta** (reversal of the 2026-09-10 "does not block beta" call) — recommend
  resequencing Epic 7 to run right after Epic 1, before Epic 5 resumes.

## 4. Detailed Change Proposals

### Credential model (rev B — the piece rev A left undefined)

Direct Access Grant needs a username + password; rev A said the username is never shown yet the phrase
alone signs you in on a fresh device — a contradiction, since a fresh device's secure storage is empty.
Rev B pins it down:

- On first launch the **app generates a high-entropy secret** (or a keypair) and stores it in the
  platform secure enclave — Android Keystore / iOS Keychain, reached via the existing
  `flutter_secure_storage`. This is device-bound and actually secret (unlike a device hardware
  identifier, which is neither stable across reinstall nor confidential, and is therefore *not* used).
- The backend creates the Keycloak account and binds it to that secret (as the credential, or as a
  registered public key for candidate (b)). The Keycloak username is a random, never-shown identifier
  derived from the secret.
- The **recovery phrase is a human-readable encoding of that secret/seed** (BIP39-style). It is the
  *only* thing that survives reinstall or moves to another device. "Read from secure local storage,
  never re-fetched from the server" describes re-viewing it on the *same* device; on a *new* device the
  phrase *is* the input the person types, from which the app reconstructs the same secret and
  authenticates. No server round-trip reveals it.
- **Consequence, stated plainly wherever the phrase is shown:** losing the phrase before it is written
  down (or before a recovery email is attached) means the account and its household data
  are unrecoverable.

### PRD (`prd.md`)

**FR-29** — OLD (current text, from the 2026-09-10 proposal):
> Before SGART is distributed via public app stores, a person with no existing account can create one
> from the app... Registration requires email verification... A registered person can self-serve a
> password reset without contacting the operator.

NEW:
> #### FR-29: Frictionless account provisioning
> On first launch, a person is provisioned a real, usable account with **zero required input** — no
> email, no username, no password screen. The app generates a device-side secret held in the platform
> secure enclave, the backend silently creates a Keycloak account bound to it (via the Admin API), and
> the person is signed in via a **browserless native login** — the app proves possession of the device
> secret to Keycloak directly, with no browser panel and no Keycloak-hosted page ever shown for login,
> registration, or password reset, and without the deprecated password grant (ROPC). The first time it becomes meaningful (first household created
> or joined), the person is shown a one-time **recovery phrase** — a human-readable encoding of the
> device secret — that is re-viewable later from their profile (from the device's secure local storage,
> never re-fetched from the server). Entering that phrase on another device (or after reinstalling)
> reconstructs the secret and signs them back into the same account. Optionally attaching an **email
> address** enables a friendlier, opt-in **"recover by email"** fallback (Story 7.3): the person may
> attach an email at any time (day one or long after), and if they lose the phrase or switch devices, a
> one-time code emailed to that address — entered natively, no browser — re-establishes access. It is
> **email-only (no password)**, additive to the phrase, never a replacement; using it stays the person's
> choice and first launch remains zero-input. **Required for beta — not a public-release-only gate.**
> **Consequences (testable):**
> - No screen is shown before the existing create/await-invite choice (Story 1.6) on first launch.
> - Login and second-device sign-in use the app's own native UI — no browser surface is ever presented.
> - The recovery phrase is shown exactly once at the point defined in the UX pass (§5), and is
>   available again, unlimited times, from the profile screen — sourced from local secure storage only.
> - Losing the phrase before it is written down or a recovery email is attached means the
>   account (and its household data) is not recoverable — this must be stated plainly wherever the
>   phrase is shown.
> - A person entering an invite join-code or link (FR2/Epic 4) already has an account by the time they
>   see it — invite acceptance never needs to provision an identity itself.
> **Feature-specific NFRs:**
> - Delegates entirely to Keycloak as the identity store (NFR4); the new SGART-owned code is the
>   backend's Admin API calls, the app's Direct-Grant token client, and a Keycloak authenticator SPI —
>   none holding a long-term secret beyond what Keycloak itself stores.
> - The account-creation endpoint is unauthenticated by nature and is rate-limited (new NFR).
> - Never-activated silently-provisioned accounts are retention-swept (new NFR, GDPR storage limitation).

**§2.2 Non-Users / §5 Non-Goals / §6.2 Out of Scope** — the 2026-09-10 wording ("MVP and the beta
onboard a curated friends-&-family cohort... with accounts created manually... self-registration is
scheduled before any public app-store release") is now inaccurate on two counts: accounts are
self-service (silently) from the first beta build, not manually created, and self-registration is a
beta requirement, not a later gate. Reword all three sections to: *"Every person, from the first beta
build onward, gets a working account with no manual provisioning and no required personal data.
Growth mechanics (referrals, discovery, additional languages, etc.) remain genuinely Post-MVP; account
provisioning itself is not one of them."*

### Epics (`epics.md`)

**NFR8** — reworded: *"Solo is first-class from day one; the curated-cohort framing no longer describes
account creation. Every person, beta included, gets a working account via frictionless provisioning
(FR15/Epic 7) with no manual step and no required personal data. A household of one is fully supported
alongside multi-person households."*

**FR15 (CAP-15)** — rewritten to match FR-29's new text above; drop every reference to magic links,
email verification, browser/AppAuth surfaces, Direct Access Grant, and Keycloak's "Terms and
Conditions"/"Update Password" required actions.

**Story 1.4** — amend AC1's first clause: *"the client obtains a JWT via a browserless custom
Direct-Grant login — the app proves possession of its device secret to Keycloak's token endpoint, where
a custom authenticator verifies a device-signed challenge; no browser or custom tab is opened, and no
password (ROPC) is used"* (replaces the implicit AppAuth/browser assumption). No other AC changes.

**Story 4.1** — retitle to **"Invite a person by code or link"**; AC1 changes from *"any member invites
a person by email"* to *"any member generates an invite, shown as a join code and a link; either can be
shared via the OS share sheet."* The email/HMAC path (raw email, `EmailHmac`, `InviteEmailSideStore`,
the "already a member"/"duplicate pending invite by email" checks) is **dropped for beta** (§5,
resolved) — an invite carries no email at all; a duplicate-invite check, if still wanted, keys off
household membership state (a person already a member of the target household cannot hold a second
pending invite to it), not email. This closes rev A's undefined AC.

**Epic 7** — retitle to **"Frictionless Account Provisioning"**, resequence to immediately after Epic
1, mark **required for beta**. Replace Stories 7.1–7.4 with:

> #### Story 7.0 (spike): Choose the browserless token mechanism — RESOLVED 2026-09-13
> Spike run against the Keycloak 26.7 docs. **Outcome: candidate (b), a custom Direct-Grant
> authenticator SPI, is chosen.** Candidate (a), impersonation token exchange, is **rejected**: subject
> impersonation (`requested_subject`) is not part of Standard Token Exchange v2 (`token-exchange-standard:v2`,
> GA/default) and is available only through **legacy token exchange (`token-exchange:v1`), which is
> Preview *and* Deprecated** — building beta-critical auth on a preview+deprecated feature violates
> CLAUDE.md §7 and risks removal. Candidate (c), a server-side Direct Access Grant, was considered and
> **rejected** (it still uses the deprecated ROPC grant, only off-device). **(b) is locked (2026-09-13)
> — no fallback held open.**
> **Chosen shape (b):** the app calls Keycloak's token endpoint via the Direct Grant flow, but a custom
> `Authenticator` SPI validates a **device-signed challenge** (a signing key derived from the enclave
> seed) instead of a password — no browser, no reusable password on the wire. Config: `sgart-app` gets
> `directAccessGrantsEnabled: true` and a custom Direct Grant flow bound; `sgart-admin` needs only
> `manage-users` (account creation) — **no** impersonation/token-exchange permission. The added cost is
> a small Java authenticator SPI deployed into Keycloak.
> **Honest nuance:** this reuses Keycloak's Direct Grant *flow container*, but replaces ROPC's password
> mechanism with a signature (add a server nonce for replay protection), mitigating RFC 9700's
> credential-handling concerns.
> **Acceptance (met by the spike's design; to be proven by test at build):** a backend/Keycloak
> integration test signs in an Admin-API-created account via the custom authenticator and receives a
> valid, `adapter.in`-accepted JWT with no browser and no password.

> #### Story 7.1: Silent account provisioning on first launch
> As a person opening SGART for the first time,
> I want a working account with no setup,
> So that I can start using the app immediately.
>
> **Acceptance Criteria:**
> **Given** a fresh install with no stored session **When** the app starts **Then** it generates a
> device-side secret in the secure enclave, calls the backend to provision a Keycloak account (Admin
> API, no email/username shown or requested), and is signed in via the Story 7.0 mechanism — before the
> create/await-invite choice (Story 1.6) is shown, with no intervening screen and no browser surface.
> **Given** that provisioning endpoint **When** it is called **Then** it is IP-based rate-limited
> server-side for beta (new NFR), since it is reachable by anyone unauthenticated; device attestation
> (Play Integrity / DeviceCheck) is a named fast-follow before public release, not required for beta
> (§5, resolved).
> **Given** a silently-provisioned account that never creates/joins a household and adds no recovery
> backup **When** its retention TTL elapses **Then** a server-side sweep deletes it (new NFR, GDPR
> storage limitation).
>
> #### Story 7.2: Recovery phrase reveal and profile re-view
> As a person with a silently provisioned account,
> I want a way to recognize and recover my account,
> So that I can use SGART on another device or after reinstalling, and understand what I'd lose if I don't.
>
> **Acceptance Criteria:**
> **Given** a person's first household is created or joined **When** that completes **Then** a one-time
> screen shows their recovery phrase with an explicit warning that losing it (without an added email
> backup) means unrecoverable loss of the account and its household data. This is the confirmed reveal
> trigger (§5, resolved) — the true first-launch screen (Story 1.6's create/await-invite choice) stays
> untouched. (A person who reinstalls *before* ever creating/joining a household has seen no phrase and
> has nothing to lose — an accepted, harmless window.)
> **Given** the profile screen (Story 1.11) **When** a person opens it **Then** their recovery phrase is
> shown again on demand, read from the device's secure local storage — never re-fetched from the server.
> **Given** a phrase entered on a different device or after reinstall **When** submitted on the sign-in
> screen **Then** the app reconstructs the device secret from it and signs the person into their
> existing account via the Story 7.0 mechanism — pure native UI, no browser.
>
> #### Story 7.3: Recover by email (opt-in)
> As a person who would rather not rely solely on remembering my recovery phrase,
> I want to optionally attach an email address to my account and use it to get back in,
> So that I have a friendlier fallback if I lose the phrase or switch devices.
>
> **In scope for beta — committed (rev E).** It is a genuinely opt-in, *additional* recovery path
> alongside the recovery phrase (never a replacement): the person may attach an email **at any time**
> (day one, or long after), and first launch stays zero-input. **Email-only — no password anywhere.**
>
> **Acceptance Criteria:**
> **Given** the profile screen **When** a person chooses to attach an email address **Then** the backend
> sets it on the existing Keycloak account via the Admin API and confirms ownership with a one-time code
> sent to that address (entered natively) — no Keycloak-hosted page and no browser shown.
> **Given** an account with a confirmed email **When** the person taps "recover by email" on a different
> device (or after reinstall) **Then** the backend emails a one-time code, the person enters it on a
> native screen, and on success the backend re-binds a fresh device key to the same account — the person
> is signed back in, browserless. No password is ever set, entered, or reset.
> **Given** the recovery phrase **When** an email is or isn't attached **Then** the phrase remains a
> fully working recovery path — email is additive, not a replacement.
> **Given** an email is now stored on the account (personal data, CLAUDE.md §5) **When** the person
> requests erasure/export (Epic 6) **Then** the email is included — it is collected only on the person's
> explicit action, for the single stated purpose of account recovery (data-minimization + purpose
> limitation), and can be detached again by the person.
> **Note (scope — email-sending infrastructure):** this opt-in feature is the reason beta needs
> transactional-email/SMTP delivery (SPF/DKIM) on the netcup host (ADR-0002). It is isolated to this
> feature — the zero-input core (Stories 7.1/7.2) never sends email. Sending a one-time code and
> verifying it in-app does *not* use Keycloak's hosted reset pages and does *not* reintroduce a browser
> surface, so it stays within the locked no-browser constraint (NFR4 respected — Keycloak remains the
> identity store; SGART only triggers the email + re-binds the key via the Admin API).
>
> #### Story 7.4: Consent capture
> As the operator,
> I want explicit, revocable consent captured before household data (personal data under CLAUDE.md §5)
> is processed,
> So that frictionless provisioning has a documented GDPR lawful basis.
>
> **Acceptance Criteria:**
> **Given** a person creating or joining their first household **When** that screen is shown **Then**
> they must explicitly accept the privacy notice/terms before continuing — consent is attached to this
> moment, when the first *household* (personal) data is processed.
> **Given** an accepted consent record **When** the person later requests erasure (Epic 6) **Then** the
> consent record is included in what gets erased/exported.
> **Note (rev B, lawful basis):** the silent account *shell* created at first launch (a random
> pseudonymous id + a device-bound credential, no email/name) is itself processing under GDPR (an online
> identifier, Recital 26). Its lawful basis is **legitimate interest / performance of the service the
> person just requested by opening the app**, with a documented purpose (providing the account the app
> needs to function) and the retention sweep above as the storage-limitation control. Rev A's flat "no
> personal data is processed before [consent]" is inaccurate and is replaced by this explicit basis.
> No *household* personal data is processed before the consent moment.

### Architecture (`ARCHITECTURE-SPINE.md`)

**"Public-phase concerns" Deferred bullet** — remove the self-registration text entirely; it is no
longer a public-phase-only concern. Add a new bullet under the near-term/beta section: *"Frictionless
account provisioning (FR15/Epic 7): account creation is backend-mediated (Admin API) and login is
browserless and app-direct — the app calls Keycloak's token endpoint via a **custom Direct-Grant
authenticator SPI** that verifies a device-signed challenge (Story 7.0 outcome (b)); no browser surface
and no password ROPC. `sgart-app` keeps `directAccessGrantsEnabled: true` with a custom Direct Grant
flow bound; `sgart-admin`'s service account gains `manage-users` (currently `view-users` only) and a
real secret (the checked-in dev placeholder cannot ship) — **no** impersonation/token-exchange
permission (impersonation token exchange is legacy Preview+Deprecated in 26.7 and was rejected). A new
unauthenticated `identity` provisioning endpoint (added to `SecurityConfig` permit list) backed by a
`ProvisionAccount` command/handler and one `adapter.out` (`KeycloakAdminCreateAccount`, registering the
device public key) following the `KeycloakAdminFindHouseholdMemberByEmail` (Story 4.6) pattern; plus the
custom authenticator SPI as a Keycloak-deployed artifact. No new domain event or aggregate —
AD-5/AD-6/AD-7 untouched. New NFRs: the create-account endpoint is
IP-based rate-limited for beta (device attestation a fast-follow), and never-activated accounts are
retention-swept (GDPR storage limitation). The opt-in 'recover by email' path (Story 7.3) adds a beta
infra dependency — transactional-email/SMTP (SPF/DKIM) on the netcup stack (ADR-0002), isolated to that
feature and using no Keycloak-hosted page; the attached email is personal data covered by
erasure/export. The `collaboration` context's invite email side-store (`EmailHmac`,
`InviteEmailSideStore`) is retired — invites carry no email for beta."*

### `sprint-status.yaml`

Move `epic-7` from wherever it currently sits to immediately after `epic-1` in sequence, mark
`required-for-beta: true`, add Story 7.0 (spike) ahead of 7.1, and record this proposal (rev B) as the
action item that superseded the 2026-09-10 mechanism/timing decision and the rev-A ROPC mechanism.

## 5. Decisions (resolved 2026-09-13)

1. **Auth mechanism — browserless custom Direct-Grant login (rev B + Story 7.0 spike, resolved).**
   Device-side password ROPC is rejected as deprecated/less-secure; impersonation token exchange is
   rejected as a legacy, Preview+Deprecated Keycloak feature (26.7). **Chosen: a custom Direct-Grant
   authenticator SPI** — the device holds a self-generated seed in the secure enclave, derives a signing
   key, and signs a login challenge Keycloak verifies via the SPI; account creation stays
   backend-Admin-API. Login and refresh are app→Keycloak. **This is locked (2026-09-13)** — the
   server-side ROPC fallback (candidate (c)) was considered and rejected (still a deprecated grant), so
   no fallback is held open. A device hardware identifier is explicitly *not* used (unstable across
   reinstall, not secret).
2. **Email/HMAC invite path (Story 4.1) — dropped for beta.** Join code + link is the only invite
   mechanism; `EmailHmac`/`InviteEmailSideStore` are retired, not kept optional. Story 4.6's
   "already a member" email-lookup convenience goes with it for beta and can be reconsidered post-beta.
3. **Recovery-phrase reveal timing — at first household creation/join.** Fires right after Story 1.6's
   create/await-invite choice completes, ties into the Story 7.4 consent moment, and leaves the true
   first-launch screen untouched.
4. **Abuse protection for the create-account endpoint — both, plus retention.** IP-based rate limiting
   ships with the beta mechanism; device attestation (Play Integrity / DeviceCheck) is a named
   fast-follow before public release. Separately, never-activated silently-provisioned accounts are
   retention-swept (GDPR storage limitation) — this covers organic sprawl that rate limiting does not.
5. **Recover by email — committed for beta, opt-in, email-only (rev E).** Attaching an email is a
   genuinely optional, *additional* recovery path a person can enable at any time (never required, never
   part of first launch). Recovery is by emailed one-time code entered natively (browserless), which
   re-binds a fresh device key — **no password anywhere**, so there is no password to forget and no
   reset flow to build (this closes the rev-D open question). The recovery phrase remains a fully working
   path whether or not an email is attached. Consequence: **email-sending infrastructure (SMTP/SPF/DKIM
   on netcup) becomes a beta requirement**, isolated to this feature. The stored email is personal data
   (explicit action, single purpose), covered by erasure/export and detachable by the person.

## 6. Implementation Handoff

**Scope classification: Moderate-to-Major.** Doc amendments are proposed above, all open questions are
resolved (§5), but the edits are **not yet applied** to `prd.md`, `epics.md`, `ARCHITECTURE-SPINE.md`,
or `sprint-status.yaml`. Handoff: Product Owner/Developer (Timo, solo) —
1. **Story 7.0 spike — DONE (2026-09-13):** mechanism resolved to candidate (b), a custom Direct-Grant
   authenticator SPI (see Story 7.0). Remaining spike-adjacent build task: prove it with the acceptance
   integration test when Epic 7 is implemented.
2. Apply the doc edits from §4.
3. `create-story` Epic 7's stories in their new position (right after Epic 1) when work resumes.

**Test coverage (CLAUDE.md §6 — rev B; rev A omitted this entirely):** this is the most
security-sensitive change in the project so far and its tests are not optional.
- `ProvisionAccount` command: state-change test (account created; signed-in session obtained).
- Privacy guarantees: erasure/export include the Keycloak account, the consent record, **and any
  attached recovery email**; the retention sweep deletes never-activated accounts; a person can detach an
  attached email. These get their own tests (per §6, and per the standing critical-path GDPR-test item).
- Recover by email (Story 7.3): a test proves an emailed one-time code verifies in-app and re-binds a
  fresh device key to the same account (browserless), and that an unattached/expired/wrong code is
  rejected.
- Abuse surface: the unauthenticated endpoint is rate-limited (a test asserts the limit trips).
- **A green build means the full suite ran:** backend `./gradlew test` (incl. ArchUnit) *and* the app's
  `flutter test` / `flutter analyze`, named explicitly at story completion.

**Success criteria:** PRD, epics.md, ARCHITECTURE-SPINE.md, and sprint-status.yaml consistently reflect
the revised FR-29/FR15/Epic 7 (browserless custom Direct-Grant mechanism, credential model, and
required-for-beta timing), Story 1.4's and Story 4.1's amended ACs, the §5 decisions, and the §6 test
coverage — before Epic 7 stories beyond the spike are drafted.
