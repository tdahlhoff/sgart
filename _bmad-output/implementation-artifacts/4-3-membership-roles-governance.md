---
baseline_commit: 348b73b
---

# Story 4.3: Membership roles & governance

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an Admin,
I want role-gated governance with safeguards,
so that the household stays controllable and never loses its last Admin.

This is **Epic 4's third story** and the one that makes a multi-person household *governable*: it lands
the full membership-lifecycle command set on top of the settled state model
(`_bmad-output/planning-artifacts/epic-4-membership-state-model.md` §7, the **4.3 row**). Stories 4.1/4.2
made a household multi-person (invite → accept → join as `PARTICIPANT`); 4.3 adds **leave, remove,
promote, demote, delete-household, and revoke-invite**, the **at-least-one-Admin** invariant, the
**Admin-only governance gates**, the `household_member_read_model` (the member roster), and the
**delete cascade**. It also closes the loop on the invite lifecycle (`PENDING → REVOKED`).

The single hardest thing in this story is **not** the aggregate — it is the cross-context consequence:
**every household-scoped read authorizes off the `identity_member_mapping` ACL table**
(`ListHouseholdsForCaller.forCaller`, `ResolveMemberIdentity.resolve`), with **no** event-stream
cross-check. So raising `MemberRemoved` / `MemberLeft` / `HouseholdDeleted` on the stream **does not by
itself revoke access** — the removed/left member (or every member of a deleted household) keeps a durable
mapping and still sees the household in their switcher and passes every `resolve(...)` gate. This is the
exact "mapping = access" class that Story 4.2's F1 HIGH finding was about. **Governance removal MUST
synchronously de-link the ACL mapping** (locked decision 3).

## Locked Decisions (Timo, 2026-09-07)

Decided at story-creation time; binding for implementation. They fold into the ACs and tasks below.

1. **Full vertical slice, one story.** 4.3 ships the backend governance domain/commands/adapters **and**
   the Flutter member-management / roles / governance UI, mirroring the 4.1/4.2 full-slice precedent.
2. **Command set = Epic 4.3 ACs + `RevokeInvite`.** All six state-model §7 commands are in scope:
   `LeaveHousehold`, `RemoveMember`, `PromoteMember`, `DemoteMember`, `DeleteHousehold`, and
   `RevokeInvite` (**Admin-only**, §8.3 confirmed). `RevokeInvite` completes the invite lifecycle
   (`PENDING → REVOKED`) now rather than in a later story.
3. **Removal de-links the ACL mapping synchronously — the crux.** `RemoveMemberHandler`,
   `LeaveHouseholdHandler`, and `DeleteHouseholdHandler` de-link the authoritative
   `identity_member_mapping` row(s) **after a successful `append`** (append-then-side-effect, mirroring
   `AcceptInviteHandler`'s purge). A new **published Identity ACL port** (`RetractMembership`) exposes
   `retractMember(householdId, memberId)` and `retractHousehold(householdId)`; the domain repo gains
   `deleteMappingByMember(householdId, memberId)` and `deleteAllMappings(householdId)`. This is what
   "disappears from every member's switcher" and "a removed member loses access" actually mean —
   de-linking is the AD-7 mechanism, not a stream event. **Skipping it re-creates the 4.2 F1 ghost-member
   leak.** Ordering: append first (a lost append must not have already revoked access); de-link second
   (an append-ok / de-link-fail leaves the member on-stream but still mapped — access retained, benign,
   self-corrects on retry — never the reverse).
4. **Delete cascade = read-side purge + stop serving (state-model §8.1).** On `HouseholdDeleted`: the three
   collaboration projectors purge their `householdId`-keyed read-model rows, and the handler de-links **all**
   ACL mappings for the household (decision 3) — that is the "stop serving" that drops it from every
   switcher and 403s every scoped query. **Per-aggregate event streams are left intact** (deep stream
   deletion is erasure-adjacent → Epic 6 / AD-7, not hand-rolled here).
5. **Member roster is pseudonymous (state-model §5, AD-6).** `ListHouseholdMembers` returns each member's
   `MemberId` + `HouseholdRole` + an `isSelf` flag; the client marks the caller "Sie" and shows others by
   role only. **No display-name / Keycloak name-resolution seam** is built (YAGNI; the deferred 4.1 D3
   "inviter name" stays deferred). No PII enters the member read model, event, or API.

## Acceptance Criteria

From `epics.md#Story 4.3` (BDD), with the locked decisions and state-model §1–§8 applied.

1. **AC1 — Roles gate only governance; both roles do all daily work.** Given the two roles, when day-to-day
   work is done (create/rename lists, add/assign items, start/complete trips, add stores, **invite by
   email**), then **both** Admin and Participant can do it — those commands stay **membership-gated, not
   role-gated** (AR10, unchanged from Epics 1–3). Roles gate **only** the governance actions in AC2. *(A
   regression guard: no new role gate leaks onto a daily command — a Participant can still add a store, a
   list, and send an invite.)*

2. **AC2 — Admin governance, Participant self-only.** Given an **Admin** caller, when they remove another
   member, promote a member to Admin, demote an Admin to Participant, revoke a pending invite, or delete
   the household, then the action is allowed. Given a **Participant** caller, when they attempt any of
   those, then it is **rejected** (`GovernanceNotPermitted`, **403**) — a Participant may **only remove
   themselves (leave)**.

3. **AC3 — Leave.** Given a member (any role), when they leave the household, then `MemberLeft` is appended,
   **their ACL mapping is de-linked** (the household disappears from *their* switcher; they 403 on further
   scoped access), and they no longer appear in the member roster. Leaving when **not** a member is a
   convergent no-op (§3.5). *(Subject to AC5 for a last Admin.)*

4. **AC4 — Remove / promote / demote.** Given an Admin, when they remove another member, then `MemberRemoved`
   is appended and **that member's ACL mapping is de-linked** (removed from their switcher, 403 thereafter).
   When they promote a Participant, `MemberPromoted` is appended (role → `ADMIN`); when they demote an Admin,
   `MemberDemoted` is appended (role → `PARTICIPANT`). **Convergent no-ops (§3.5):** promoting an existing
   Admin, demoting an existing Participant, and removing a non-member each raise nothing and succeed.

5. **AC5 — At-least-one-Admin (last-Admin invariant).** Given the **last** Admin, when they attempt to
   **leave**, **be removed**, or **be demoted**, then it is **prevented** (`LastAdmin`, **409**) — no
   `MemberLeft`/`MemberRemoved`/`MemberDemoted` reaches the stream and no ACL mapping is de-linked. Guarded
   atomically on the single `household-{id}` stream off the folded `rolesByMember` map.

6. **AC6 — Revoke invite (Admin-only).** Given an Admin and a **PENDING, non-expired** invite, when they
   revoke it, then `InviteRevoked` is appended (invite → `REVOKED`, terminal), the invite's raw-email
   side-store row is **purged** (AD-6), and it leaves the pending-invites list. Revoking an
   **already-REVOKED** invite is a convergent no-op; revoking an **absent** invite → `InviteNotFound`
   (**404**). A **Participant** revoking → `GovernanceNotPermitted` (**403**). *(Revoke of an already
   ACCEPTED/EXPIRED invite is likewise rejected as "no pending invite to revoke", 404 — it is not PENDING.)*

7. **AC7 — Delete household + cascade.** Given an Admin, when they confirm household deletion (a clear,
   hard-to-mis-trigger confirmation on the client), then `HouseholdDeleted` is appended, **all** ACL mappings
   for the household are de-linked, and every `householdId`-keyed read-model row (household name, stores,
   lists, items, suggestions, assignments, trips, invites, members) is purged — the household disappears from
   **every** member's switcher and all scoped reads 403/return-empty. Per-aggregate event streams are left
   intact (Epic 6 owns deep erasure). Deleting an **already-deleted** household is a convergent no-op.
   Erasure of an **individual's** personal data remains a separate right (Epic 6) — not this story.

8. **AC8 — Member roster read model.** Given a member of a household, when they open member management, then
   `ListHouseholdMembers` returns each member's `MemberId`, `HouseholdRole`, and an `isSelf` flag — **no
   email/name/PII** (AD-6). Projected from `MemberJoined`/`MemberLeft`/`MemberRemoved`/`MemberPromoted`/
   `MemberDemoted`/`HouseholdDeleted` into `household_member_read_model`, keyed `(householdId, memberId)`.
   A non-member requesting the roster → **403**.

9. **AC9 — Client member-management & governance slice.** A member-management screen shows the roster (the
   caller marked „Sie", others by role). **Admin** sees per-member actions (promote / demote / remove) with
   confirmations, a **revoke** action on each pending invite, and a **delete-household** action behind a
   hard confirmation (e.g. type-to-confirm / explicit destructive dialog). **Participant** sees only „Haushalt
   verlassen" (leave). The last-Admin `409`, governance `403`, and not-found `404` surface as distinct inline
   German errors; successful leave/removal/deletion re-routes via `HouseholdsCubit.bootstrap()`. `commandId`
   idempotency (regenerate on payload change / after success) + `isSubmitting` re-entrancy guard; a11y
   labels; `flutter analyze` clean.

## Tasks / Subtasks

> Each task lists its expected test(s) — see the **Test Manifest**. A `[x]` task with no matching test is
> an integrity failure (Epic-3 retro Action 2). TDD is the default (CLAUDE.md §6). Vocabulary is fixed by
> state-model §1 — use those exact event/command names, no synonyms (AD-11).

### Backend — domain events (state-model §1)

- [ ] **T1. Six domain events** (AC3–AC7) under `de.sgart.collaboration.domain.event`, each a record
  implementing `DomainEvent`, **ids/roles only — no PII** (AD-6), mirroring `MemberJoined`'s shape:
  - `MemberLeft(EventId, HouseholdId, MemberId memberId)`
  - `MemberRemoved(EventId, HouseholdId, MemberId memberId, MemberId removedBy)`
  - `MemberPromoted(EventId, HouseholdId, MemberId memberId, MemberId promotedBy)`
  - `MemberDemoted(EventId, HouseholdId, MemberId memberId, MemberId demotedBy)`
  - `HouseholdDeleted(EventId, HouseholdId, MemberId deletedBy)`
  - `InviteRevoked(EventId, HouseholdId, InviteId, MemberId revokedBy)`

### Backend — domain exceptions

- [ ] **T2. `LastAdminException`** (AC5) — `de.sgart.collaboration.domain.exception.LastAdminException`,
  signalling a leave/remove/demote that would drop the household's last Admin.
- [ ] **T3. `GovernanceNotPermittedException`** (AC2, AC6) — governance attempted by a non-Admin. Mirror
  `RenameNotPermittedException`. Reused by remove/promote/demote/delete/revoke.

### Backend — domain aggregate (`Household` gains the governance lifecycle)

- [ ] **T4. `InviteStatus.REVOKED` + folds** (AC6, AC3/AC4/AC7 role state) — add `REVOKED` to the private
  `InviteStatus` enum; in `apply(...)` add `InviteRevoked -> withStatus(REVOKED)` (mirror the `InviteAccepted`
  case). Add `apply(...)` cases for `MemberLeft`/`MemberRemoved` (→ `rolesByMember.remove(memberId)`),
  `MemberPromoted` (→ put `ADMIN`), `MemberDemoted` (→ put `PARTICIPANT`), and `HouseholdDeleted` (→ set a
  `boolean deleted = true` flag). Update the aggregate javadoc's folded-events sentence.
- [ ] **T5. `Household.leaveHousehold(MemberId requestedBy, CommandId)`** (AC3, AC5) — `requireMember`
  (non-member → convergent no-op, raise nothing, §3.5); if `requestedBy` is the **only** `ADMIN` →
  `LastAdminException`; else `raise(MemberLeft(...))`.
- [ ] **T6. `Household.removeMember(MemberId requestedBy, MemberId target, CommandId)`** (AC4, AC5, AC2) —
  `requireAdmin(requestedBy)` (→ `GovernanceNotPermittedException`); if `target` not a member → convergent
  no-op (§3.5); if `target` is the only `ADMIN` → `LastAdminException`; else `raise(MemberRemoved(target,
  requestedBy))`.
- [ ] **T7. `Household.promoteMember(...)` / `demoteMember(...)`** (AC4, AC5, AC2) — `requireAdmin`; unknown
  `target` → `NotAHouseholdMemberException` (reuse); **promote an already-Admin / demote an
  already-Participant → convergent no-op** (§3.5); demote of the only Admin → `LastAdminException`; else
  raise `MemberPromoted` / `MemberDemoted`.
- [ ] **T8. `Household.revokeInvite(MemberId requestedBy, InviteId, CommandId)`** (AC6, AC2) — `requireAdmin`;
  branch on the folded `InviteState`: absent → `InviteNotFoundException` (reuse); `PENDING` → `raise(InviteRevoked)`;
  `REVOKED` → convergent no-op; `ACCEPTED`/`EXPIRED` → `InviteNotFoundException` ("no pending invite to
  revoke"). *(No `now`/expiry branch needed — a past-TTL PENDING invite may still be revoked; revoke is a
  terminal governance action, expiry is lazy housekeeping.)*
- [ ] **T9. `Household.deleteHousehold(MemberId requestedBy, CommandId)`** (AC7) — `requireAdmin`; if already
  `deleted` → convergent no-op (§3.5); else `raise(HouseholdDeleted(requestedBy))`. **No last-Admin guard**
  (deleting the whole household is allowed even for a sole Admin).
- [ ] **T10. Guard rails** — add `private void requireAdmin(MemberId)` (throws `GovernanceNotPermittedException`
  when the member's folded role ≠ `ADMIN`, including unknown members) and `private boolean isOnlyAdmin(MemberId)`
  (the atomic last-Admin check off `rolesByMember`). A mutation on an already-`deleted` household is rejected
  fail-fast (`GovernanceNotPermittedException` or a dedicated guard) as **defense-in-depth** — note it is
  largely unreachable because the ACL mappings are already de-linked (403 at the seam). Keep `apply(...)`'s
  `default -> throw` intact.
  - [ ] `HouseholdTest` cases (one focus each): leave-happy · leave-last-admin-blocked · leave-non-member-noop ·
    remove-happy(+role gone) · remove-by-participant-403 · remove-last-admin-blocked · remove-non-member-noop ·
    promote-happy · promote-already-admin-noop · promote-by-participant-403 · demote-happy · demote-only-admin-blocked ·
    demote-already-participant-noop · revoke-pending-happy · revoke-by-participant-403 · revoke-absent-404 ·
    revoke-already-revoked-noop · revoke-accepted-404 · delete-happy · delete-by-participant-403 ·
    delete-already-deleted-noop.

### Backend — application (commands + handlers + exceptions, CLAUDE.md §8: DTO beside handler)

- [ ] **T11. Application exceptions + `WriteErrorAdvice` mappings** (AC2, AC5) —
  `LastAdminApplicationException` (**409**), `GovernanceNotPermittedApplicationException` (**403**), each with
  `errorDescriptor()` + a distinct client-facing `code` (e.g. `membership.lastAdmin`, `governance.notPermitted`).
  One `@ExceptionHandler` per type in `WriteErrorAdvice`. Reuse existing `InviteNotFoundApplicationException`
  (404) for revoke-absent and `NotAHouseholdMemberApplicationException` (403) for unknown-target. Every new
  domain exception **must** map (a missing map = 500 — 4.1/4.2 review scar).
- [ ] **T12. `LeaveHousehold` + `LeaveHouseholdHandler`** (AC3) — command
  `(HouseholdId, CommandId, AggregateVersion basedOnVersion)`. Handler injects `EventStore`,
  `ResolveMemberIdentity`, **`RetractMembership`** (T18): resolve caller (`NotAMemberException` → 403) →
  rehydrate → `leaveHousehold` → **append** → **`retractMembership.retractMember(householdId, callerMemberId)`**
  (de-link, after append). `void` return; the client re-bootstraps to re-route.
- [ ] **T13. `RemoveMember` + `RemoveMemberHandler`** (AC4) — command
  `(HouseholdId, MemberId targetMemberId, CommandId, AggregateVersion)`. Resolve caller → rehydrate →
  `removeMember(caller, target)` → append → **de-link the TARGET's** mapping
  (`retractMembership.retractMember(householdId, target)`) after append. A convergent no-op (empty
  `uncommittedEvents`) still returns success and de-links nothing new (idempotent).
- [ ] **T14. `PromoteMember` + `PromoteMemberHandler`, `DemoteMember` + `DemoteMemberHandler`** (AC4) — same
  command shape as remove. Resolve caller → rehydrate → domain call → append. **No ACL de-link** (role change,
  not a membership removal).
- [ ] **T15. `DeleteHousehold` + `DeleteHouseholdHandler`** (AC7) — command `(HouseholdId, CommandId,
  AggregateVersion)`. Resolve caller → rehydrate → `deleteHousehold(caller)` → append →
  **`retractMembership.retractHousehold(householdId)`** (de-link *all* mappings) after append. Read-model
  purge is the projectors' job (T17), not the handler's.
- [ ] **T16. `RevokeInvite` + `RevokeInviteHandler`** (AC6) — command `(HouseholdId, InviteId, CommandId,
  AggregateVersion)`. Handler injects `EventStore`, `ResolveMemberIdentity`, **`InviteEmailSideStore`**:
  resolve caller → rehydrate → `revokeInvite(caller, inviteId)` → append → **`inviteEmailSideStore.purge(inviteId)`**
  after append (AD-6 — REVOKED is a side-store purge point, §2a). A no-op revoke (already REVOKED) still
  purges idempotently.
  - [ ] `*HandlerTest` for each (fast, in-memory doubles): assert the appended event(s), the ACL de-link
    (assert the mapping repo has **no** row for the removed member / no rows for a deleted household / row
    removed for a leaver), and — for a **rejected** governance call (403/409/404) — assert **no** append and
    **no** de-link occurred (the 4.2 F1 discipline: a rejected command must leave no side effect). Revoke:
    assert purge-after-append.

### Backend — Identity ACL de-link seam (state-model §5/§6, AD-7 — locked decision 3)

- [ ] **T17. `MemberMappingRepository` de-link methods** — add `deleteMappingByMember(HouseholdId, MemberId)`
  and `deleteAllMappings(HouseholdId)` to the domain port; implement in `JdbcMemberMappingRepository`
  (`DELETE ... WHERE household_id = ? AND member_id = ?` / `WHERE household_id = ?`, both idempotent) and
  `InMemoryMemberMappingRepository` (the test double). Javadoc: these are the **governance de-link** (a member
  removed/left, or a household deleted), the AD-7 mechanism that revokes access — distinct from `deleteMapping`'s
  join-failure compensation.
  - [ ] `Jdbc*RepositoryTest`/`InMemory*RepositoryTest` — delete-by-member removes only that row (two-member
    isolation); delete-all removes every row for the household and none of another's; both idempotent.
- [ ] **T18. `RetractMembership` published ACL application port** — new
  `de.sgart.identity.application.RetractMembership` with `retractMember(HouseholdId, MemberId)` and
  `retractHousehold(HouseholdId)`, delegating to T17. This is the **published** cross-context seam the
  Collaboration governance handlers call (AD-2 — they never touch `identity.domain` or the mapping table
  directly). Wire the bean in `IdentityBeansConfig`; inject into the four governance handlers via
  `CollaborationApplicationConfig`.
  - [ ] `RetractMembershipTest` — delegates member/household retraction to the repo (verify the exact repo
    calls with a fake).

### Backend — read model, projector, cascade purge (AD-4; Epic-2 Action 4)

- [ ] **T19. `household_member_read_model` migration** (AC8) — **V14** (next free number confirmed): table
  `(household_id VARCHAR, member_id VARCHAR, role VARCHAR(20), PRIMARY KEY (household_id, member_id))`. **No
  PII column** — the `NoPersistedPersonalDataTest` guard must stay green (add the table to its allow-list of
  no-PII read-model tables if that test enumerates tables).
- [ ] **T20. `HouseholdMemberReadModel` port + `JdbcHouseholdMemberReadModel`** (AC8) — `domain/readmodel/HouseholdMemberReadModel.java`
  with `upsert(householdId, memberId, role)`, `remove(householdId, memberId)`, `purgeHousehold(householdId)`,
  and a read `membersOf(householdId) -> List<MemberRoleView>` (`(memberId, role)`; **no PII**). Jdbc adapter
  mirrors `JdbcInviteReadModel`.
- [ ] **T21. Projector cases in `HouseholdReadModelProjector`** (AC8, AC7) — fold into
  `household_member_read_model`: `MemberJoined -> upsert(role)`, `MemberPromoted -> upsert(ADMIN)`,
  `MemberDemoted -> upsert(PARTICIPANT)`, `MemberLeft`/`MemberRemoved -> remove(memberId)`. Register the new
  events in the codec first (T24). Rides the **existing** all-stream subscription — do **not** add another.
  - [ ] `HouseholdReadModelProjectorTest` (Testcontainers) — join→member row; promote→role flips to ADMIN;
    demote→PARTICIPANT; leave/remove→row gone; **two-household isolation** + **replay idempotency**.
- [ ] **T22. Delete cascade — read-side purge** (AC7, decision 4) — add a `case HouseholdDeleted -> purge...`
  to **each** of the three collaboration projectors, purging that projector's `householdId`-keyed rows via a
  new `purgeHousehold(HouseholdId)` on each read-model port:
  - `HouseholdReadModelProjector` → household name, store, invite, **member** read models.
  - `ShoppingListReadModelProjector` → shopping-list, item, item-suggestion, item-store-assignment read models.
  - `ShoppingTripReadModelProjector` → trip read model (+ trip-store rows).
  - Each `purgeHousehold` is an idempotent `DELETE ... WHERE household_id = ?` (re-projecting `HouseholdDeleted`
    is a no-op). Streams are untouched (decision 4).
  - [ ] Projector tests (Testcontainers) — after `HouseholdDeleted`, every read model for that household is
    empty and **another household's rows are untouched** (isolation); re-projecting the delete is a no-op.
- [ ] **T23. `ListHouseholdMembers` query** (AC8) — `application/query/ListHouseholdMembers.forHousehold(String
  keycloakUserId, String householdId)`: `resolveMemberIdentity.resolve` (403 non-member) → `membersOf(...)` →
  map to a view carrying `memberId`, `role`, and `isSelf = memberId.equals(callerMemberId)`. Wire the bean.
  - [ ] `ListHouseholdMembersTest` — returns members with roles + the caller flagged `isSelf`; a non-member
    caller → `NotAMemberException`.

### Backend — codec + controllers

- [ ] **T24. Register the six events in `DomainEventJsonCodec`** (AC3–AC7) — stable string tags
  (`"MemberLeft"`, `"MemberRemoved"`, `"MemberPromoted"`, `"MemberDemoted"`, `"HouseholdDeleted"`,
  `"InviteRevoked"`), `toJsonBytes`/`fromJsonBytes` cases, and payload records (ids/roles only).
  - [ ] `DomainEventJsonCodecTest` — round-trip each; **assert each payload has no `email`/`hmac`/`keycloak`
    component** (the privacy round-trip guard, matching the 4.1/4.2 guards).
- [ ] **T25. `MemberController`** (AC2, AC3, AC4, AC8) — new `adapter/in/MemberController` under
  `/api/v1/households/{householdId}/members` (mirror `InviteController`; caller from JWT only, AR10/AD-5):
  - `GET` → `ListHouseholdMembers` (roster; **no PII** in the response DTO).
  - `DELETE /me` → `LeaveHousehold` (self-leave — the client need not know its own `MemberId`).
  - `DELETE /{memberId}` → `RemoveMember` (Admin removes another).
  - `POST /{memberId}/promote` → `PromoteMember`; `POST /{memberId}/demote` → `DemoteMember`.
  - Command envelopes carry the client-generated `commandId` in the body; no response bodies on the mutations.
  - [ ] `MemberControllerTest` (`@WebMvcTest`) — roster 200 (no PII); leave 200/204; remove 200/204 · 403
    (participant) · 409 (last admin); promote/demote 200 · 403; **no email/name in any request/response**.
- [ ] **T26. `HouseholdController` delete + `InviteController` revoke** (AC6, AC7) — add
  `@DeleteMapping("/{householdId}")` → `DeleteHousehold` on `HouseholdController`; add
  `@DeleteMapping("/{inviteId}")` → `RevokeInvite` on `InviteController`. Client-generated `commandId` in the
  body; no response bodies.
  - [ ] `HouseholdControllerTest` — delete 200/204 (admin) · 403 (participant). `InviteControllerTest` —
    revoke 200/204 (admin) · 403 (participant) · 404 (absent/non-pending).

### Backend — privacy guarantees (first-class, §6) & regression guard

- [ ] **T27. No-PII guarantees** (AC8) — extend the codec no-PII guard (T24) and add
  `assertNoPersonalDataComponent(...)` for the six new events; confirm `household_member_read_model` adds no
  PII column so `NoPersistedPersonalDataTest` stays green. Assert the member roster API DTO carries no
  email/name.
- [ ] **T28. AC1 regression guard** — a test asserting a **Participant** can still add a store, create a list,
  and send an invite (daily commands stay membership-gated, not role-gated) — no governance gate leaked onto
  a daily command. (Place with the relevant handler tests or a focused `HouseholdTest` case.)

### Client — Flutter (member management, roles, governance)

- [ ] **T29. `MembersApi`** (AC9) — `features/members/data/members_api.dart`:
  `listMembers(householdId)`, `leave(householdId, {commandId})`, `removeMember(householdId, memberId, {commandId})`,
  `promote(...)`, `demote(...)`, plus `deleteHousehold(householdId, {commandId})` and
  `revokeInvite(householdId, inviteId, {commandId})` (the last two may live in the existing `households`/`invites`
  data layer if that reads cleaner — keep one API per resource). Mirror `InvitesApi.acceptInvite`'s envelope
  shape (caller-generated `commandId`, no response body on mutations). A `MemberView(memberId, role, isSelf)` model.
  - [ ] `members_api_test.dart` — request shapes (paths + bodies) for each endpoint.
- [ ] **T30. `MembersCubit` / `MembersState`** (AC9) — loads the roster; exposes leave/remove/promote/demote/
  delete/revoke intents, each via `command_intent.dart` (one `commandId` per intent, regenerate on payload
  change / after success — Epic-1 Action) with an `isSubmitting` guard (Epic-2 Action 3). Surfaces `403`
  (`governance.notPermitted`), `409` (`membership.lastAdmin`), `404` (`invite.notFound`) as distinct inline
  errors via `error_message_resolver.dart`. On a successful **leave / removal-of-self / delete**, emits a signal
  the screen uses to call `HouseholdsCubit.bootstrap()` and re-route.
  - [ ] `members_cubit_test.dart` — roster load; each governance action success; last-admin 409 → error;
    participant 403 → error; `isSubmitting` guard; `commandId` regenerated after success.
- [ ] **T31. Member-management screen** (AC9) — `features/members/presentation/members_page.dart`: the roster
  (caller row „Sie", others by role); **Admin** sees per-member promote/demote/remove (with confirm dialogs),
  a **revoke** action on each pending invite, and a **delete-household** action behind a **hard confirm**
  (destructive dialog — e.g. type-the-household-name or an explicit two-step confirm, „hard-to-mis-trigger"
  per AC7). **Participant** sees only „Haushalt verlassen". Provide APIs/cubits across the route boundary the
  by-value way (`create_or_await_choice_page.dart` precedent — the `ProviderNotFoundException` lesson). Widget
  keys on every actionable control. Wire an entry point into the existing household settings/switcher surface
  (locate it — Story 1.7 switch/rename lives in the `households` feature; add a „Mitglieder verwalten" entry).
  - [ ] `members_page_test.dart` — Admin sees governance controls + can promote/remove (routes/refreshes on
    self-leave & delete); Participant sees only leave; last-admin error shown inline; hard-confirm required
    before delete fires (no accidental delete on a single tap).
- [ ] **T32. Localization + a11y** (AC9) — German ARB keys (`app_de.arb`) for the screen title, role labels,
  „Sie", the governance actions, the confirm dialogs (incl. the hard delete-household confirm), and the
  error messages (last-admin / governance-forbidden / not-found); `Semantics`/labels on the new controls;
  `flutter analyze` clean. No stale strings (Epic-3 Action 4).

### Definition of Done (standing, per retros)

- [ ] Full suites green **and named**: backend `./gradlew test` (incl. ArchUnit `HexagonalArchitectureTest` +
  Testcontainers + `NoPersistedPersonalDataTest`) **and** `flutter test` + `flutter analyze` (CLAUDE.md §6;
  backend-test-hygiene). Report which suite ran and the counts.
- [ ] Every new application exception mapped in `WriteErrorAdvice` (a missing map = 500 — 4.1/4.2 scar);
  `commandId` lifecycle correct; a11y labels on new widgets; no dead code/strings/stale comments.
- [ ] **ACL de-link verified end-to-end** (locked decision 3): a removed/left member no longer resolves into
  the household and it drops from their switcher; a deleted household de-links all mappings — proven in the
  handler tests (mapping-repo assertions), not left for review. This is the story's highest-risk guarantee.
- [ ] Each new/changed read model ships its two-household isolation + replay/idempotency projector test **in
  this PR** (Epic-2 Action 4) — incl. the delete-cascade purge across all three projectors.
- [ ] Ordering-guarantee tests actually detect order (append-before-de-link, append-before-purge) — record
  call order, not just call count (the 4.1 fake-counter scar).
- [ ] Every `[x]` task has its Test-Manifest test actually present (Epic-3 Action 2).

## Dev Notes

### Ground truth — read these before coding
- **Membership state model (authoritative vocabulary):**
  `_bmad-output/planning-artifacts/epic-4-membership-state-model.md` — §1 the exact event/command names
  (use verbatim, AD-11), §2b the membership state machine, §3 the invariants (last-Admin §3.1, governance
  gate §3.2, convergent no-ops §3.5), §4 cross-context flow, §5 read models (the `household_member_read_model`
  row), §6 GDPR/de-linking, **§7 the 4.3 row this story implements**, **§8 the three open sub-decisions —
  all now decided in Locked Decisions above** (cascade = §8.1 read-side purge; revoke = §8.3 Admin-only).
- **Architecture spine:** `.../architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md` — AD-2
  (cross-context calls go through published application ports — `RetractMembership` is one), AD-4
  (command→event, projection-only read models, expected-version), **AD-5 (`MemberId` pseudonym, Identity ACL
  owns the mapping)**, **AD-7 (erasure/removal is de-linking, never history rewrite — the model for the ACL
  de-link)**, AD-8 (commandId idempotency / convergent no-ops), AD-10 (Invite is an entity of `Household`),
  AD-11 (ubiquitous language). Also `ADR-0001` (crypto-shredding for erasable PII) — context for why streams
  are left intact and de-linking is the removal mechanism.

### The crux — why removal MUST de-link the ACL mapping (read this twice)
`ListHouseholdsForCaller.forCaller` (switcher) and `ResolveMemberIdentity.resolve` (every scoped
command/query gate) derive household membership **solely** from the `identity_member_mapping` table —
**there is no event-stream cross-check** (verified in-source). Therefore:
- Raising `MemberRemoved`/`MemberLeft`/`HouseholdDeleted` on the stream updates the *aggregate's*
  `rolesByMember` and the *read model*, but **does nothing to access**. A removed member whose mapping row
  survives is a **durable ghost member** — full read access, still in their switcher. This is the identical
  consequence class to Story 4.2's F1 HIGH finding.
- The fix is the **synchronous ACL de-link** (locked decision 3), handler-driven **after** the append,
  through the published `RetractMembership` port. `RemoveMember` de-links the **target**; `LeaveHousehold`
  de-links the **caller**; `DeleteHousehold` de-links **all** mappings for the household.
- **Ordering (append-then-de-link):** append first so a lost append never revokes access prematurely; de-link
  second — an append-ok/de-link-fail leaves the member on-stream but still mapped (access retained, benign,
  self-corrects), the fail-safe direction. Never de-link before append.
- **Reject paths de-link nothing** (mirrors 4.2 F1 discipline): a 403/409/404 governance rejection throws
  before append, so no mapping is ever touched for a rejected caller. Assert this in the handler tests.

### Patterns to mirror (exact files)
- **Aggregate governance methods** ← `Household.rename` (the Admin-only `requireAdmin` gate template) and
  `Household.archiveStore` / `acceptInvite` (the convergent-no-op branch template). Add `requireAdmin` beside
  the existing `requireMember`; the last-Admin check reads the folded `rolesByMember` (only the aggregate can
  guard it atomically — §3.1).
- **Handler shape + post-append side-effect** ← `application/command/AcceptInviteHandler.java` (rehydrate →
  `loadedVersion` → domain call → `append` → **side-effect after append**; here the side-effect is the ACL
  de-link / side-store purge instead of a purge). `RevokeInviteHandler`'s purge is exactly `AcceptInviteHandler`'s
  purge-after-append.
- **Resolve-then-command** ← `RenameHouseholdHandler` / `InvitePersonHandler` (resolve the caller's `MemberId`
  via `ResolveMemberIdentity` — 403 if not a member — then call the aggregate).
- **ACL port + repo** ← `identity/application/IssueMemberIdentity.java` (published-port shape; it already has a
  `retract`/`deleteMapping` compensation — `RetractMembership` is its governance sibling) +
  `identity/domain/MemberMappingRepository.java` (add the two delete methods) + `JdbcMemberMappingRepository` /
  `InMemoryMemberMappingRepository`.
- **Read model + projector** ← `adapter/out/JdbcInviteReadModel.java` (`markAccepted`/`markExpired` idempotent
  flips → the `HouseholdMemberReadModel` upsert/remove/purge template) + `HouseholdReadModelProjector.java`
  (add the `MemberJoined`/promote/demote/leave/remove/`HouseholdDeleted` cases; it already subscribes to all
  streams and already has a `MemberJoined -> addMember` case for the *household* member set — the new member
  **read model** is a distinct table, do not conflate).
- **Codec** ← `adapter/out/DomainEventJsonCodec.java` (the 4.1/4.2 `MemberInvited`/`InviteAccepted` additions
  are the template for all six new events).
- **Error advice** ← `adapter/in/WriteErrorAdvice.java` (one `@ExceptionHandler` per application exception;
  409 `LastAdmin`, 403 `GovernanceNotPermitted`).
- **Controllers** ← `adapter/in/InviteController.java` (nested-under-household resource controller with JWT-only
  caller + client-generated `commandId`) — `MemberController` mirrors it exactly.
- **Client** ← `features/invites/data/invites_api.dart` + `presentation/accept_invite_cubit.dart` (the
  API + cubit + `command_intent` + error-resolver pattern), `create_or_await_choice_page.dart` (by-value
  re-provide across a pushed route), `households_cubit.dart` (`bootstrap()` to re-route after leave/delete),
  `shared/errors/error_message_resolver.dart` (map the new codes).

### Delete cascade specifics (AC7, decision 4)
- **Two mechanisms, distinct roles:** (1) the **handler** de-links *all* ACL mappings (`retractHousehold`) —
  this is the *access* "stop serving": the household instantly 403s every scoped query and drops from every
  switcher; (2) the **projectors** purge the *read-model* rows (`purgeHousehold` per port) — storage cleanup so
  no stale rows linger. Read-model purge is eventually-consistent and that is fine, because access is already
  cut by (1). **Streams are not touched** (Epic 6 / AD-7 owns deep erasure — do not hand-roll stream deletion).
- **Every `householdId`-keyed read model must be purged** — enumerate them so none is missed: household name,
  store, shopping-list, item, item-suggestion, item-store-assignment, trip, invite, and the new member read
  model. All live in the `collaboration` context and are reached by the three existing projectors, so **no
  cross-context reach** is needed (respects bounded-context boundaries).

### GDPR specifics (§6, AD-6/AD-7)
- **Removal/leave/delete = de-linking, never history rewrite** (AD-7): the ACL mapping row is deleted; the
  pseudonymous `MemberId` in past events becomes unresolvable. This is the model's prescribed erasure
  mechanism, exercised here for the first time by governance (Epic 6 generalizes it to full account erasure).
- **`RevokeInvite` purges the raw-email side-store** (AD-6) — REVOKED is one of the four purge points the
  side-store was built for in 4.1 (accept/revoke/expire/erasure). This story fires the **revoke** one for the
  first time.
- **The member read model carries no PII** (AD-6, decision 5) — `(householdId, memberId, role)` only. The
  roster shows the caller as „Sie" and others by role; **no name/email seam** (YAGNI). `NoPersistedPersonalDataTest`
  must stay green.

### Previous-work intelligence (Stories 4.1 / 4.2 — read their Dev Agent Records & Review Findings)
- **4.2's F1 saga is the direct precedent for this story's crux:** a durable ACL mapping = durable household
  access, and a mapping written/left for a caller who should not have access is a HIGH GDPR leak. 4.2 solved
  the *write* side (provision/persist/retract, persist-on-success). 4.3 is the *delete* side — the same
  invariant, inverted: a mapping that should be **gone** must actually be **deleted**, synchronously, on the
  success path, or the removed member is a ghost. Apply the same rigor and the same test discipline (assert
  the mapping state, assert reject paths leave no side effect, assert ordering by recorded call order).
- **4.1/4.2 review scars to not repeat:** map **every** new exception in `WriteErrorAdvice`; ship each read
  model's isolation + replay projector test **in this PR**; make ordering tests actually detect order (record
  call order, not call count); no raw English enum/role in the German UI; no stale ARB/javadoc left behind.
- **`fromStart`-replay projector debt** (Epic-3 retro §7) stays deferred to Story 4.4 (SSE) — do not add
  checkpointing here. Re-projection idempotency (upsert/delete-by-key) is the correctness lever, and this
  story's purges/upserts are all idempotent.
- **Deferred item to check:** `deferred-work.md` holds the 4.2 „provision/persist non-atomic same-user 500"
  note — **out of scope here** (not re-opened by 4.3); do not attempt to fix it in this story.

### Scope guards (what 4.3 does NOT do — YAGNI/KISS, CLAUDE.md §1)
- **No display-name / Keycloak name-resolution seam** (decision 5) — pseudonymous roster only.
- **No deep per-stream deletion** (decision 4) — read-side purge + de-link only; deep erasure is Epic 6.
- **No SSE/live propagation** of governance changes — Story 4.4. A demoted/removed member's *open* session
  reconciles on next request (their next scoped call 403s once de-linked); real-time push is 4.4's concern.
- **No web-fallback / deep-link** work — Story 4.6.

## Test Manifest (task → named test)

| Task | Test(s) |
|---|---|
| T4–T10 | `HouseholdTest`: leave-happy · leave-last-admin-blocked · leave-non-member-noop · remove-happy · remove-by-participant-403 · remove-last-admin-blocked · remove-non-member-noop · promote-happy · promote-already-admin-noop · promote-by-participant-403 · demote-happy · demote-only-admin-blocked · demote-already-participant-noop · revoke-pending-happy · revoke-by-participant-403 · revoke-absent-404 · revoke-already-revoked-noop · revoke-accepted-404 · delete-happy · delete-by-participant-403 · delete-already-deleted-noop |
| T12 | `LeaveHouseholdHandlerTest` (append MemberLeft + de-link caller mapping · last-admin 409 → no append/no de-link · non-member no-op) |
| T13 | `RemoveMemberHandlerTest` (append MemberRemoved + de-link **target** mapping · participant 403 → no side effect · last-admin 409 → no side effect · non-member no-op · de-link-after-append order) |
| T14 | `PromoteMemberHandlerTest` / `DemoteMemberHandlerTest` (append + role flip, no de-link · 403 · demote-last-admin 409 · convergent no-ops) |
| T15 | `DeleteHouseholdHandlerTest` (append HouseholdDeleted + de-link **all** mappings · participant 403 → no side effect · already-deleted no-op) |
| T16 | `RevokeInviteHandlerTest` (append InviteRevoked + purge side-store after append · participant 403 → no side effect · absent 404 · already-revoked no-op-still-purges) |
| T17 | `Jdbc/InMemoryMemberMappingRepositoryTest` (delete-by-member isolation · delete-all-for-household isolation · both idempotent) |
| T18 | `RetractMembershipTest` (delegates member + household retraction to the repo) |
| T21 | `HouseholdReadModelProjectorTest` (join→member row · promote→ADMIN · demote→PARTICIPANT · leave/remove→row gone · two-household isolation · replay idempotency) |
| T22 | Projector tests ×3 (`HouseholdDeleted` purges every householdId-keyed read model · other household untouched · replay no-op) |
| T23 | `ListHouseholdMembersTest` (members+roles+isSelf · non-member → NotAMember/403) |
| T24 | `DomainEventJsonCodecTest` (round-trip all 6 events · no email/hmac/keycloak payload component) |
| T25 | `MemberControllerTest` (roster 200 no-PII · leave · remove 403/409 · promote/demote 403 · no PII in req/resp) |
| T26 | `HouseholdControllerTest` (delete 403 admin-gate) · `InviteControllerTest` (revoke 403/404) |
| T27 | `HouseholdTest`/`NoPersistedPersonalDataTest` (`assertNoPersonalDataComponent` for the 6 events · member table adds no PII column · roster DTO no-PII) |
| T28 | AC1 regression (a Participant can still addStore / createList / invite) |
| T29 | `members_api_test.dart` (request shapes: list/leave/remove/promote/demote/delete/revoke) |
| T30 | `members_cubit_test.dart` (roster load · each action success · last-admin 409 · participant 403 · isSubmitting · commandId-regen) |
| T31 | `members_page_test.dart` (Admin governance controls + promote/remove routes on self-leave/delete · Participant leave-only · last-admin inline error · hard-confirm gates delete) |

## Project Structure Notes

- **New backend files** under `de.sgart.collaboration`: `domain/event/{MemberLeft,MemberRemoved,MemberPromoted,
  MemberDemoted,HouseholdDeleted,InviteRevoked}.java`; `domain/exception/{LastAdmin,GovernanceNotPermitted}Exception.java`;
  `application/command/{LeaveHousehold,RemoveMember,PromoteMember,DemoteMember,DeleteHousehold,RevokeInvite}.java`
  each **beside** its `*Handler.java` (CLAUDE.md §8); `application/exception/{LastAdmin,GovernanceNotPermitted}ApplicationException.java`;
  `application/query/ListHouseholdMembers.java`; `domain/readmodel/HouseholdMemberReadModel.java`;
  `adapter/in/MemberController.java`; `adapter/out/JdbcHouseholdMemberReadModel.java`.
- **New backend files** under `de.sgart.identity`: `application/RetractMembership.java`.
- **New migration:** `V14__household_member_read_model.sql` (next free number — V13 is the last existing).
- **Modified backend files:** `domain/Household.java` (6 command methods + `requireAdmin` + `isOnlyAdmin` +
  `InviteStatus.REVOKED` + `deleted` flag + 6 `apply` cases + javadoc), `adapter/in/{HouseholdController,
  InviteController}.java` (delete + revoke endpoints), `adapter/in/WriteErrorAdvice.java` (2 mappings),
  `adapter/out/DomainEventJsonCodec.java` (6 tags+payloads), `adapter/out/HouseholdReadModelProjector.java`
  (member cases + delete cascade), `adapter/out/ShoppingListReadModelProjector.java` +
  `adapter/out/ShoppingTripReadModelProjector.java` (delete cascade), the read-model ports/adapters gaining
  `purgeHousehold`, `identity/domain/MemberMappingRepository.java` + its two adapters (`Jdbc`/`InMemory`),
  `identity/adapter/out/IdentityBeansConfig.java` (`RetractMembership` bean),
  `adapter/out/CollaborationApplicationConfig.java` (6 handler beans + `ListHouseholdMembers` + inject
  `RetractMembership`).
- **New/modified client files:** `features/members/data/members_api.dart` (+ model),
  `features/members/presentation/{members_cubit,members_state,members_page}.dart`,
  entry point in the existing `households` settings/switcher surface, `shared/errors/error_message_resolver.dart`
  (new codes), `l10n/app_de.arb` (+ generated localizations), and the mirrored `app/test/...` files.
- **ArchUnit:** new classes fall under `..domain..`/`..application..`/`adapter.in`/`adapter.out` and obey
  `HexagonalArchitectureTest` unchanged — the domain methods throw domain exceptions; `adapter.in` translates
  at the handler seam (never imports `..domain..`); the ACL de-link is reached through the published
  `RetractMembership` port (AD-2), never the mapping table directly.

## References

- [Source: `_bmad-output/planning-artifacts/epic-4-membership-state-model.md` §1, §2b, §3.1, §3.2, §3.5, §4, §5, §6, §7, §8]
- [Source: `_bmad-output/planning-artifacts/epics.md#Story 4.3: Membership roles & governance`]
- [Source: `.../architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md#AD-2, AD-4, AD-5, AD-7, AD-8, AD-10, AD-11`; `ADR-0001` crypto-shredding]
- [Source: `_bmad-output/implementation-artifacts/4-2-accept-an-invite-and-join.md` (F1 saga — the mapping=access precedent; Dev Agent Record; Review Findings)]
- [Source: `_bmad-output/implementation-artifacts/4-1-invite-a-person-by-email.md` (invite entity, side-store, WriteErrorAdvice mappings)]
- [Source: `CLAUDE.md#1 Clean Code`, `#5 DSGVO/GDPR`, `#6 Testing`, `#8 Package Structure`]
- [Source: `backend/.../collaboration/domain/Household.java` (`rename`/`requireMember`/`acceptInvite`/`apply`/`InviteState`),
  `application/command/{AcceptInvite,RenameHousehold,InvitePerson}Handler.java`,
  `adapter/in/{HouseholdController,InviteController,WriteErrorAdvice}.java`,
  `adapter/out/{DomainEventJsonCodec,HouseholdReadModelProjector,ShoppingListReadModelProjector,ShoppingTripReadModelProjector,JdbcInviteReadModel,CollaborationApplicationConfig}.java`,
  `identity/application/{IssueMemberIdentity,ResolveMemberIdentity,ListHouseholdsForCaller}.java`,
  `identity/domain/MemberMappingRepository.java`, `identity/adapter/out/{Jdbc,InMemory}MemberMappingRepository.java`, `identity/adapter/out/IdentityBeansConfig.java`]
- [Source: `app/lib/features/invites/{data/invites_api.dart,presentation/accept_invite_cubit.dart}`,
  `app/lib/features/households/presentation/{create_or_await_choice_page,households_cubit}.dart`,
  `app/lib/shared/commands/command_intent.dart`, `app/lib/shared/errors/error_message_resolver.dart`, `app/lib/l10n/app_de.arb`]

## Questions for Timo (non-blocking — sensible defaults chosen; the four big ones are already locked above)

1. **`RetractMembership` as a new port vs. extending `IssueMemberIdentity`.** Default = **a new
   `RetractMembership` application service** (governance de-link reads as its own use case, distinct from
   issue's join-failure compensation). Prefer folding `retractMember`/`retractHousehold` onto the existing
   `IssueMemberIdentity` (it already has a `retract`)? Say so and it moves there.
2. **Hard-confirm UX for delete-household.** Default = a **type-the-household-name-to-confirm** destructive
   dialog (strongest "hard-to-mis-trigger" per AC7). Prefer a simpler two-step „Wirklich löschen?" confirm?
   Either satisfies the AC; say which.
3. **Leave endpoint shape.** Default = `DELETE /api/v1/households/{householdId}/members/me` (self-leave without
   the client needing its own `MemberId`) alongside `DELETE .../members/{memberId}` for admin-remove. Prefer a
   dedicated `POST .../leave`? Cosmetic; say the word.
4. **`RemoveMember` targeting self.** Default = **allowed** (behaves like leave, still subject to the last-Admin
   guard) — the domain does not special-case `target == caller`. Prefer to reject self-targeted `RemoveMember`
   and force the leave path? Say so.

## Dev Agent Record

### Agent Model Used

_(to be filled by the dev agent)_

### Debug Log References

### Completion Notes List

### File List
