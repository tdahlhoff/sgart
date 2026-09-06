# Epic 4 — Membership & Invite State Model (up-front modeling artifact)

**Date:** 2026-09-06 · **Author:** Timo (decisions) + Amelia (drafting) · **Status:** draft for Epic 4 planning
**Why this exists:** Epic 3's retrospective Action 1 — when an epic touches a shared state model or
cross-aggregate vocabulary, draft the **state machine + single ubiquitous vocabulary up front**,
before the first story mutates it. Epic 3's item-transfer/status model churned across 3.3→3.4→3.6
because this was skipped. This artifact fixes the membership vocabulary once, so 4.1/4.2/4.3 build on
a settled model instead of re-discovering it.

This feeds `create-story` for 4.1–4.3. It does **not** change the PRD/epics/architecture spine — it
concretizes them (AD-3, AD-5, AD-6, AD-11).

---

## 0. Locked decisions (Timo, 2026-09-06)

1. **Invite is an entity inside the `Household` aggregate** (like `Store` already is), on the
   `household-{id}` stream — not a separate aggregate. The no-duplicate-pending-invite invariant is
   then an atomic single-stream check, mirroring active-store-name uniqueness.
2. **Split, self-describing membership events** — `MemberLeft` / `MemberRemoved` / `MemberPromoted` /
   `MemberDemoted` (not a unified `MemberRemoved(removedBy)` / `MemberRoleChanged(old,new)`). Rationale:
   each event names its action outright; no consumer computes leave-vs-remove or promote-vs-demote from
   a field. Deliberately chosen up front.
3. **Lazy, on-access invite expiry** — no scheduler. `InviteExpired` is raised opportunistically when
   an accept is attempted past TTL, or when a new invite to the same address is created; the read model
   derives "expired" from the timestamp for display.

---

## 1. Vocabulary (the single ubiquitous set — fix now, don't churn)

**Aggregate:** `Household` (existing, `household-{id}` stream). Gains a `pendingInvitesById` map and
membership lifecycle beyond join. Identity ACL stays the sole minter of `MemberId` (AD-5).

### Invite lifecycle events (on `household-{id}`)
| Event | Payload (ids/HMAC only — no PII, AD-5/AD-6) |
|---|---|
| `MemberInvited` | `inviteId, householdId, emailHmac, invitedBy(MemberId), role=PARTICIPANT, invitedAt` |
| `InviteAccepted` | `inviteId, householdId, memberId` *(the ACL-minted joiner)* |
| `InviteRevoked` | `inviteId, householdId, revokedBy(MemberId)` |
| `InviteExpired` | `inviteId, householdId` *(raised lazily; no command)* |

### Membership lifecycle events (on `household-{id}`)
| Event | Payload | Meaning |
|---|---|---|
| `MemberJoined` *(existing)* | `householdId, memberId, role` | `ADMIN` on create; `PARTICIPANT` on accept |
| `MemberLeft` | `householdId, memberId` | self-removal (the member left) |
| `MemberRemoved` | `householdId, memberId, removedBy(MemberId)` | an Admin removed another member |
| `MemberPromoted` | `householdId, memberId, promotedBy(MemberId)` | `PARTICIPANT → ADMIN` |
| `MemberDemoted` | `householdId, memberId, demotedBy(MemberId)` | `ADMIN → PARTICIPANT` |

### Household lifecycle event (on `household-{id}`)
| Event | Payload | Meaning |
|---|---|---|
| `HouseholdDeleted` | `householdId, deletedBy(MemberId)` | terminal; household gone for all members |

### Commands → events
| Command | Auth | Emits |
|---|---|---|
| `InvitePerson(householdId, requestedBy, email)` | **membership-gated** (any member, like `addStore`) | `MemberInvited` (+ maybe a housekeeping `InviteExpired`) |
| `AcceptInvite(householdId, inviteId, joiner)` | authenticated invitee (Keycloak) | `InviteAccepted` (+ `MemberJoined` unless already a member) / or `InviteExpired`+reject |
| `RevokeInvite(householdId, inviteId, requestedBy)` | **role-gated** (Admin) | `InviteRevoked` |
| `LeaveHousehold(householdId, requestedBy)` | membership (self only) | `MemberLeft` |
| `RemoveMember(householdId, requestedBy, targetMemberId)` | **role-gated** (Admin) | `MemberRemoved` |
| `PromoteMember(householdId, requestedBy, targetMemberId)` | **role-gated** (Admin) | `MemberPromoted` |
| `DemoteMember(householdId, requestedBy, targetMemberId)` | **role-gated** (Admin) | `MemberDemoted` |
| `DeleteHousehold(householdId, requestedBy)` | **role-gated** (Admin) + hard confirm | `HouseholdDeleted` |

---

## 2. State machines

### 2a. Invite (per `inviteId`)
```
                 InvitePerson
      (none) ─────────────────────▶ PENDING
                                      │
             AcceptInvite (in TTL)    │──────────▶ ACCEPTED  (terminal)
             RevokeInvite (Admin)     │──────────▶ REVOKED   (terminal)
             past TTL, on next access │──────────▶ EXPIRED   (terminal)
```
- **Lazy expiry:** there is no timer. `InviteExpired` is raised when (a) `AcceptInvite` arrives after
  `invitedAt + TTL` → raise `InviteExpired`, reject the accept; or (b) `InvitePerson` targets an
  `emailHmac` whose only blocker is a past-TTL pending invite → raise `InviteExpired` (housekeeping),
  then allow the new `MemberInvited`. The read model shows "expired" from the timestamp regardless.
- **Side-store purge** (raw email) fires on ACCEPTED, REVOKED, EXPIRED (and erasure).

### 2b. Membership (per `MemberId`, within one household)
```
                MemberJoined(ADMIN)        ┌──── MemberPromoted ────┐
   (none) ──── on household create ──────▶ │                        ▼
      │        MemberJoined(PARTICIPANT)   │  PARTICIPANT ◀──────▶ ADMIN
      └──────── on InviteAccepted ────────▶│        ▲   MemberDemoted   │
                                           │        └── MemberLeft ─────┤
                                           │            MemberRemoved   │
                                           ▼                            ▼
                                         (none)  ◀───────────────────  (none)

         any state ──── HouseholdDeleted ────▶ (terminal, all members)
```

---

## 3. Invariants (all guarded atomically on `household-{id}`, AD-4/AD-8)

1. **At-least-one-Admin (last-Admin).** Reject `MemberLeft(admin)`, `MemberRemoved(admin)`, and
   `MemberDemoted(admin)` when that member is the *only* `ADMIN` → `LastAdminException` (409). The
   `Household` folds the full `rolesByMember` map, so only it can guard this atomically.
2. **Governance is Admin-only.** `RemoveMember` / `PromoteMember` / `DemoteMember` / `DeleteHousehold`
   / `RevokeInvite` require `requestedBy` to be `ADMIN` → `GovernanceNotPermittedException` (403).
   `LeaveHousehold` needs only membership (self). *(Follows the existing `rename` role-gate vs
   `addStore` membership-gate split in `Household`.)*
3. **Invite creation is membership-gated.** `InvitePerson` requires `requestedBy` be any member
   (4.1 "any member invites") — mirrors `addStore`.
4. **No duplicate pending invite.** `InvitePerson` is rejected if a *non-expired* PENDING invite for
   the same `emailHmac` exists in this household → `DuplicatePendingInviteException` (409). Atomic on
   the one stream (the reason Invite is an entity here).
5. **Convergent no-ops (AD-8):** promote-an-already-Admin, demote-an-already-Participant,
   remove/leave a non-member, accept-an-already-accepted invite, delete-an-already-deleted household —
   raise nothing.

### ⚠️ The one that isn't a domain invariant — "email already belongs to a member" (4.1 E5)
Members are pseudonymous `MemberId`s carrying **no email** (AD-6), so the `Household` aggregate
**cannot** see whether an invited email is already a member. This check lives at the **application /
ACL seam**, not the domain: the `InvitePerson` handler asks the Identity ACL to resolve the email →
`keycloakUserId` and check whether that user is already mapped to a `MemberId` in this household; if
so, reject before issuing the command. **Document this in Story 4.1 so it isn't discovered mid-dev.**
(Symmetrically, 4.2 E5 "invitee already a member" → the accept path resolves the joiner's `MemberId`
via the ACL; if it's already in `rolesByMember`, consume the invite (`InviteAccepted`) but raise **no**
second `MemberJoined`.)

---

## 4. Cross-context accept flow (reuses Epic 1's ACL)

```
invitee opens invite link (deep-link 4.2, or web-fallback 4.6 — same outcome)
  → Keycloak auth (JWT)
  → adapter.in AcceptInvite: MintMemberIdentity (ACL, AD-5 — the sole minter, already built Epic 1)
        → memberId for (keycloakUserId, householdId)
  → AcceptInvite command (carries memberId)
  → Household: invite PENDING & in-TTL? → InviteAccepted + MemberJoined(PARTICIPANT)
                invite past TTL?         → InviteExpired (reject)
                joiner already a member? → InviteAccepted only (no dup MemberJoined)
  → purge raw-email side-store entry for inviteId
```

---

## 5. Read models

| Read model | Keyed by | Projected from | Consumers |
|---|---|---|---|
| `household_member_read_model` | `(householdId, memberId)` → `role` | MemberJoined/Left/Removed/Promoted/Demoted, HouseholdDeleted | member list + role display (4.3), live sync (4.4) |
| `invite_read_model` | `(householdId, inviteId)` → `status, invitedAt, invitedBy` | MemberInvited/Accepted/Revoked/Expired | pending-invite list; derives "expired" from `invitedAt + TTL` |
| **raw-email side-store** (mutable, **not** an event stream) | `inviteId → normalizedEmail` | written on invite, **purged** on accept/revoke/expire/erasure | Keycloak email delivery only |

Per Epic 2 Action 4 (still open): each new read model ships with a two-household isolation +
replay/idempotency test **in the same PR**.

---

## 6. DSGVO / GDPR — Epic 4 is the first PII-touching epic (CLAUDE.md §5/§6)

- `MemberInvited` carries only `HMAC(stable per-deployment secret, normalizedEmail)` — **never** the raw
  email (AD-6). HMAC secret is stable (not per-invite salt) or the duplicate-pending check breaks.
- Raw email lives **only** in the mutable side-store, purged on accept/revoke/expire/erasure (AD-6/AD-7).
- Erasure is **de-linking, never history rewrite** (AD-7): remove the ACL mapping + `MemberRemoved`; the
  pseudonymous `MemberId` in past events becomes unresolvable.
- **Privacy tests are first-class (§6), not inferred:** no-raw-email-in-any-event; purge-on-accept;
  purge-on-expire; HMAC-stability (same email → same HMAC so the dup check holds); already-member
  rejection leaks nothing.

---

## 7. Story → model mapping

- **4.1 Invite by email** → `MemberInvited` entity + `InvitePerson` (membership-gated) + no-dup-pending
  (aggregate, §3.4) + already-member (ACL seam, §3⚠) + `emailHmac` + side-store + `invite_read_model`.
- **4.2 Accept & join** → `AcceptInvite` + `InviteAccepted` + `MemberJoined(PARTICIPANT)` + ACL mint +
  purge + expired(lazy)/revoked/already-member guards. Deep-link/web-fallback = adapter.in (4.6).
- **4.3 Roles & governance** → `LeaveHousehold`/`MemberLeft`, `RemoveMember`/`MemberRemoved`,
  `PromoteMember`/`MemberPromoted`, `DemoteMember`/`MemberDemoted`, `DeleteHousehold`/`HouseholdDeleted`,
  `RevokeInvite`/`InviteRevoked`, the last-Admin invariant, the role gates, `household_member_read_model`,
  and the delete cascade (§8).
- **4.4/4.5/4.6** consume these events/read models over SSE + notifications; no new membership state.

---

## 8. Open sub-decisions (named now so they don't ambush a story)

1. **Household-deletion cascade.** 4.3 AC says a deleted household's Lists/Trips/Stores/memberships are
   removed for all members. Recommendation: a **read-side purge keyed by `householdId`** + stop serving
   the household on `HouseholdDeleted`; deep per-aggregate-stream deletion is erasure-adjacent → align
   with Epic 6 (AD-7 de-linking), don't hand-roll it in 4.3. **Decide during 4.3 planning.**
2. **Invite TTL value** (e.g. 7 days) — product decision for 4.1.
3. **`RevokeInvite` — Admin-only or any member?** Drafted Admin-only (governance). Confirm in 4.1/4.3.
