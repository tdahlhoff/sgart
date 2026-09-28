---
title: 'Story 8.4: Single replaceable household invite code'
type: 'feature'
created: '2026-09-28'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
context: []
baseline_commit: '959707e0bc3d940a0f86c688303dc798c7eb8a72'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Invites are an ever-growing list: any member can mint unlimited codes, each code is
single-use (accept flips it PENDING→ACCEPTED, a second joiner gets `invite.alreadyUsed`) and dies
after a 7-day TTL (`Invite.TIME_TO_LIVE`). Sharing one code with the whole family fails for the
second person, and the list only grows (manual test 2026-09-20, F7). Separately, the invite entry
point hides behind a generic „Haushalt verwalten" row nobody expects to contain it (F6).

**Locked decisions (Timo, 2026-09-20):** exactly **one active code per household**, reusable by any
number of people any number of times; **no TTL, no rotate-on-use** — it lives until manually
replaced. „Code ersetzen" invalidates the old code instantly and issues a new one atomically. A
shared reusable bearer code is a deliberately lower bar than per-person single-use codes — accepted
as sufficient for small known-people households, mitigated by manual replacement. Keep F6+F7 in one
story (Timo, 2026-09-28).

**Decided (Timo, 2026-09-28):**
- **Any Admin may replace the code** — not creator-only. Roles shipped in Epic 4, so F7's "until real
  roles land" premise no longer holds; the existing `requireAdmin` guard is reused, no creator
  tracking is added, and the last-admin invariant guarantees someone can always replace it.
- **Discoverability via a promoted row:** the household switcher sheet gets a direct
  „Mitglieder einladen" row beside „Haushalt verwalten" that opens the invite screen in one tap
  (the hub's own „Einladen" row stays).

**Approach:** The `Household` aggregate holds a single `activeInviteId` instead of the pending-invite
map. Household creation issues the first code in the same append; `replaceInviteCode` revokes the
old id and issues a new one in one append; `acceptInvite` checks the id equals the active one and
joins without consuming it. Retire TTL, expiry, consumption, the create-invite command and per-invite
revoke. The invites screen shows the one code (share/copy) plus „Code ersetzen" for Admins;
a promoted switcher row opens it in one tap.

## Boundaries & Constraints

**Always:** code/link wire format unchanged (`householdId:inviteId`, `?h=&i=`), so deep links and the
web-fallback page keep parsing; the accept endpoint path `POST .../invites/{inviteId}/accept` stays;
every member can view and share the code; replace is a CQRS command with a client-minted
`newInviteId` + `commandId` (idempotent retry, like the old create); the authority check lives in the
`Household` domain and surfaces as `governance.notPermitted` (403) via an application exception;
the UI hides „Code ersetzen" for non-Admins. Events stay PII-free (`MemberId` only).
No back-fill: the event store starts from zero (no real beta data).

**Never:** no TTL or expiry anywhere (aggregate, read-model query, UI copy); no per-invitee binding;
no list of codes; no unlimited create; no new Keycloak involvement.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Household created | create command | household has exactly one active code | — |
| Many joiners | 3 different people accept the active code | all 3 join; code unchanged | — |
| Re-accept by member | existing member accepts active code | convergent no-op, 200 | — |
| Replace | ADMIN caller, new id | old id no longer accepts, new id does; one append | — |
| Old code after replace | accept with replaced id | nothing joins | 404 `invite.notFound` („ungültig oder ersetzt") |
| Participant replaces | PARTICIPANT caller | nothing changes | 403 `governance.notPermitted` |
| Replace retried | same `commandId` twice | converges, still one active code | — |
| Web fallback accept | stale/replaced link | page shows „ungültig oder ersetzt" | no „abgelaufen"/„bereits verwendet" copy remains |

</frozen-after-approval>

## Code Map

Backend (`backend/src/main/java/de/sgart/collaboration/`):
- `domain/Household.java` -- `pendingInvitesById` (l.57), `InviteState`/`InviteStatus` (l.526-542),
  `invitePerson` (l.198), `acceptInvite` (l.231, consumes at l.255), `revokeInvite` (l.380,
  `requireAdmin` l.419), fold l.481-500, `create` (l.70). Replace with `activeInviteId`.
- `domain/Invite.java` -- only `TIME_TO_LIVE`; delete.
- `domain/event/` -- keep `MemberInvited` (issue) + `InviteRevoked` (invalidate); delete
  `InviteAccepted`, `InviteExpired`. `domain/exception/` -- delete `InviteExpiredException`,
  `InviteAlreadyConsumedException`.
- `adapter/out/DomainEventJsonCodec.java` -- invite tags l.92-95/129-132/271-290/515-545/661-669.
- `adapter/out/HouseholdReadModelProjector.java` l.104-122; `JdbcInviteReadModel.java` (TTL filter
  in `pendingInvitesOf`); `domain/readmodel/InviteReadModel.java`, `InviteView.java`.
- `application/query/ListPendingInvites.java` -- becomes a single-code query.
- `application/command/` -- `CreateHouseholdHandler` (l.72, mint first `InviteId`),
  `AcceptInviteHandler` (drop lazy `InviteExpired` append), `InvitePersonHandler` + `RevokeInvite*`
  (replace with `ReplaceInviteCode` + handler). `application/InviteLinkFactory` -- only used by
  `InvitePersonHandler` dev logging; delete with it (+ `CollaborationApplicationConfig` l.228-236).
- `adapter/in/InviteController.java` (POST/GET/DELETE l.49-88), `WriteErrorAdvice.java`
  (drop `invite.expired` 410 / `invite.alreadyUsed` 409).
- `backend/src/main/resources/static/invite/index.html` -- `showOutcome` l.176-187 copy.
- Next migration: `V23__...` (after `V22__membership_nickname.sql`); V12 `invite_read_model`.

App (`app/lib/features/`):
- `invites/data/invites_api.dart`, `pending_invite.dart`; `invites/presentation/invites_cubit.dart`
  (`createInvite` l.35-65), `invites_state.dart`, `invites_view.dart` (`_ReadyBody` l.40-83, reuse
  `_ShareableRow` l.136-194 for code + link), `invite_page.dart`.
- `members/presentation/members_page.dart` (pending invites + revoke l.103-107, l.294-316),
  `members_cubit.dart` (`listPendingInvites` l.44, `revokeInvite` l.137-147).
- `onboarding/presentation/onboarding_wizard_page.dart` `_InviteStepBody` (l.487, embeds
  `InvitesView`).
- `households/presentation/household_switcher_sheet.dart` (l.43-46, `_openManage` l.91),
  `manage_household_page.dart` (rows l.76-90).
- `shared/errors/error_message_resolver.dart` l.37-40; `l10n/app_de.arb`.
- Keep untouched: `invites/data/invite_link.dart`, deep-link service, accept/await-invite flow.

Tests to rewrite (not just delete): `HouseholdTest` invite cases (l.295-779),
`AcceptInviteHandlerTest`, `InvitePersonHandlerTest`, `RevokeInviteHandlerTest`,
`ListPendingInvitesTest`, `InviteControllerTest`, `HouseholdReadModelProjectorTest` invite cases,
`DomainEventJsonCodecTest` l.368-431, `InviteLinkFactoryTest`, `InviteWebFallbackControllerTest`;
app `invites_cubit_test`, `invites_view_test`, `invites_api_test`, `manage_household_page_test`,
`members_cubit_test`, `members_page_test`, `onboarding_wizard_page_test` invite cases.

## Tasks & Acceptance

**Execution:**
- [x] `Household.java` + events/exceptions/codec -- single `activeInviteId`; `create` takes the first
  `InviteId` and raises `MemberInvited`; `replaceInviteCode(requestedBy, newInviteId, commandId)`
  raises `InviteRevoked(old)` + `MemberInvited(new)` behind `requireAdmin`; accept
  requires `inviteId == activeInviteId`, raises only `MemberJoined`; delete TTL/expiry/consumption --
  domain first, TDD in `HouseholdTest`.
- [x] `CreateHouseholdHandler`, new `ReplaceInviteCode`/handler, `AcceptInviteHandler` -- mint the
  first id server-side; replace command beside its handler; drop the lazy-expiry path.
- [x] `V23__single_household_invite_code.sql` + projector + read model + query -- store the active
  invite id per household (drop V12's list table); `GET .../invite-code` returns `{inviteId,
  canReplace}` for members (`canReplace` = caller is ADMIN, from `household_member_read_model.role`).
- [x] `InviteController`, `WriteErrorAdvice`, `static/invite/index.html` -- `GET`/`POST .../replace`
  endpoints, remove create/list/revoke endpoints and 409/410 invite codes and copy.
- [x] App invites feature -- API + cubit + view show one code (code + link share/copy) and
  „Code ersetzen" (confirmation dialog) when `canReplace`; delete create/pending-list/revoke paths.
- [x] `members_page.dart`/`members_cubit.dart` -- drop the pending-invite section and revoke.
- [x] `household_switcher_sheet.dart` + `manage_household_page.dart` -- promoted „Mitglieder
  einladen" row opening the invite screen directly; extract the hub's `_openInvites` (l.103) into a
  shared `buildInvitePageRoute(context, householdId)` that reads deps before the sheet pops (same
  read-pop-push pattern as `buildManageHouseholdPageRoute`, l.34) and use it from both; ARB strings; resolver drops `invite.expired`/
  `invite.alreadyUsed`, rewords `invite.notFound`.
- [x] Tests -- I/O matrix rows as domain/handler/controller/projector tests; app cubit/view/page tests.

**Acceptance Criteria:**
- Given any household, when a member opens the invite screen, then exactly one code and its link are
  shown with share/copy, and no create button or list of codes exists.
- Given an Admin, when they confirm „Code ersetzen", then the screen shows the new code and the old
  one is rejected on accept; for a Participant the action is not shown.
- Given the household switcher sheet, when any member opens it, then a „Mitglieder einladen" row is
  visible and opens the invite screen in one tap.

## Implementation Notes

## Spec Change Log

## Review Triage Log

- **false** — Blind Hunter + Verification-gap "Other findings": claimed `GetActiveInviteCode`, `ReplaceInviteCode`/`ReplaceInviteCodeHandler`, `active_invite_code.dart`, and `V23__single_household_invite_code.sql` are missing from the diff / never created. Refuted: all four files exist on disk, are correctly wired (`CollaborationApplicationConfig`, `invites_api.dart` imports), and are exercised by `InviteControllerTest`'s `@SpringBootTest`. This is a `git diff` artifact — untracked new files don't appear in a plain diff against a commit — not a code defect.
- **false** — Blind Hunter: "`HouseholdReadModelSubscriptionTest`'s TRUNCATE ... household_invite_code will fail without the migration" — same diff-artifact root cause as above; the migration file exists (`V23__single_household_invite_code.sql`, verified on disk) and the table it creates matches the TRUNCATE target.
- **false** — Blind Hunter: "`DomainEventJsonCodec` drops `INVITE_EXPIRED_TYPE`/`INVITE_ACCEPTED_TYPE` with no fallback/compat handling for a stream that still contains those types." Verified: both switch statements have a `default -> throw new IllegalArgumentException(...)` (`DomainEventJsonCodec.java:132,300,555`) — fail-fast, not silent corruption, and matches CLAUDE.md's Fail-Fast principle plus the spec's explicit locked decision "No back-fill: the event store starts from zero (no real beta data)."
- **false** — Blind Hunter: "no test pins `InviteRevoked`'s `inviteId` to the *previous* active code, only that two events fire." Refuted: `HouseholdTest.replaceInviteCode_byAnAdmin_invalidatesTheOldCodeAndIssuesTheNewOneInOneAppend` (l.392) explicitly asserts `revoked.inviteId()).isEqualTo(inviteId)` — the old id — already pins this.
- **medium** — Blind Hunter + Edge Case Hunter (claim, converging on the same root cause): `GetActiveInviteCode` and `ReplaceInviteCodeHandler` have no dedicated application-layer unit test — every sibling handler (Accept/CreateHousehold/Promote/Demote/Remove/Rename/Delete) has one, these two don't; only the `@SpringBootTest` `InviteControllerTest` slice exercises them. Specifically, the spec's own claim "a retry with the same commandId ... converges at the append" for replace is untested at the handler level — `HouseholdTest`'s "retriedWithTheSameNewInviteId" test varies `commandId` across calls, proving the domain no-op path, not the `EventStore` commandId-dedup path the claim describes. This is a real CLAUDE.md §6 CQRS-coverage gap (commands/queries need their own fast unit tests). → **patch**
- **low** — Blind Hunter: `JdbcInviteReadModel#purgeHousehold`'s Javadoc still reads "Story 4.3, AC7, decision 4" — every other Javadoc in the class was updated to Story 8.4 except this one. Direct comment correction. → **patch**
- **low** — Blind Hunter: `InviteControllerTest`'s new nested classes use fully-qualified inline type names instead of imports, inconsistent with the file/codebase style. Direct fix (add imports). → **patch**
- **medium** — Verification-gap (Regression gap) + Blind Hunter, same finding: `InviteController.activeInviteCode`'s empty-`Optional` → 404 branch is never exercised — all three `activeInviteCode_*` tests in `InviteControllerTest` preset a present `activeInvite` before calling GET, so a regression in that branch (wrong status, unhandled exception) ships undetected. → **patch**: add `activeInviteCode_returns404WhileTheProjectionHasNotCaughtUpYet`.
- **low** — Blind Hunter: `CreateHousehold`'s Javadoc on retry semantics ("mints a fresh id per attempt safely") is non-obvious about *why* — it's the `EventStore`'s commandId-dedup discarding the whole retried append, not id idempotency itself. Direct doc correction. → **patch**
- **reject (low)** — Blind Hunter: `InvitesCubit.replaceCode()` has no defensive `canReplace` check before calling the API (server enforces 403; UI already hides the button). Fix would add a guard branch for a caller shape (programmatic/future entry point) that doesn't exist today — more than a direct correction, and unlikely to be hit in everyday use.
- **reject (low)** — Edge Case Hunter: onboarding's invite step could show `_FailureBody` instead of the code if it renders before the household-creation projector has caught up (`GET .../invite-code` 404s on the empty-Optional path). Real but narrow: `_FailureBody` already has a retry button (`invites-retry-button`) that re-calls `bootstrap()`, and the projector-catch-up window (sub-100ms typical) is very likely smaller than the UI's own step-transition + network round-trip. A real fix (poll-before-fail, or seed the id from the create response) is a design decision, not a trivial patch, and the self-healing retry makes this unlikely to be a persistent problem in everyday use.
- **no action** (not a code finding) — Blind Hunter: noted `sprint-status.yaml` still said `in-progress`. Already advanced to `in-review` as part of this step; not a defect in the reviewed code.

## Design Notes

Reusing `MemberInvited`/`InviteRevoked` keeps the codec and live-sync routing (`HouseholdResolver`
l.87 → members topic) untouched; renaming them to "invite code" events was weighed and rejected as
churn with no user-visible gain. The first code is minted server-side in `CreateHouseholdHandler`
(a retry with the same `commandId` converges at the append, so a fresh id per attempt is safe).

## Verification

**Commands:**
- `cd backend && ./gradlew test` -- expected: green, incl. ArchUnit
- `cd app && flutter test && flutter analyze` -- expected: green, 0 issues
