---
title: Sprint Change Proposal — Self-Registration Required Before Public Release
date: 2026-09-10
status: draft
---

# Sprint Change Proposal — Self-Registration for the Public Phase

## 1. Issue Summary

**Trigger:** Not a story defect — surfaced during beta-readiness prep (2026-09-10) while confirming
that Keycloak's Admin Console would be used to manually create beta-tester accounts (`registrationAllowed: false`
in `keycloak/realm-sgart.json`, confirmed intentional per NFR8's "no public self-signup" MVP scope).

**Core problem:** Timo pointed out that manual account creation only works for a curated cohort *he
personally onboards*. It does not survive the transition to public app-store distribution: the first
person of any family/friend group who discovers SGART on the App Store/Play Store has no household,
no invite, and — critically — **no way to become an authenticated Keycloak user at all**, since the
invite flow (FR-2/FR2) only ever grants membership to someone who is *already* authenticated, and the
current PRD/architecture has no scheduled mechanism for a brand-new person to create an account
unassisted.

**Category:** New requirement emerged from the product owner, exposing a gap in the original
requirements — the PRD always named "public self-signup" as a Post-MVP concept (§2.2, §5, §6.2,
NFR8), but never turned it into a scheduled, specced requirement with an epic. It was an open-ended
"someday" bullet, not a plan.

**Evidence:**
- `keycloak/realm-sgart.json`: `registrationAllowed: false`, no `resetPasswordAllowed`.
- `AcceptInviteHandler.java` javadoc: "the joiner's identity comes entirely from their own JWT" — accept never creates an account.
- PRD §2.2 Non-Users: "The general public, at MVP... open self-signup... Post-MVP" (no FR, no epic).
- PRD §5 Non-Goals / §6.2 Out of Scope: "No public self-signup in MVP" bundled with genuinely open-ended growth mechanics, not distinguished as a scheduled gate.
- `epics.md` NFR8 and the FR Coverage Map: no FR/epic exists for self-registration; `ARCHITECTURE-SPINE.md`'s "Public-phase concerns" bullet doesn't name it either.

## 2. Impact Analysis

**Epic impact:** No existing epic (1–6) is invalidated or needs rework — this is additive. A new
epic is required: **Epic 7 — Self-Registration & Public Onboarding**, sequenced *after* Epic 6 (Data
Protection), since self-registration adds a new personal-data-collection surface (password, consent
timestamp) that the export/erasure machinery should already be in place to cover. It does **not**
block the current beta — beta continues on manual Admin Console account creation as already scoped —
and is a gate before the public app-store release, not before beta.

**PRD impact (`prd.md`):**
- §2.2 Non-Users — reword the "general public" bullet to distinguish the MVP/beta curated cohort from the now-scheduled self-registration requirement.
- §5 Non-Goals — "No public self-signup in MVP" stays true for MVP/beta, but gains a forward pointer that it's a scheduled pre-public-release requirement (mirrors how ADR-0001 turned crypto-shredding from a vague Post-MVP idea into a named, gated requirement).
- §6.2 Out of Scope — split self-registration out of the "public self-signup / growth mechanics" catch-all; growth mechanics (referrals, discovery, etc.) stay genuinely open, self-registration becomes a named FR.
- New **FR-29: Self-registration for the public phase** under §4.1.

**Epics/NFR impact (`epics.md`):**
- NFR8 reworded: curated-cohort framing scoped explicitly to MVP/beta, with self-registration named as the pre-public-release gate.
- New **FR15 (CAP-15)** in the Requirements Inventory + FR Coverage Map, mapped to Epic 7.
- Deferred section: self-registration removed from the vague "growth mechanics" bucket.
- New **Epic 7** section with 3 stories (register, verify+consent, self-service password reset).

**Architecture impact (`ARCHITECTURE-SPINE.md`):** Low blast radius — registration happens entirely
inside Keycloak, before any SGART command is issued, so **AD-5/AD-6/AD-7 (pseudonym issuance,
no-persisted-PII, erasure-by-de-linking) are untouched**: no new domain event, no new aggregate. The
"Public-phase concerns" Deferred bullet gets self-registration named explicitly, with the concrete
mechanism noted (`registrationAllowed`/`resetPasswordAllowed` flip, Keycloak's built-in
"Terms and Conditions" and "Verify Email" required actions for GDPR consent + email verification).

**UX impact:** A new UX-DR (self-registration entry screen reachable from the existing sign-in flow)
is needed when Epic 7 is actually planned — flagged for a UX pass at that time, not designed now.

**Technical/infra impact:** None for the current beta-prep work in progress (TLS proxy, production
docker-compose, GDPR privacy tests, Android signing) — Epic 7 is scheduled after beta, before the
public app-store release, and touches the Keycloak realm config + a thin Flutter/backend surface, not
the event-sourced core.

## 3. Recommended Approach

**Option 1 — Direct Adjustment (additive), selected.** Add Epic 7 and amend the PRD/epics
sections named above. No rollback (nothing broken, this is a gap-fill) and no MVP scope reduction
(Epics 1–6 stand as delivered/planned).

- Effort: **Medium** — new epic (~3 stories), mostly Keycloak realm config + a thin UI surface, since
  registration/verification/password-reset can largely reuse Keycloak's own hosted pages (already the
  pattern for login) rather than requiring native Flutter forms.
- Risk: **Low** — additive only; no changes to shipped Epics 1–6 domain code; no new events/aggregates.
- Timeline: does not block beta (explicitly confirmed with Timo); slots in after Epic 6, before the
  public app-store release milestone.

## 4. Detailed Change Proposals

### PRD (`prd.md`)

**§2.2 Non-Users** — OLD: *"The general public, at MVP. MVP onboards a curated friends-&-family cohort only; open self-signup and any growth mechanics are Post-MVP."*
NEW: *"The general public, during MVP/beta. MVP and the beta onboard a curated friends-&-family cohort only, with accounts created manually. Self-registration is scheduled before any public app-store release (FR-29) — it is a named requirement, not an open-ended Post-MVP maybe. Open-ended growth mechanics (referrals, discovery, etc.) remain genuinely Post-MVP."*

**§5 Non-Goals** — OLD: *"No public self-signup in MVP. Friends-&-family cohort only."*
NEW: *"No public self-signup during the MVP/beta curated cohort — but self-registration is a scheduled, gated requirement before any public app-store release (FR-29), not an open-ended deferral."*

**§6.2 Out of Scope for MVP** — OLD: *"Full web client and public self-signup / growth mechanics."*
NEW: *"Full web client (Post-MVP, open-ended) and open-ended growth mechanics (referrals, discovery — Post-MVP). Self-registration itself is out of scope for MVP/beta but is a scheduled, named requirement (FR-29, Epic 7) gated before the public app-store release — not lumped with the open-ended items above."*

**New FR under §4.1 Households & Membership:**

> #### FR-29: Self-registration for the public phase
> Before SGART is distributed via public app stores, a person with no existing account can create one
> from the app, without requiring an existing member to invite them first. Gates the public/app-store
> release; not required for the MVP/beta curated cohort (manual account creation, §2.2).
> **Consequences (testable):**
> - On first launch with no session, the app's own login screen shows a visible "Register" link/button
>   alongside sign-in — a person never has to already know a registration URL or be told one out of band.
> - Tapping it opens the registration flow in-app (the same in-app browser/AppAuth surface already used
>   for sign-in, per Story 1.4 — not a bare external browser link the person has to find their way back from).
> - Registration requires email verification before the account can create or join a Household.
> - Registration captures explicit acceptance of the privacy notice / terms (GDPR lawful basis,
>   consent must be revocable per CLAUDE.md §5) before any personal data beyond the account itself is processed.
> - A registered person can self-serve a password reset without contacting the operator.
> - Once registered and verified, the person proceeds through the existing FR-1 create/await-invite flow unchanged — self-registration only changes *how someone becomes authenticated*, not what happens after.
> **Feature-specific NFRs:**
> - Delegates entirely to Keycloak (NFR4) — no new SGART-owned credential storage, no new domain event or aggregate.
> **Notes:**
> - `[NOTE FOR PM]` Deliberately scoped as a *gate before public release*, distinct from the MVP/beta curated-cohort model (§2.2) — manual Admin Console account creation continues through beta.

### Epics (`epics.md`)

**NFR8** — OLD: *"Curated cohort; solo is first-class. MVP onboards a friends-&-family cohort only, with no public self-signup; a household of one is fully supported alongside multi-person households."*
NEW: *"Curated cohort through MVP/beta; solo is first-class. MVP and the beta onboard a friends-&-family cohort with manually created accounts; self-registration (FR15/Epic 7) is required before any public app-store release, not an open-ended deferral. A household of one is fully supported alongside multi-person households."*

**Requirements Inventory** — add:

> FR15 (CAP-15): **Self-registration for the public phase.** A person with no existing account can register from the app (no inviter required), verify their email, and accept the privacy notice/terms before any personal data beyond the account is processed; self-service password reset is included. Gates the public app-store release; not required for the MVP/beta curated cohort. *(PRD FR-29)*

**FR Coverage Map** — add row: `| FR15 | Self-registration | Epic 7 |`

**Deferred (out of MVP) list** — remove "public self-signup" from the undifferentiated Post-MVP list; it is now Epic 7, scheduled and specced, not an open-ended deferral. Genuinely open items (full web client, additional languages, multi-market data, geolocation discovery, crypto-shredding implementation, receipts/OCR, price intelligence, dashboard, list templates) remain as-is.

**New Epic section:**

> ### Epic 7: Self-Registration & Public Onboarding
>
> Before SGART leaves the curated friends-&-family cohort for public app-store distribution, a
> person with no existing account and no inviter can create one, verify their email, and accept the
> privacy notice — then proceeds through the existing Epic 1 create/await-invite flow unchanged.
> Self-service password reset closes the same gap for existing accounts. **Gate before public
> release**, not required for beta. *(FR15/FR-29.)*
>
> #### Story 7.1: Self-register a new account
> As a person with no SGART account,
> I want to create one myself,
> So that I can start using SGART without waiting on someone to invite or provision me.
>
> **Acceptance Criteria:**
> **Given** a fresh app install, first launch, no session **When** the login screen is shown **Then** it displays a visible "Registrieren" link/button next to sign-in — discoverable without any out-of-band instruction.
> **Given** that link **When** a person with no account taps it **Then** the registration flow opens in-app (the existing AppAuth/in-app-browser surface from Story 1.4), and they can create a Keycloak account (email + password, or Keycloak's hosted registration form) without any existing member's involvement.
> **Given** a freshly registered, unverified account **When** the person tries to create or join a Household **Then** they are blocked until email verification completes (Keycloak's "Verify Email" required action).
> **Given** registration **When** it completes **Then** the person lands in the existing FR-1 create/await-invite routing — no new post-registration flow is built.
>
> #### Story 7.2: Consent capture at registration
> As the operator,
> I want explicit, revocable consent captured before any personal data beyond the account is processed,
> So that self-registration has a documented GDPR lawful basis.
>
> **Acceptance Criteria:**
> **Given** registration **When** a new account is created **Then** the person must explicitly accept the privacy notice/terms (Keycloak's "Terms and Conditions" required action) before proceeding — no silent/implied consent.
> **Given** an accepted consent record **When** the person later requests erasure (Epic 6) **Then** the consent timestamp is included in what gets erased/exported — no orphaned consent record survives erasure.
>
> #### Story 7.3: Self-service password reset
> As a registered person,
> I want to reset my own password,
> So that a forgotten password doesn't require contacting the operator.
>
> **Acceptance Criteria:**
> **Given** the sign-in screen **When** a person requests a password reset **Then** Keycloak's self-service reset flow (`resetPasswordAllowed: true`) sends a reset email without operator involvement.
> **Given** the beta's manually created accounts **When** Epic 7 ships **Then** existing testers can also use self-service reset going forward — this is not registration-only.

**Epic List section** — add after Epic 6:

> ### Epic 7: Self-Registration & Public Onboarding
> A person with no existing account can register themselves, verify their email, and accept the
> privacy notice — the gate that must clear before SGART leaves the curated friends-&-family cohort
> for public app-store distribution. Does not block beta.
> **FRs covered:** FR15.

### Architecture (`ARCHITECTURE-SPINE.md`)

**Deferred → "Public-phase concerns" bullet** — OLD: *"Public-phase concerns: self-hosted push (UnifiedPush) vs. FCM/APNs, full web client, multi-market StoreChain sourcing, additional UI languages."*
NEW: *"Public-phase concerns: self-hosted push (UnifiedPush) vs. FCM/APNs, full web client, multi-market StoreChain sourcing, additional UI languages. **Self-registration (FR15/Epic 7) is scheduled, not open-ended**: flip `registrationAllowed`/`resetPasswordAllowed` in the Keycloak realm, use Keycloak's built-in 'Verify Email' and 'Terms and Conditions' required actions for verification/consent. No new domain event or aggregate — registration happens entirely inside Keycloak before any SGART command is issued, so AD-5/AD-6/AD-7 are untouched."*

### `sprint-status.yaml`

Add `epic-7` in `backlog` status with its 3 stories in `backlog`, plus a new action item recording this
change. Also correcting a stale entry found while editing: the Epic-4 retro action "SSE fan-out path
tests" is marked `open` but was actually completed and committed (`501a456`) — updating to `done`.

## 5. Implementation Handoff

**Scope classification: Moderate** — backlog reorganization (new epic + PRD/architecture doc
amendments), no code changes yet. Handoff: **Product Owner / Developer** — the doc edits above are
applied directly in this session (Timo is both PM and the only developer on this solo project); Epic 7
stories get `create-story`'d in their normal turn, after Epic 6, when work resumes post-beta.

**Success criteria:** PRD, epics.md, ARCHITECTURE-SPINE.md, and sprint-status.yaml all reflect FR-29 /
FR15 / Epic 7 consistently; no existing epic's scope or acceptance criteria changed; beta-prep work in
progress is unaffected.

## 6. Addendum (same day, 2026-09-10) — Mechanism refined to magic-link + forced password

After the initial approval above, Timo pressure-tested the mechanism: "is registration even
necessary — could invitees join without registering/logging in at all?" Resolved as follows, and
folded into the same Epic 7 (no new correct-course cycle needed — additive refinement, still
Moderate/docs-only):

- **Dropping auth entirely is not viable** — live sync across a person's own devices, multi-person
  household roles, and GDPR export/erasure (Epic 6) all require a durable per-person identity. A
  loginless mode would be a different, local-only product, not a scope tweak.
- **But the *friction* of getting that identity can drop a lot.** Only the first person of a new
  group (no inviter) needs a true "create an identity from nothing" flow. Everyone they invite
  already has their email known to the system (the invite side-store) — there's no reason invite
  acceptance can't provision their account directly, with no separate registration form.
- **Mechanism: passwordless magic-link for first auth, then forced password.** First-time
  authentication (both the no-inviter register path and invite-acceptance for a brand-new invitee)
  uses a one-time emailed link — no password sequence needed. Timo flagged the obvious risk:
  "that email may be deleted" — so immediately after that first magic-link login, Keycloak's
  "Update Password" required action forces a real password before proceeding. Every later login
  uses that password (or an already-issued long-lived session), never a second emailed link.
- **Result: Epic 7 grew from 3 to 4 stories** — split "self-register" (7.1, no-inviter path) from a
  new "passwordless account provisioning at invite acceptance" (7.2, the invitee path), keeping
  consent capture (now 7.3) and password reset (now 7.4). Extends Story 4.2's accept-invite flow to
  cover a brand-new invitee, but only the entry condition (who may call accept) changes — no domain
  event, aggregate, or AD-5/AD-6/AD-7 mechanic is touched.
