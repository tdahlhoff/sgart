# Story 4.4: Real-time live sync

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a member,
I want everyone's changes to appear live,
so that the list is correct for all of us without refreshing.

## Acceptance Criteria

1. **Live propagation.** Given members connected to the same household, when one changes a list,
   item, or trip, then the change appears on other connected members' devices within a few seconds,
   with no manual refresh, via the **per-household SSE stream** (FR-10, FR7, AR10).

2. **Reconnect + reconcile.** Given a dropped connection, when connectivity returns, then the
   client **automatically re-establishes** the SSE connection and **reconciles** current state
   (FR-10).

3. **Access is the mapping (derived, AR10 / §5).** Given a member who is removed, leaves, or whose
   household is deleted (Story 4.3 ACL de-link), then any open SSE stream that member holds for that
   household is **closed server-side at once**, and a fresh connection attempt is **rejected `403`**
   — a de-linked member never keeps receiving household changes. This is not in the epic's two ACs
   but is a **required** end-to-end behavior: a live stream *is* access, and access follows the ACL
   mapping (the "mapping = access" invariant proven synchronously in Story 4.3).

### Locked decisions (settled with Timo during planning, 2026-09-07)

- **LD-1 — Content-free nudge (not event data).** The SSE stream carries only a coarse
  "something changed" signal — `event: changed`, `data: {"householdId":"…","resource":"list|trip|members|household"}`
  — **never** item/list/trip/member content. The client reacts by **re-fetching** through the
  existing GET queries. Rationale: §5 data-minimization (item names, purchase history, membership
  are personal data — keep them off the push channel, its buffers, and any intermediary), KISS/DRY
  (reuse tested `List*`/`TripView` queries, no read-event schema to design/version), one source of
  truth (read models stay the only thing the client renders — no client-side delta-apply path to
  diverge from the projector or from the future offline queue), and consistency with Story 4.5's
  content-free wake-and-fetch push. The `resource` hint is coarse (derived from stream type) and the
  client MAY ignore it and refetch the active screens.

- **LD-2 — Live sync only; the fan-out subscribes `fromEnd`.** The server-side fan-out subscription
  needs only *new* events to turn into nudges, so it subscribes from the **live edge** and holds
  **no** per-client or per-subscription position. A gap during downtime is healed by AC2's client
  reconnect-refetch, not by replay. **Checkpointed subscriptions are explicitly out of scope** (see
  Deferred successors) — do **not** touch the existing `fromStart` projectors/PM subscription in
  this story.

- **LD-3 — Activity attribution ("Anna checked off Milk") is a fast-follow, not this story.** 4.4
  delivers the *mechanism* (nudge + refetch + reconnect). The "who did what" affordance is a
  separate successor story because it needs new plumbing (an activity read model or `lastChangedBy`
  enrichment) **and** a genuine privacy decision: co-member display identity does not exist today —
  `household_member_read_model` (V14) stores only `(household_id, member_id, role)`, and a member's
  profile display name (Story 1.11) is not shared to co-members anywhere. Do **not** surface member
  names in this story.

## Tasks / Subtasks

### Backend — SSE endpoint (adapter.in)

- [ ] **T1. `GET /api/v1/households/{householdId}/stream` SSE endpoint** (AC1, AC3) — a new
  controller (or a method on a new `HouseholdStreamController`) producing `text/event-stream`,
  returning a Spring MVC `SseEmitter` (the stack is `spring-boot-starter-web` — MVC, not WebFlux;
  `SseEmitter` is the primitive, **not** `Flux<ServerSentEvent>`).
  - [ ] Authorize with `ResolveMemberIdentity.resolve(jwt.getSubject(), householdId)` — a
    `NotAMemberException` maps to **403** via the existing `WriteErrorAdvice` (`@RestControllerAdvice`,
    verified: `handleNotAMember` → `HttpStatus.FORBIDDEN`). **Run this check synchronously at the top of
    the controller method, before creating/returning the `SseEmitter`.** Once the emitter is returned the
    response is committed at `200` and async — a deferred check could not produce a 403. Caller identity
    comes **only** from the JWT `sub` via `AuthenticationPrincipal Jwt` (AD-5, AR10) — never from path or
    body beyond the `{householdId}` scope check.
  - [ ] Register the returned emitter in the emitter registry (T3) keyed by `(householdId, memberId)`.
    De-register on `onCompletion`, `onTimeout`, and `onError`.
  - [ ] Set a long async timeout suited to a persistent stream — a plain `SseEmitter` inherits the MVC
    async timeout (`spring.mvc.async.request-timeout`, ~30s on Tomcat) and would drop; pass a long/zero
    timeout to the `SseEmitter` ctor and/or raise the property. Emit a periodic **heartbeat** (SSE comment
    `:\n\n` or a `ping` event) at an interval **shorter than** any proxy idle timeout so idle connections
    aren't dropped and dead peers are detected (registry cleanup). Make the interval configurable.
  - [ ] `package-info.java` unchanged if it lands in the existing `collaboration.adapter.in` package;
    keep it there beside the other controllers (the domain screams — this is collaboration transport).

### Backend — fan-out (adapter.out)

- [ ] **T2. `HouseholdLiveSyncFanout` subscription** (AC1) — a new `SmartLifecycle` bean that
  **structurally mirrors `CollaborationProcessManagerSubscription`**: auto-start gated by a flag
  (default **off** so construction does no I/O and `contextLoads()` survives KurrentDB down),
  single-threaded resubscribe scheduler, per-event **log-and-skip** so one bad event never tears the
  subscription down.
  - [ ] **Subscribe `fromEnd`** (`SubscribeToAllOptions.get().fromEnd().filter(...)`) — LD-2. Verified:
    `fromEnd()` is inherited from `OptionsWithPositionAndResolveLinkTosBase` and chains before `.filter()`.
    Filter to the three prefixes that carry user-visible change: `household-`, `list-`, `trip-` via three
    `SubscriptionFilter.newBuilder().addStreamNamePrefix(StreamId.StreamType.X.prefix() + "-")` calls
    (note the explicit `+ "-"` — `prefix()` returns `"list"`, not `"list-"`). **Precedent for the
    multi-prefix filter: `ShoppingListReadModelProjector`** already filters `list-` **and** `household-`
    together; mirror `CollaborationProcessManagerSubscription` for the `SmartLifecycle`/resubscribe
    scaffolding, and that projector for the multi-prefix filter.
  - [ ] For each event: **resolve the household** (T4), map the stream type to a coarse `resource`
    hint, and push a content-free nudge (T5) to every emitter registered for that household.
  - [ ] React to **de-link events** to enforce AC3 (T6).
- [ ] **T3. `HouseholdEmitterRegistry`** — thread-safe in-memory registry, e.g.
  `ConcurrentHashMap<HouseholdId, ConcurrentMap<MemberId, Set<SseEmitter>>>` (a `Set` per member so
  one person's multiple devices each get their own stream). Methods: `register`, `deregister`,
  `broadcast(householdId, nudge)`, `evictMember(householdId, memberId)`, `evictHousehold(householdId)`.
  Broadcast must tolerate a dead emitter (catch `IOException`/`IllegalStateException`, deregister,
  continue — never let one dead client block the others). **`SseEmitter.send()` is not safe for
  concurrent calls on the same emitter**, and two writers can hit one emitter at once (the T1 heartbeat
  scheduler and this fan-out broadcast) — **serialize sends per emitter** (a per-emitter lock, or wrap
  the emitter so every `send` synchronizes). **Single-process assumption is fine for MVP** (modular
  monolith, one instance); horizontal scale-out would need a shared bus — out of scope (see Deferred
  successors).
- [ ] **T4. Household resolution — from the event's *stream name*, not its body** (AC1). *(Refined
  2026-09-07 after code verification — see "Plan refinements" in Dev Notes.)* A decoded event is **not
  required** to produce a nudge: `RecordedEvent.getStreamId()` gives the stream key
  (`household-{id}` / `list-{id}` / `trip-{id}`) and `getEventType()` gives the type string — both
  **without deserializing any payload**. Resolve `HouseholdId` from the stream key:
  - `household-{id}` → `HouseholdId` is the parsed UUID **directly** (no lookup, no decode). This covers
    all household/member/role/invite/store events, incl. the T6 de-link events.
  - `list-{id}` → look up `shopping_list_read_model` (`list_id → household_id`). The list exists when any
    list event fires, so there is **no** projector race in practice. **Cache** `listId → householdId`
    (immutable once the list is created) so the hot path is O(1) after first sight.
  - `trip-{id}` → look up the trip read model (`trip_id → household_id`); add a small
    `findHouseholdId(TripId)` accessor if none exists yet. Same immutable-mapping cache.
  - **Rejected alternative — the marker interface.** An earlier draft recommended a
    `HouseholdScopedEvent { HouseholdId householdId(); }` interface implemented on the ~27 events that
    carry the field (verified: exactly five do **not** — `ItemRemoved`, `ItemUpdated`,
    `ShoppingListRenamed`, `ItemTransferConfirmed`, `ItemTransferCancelled`). Reject it: it is speculative
    infrastructure spread across 27 files (YAGNI), and it forces the nudge path to deserialize
    item/member payloads into memory for no functional gain. The stream-name approach keeps the nudge
    path **codec-free and §5-pure** — personal data is never deserialized merely to emit a content-free
    nudge. Keep `DomainEvent` minimal either way (do **not** add `householdId()` to it — that part stands).
  - `DomainEventJsonCodec` is therefore used **only** on the eviction path (T6), and only for the two
    event types (`MemberRemoved`, `MemberLeft`) that need a `memberId` from the body.
- [ ] **T5. Content-free nudge payload** (AC1, LD-1) — an SSE `changed` event whose `data` is
  `{"householdId":"…","resource":"list|trip|members|household"}`. **No item/list/trip/member content.**
  `resource` derives from the stream key + event-type *string* only (no body decode, consistent with T4):
  `list-`→`list`, `trip-`→`trip`, `household-` → `members` when `getEventType()` names a member/invite
  event (`Member*` / `Invite*`), else `household`. The hint is coarse and the client MAY ignore it and
  refetch its active screens.

### Backend — access revocation (the crux)

- [ ] **T6. Close a de-linked member's live stream** (AC3, AR10, §5) — the fan-out already observes
  every `household-` stream event, so react to the Story 4.3 governance events:
  - `MemberRemoved` / `MemberLeft` → `registry.evictMember(householdId, memberId)` — complete that
    member's emitter(s) for that household (so their client stops receiving and will 403 on
    reconnect). Other members' streams are untouched.
  - `HouseholdDeleted` → `registry.evictHousehold(householdId)` — complete **all** emitters for it.
  - New connections are already blocked by T1's `ResolveMemberIdentity` gate (de-linked → no mapping
    → 403). T6 handles the **already-open** connections. Together they make "mapping = access"
    hold for the live channel exactly as Story 4.3 made it hold for commands/queries.

### Client (Flutter)

- [ ] **T7. `HouseholdEventStream` SSE client** (AC1, AC2) — in `lib/shared/sync/` (new folder;
  it's cross-feature infrastructure like `lib/shared/http`). Prefer a **Dio streamed response**
  (`ResponseType.stream`) over adding a dependency, so the existing `AuthenticatedHttpClient` bearer
  interceptor supplies the token (never a token in path/query — see the client's own rule). Parse the
  `text/event-stream` frames (a small, well-defined line format: `event:`, `data:`, blank-line
  dispatch, `:` heartbeat comments). If manual parsing proves fragile, evaluate a **current,
  maintained** SSE package and pin the latest supported version (§7).
  - [ ] **Auto-reconnect** with capped exponential backoff + jitter (AC2). Treat a 4xx (esp. 403
    from T6/AC3) as terminal — do **not** reconnect-loop a revoked stream; surface it (the member
    was removed → the household should drop out of view, consistent with Story 4.3's cascade).
  - [ ] Expose a connection status (`connecting` / `live` / `reconnecting` / `offline` / `revoked`)
    and a nudge stream.
- [ ] **T8. Reconcile on (re)connect and on each nudge** (AC1, AC2) — on connect **and** on every
  reconnect, call `refresh()` on the active household's live cubits so a missed-events gap self-heals
  (AC2 reconcile). On each `changed` nudge, do the same (optionally scoped by the `resource` hint).
  **Debounce/coalesce** bursts so a flurry of edits triggers at most one refetch per short window.
  - Target cubits already expose reload methods and guard `emit` with `isClosed`:
    `ShoppingListsCubit.refresh()`, `ActiveTripsCubit` (bootstrap/refresh), and — when open — the
    list-detail and trip cubits. Use `invalidateArchive()` where a Done list may have changed.
- [ ] **T9. Lifecycle owner = `HouseholdShell`** (AC1, AC2) — the shell is already **keyed on the
  active household id** (`ValueKey(activeHousehold.householdId)`) and torn down/rebuilt on a switch,
  making it the natural owner. Start the stream for the active household on shell build; stop it on
  household switch and on sign-out. One stream per active household.
- [ ] **T10. Real connection-status indicator** (AC1) — replace the existing **placeholder** in the
  app bar: `Icon(Icons.cloud_queue_outlined, key: Key('sync-status-placeholder'))` with tooltip
  `householdsSyncStatusPlaceholderLabel` (in `household_shell.dart`, explicitly marked "the real
  status is Epic 4/5"). Drive it from T7's status. Add German l10n strings (language policy: German
  in product UI, English in code/docs) and keep a stable widget `Key` for tests.

### Tests (CLAUDE.md §6 — the full pyramid; a green build runs the whole suite for every touched module)

- [ ] **T11. Backend unit** — fan-out event→household resolution (both the field path and the
  read-model fallback for the five list-scoped events); `resource` mapping; registry
  register/deregister/broadcast/evict, incl. dead-emitter tolerance and multi-device `Set`;
  react-routing to evict on `MemberRemoved`/`MemberLeft`/`HouseholdDeleted`.
- [ ] **T12. Backend integration (Testcontainers + KurrentDB)** — append an event → nudge is
  delivered to a subscribed emitter **for the right household only** (a second household's emitter
  receives nothing — isolation); non-member `GET …/stream` → **403**; a member's stream is **closed**
  after `MemberRemoved`/`HouseholdDeleted`, and their reconnect is 403 (AC3). Delivery and eviction go
  through the async `fromEnd` subscription, so **await with a polling assertion / latch** (e.g.
  Awaitility) — never a fixed sleep. Cover the five field-less list events (T4 list-read-model path) with
  a real appended `ItemRemoved`/`ShoppingListRenamed` so the resolver's lookup branch is exercised end-to-end.
- [ ] **T13. Privacy regression (§5 / §6 "test the privacy guarantees explicitly")** — assert a nudge
  frame contains **no** item/list/trip/member content — only `householdId` + coarse `resource`. Strengthened
  by T4: the nudge path never even decodes the event body, so add a unit assertion that the resolver +
  nudge builder produce a frame from `getStreamId()`/`getEventType()` alone (no `DomainEventJsonCodec` on
  the nudge path — codec is exercised only by the T6 eviction tests).
- [ ] **T14. Client** — SSE frame parsing; reconnect/backoff (incl. terminal 403 → no loop);
  nudge→`refresh()` wiring and coalescing (bloc_test); reconcile-on-connect; status transitions and
  the app-bar indicator swap.
- [ ] **T15. ArchUnit** — new adapter.in endpoint and adapter.out fan-out/registry/resolver +
  any port respect hexagonal dependencies (`HexagonalArchitectureTest`); no `..domain..` import from
  `adapter.in`; `package-info` present where a new package is introduced.
- [ ] **T16. Full-suite green** — backend `./gradlew test` (incl. ArchUnit + Testcontainers) **and**
  app `flutter test` + `flutter analyze`. Report both counts explicitly; a partial run is not green
  (backend-test-hygiene lesson).

## Dev Notes

### Plan refinements (verified against code, 2026-09-07)

Every load-bearing claim below was checked against the actual sources before dev; the LDs (LD-1/2/3)
are unchanged. Refinements are confined to implementation *shape*, not scope.

- **Verified facts:** `fromEnd()` exists (inherited from `OptionsWithPositionAndResolveLinkTosBase`) and
  chains before `.filter()`; multi-prefix filters work — `ShoppingListReadModelProjector` already filters
  `list-`+`household-`; `WriteErrorAdvice.handleNotAMember` maps `NotAMemberException`→**403**;
  `RecordedEvent` exposes `getStreamId()` (stream key) and `getEventType()` **without** decoding the body;
  exactly **five** events lack a `householdId` field (`ItemRemoved`, `ItemUpdated`, `ShoppingListRenamed`,
  `ItemTransferConfirmed`, `ItemTransferCancelled`); the shell placeholder icon `sync-status-placeholder`
  + `householdsSyncStatusPlaceholderLabel` and the cubit reload methods (`ShoppingListsCubit.refresh`,
  `ActiveTripsCubit.refresh/bootstrap`, `TripCubit.refresh`) all exist as cited; client uses `dio ^5.11.0`.
- **T4 changed — stream-name resolution instead of a marker interface** (⚠️ **decision worth a glance**):
  the earlier "recommended shape" added a `HouseholdScopedEvent` interface across ~27 event files. Since
  household resolution and the `resource` hint are both derivable from the *stream name* + event-type
  string, the nudge path needs **no body decode at all** — so T4 now resolves from `getStreamId()` (+ a
  cached `listId/tripId→householdId` lookup) and touches **zero** event files. Cleaner (KISS/YAGNI) and
  more §5-pure (personal data never deserialized to emit a content-free nudge). If you'd rather keep the
  marker interface, revert T4/T5 to the field-based form — it still works. `DomainEvent` stays minimal
  either way.
- **Correctness subtleties now explicit:** T1 auth must run **synchronously before returning the emitter**
  (else the response is committed at 200 and 403 can't fire); the MVC async timeout (~30s) must be raised
  or the stream drops (T1); `SseEmitter.send()` is not concurrency-safe so heartbeat vs broadcast sends
  must be serialized per emitter (T3); and the async eviction race is **benign** by design (see the
  "mapping = access" note).

### Architecture patterns & constraints

- **SSE is the mandated live-sync transport** (ARCHITECTURE-SPINE §Transport/§Real-time; PRD
  addendum). The endpoint contract is fixed by the addendum: `GET /households/{id}/stream`. The
  *concretization* the spine deliberately left to build-time — "SSE event JSON format +
  reconnect/auth" — is what this story decides, per LD-1/LD-2/AC3.
  [Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md#Transport; prds/prd-sgart-2026-08-06/addendum.md]
- **Event sourcing / CQRS (AD-1).** State changes only by appending events; read models are built by
  projectors and are the client's render source. The fan-out is a **third independent consumer** of
  the event streams (alongside the read-model projectors and the PM subscription) — it never writes,
  never projects, only observes-and-notifies. [Source: ARCHITECTURE-SPINE.md#AD-1; deferred-work.md]
- **Subscription pattern to copy exactly:** `backend/.../collaboration/adapter/out/CollaborationProcessManagerSubscription.java`
  — `SmartLifecycle`, autoStart flag (default off; construction does no I/O so `contextLoads()`
  survives KurrentDB down), `DomainEventJsonCodec.fromJsonBytes`, per-event try/catch log-and-skip,
  `RESUBSCRIBE_DELAY` reschedule on `onCancelled`. **The one difference: `fromEnd`, not `fromStart`**
  (LD-2). Do not add another subscription per aggregate — one filtered `subscribeToAll` over the
  three prefixes (same discipline as Story 4.3's projector fix).
- **Auth seam (AD-5, AR10):** membership is resolved through the Identity ACL —
  `ResolveMemberIdentity.resolve(keycloakUserId, householdId)` throws `NotAMemberException` when the
  caller has no mapping. This is the *same* gate every command/query uses; the SSE endpoint is just
  another caller of it. Caller identity is the JWT `sub` only.
  [Source: backend/.../identity/application/ResolveMemberIdentity.java; identity/domain/MemberMappingRepository.java]
- **"Mapping = access" (AC3 crux).** Story 4.3 established that removing a member / leaving / deleting
  a household **synchronously de-links the ACL mapping** after the append — proven end-to-end with
  recorded call-order tests. A live SSE stream is a form of access, so it must obey the same
  invariant: T6 evicts the open stream on the de-link events the fan-out already sees. New
  connections are already blocked by the `ResolveMemberIdentity` gate. [Source: 4-3-membership-roles-governance.md Dev Agent Record; bmad memory: "mapping = access (synchronous ACL de-link)"]
  - **Why the async eviction race is benign (design property, not a gap).** The de-link is synchronous in
    Story 4.3 (append-then-de-link), but T6's fan-out eviction is async (`fromEnd` subscription thread), so
    a nudge can briefly race *ahead* of eviction and reach an already-removed member's open stream. This
    leaks nothing and grants no access: (1) the nudge is **content-free** (LD-1) — a `householdId` + coarse
    `resource`, no personal data; and (2) the refetch it triggers hits the GET queries, which authorize
    against the **already-de-linked** mapping → `403` → the client treats the household as revoked (T7).
    So access correctness rests on the synchronous de-link + authorized-refetch; **T6 is promptness /
    resource cleanup** (close the dangling stream quickly), defense-in-depth on top of an already-safe
    channel — not the sole barrier. Say this explicitly so a reviewer doesn't read the async eviction as
    an AC3 hole.
- **§5 GDPR.** LD-1's content-free nudge is the data-minimization choice; T13 tests it. Treat
  receipt contents, purchase history, and household membership as personal data.

### Source tree — files to touch

**Backend (NEW unless noted):**
- `collaboration/adapter/in/HouseholdStreamController.java` — the SSE endpoint (T1).
- `collaboration/adapter/out/HouseholdLiveSyncFanout.java` — the fan-out subscription (T2, `fromEnd`).
- `collaboration/adapter/out/HouseholdEmitterRegistry.java` — in-memory emitter registry (T3).
- `collaboration/adapter/out/HouseholdResolver.java` — stream-name → `HouseholdId` (T4), with a
  cached `listId/tripId → householdId` lookup. **No** `HouseholdScopedEvent` interface, **no** event-file
  changes (see revised T4). May add a small `findHouseholdId(TripId)` to the trip read model.
- `application.yml` / config — autoStart flag + heartbeat/timeout properties; `spring.mvc.async.request-timeout`.
- Reuse: `StreamId`, `ResolveMemberIdentity`/`WriteErrorAdvice` (403 seam), `JdbcShoppingListReadModel`
  (list→household), the trip read model (trip→household). `DomainEventJsonCodec` is needed **only** on the
  eviction path (T6), not the nudge path.

**Client (NEW unless noted):**
- `app/lib/shared/sync/household_event_stream.dart` — SSE client + reconnect (T7).
- `app/lib/shared/sync/live_sync_status.dart` — status enum/model (T7).
- **UPDATE** `app/lib/features/households/presentation/household_shell.dart` — own the stream
  lifecycle (keyed by active household id) and replace the `sync-status-placeholder` icon (T9, T10).
- **UPDATE** `app/lib/l10n/` ARB files — real status strings replacing
  `householdsSyncStatusPlaceholderLabel` usage (German UI).
- Reuse: `AuthenticatedHttpClient` (Dio + bearer interceptor), `ShoppingListsCubit.refresh()`,
  `ActiveTripsCubit`, list-detail/trip cubits' reload methods.

### Files being modified — current state & what must be preserved

- `household_shell.dart` — the shell builds **all three tabs eagerly** and hoists `ShoppingListsCubit`
  above the `IndexedStack` (keyed on `activeHousehold.householdId`) so state survives tab switches
  and `ActiveTripsView` can call `invalidateArchive()` after a completed trip (Story 3.4 AC7 fix).
  **Preserve:** the keying (torn-down-on-switch behavior your stream lifecycle depends on), the
  eager-build, and the hoisted-cubit arrangement. Add the stream **without** changing the tab/cubit
  topology; the app-bar `actions` slot already hosts the placeholder icon — swap that widget, keep
  the layout.
- Cubits (`ShoppingListsCubit` etc.) already guard every `emit` with `isClosed` and expose idempotent
  reload methods (`refresh`, `bootstrap`, `invalidateArchive`, `retryArchive`). **Reuse these** for
  reconcile — do not add a parallel reload path. (`HomeCubit` from Story 1.1 is an unrelated
  placeholder and out of scope.)

### Testing standards

- Domain-free live-sync logic (resolver, registry, resource mapping) → fast unit tests, no
  DB/transport. End-to-end delivery/auth/eviction → Testcontainers integration (KurrentDB). Client →
  `flutter test`/`bloc_test`, network isolated behind the SSE client double. Test names read as full
  behavioral sentences in the ubiquitous language, no abbreviations. Every fixture uses **synthetic
  fake data** (§6 DSGVO). AAA structure, independent/deterministic tests.

### Dependency currency (§7)

- Backend uses `spring-boot-starter-web` (MVC) → `SseEmitter`; **no new backend dependency** needed.
- Client: prefer the already-present `dio ^5.11.0` (confirmed in `app/pubspec.yaml`) streamed response
  over a new package. **If** an SSE package is warranted, pick a current, maintained one and pin the
  latest supported version; flag any deprecated transitive it drags in.
- **Observed while verifying (proactive §7 flag):** event-store client is `kurrentdb-client 1.2.1` — check
  whether a newer supported patch/minor exists before dev and bump if so (small, continuous upgrade).

### Scope boundaries (do NOT do in this story)

- **No activity attribution / "who did what"** (LD-3) — no member display names to co-members.
- **No checkpointed subscriptions** (LD-2) — leave the `fromStart` projectors + PM subscription
  untouched.
- **No offline queue / conflict surfacing** (FR-11, FR-26 — deferred). Reconcile here is a plain
  refetch that replaces server-derived state; there is no local pending state to preserve yet. Note
  the seam so the future offline queue reconciles cleanly against a refetch rather than a delta-apply.
- **No multi-instance fan-out** — the in-memory registry assumes the single-process monolith.

### Deferred successors (record so they are not lost)

- **Live activity attribution** (fast-follow, this epic): an activity read model (or `lastChangedBy`
  + `lastChangedAt` enrichment) fetched on nudge, plus the co-member member-label **privacy
  decision** — enables "Anna checked off Milk". Rides on this story's nudge. (LD-3)
- **Checkpointed subscriptions** (repo-wide debt, own story): retrofit `ShoppingListReadModelProjector`,
  `ShoppingTripReadModelProjector`, `HouseholdReadModelProjector`, and
  `CollaborationProcessManagerSubscription` with stored positions + the per-event transaction
  boundary (the deferred-work note ties the projectors' two-write atomicity fix to this). Not on
  4.4's path (fan-out is `fromEnd`). [Source: deferred-work.md — "checkpointed subscriptions"]
- **Multi-instance live sync**: replace the in-memory registry with a shared fan-out bus when the
  monolith scales horizontally.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 4.4: Real-time live sync]
- [Source: _bmad-output/planning-artifacts/prds/prd-sgart-2026-08-06/prd.md#FR-10 Live Sync of Household changes]
- [Source: _bmad-output/planning-artifacts/prds/prd-sgart-2026-08-06/addendum.md — SSE endpoint `GET /households/{id}/stream`; open items §95 (JSON format, reconnect, auth)]
- [Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md — Transport, Real-time (SSE), AD-1, AR10]
- [Source: backend/.../collaboration/adapter/out/CollaborationProcessManagerSubscription.java — subscription pattern to mirror]
- [Source: backend/.../identity/application/ResolveMemberIdentity.java — SSE auth gate]
- [Source: backend/.../shared/StreamId.java, DomainEvent.java — stream prefixes; minimal event contract]
- [Source: _bmad-output/implementation-artifacts/4-3-membership-roles-governance.md — "mapping = access" de-link crux]
- [Source: app/lib/features/households/presentation/household_shell.dart — lifecycle owner + sync-status placeholder]

## Project Structure Notes

- Backend additions stay inside the existing `de.sgart.collaboration` bounded context, split across
  `adapter.in` (endpoint) and `adapter.out` (fan-out, registry, resolver) — dependencies point
  inward; the fan-out implements observation over the event store (adapter.out), the endpoint drives
  in. No new bounded context (KISS — live sync is collaboration transport, not a new domain).
- Client adds one cross-feature `lib/shared/sync/` folder (peer of `lib/shared/http`), consistent
  with the feature-sliced structure; the only feature file touched is the shell that already owns the
  active-household lifecycle.
- No detected conflicts with the unified structure.

## Dev Agent Record

### Agent Model Used

_(to be filled by dev-story)_

### Debug Log References

### Completion Notes List

### File List
