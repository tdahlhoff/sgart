---
baseline_commit: 57ab53eaf40e1dc24b8f5a809227fddcbec3266c
---

# Story 4.2: Accept an invite and join

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an invited person,
I want to open my invite and join the household,
so that I become a member and can see and work on our shared lists.

This is **Epic 4's second story** and the moment SGART first becomes **multi-person**: a household
gains a *second* real member. It completes the invite half-loop Story 4.1 opened (an invite exists;
now it can be redeemed) and lights up the Epic-1 „Auf Einladung warten" placeholder
(`await_invite_page.dart`) that has been an informational dead-end since Story 1.9 (Epic-1 retro
Action 5). It builds directly on the up-front membership state model:
`_bmad-output/planning-artifacts/epic-4-membership-state-model.md` (implements the **4.2 row of §7**).

## Locked Decisions (Timo, 2026-09-06)

Decided at story-creation time; binding for implementation. They fold into the ACs and tasks below.

1. **Core accept slice; OS deep-link + web fallback stay in 4.6.** 4.2 builds the full accept-and-join
   vertical slice — backend `AcceptInvite` domain/command/handler + a Flutter **Accept-invite screen**
   reachable in-app (the person pastes their invite link/code). Platform **deep-link registration**
   (`app_links` intent filters / Universal Links), the **browser web-fallback** page, and the
   **Keycloak realm/redirect** wiring all land in **Story 4.6** (state model §7; spine "Deferred →
   invite deep-link/web-fallback flow"). Define the invite-link **format + parser now** so 4.6's deep
   link reuses it unchanged.
2. **Real Keycloak email delivery + Admin email→user lookup stay deferred to 4.6.** Accept never needs
   the email→user lookup — the joiner's `MemberId` is minted from **their own JWT** (`MintMemberIdentity`,
   idempotent per household), not from an email. `DeferredFindHouseholdMemberByEmail` stays stubbed;
   no real invite email is sent in 4.2 (the invitee obtains the link out-of-band for testing/manual
   use). **Fix-rigor (Epic-3 Action 4):** update `DeferredFindHouseholdMemberByEmail`'s javadoc — it
   currently says the real lookup ships "in Stories 4.2/4.6"; correct it to **4.6** so no doc claims
   4.2 does it.
3. **The invite link is a bearer capability (UUID), not email-bound.** Whoever holds the personal link
   (`householdId` + unguessable `inviteId` UUID) and authenticates via Keycloak joins as
   `HouseholdRole.PARTICIPANT`. Accept does **not** verify that the authenticated account's email equals
   the invited address (that would require the deferred Keycloak email lookup, decision 2). E5
   (already-a-member) is handled structurally by idempotent mint — no duplicate `MemberJoined`.
4. **Scope = full vertical slice** (backend + Flutter accept UI), mirroring the 4.1 precedent.

## Acceptance Criteria

From `epics.md#Story 4.2` (BDD), with the locked decisions applied. In 4.2 the invite is redeemed via
an **in-app Accept screen** (paste the link/code); the OS deep-link and web-fallback entry points that
*route to* this same accept outcome are Story 4.6 (decision 1).

1. **AC1 — Accept and join as Participant.** Given a valid (PENDING, in-TTL) personal invite, when the
   invitee opens the Accept screen with the invite link/code and authenticates via Keycloak, then
   `AcceptInvite` succeeds: the Identity ACL **mints the joiner's `MemberId`** (AD-5, the sole minter),
   `InviteAccepted(inviteId, memberId)` and `MemberJoined(memberId, PARTICIPANT)` are appended to the
   `household-{id}` stream, the joiner becomes a member, and the client routes into the joined
   household (the household now appears in their switcher). `POST .../invites/{inviteId}/accept`
   returns `200` (no body; the client already holds `householdId`).

2. **AC2 — Side-store purged on accept.** Given a successful accept, when membership is created, then
   the invite's raw-email row in `invite_email_side_store` is **purged** (`InviteEmailSideStore.purge(inviteId)`,
   AD-6) — accept is the first purge point the port exposes to actually fire. The invite leaves the
   inviter's pending-invites list (`invite_read_model` status → `ACCEPTED`, so `ListPendingInvites`
   no longer returns it).

3. **AC3 — Expired invite cannot be redeemed.** Given a **past-TTL** pending invite, when it is
   accepted, then it is **rejected** with a distinct client-localizable error (mapped `410 Gone`), the
   lazy `InviteExpired` transition is **persisted** (housekeeping — the invite becomes EXPIRED on the
   stream) and its side-store row is purged, and **no** `MemberJoined` is appended. An already-EXPIRED
   invite is likewise rejected (no second `InviteExpired`).

4. **AC4 — Already-a-member is a no-op (E5).** Given an invitee who is **already a member** of that
   household, when they open an in-TTL invite, then it is a **no-op join**: the invite is consumed
   (`InviteAccepted` raised, side-store purged) but **no duplicate `MemberJoined`** is appended (the
   idempotent mint replays their existing `MemberId`, which is already in `rolesByMember`); they are
   simply taken into the household. A retried accept of the *same* intent (same `commandId`) converges
   idempotently (AD-8) — never a second membership.

5. **AC5 — Unknown / consumed invite rejected.** Given an `inviteId` that does not exist on the
   household stream, when accept is attempted, then it is rejected (`404`, `InviteNotFoundException`).
   Given an invite already consumed (ACCEPTED) and a caller who is **not** the member who consumed it,
   then it is rejected (`409`) — a spent link cannot be ridden by a stranger. (`RevokeInvite`/REVOKED
   is Story 4.3; not reachable here.)

6. **AC6 — Client Accept-invite slice.** The Epic-1 „Auf Einladung warten" screen (`await_invite_page.dart`)
   becomes functional: paste an invite link/code → „Beitreten" accepts → on success the person is
   routed into the household; on expiry/not-found/already-consumed a distinct inline German error is
   shown. Client-side fail-fast on a malformed/empty link (parse before sending); `commandId`
   idempotency (regenerate on payload change / after success — Epic-1 Action) + `isSubmitting`
   re-entrancy guard (Epic-2 Action 3); a11y labels on the new field + button; German localization.

## Tasks / Subtasks

> Each task lists its expected test(s) — see the **Test Manifest**. A `[x]` task with no matching
> test is an integrity failure (Epic-3 retro Action 2). TDD is the default (CLAUDE.md §6).

### Backend — domain (Household aggregate gains accept)

- [x] **T1. `InviteAccepted` domain event** (AC1) —
  `de.sgart.collaboration.domain.event.InviteAccepted (EventId, HouseholdId, InviteId, MemberId memberId)`
  on the `household-{id}` stream. Carries **only** the ACL-minted joiner `MemberId` — no email/HMAC/
  keycloakUserId (AD-5/AD-6). Mirror `MemberInvited`'s record shape; implements `DomainEvent`.
- [x] **T2. `InviteNotFoundException` domain exception** (AC5) —
  `de.sgart.collaboration.domain.exception.InviteNotFoundException`, mirroring `ItemNotFoundException`.
- [x] **T3. `InviteExpiredException` domain exception** (AC3) —
  `de.sgart.collaboration.domain.exception.InviteExpiredException`. Signals a redeem-past-TTL /
  already-expired rejection.
- [x] **T4. `InviteAlreadyConsumedException` domain exception** (AC5) —
  `de.sgart.collaboration.domain.exception.InviteAlreadyConsumedException` for an accept against an
  ACCEPTED invite by a caller who is not already a member.
- [x] **T5. `InviteStatus.ACCEPTED` + `apply(InviteAccepted)`** (AC1, AC4) — add `ACCEPTED` to the
  private `InviteStatus` enum in `Household`; in `apply(...)`, `InviteAccepted` → mark that
  `InviteState` `ACCEPTED` (reuse the existing `withStatus`). `MemberJoined` is already folded into
  `rolesByMember` (do not change it).
- [x] **T6. `Household.acceptInvite(...)` + fold** (AC1, AC3, AC4, AC5) — add:
  - Signature: `acceptInvite(InviteId inviteId, MemberId joiner, Instant now, CommandId commandId)`.
    **No membership gate** — accept is precisely how a non-member becomes one (contrast `invitePerson`'s
    `requireMember`). `now` is caller-injected (never `Instant.now()` here), like `invitePerson`.
  - Branch on the folded `InviteState` for `inviteId`:
    1. **absent** → throw `InviteNotFoundException` (no event).
    2. **PENDING & not expired at `now`** → `raise(InviteAccepted(joiner))`; **and if
       `!rolesByMember.containsKey(joiner)`** → `raise(MemberJoined(joiner, PARTICIPANT))` (E5: skip the
       join event when already a member — AC4, §3⚠/§3.5).
    3. **PENDING & expired at `now`** → `raise(InviteExpired(inviteId))` (persist the lazy transition),
       then throw `InviteExpiredException` (AC3). *(The handler appends the raised `InviteExpired` on
       this exception path — see T9.)*
    4. **EXPIRED** → throw `InviteExpiredException` (no new event).
    5. **ACCEPTED** → if `rolesByMember.containsKey(joiner)` → **no-op success**, raise nothing
       (idempotent re-accept, AD-8/§3.5); else → throw `InviteAlreadyConsumedException` (AC5).
  - Reuse `InviteState.isExpiredAt(now)` (already present from 4.1). Update the aggregate javadoc to
    list `InviteAccepted` in the folded-events sentence.
  - [x] `HouseholdTest` cases: `acceptInvite_onAPendingInTtlInvite_raisesInviteAcceptedAndMemberJoinedAsParticipant`;
    `acceptInvite_bySomeoneAlreadyAMember_raisesInviteAcceptedButNoSecondMemberJoined`;
    `acceptInvite_onAPastTtlPendingInvite_raisesInviteExpiredThenThrowsInviteExpired`;
    `acceptInvite_onAnAlreadyExpiredInvite_throwsInviteExpiredWithoutANewEvent`;
    `acceptInvite_onAnUnknownInvite_throwsInviteNotFound`;
    `acceptInvite_reAcceptedByTheSameJoiner_isAConvergentNoOp`;
    `acceptInvite_onAConsumedInviteByANonMember_throwsInviteAlreadyConsumed`.

### Backend — application (command, handler, exceptions)

- [x] **T7. Application exceptions + `WriteErrorAdvice` mappings** (AC3, AC5) — add
  `InviteExpiredApplicationException` (**410 GONE**), `InviteNotFoundApplicationException` (404),
  `InviteAlreadyConsumedApplicationException` (409), each with an `errorDescriptor()` + a distinct
  client-facing `code`. Register one `@ExceptionHandler` per type in `WriteErrorAdvice` (410 is a new
  status in that advice — use `HttpStatus.GONE`). Mirror the 4.1 additions.
- [x] **T8. `AcceptInvite` command** (AC1) —
  `de.sgart.collaboration.application.command.AcceptInvite (HouseholdId, InviteId, CommandId, AggregateVersion basedOnVersion)`
  beside its handler (CLAUDE.md §8). No email/role in the command (role is fixed PARTICIPANT in the
  domain; the joiner id comes from the JWT via mint, never the body).
- [x] **T9. `AcceptInviteHandler`** (AC1–AC5) — mirror `InvitePersonHandler`/`CreateHouseholdHandler`,
  injecting `EventStore`, `MintMemberIdentity`, `InviteEmailSideStore`, `Clock`:
  - `handle(String keycloakUserId, String rawHouseholdId, String rawInviteId, String rawCommandId)`:
    1. parse envelope via `CommandFieldTranslations` (`toHouseholdId`, `toInviteId`, `toCommandId`; 400
       on malformed).
    2. **mint-then-append (AD-5):** `MemberId joiner = mintMemberIdentity.mint(keycloakUserId, householdId)`
       *before* the domain call — `MemberJoined`/`InviteAccepted` must carry the minted id. Idempotent:
       a joiner who is already a member replays their existing id (drives AC4).
    3. `Household household = Household.rehydrate(streamId, eventStore.readStream(streamId))`;
       `loadedVersion = household.version()`.
    4. call `household.acceptInvite(inviteId, joiner, clock.instant(), commandId)` inside a `try`:
       - **on success:** `eventStore.append(loadedVersion, household.uncommittedEvents(), commandId)`;
         then `inviteEmailSideStore.purge(inviteId)` (AC2 — purge-on-accept). *(Append-before-purge, the
         4.1 ordering rationale — an orphan side-store row is harmless; a purge before a lost append would
         drop deliverability for an invite still pending.)* A no-op re-accept (empty `uncommittedEvents`)
         still purges idempotently and returns success.
       - **catch `InviteExpiredException`:** if `uncommittedEvents` is non-empty (branch-3 lazy
         `InviteExpired`), `append(...)` it (persist the transition) **and** `purge(inviteId)`; then
         throw `InviteExpiredApplicationException` (410). (Already-EXPIRED / branch-4: nothing to append.)
       - **catch `InviteNotFoundException`** → `InviteNotFoundApplicationException` (404).
       - **catch `InviteAlreadyConsumedException`** → `InviteAlreadyConsumedApplicationException` (409).
  - `void` return (the client already holds `householdId`; it re-bootstraps to route in — AC1/AC6).
  - [x] `AcceptInviteHandlerTest` (fast, in-memory doubles): appends `InviteAccepted` + `MemberJoined`
    on a valid invite and purges the side-store; **already-member → `InviteAccepted` only, no second
    `MemberJoined`** (fake mint returns an existing member id already on the stream); past-TTL →
    `InviteExpired` appended + side-store purged + `InviteExpiredApplicationException` (410); already-
    EXPIRED → 410, no event appended; unknown invite → 404, nothing appended; **purge happens only after
    a successful append**; same-`commandId` retry converges (EventStore dedup).

### Backend — adapters (codec, projector, read model, controller)

- [x] **T10. Register `InviteAccepted` in `DomainEventJsonCodec`** (AC1) — add `INVITE_ACCEPTED_TYPE`
  ("InviteAccepted"), the `toJsonBytes`/`fromJsonBytes` cases, and an `InviteAcceptedPayload
  (eventId, householdId, inviteId, memberId)`. `MemberJoined` is already registered (reused). Stable
  string tag, never the class name.
  - [x] `DomainEventJsonCodecTest` — round-trip `InviteAccepted`; **assert the payload has no email/HMAC
    component** (only `memberId`) — the privacy round-trip guard, matching the 4.1 `MemberInvited` guard.
- [x] **T11. `JdbcInviteReadModel.markAccepted` + projector case** (AC2) — add
  `markAccepted(HouseholdId, InviteId)` → `UPDATE invite_read_model SET status = 'ACCEPTED' WHERE ...`
  (idempotent flag flip, mirroring `markExpired`). In `HouseholdReadModelProjector.project`, add
  `case InviteAccepted accepted -> inviteReadModel.markAccepted(...)`. Rides the **existing**
  `household-{id}` prefix subscription — do **not** add a second subscription. **No migration** — V12's
  `status VARCHAR(20)` already accommodates `ACCEPTED` (verify `markAccepted` is covered by the
  `NoPersistedPersonalDataTest` — it adds no column, so the guard is untouched).
  - [x] `HouseholdReadModelProjectorTest` (Testcontainers) — `InviteAccepted` flips PENDING → ACCEPTED
    so the invite drops out of `pendingInvitesOf`; `MemberJoined(PARTICIPANT)` adds the joiner to the
    household member set; **two-household isolation** + **replay idempotency** (re-projecting accept is a
    no-op) — Epic-2 Action 4.
- [x] **T12. `InviteController` accept endpoint** (AC1, AC3, AC5) — add to the existing controller:
  `@PostMapping("/{inviteId}/accept")` `@ResponseStatus(HttpStatus.OK)` `accept(@AuthenticationPrincipal
  Jwt jwt, @PathVariable String householdId, @PathVariable String inviteId, @RequestBody AcceptInviteRequest
  request)` → `acceptInviteHandler.handle(caller.keycloakUserId(), householdId, inviteId, request.commandId())`.
  `AcceptInviteRequest{commandId}`. Caller identity from JWT only (AR10/AD-5). No response body.
  - [x] `InviteControllerTest` (`@WebMvcTest`) — `200` on accept; `410` expired; `404` unknown; `409`
    already-consumed; **assert no email in any request/response field**.

### Backend — privacy guarantees (first-class, §6)

- [x] **T13. Accept exercises the AD-6 purge-on-accept guarantee** (AC2) — covered by T9 (handler purge
  assertion) + T10 (codec no-PII payload). Add/extend a focused privacy assertion that `InviteAccepted`
  has no record component whose name contains `email`/`hmac` (only `memberId`) — mirror the 4.1
  `InvitePrivacyTest`/`NoPersistedPersonalDataTest` component check. No migration/column change, so
  the `NoPersistedPersonalDataTest` column guard is unaffected (confirm it stays green).

### Client — Flutter (invite acceptance)

- [x] **T14. Invite-link format + parser** (AC6, decision 1) — add
  `app/lib/features/invites/data/invite_link.dart`: an `InviteLink{householdId, inviteId}` value +
  `InviteLink.tryParse(String)` accepting the canonical link SGART will also deep-link/web-fallback in
  4.6 — an `https` URL `.../invite?h=<householdId>&i=<inviteId>` (also accept a bare `h=..&i=..` /
  `householdId:inviteId` code form). Returns `null` on anything malformed (drives client fail-fast).
  Keep it pure (no Flutter deps) so 4.6's deep-link handler reuses it.
  - [x] `invite_link_test.dart` — parses the URL form; parses the code form; rejects malformed/empty/
    missing-param inputs.
- [x] **T15. `InvitesApi.acceptInvite`** (AC1) — add
  `Future<void> acceptInvite(String householdId, {required String inviteId, required String commandId})`
  → `POST /api/v1/households/{householdId}/invites/{inviteId}/accept` with `{commandId}`. Mirror
  `sendInvite` (caller-minted `commandId`, no response body).
  - [x] `invites_api_test.dart` — accept request shape (path + body).
- [x] **T16. `AcceptInviteCubit` / `AcceptInviteState`** (AC6) — a small cubit (or an extension of the
  invites feature) that: parses the pasted link via `InviteLink.tryParse` (fail-fast → invalid-link
  error, no call); accepts via `command_intent.dart` (one `commandId` per intent, regenerate on payload
  change / after success — Epic-1 Action) with an `isSubmitting` guard (Epic-2 Action 3); surfaces
  `410`/`404`/`409` as distinct inline errors (expired / not-found / already-used) via
  `error_message_resolver.dart`. On success emits a "joined(householdId)" signal the screen uses to
  refresh routing.
  - [x] `accept_invite_cubit_test.dart` — success; malformed link blocked client-side (no API call);
    expired → error; not-found → error; already-used → error; `isSubmitting` guard; `commandId`
    regenerated after success.
- [x] **T17. Light up `AwaitInvitePage`** (AC6, Epic-1 retro Action 5) — replace the informational
  dead-end with a functional Accept screen: an invite-link/code field (`Key('await-invite-link-field')`)
  + „Beitreten" button (`Key('await-invite-join-button')`), inline error surface, keep „Zurück". On a
  successful join, refresh the household routing so the joiner lands in the household — call
  `HouseholdsCubit.bootstrap()` (0 → 1 households → the shell). Provide the `InvitesApi` across the
  route boundary the same way `create_or_await_choice_page.dart` re-provides its deps by value (the
  `ProviderNotFoundException` lesson). **Fix-rigor (Epic-3 Action 4):** update the class javadoc — it
  currently says "invite acceptance is Epic 4 / informational dead-end"; it now accepts.
  - [x] `await_invite_page_test.dart` — paste a link + join → success routes on; malformed link shows the
    inline error without an API call; expired shows the inline error; „Zurück" still pops.
- [x] **T18. Localization + a11y** (AC6) — add German keys to `app/lib/l10n/app_de.arb` for the accept
  field label/hint, „Beitreten", and the four error messages (invalid link / expired / not-found /
  already-used); a11y `Semantics`/labels on the field + button. Replace the old „warten"-only ARB copy
  that framed the screen as a dead-end (no stale strings — Epic-3 Action 4). `flutter analyze` clean.

### Definition of Done (standing, per retros)

- [x] Full suites green **and named**: backend `./gradlew test` (incl. ArchUnit + Testcontainers) **and**
  `flutter test` + `flutter analyze` (CLAUDE.md §6; backend-test-hygiene). Report which suite ran.
- [x] commandId lifecycle correct; error advice mapped + tested (incl. the new **410**); a11y labels on
  new widgets; no dead code/strings/stale comments; client fail-fast on the link input (Epic-1 DoD).
- [x] Optimistic/routing check (Epic-2 Action 1): the join's success routing (bootstrap → shell) and
  error rollback verified in dev, not left for review.
- [x] `invite_read_model` behavior change (accept → ACCEPTED drops from pending) ships with its
  isolation + replay projector test in this change (Epic-2 Action 4).
- [x] Every `[x]` task has its Test-Manifest test actually present (Epic-3 Action 2).

## Dev Notes

### Ground truth — read these before coding
- **Membership state model (authoritative vocabulary):**
  `_bmad-output/planning-artifacts/epic-4-membership-state-model.md` — §1 vocabulary (`AcceptInvite`
  → `InviteAccepted` + `MemberJoined`), §2a invite state machine (lazy expiry on accept), §2b membership
  machine, §3⚠ + §3.5 the E5 already-member no-op and convergent no-ops, §4 the cross-context accept
  flow (mint → command → purge), §5 read models, §6 GDPR, **§7 the 4.2 row this story implements**.
- **Architecture spine:** `.../architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md` —
  AD-4 (command→event, projection-only read models, expected-version), **AD-5 (`MemberId` pseudonym,
  Identity ACL is the sole minter — invite acceptance is *the* mint moment)**, AD-6 (no persisted PII;
  raw email only in the mutable side-store, purged on accept), AD-8 (commandId idempotency / convergent
  no-ops), AD-10 (Invite is an entity of `Household`), AD-11 (ubiquitous language). Note the spine's
  Deferred list explicitly parks "invite deep-link/web-fallback flow" as build-time detail → 4.6.

### Patterns to mirror (exact files)
- **`Household.acceptInvite`** ← the existing `Household.invitePerson` (folded `pendingInvitesById`
  lookup, `now`-injected expiry via `InviteState.isExpiredAt`, `raise(...)` per branch). Accept differs
  in the *no membership gate* and in raising `InviteAccepted`(+`MemberJoined`) rather than `MemberInvited`.
- **Mint-then-append** ← `application/command/CreateHouseholdHandler.java` (mint the `MemberId` via
  `MintMemberIdentity` *before* the aggregate raises the member event; the idempotent mint replays an
  existing id — which is exactly what makes AC4 a no-op). `AcceptInviteHandler` uses **mint**, never
  `ResolveMemberIdentity` — the joiner is (by definition) possibly not yet a member.
- **Handler shape + side-store purge** ← `application/command/InvitePersonHandler.java` (rehydrate →
  `loadedVersion` → domain call → `append` → side-store side-effect; the append-before-side-store
  ordering rationale carries over directly to append-before-purge).
- **Controller** ← the existing `adapter/in/InviteController.java` (add the `/{inviteId}/accept`
  `@PostMapping`; `AuthenticatedCaller.fromJwt`; client-minted `commandId` in the body; no response body).
- **Codec** ← `adapter/out/DomainEventJsonCodec.java` (the 4.1 `MemberInvited`/`InviteExpired` additions
  are the template for `InviteAccepted`).
- **Projector / read model** ← `adapter/out/HouseholdReadModelProjector.java` + `JdbcInviteReadModel.java`
  (`markExpired` is the exact template for `markAccepted`; the projector's existing `MemberJoined`→
  `addMember` case already handles the joiner's membership projection — do not duplicate it).
- **Error advice** ← `adapter/in/WriteErrorAdvice.java` (one `@ExceptionHandler` per application
  exception; 410 GONE is new — `ResponseEntity.status(HttpStatus.GONE)`).
- **Client** ← `features/invites/data/invites_api.dart` (add `acceptInvite`), `shared/commands/command_intent.dart`
  (idempotent commands), `shared/errors/error_message_resolver.dart` (map new codes),
  `features/households/presentation/await_invite_page.dart` (the placeholder to light up) +
  `create_or_await_choice_page.dart` (the by-value re-provide pattern across the route boundary) +
  `households_cubit.dart` (`bootstrap()` to re-route after joining).

### The accept outcome contract (read this — it is the crux)
Accept has **three** externally-distinct outcomes and the handler must tell them apart:
- **joined** (2xx, route into the household) — `InviteAccepted` [+ `MemberJoined`] raised;
- **can't join: expired** (410) — the aggregate throws `InviteExpiredException`; the handler still
  **appends** any `InviteExpired` the aggregate raised (lazy expiry is a real, persisted transition —
  §2a) and purges the side-store, *then* surfaces the 410;
- **can't join: unknown / consumed-by-another** (404 / 409) — thrown before any event, nothing appended.
The already-member case (AC4) is a *joined* outcome with `InviteAccepted` but **no** `MemberJoined`.
Because a command "returns no domain data" (CQRS), success is a bare `200`/`void`; the client re-derives
state by calling `HouseholdsCubit.bootstrap()` (read-your-writes via the ACL member-mapping written by
mint — the household appears without waiting on the projector). This mirrors `CreateHousehold`'s
route-on-response approach.

### Why accept needs no email, no lookup, no new read model (scope guards)
- **No email anywhere in accept.** The joiner is identified by their JWT `sub` → minted `MemberId`. The
  invited email is never compared (decision 3) and never read — `NormalizedEmail`/`InviteEmailHasher`/
  `FindHouseholdMemberByEmail` are **not** touched by this story (the email→user lookup stays the 4.6
  deferral). The only side-store interaction is `purge` (AC2).
- **No `household_member_read_model` yet.** State model §5 routes that (and role display) to **Story 4.3**.
  For 4.2 the joiner's membership is already projected by the existing `MemberJoined`→`addMember` case,
  and their household appears via the Identity ACL's `ListHouseholdsForCaller` (member-mapping). Do
  **not** build a member read model here (YAGNI / KISS — CLAUDE.md §1).
- **No migration.** V12's `status VARCHAR(20)` already holds `ACCEPTED`; the side-store table exists.

### GDPR specifics (§6) — accept is the first purge point that fires
- **Purge-on-accept is the AD-6 guarantee this story newly exercises** — 4.1 wired `InviteEmailSideStore.purge`
  but only lazy-expiry housekeeping called it; accept is the first *success-path* purge. Assert it (T9)
  and keep the side-store the single raw-email location (unchanged from 4.1).
- **`InviteAccepted` carries only `MemberId`** (AD-5/AD-6) — verified by the codec no-PII payload guard
  (T10/T13). No display name/email in the event, read model, or API.
- **Membership as personal data (CLAUDE.md §5):** the new membership row (mint mapping + `MemberJoined`)
  is exactly the pseudonymous, de-linkable representation AD-5/AD-7 prescribe — erasure (Epic 6) removes
  the mapping, leaving an unlinkable `MemberId`. No extra PII is introduced by joining.

### Handler ordering & clock (carry-over from 4.1)
- **Mint-then-append** (AD-5): the minted id must be in `MemberJoined`/`InviteAccepted`. A lost append
  after a successful mint leaves an idempotent-replayable mapping (a retry with a fresh `basedOnVersion`
  converges); same latent shape as `CreateHouseholdHandler`, accepted and precedented.
- **Append-before-purge** (AD-6): purge only after a successful append. An orphan side-store row (append
  ok, purge failed) is harmless and self-corrects on a later purge point; the reverse could strand a
  still-pending invite's deliverability.
- **Inject `Clock`** into `AcceptInviteHandler`; pass `now` into the aggregate so past-TTL expiry is
  deterministic/testable (never `Instant.now()` in the domain).

### Convergence / no-op semantics (AD-8, §3.5)
- Re-accept by the **same** joiner (idempotent retry, same `commandId`) → EventStore dedup makes it a
  true no-op; even without dedup, branch-5 (ACCEPTED + already-member) raises nothing. Both converge on
  one membership.
- Already-a-member accept (AC4) is a **convergent no-op on the join** (InviteAccepted still consumes the
  invite; no second MemberJoined) — deliberately not a rejection.

### Previous-work intelligence (Story 4.1 — read its Dev Agent Record)
- 4.1 shipped the invite entity, `InviteState` fold, `invite_read_model` (V12, status column reused
  here), `invite_email_side_store` (V13, purged here), the `HmacSha256` HMAC, the `WriteErrorAdvice`
  invite mappings, and the Flutter invites feature. 4.2 extends all of these — do not recreate them.
- **4.1 review scars to not repeat:** map every new exception in `WriteErrorAdvice` (a missing map = 500);
  give the projector case a two-household isolation + replay test *in this PR*; make ordering-guarantee
  tests actually detect order (the 4.1 fake-counter finding — assert `append` happened before `purge`,
  e.g. record call order, not just call count); no raw English enum in the German UI; no stale ARB
  descriptions or javadoc left behind (Epic-3 Action 4 — the `await_invite_page` + `DeferredFind...`
  docs both need updating).
- **Epic-3 retro Action 5 (error-advice contract test)** was **retired** in 4.1 (distributed per-endpoint
  coverage kept). 4.2 continues per-endpoint (T9/T12) — no standalone contract test, decision settled.
- **`fromStart`-replay projector debt** (retro §7) is knowingly deferred to Story 4.4 (SSE) — do not
  attempt checkpointing here.

## Test Manifest (task → named test)

| Task | Test(s) |
|---|---|
| T6 | `HouseholdTest`: accept-happy(InviteAccepted+MemberJoined PARTICIPANT) · already-member-no-2nd-join · past-TTL(expire-then-throw) · already-expired(no event) · unknown(not-found) · re-accept-no-op · consumed-by-non-member |
| T9 | `AcceptInviteHandlerTest` (append accept+join+purge · already-member→accept-only-no-join · past-TTL→expire-appended+purge+410 · already-expired→410-no-append · unknown→404-no-append · purge-after-append · same-commandId retry converges) |
| T10 | `DomainEventJsonCodecTest` (round-trip InviteAccepted · no-email/hmac payload guard) |
| T11 | `HouseholdReadModelProjectorTest` (accept→ACCEPTED drops from pending · MemberJoined adds member · two-household isolation · replay idempotency) |
| T12 | `InviteControllerTest` (200 accept · 410 expired · 404 unknown · 409 already-consumed · no-email-in-req/resp) |
| T13 | `HouseholdTest`/`NoPersistedPersonalDataTest` (`assertNoPersonalDataComponent(InviteAccepted.class)` · column guard still green) |
| T14 | `invite_link_test.dart` (URL form · code form · malformed rejected) |
| T15 | `invites_api_test.dart` (accept request shape) |
| T16 | `accept_invite_cubit_test.dart` (success · malformed-blocked · expired · not-found · already-used · isSubmitting · commandId-regen) |
| T17 | `await_invite_page_test.dart` (join routes on · malformed inline error, no call · expired inline error · back pops) |

## Project Structure Notes

- **New backend files** under `de.sgart.collaboration`: `domain/event/InviteAccepted.java`,
  `domain/exception/{InviteNotFound,InviteExpired,InviteAlreadyConsumed}Exception.java`,
  `application/command/AcceptInvite.java` + `AcceptInviteHandler.java`,
  `application/exception/{InviteExpired,InviteNotFound,InviteAlreadyConsumed}ApplicationException.java`.
- **Modified backend files:** `domain/Household.java` (accept method + `InviteStatus.ACCEPTED` +
  `apply(InviteAccepted)` + javadoc), `adapter/in/InviteController.java` (accept endpoint +
  `AcceptInviteRequest`), `adapter/in/WriteErrorAdvice.java` (3 mappings incl. 410),
  `adapter/out/DomainEventJsonCodec.java` (InviteAccepted tag+payload),
  `adapter/out/HouseholdReadModelProjector.java` (InviteAccepted case),
  `adapter/out/JdbcInviteReadModel.java` (`markAccepted`), the bean wiring for `AcceptInviteHandler`
  (where `InvitePersonHandler` is registered — `adapter/out/CollaborationApplicationConfig.java`),
  and `identity/adapter/out/DeferredFindHouseholdMemberByEmail.java` (javadoc: 4.2/4.6 → 4.6).
- **No new migration** (V12 `status` reused; V13 side-store reused). Next free number remains V14.
- **New/modified client files:** `features/invites/data/invite_link.dart` (+ `invites_api.dart` accept
  method), `features/invites/presentation/accept_invite_cubit.dart` + `accept_invite_state.dart`,
  `features/households/presentation/await_invite_page.dart` (light up), `shared/errors/error_message_resolver.dart`
  (new codes), `l10n/app_de.arb`.
- **ArchUnit:** new classes fall under `..domain..`/`..application..`/`adapter.in`/`adapter.out` and obey
  `HexagonalArchitectureTest` unchanged — the domain method throws domain exceptions; `adapter.in`
  translates at the handler seam (never imports `..domain..`); mint is reached through the published
  Identity application port (AD-2).

## References

- [Source: `_bmad-output/planning-artifacts/epic-4-membership-state-model.md` §1, §2a, §2b, §3⚠, §3.5, §4, §5, §7]
- [Source: `_bmad-output/planning-artifacts/epics.md#Story 4.2: Accept an invite and join`]
- [Source: `.../architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md#AD-4, AD-5, AD-6, AD-8, AD-10, AD-11`]
- [Source: `_bmad-output/implementation-artifacts/4-1-invite-a-person-by-email.md` (Dev Agent Record, Review Findings, Test Manifest)]
- [Source: `CLAUDE.md#5 Data Protection — DSGVO/GDPR`, `#6 Testing`, `#8 Package Structure`]
- [Source: `backend/.../collaboration/domain/Household.java` (`invitePerson`/`InviteState`/`apply`),
  `application/command/{CreateHousehold,InvitePerson}Handler.java`,
  `adapter/in/InviteController.java`, `adapter/in/WriteErrorAdvice.java`,
  `adapter/out/{DomainEventJsonCodec,HouseholdReadModelProjector,JdbcInviteReadModel}.java`,
  `identity/application/MintMemberIdentity.java`, `identity/adapter/out/DeferredFindHouseholdMemberByEmail.java`]
- [Source: `app/lib/features/households/presentation/{await_invite_page,create_or_await_choice_page,first_run_router,households_cubit}.dart`,
  `app/lib/features/invites/data/invites_api.dart`, `app/lib/shared/commands/command_intent.dart`,
  `app/lib/shared/errors/error_message_resolver.dart`]

## Questions for Timo (non-blocking — sensible defaults chosen)

1. **Inviter-name in the pending list (the 4.1 D3 follow-up).** 4.1 left "surface the inviter's name in
   the pending-invites list" scheduled "alongside 4.2/4.3". Default = **defer to 4.3**, where the
   `household_member_read_model` + a `MemberId`→display-name ACL read seam land together (state model
   §5); adding a name-resolution seam here, in an accept-only story, would be off-scope. Say the word to
   pull it into 4.2 instead.
2. **410 Gone vs 409 Conflict for an expired invite.** Default = **410 Gone** (semantically "the invite
   resource is no longer available") with a distinct client code the app localizes to „Einladung
   abgelaufen". Prefer a plain 409 to match the other invite errors? Say so and it becomes a 409.
3. **Accept-screen entry.** 4.2 lands accept on the „Auf Einladung warten" screen via a pasted
   link/code (OS deep-link + web fallback are 4.6, decision 1). Confirmed by omission — no in-4.2
   platform deep-link work.

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

None — no failing CI/build runs to link; local `./gradlew test` and `flutter test`/`flutter analyze`
runs were used iteratively during TDD and are summarized in Completion Notes.

### Completion Notes List

- **Backend domain (T1–T6):** added `InviteAccepted` (carries only the ACL-minted `MemberId`, no
  email/HMAC) and the three domain exceptions (`InviteNotFound`, `InviteExpired`,
  `InviteAlreadyConsumed`). `Household.acceptInvite` implements all 5 branches from the story
  exactly (absent/pending-fresh/pending-expired/expired/accepted), reusing `InviteState.isExpiredAt`
  and adding `InviteStatus.ACCEPTED` + its fold case. 7 new `HouseholdTest` cases pass; the existing
  33 invite/household domain tests stayed green (no regression).
- **Backend application (T7–T9):** three application exceptions map to 404/410/409 respectively (410
  is a new status in `WriteErrorAdvice` — `HttpStatus.GONE`). `AcceptInviteHandler` mints-then-appends
  (mirrors `CreateHouseholdHandler`), and on the `InviteExpiredException` path re-appends the lazy
  `InviteExpired` + purges the side-store before surfacing 410 — matching the "three externally
  distinct outcomes" contract in Dev Notes exactly. 8 new `AcceptInviteHandlerTest` cases pass,
  including an explicit purge-happens-after-append ordering assertion (mirrors the 4.1 fake that
  re-reads the stream on every side-store call, not just a call-count check — the 4.1 review scar).
- **Backend adapters (T10–T12):** `InviteAccepted` registered in `DomainEventJsonCodec` (round-trip +
  no-email/HMAC payload guard tests); `JdbcInviteReadModel.markAccepted` mirrors `markExpired` exactly
  (idempotent flag flip, no migration — V12's `status` column already fits); the projector case rides
  the existing subscription. `InviteController` gained `POST .../accept`; 4 new `InviteControllerTest`
  cases (200/404/410/409) plus a no-email-in-response assertion. Bean wiring added to
  `CollaborationApplicationConfig`.
- **Backend privacy (T13):** covered inline by the T6 no-PII fold test, the T10 codec no-email/HMAC
  payload guard, and the unmodified `NoPersistedPersonalDataTest` (no new migration/column — verified
  green). `DeferredFindHouseholdMemberByEmail`'s javadoc corrected to say the real lookup ships in 4.6
  only (Epic-3 Action 4 fix-rigor from decision 2).
- **Client (T14–T18):** `InviteLink.tryParse` accepts the canonical `https://…/invite?h=&i=` URL, a
  bare `h=&i=` query-string code, and a short `householdId:inviteId` code, rejecting anything else
  (9 unit tests). `InvitesApi.acceptInvite` mirrors `sendInvite`'s envelope shape. `AcceptInviteCubit`
  mirrors `CreateHouseholdCubit`'s idle/submitting/success/failure shape with a `CommandIntent` keyed
  on the parsed link (regenerated on a different link or after success) and an `isSubmitting`
  re-entrancy guard (8 cubit tests). `AwaitInvitePage` is now a functional accept screen
  (`await-invite-link-field` + `await-invite-join-button`, keeping `await-invite-back-button`); on
  success it calls `HouseholdsCubit.bootstrap()` and pops to the first-run router, which then routes
  into the joined household via the Identity ACL's read-your-writes member-mapping. Updated
  `CreateOrAwaitChoicePage._openAwaitInvite` to re-provide `InvitesApi`/`HouseholdsCubit` by value
  across the pushed route (the established `ProviderNotFoundException` lesson, mirrored from
  `_openOnboarding`), with a regression test alongside the existing onboarding one. Added 6 new
  German ARB keys and rewrote the stale "this feature ships later" body copy; wired the 4 new error
  codes (`invite.invalidLink`/`invite.expired`/`invite.notFound`/`invite.alreadyUsed`) into
  `error_message_resolver.dart`. a11y: field/button labels come from `InputDecoration.labelText` /
  `SgartButton.label`, matching every other form field's a11y pattern in this codebase.
- **Full suites green (both named, CLAUDE.md §6):** backend `./gradlew test` — 713 tests, 0 failures
  (incl. ArchUnit `HexagonalArchitectureTest` and the `NoPersistedPersonalDataTest` privacy guard).
  Flutter `flutter test` — 556 tests, 0 failures; `flutter analyze` — no issues found.
- **DoD cross-check:** commandId lifecycle verified in both `AcceptInviteHandlerTest` (same-commandId
  retry converges) and `AcceptInviteCubitTest` (regenerated after success); routing verified via the
  `await_invite_page_test` success case asserting `HouseholdsCubit` reaches `HouseholdsStatus.shell`;
  the projector's isolation + replay-idempotency tests for `InviteAccepted` ship in this change
  (Epic-2 Action 4); every `[x]` task above has its named test present in the Test Manifest.

### File List

**New — backend:**
- `backend/src/main/java/de/sgart/collaboration/domain/event/InviteAccepted.java`
- `backend/src/main/java/de/sgart/collaboration/domain/exception/InviteNotFoundException.java`
- `backend/src/main/java/de/sgart/collaboration/domain/exception/InviteExpiredException.java`
- `backend/src/main/java/de/sgart/collaboration/domain/exception/InviteAlreadyConsumedException.java`
- `backend/src/main/java/de/sgart/collaboration/application/command/AcceptInvite.java`
- `backend/src/main/java/de/sgart/collaboration/application/command/AcceptInviteHandler.java`
- `backend/src/main/java/de/sgart/collaboration/application/exception/InviteNotFoundApplicationException.java`
- `backend/src/main/java/de/sgart/collaboration/application/exception/InviteExpiredApplicationException.java`
- `backend/src/main/java/de/sgart/collaboration/application/exception/InviteAlreadyConsumedApplicationException.java`
- `backend/src/test/java/de/sgart/collaboration/application/AcceptInviteHandlerTest.java`

**Modified — backend:**
- `backend/src/main/java/de/sgart/collaboration/domain/Household.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/in/InviteController.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/in/WriteErrorAdvice.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/DomainEventJsonCodec.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/HouseholdReadModelProjector.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/JdbcInviteReadModel.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/CollaborationApplicationConfig.java`
- `backend/src/main/java/de/sgart/identity/adapter/out/DeferredFindHouseholdMemberByEmail.java`
- `backend/src/test/java/de/sgart/collaboration/domain/HouseholdTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/out/DomainEventJsonCodecTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/out/HouseholdReadModelProjectorTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/in/InviteControllerTest.java`

**New — client:**
- `app/lib/features/invites/data/invite_link.dart`
- `app/lib/features/invites/presentation/accept_invite_cubit.dart`
- `app/lib/features/invites/presentation/accept_invite_state.dart`
- `app/test/features/invites/data/invite_link_test.dart`
- `app/test/features/invites/presentation/accept_invite_cubit_test.dart`
- `app/test/features/households/presentation/await_invite_page_test.dart`

**Modified — client:**
- `app/lib/features/invites/data/invites_api.dart`
- `app/lib/features/households/presentation/await_invite_page.dart`
- `app/lib/features/households/presentation/create_or_await_choice_page.dart`
- `app/lib/shared/errors/error_message_resolver.dart`
- `app/lib/l10n/app_de.arb` (and generated `app/lib/l10n/gen/app_localizations*.dart`)
- `app/test/features/invites/data/invites_api_test.dart`
- `app/test/features/households/presentation/create_or_await_choice_page_test.dart`
- `app/test/support/fake_invites_dependencies.dart`

### Change Log

| Date | Change |
|---|---|
| 2026-09-06 | Story 4.2 implemented: backend `AcceptInvite` domain/command/handler/adapters (T1–T13) and the Flutter accept-invite slice (T14–T18). Full suites green (backend 713/0, Flutter 556/0, analyze clean). Status → review. |
| 2026-09-06 | Code review (3-layer: Blind Hunter, Edge Case Hunter, Acceptance Auditor). 1 decision-needed (HIGH access-control), 5 patch, 3 dismissed. Status → in-progress pending fix. |
| 2026-09-07 | Applied all 6 review patches (F1 HIGH provision/persist-on-success split + regression test; F2 `InviteLink.tryParse` catches malformed percent-escape + regression test; F3 lazy-expiry append swallows `ConcurrencyConflictException`, still surfaces 410; F4 `acceptInvite` switch gets a fail-fast `default`; F5 T13 manifest corrected; F6 T9 test renamed to the true no-op mechanism). Full suites green: backend `./gradlew test` 745/0, `flutter test` 557/0, `flutter analyze` clean. |
| 2026-09-07 | Re-review (3-layer, Opus 4.8). F2/F4/F5/F6 verified; **F1 found NOT fully closed** — success-path `persist`-before-`append` re-opened the mapping leak for a concurrency-loser (blind HIGH + edge). Resolved via **Option 2** (Timo): `provision`→`ProvisionedMemberId`, added `MemberMappingRepository.deleteMapping`/`MintMemberIdentity.retract`, handler compensates a *freshly-provisioned* mapping on append failure (+2 regression tests). Also: F3 concurrency-conflict regression test added; expired-path purge made unconditional (GDPR storage-limitation); cubit code `invites.unknown`→`invite.unknown`. 1 defer (provision/persist non-atomic same-user 500 → `deferred-work.md`). Full suites green: backend `./gradlew test` **750/0**, `flutter test` **557/0**, `flutter analyze` clean. Status → done. |

### Review Findings

Adversarial code review 2026-09-06 (Blind Hunter + Edge Case Hunter + Acceptance Auditor).
Severity: HIGH = intolerable, MED = tolerable, LOW = cosmetic/hygiene.

- [x] [Review][Patch] **HIGH — Eager `mint()` before invite validation grants household access on every rejected accept** (resolved 2026-09-06 → Option 2, persist-on-success). **Fix:** split the mint seam into `provision` (return the caller's existing `MemberId`, else a freshly generated **unsaved** id) + `persist` (idempotent mapping write); `mint` becomes `provision`+`persist` (unchanged for `CreateHouseholdHandler`). `AcceptInviteHandler`: `provision` → `acceptInvite` (throws on 404/409/410 → never persists) → `persist` on the no-throw path **before** `append` (id-stable retry keeps append-ok/persist-fail recoverable) → `append` → `purge`. The lazy-expiry `catch` appends+purges with no `persist`, so AC3's expiry purge is preserved and no mapping is written for an expired/rejected caller. Add an `AcceptInviteHandlerTest` case asserting the mapping repo has **no** entry for the caller after a 404/409/410. Original detail: `AcceptInviteHandler.handle` mints the joiner's `MemberId` (`AcceptInviteHandler.java:78`) *before* `household.acceptInvite(...)` can reject. `MintMemberIdentity.mint` durably `save`s the `(keycloakUserId → memberId)` mapping row unconditionally, and there is **no `@Transactional`** on the handler/controller/bean-config, so the row is committed and never rolled back when the domain throws `InviteNotFound` (404) / `InviteExpired` (410) / `InviteAlreadyConsumed` (409), or when `append` loses a concurrency race. **Every** household-scoped read authorizes off that mapping table — `ResolveMemberIdentity.resolve` (`ResolveMemberIdentity.java:33`) and `ListHouseholdsForCaller.forCaller` / `ListMyHouseholds` ("membership taken from the *authoritative* Identity ACL mapping"). Net: any authenticated caller who POSTs `.../households/{anyGuessedHouseholdId}/invites/{anyGarbageInviteId}/accept` receives a 404 yet gains a durable member-mapping → the household (its name, pending invites, stores, lists, purchase history — all personal data per CLAUDE.md §5) appears in their switcher and passes every `resolve(...)` check. Defeats AC5 ("a spent link cannot be ridden by a stranger": the 409 stranger still ends up mapped), voids the AC3 410 expiry protection, and is a GDPR breach (mapping written for a household the person has no relationship with). The concurrent-accept loser gains access identically. **Unguarded and untested** — the handler tests assert stream/side-store state but never that a *rejected* accept leaves no mapping. Contradicts the doc-comment's "mirrors CreateHouseholdHandler" defence: `CreateHousehold`/`InvitePerson` mint for operations with no post-mint domain-rejection branch, so a committed mapping there always maps to real membership; accept is the first mint-then-reject handler. **Decision needed:** fix approach — (a) make `handle` `@Transactional` so a thrown rejection rolls the mapping back; (b) split mint so the mapping row is persisted only on the join branch (provisional id, persist-on-success); (c) other. Sources: blind+edge+auditor.
- [x] [Review][Patch] **MED — `InviteLink.tryParse` throws instead of returning null on malformed percent-encoding** [app/lib/features/invites/data/invite_link.dart:46] — for input with a bad `%` escape (e.g. `h=%zz&i=b`, or the URL form `…/invite?h=%zz&i=b`), `Uri.splitQueryString(raw)` / `uri.queryParameters` throw `FormatException`/`ArgumentError`. The cubit calls `InviteLink.tryParse` at `accept_invite_cubit.dart:32`, **before** its `try` block (line 42), so the exception escapes uncaught: no state is emitted, the inline invalid-link error never shows, the form is stuck. Contract says "anything else yields `null`". Fix: wrap the query-parse in try/catch returning `null` (also hardens the over-permissive whole-string `splitQueryString` fallback). Source: edge+blind.
- [x] [Review][Patch] **MED — Lazy-expiry `append` inside the `catch` can throw and mask the 410 + strand the side-store** [backend/.../application/command/AcceptInviteHandler.java:93] — on the branch-3 lazy-expiry path the freshly-raised `InviteExpired` is appended inside `catch (InviteExpiredException)`. If a concurrent write advanced the stream between `rehydrate` and this `append`, `append(basedOnVersion)` throws `ConcurrencyConflictException`, which propagates instead of `InviteExpiredApplicationException` — the client sees 409 for an expired invite (should be 410, AC3) and the `purge` on line 94 is skipped (orphan side-store row). Fix: on the housekeeping append, treat a concurrency conflict as non-fatal and still surface 410 (the expiry persists on the winning path / a later attempt). Source: edge+blind.
- [x] [Review][Patch] **LOW — `acceptInvite` status `switch` has no `default` branch (fail-fast gap)** [backend/.../domain/Household.java:241] — all three current `InviteStatus` values are handled, but a future enum value would fall through raising nothing → handler sees empty `uncommittedEvents()`, purges, returns 200: a phantom success. Inconsistent with `apply(...)`'s `default -> throw`. Fix: add `default -> throw new IllegalStateException(...)`. Source: edge.
- [x] [Review][Patch] **LOW — Test Manifest T13 names `InvitePrivacyTest`, which does not exist** [4-2-accept-an-invite-and-join.md:386] — `find` returns no such class; the `InviteAccepted` no-PII component assertion actually lives in `HouseholdTest` (`assertNoPersonalDataComponent(InviteAccepted.class)`) + the codec guard. The guarantee is covered; the manifest reference is wrong (Epic-3 Action 2 integrity drift). Fix: correct the T13 manifest reference. Source: auditor.
- [x] [Review][Patch] **LOW — T9 "same-`commandId` retry converges (EventStore dedup)" mislabels the mechanism** [backend/.../application/AcceptInviteHandlerTest.java] — `aRetryWithTheSameCommandIdConverges` re-invokes `handle`; on the retry the invite is already ACCEPTED + joiner already a member, so the domain no-ops, `uncommittedEvents()` is empty, and `append`/dedup is **never called**. Convergence comes from the domain no-op, not EventStore dedup as claimed. Fix: relabel the test/comment to the true mechanism (or add a test that actually exercises dedup). Source: auditor.

**Dismissed (noise / false-positive / established convention):**
- Post-join `bootstrap()` fire-and-forget + unconditional `popUntil` (`await_invite_page.dart:56-57`) — mirrors the established `CreateHousehold` route-on-response pattern; the first-run router owns the error state. (edge)
- "Dead command ceremony" — `AcceptInvite` built then unpacked via `command.inviteId()` — identical to `InvitePersonHandler.java:114-125`; established CQRS convention, not a 4.2 defect. (blind)
- `InviteLink` query-form over-permissive misparse (standalone) — the cited path/fragment examples actually return `null`; folded into the malformed-escape patch above. (blind)

### Review Findings — Re-review 2026-09-07

Adversarial re-review of the 6 applied patches (Blind Hunter + Edge Case Hunter + Acceptance Auditor,
all Opus 4.8). Baseline `57ab53e`. **F2, F4, F5, F6 verified correct.** **F3 code correct but its
regression test is missing.** **F1 is NOT fully closed** — the chosen persist-before-append ordering
reintroduces the same access leak on the concurrency-conflict path. Severity: HIGH = intolerable,
MED = tolerable, LOW = cosmetic/hygiene.

- [x] [Review][Patch] **HIGH — F1 not fully closed: success-path `persist`-before-`append` orphans a durable member-mapping when the success `append` loses a concurrency race → the F1 household-access/GDPR leak returns for the 409 concurrency-loser** [backend/.../application/command/AcceptInviteHandler.java:124-127]. **RESOLVED 2026-09-07 (Option 2):** `provision` now returns `ProvisionedMemberId(memberId, freshlyProvisioned)`; `MemberMappingRepository.deleteMapping` + `MintMemberIdentity.retract` added; the handler wraps the success-path `append` in a `try` that, on any failure, retracts the mapping **iff freshly provisioned** (never an existing member's) then rethrows. Regression tests: `aSuccessPathAppendConflictRetractsTheFreshMappingSoTheLosingCallerKeepsNoAccess` (loser leaves no mapping, no purge), `anAlreadyMemberWhoseAppendConflictsKeepsTheirRealMapping` (existing member not retracted), plus `MintMemberIdentityTest` retract cases. Transactional-outbox left deferred. **Decision resolved 2026-09-07 → Option 2 (keep persist-before-append, add compensating delete):** on any success-path `append` failure, delete the just-provisioned mapping **iff it was freshly provisioned** (never an already-member's real mapping), then rethrow; keeps `CreateHouseholdHandler`'s recoverable ordering, closes the loser leak. `provision` returns fresh-vs-existing; add `MemberMappingRepository.deleteMapping`; add an append-conflict regression test asserting no mapping survives. The airtight transactional-outbox answer is deferred (matches 4.1's outbox deferral). Deterministic `MemberId` rejected — breaks AD-5 pseudonymity/erasure. The split is correct for the *sequential* domain-rejection cases (404/410/409 throw before `persist` — verified, with the three no-mapping-after-rejection tests present and asserting). But on the **success** branch `persist(...)` (line 124) runs *before* `append(...)` (line 127), and a `ConcurrencyConflictException` from that success `append` is **not** caught. Confirmed reachability: `ListHouseholdsForCaller.forCaller` and `ResolveMemberIdentity.resolve` derive household membership **solely** from the `identity_member_mapping` row (verified — no event-stream cross-check), so a persisted mapping = real, durable household access independent of the stream. **Failure scenario (concurrent double-redemption of a shared bearer link):** callers A and B both hold the link and POST accept near-simultaneously; both rehydrate at v_n (invite PENDING). B wins: `provision`→`acceptInvite` raises events→`persist(B→idB)`→`append` succeeds. A loses: `provision`→`acceptInvite` also raises events on A's stale snapshot (no throw)→**`persist(A→idA)` writes A's mapping**→`append(v_n)` throws `ConcurrencyConflictException`→A gets 409, mapping **never rolled back**. A's retry now sees the invite ACCEPTED-by-B → `InviteAlreadyConsumedException`/409 again, mapping persists permanently. Net: A — a stranger correctly rejected with 409 — is a durable "ghost member" with full access to the household's personal data. Voids AC5 ("a spent link cannot be ridden by a stranger") under concurrency and re-breaches AD-5/GDPR — the exact guarantee the F1 split claimed ("no path where a rejected accept persists a mapping"). Narrower trigger than the original F1 (needs a race in the rehydrate→append window, not any garbage POST), but same consequence class. **Unguarded and untested** — no test drives an append conflict on the success path. **Decision needed — fix approach:** (a) **persist AFTER a successful append** (Blind Hunter's rec; the loser's append fails first so `persist` never runs → fail-closed; cost: an `append`-ok/`persist`-fail blip leaves the joiner on-stream but unmapped and a fresh-id retry is then rejected — rare, fail-closed, needs a recovery story); (b) **keep persist-before-append but compensate** — catch the success-`append` throwable and delete the just-provisioned mapping (only when `provision` minted a *fresh* id, i.e. caller was not already a member) before rethrowing (preserves the id-stable-retry benefit for benign unrelated-write races); (c) accept & document as a known narrow pre-beta race and defer; (d) other. Sources: blind (HIGH) + edge (confirmed); auditor dismissed it but reasoned only through the single-actor recoverable case, missing the concurrent-stranger case.
- [x] [Review][Patch] **LOW — F3's concurrency-conflict regression test is missing (patch code correct, test half not delivered)** [backend/.../application/AcceptInviteHandlerTest.java] — **RESOLVED 2026-09-07:** added `aLazyExpiryAppendConflictStillSurfaces410AndLeavesNoMappingForTheExpiredCaller` (an `AppendConflictingEventStore` double forces the housekeeping append to conflict; asserts `InviteExpiredApplicationException`/410, not the raw 409, and no mapping for the expired caller). **F2 URL-variant sub-item NOT added as a test** — empirically `Uri.queryParameters` (the URL path) does *not* throw on `%zz` (only `Uri.splitQueryString`, the bare-query path, does), so the URL form yields `%zz` as an opaque id rather than crashing; asserting `null` there would contradict the parser's deliberate no-id-content-validation design (server is authoritative). Documented in an `invite_link_test.dart` comment instead. Source: auditor.
- [x] [Review][Patch] **LOW — `AcceptInviteCubit` emits error code `invites.unknown` (plural), inconsistent with the `invite.*` convention and unmapped in the resolver** [app/lib/features/invites/presentation/accept_invite_cubit.dart:57] — **RESOLVED 2026-09-07:** renamed to `invite.unknown` (aligns with its sibling `invite.invalidLink`/`.expired`/`.notFound`/`.alreadyUsed`; still falls to the generic fallback, which is the intended UX). The pre-existing plural `invites.unknown` in `invites_cubit.dart` (4.1) is left untouched — out of scope, and its onboarding test depends on it. Source: blind+auditor.
- [x] [Review][Patch] **LOW — Unguarded accept side-effects around the happy/expiry paths degrade to 500 or strand the side-store** [backend/.../application/command/AcceptInviteHandler.java] — **PARTIALLY RESOLVED 2026-09-07:** (c) fixed — the expired `catch` now purges the side-store **unconditionally** (idempotent), so the terminal `EXPIRED` branch and a previously-stranded row are both purged (closes the GDPR storage-limitation gap). (a)/(b) **left as-is by design:** a *non-concurrency* infra failure of `append`/`purge` surfacing as 500 is correct fail-fast — silently swallowing all infra errors to force a 410/200 would hide real faults; the success-path 500 self-corrects on the next no-op re-accept. Source: edge.
- [x] [Review][Defer] **LOW — `provision`/`persist` is a non-atomic check-then-act → concurrent same-user accept can 500 on the unique index** [backend/.../identity/application/MintMemberIdentity.java:64-68] — two concurrent first-time accepts by the *same* keycloakUserId+household (two devices) can both see `findMemberId(...).isEmpty()` and both `save`, the second violating `idx_identity_member_mapping_household_keycloak` (verified present in `V1`) → unmapped `DataIntegrityViolationException`/500 rather than a converging no-op. **Deferred, pre-existing** — the same check-then-act lived in the old `mint()`; the split only widens the read→write window. Not caused by 4.2's intent. Source: blind+edge.

**Dismissed (re-review — noise / by-design / covered-in-combination):**
- AC5 literal wording vs code: a *different* already-member re-accepting a consumed invite gets a no-op success, not 409 — matches the §3.5 convergent-no-op design intent and grants no access (they are already a member). By design. (auditor)
- `InviteLink` colon/query forms accept `mailto:x` / non-`https` schemes — client fail-fast is UX-only; ids are opaque UUIDs the server validates authoritatively (404 on nonsense). Low-value to tighten. (edge)
- T18 a11y — no explicit `Semantics` widget; relies on `InputDecoration.labelText` / `SgartButton.label`, matching the established codebase a11y pattern. (auditor)
- T13 `assertNoPersonalDataComponent` bans `email`/`keycloak`/`displayname` but not `hmac` — the "no hmac" guarantee is covered in combination by the codec test `inviteAcceptedJsonPayloadCarriesNoEmailOrHmacComponent`; `InviteAccepted` has no hmac component regardless. (auditor)
