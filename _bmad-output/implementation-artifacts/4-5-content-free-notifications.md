---
baseline_commit: 6dff48bbf7ab27e04ccefb9b86736996cfdf71b8
---

# Story 4.5: Content-free notifications

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a member,
I want to be nudged about relevant changes without my data leaving the household,
so that we stay coordinated privately even when the app is closed.

## Context & Relationship to Story 4.4

Story 4.4 already ships **in-app live sync** while the app is **foregrounded and SSE-connected**: a
`fromEnd` `$all` subscription (`HouseholdLiveSyncFanout`) broadcasts a content-free `changed` nudge
over the per-household SSE stream, and the Flutter client refetches through existing GET queries
(LD-1: wake-and-fetch, **never** event data on the wire).

Story 4.5 is the **backgrounded / app-closed** counterpart: when a member is **not** holding an open
SSE connection, a **content-free push** (FCM/APNs) wakes their device so the app can fetch current
state on next open. Same privacy contract as LD-1 — the push payload carries **only** a
wake-and-fetch signal (`{householdId, resource}`), never item/list/receipt content (FR12, NFR3,
AD-5). This story adds: device-token registration, a **swappable push port**, a **notification
fan-out** subscription (mirror of 4.4's fan-out) with **list-changed debouncing**, and the Flutter
push-receive → refetch wiring reusing 4.4's `HouseholdLiveSyncController` reconcile seam.

## Acceptance Criteria

1. **Content-free payload (FR12, NFR3, AD-5).** Given household activity, when a notification fires,
   then the push payload carries **no item/list/receipt/store content and no PII** — only a
   wake-and-fetch signal (`{householdId, resource}`, mirroring the 4.4 SSE nudge) that prompts the
   app to fetch real state from the household's own server via existing GET queries. Asserted by an
   explicit privacy test over the serialized payload.

2. **Fixed MVP default set (FR12).** Given the fixed MVP defaults (no per-member configuration —
   `NotificationSettingsUpdated` is reserved-not-built, ARCHITECTURE-SPINE §Deferred), when the
   corresponding events occur, then pushes are sent for exactly:
   - **list changed** — the item/list mutation events, **debounced to at most one ping per list per
     ~5-minute interval**;
   - **trip started / completed** — `TripStarted` / `TripCompleted` (not debounced).
   - **invitation received** — **DEFERRED (D1, locked 2026-09-10):** invite delivery stays Keycloak
     email (AD-6); the invite *push* trigger is a follow-up slice (the invitee has no
     household-scoped `MemberId`/device at invite time). MVP push triggers = list-changed +
     trip-started/completed only.

3. **Swappable push adapter (NFR3).** Given push delivery, when a ping is sent, then it goes through
   a **swappable port** owned by the application/domain (transport replaceable later — e.g.
   UnifiedPush) with **no FCM/APNs type leaking past the adapter**. The wired default is a hermetic
   logging/no-op adapter (see **Proposed Decision D2**); the FCM adapter sits behind config.

4. **Recipient scoping & "mapping = access" (AD-5, AD-7, 4.3/4.4 crux).** Given a household event,
   when the fan-out resolves recipients, then a push reaches **only current members'** registered
   devices. A member who has left / been removed / whose household was deleted receives **no**
   further pushes for it (device resolution goes through the live Identity ACL mapping, so a
   synchronous de-link removes access — same "mapping = access" invariant as 4.3/4.4).

5. **Device-token registration & GDPR (CLAUDE.md §5, AD-6, AD-7).** Given a signed-in member, when
   the client registers/refreshes its push token, then the token is stored **keyed by
   `keycloakUserId` in the Identity context** (a device belongs to a person across households, not to
   a household-scoped `MemberId`), with a **documented purpose and retention**, deletable on erasure
   (de-link), and **never** copied into an event or a Collaboration read model. A stale/unregistered
   token that the transport rejects is pruned.

6. **No regressions.** Given the new fan-out subscription runs alongside the read-model projectors,
   the process-manager subscription, and the 4.4 live-sync fan-out, when events flow, then none of
   the existing four consumers is disturbed; the full backend suite (incl. ArchUnit + Testcontainers)
   and the Flutter suite stay green.

## Locked Decisions (Timo, 2026-09-10)

- **D1 — invitation push DEFERRED.** MVP ships **list-changed + trip-started/completed** pushes only;
  invitation delivery stays Keycloak email (AD-6). Invite push is a follow-up slice (needs a
  Keycloak email→user resolution the invitee doesn't have a `MemberId`/device for at invite time).
- **D2 — port + logging default now; live FCM deferred.** Ship the **swappable
  `ContentFreePushSender` port + `LoggingContentFreePushSender` as the wired default** (hermetic,
  unit/integration-testable, privacy-assertable) **plus an `FcmContentFreePushSender` behind config**
  (`@ConditionalOnProperty`). Live Firebase/`google-services.json`/APNs credentials and the Flutter
  `firebase_messaging` native platform wiring are a **manual follow-up** (external credentials,
  un-unit-testable) — documented in the first-real-world-test guide, not wired here.
- **D3 — no actor self-exclusion** in MVP (KISS; client dedups via wake-and-fetch, matching 4.4 SSE).

## Tasks / Subtasks

> Follow red-green-refactor per task (CLAUDE.md §6, TDD default). Mirror existing patterns; do not
> reinvent. Names: no abbreviations (AD-11). Keep the domain pure — push/FCM types live only in
> `adapter.out` (AD-1, §8 hexagonal).

- [x] **Task 1 — Device-token registration in the Identity context (AC5)** (AC: 5)
  - [x] Model a `DeviceToken` value object + a `DeviceTokenRepository` domain port in
        `de.sgart.identity.domain`, keyed by `KeycloakUserId` (reuse existing `KeycloakUserId`).
        Store minimal fields: `keycloakUserId`, opaque `token`, `platform` (enum: `ANDROID`/`IOS`),
        `registeredAt`. **No** household id, **no** `MemberId` on the token (a device is
        person-scoped, serves all the person's households).
  - [x] Add an application service `RegisterDeviceToken` (command-style, returns nothing beyond
        success — CQRS §4) and `UnregisterDeviceToken`. Fail-fast validation (blank token → 400 via
        the existing envelope/exception seam).
  - [x] Add `adapter.in` endpoint(s): `POST /api/v1/devices` (register/refresh — upsert by token) and
        `DELETE /api/v1/devices/{token}` (or on sign-out). `keycloakUserId` comes from the JWT `sub`,
        never the body (§Conventions: Auth/Ids). Mirror `IdentityController` conventions.
  - [x] Provide a `JdbcDeviceTokenRepository` (+ Flyway/whatever-migrations mechanism this repo uses —
        **verify the existing migration tooling/next version number**; 4.3 introduced V14) and an
        `InMemoryDeviceTokenRepository` for tests, mirroring `Jdbc/InMemoryMemberMappingRepository`.
  - [x] **GDPR:** wire device-token deletion into the existing erasure/de-link path
        (`RetractMembership` / account deletion) so tokens are removed on erasure (AD-7); document
        purpose + retention in the `package-info.java` / migration comment. Add an explicit
        erasure-removes-device-tokens test (CLAUDE.md §6 DSGVO tests).
  - [x] Unit-test the domain VO/validation; integration-test the endpoint + repository upsert-by-token
        and idempotent re-register.

- [x] **Task 2 — Swappable content-free push port (AC1, AC3)** (AC: 1, 3)
  - [x] Define `ContentFreePushSender` port in `de.sgart.collaboration.application` (or a shared
        notification sub-package) — method e.g. `send(DeviceToken target, ChangeNudge nudge)` where
        `ChangeNudge` carries only `{householdId, resource}`. **No FCM/APNs type in the signature.**
  - [x] Define the content-free payload type + its JSON serialization; add a **privacy unit test**
        asserting the serialized form contains only `householdId` + `resource` and no item/list/store
        content (AC1). Reuse the `resource` vocabulary from 4.4 (`list` / `trip` / `members` /
        `household`) — see `HouseholdResolver.resourceFor`.
  - [x] Implement `LoggingContentFreePushSender` (wired default) and `FcmContentFreePushSender`
        (behind `@ConditionalOnProperty`, D2). The FCM adapter is the **only** place an FCM SDK type
        may appear (AD-1/§8). On a transport "token invalid/unregistered" response, signal back so the
        stale token is pruned (AC5).
  - [x] ArchUnit must still pass — confirm no `adapter.out` push type is imported by `application`/
        `domain`; add a note if a new port package is introduced.

- [x] **Task 3 — Notification fan-out subscription (AC2, AC4)** (AC: 2, 4)
  - [x] Add `HouseholdNotificationFanout` in `de.sgart.collaboration.adapter.out`, **structurally
        mirroring `HouseholdLiveSyncFanout`** (`SmartLifecycle`, `autoStart` flag, single-threaded
        resubscribe, per-event log-and-skip, `fromEnd` `$all` with the **single regex filter** — reuse
        the exact filter shape; do **not** use chained `addStreamNamePrefix`, see the 4.4 code comment
        / T12 finding). Register it in the collaboration config alongside the existing fan-out.
  - [x] Map events → triggers (AC2): item/list mutation events → `list changed`; `TripStarted` →
        `trip started`; `TripCompleted` → `trip completed`. Ignore all other events. (Use the
        `DomainEventJsonCodec` `*_TYPE` constants — do not re-parse strings ad hoc.)
  - [x] **Debounce list-changed** to ≤1 ping per **list** per ~5 min. Keep an in-memory
        `listId → lastSentAt` map (KISS for the single-node modular monolith; a restart-reset is
        acceptable — a missed ping self-heals via SSE/refetch, same reasoning as 4.4's `fromEnd`).
        Make the window and clock injectable for deterministic tests (isolate the clock — CLAUDE.md
        §6). Trip events are **not** debounced.
  - [x] **Resolve recipients (AC4):** for the event's `householdId`, list current members
        (`HouseholdMemberReadModel.membersOf`), resolve each `MemberId → keycloakUserId` via the
        Identity ACL (`MemberMappingRepository` / a small published port — respect AD-2: cross-context
        only via an application-layer port, never reaching into Identity's tables), then load each
        person's device tokens and `send` through the port. A de-linked member yields no tokens →
        no push (AC4, "mapping = access").
  - [x] Unit-test `react(...)` routing (trigger selection, debounce window boundaries, recipient
        resolution, dead-token pruning) the same way `HouseholdLiveSyncFanoutTest` tests routing
        without the event-store client; add a Testcontainers integration test mirroring
        `HouseholdLiveSyncFanoutIntegrationTest` proving an appended event drives exactly one push per
        recipient device (and debounce suppresses the second within the window).

- [x] **Task 4 — Flutter: device-token registration + push wake-and-fetch (AC1, AC5)** (AC: 1, 5)
  - [x] Add a `PushNotifications` port (abstract) in `app/lib/shared/` (mirror the
        `shared/sync/` structure) with two responsibilities: obtain/refresh the device token and
        register it with the backend (`POST /api/v1/devices`), and surface incoming content-free
        pushes as a stream of `{householdId, resource}`.
  - [x] Wire an incoming push to a **refetch**, reusing the existing 4.4 reconcile seam
        (`HouseholdLiveSyncController.onReconcile` / the same GET-query refetch path) — **do not**
        apply push payload data to state (LD-1). If the push targets the non-active household, refetch
        that household's state / mark it stale on next switch (keep KISS — reuse existing controllers).
  - [x] Register the token on sign-in and refresh; unregister on sign-out (AC5).
  - [x] Concrete `firebase_messaging` implementation behind the port is **D2-gated**: add the port +
        registration client + a fake/in-memory impl for tests now; wire the real FCM plugin +
        native config only if D2 says "live now". Add the `firebase_messaging` (or chosen) dependency
        at the **current supported version** (CLAUDE.md §7 — verify latest for Flutter 3.44) only when
        wiring the concrete adapter.
  - [x] Widget/unit tests: token-registration call fires on sign-in; an incoming nudge triggers
        exactly one refetch (reuse the debounce already in `HouseholdLiveSyncController`); no payload
        data is read into state.

- [x] **Task 5 — Full green build + docs (AC6)** (AC: 6)
  - [x] Run the **complete** backend suite `./gradlew test` (incl. ArchUnit + Testcontainers) **and**
        the app's `flutter test` + `flutter analyze` (CLAUDE.md §6 — a partial run is not green).
        Report which suites ran and their counts.
  - [x] Update `docs/first-real-world-test` guide (a 6dff48b sibling) with the manual FCM/APNs
        credential + native-config steps if D2 defers them, so the deferred wiring is not lost.
  - [x] Update the Dev Agent Record, File List, Change Log; flag any newly-noticed outdated deps
        (CLAUDE.md §7).

### Review Findings

Code review 2026-09-10 (3-layer adversarial: Blind Hunter, Edge Case Hunter, Acceptance Auditor).
Outcome: **Changes Requested** — all 6 ACs met, but 6 fixable findings. 3 High/Med/Low breakdown:
1 High, 1 Medium, 4 Low to patch; 3 deferred; 6 dismissed as noise.

**Patch (unchecked = open):**

- [x] [Review][Patch] HIGH — Device token (person-scoped PII) sent in the DELETE URL path, so it
      lands verbatim in Tomcat/proxy/APM access logs — contradicting AD-6 and the code's own
      "never log the token" invariant; also breaks unregister for any token containing `/` (no
      URL-encoding) → stale token never removed. Move the token off the path (JSON body / dedicated
      unregister endpoint) and stop interpolating it into the URL.
      [backend/.../identity/adapter/in/DeviceController.java:47 · app/lib/shared/push/backend_device_registration_client.dart:19]
- [x] [Review][Patch] MED — Debounce window is marked (`lastSentAtByListId[listId]=now`) inside
      `isDebounced` *before* `notifyRecipients` runs, so a projector-race unresolved household /
      zero recipients / send failure still consumes the ~5-min window and silently suppresses the
      next genuinely-deliverable list-changed push. Mark the window only when a push is actually
      dispatched (resolve recipients first, then debounce-and-send). [HouseholdNotificationFanout.java react/isDebounced]
- [x] [Review][Patch] LOW — `lastSentAtByListId` (ConcurrentMap) never evicts — one entry per list
      ever changed, growing unbounded over the node's lifetime (new vs the mirrored
      HouseholdLiveSyncFanout, which has no such map). Evict entries older than the window lazily on
      access or via a periodic sweep. [HouseholdNotificationFanout.java:~112]
- [x] [Review][Patch] LOW — Subscription filter regex is `^(household|list|trip)-.*` but `react`
      has no `household-` branch, so every household/member event is delivered only to be dropped
      (dead subscription scope). Narrow to `^(list|trip)-.*` (KISS/YAGNI + slight traffic win). [HouseholdNotificationFanout.java subscribe()]
- [x] [Review][Patch] LOW — `LoggingContentFreePushSender` logs the household id at INFO on every
      push; this is the wired default that runs in any non-FCM deployment. Household membership is
      personal data (CLAUDE.md §5) — log at DEBUG for privacy-by-default. [LoggingContentFreePushSender.java:32]
- [x] [Review][Patch] LOW — Client fires `unawaited(register())` on sign-in while `signOut` awaits
      `unregister()` first; if the in-flight register resolves after sign-out, the device is
      re-registered post-logout (a signed-out device keeps a live token row). Guard registration
      against the current auth state / cancel it on sign-out. [app/lib/features/auth/presentation/auth_cubit.dart]

**Deferred (pre-existing or FCM-gated — logged to deferred-work.md):**

- [x] [Review][Defer] LOW — Invalid-token prune (`deleteByToken`) has no generation guard, so it
      could delete a token re-registered between resolution and prune. Only reachable once real FCM
      delivery returns `TOKEN_INVALID` (D2 deferred; the logging default always returns DELIVERED)
      → fold into the FCM wiring follow-up. [HouseholdNotificationFanout.java · PruneDeviceToken.java]
- [x] [Review][Defer] LOW — `AuthenticatedCaller.fromJwt` NPEs (→ 500) on a JWT with no `sub`
      claim; pre-existing seam reused by the new controller, systemic across all controllers.
- [x] [Review][Defer] LOW — `stop()`/`subscribe()` shutdown race can orphan a live `$all`
      subscription; pre-existing pattern copied verbatim from HouseholdLiveSyncFanout (fix both, not
      here). [HouseholdNotificationFanout.java stop/subscribe]

**Dismissed (6):** `mapRow` unknown-platform throw (write path validates the enum — not reachable
without manual DB corruption); upsert token reassignment (benign/correct for per-device tokens —
re-login on a shared device should move the token); `InMemoryDeviceTokenRepository` HashMap CME
(test double, no concurrent path — mirrors existing InMemoryMemberMappingRepository); backward-clock
over-suppression (hypothetical); non-active-household push discarded client-side (story-sanctioned
KISS — relies on fetch-on-switch); `ContentFreePushSender`→`identity.application.PushTarget`
coupling (AD-2-sanctioned application-port seam, ArchUnit green).

## Dev Notes

### Architecture & patterns to mirror (do NOT reinvent)

- **The fan-out is a fourth independent `$all` consumer.** There are already three: the read-model
  projectors, `CollaborationProcessManagerSubscription`, and (4.4) `HouseholdLiveSyncFanout`. Add a
  **fourth** the same way — never bolt notification logic onto an existing one (SRP, §8). Copy
  `HouseholdLiveSyncFanout`'s lifecycle/resubscribe/`fromEnd`/regex-filter skeleton verbatim in shape.
  [Source: backend/src/main/java/de/sgart/collaboration/adapter/out/HouseholdLiveSyncFanout.java]
- **KurrentDB client filter constraint (T12, 4.4):** a `SubscriptionFilter` accepts exactly **one**
  `addStreamNamePrefix`; use `.withStreamNameRegularExpression("^(household|list|trip)-.*")` (build the
  prefixes from `StreamId.StreamType`), **not** chained prefixes. Reuse the 4.4 pattern exactly.
- **Recipient resolution path:** `HouseholdMemberReadModel.membersOf(householdId)` → `MemberId`s;
  Identity ACL maps `MemberId → keycloakUserId` (`MemberMappingRepository`); device tokens keyed by
  `keycloakUserId`. Respect **AD-2**: Collaboration touches Identity only through an
  application-layer port (see how `ListHouseholdMembers` composes `ResolveMemberIdentity`), never by
  reaching into Identity tables.
  [Source: backend/src/main/java/de/sgart/collaboration/application/query/ListHouseholdMembers.java]
- **`resource` vocabulary already exists** — reuse `HouseholdResolver.resourceFor(streamId, eventType)`
  (`list` / `trip` / `members` / `household`) so the push nudge and the SSE nudge speak the same
  language (LD-1 parity). [Source: backend/.../adapter/out/HouseholdResolver.java]
- **Event type constants:** route via `DomainEventJsonCodec.*_TYPE` constants (`ITEM_ADDED_TYPE`,
  `TRIP_STARTED_TYPE`, `TRIP_COMPLETED_TYPE`, …). [Source: backend/.../adapter/out/DomainEventJsonCodec.java]
- **Flutter reuse:** the wake-and-fetch reconcile already exists —
  `HouseholdLiveSyncController.onReconcile` refetches via existing GET queries and debounces bursts.
  Route incoming pushes into the **same** refetch; do not build a second reconcile path.
  [Source: app/lib/shared/sync/household_live_sync_controller.dart]

### Device tokens belong in Identity, not Collaboration

A device is **person-scoped** (one phone serves all the person's households) and its token is PII.
AD-5 keeps Collaboration events/read models `MemberId`-only; AD-6 keeps PII out of the log; AD-7
makes erasure a de-link. Therefore the token store lives in the **Identity** context keyed by
`keycloakUserId`, joins erasure/de-link there, and is resolved for fan-out via a published port. Do
**not** add a device/token column to any Collaboration read model.

### Privacy / GDPR (CLAUDE.md §5 — first-class, tested)

- **Data minimization:** store only `keycloakUserId`, `token`, `platform`, `registeredAt`. Documented
  purpose = deliver content-free wake pings; retention = life of the device registration, pruned on
  invalid-token response and on erasure.
- **Content-free guarantee is a test, not a comment:** serialize the payload and assert only
  `{householdId, resource}` (AC1). `householdId` is an opaque UUID, not item/list/receipt content —
  matches FR12 ("no item/list/receipt content") and LD-1.
- **Erasure:** removing a member / deleting the household / erasing an account removes device tokens
  and stops pushes (AC4, AC5); add explicit tests (DSGVO §6 in tests).
- **Transport carve-out is known & bounded:** NFR3 accepts FCM/APNs as an MVP carve-out to the
  self-hosting NFR precisely *because* payloads are content-free; the swappable port keeps
  UnifiedPush open for the public phase. Do not widen the payload.

### Testing standards (CLAUDE.md §6)

- Domain (`DeviceToken` VO, validation) → pure fast unit tests, no infra.
- Fan-out routing/debounce/recipient-resolution/dead-token-pruning → unit tests via a `react(...)`
  seam (mirror `HouseholdLiveSyncFanoutTest`), clock injected.
- End-to-end append→push → one Testcontainers test (mirror `HouseholdLiveSyncFanoutIntegrationTest`).
- Endpoint + repository → integration tests (upsert-by-token, idempotent re-register, erasure prune).
- Flutter → registration-on-sign-in, push→single-refetch, no-payload-data-into-state.
- **Green = full suite for every touched module:** backend `./gradlew test` (ArchUnit +
  Testcontainers) **and** `flutter test` + `flutter analyze`. Never report green from a partial run
  (see [[backend-test-hygiene]]).

### Project Structure Notes

- Backend: new domain/app/adapter classes split by the §8 hexagonal layers inside `identity`
  (device tokens) and `collaboration` (fan-out + push port). If a notification grouping grows,
  a `application/notification` sub-package is justified (§8 "subfolder when it earns its keep") —
  otherwise keep flat. Each new layer/sub-package needs a `package-info.java` (§8).
- Flutter: put the push port under `app/lib/shared/` alongside `shared/sync/` (mirrors 4.4).
- **Verify migration tooling & next version number** before adding a device-token table (4.3 shipped
  V14; confirm V15 is next). Do not assume the tool — read an existing migration first.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 4.5: Content-free notifications] — ACs.
- [Source: _bmad-output/planning-artifacts/epics.md#L60] — FR12 debounce spec (≤1 ping/list/~5 min).
- [Source: _bmad-output/planning-artifacts/architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md#AD-5] — MemberId-only pseudonym.
- [Source: ...ARCHITECTURE-SPINE.md#AD-6] — no persisted PII; identity read live.
- [Source: ...ARCHITECTURE-SPINE.md#AD-7] — erasure by de-linking.
- [Source: ...ARCHITECTURE-SPINE.md#Deferred] — `NotificationSettingsUpdated` reserved-not-built (fixed MVP defaults); UnifiedPush is a public-phase option; NFR3 push carve-out.
- [Source: ...ARCHITECTURE-SPINE.md#Capability → Architecture Map] — "Content-free notifications (FR-19) | Backend + client | AD-5 (no data in payload)".
- [Source: _bmad-output/implementation-artifacts/4-4-real-time-live-sync.md] — LD-1 wake-and-fetch contract, fan-out pattern, T12 filter constraint, "mapping = access" crux.
- [Source: CLAUDE.md §4 CQRS, §5 DSGVO, §6 Testing, §7 Dependency currency, §8 Package structure].

## Remaining Verification (dev-time, self-serve)

- **Migration version:** confirm the next migration number for the device-token table (4.3 shipped
  V14; verify V15 is next by reading the existing migrations before adding one).

## Dev Agent Record

### Agent Model Used

Story context + decisions: Opus 4.8. Implementation: Sonnet (per [[model-preferences]]).

### Debug Log References

- Migration number corrected to **V17** at dev start (V16 was already the latest; the story draft's
  "V15" guess was wrong — verified by reading `db/migration/`).
- Verified hexagonal purity post-implementation: `collaboration.application` (`ChangeNudge`,
  `ContentFreePushSender`) and `identity.domain` (`DeviceToken`) import no FCM/JDBC/Spring type.
- AC1 privacy guarantee is a test, not a comment: `ChangeNudgeJsonCodecTest` asserts the serialized
  payload key-set is **exactly** `{householdId, resource}`.

### Completion Notes List

- **All 6 ACs satisfied.** Content-free push (`ChangeNudge{householdId,resource}` only, AC1);
  fixed MVP trigger set — list-changed (debounced ≤1/list/~5 min) + trip started/completed, invite
  push deferred per D1 (AC2); swappable `ContentFreePushSender` port with `LoggingContentFreePushSender`
  wired default + `FcmContentFreePushSender` behind `@ConditionalOnProperty` (AC3); recipient
  resolution via the live Identity ACL mapping, so a de-linked member gets no push (AC4); device
  tokens person-scoped in Identity, pruned on invalid-token response + `deleteAllForUser` Epic-6
  erasure hook (AC5); no regressions (AC6).
- **Device-token deletion — design refinement vs. Task 1's literal text.** Task 1 sketched wiring
  token deletion into `RetractMembership`. That is intentionally **not** done: a device is
  person-scoped and serves all the person's households, so leaving one household must not delete the
  device token. "Mapping = access" (AC4) is enforced by recipient resolution over the live mapping,
  not by token deletion. Bulk token removal lives in `deleteAllForUser` (the Epic-6 account-erasure
  hook — implemented + tested, not yet wired to a trigger, correct for this story's scope).
- **"List changed" event set (explicit allow-list):** `ShoppingListCreated/Renamed`,
  `ItemAdded/Updated/Removed`, the three `ItemTransfer*`, `ItemAssignedToStore`, `ItemRerouted`,
  `ItemCheckedOff/Unchecked/Discarded`. Deliberately excludes `TripStartedForList`/
  `TripCompletedForList` (they map to the trip trigger — avoids double-pinging one real action).
- **D2 honored:** no `firebase-admin` / `firebase_messaging` dependency added. `FcmContentFreePushSender`
  is a documented skeleton that throws if enabled without the follow-up wiring; Flutter ships the
  port + registration client + a fake. Manual FCM/APNs/native steps documented in
  `docs/first-real-world-test.md`.
- **Flutter wiring is optional (nullable) dep injection** in `AuthCubit`/`HouseholdShell`, mirroring
  the existing guarded-optional pattern (`HouseholdEventStreamFactory`) — avoids churning ~10
  unrelated test call sites. Push-driven reconcile fires only for the active household; a non-active
  household relies on fetch-on-open when switched to (story's KISS note).

### Tests (full suites — no partial runs)

- **Backend `./gradlew test`: 945 passed, 0 failed** — incl. `HexagonalArchitectureTest` (ArchUnit)
  and all Testcontainers integration tests (KurrentDB 25.1.4 + PostgreSQL 18.6 containers started;
  `HouseholdNotificationFanoutIntegrationTest`, `JdbcDeviceTokenRepositoryTest`).
- **Flutter `flutter analyze`: No issues found. `flutter test`: 626 passed, 0 failed.**

### File List

**Backend — new (main):**
- `backend/src/main/resources/db/migration/V17__device_token.sql`
- `backend/src/main/java/de/sgart/identity/domain/{DevicePlatform,DeviceToken,DeviceTokenRepository}.java`
- `backend/src/main/java/de/sgart/identity/application/{RegisterDeviceToken,UnregisterDeviceToken,PruneDeviceToken,PushTarget,ResolveHouseholdPushTargets,InvalidDeviceRegistrationException}.java`
- `backend/src/main/java/de/sgart/identity/adapter/out/{InMemoryDeviceTokenRepository,JdbcDeviceTokenRepository}.java`
- `backend/src/main/java/de/sgart/identity/adapter/in/{DeviceController,DeviceErrorAdvice}.java`
- `backend/src/main/java/de/sgart/collaboration/application/{ChangeNudge,PushDeliveryResult,ContentFreePushSender}.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/{ChangeNudgeJsonCodec,LoggingContentFreePushSender,FcmContentFreePushSender,HouseholdNotificationFanout,HouseholdNotificationConfig}.java`

**Backend — new (test):** matching tests for every class above, plus
`HouseholdNotificationFanoutIntegrationTest.java` (Testcontainers), `DeviceControllerTest.java`,
`Jdbc/InMemoryDeviceTokenRepositoryTest.java`, `DeviceTokenTest.java`,
`Register/Unregister/PruneDeviceTokenTest.java`, `ResolveHouseholdPushTargetsTest.java`,
`ChangeNudgeJsonCodecTest.java`, `Logging/FcmContentFreePushSenderTest.java`,
`HouseholdNotificationFanoutTest.java`.

**Backend — modified:** `identity/domain/MemberMappingRepository.java` (+`keycloakUserIdsFor`),
`identity/adapter/out/{Jdbc,InMemory}MemberMappingRepository.java`,
`identity/adapter/out/IdentityBeansConfig.java`, `resources/application.yaml`
(+`sgart.push.fcm.enabled`), `test/.../collaboration/application/RemoveMemberHandlerTest.java`.

**Flutter — new:** `app/lib/shared/push/{push_notifications,backend_device_registration_client}.dart`,
`app/test/shared/push/backend_device_registration_client_test.dart`,
`app/test/support/fake_push_notifications.dart`.

**Flutter — modified:** `app/lib/features/auth/presentation/auth_cubit.dart`,
`app/lib/features/households/presentation/household_shell.dart`,
`app/lib/shared/sync/household_live_sync_controller.dart`,
`app/lib/shared/http/authenticated_http_client.dart` (+bodiless `delete`), and the two
corresponding test files.

**Docs — modified:** `docs/first-real-world-test.md` (+"Background push notifications (D2)" manual
follow-up section).

## Change Log

| Date       | Description                                                                 |
| ---------- | -------------------------------------------------------------------------- |
| 2026-09-10 | Story drafted (ready-for-dev).                                             |
| 2026-09-10 | D1/D2/D3 locked; implemented full slice; backend 945/945, Flutter 626/626 green → review. |
| 2026-09-10 | 3-layer code review: 6 patches applied (1 High token-in-path→body, 1 Med debounce-before-delivery, 4 Low), 3 deferred, 6 dismissed; backend 945/945 + Flutter 626/626 still green → done. |
