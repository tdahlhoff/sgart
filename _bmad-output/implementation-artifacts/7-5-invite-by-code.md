---
baseline_commit: fd73bc1
status: done
---

# Story 7.5: Invite by code or link; retire the email invite path

Status: done

<!-- Note: Validation is optional. Run validate-create-story for a quality check before dev-story. -->

## Story

As a member,
I want to invite people with a join code or link instead of by email,
so that inviting works without collecting anyone's email.

This is **Epic 7's fifth and final build story**. It completes the rev-E mandate (sprint-change
2026-09-13): invites move to a **join code + link** the app can share, and the entire **email/HMAC
invite path is retired** — `EmailHmac`, `InviteEmailSideStore`, the duplicate-pending-by-HMAC check,
and the already-a-member-by-email check all go away. It **supersedes Story 4.1** (invite by email) and
**extends Story 4.2** with a code-entry surface.

It is **mostly a subtraction plus a UI reshape**, not new invite machinery — see the design note's §0
discovery: the code-entry contract *already exists* (`InviteLink.tryParse` already parses a
`householdId:inviteId` colon form, built in 4.2/4.6) and `Household.acceptInvite` already no-ops for an
existing member (the E5 case). So there is **no new invite aggregate, no code index, no resolve
endpoint, and no already-member seam** to build. Accept stays on `(householdId, inviteId)` + the
caller's own JWT — `AcceptInviteHandler` is unchanged except for dropping its side-store purge.

**Feeds directly off the architect design note:** `epic-7-story-7.5-invite-by-code-design.md`
(Winston, 2026-09-17) — the one open major (join-code form) was **resolved with Timo 2026-09-17**:
the join code IS the existing `householdId:inviteId` colon form; **no** friendly short code for beta.
This story pins the note into buildable tasks; §-refs below point at the note's detail.

## Context — what already exists vs. what 7.5 changes

**Already built (reuse verbatim, do NOT recreate):**

*App (Flutter):*
- `invite_link.dart` — `InviteLink.tryParse` already accepts the canonical `https` link, the bare
  `h=..&i=..` query string, **and the `householdId:inviteId` colon form**. The code-entry data model
  is done; only the UI that feeds it is new. Keep `invite_link_test.dart`.
- `accept_invite_cubit.dart` / `accept_invite_state.dart` — already `POST .../accept`; the code-entry
  field feeds this unchanged.
- `invite_deep_link_service.dart` — the OS deep-link/App-Link handler; unchanged.

*Backend (`de.sgart.collaboration`):*
- `AcceptInviteHandler` — accept on `(householdId, inviteId)` + JWT; the `ConsentGate` check (7.4),
  provision-then-persist-on-success, and compensating `retract` (4.2) all stay. **Only change:** drop
  the `InviteEmailSideStore` field and every `purge(...)` call.
- `Household.acceptInvite` — already prevents duplicate membership (E5 no-op: raises `InviteAccepted`,
  skips `MemberJoined` when the joiner is already a member). This **is** the AC2 "already a member"
  prevention — no new code.
- `InviteLinkFactory` — builds `<base-url>?h=<householdId>&i=<inviteId>` (`sgart.invite.base-url`);
  unchanged, now also the shape the app mirrors to render the shareable link.
- `JdbcInviteReadModel` / `invite_read_model` (V12) — never held email; unchanged.

**What 7.5 changes:** (1) *subtracts* the whole email/HMAC path across domain, application, adapters,
config, DB, and their tests; (2) *reshapes* the app invite screen to create + share (code + link via
`share_plus`) and adds a paste-a-code field to the join screen; (3) makes the event log
drops the `emailHmac` field outright, wiping pre-launch event data instead of staying compatible with it.

## Locked Decisions (Timo, 2026-09-17 — from the design note)

- **D-A — The "join code" IS the existing `householdId:inviteId` colon form.** Two representations of
  one invite: the **link** (`InviteLinkFactory`) and the **code** (`<householdId>:<inviteId>`, already
  parsed by `InviteLink.tryParse`). Both are bearer capabilities over opaque UUIDs — no PII (AD-6).
  **No** short human-memorable code for beta (that would need a new global code→invite index + resolve
  endpoint — YAGNI; design §5). Confirmed by Timo 2026-09-17.
- **D-B — Retire the email path by subtraction, now, in full** (not deprecate — CLAUDE.md §7).
- **D-C — Already-a-member prevention moves to accept time via existing domain state** (the
  `acceptInvite` E5 no-op). The 4.1 invite-time email check is deleted.
- **D-D — Add `share_plus`** (OS share sheet) at its current supported major (CLAUDE.md §7).
- **D-E — Retention obligation retires with the side-store** — dropping `invite_email_side_store`
  removes the "purge raw email on accept/expiry/revoke" duty and the compensation gap
  (`deferred-work.md` line 135).

## Acceptance Criteria

Derived from `epics.md` §Epic 7 Story 7.5 with the design note's resolved decision applied.

1. **AC1 — Generate an invite as a code + link (D-A).** Given a household, when any member generates
   an invite, then the app shows it as a **join code** (`householdId:inviteId`) **and** a **link**
   (`<base-url>?h=<householdId>&i=<inviteId>`) — two representations of the same `inviteId` (same
   aggregate, 7-day TTL, single-use "already consumed" semantics). Either can be shared via the
   **OS-native share sheet** (`share_plus`). **The invite carries no email.** `POST .../invites` body
   is `{ inviteId, commandId }` — no `email` field.

2. **AC2 — The email/HMAC path is retired (D-B, D-C, AD-6).** When this story ships, then **no email
   is collected or hashed anywhere in the invite path**: `EmailHmac`, `InviteEmailHasher` /
   `HmacSha256InviteEmailHasher`, `InviteEmailSideStore` / `JdbcInviteEmailSideStore`,
   `NormalizedEmail`, the duplicate-pending-by-HMAC check in `Household.invitePerson`, the
   already-a-member-by-email check + identity's `FindHouseholdMemberByEmail` (+ its adapters), the
   `sgart.invite.email-hmac-secret` config, and the `invite_email_side_store` table are all **deleted**.
   A person already a member of the target household is prevented from re-joining by **membership state
   at accept time** (the existing `acceptInvite` E5 no-op) — no duplicate membership is created.

3. **AC3 — Join by code or link, accept unchanged (D-A).** Given a join code or link, when the invitee
   enters the code on the native join screen, or opens the link (deep link, or web fallback if the app
   isn't installed), then they join via the existing `AcceptInviteHandler` **unchanged** — accept is on
   `(householdId, inviteId)` plus the caller's own JWT. The code is parsed client-side by
   `InviteLink.tryParse` (no network round-trip for an unparseable code).

4. **AC4 — `MemberInvited` carries no email-derived field (design §1, revised 2026-09-17).** No
   production launch has happened yet, so there is no legacy event data to stay compatible with — the
   event store is wiped/reset rather than kept readable across the shape change. `MemberInvitedPayload`
   simply drops the `emailHmac` field; **no tolerant reader, no `@JsonIgnoreProperties`, no upcasting,
   no version bump.** `NoPersistedPersonalDataTest` confirms `MemberInvited` carries no email-derived
   field.

5. **AC5 — Retention & GDPR (D-E, CLAUDE.md §5).** The invite path now collects **no email at all**
   (strictly more privacy-preserving; FR-29 zero-required-PII). The `invite_email_side_store` retention
   duty and its compensation gap are removed; `deferred-work.md` line 135 is deleted and the Story 6.3
   retention note's invite-email example is updated.

6. **AC6 — Security surface & green build.** No new endpoint and no new `SecurityConfig` permit rule
   (accept/invite already authenticated; the link's web fallback surface is 4.6, unchanged). When the
   story completes, **both** suites are named and green: backend `./gradlew test` (incl. ArchUnit +
   Testcontainers — pure deletion must not break AD-1/AD-2 layer rules) **and** app `flutter test` +
   `flutter analyze`. `share_plus` is added at its current supported major (CLAUDE.md §7).

## Tasks / Subtasks

### Backend — domain subtraction — AC2, AC4

- [x] `Household.invitePerson` — drop the `EmailHmac emailHmac` parameter, the duplicate-pending
      loop that compares `invite.emailHmac()`, and `InviteState.emailHmac`. `InviteState` becomes
      `(invitedAt, status)` (+ `withStatus`). Keep the past-TTL lazy-expiry housekeeping (AC5 of 4.1).
      Update the fold arm for `MemberInvited` to build `InviteState` without the hmac.
- [x] `MemberInvited` event — drop the `EmailHmac emailHmac` component + its null-check + the
      `EmailHmac` import; update the Javadoc (no longer "by email").
- [x] **Delete** `domain/EmailHmac.java` and `domain/exception/DuplicatePendingInviteException.java`.
- [x] TDD: adjust `HouseholdTest` first — inviting the same household twice creates two independent
      pending invites (no duplicate-by-email rejection); accepting when already a member is the E5
      no-op (no duplicate `MemberJoined`).

### Backend — event codec — AC4

- [x] `DomainEventJsonCodec` — remove the `EmailHmac` import; the `MemberInvited` **write** arm drops
      `emailHmac`; `MemberInvitedPayload` loses its `emailHmac` field entirely (no tolerant-reader
      annotation needed — see AC4 revision). The **read** arm builds `MemberInvited` without the hmac.
- [x] Remove/adjust any existing `DomainEventJsonCodecTest` case built around a legacy `emailHmac`
      payload; it no longer applies (no compatibility contract to test).
- [x] Wipe the local/dev/staging KurrentDB event store (and any read-model tables it feeds) as part of
      shipping this story, since it predates any production launch — document this once in the
      migration note, not as recurring ops guidance.

### Backend — application subtraction — AC2

- [x] `InvitePerson` command — drop `emailHmac` (+ import + null-check).
- [x] `InvitePersonHandler` — drop `InviteEmailHasher`, `InviteEmailSideStore`,
      `FindHouseholdMemberByEmail`, `NormalizedEmail`, the already-member `ifPresent` throw, the
      side-store `store(...)`, and the `InviteExpired`→`purge` step. It shrinks to: resolve caller
      `MemberId` (403 if not a member) → load household (AD-8) → `invitePerson(requestedBy, inviteId,
      now, commandId)` → append → optional dev-log of the link (`logInviteLinkForDevTesting`).
- [x] `AcceptInviteHandler` — drop the `InviteEmailSideStore` field/ctor arg and **all** `purge(...)`
      calls (expiry branch + success branch). Everything else (`ConsentGate`, provision→persist→
      append, compensating `retract`) is unchanged.
- [x] `RevokeInviteHandler` — drop its `InviteEmailSideStore` purge; the revoke state transition is
      unchanged.
- [x] **Delete** `application/InviteEmailHasher.java`, `application/InviteEmailSideStore.java`,
      `application/NormalizedEmail.java`, and the exceptions
      `application/exception/InvalidInviteEmailApplicationException.java`,
      `application/exception/AlreadyAHouseholdMemberApplicationException.java`,
      `application/exception/DuplicatePendingInviteApplicationException.java`.

### Backend — identity subtraction — AC2

- [x] **First grep-confirm no other consumer**, then delete identity's
      `application/FindHouseholdMemberByEmail.java` and its adapters
      `adapter/out/KeycloakAdminFindHouseholdMemberByEmail.java`,
      `adapter/out/DeferredFindHouseholdMemberByEmail.java`, and their wiring in `IdentityBeansConfig`.
      (Its only application consumer was `InvitePersonHandler`.)

### Backend — adapters, config, migration — AC2, AC6

- [x] **Delete** `adapter/out/HmacSha256InviteEmailHasher.java` and
      `adapter/out/JdbcInviteEmailSideStore.java`.
- [x] `CollaborationApplicationConfig` — drop the deleted beans from the `InvitePersonHandler` /
      `AcceptInviteHandler` / `RevokeInviteHandler` wiring and the `email-hmac-secret` property use.
- [x] `InviteController` — `InviteRequest` drops `email`; the `POST` no longer passes it. `GET`
      (`PendingInviteResponse`) already carries no email — unchanged. Update the Javadoc.
- [x] `WriteErrorAdvice` — remove the advice arms for the deleted exceptions (400 invalid-email, 409
      duplicate-pending, 409 already-a-member). Confirm no other event maps them.
- [x] `application.yaml` — remove `sgart.invite.email-hmac-secret` (lines ~87 prod and ~160 local).
      Keep `sgart.invite.base-url`.
- [x] `V21__drop_invite_email_side_store.sql`: `DROP TABLE invite_email_side_store;` (latest migration
      is V20). Keep `invite_read_model`.
- [x] **Delete** `InviteEmailHmacSecretProfileGuardTest` (the guard that made the secret mandatory
      outside dev).

### Backend — tests: delete / adjust / add — AC2, AC4

- [x] **Delete**: `EmailHmacTest`, `HmacSha256InviteEmailHasherTest`, `JdbcInviteEmailSideStoreTest`,
      `InviteEmailHmacSecretProfileGuardTest`, `FindHouseholdMemberByEmailTest`, and the
      already-member/duplicate-pending/invalid-email cases in `InvitePersonHandlerTest`,
      `HouseholdTest`, `InviteControllerTest`.
- [x] **Adjust**: `InvitePersonHandlerTest`, `AcceptInviteHandlerTest`, `RevokeInviteHandlerTest`,
      `HouseholdTest`, `InviteControllerTest`, `DomainEventJsonCodecTest` to the slimmer signatures.
- [x] **Add**: a `NoPersistedPersonalDataTest` assertion that `MemberInvited` carries no email-derived
      field.

### App — invite screen: create + share — AC1

- [x] `invites_view` / `invite_page`: replace the email `TextField` + validator with a **create-invite
      action** → on success render the **code** (`householdId:inviteId`) and the **link** (built the
      same way the backend does, from a configured base URL), each with **share** (`share_plus`) and
      copy. Keep the pending-invite list + revoke.
- [x] `invites_api`: `POST .../invites` body drops `email`; keep the client-generated
      `inviteId` + `commandId` envelope (read-your-writes, unchanged).
- [x] **Delete** `invite_email_validator.dart` (+ its test).
- [x] Add `share_plus` to `app/pubspec.yaml` at its current supported major (`flutter pub add
      share_plus`); commit the lockfile.

### App — join by code (extends 4.2) — AC3

- [x] Add a **paste-a-code field** to the await-invite/join screen; feed the raw string through the
      existing `InviteLink.tryParse` → `AcceptInviteCubit` (unchanged). Client-side fail-fast on an
      unparseable code (no network). The deep-link path (`invite_deep_link_service`) is unchanged.

### App — l10n + tests — AC1, AC3, AC6

- [x] `app_de.arb`: create/share/copy + paste-a-code strings; **remove** the email-entry and
      "already a member"/"duplicate invite"/"invalid email" copy tied to the retired 400/409s.
      Regenerate l10n; add/adjust the `error_message_resolver` arms.
- [x] Widget/cubit tests: update `invites_cubit_test`, `invites_api_test`,
      `invite_page`/`invites_view` widget tests; **delete** the email-validator test; add a
      join-by-code test (valid colon code → accept called; unparseable code → no network, inline
      error). Keep/extend `invite_link_test`.

### Docs — AC5

- [x] Delete `deferred-work.md` line 135 (side-store compensation gap — now moot). Review lines 133/134
      (read-model TTL robustness) — they **survive** (the read model stays); leave them.
- [x] Update the Story 6.3 retention note in `epics.md` (its invite-raw-email retention example no
      longer applies).
- [x] Add "retired by 7.5" as-built pointers under Stories 4.1 (email/HMAC path) and 4.2 (code-entry
      surface added), matching the existing epics.md forward-pointers.

### Full build — AC6

- [x] Run and name green: **both** backend `./gradlew test` (incl. ArchUnit + Testcontainers) **and**
      app `flutter test` + `flutter analyze`. A partial run is not a green build (CLAUDE.md §6;
      `backend-test-hygiene`).

### Definition of Done (standing, per retros)

- [x] No dead code/strings/fields/stale comments after the subtraction (grep for `EmailHmac`,
      `emailHmac`, `SideStore`, `NormalizedEmail`, `FindHouseholdMemberByEmail`, `email-hmac-secret`
      across backend + app → zero hits outside migrations/history/this doc).
- [x] Fail-fast preserved on the invite/accept handlers; client-side fail-fast on an unparseable code.
- [x] a11y labels on all new interactive widgets (create/share/copy/paste).
- [x] Privacy DoD: `NoPersistedPersonalDataTest` proves `MemberInvited` holds no email-derived field;
      no compatibility regression is needed (pre-launch, event store wiped).
- [x] Both touched suites named at completion; a red build blocks (CLAUDE.md §6).

## Dev Notes

### Ground truth — read these before coding
- **`epic-7-story-7.5-invite-by-code-design.md`** (Winston, 2026-09-17) — governs. §0 the discovery
  that shrinks the story, §1 the event-log note (superseded 2026-09-17 — no tolerant reader needed,
  pre-launch), §2 the backend delete list, §3
  tests, §4 the app reshape, §5 the friendly-code trade-off (rejected for beta), §6 GDPR/retention/docs.
- Story 4.1 file + `4-1-invite-a-person-by-email.md` — the email/HMAC path this story retires (the
  as-built it supersedes).
- Story 4.2 / 4.6 files — the accept path + `InviteLink.tryParse` colon form this story reuses.
- `ARCHITECTURE-SPINE.md` AD-1/AD-2 (hexagonal — pure deletion must not break the layer rules), AD-5
  (identity ACL), AD-6 (no persisted PII), AD-8 (load-then-append), AD-10 (invite is a `Household`
  entity).

### Retiring a field that lives in the event log (design §1, revised 2026-09-17)
- Past `MemberInvited` events are persisted with `emailHmac`, and the log is normally **never
  rewritten** (same principle as AD-7) — but this project has not launched to production yet, so there
  is no real event data to preserve compatibility with. Timo's call: wipe the event store (and the read
  models it feeds) as part of shipping this story instead of building a tolerant reader for data that
  doesn't matter. `MemberInvitedPayload` simply drops the field. No upcasting, no version bump, no
  compatibility regression test.

### Why there is no new invite machinery (design §0)
- `InviteLink.tryParse` already parses `householdId:inviteId` (built 4.2/4.6) → the code is just a
  string the join field feeds to the existing `AcceptInviteCubit`. Accept is unchanged on
  `(householdId, inviteId)` + JWT. `Household.acceptInvite` already no-ops for an existing member (E5)
  → that IS the "already a member" prevention. No code index, no resolve endpoint (design §5 rejects
  the friendly short code for beta as YAGNI + new attack surface).

### GDPR / privacy (CLAUDE.md §5, AD-6)
- Net effect is **more** private: the invite path collects **no email at all** after this story
  (rev-E mandate + FR-29 zero-required-PII). The side-store (the one place a raw email lived) and its
  retention/compensation debt are deleted.

### Scope guards (KISS / YAGNI — CLAUDE.md §1)
- **In 7.5:** the email/HMAC subtraction (domain→DB→config→tests), the pre-launch event-store wipe, the app
  create+share + paste-a-code reshape, `share_plus`, l10n, the docs housekeeping.
- **Not in 7.5:** a friendly/short human-memorable join code (design §5 — backlog), any change to the
  accept handler's logic, the web-fallback surface (4.6, unchanged), analytics, or the erasure/export
  *feature* (Epic 6). No new endpoint, no new `SecurityConfig` rule, no `Household` state-machine
  change.

### Testing standards (CLAUDE.md §6)
- **Domain (fast):** two invites to the same household coexist; accept-when-already-member is a no-op.
- **Codec:** new `MemberInvited` serializes and deserializes without an `emailHmac` field.
- **Handlers:** invite without email succeeds; accept unchanged; revoke unchanged (no purge).
- **Privacy/arch:** `NoPersistedPersonalDataTest` covers `MemberInvited`; ArchUnit still green after
  deletion.
- **App:** create → code+link shown + shareable; join-by-code valid → accept called; unparseable code
  → no network. Synthetic, clearly-fake data only.

## Test Manifest (task → named test)

| Task / AC | Named test |
|-----------|------------|
| AC1 invite no email | `invitePerson_createsAPendingInvite_withNoEmail` |
| AC2 no duplicate-by-email | `invitePerson_sameHouseholdTwice_createsTwoIndependentInvites` |
| AC2 already-member at accept | `acceptInvite_whenCallerAlreadyMember_isANoOp_noDuplicateMembership` |
| AC3 join by code | `acceptInviteCubit_validColonCode_callsAcceptWithHouseholdAndInviteId`, `joinByCode_unparseableCode_failsFastWithoutNetwork` |
| AC4 new log write | `memberInvited_serializes_withoutEmailHmac` |
| AC4 privacy | `noPersistedPersonalData_memberInvitedHoldsNoEmailDerivedField` |
| AC1 app create+share | `invitePage_createInvite_showsCodeAndLink_andSharesViaShareSheet` |
| AC6 build | backend `./gradlew test` (named) + app `flutter test` + `flutter analyze` (named) |

## Project Structure Notes

- **Backend (`de.sgart.collaboration`):** `domain` — slim `Household.invitePerson`/`InviteState`,
  `MemberInvited`; delete `EmailHmac`, `DuplicatePendingInviteException`. `application` — slim
  `InvitePerson`, `InvitePersonHandler`, `AcceptInviteHandler`, `RevokeInviteHandler`; delete
  `InviteEmailHasher`, `InviteEmailSideStore`, `NormalizedEmail`, 3 exceptions. `adapter.out` — delete
  `HmacSha256InviteEmailHasher`, `JdbcInviteEmailSideStore`; slim `DomainEventJsonCodec` (no `emailHmac`);
  slim `CollaborationApplicationConfig`. `adapter.in` — `InviteController` DTO, `WriteErrorAdvice`
  arms. `db/migration/V21__drop_invite_email_side_store.sql`.
- **Backend (`de.sgart.identity`):** delete `FindHouseholdMemberByEmail` + its two adapters + wiring
  (grep-confirm no other consumer first).
- **No change:** the `Household` state machine, `AcceptInviteHandler`'s logic, `SecurityConfig`,
  `invite_read_model` (V12), the deep-link/web-fallback surface (4.6).
- **App (Flutter):** reshape `features/invites/presentation/invite_page.dart` + `invites_view.dart`;
  slim `features/invites/data/invites_api.dart`; delete `invite_email_validator.dart`; add paste-a-code
  to the await-invite/join screen; add `share_plus`; `app_de.arb` + `l10n/gen/*`; reuse
  `invite_link.dart` unchanged.

## References
- `epic-7-story-7.5-invite-by-code-design.md` (the design note — governs)
- `epics.md` §Epic 7 Story 7.5 (+ 4.1/4.2 forward-pointers) · `prd.md` FR2/FR15/FR-29 ·
  `sprint-change-proposal-2026-09-13.md` (rev E)
- `ARCHITECTURE-SPINE.md` AD-1/AD-2/AD-5/AD-6/AD-8/AD-10
- Backend: `Household` · `MemberInvited` · `InvitePersonHandler` · `AcceptInviteHandler` ·
  `RevokeInviteHandler` · `InviteController` · `InviteLinkFactory` · `DomainEventJsonCodec` ·
  `NoPersistedPersonalDataTest`
- App: `invite_link.dart` (+ test) · `accept_invite_cubit.dart` · `invite_deep_link_service.dart` ·
  `invites_api.dart` · `invite_page.dart` / `invites_view.dart`

## Questions for Timo (non-blocking — the one major resolved 2026-09-17)

- **Invite-link base URL on the app side** — the app must render the shareable `https` link; default:
  reuse the same configured base URL the backend's `sgart.invite.base-url` uses (a build-time app
  config / `--dart-define`), so link + deep-link + web-fallback stay one source of truth. OK?
- **`share_plus` version** — default: whatever `flutter pub add share_plus` resolves as the current
  supported major on this Flutter/Dart SDK (CLAUDE.md §7). Pin at add time.

## Change Log

| Date | Change | By |
|------|--------|-----|
| 2026-09-17 | Story drafted from `epic-7-story-7.5-invite-by-code-design.md` (Winston, 2026-09-17; the one open major — join-code form — resolved with Timo: reuse the existing `householdId:inviteId` colon form, no friendly short code for beta) + `epics.md` §Epic 7 Story 7.5 / `prd.md` FR2/FR15/FR-29 / rev-E sprint change. Grounded in the shipped invite slice (`Household.invitePerson`/`acceptInvite`, `InvitePersonHandler`, `AcceptInviteHandler`, `RevokeInviteHandler`, `InviteController`, `InviteLinkFactory`, `DomainEventJsonCodec`) and the app invite feature (`invite_link.tryParse` colon form already built). Mostly subtraction (retire email/HMAC path end to end) + a UI reshape (create+share, paste-a-code). Status → draft (ready for dev on Timo's go) | Winston (Opus 4.8) |
| 2026-09-17 | AC4 revised: dropped the tolerant-reader requirement. No production launch has happened yet, so `MemberInvitedPayload` drops `emailHmac` outright and the event store is wiped instead of kept legacy-readable — simpler, same privacy outcome (AD-6). | Timo |
| 2026-09-18 | Applied all 7 review patches: (1) `InviteLinkConfig.baseUrl` now a getter that throws in `kReleaseMode` when the localhost default was never overridden (a plain `assert` is stripped from release builds, so a real `throw` is used); (2) `InvitesCubit.createInvite` no longer clears `lastCreatedInviteId` at the start of an attempt — a failed subsequent create keeps the prior invite's shareable card (removed the now-dead `clearLastCreatedInviteId` copyWith param, Boy-Scout); (3) re-pointed 5 invite-exception javadocs off the deleted `DuplicatePendingInvite(Application)Exception` onto surviving siblings; (4) added `acceptInviteCubit_validColonCode_callsAcceptWithHouseholdAndInviteId` (colon-form happy path through the cubit); (5) removed the retired invite-email/find-by-email references from `docs/first-real-world-test.md` (deleted the SMTP-invite-email-delivery and D4-lookup sections; fixed dangling back-references); (6) onboarding invite-step finish label → neutral "Fertig"; (7) `V21` migration → `DROP TABLE IF EXISTS`. Both suites re-run green: app `flutter test` (726) + `flutter analyze` (0 issues); backend `./gradlew test` BUILD SUCCESSFUL (incl. ArchUnit + Testcontainers). | Claude Opus 4.8 (patch round) |
| 2026-09-18 | Patch-round re-review (Opus 4.8, full 4-layer fan-out — the diff carried a DB migration so the thin-slice fast-path did not apply). 8 findings rejected (see Rejected round 2), 2 patched: (a) `InviteLinkConfig` release guard extracted into a pure `@visibleForTesting rejectsConfiguredBaseUrl` predicate that now also rejects a blank/whitespace override, with unit tests covering both branches (the `kReleaseMode`-const-false test-runner limitation); (b) added `createInvite_afterASuccess_aFailedCreatePreservesLastCreatedInviteId` locking in patch 2's card-survives-failed-create invariant. App suite green: `flutter test` 731 + `flutter analyze` 0 (backend untouched this round). Story → done. | Claude Opus 4.8 (re-review) |
| 2026-09-17 | Dev-story implemented end to end: backend email/HMAC path subtracted (`Household.invitePerson`/`InviteState`, `MemberInvited`, `DomainEventJsonCodec`, `InvitePersonHandler`, `AcceptInviteHandler`, `RevokeInviteHandler`, `DeleteHouseholdHandler` — the last an unlisted-but-required deviation since it also held an `InviteEmailSideStore` dependency; `InviteController`, `WriteErrorAdvice`, `CollaborationApplicationConfig`, identity's `FindHouseholdMemberByEmail` + adapters + wiring, `application.yaml`, `V21__drop_invite_email_side_store.sql`); `EmailHmac`/`InviteEmailHasher`/`InviteEmailSideStore`/`NormalizedEmail`/3 application exceptions/`DuplicatePendingInviteException` deleted along with their tests; `NoPersistedPersonalDataTest` extended with the V21-drop-migration exemption (its `DROP TABLE invite_email_side_store` statement necessarily still names the table) and now covers `MemberInvited`. App: `invites_api`/`invites_cubit`/`invites_state`/`invites_view` reshaped to create+share (code via `InviteLink.codeFor`, link via new `InviteLink.linkFor`/`InviteLinkConfig`, `share_plus` 13.3.0, clipboard copy); `invite_email_validator.dart` deleted (no test existed); onboarding's invite step now embeds the shared `InvitesView` instead of duplicating the create flow; join-by-code already existed on `AwaitInvitePage` from 4.2/4.6 and needed no change. l10n updated (`app_de.arb` + regenerated `gen/*`), `error_message_resolver` trimmed. Both full suites green: backend `./gradlew test` (1041 tests) and app `flutter test` (725 tests) + `flutter analyze` (0 issues). Local/dev KurrentDB event store was not separately wiped in this sandbox (no persistent dev/staging environment reachable here) — flagged for whoever next runs against a real dev/staging KurrentDB instance to wipe it before relying on rehydration, per the AC4 pre-launch decision. | Claude Sonnet 5 (dev-story) |

## Review Findings

_Code review 2026-09-17 (Opus 4.8, 4-layer: Blind / Edge-Case / Verification-Gap / Acceptance)._

### Decision-needed

_Both resolved with Timo 2026-09-17. D1 rejected (see Rejected); D2 accepted as a patch (below)._

### Patch

- [x] **[Review][Patch] Invite-link base URL has no release-mode safeguard (D2, resolved 2026-09-17)** [`app/lib/shared/http/invite_link_config.dart:6`] — `InviteLinkConfig.baseUrl` defaults to `http://localhost:8081/invite` via `String.fromEnvironment` with no guard; a release build missing `--dart-define=SGART_INVITE_BASE_URL` silently ships invite links/QRs pointing at localhost. Add a release-mode fail-fast (assert the base URL is not the localhost default when `kReleaseMode`), mirroring the backend's boot-time config guard.
- [x] **[Review][Patch] A failed or subsequent create wipes the previously shown invite card** [`app/lib/features/invites/presentation/invites_cubit.dart:44`] — `createInvite` emits `clearLastCreatedInviteId: true` at the *start* of every attempt. The shareable code/link live only in `_CreatedInviteCard` (bound to `lastCreatedInviteId`); pending-invite rows show date/inviter/status only, with no code or share/copy. So creating invite A (card shown) then a second create that **fails** removes A's shareable card even though A is fine and still pending — and there is no way to re-surface an earlier invite's code. Only clear/set `lastCreatedInviteId` on success.
- [x] **[Review][Patch] Invite exception javadocs reference the deleted `DuplicatePendingInvite(Application)Exception`** [`InviteExpiredException.java:10`, `InviteAlreadyConsumedException.java:9`, `InviteExpiredApplicationException.java:10`, `InviteAlreadyConsumedApplicationException.java:10`, `InviteNotFoundApplicationException.java:9`] — two domain exceptions carry dangling `{@link DuplicatePendingInviteException}` (broken javadoc reference) and three application exceptions cite `{@code DuplicatePendingInviteApplicationException}` as their exemplar; all five target classes were deleted by this story. Update the cross-references (Boy-Scout / no dead references).
- [x] **[Review][Patch] Missing the manifest's colon-code-through-cubit accept test** [`app/test/features/invites/presentation/`] — the Test Manifest names `acceptInviteCubit_validColonCode_callsAcceptWithHouseholdAndInviteId`; no test feeds a `householdId:inviteId` colon-form code through the cubit's accept path (existing cubit test uses the `https` query form only). Behavior is covered at the `InviteLink` unit level and by the malformed-link no-network cubit test, but the AC3 colon-code happy path through the cubit is unproven. Add it.
- [x] **[Review][Patch] `docs/first-real-world-test.md` documents deleted classes/config** [`docs/first-real-world-test.md`] — the setup guide still references `KeycloakAdminFindHouseholdMemberByEmail`, `DeferredFindHouseholdMemberByEmail`, `InviteEmailSideStore`, and the invite-email HMAC secret guard — all removed by this story. It now instructs a developer against code/config that no longer exists.
- [x] **[Review][Patch] Onboarding invite-step finish label contradicts the embedded create+share flow** [`app/lib/l10n/app_de.arb:906`] — the invite step now embeds the full create-invite `InvitesView`, yet the only finish affordance is `onboardingInviteFinishButtonLabel` = "Später einladen — fertig" ("invite later — done"). After a user actually creates/shares an invite there, "invite later" reads wrong. Reword to a neutral done/continue label.
- [x] **[Review][Patch] `V21` uses `DROP TABLE` without `IF EXISTS`** [`backend/src/main/resources/db/migration/V21__drop_invite_email_side_store.sql`] — safe on the forward Flyway path, but `DROP TABLE IF EXISTS invite_email_side_store;` is more robust for partial/hand-repaired environments.

### Deferred

- [x] **[Review][Defer] `invitePerson` no longer runs the invite-time lazy past-TTL expiry the task said to keep** [`backend/.../domain/Household.java:198-206`] — deferred: the 7.5 task "Keep the past-TTL lazy-expiry housekeeping (AC5 of 4.1)" is not honored — `invitePerson` now only raises `MemberInvited`, because the housekeeping was inseparable from the removed duplicate-by-email loop. No functional harm: expiry is still enforced lazily at accept time (`acceptInvite`) and filtered by the read model (`isExpiredAt`); the only effect is that `pendingInvitesById` never sheds never-accepted PENDING entries as `InviteExpired` events. Re-adding a sweep adds complexity for no user-facing benefit — confirm the deviation is acceptable.
- [x] **[Review][Defer] ADR-0001 (crypto-shredding) is premised on the retired `invite_email_side_store` and duplicate-check HMAC** [`docs/adr/0001-*`] — deferred: architecture decision records are append-only history; annotate with a superseding note rather than edit. Not in this story's AC scope.
- [x] **[Review][Defer] `deferred-work.md:133` (reused-inviteId strands read-model row) is now even less reachable** [`_bmad-output/implementation-artifacts/deferred-work.md:133`] — deferred: with invite-time `InviteExpired` housekeeping removed, the `InviteExpired(X)→MemberInvited(X)` sequence it describes can no longer arise from the aggregate. AC5 deliberately kept lines 133/134 ("they survive; leave them"), so leaving as-is with this note.

### Rejected

- **D1 — `emailHmac` legacy-event read-compat / store wipe (rejected 2026-09-17):** Timo confirmed no real beta ever ran — only emulator testing, no persisted production/beta event data. The backend event store will be started from zero, so there are no legacy `MemberInvited` events carrying `emailHmac` to stay compatible with. No defensive codec test needed; the AC4 wipe is moot on a fresh store.
- **EC3 — `_share()` has no try/catch (low):** an unhandled future from `onPressed` is routed to `FlutterError.onError` and logged, not a crash; `share_plus` returns a dismissed result on user-cancel rather than throwing. Unlikely in everyday use and the fix adds a branch — not worth it.
- **EC4 — `error_message_resolver` has no arm for the create 403 (low):** `NotAMemberException` → 403 is a pre-existing path shared by every write command (addStore, etc.), not introduced by 7.5; the invite-create surface is member-gated, so a non-member 403 is a should-never-happen ACL divergence and the generic fallback is acceptable.
- **BH7 — `createInvite` mints a fresh `commandId` per tap, defeating server dedup (false):** by design and documented in the cubit — every tap is an independent invite (AC2 allows coexisting pending invites); a timeout-then-retry produces at worst a harmless extra single-use invite, not a corrupted state.
- **AA2 — story says 7.5 "adds a paste-a-code field" but it pre-existed (rejected):** the fix edits the spec under review; the field shipped in 4.2/4.6 and the dev recorded the reuse correctly in the Change Log.
- **AA4 — delivered test names differ from the manifest (false):** the behaviors (`memberInvited` no-email serialization; `MemberInvited` no-personal-data guard) are covered under different names/locations; a naming mismatch with the manifest is not a defect.
- **AA5 — codec privacy assertion uses `doesNotContain("email")` (false):** correct for the current payload (no field name contains "email"); an observation about a future-fragile substring guard, not a present defect.

_Re-review of the patch round 2026-09-18 (Opus 4.8, full 4-layer fan-out — Blind / Edge-Case / Verification-Gap / Acceptance; the diff carried a DB migration so the thin-slice fast-path did not apply)._

### Patch (round 2)

- [x] **[Review][Patch] Release base-URL guard has an untested gap** [`app/lib/shared/http/invite_link_config.dart:19`] — the `kReleaseMode && _configured == _localhostDefault` guard (a) lets an empty `--dart-define=SGART_INVITE_BASE_URL=` override slip through (empty ≠ localhost), shipping links built from a blank base URL, and (b) is unreachable under `flutter test` (`kReleaseMode` is a compile-time `false`), so this new production logic has no test. Extract the decision into a pure `@visibleForTesting` predicate `rejectsConfiguredBaseUrl({required bool isReleaseMode, required String configured})` that also rejects a blank/empty value, and unit-test both branches with injected flags.
- [x] **[Review][Patch] "Created-invite card survives a subsequent failed create" invariant is untested** [`app/test/features/invites/presentation/invites_cubit_test.dart`] — patch 2 deliberately preserves `lastCreatedInviteId` across a failing create, but no test chains create-success → failing-create and asserts the id is unchanged (the existing rejection test never sets it). A future edit re-adding a clear-on-failure would pass all current tests while making a still-valid invite's card vanish. Add `createInvite_afterASuccess_aFailedCreatePreservesLastCreatedInviteId`.

### Rejected (round 2)

- **Idempotent-retry duplicate on `createInvite` (blind/edge, false):** the per-tap `commandId`/`inviteId` minting is pre-existing (documented + covered by `createInvite_mintsAFreshCommandIdAndInviteIdEveryCall`), not introduced by this patch round, and already adjudicated as BH7 (rejected — coexisting invites are AC2 by design; a timeout-retry yields at worst a harmless extra single-use invite).
- **`copyWith` can never reset `lastCreatedInviteId` (low):** intentional per patch 2 (the card must survive failures, replaced only on the next success); no consumer needs a reset path — adding one is a branch for undemonstrated need (YAGNI).
- **Broaden the guard to https/well-formed URLs, profile mode, `bool.hasEnvironment` (low):** scope creep beyond the agreed D2 finding; `kReleaseMode` is the correct gate for a distributed build, throwing on an explicit localhost-in-release is correct, and URL-shape validation is gold-plating the backend guard does not do either.
- **V21 does not scrub emails in historical event payloads (false):** covered by AC4's pre-launch event-store wipe (prior D1, rejected — the store starts from zero); the `DomainEventJsonCodec` field drop is out of this diff's scope.
- **DRY: localhost default duplicates `application.yaml` (low):** cross-stack (Dart vs Java yaml) — no shared literal is possible; comment-documented single-source-of-truth is the pragmatic reality.
- **Over-long mid-token-wrapped doc line (low):** pre-existing, outside this round's changed lines; cosmetic.
- **Orphaned l10n keys after removals (false):** verification-gap confirmed the removed keys are referenced nowhere in `lib`/`test`, and there is a single ARB locale, so no generation break.
- **`baseUrl` const→getter could break a const-context use (false):** the only consumer is a runtime call at `invites_view.dart:99`; no const-context reference exists.
