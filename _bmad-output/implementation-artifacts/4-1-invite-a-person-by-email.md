---
baseline_commit: a58357f0ddb388c3af504238cc00d16821e8c40c
---

# Story 4.1: Invite a person by email

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a member of a household,
I want to invite someone by their email address,
so that we can share the household's lists together.

This is **Epic 4's first story** and **SGART's first GDPR/PII-touching feature** — the point where
CLAUDE.md §5 (DSGVO) moves from *designed-but-unexercised* to *must-be-exercised*, and §6 requires
the privacy guarantees to have their **own first-class tests**. It builds directly on the up-front
membership state model drafted for this epic (Epic-3 retro Action 1): `_bmad-output/planning-artifacts/epic-4-membership-state-model.md`.

## Locked Decisions (Timo, 2026-09-06)

These were decided at story-creation time and are binding for implementation. They fold into the ACs
and tasks below.

1. **Scope = full vertical slice.** Backend invite domain/command/read-model **and** the Flutter
   invite UI: light up the onboarding wizard's currently-disabled „Einladung senden" step
   (`onboarding_wizard_page.dart` `_InviteStep`) and add an invite entry to the manage-household hub
   (`manage_household_page.dart`). Completes the Epic-1 placeholder per Epic-1 retro Action 5.
2. **Keycloak depth = domain now, Keycloak seam deferred.** Build the full invite domain
   (`MemberInvited` / `InvitePerson` / no-duplicate-pending / HMAC / side-store / read-model) now.
   Define the Identity-ACL email-resolution **port** for the E5 already-member check, but back it with
   a **resolvable stub** for 4.1 (returns "no such member" in production, since no second real user
   can exist before 4.2). Wire the real Keycloak Admin API email→user lookup **and** actual invite
   email delivery in Stories 4.2 / 4.6. E5 is tested at the seam via a **fake** that returns a member.
3. **Raw email = dedicated side-store table + updated guard test.** Persist the raw normalized email
   in a mutable `invite_email_side_store` table (`invite_id → normalized_email`), and **extend**
   `NoPersistedPersonalDataTest` to whitelist this one documented AD-6 exception **while still banning
   `email`/`display_name` columns in every event-projected read model**. The side-store is the only
   place a raw email lives (AD-6), exists for Keycloak delivery, and is purgeable by `invite_id`.
4. **Invite TTL = 7 days.** Drives lazy on-access expiry and the no-duplicate-pending check.

## Acceptance Criteria

From `epics.md#Story 4.1` (BDD), with the locked decisions applied:

1. **AC1 — Invite created, no raw email in the log.** Given a household, when any member invites a
   person by email, then an invite is created and a `MemberInvited` event is appended to the
   `household-{id}` stream carrying `inviteId` + `HMAC(stable per-deployment secret, normalizedEmail)`
   — **never** the raw email (AD-6). The raw email is written **only** to the mutable
   `invite_email_side_store` (purged on accept/revoke/expiry/erasure). `POST` returns `201` with no
   body (the client mints and carries `inviteId`, read-your-writes).

2. **AC2 — No duplicate pending invite.** Given a **non-expired** pending invite to an email in a
   household, when a second invite to the **same email in the same household** is attempted, then it
   is **rejected** with `409` (`DuplicatePendingInviteException` → application exception). The check
   works via the stored `EmailHmac` (same email → same HMAC — HMAC stability is load-bearing, AD-6).

3. **AC3 — Already-a-member rejected (E5).** Given an email that already belongs to a **current
   member** of the household, when a member tries to invite it, then it is **rejected** with `409`
   (`AlreadyAHouseholdMemberApplicationException`) and **no invite is created**. This is enforced at
   the **application/ACL seam**, not in the domain (members carry no email, AD-6 — the aggregate
   cannot see it). Per locked decision 2, the real Keycloak lookup is deferred; the seam + its port +
   a fake-backed test ship now.

4. **AC4 — Membership-gated, role-agnostic.** Any member (Admin **or** Participant) may invite —
   `InvitePerson` requires only membership (`requireMember`, like `addStore`), never Admin. A
   non-member is rejected `403` (`NotAMemberException` from the ACL, or
   `NotAHouseholdMemberApplicationException` for ACL/stream divergence).

5. **AC5 — Lazy expiry housekeeping.** Given the only blocker for an email is a **past-TTL** pending
   invite, when a member invites that same email again, then `InviteExpired` is raised for the stale
   invite (housekeeping, no command) **and** the new `MemberInvited` is allowed. The stale invite's
   `invite_email_side_store` row is purged. (Accept-past-TTL expiry is Story 4.2.)

6. **AC6 — Invite read model.** `MemberInvited` / `InviteExpired` project into a new
   `invite_read_model` (`householdId, inviteId → status, invitedAt, invitedBy`) with **no email/HMAC
   column** (AD-6). A `GET .../invites` query returns the household's pending (non-expired) invites;
   "expired" is derived from `invitedAt + TTL`. Ships with a two-household isolation + replay/idempotency
   test in the **same** change (Epic-2 Action 4, still open).

7. **AC7 — Client slice.** The onboarding wizard invite step sends a real invite (no longer
   „folgt später"); the manage-household hub gains an „Einladen" entry that sends invites and shows a
   minimal pending-invites list (count + date + inviter — **no** email, privacy-first). Client-side
   email fail-fast validation; a11y labels on new interactive widgets; German localization; inline
   error surfacing for the `409`/`400` cases. Solo remains first-class — skipping the invite is a full
   success (unchanged).

## Tasks / Subtasks

> Each task lists its expected test(s) — see the **Test Manifest** section. A `[x]` task with no
> matching test is an integrity failure (Epic-3 retro Action 2). TDD is the default (CLAUDE.md §6).

### Backend — shared ids & value objects

- [x] **T1. `InviteId` shared id** (AC1) — add `de.sgart.shared.InviteId` mirroring
  `de.sgart.shared.StoreId` (`Identifier`-based UUID, `generate()` / `fromString()`).
  - [x] `InviteIdTest` (round-trip / equality), mirroring `StoreId`/`MemberId` id tests.

### Backend — domain (Household aggregate gains the Invite entity)

- [x] **T2. `EmailHmac` value object** (AC1, AC2) — `de.sgart.collaboration.domain.EmailHmac` holding
  only the HMAC digest (hex string), equality by digest. **Never** holds or derives the raw email.
  - [x] `EmailHmacTest` — equality by digest; rejects null/blank.
- [x] **T3. `MemberInvited` domain event** (AC1) — `de.sgart.collaboration.domain.event.MemberInvited`
  `(EventId, HouseholdId, InviteId, EmailHmac, MemberId invitedBy, HouseholdRole role, Instant invitedAt)`,
  `role` fixed to `PARTICIPANT` (§1). Mirror `StoreAdded`'s record shape. Implements `DomainEvent`.
- [x] **T4. `InviteExpired` domain event** (AC5) — `(EventId, HouseholdId, InviteId)`; raised lazily
  as housekeeping, no command.
- [x] **T5. `DuplicatePendingInviteException` domain exception** (AC2) —
  `de.sgart.collaboration.domain.exception.DuplicatePendingInviteException`, mirroring
  `DuplicateStoreNameException`.
- [x] **T6. Invite TTL policy constant** (AC5) — `Invite.TIME_TO_LIVE = Duration.ofDays(7)` (a domain
  constant so expiry is computed deterministically in the aggregate). Expiry: `invitedAt.plus(TTL)`
  is not after `now`.
- [x] **T7. `Household.invitePerson(...)` + fold** (AC1, AC2, AC4, AC5) — add to
  `de.sgart.collaboration.domain.Household`:
  - Signature: `invitePerson(MemberId requestedBy, InviteId inviteId, EmailHmac emailHmac, Instant now, CommandId commandId)`.
  - `requireMember(requestedBy)` — reuse the existing private guard (membership-gated, AC4). Do **not**
    add an Admin gate.
  - Duplicate/housekeeping logic over a new `pendingInvitesById` map (`InviteId → InviteState{emailHmac, invitedAt, status}`, an inner record like `StoreState`):
    - a **non-expired** PENDING invite with the same `emailHmac` → throw `DuplicatePendingInviteException` (AC2);
    - a **past-TTL** PENDING invite with the same `emailHmac` (the only blocker) → `raise(InviteExpired)` for it, then continue (AC5);
  - `raise(new MemberInvited(...))` with `role=PARTICIPANT`, `invitedAt=now`.
  - `apply(MemberInvited)` → put PENDING `InviteState`; `apply(InviteExpired)` → mark that invite EXPIRED (keep it foldable / out of the active-blocker set). Register both in the `apply(...)` switch and update the aggregate javadoc.
  - [x] `HouseholdTest` cases: `invitePerson_raisesMemberInvitedCarryingTheEmailHmacNotTheEmail`;
    `invitePerson_byAnyMember_isAllowed` (Participant too); `invitePerson_byNonMember_throwsNotAHouseholdMember`;
    `invitePerson_withANonExpiredPendingInviteToTheSameEmail_throwsDuplicatePendingInvite`;
    `invitePerson_withAPastTtlPendingInviteToTheSameEmail_raisesInviteExpiredThenMemberInvited`;
    `invitePerson_toADifferentEmail_isAllowedAlongsideAnExistingPendingInvite`.

### Backend — application (command, ports, query, exceptions)

- [x] **T8. `NormalizedEmail` application value object** (AC1, AC3) — trims + lowercases, fail-fast
  rejects a malformed address; lives in `application` (**never** enters the domain). Invalid →
  `InvalidInviteEmailApplicationException` (400).
- [x] **T9. `InviteEmailHasher` port + impl** (AC1, AC2) — application-owned interface
  `de.sgart.collaboration.application.InviteEmailHasher` (`EmailHmac hash(NormalizedEmail)`); impl
  `HmacSha256InviteEmailHasher` in `adapter.out`, secret from config
  (`sgart.invite.email-hmac-secret`, a **stable per-deployment** secret — AD-6: never a per-invite
  salt). Fail-fast if the secret is unconfigured.
  - [x] `HmacSha256InviteEmailHasherTest` — **HMAC stability**: same normalized email → same digest
    (so the AC2 dup check holds); different email → different digest; a different secret → different
    digest.
- [x] **T10. `InviteEmailSideStore` port + Jdbc impl** (AC1, AC5) — application-owned interface
  `de.sgart.collaboration.application.InviteEmailSideStore` (`store(InviteId, NormalizedEmail)`,
  `purge(InviteId)`, `Optional<NormalizedEmail> findEmail(InviteId)` for later delivery). Jdbc impl
  in `adapter.out` over `JdbcClient`, mirroring `JdbcMemberMappingRepository`. **This is the only PII
  store** (AD-6).
  - [x] `JdbcInviteEmailSideStoreTest` (Testcontainers) — store/find/purge round-trip; purge removes
    the row.
- [x] **T11. Identity-ACL email-resolution seam** (AC3) — new port in `de.sgart.identity.application`,
  e.g. `FindHouseholdMemberByEmail` with `Optional<MemberId> forHousehold(String email, HouseholdId)`
  (published `String` signature so `KeycloakUserId` stays inside identity, AD-2, like
  `ResolveMemberIdentity`). **4.1 impl = a deferred/stub adapter** that resolves email→keycloakUserId
  as "unknown" (returns empty) — the real Keycloak Admin lookup is 4.2/4.6 (locked decision 2).
  Document the deferral in the impl's javadoc.
  - [x] `FindHouseholdMemberByEmailTest` — the stub returns empty; (the real Keycloak path is 4.2).
- [x] **T12. Application exceptions + `WriteErrorAdvice` mappings** (AC2, AC3, AC1) — add
  `DuplicatePendingInviteApplicationException` (409), `AlreadyAHouseholdMemberApplicationException`
  (409), `InvalidInviteEmailApplicationException` (400), each with an `errorDescriptor()` + a distinct
  client-facing `code` (localized client-side). Register all three `@ExceptionHandler`s in
  `de.sgart.collaboration.adapter.in.WriteErrorAdvice` (the seam Epic-3 Action 5 references — see
  Dev Notes).
- [x] **T13. `InvitePerson` command + `InvitePersonHandler`** (AC1–AC5) — mirror
  `AddStore`/`AddStoreHandler`:
  - `InvitePerson(HouseholdId, InviteId, EmailHmac, CommandId, AggregateVersion basedOnVersion)`.
  - Handler `handle(keycloakUserId, rawHouseholdId, rawInviteId, rawEmail, rawCommandId)`:
    1. parse envelope via `CommandFieldTranslations` (add `toInviteId`); `NormalizedEmail` validate (400);
    2. `emailHmac = inviteEmailHasher.hash(normalizedEmail)`;
    3. `requestedBy = resolveMemberIdentity.resolve(keycloakUserId, householdId)` (403 if non-member);
    4. **E5**: `findHouseholdMemberByEmail.forHousehold(rawEmail, householdId).ifPresent(→ throw AlreadyAHouseholdMemberApplicationException)` (AC3);
    5. load `Household`, call `invitePerson(memberId, inviteId, emailHmac, clock.instant(), commandId)`, translating `DuplicatePendingInviteException`→409 and `NotAHouseholdMemberException`→403;
    6. `eventStore.append(basedOnVersion, household.uncommittedEvents(), commandId)`;
    7. **after a successful append**: `inviteEmailSideStore.store(inviteId, normalizedEmail)`; and for any `InviteExpired` in `uncommittedEvents`, `inviteEmailSideStore.purge(thatInviteId)` (AC5). (Append-before-side-store ordering avoids orphan PII rows on a concurrency loss — document this.)
  - Inject `java.time.Clock` (or `InstantSource`) so tests use a fixed instant.
  - [x] `InvitePersonHandlerTest` (fast, in-memory doubles): appends `MemberInvited` with HMAC;
    duplicate-pending → 409; **already-member (fake resolver returns a member) → 409, no append**;
    non-member → 403; invalid email → 400; past-TTL re-invite → `InviteExpired`+`MemberInvited` and
    side-store purge of the stale row; side-store receives the raw email; **append happens before the
    side-store write**.

- [x] **T14. `ListPendingInvites` query** (AC6) — `de.sgart.collaboration.application.query.ListPendingInvites`
  reading `invite_read_model`, returning pending (non-expired-by-`invitedAt+TTL`) invites
  (inviteId, invitedAt, invitedBy, status) — **no email**. Mirror `ListStores`.
  - [x] `ListPendingInvitesTest` — returns only this household's pending invites; excludes
    expired/accepted; two-household isolation.

### Backend — adapters (codec, projector, read model, controller, migrations)

- [x] **T15. Register events in `DomainEventJsonCodec`** (AC1, AC5) — add `MemberInvited` +
  `InviteExpired` type tags, `toJsonBytes`, `fromJsonBytes`, and payload records. `MemberInvited`
  payload carries `emailHmac` (string), `invitedBy`, `role`, `invitedAt` (ISO-8601 UTC). Round-trip
  must preserve all fields.
  - [x] `DomainEventJsonCodecTest` — round-trip both events; **assert the `MemberInvited` payload has
    no raw-email field** (privacy round-trip guard).
- [x] **T16. `invite_read_model` migration + `JdbcInviteReadModel`** (AC6) — `V12__invite_read_model.sql`
  (`household_id UUID, invite_id UUID, status VARCHAR, invited_at TIMESTAMPTZ, invited_by UUID,
  PRIMARY KEY (household_id, invite_id)`) — **no email/hmac column** (must keep
  `NoPersistedPersonalDataTest` green). Jdbc read model with `upsertInvite` / `markExpired`, mirroring
  `JdbcStoreReadModel`.
- [x] **T17. `invite_email_side_store` migration** (AC1, decision 3) — `V13__invite_email_side_store.sql`
  (`invite_id UUID PRIMARY KEY, normalized_email VARCHAR NOT NULL`) with a header comment stating it
  is the AD-6 raw-email side-store, mutable, purged on accept/revoke/expiry/erasure. This is the table
  the guard test whitelists (T21).
- [x] **T18. Extend `HouseholdReadModelProjector`** (AC6) — add `MemberInvited` → `upsertInvite(PENDING)`
  and `InviteExpired` → `markExpired(...)` cases. These ride the **existing** `household-{id}` prefix
  subscription (do **not** add a second subscription). Inject `JdbcInviteReadModel` into the projector
  constructor (update `IdentityBeansConfig`/wiring as needed).
  - [x] `HouseholdReadModelProjectorTest` (Testcontainers) — projects `MemberInvited` to PENDING;
    `InviteExpired` to EXPIRED; **two-household isolation** + **replay idempotency** (re-projecting is
    a no-op) — Epic-2 Action 4.
- [x] **T19. `InviteController`** (AC1, AC4, AC6, AC7) — `de.sgart.collaboration.adapter.in.InviteController`,
  `@RequestMapping("/api/v1/households/{householdId}/invites")`:
  - `POST` (`201`) `InviteRequest{inviteId, email, commandId}` → `invitePersonHandler.handle(...)`;
  - `GET` → `listPendingInvites.forHousehold(...)` → `PendingInviteResponse{inviteId, invitedAt, invitedBy, status}`.
  - Caller identity from JWT `sub` via `AuthenticatedCaller` only (AR10/AD-5). Mirror `StoreController`.
  - [x] `InviteControllerTest` (`@WebMvcTest`) — `201` on invite; `409` duplicate-pending; `409`
    already-member; `403` non-member; `400` invalid email; `GET` returns pending invites; **assert no
    response body/field ever carries the raw email**.

### Backend — privacy guarantees (first-class, §6)

- [x] **T20. `MemberInvited` carries no raw email** (AC1) — covered by T7 (event) + T15 (codec
  payload) + T19 (API response). Add a focused assertion in `NoPersistedPersonalDataTest` or a new
  `InvitePrivacyTest` that the `MemberInvited` record has no component whose name contains `email`
  (only `emailHmac`) — the digest is allowed, the address is not.
- [x] **T21. Update `NoPersistedPersonalDataTest`** (decision 3) — the migration column-name guard must
  **whitelist `V13__invite_email_side_store.sql`** (the documented AD-6 side-store) while continuing
  to fail on an `email`/`display_name` column in **any other** migration (esp. the new
  `invite_read_model`). Keep the existing comment-stripping. Add a test asserting the read-model
  migration still trips the guard if an email column were added (guard-still-works regression).

### Client — Flutter (`app/lib/features/invites/`)

- [x] **T22. `InvitesApi`** (AC7) — `sendInvite(householdId, {inviteId, email, commandId})` →
  `POST .../invites`; `listPendingInvites(householdId)` → `GET .../invites`. Mirror
  `stores_api.dart` (caller-minted `inviteId` + `commandId`, no response body on send).
  - [x] `invites_api_test.dart` — request shape; pending-invites parse.
- [x] **T23. `InvitesCubit` / `InvitesState`** (AC7) — optimistic send via the shared
  `command_intent.dart` (one `commandId` per intent, regenerate on payload change / after success —
  Epic-1 Action) with an `isSubmitting` re-entrancy guard (Epic-2 Action 3); surfaces `409`
  (duplicate / already-member) and `400` (invalid email) as distinct inline errors; loads pending
  invites. Client-side email fail-fast before send.
  - [x] `invites_cubit_test.dart` — success; duplicate-pending error; already-member error; invalid
    email blocked client-side; `isSubmitting` guard; commandId regenerated after success.
- [x] **T24. Onboarding wizard `_InviteStep` — enable send** (AC7) — in
  `onboarding_wizard_page.dart`: wire an email `TextEditingController` + `InvitesCubit`, make
  „Einladung senden" functional (remove the `onPressed: null` disabled treatment and the
  „folgt später"/deferred note), show success + inline error, keep „Später einladen — fertig"
  finishing. Update the step's javadoc (it currently says send is deferred to 4.1).
  - [x] widget test — send from the wizard; error shown; finish still works; **fix-rigor**: remove the
    now-stale „deferred to Epic 4" comment/note (Epic-3 Action 4 — no contradicting docs left behind).
- [x] **T25. Manage-household hub — invite entry** (AC7) — in `manage_household_page.dart` add an
  „Einladen" / „Mitglieder & Einladungen" row (matching the existing „Geschäfte" row pattern +
  re-provide-by-value across the route boundary) opening an invite page that sends invites and lists
  pending invites (count + date + inviter, **no email**).
  - [x] widget test — row opens the page; send works; pending list renders.
- [x] **T26. Localization + a11y** (AC7) — add German keys to `app/lib/l10n/app_de.arb` for all new
  copy; a11y labels/semantics on the new email field, send button, invite row, pending list.
  `flutter analyze` clean.

### Definition of Done (standing, per retros)

- [x] Full suites green **and named**: backend `./gradlew test` (incl. ArchUnit + Testcontainers) **and**
  `flutter test` + `flutter analyze` (CLAUDE.md §6; backend-test-hygiene). Report which suite ran.
- [x] commandId lifecycle correct; error advice mapped + tested; a11y labels on new widgets; no dead
  code/strings/stale comments; client fail-fast on the email input (Epic-1 DoD).
- [x] Optimistic-state check (Epic-2 Action 1): the send's optimistic rebuild + error rollback
  verified in dev, not left for review.
- [x] New read model (`invite_read_model`) ships with isolation + replay tests in this change (Epic-2
  Action 4).
- [x] Every `[x]` task has its Test-Manifest test actually present (Epic-3 Action 2).

## Dev Notes

### Ground truth — read these before coding
- **Membership state model (authoritative vocabulary):** `_bmad-output/planning-artifacts/epic-4-membership-state-model.md`
  — §0 locked decisions, §1 vocabulary, §2 state machines, §3 invariants (incl. §3⚠ the E5
  application-seam note), §5 read models, §6 GDPR, §7 story→model mapping. This story implements the
  4.1 row of §7 exactly.
- **Architecture spine:** `.../architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md` —
  AD-1 (hexagonal/ES), AD-2 (cross-context only via published application ports), AD-4 (command→event,
  projection-only read models), AD-5 (`MemberId` pseudonym, ACL is sole minter), **AD-6 (no persisted
  PII; `MemberInvited` carries `HMAC(stable secret, normalizedEmail)`; raw email only in a mutable
  side-store, stable secret not a per-invite salt)**, AD-7 (erasure by de-linking), AD-10 (aggregate
  owns its entities — Invite is an entity of `Household`), AD-11 (ubiquitous language, no abbreviations).

### Patterns to mirror (exact files)
- **Invite-as-entity-of-Household** ← `Store` entity pattern in `Household.java`: `pendingInvitesById`
  map + inner `InviteState` record mirror `storesById` + `StoreState`; `invitePerson` mirrors
  `addStore` (membership gate via `requireMember`, raise-on-invariant); fold in the `apply(...)` switch.
- **Command + handler** ← `application/command/AddStore.java` + `AddStoreHandler.java`: online
  load-then-append (`basedOnVersion` = loaded version, AD-8), ACL resolve first, domain→application
  exception translation at the handler seam, `void` return.
- **Controller** ← `adapter/in/StoreController.java` (client-minted id in the envelope, `201` no body,
  `AuthenticatedCaller.fromJwt`), nested under `/households/{householdId}`.
- **Codec** ← `adapter/out/DomainEventJsonCodec.java` — add type-tag constants + `toJsonBytes` +
  `fromJsonBytes` + payload records for both new events (stable string tags, never class names).
- **Projector** ← `adapter/out/HouseholdReadModelProjector.java` — new cases on the **existing**
  `HOUSEHOLD` prefix subscription; idempotent upserts (replay-safe).
- **Jdbc read model / side-store** ← `JdbcStoreReadModel` + `identity/adapter/out/JdbcMemberMappingRepository.java`
  (plain `JdbcClient`, KISS — no JPA).
- **Identity ACL port** ← `identity/application/ResolveMemberIdentity.java` (published `String`
  overload keeps `KeycloakUserId` inside the context, AD-2).
- **Migration shape** ← `resources/db/migration/V4__store_read_model.sql` (household-scoped, header
  comment stating projection source + erasure coverage). Next free numbers: **V12**, **V13**.
- **Error mapping** ← `adapter/in/WriteErrorAdvice.java` — one `@ExceptionHandler` per application
  exception → status + `errorDescriptor()`. `code` is the machine key; the client localizes it.
- **Client**: `stores_api.dart` (API), `command_intent.dart` (idempotent optimistic commands),
  `onboarding_wizard_page.dart` `_InviteStep` (the placeholder to light up), `manage_household_page.dart`
  (hub row + re-provide-by-value across the route boundary).

### GDPR specifics — this is the whole point of the story (§6)
- **Raw email never enters an event or the invite read model.** Only `EmailHmac` (a hex digest) is in
  `MemberInvited`; the read model has neither email nor hmac (§5). Enforced by tests T20/T21 + the
  codec round-trip assertion.
- **HMAC stability is a correctness requirement, not a nicety** (AD-6): the AC2 duplicate check
  compares digests, so a per-invite salt would silently break it. Use a single stable per-deployment
  secret from config; test stability explicitly (T9).
- **The side-store is the single PII location** and must be purgeable by `invite_id`. 4.1 writes it on
  invite and purges it on lazy-expiry housekeeping; purge-on-accept is 4.2, purge-on-revoke is 4.3,
  purge-on-erasure is Epic 6 — the port exposes `purge` now so those wire in without re-shaping it.
- **The `NoPersistedPersonalDataTest` tension is real and expected** (decision 3): the current test
  fails on **any** `email` substring in **any** migration. You must (a) name the side-store table's
  column `normalized_email` and (b) update the test to whitelist exactly `V13__invite_email_side_store.sql`
  while it keeps guarding every other migration (including the new `invite_read_model`). Do **not**
  weaken the guard globally.

### Handler ordering & clock
- **Append before side-store write** so a lost concurrency race never leaves an orphan raw-email row
  (PII hygiene). A side-store write failing after a successful append leaves an invite with no
  deliverable address — acceptable/retriable and self-heals; the reverse leaks PII.
- **Inject a `Clock`/`InstantSource`** into `InvitePersonHandler`; pass `now` into the domain so expiry
  is deterministic and testable (never call `Instant.now()` inside the aggregate — keep the domain
  pure and time-injected).

### Convergence / no-op semantics (AD-8, §3.5)
- Re-inviting the same email while a **non-expired** pending invite exists is a **rejection** (409),
  **not** a convergent no-op (§3.4) — this is the one place a "duplicate" is an error, deliberately.
- commandId dedup at the `EventStore` still makes a genuine retry of the *same* `InvitePerson` intent
  idempotent (AD-8) — that is separate from the AC2 business rejection.

### Read-model shows no email — deliberate (see Questions)
The pending-invites list (AC7) shows inviteId/date/inviter/status but **not** the invitee address,
because the read model holds no email (AD-6, §5). This is privacy-first and sufficient for 4.1 (dup
feedback + a base for 4.3 revoke). Whether to surface the address by reading the side-store at query
time is an open sub-decision — see **Questions for Timo**.

### Epic-3 retro Action 5 — the error-advice contract test decision
4.1 is "Epic 4's first endpoint story", where Action 5 asks: build the base error-advice contract test
(every command endpoint maps missing/malformed input + every reachable domain exception to a 4xx,
never 500) **or** formally retire it. The distributed per-endpoint coverage (T12/T13/T19) has held for
three epics. Default here: **continue per-endpoint** and surface the build-or-retire call to Timo (see
Questions) rather than silently drifting a fourth time.

### Previous-work intelligence (cross-epic, since this is story 1 of the epic)
- **Epic-3 retro dominant defect:** tasks marked `[x]` with tests absent. The Test Manifest below is
  the antidote — do not check a task without its test.
- **Fix-rigor (Action 4):** if a review fix is needed, its test must exercise the exact branch, and no
  contradicting javadoc may be left (e.g. the `_InviteStep` "deferred to Epic 4" comment MUST go when
  send is enabled — T24).
- **Optimistic-state drift (Epic-2 Action 1)** recurred 3× in Epic 3 — verify the send's optimistic
  render + error rollback in dev (T23), not in review.
- **`fromStart`-replay projector debt** (retro §7) is knowingly deferred to 4.4; do not try to fix
  checkpointing here.

## Test Manifest (task → named test)

| Task | Test(s) |
|---|---|
| T1 | `InviteIdTest` |
| T2 | `EmailHmacTest` |
| T7 | `HouseholdTest`: invite happy-path/HMAC-not-email · any-member · non-member · dup-pending · past-TTL-expiry+invite · different-email-allowed |
| T9 | `HmacSha256InviteEmailHasherTest` (stability + secret-sensitivity) |
| T10 | `JdbcInviteEmailSideStoreTest` (Testcontainers: store/find/purge) |
| T11 | `FindHouseholdMemberByEmailTest` (stub returns empty) |
| T13 | `InvitePersonHandlerTest` (append+HMAC · dup→409 · already-member→409/no-append · non-member→403 · invalid-email→400 · past-TTL→expire+invite+purge · side-store gets email · append-before-side-store) |
| T14 | `ListPendingInvitesTest` (pending-only · isolation) |
| T15 | `DomainEventJsonCodecTest` (round-trip both · no-raw-email payload guard) |
| T18 | `HouseholdReadModelProjectorTest` (project PENDING/EXPIRED · two-household isolation · replay idempotency) |
| T19 | `InviteControllerTest` (201 · 409 dup · 409 already-member · 403 · 400 · GET pending · no-email-in-response) |
| T20/T21 | `NoPersistedPersonalDataTest` / `InvitePrivacyTest` (MemberInvited no email component · side-store whitelisted · read-model guard still fires) |
| T22 | `invites_api_test.dart` |
| T23 | `invites_cubit_test.dart` |
| T24 | onboarding wizard invite-step widget test |
| T25 | manage-household invite-entry widget test |

## Project Structure Notes

- **New backend files** under `de.sgart.collaboration`: `domain/EmailHmac.java`, `domain/Invite.java`
  (TTL constant + `InviteState`, or fold `InviteState` into `Household` like `StoreState` — prefer the
  latter for symmetry; keep `TIME_TO_LIVE` where the aggregate reads it), `domain/event/MemberInvited.java`,
  `domain/event/InviteExpired.java`, `domain/exception/DuplicatePendingInviteException.java`,
  `application/NormalizedEmail.java`, `application/InviteEmailHasher.java`,
  `application/InviteEmailSideStore.java`, `application/command/InvitePerson.java` +
  `InvitePersonHandler.java`, `application/query/ListPendingInvites.java`,
  `application/exception/{DuplicatePendingInvite,AlreadyAHouseholdMember,InvalidInviteEmail}ApplicationException.java`,
  `adapter/in/InviteController.java`, `adapter/out/{HmacSha256InviteEmailHasher,JdbcInviteReadModel,JdbcInviteEmailSideStore}.java`,
  `domain/readmodel/InviteReadModel.java` (+ view) as the `StoreReadModel`/`StoreView` pair shows.
- **New identity file:** `identity/application/FindHouseholdMemberByEmail.java` + a deferred/stub
  `adapter/out` impl + its bean in `IdentityBeansConfig`.
- **Shared:** `shared/InviteId.java`; extend `CommandFieldTranslations` with `toInviteId`.
- **Migrations:** `resources/db/migration/V12__invite_read_model.sql`, `V13__invite_email_side_store.sql`
  (edit `src/main/...` only; `build/...` copies are generated).
- **`package-info.java`** already present per layer — no new stereotype packages needed (the counts
  here don't warrant new subpackages; CLAUDE.md §8 "subfolder only when it earns its keep").
- **New client feature:** `app/lib/features/invites/{data/invites_api.dart, presentation/invites_cubit.dart,
  invites_state.dart, invite_page.dart}`; edits to `onboarding_wizard_page.dart`, `manage_household_page.dart`,
  `l10n/app_de.arb`.
- **Wiring:** register new beans (handler, hasher, side-store, read model, query, ACL port impl) where
  the store slice's beans are registered; inject `JdbcInviteReadModel` into `HouseholdReadModelProjector`.
- **ArchUnit:** the new classes fall under `..domain..` / `..application..` / `adapter.in` / `adapter.out`
  and must obey `HexagonalArchitectureTest` (AD-1/AD-2) unchanged — domain imports no framework; the
  side-store/hasher are adapters behind application ports; `adapter.in` never imports `..domain..`
  (translate at the handler seam).

## References

- [Source: `_bmad-output/planning-artifacts/epic-4-membership-state-model.md` §0–§8]
- [Source: `_bmad-output/planning-artifacts/epics.md#Story 4.1: Invite a person by email`]
- [Source: `.../architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md#AD-5, AD-6, AD-7, AD-10, AD-11`]
- [Source: `CLAUDE.md#5 Data Protection — DSGVO/GDPR`, `#6 Testing`, `#8 Package Structure`]
- [Source: `_bmad-output/implementation-artifacts/epic-3-retro-2026-09-06.md#6 Action Items` (1,2,4,5)]
- [Source: `backend/.../collaboration/domain/Household.java`, `application/command/AddStore{,Handler}.java`,
  `adapter/in/StoreController.java`, `adapter/out/DomainEventJsonCodec.java`,
  `adapter/out/HouseholdReadModelProjector.java`, `adapter/in/WriteErrorAdvice.java`]
- [Source: `backend/.../identity/application/{Resolve,Mint}MemberIdentity.java`,
  `domain/MemberMappingRepository.java`, `adapter/out/JdbcMemberMappingRepository.java`,
  `test/.../identity/NoPersistedPersonalDataTest.java`]
- [Source: `app/lib/features/onboarding/presentation/onboarding_wizard_page.dart`,
  `app/lib/features/households/presentation/manage_household_page.dart`,
  `app/lib/features/stores/data/stores_api.dart`, `app/lib/shared/commands/command_intent.dart`]

## Questions for Timo (non-blocking — sensible defaults chosen)

1. **Error-advice contract test (Epic-3 Action 5, due this story).** Default = keep the distributed
   per-endpoint coverage (which has held for three epics) and treat the standalone contract test as
   superseded. Confirm "retire it", or say "build it now" and it becomes an added task.
2. **Show the invitee's email in the pending-invites list?** Default = **no** (read model holds no
   email, privacy-first; list shows date/inviter/status). If you want the address shown to household
   members, it means reading the side-store at query time — a deliberate, small PII-resurfacing to
   the household's own members. Say the word and it's an added task; otherwise it stays out.
3. **`RevokeInvite` is out of 4.1** (state model routes it to 4.3). Confirmed by omission — the
   pending list is read-only here; revoke ships in 4.3.

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

None — no failing CI run or crash dump to link. Local iteration notes: a Postgres `PSQLException`
("Can't infer the SQL type … java.time.Instant") in `JdbcInviteReadModel` was fixed by binding
`java.sql.Timestamp` explicitly; a resulting nanosecond-vs-microsecond precision mismatch in
`HouseholdReadModelProjectorTest` was fixed by truncating the test's `Instant` to `ChronoUnit.MICROS`
before asserting equality (`TIMESTAMPTZ` is microsecond-precision).

### Completion Notes List

- Full vertical slice shipped per the locked decisions: backend invite domain/application/adapters
  (T1–T21) and the Flutter invite UI (T22–T26).
- AD-6 GDPR guarantee is structural, not incidental: `MemberInvited`/`InviteExpired` and the
  `invite_read_model` carry no raw email — only `EmailHmac` (a stable-secret HMAC digest); the raw
  address lives solely in the mutable `invite_email_side_store`, purged on lazy expiry (accept/revoke/
  erasure purge points are wired for 4.2/4.3/Epic 6 but not exercised here). Verified by a dedicated
  `EmailHmac`-only-component assertion, a codec no-"@"/no-`"email"` JSON assertion, and the
  `InviteControllerTest`'s no-email-in-response assertion.
- `NoPersistedPersonalDataTest` now whitelists exactly `V13__invite_email_side_store.sql` by filename
  (not by pattern) and gained a regression test proving the guard still fires on the new
  `invite_read_model` migration if it ever grew an email column.
- E5 (already-a-member) is enforced at the application/ACL seam via a new
  `FindHouseholdMemberByEmail` port; the 4.1 implementation (`DeferredFindHouseholdMemberByEmail`) is
  a documented stub returning "unknown" always — exercised in tests via a fake that returns a member,
  per locked decision 2. The real Keycloak lookup is deferred to 4.2/4.6.
- Questions for Timo: proceeded with all three defaults as instructed (retire the standalone
  error-advice contract test call as superseded; keep the pending-invites list email-free; confirm
  `RevokeInvite` stays out of 4.1).
- Deviation: `InvitePersonHandlerTest`'s past-TTL test needed the ordinary `Household.create` event
  count (2) accounted for in its final event-count assertion; caught and fixed during TDD (no scope
  change).
- Client-side note: the optimistically-appended pending-invite row has no real `invitedBy` display
  name available client-side (the read model/API only ever carries the opaque `MemberId`), so the
  optimistic row's `invitedBy` is empty until the next `GET` refresh reconciles it — acceptable since
  the UI never renders `invitedBy` as a name (AC7 shows date + status only).
- Full suites run and green, named explicitly:
  - Backend: `./gradlew test` from `backend/` — **710 tests, 0 failures** (includes ArchUnit's
    `HexagonalArchitectureTest` and every Testcontainers-backed integration test).
  - Flutter: `flutter test` from `app/` — **531 tests, 0 failures**; `flutter analyze` — **no issues
    found**.

### File List

**Backend — new files**
- `backend/src/main/java/de/sgart/shared/InviteId.java`
- `backend/src/main/java/de/sgart/collaboration/domain/EmailHmac.java`
- `backend/src/main/java/de/sgart/collaboration/domain/Invite.java`
- `backend/src/main/java/de/sgart/collaboration/domain/event/MemberInvited.java`
- `backend/src/main/java/de/sgart/collaboration/domain/event/InviteExpired.java`
- `backend/src/main/java/de/sgart/collaboration/domain/exception/DuplicatePendingInviteException.java`
- `backend/src/main/java/de/sgart/collaboration/domain/readmodel/InviteReadModel.java`
- `backend/src/main/java/de/sgart/collaboration/domain/readmodel/InviteView.java`
- `backend/src/main/java/de/sgart/collaboration/application/NormalizedEmail.java`
- `backend/src/main/java/de/sgart/collaboration/application/InviteEmailHasher.java`
- `backend/src/main/java/de/sgart/collaboration/application/InviteEmailSideStore.java`
- `backend/src/main/java/de/sgart/collaboration/application/command/InvitePerson.java`
- `backend/src/main/java/de/sgart/collaboration/application/command/InvitePersonHandler.java`
- `backend/src/main/java/de/sgart/collaboration/application/query/ListPendingInvites.java`
- `backend/src/main/java/de/sgart/collaboration/application/exception/DuplicatePendingInviteApplicationException.java`
- `backend/src/main/java/de/sgart/collaboration/application/exception/AlreadyAHouseholdMemberApplicationException.java`
- `backend/src/main/java/de/sgart/collaboration/application/exception/InvalidInviteEmailApplicationException.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/in/InviteController.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/HmacSha256InviteEmailHasher.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/JdbcInviteEmailSideStore.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/JdbcInviteReadModel.java`
- `backend/src/main/java/de/sgart/identity/application/FindHouseholdMemberByEmail.java`
- `backend/src/main/java/de/sgart/identity/adapter/out/DeferredFindHouseholdMemberByEmail.java`
- `backend/src/main/resources/db/migration/V12__invite_read_model.sql`
- `backend/src/main/resources/db/migration/V13__invite_email_side_store.sql`

**Backend — modified files**
- `backend/src/main/java/de/sgart/collaboration/domain/Household.java`
- `backend/src/main/java/de/sgart/collaboration/application/CommandFieldTranslations.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/in/WriteErrorAdvice.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/DomainEventJsonCodec.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/HouseholdReadModelProjector.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/CollaborationApplicationConfig.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/CollaborationReadModelConfig.java`
- `backend/src/main/java/de/sgart/identity/adapter/out/IdentityBeansConfig.java`
- `backend/src/main/resources/application.yaml`

**Backend — new tests**
- `backend/src/test/java/de/sgart/shared/InviteIdTest.java`
- `backend/src/test/java/de/sgart/collaboration/domain/EmailHmacTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/out/HmacSha256InviteEmailHasherTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/out/JdbcInviteEmailSideStoreTest.java`
- `backend/src/test/java/de/sgart/identity/application/FindHouseholdMemberByEmailTest.java`
- `backend/src/test/java/de/sgart/collaboration/application/InvitePersonHandlerTest.java`
- `backend/src/test/java/de/sgart/collaboration/application/ListPendingInvitesTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/in/InviteControllerTest.java`

**Backend — modified tests**
- `backend/src/test/java/de/sgart/collaboration/domain/HouseholdTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/out/DomainEventJsonCodecTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/out/HouseholdReadModelProjectorTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/out/HouseholdReadModelSubscriptionTest.java`
- `backend/src/test/java/de/sgart/identity/NoPersistedPersonalDataTest.java`

**Client — new files**
- `app/lib/features/invites/data/pending_invite.dart`
- `app/lib/features/invites/data/invites_api.dart`
- `app/lib/features/invites/presentation/invites_state.dart`
- `app/lib/features/invites/presentation/invite_email_validator.dart`
- `app/lib/features/invites/presentation/invites_cubit.dart`
- `app/lib/features/invites/presentation/invites_view.dart`
- `app/lib/features/invites/presentation/invite_page.dart`

**Client — modified files**
- `app/lib/features/households/presentation/first_run_router.dart`
- `app/lib/features/households/presentation/manage_household_page.dart`
- `app/lib/features/onboarding/presentation/onboarding_wizard_page.dart`
- `app/lib/shared/errors/error_message_resolver.dart`
- `app/lib/l10n/app_de.arb`

**Client — new tests**
- `app/test/support/fake_invites_dependencies.dart`
- `app/test/features/invites/data/invites_api_test.dart`
- `app/test/features/invites/presentation/invites_cubit_test.dart`
- `app/test/features/households/presentation/manage_household_page_test.dart`

**Client — modified tests**
- `app/test/features/onboarding/presentation/onboarding_wizard_page_test.dart`

## Change Log

| Date | Change |
|---|---|
| 2026-09-06 | Story implemented end-to-end (backend T1–T21, client T22–T26); backend `./gradlew test` 710/0, `flutter test` 531/0, `flutter analyze` clean; Status → review. |
| 2026-09-06 | Applied all 13 code-review patches (D1/D2 + 11 more). The AC7 inviter-name follow-up remains open (scheduled alongside 4.2/4.3); 3 findings stay deferred (see `deferred-work.md`). |
| 2026-09-06 | Re-reviewed patches (all 13 verified in-code); committed to `main` (c5b84c0) + pushed. Status → done. |

## Review Findings (code review 2026-09-06)

Three adversarial layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor) at Opus 4.8.
26 raw findings → 17 after dedup + dismissing 1 as noise.

### Decisions resolved (2026-09-06, Timo)

- **D1 → option 2 (profile guard):** introduce Spring profiles and gate the default HMAC secret to
  dev/test only, so a prod deploy fails fast without an explicit `SGART_INVITE_EMAIL_HMAC_SECRET`.
  Now tracked as a patch below.
- **D2 → option 1 (correct comment now, defer outbox):** the append-before-side-store ordering is
  correct; fix the misleading "self-heals (a later retry)" javadoc now (patch below), and log the
  outbox/compensation as deferred debt (see `deferred-work.md`).
- **D3 → option 2 (schedule follow-up):** inviter-name resolution for the pending list is a follow-up
  (see Follow-up Actions below), not a 4.1 code change.

### Follow-up actions (new work, not patched in 4.1)

- [ ] [Review][Followup] **Surface the inviter name in the pending-invites list (AC7).** Requires
  resolving the opaque `MemberId` → a display name at query time (an Identity-ACL read seam). Schedule
  as its own task/story alongside the 4.2/4.3 invite work.

### Patch

- [x] [Review][Patch] **[D1]** Prod deploy silently uses the committed default HMAC secret — add a
  Spring profile guard so the dev default is dev/test-only and prod fails fast without an explicit
  `SGART_INVITE_EMAIL_HMAC_SECRET` [backend/src/main/resources/application.yaml:56]
- [x] [Review][Patch] **[D2]** Correct the misleading "self-heals (a later retry)" javadoc on the
  append-before-side-store ordering — no retry path exists; note the compensation as deferred debt [backend/.../application/command/InvitePersonHandler.java:38]

- [x] [Review][Patch] Missing email-length guard → over-320-char address passes the regex, appends
  the event, then 500s on the `VARCHAR(320)` side-store insert (orphaned invite) [backend/.../application/NormalizedEmail.java:36]
- [x] [Review][Patch] Already-a-member seam is passed `rawEmail` instead of `normalizedEmail.value()`
  — latent AC3 bypass once the real Keycloak lookup lands (4.2) [backend/.../application/command/InvitePersonHandler.java:105]
- [x] [Review][Patch] `TIME_TO_LIVE` duplicated with a factually-wrong justification (the class
  already depends on `domain`); drifting copies would desync read-model expiry from the aggregate [backend/.../adapter/out/JdbcInviteReadModel.java:27]
- [x] [Review][Patch] Read-model expiry cutoff uses wall-clock `Instant.now()` instead of an injected
  `Clock` — cross-node skew + untestable derived expiry [backend/.../adapter/out/JdbcInviteReadModel.java:37]
- [x] [Review][Patch] AC6 query-time derived-expiry (`invited_at + TTL`) has zero test coverage
  (only the explicit-`InviteExpired` path is tested) [backend/.../adapter/out/JdbcInviteReadModelTest]
- [x] [Review][Patch] The "append-before-side-store" test can't detect ordering — the fake counter
  increments on every `store()` call regardless of order, so the T13 guarantee is unverified [backend/.../application/InvitePersonHandlerTest.java:255]
- [x] [Review][Patch] Onboarding invite step is a silent dead-end if its `bootstrap()` GET fails —
  the send button stays enabled but `sendInvite` early-returns (status != ready) with no feedback [app/.../onboarding/presentation/onboarding_wizard_page.dart:347]
- [x] [Review][Patch] Pending-invite status rendered as the raw English enum (`PENDING`/`EXPIRED`)
  in an otherwise-German UI — violates the language policy [app/.../invites/presentation/invites_view.dart:128]
- [x] [Review][Patch] `pendingInvitesOf` has no `ORDER BY` — arbitrary, non-deterministic list order [backend/.../adapter/out/JdbcInviteReadModel.java:39]
- [x] [Review][Patch] Dead `adapter.out` import (javadoc-only, ArchUnit can't see it) + outward
  `application.query` import in a domain port [backend/.../domain/readmodel/InviteReadModel.java:3]
- [x] [Review][Patch] Stale ARB descriptions contradict the now-functional send ("disabled in this
  story", "(non-sending)") — fix-rigor / Epic-3 Action 4 [app/lib/l10n/app_de.arb:783]

### Deferred

- [x] [Review][Defer] Unicode NFC normalization not applied before hashing — NFC vs NFD forms of the
  same address hash differently (AC2 miss); not triggered by NFC-emitting clients [backend/.../application/NormalizedEmail.java:35] — deferred, low-likelihood
- [x] [Review][Defer] TTL-boundary nanosecond (domain) vs microsecond (`TIMESTAMPTZ`) precision
  divergence — a sub-microsecond one-tick window; aggregate stays source of truth [backend/.../adapter/out/JdbcInviteReadModel.java:37] — deferred, negligible
- [x] [Review][Defer] Reused `inviteId` on a past-TTL re-invite would strand the read-model row
  (`markExpired` then `INSERT ... DO NOTHING`) — not reachable via the shipped client (fresh
  `inviteId` per intent) [backend/.../adapter/out/JdbcInviteReadModel.java:59] — deferred, not reachable

### Dismissed (noise / false positive)

- Client "duplicate optimistic row on a deduped retry" — not reachable: a lost-response retry
  performs only one optimistic append, `bootstrap()` full-replaces the list, and `ValueKey`
  duplicates don't crash.
