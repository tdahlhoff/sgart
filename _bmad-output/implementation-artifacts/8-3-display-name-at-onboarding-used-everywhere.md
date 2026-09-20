---
title: 'Story 8.3: Choose a per-household nickname at onboarding, shown throughout the household'
type: 'feature'
created: '2026-09-20'
status: 'ready-for-dev'
route: 'dispatch'
review_loop_iteration: 0
context: []
baseline_commit: '4bd2ce6'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** After silent provisioning a person has no human name. The Profile header renders the
raw device-credential id (`9y8erzyb-jlr0gugblyd5_…`) as the "name" because `displayName` is read
live from the JWT `name` claim, which falls back to the cryptic `preferred_username` when no name is
set (manual test 2026-09-20, F3). There is nothing personal or legible about how a member appears —
and the member roster shows nothing but an opaque `MemberId`.

**What Timo wants (2026-09-20):** each person picks a **self-chosen nickname** — a step like „Wie
möchtest du genannt werden?" — and that nickname is what the household sees: in the Profile header,
in the member list, everywhere a person is named inside a household. This is a family/partners app;
a nickname shared inside a small circle of known people is **low-sensitivity, freely-chosen
pseudonymous data**, not the kind of PII the name-less roster (AD-6) was written to protect. So AD-6
is **consciously and explicitly relaxed for this one field** (see Boundaries).

**Locked decisions (Timo, 2026-09-20):**
- **Per-household membership**, not per-account. A person can be „Papa" in the family household and
  „Timo" in a flatshare. The nickname is tied to the **membership** `(keycloakUserId, householdId)`
  — exactly the identity ACL's `MemberMapping` key.
- **Stored in SGART's own identity context**, in a new mutable, clearly-named, erasable table —
  **never in domain events** (NFR2 preserved: events still carry only `MemberId`). Chosen over
  Keycloak so the roster resolves nicknames with a **local Postgres join** through the existing
  `MemberId ↔ keycloakUserId` ACL, with **no Keycloak Admin round-trips on the read path** and no
  admin creds required for reads.
- **Required at onboarding**, editable later from Profile, **no uniqueness** (two „Peter" in one
  household is the users' own problem to disambiguate — the app never blocks or auto-dedupes).
- **One coherent story** (not split): shipping only "your own name" would leave every *other*
  member showing a raw code in the roster — a visibly broken beta. Capture + storage + Profile edit
  + roster resolution ship together.

**Approach:** Add an identity-owned per-membership nickname (`membership_nickname`, keyed by
`(keycloakUserId, householdId)`, migration `V22`) with a single write port
`SetMembershipNickname` and a batch resolve port `ResolveMembershipNicknames(householdId, memberIds)
→ Map<MemberId, nickname>`. Expose `PUT /api/v1/identity/households/{householdId}/nickname` (own
nickname for a household the caller is a member of; keycloakUserId from the JWT `sub`; fail-fast
validation: non-blank/non-whitespace, length-bounded). The collaboration roster
(`GET /api/v1/households/{id}/members`) enriches each `MemberResponse` with the resolved nickname
via the new published identity port (the same cross-context ACL call pattern `MemberController`
already uses through `AuthenticatedCaller`/`ResolveMemberIdentity`). App-side: a **required**
nickname step in both onboarding entry points (create-household and join/await-invite), a Profile
edit for the active household's nickname, and nicknames rendered in the member list; the Profile
header switches from the JWT `displayName` to the active household's resolved nickname (JWT name kept
only as a fallback when unset).

## Boundaries & Constraints

**Always:**
- Nickname lives **only** in the new identity-context table (mutable, editable, erasable). Domain
  **events carry no nickname** — they keep carrying only `MemberId` (NFR2/AR5 intact).
- The nickname is resolved for the roster via a **published identity application port** (a plain
  `String keycloakUserId` / `HouseholdId` / `MemberId` signature — no `KeycloakUserId` type leaks
  across the context boundary, matching `ResolveMemberIdentity`/`IssueMemberIdentity`, AD-2).
- **Fail-fast validation** on the write path (server-side): reject a blank/whitespace-only or
  over-length nickname with a mapped application exception → localized `code`. The client validates
  the same rules before submit, but the server is the authority.
- `keycloakUserId` comes **only** from the JWT `sub` (AR10) — never from the request body/path. A
  person may set only **their own** nickname, and only for a household they are a member of.
- All user-facing strings resolve through the `de-DE` key-based localization layer; errors surface
  via localized copy keyed by `code`. Onboarding copy is plain-language German.
- The nickname is **personal data**: this story documents its purpose (in-household social display),
  lawful basis, retention (lives with the membership), and wires it into **erasure and export** —
  every nickname row for a `keycloakUserId` is locatable and deleted on account erasure, and a
  membership's nickname is removed when that membership is de-linked/erased.

**The explicit AD-6 exception (call it out, don't smuggle it):**
- AD-6 / decision-5 made the roster name-less "by construction," enforced by
  `NoPersistedPersonalDataTest`. This story **deliberately** persists one low-sensitivity personal
  datum (a self-chosen nickname) in a named identity table. Reconcile the test with a **documented,
  intentional exemption** for exactly this table (not a rename-to-dodge) — the exemption states the
  purpose, that it is not in events, and that it is erasable/exportable. Record the decision in the
  architecture spine / a short note; the `package-info.java` for the owning package states it too.

**Never:**
- No nickname in KurrentDB events, no nickname in any projection derived from events.
- No Keycloak `firstName`/attribute storage for the nickname, and **no Keycloak Admin lookups on
  the roster read path** (the whole point of storing it locally).
- No uniqueness constraint or duplicate-blocking; no auto-generated default that pretends to be a
  chosen name.
- No new product scope beyond naming members (Epic 8 = harden shipped behavior). In particular, do
  **not** build an activity feed — „<Name> hat Milch hinzugefügt" messages **do not exist in the app
  today**; that AC bullet is a forward-pointer, not work for this story (YAGNI). Note it in
  `deferred-work.md` so a future activity-feed story wires the name slot.
- Do not make the nickname a required field of the collaboration create/join **command** (it would
  ripple into the Story 4.6 web-fallback accept page, which cannot collect one). The requirement is
  enforced by the app onboarding gate + a dedicated identity write call after membership exists; the
  data layer tolerates an as-yet-unset nickname with a neutral fallback display.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Create-household onboarding | New person, no household, completes create form | Required nickname step gates completion; on create success the client `PUT`s the nickname for the new household; header + roster show it | Blank/whitespace/over-length → client blocks submit; server also rejects with localized `code` |
| Join-by-invite onboarding | New person accepts an invite | Same required nickname step gates the join; nickname set for the joined household | As above |
| Edit from Profile | Member opens Profile, changes their nickname for the active household | `PUT` updates that household's nickname; every surface in that household reflects it after refresh | Validation as above; failure keeps the old nickname and shows localized error |
| Same nickname as another member | Two members both choose „Peter" | Both saved; both shown „Peter" | No error — uniqueness is intentionally not enforced |
| Member roster render | Any member lists the household | Each row shows the resolved nickname (self and others); `isSelf` still marks the caller | A member with no nickname yet → neutral fallback (e.g. „Mitglied"), never the raw id |
| Nickname not yet set (e.g. web-fallback accept, or `PUT` failed) | Membership exists, no nickname row | Header/roster show the neutral fallback; Profile invites setting one | Never crashes; never shows the credential id |
| Two households, different nicknames | Person is „Papa" in H1, „Timo" in H2 | Each household shows its own nickname; switching active household switches the header nickname | — |
| Account erasure / membership de-link (Epic 6 wiring point) | A `keycloakUserId` erased, or a membership retracted | All nickname rows for that user (or that membership) are deleted | Erasure path provided + unit-tested here; Epic 6 invokes it |

</frozen-after-approval>

## Code Map

### Backend — identity context (owns the nickname)
- `backend/src/main/resources/db/migration/V22__membership_nickname.sql` -- new mutable table
  `membership_nickname (keycloak_user_id, household_id, nickname, updated_at)`, primary key
  `(keycloak_user_id, household_id)`, index on `keycloak_user_id` for erasure lookup. Clearly named
  personal-data table (the documented AD-6 exception).
- `backend/src/main/java/de/sgart/identity/domain/MembershipNickname.java` -- value object /
  aggregate row: `(KeycloakUserId, HouseholdId, nickname)` with the invariant (non-blank, trimmed,
  length-bounded) enforced in the constructor (fail-fast).
- `backend/src/main/java/de/sgart/identity/domain/MembershipNicknameRepository.java` -- port:
  `save`, `find(keycloakUserId, householdId)`, `resolveForHousehold(householdId, memberIds)` (joins
  `MemberMapping` to key by `MemberId`), `deleteFor(keycloakUserId)` (erasure),
  `deleteMapping(keycloakUserId, householdId)` (membership de-link).
- `backend/src/main/java/de/sgart/identity/application/SetMembershipNickname.java` -- write use
  case: validate + upsert the caller's nickname for a household they belong to (verify membership
  via `MemberMapping`); plain-`String` published signature (AD-2). Own application exception for
  invalid input.
- `backend/src/main/java/de/sgart/identity/application/ResolveMembershipNicknames.java` -- query use
  case: `(householdId, List<MemberId>) → Map<MemberId, String>` via the ACL join; the published,
  boundary-safe port the collaboration roster calls.
- `backend/src/main/java/de/sgart/identity/adapter/out/JdbcMembershipNicknameRepository.java` --
  Postgres adapter (mirrors `JdbcMemberMappingRepository`).
- `backend/src/main/java/de/sgart/identity/adapter/in/IdentityController.java` (or a new
  `NicknameController`) -- `PUT /api/v1/identity/households/{householdId}/nickname` taking
  `{ nickname }`, `keycloakUserId` from the JWT; maps the invalid-input exception to a localized
  `code`.
- `backend/src/main/java/de/sgart/identity/application/DeleteAccount.java` / erasure seam --
  extend so account erasure also deletes every `membership_nickname` row for the `keycloakUserId`
  (provide `deleteFor`; Epic 6 wires the trigger, mirroring the 7.4 `deleteFor` pattern).
- `backend/src/main/java/de/sgart/identity/application/RetractMembership.java` -- on de-link, also
  drop that membership's nickname row (keep erasure and de-link symmetric with `MemberMapping`).
- `package-info.java` for the owning identity package -- state the documented nickname/AD-6
  exception.

### Backend — collaboration context (roster read path)
- `backend/src/main/java/de/sgart/collaboration/adapter/in/MemberController.java` -- enrich
  `MemberResponse` with `nickname`: after `listHouseholdMembers.forHousehold(...)`, call
  `ResolveMembershipNicknames` for the roster's `MemberId`s and merge (fallback string when absent).
  No new domain state in collaboration; the nickname only passes through the read path.
- `backend/src/main/java/de/sgart/collaboration/adapter/in/*MemberResponse*` -- add the `nickname`
  field to the transport DTO.

### Backend — architecture tests
- `backend/src/test/java/de/sgart/identity/NoPersistedPersonalDataTest.java` -- add the
  **documented, intentional** exemption for `membership_nickname` / `MembershipNickname` (purpose +
  not-in-events + erasable), not a naming dodge.
- `HexagonalArchitectureTest` -- unaffected (`..domain..`/`..application..` patterns already match);
  confirm the new ports keep `adapter.in` free of `..domain..` imports.

### App — Flutter
- `app/lib/features/members/data/member_view.dart` + `members_api.dart` -- add `nickname` to
  `MemberView` / JSON parsing (the roster now returns it).
- `app/lib/features/members/presentation/members_page.dart` -- render the nickname per row (fallback
  when empty); keep the `isSelf` marker.
- `app/lib/features/settings/presentation/profile_screen.dart` -- `_IdentityHeader` shows the
  **active household's** nickname (not `authState.displayName`), with the JWT name as fallback; add a
  nickname edit affordance (mirrors the email-edit pattern) that `PUT`s and refreshes.
- Onboarding capture — a required „Wie möchtest du genannt werden?" step wired into **both** entry
  points:
  - `app/lib/features/households/presentation/create_household_cubit.dart` /
    `create_or_await_choice_page.dart` (create flow),
  - `app/lib/features/households/presentation/await_invite_page.dart` (join flow).
  Client-validates (non-blank/whitespace, length); on membership success, calls the nickname API.
- `app/lib/features/settings/data/…` (new `NicknameApi` or extend an identity/profile API) -- client
  for `PUT .../nickname`.
- `app/lib/l10n/*` -- German copy: the onboarding prompt, the Profile edit label, validation error
  messages, the roster fallback string. No hard-coded strings.
- `app/lib/features/auth/presentation/auth_state.dart` -- reference: `displayName` (from `/me`) is
  no longer the header's primary source; keep it only as the no-household fallback.

## Tasks & Acceptance

**Execution (TDD — write the failing test first for each slice):**
- [ ] Domain: `MembershipNickname` invariant tests (trims; rejects blank/whitespace/over-length);
  then the value object.
- [ ] Application: `SetMembershipNicknameTest` (upserts for a member; rejects a non-member; rejects
  invalid input) + `ResolveMembershipNicknamesTest` (resolves a mix of set/unset members via the
  ACL join) + erasure/de-link tests (`deleteFor` removes all rows for a user;
  `RetractMembership` drops the membership's nickname); then the use cases.
- [ ] Adapter.out: `JdbcMembershipNicknameRepositoryTest` (Testcontainers-Postgres, mirrors the
  member-mapping repo test); then the JDBC adapter + `V22` migration.
- [ ] Adapter.in: controller test for `PUT .../nickname` (own nickname set/updated; 4xx on invalid;
  identity from JWT only); then the endpoint.
- [ ] Collaboration: `MemberController` roster test asserting each row carries the resolved nickname
  (self + others) with the fallback for unset; then the enrichment.
- [ ] ArchUnit: add the documented `NoPersistedPersonalDataTest` exemption; confirm hexagonal rules
  still pass.
- [ ] App: `MemberView`/`members_api` parse-nickname tests; members page renders nickname/fallback;
  Profile shows + edits the active household's nickname; onboarding gate blocks create/join without
  a valid nickname (both entry points). Then the widgets/cubits.
- [ ] `deferred-work.md`: record the activity-feed name-slot as a forward-pointer (out of scope).
- [ ] Architecture note: record the AD-6 nickname exception in the spine / a short design note.

**Acceptance Criteria:**
- Given first-run onboarding (create **or** join), when a person sets up, then a **required**
  „Wie möchtest du genannt werden?" step captures a nickname (validated non-blank/whitespace,
  length-bounded) before they can proceed.
- Given a saved nickname, when it is stored, then it lives in the identity-context
  `membership_nickname` store keyed to `(keycloakUserId, householdId)` and **never** in domain
  events (NFR2/AR5), with a documented purpose and erasure/export coverage.
- Given any in-household surface that names a person — Profile header and member list — when it
  renders, then it shows that household's nickname resolved via the identity ACL (never the raw
  credential/`MemberId`); an unset nickname shows a neutral fallback, never the id.
- Given the Profile screen, when a person edits their nickname for the active household, then the new
  nickname applies everywhere it is shown in that household.
- Given a person in two households, when they view each, then each shows its own nickname
  independently.
- Given two members choosing the same nickname, when both are saved, then both are allowed (no
  uniqueness enforcement).
- Given account erasure or a membership de-link, when it runs, then the corresponding nickname
  row(s) are deleted (erasure path unit-tested here; Epic 6 invokes it).
- Given story completion, when the build is called green, then it names **both** suites: backend
  `./gradlew test` (incl. ArchUnit + Testcontainers) **and** the app's `flutter test` /
  `flutter analyze` (CLAUDE.md §6) — this is a full-stack story, so both are the meaningful gate;
  state which ran.

## Implementation Notes

_(filled during dev-story)_
