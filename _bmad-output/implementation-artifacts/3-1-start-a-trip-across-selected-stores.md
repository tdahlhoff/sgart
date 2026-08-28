---
baseline_commit: 18face5214cdad9ad4d71825fa99b8c8595e9fb8
---

# Story 3.1: Start a trip across selected stores

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a member,
I want to start a trip from a list across the stores I'll visit,
so that I can shop with the list organized for this run.

## Acceptance Criteria

Derived from **epics.md § Story 3.1** (FR9/CAP-9 trip start) + **FR3/CAP-3** inline store creation,
refined against **ARCHITECTURE-SPINE.md** (AD-3, AD-4, AD-8, AD-10, AD-11, AR2), **UX-DR17 / UX-DR22
/ EXPERIENCE.md** (trip start = multi-select stores ≥1, list-detail „Einkauf starten", the reusable
store picker), the `screen-trip-lifecycle.html` artifact (Frame 1 „Einkauf starten"), and the Epic-2
slices this builds on (2.1 `ShoppingList` aggregate, 2.4 first process manager, 2.6 reusable store
picker). This is **Epic 3's first story and SGART's third aggregate** (`ShoppingTrip`, after
`Household` and `ShoppingList`). Each AC is independently testable.

1. **Start a trip from an Open list across one or more stores (AC1, FR9).** Given an **Open** list,
   when a member starts a trip and selects **one or more** stores, then the list transitions
   **Open → In-Trip** via **command → `TripStartedForList`** (a new event on the list's own
   `list-{id}` stream, the single guarded append), and a new **`ShoppingTrip` aggregate is created**
   linked to that list across those stores via **`TripStarted`** on its own `trip-{id}` stream — the
   trip is **Active**. The trip creation is driven by a **`TripStartProcessManager`** reacting to
   `TripStartedForList` (AD-10, mirrors the Story 2.4 `ItemMoveProcessManager`), so the two aggregate
   writes are never a non-atomic two-append in one handler.

2. **At most one Active trip per list (AC2, FR9).** Given a list that is **already In-Trip** (it has
   an Active trip), when a member tries to start another, then it is **prevented** — the list's
   `startTrip` requires **Open**, so a second start finds it In-Trip and is refused
   (`TripNotStartableException` → **409**). The guard is atomic: it is the list stream's own
   expected-version append (AD-8), so two concurrent starts cannot both create a trip.

3. **A trip requires at least one store (AC3, FR9).** Given the store selection, when a member tries
   to start a trip with **zero** stores, then it is **rejected** as a fail-fast **400**
   (`InvalidTripStoreSelectionException`) — the command carries ≥1 `storeId`; the client's confirm
   button is also disabled until at least one store is selected (belt-and-braces, UX-DR17 „mind.
   eines"). A **linked list is required** by construction (the trip is started *from* a list; the
   endpoint is nested under the list).

4. **Add a new store inline while selecting, incl. the zero-stores household (AC4, FR3/CAP-3,
   UX-DR22).** Given the store selector when starting a trip, when a member needs a store that does
   not exist yet, then they can **add a new store inline** (free-form name + advisory client-side
   chain suggestion, accept/change/clear — the **same rules as Story 1.8/2.6**), created in the
   household via the **existing `AddStore`** command and **immediately available in the selection** —
   so a household with **no stores yet can still start a trip** by adding one here (resolves the
   zero-stores case). This reuses and **extends the Story 2.6 reusable store picker to multi-select**
   (2.6 Cl. 8); the inline create is client-orchestrated (`AddStore` on the household, then the
   selection includes the new store), exactly like 2.6.

5. **An In-Trip list stays visible in the overview with an In-Trip label (AC5).** Given a list that
   has moved to **In-Trip**, when the „Listen" overview is viewed under the **„Offen"** filter, then
   the list **still appears** (it is active, not archived) with an **„Im Einkauf"** status label —
   `ListOpenLists` returns **Open + In-Trip** lists (fixing the deferred „Liste N" ordinal
   under-count: the ordinal now counts Open **and** In-Trip lists in creation order, per
   `deferred-work.md`). A **Done** list remains only under „Erledigt"; the In-Trip list is never
   hidden mid-trip.

6. **An In-Trip list's detail is read-only for off-trip item editing; „Einkauf starten" shows only
   on an Open list (AC6).** Given an **In-Trip** list, when its detail is viewed, then add/edit/
   remove/move/assign are **inert** (mirrors the Done read-only detail) — off-trip item commands are
   already refused by the aggregate's `requireOpen()` (In-Trip → `ItemChangeNotPermittedException`);
   in-trip item actions live on the trip screen (Story 3.2), **not built here**. The **„Einkauf
   starten"** action is offered **only on an Open list** (hidden on In-Trip and Done).

7. **Membership, isolation & no personal data (AC7).** The start-trip command is
   **membership-gated** (non-member → **403**), a malformed id is **400**, an unknown list is **404**,
   and a list under another household is **404** (mirrors `MoveItemHandler`/`AssignItemToStoreHandler`).
   `TripStartedForList` and `TripStarted` carry **no personal data** — household/list/trip/store ids
   only, and **no *who*** (no audit trail, mirrors `ItemAdded`); `MemberId` is used only at the handler
   seam to confirm membership, never persisted on the events (AD-5/AD-6). The list read model is
   queried **by `household_id`** so one household's In-Trip status never leaks to another. Tests use
   synthetic, clearly-fake German data only.

## Clarifications (LOCKED)

Taken from the epic ACs, the ARCHITECTURE-SPINE, the Story 2.1/2.4/2.6 patterns, `deferred-work.md`,
the Epic-2 retro action items, and **Timo's decisions (2026-08-28)**. **If any is wrong, correct it
before `dev-story`.**

1. **List-primary coordination + a `TripStartProcessManager` (Timo, 2026-08-28 — the crux).** Starting
   a trip changes two aggregates. The invariant „at most one Active trip per list" + „list must be
   Open" can only be atomically guarded on the **list's own stream** (one-stream optimistic
   concurrency), so the **list is the primary guarded write**: `StartTripHandler` loads `list-{id}`,
   `requireOpen()`, and appends the single guarded event **`TripStartedForList`** (folds Open →
   In-Trip). A **`TripStartProcessManager`** then reacts to `TripStartedForList` and creates the
   `ShoppingTrip` (raising the marquee **`TripStarted`** on `trip-{id}`), with the command id
   **derived deterministically from the triggering event id** (`CommandId.deterministicFrom`) so a
   subscription restart / catch-up replay recreates it exactly once. This mirrors the Story 2.4
   `ItemMoveProcessManager` exactly and has **no orphaned-trip risk** (the trip is only ever created
   after the list transition committed). **Not** trip-primary (racy „≤1 trip") and **not** a
   two-append-in-one-handler (violates AD-10, non-atomic). [Source: `MoveItemHandler`,
   `ItemMoveProcessManager`, `CollaborationProcessManagerSubscription`; ARCHITECTURE-SPINE.md
   #AD-8/#AD-10.]

2. **Defer ALL trip read-side to Story 3.2 (Timo, 2026-08-28).** 3.1 builds the **write side**
   (`ShoppingTrip` aggregate + `TripStarted`) and only the **list** read-model change (status →
   In-Trip). **No** `trip_read_model`, **no** `ShoppingTripReadModelProjector`, **no** `trip-`
   subscription, **no** trip query in 3.1 — Story 3.2 (the store-grouped view) adds the trip projector
   with `fromStart` catch-up, which projects the 3.1-created trip streams retroactively (the ES way).
   Writing a `trip-{id}` stream that nothing projects yet is correct and expected. Premature-value
   discipline (Epic-2 retro). [Source: epics.md Story 3.2; Epic-2 retro §1.]

3. **In-Trip lists show under „Offen" with an „Im Einkauf" label; `ListOpenLists` includes In-Trip
   (Timo, 2026-08-28).** Extend `ListOpenLists.forHousehold` to return `status ∈ {OPEN, IN_TRIP}`
   (the „active, not archived" set) — **this also fixes the deferred „Liste N" ordinal under-count**
   (`deferred-work.md`: the ordinal must count Open *and* In-Trip lists once In-Trip is reachable —
   it is reachable now). The row renders an „Im Einkauf" status label for In-Trip. „Erledigt" stays
   Done-only. The list is **never** hidden from the overview mid-trip. [Source: `ListOpenLists`,
   `deferred-work.md` „ListOpenLists OPEN-only filter will under-count".]

4. **Extend the Story 2.6 reusable store picker to multi-select — do not fork a one-off (2.6 Cl. 8).**
   Trip start needs **≥1** store, so the selection is **multi-select with a confirm** (checkbox rows +
   „Einkauf starten" button), whereas 2.6 is single-select (tap-to-return). **Reuse** the picker's
   inline-create row (`StoreChainMatcher` + `StoreChainReferenceCache` + `AddStore`, Story 1.8/2.6)
   and its active-store list; **keep 2.6's single-select path working unchanged** (its list-detail
   and 3.2's in-trip reroute reuse it). Factor the shared inline-create-store row into a reusable
   widget if that keeps both modes DRY; do **not** duplicate `StoreChainMatcher`. Inline create adds
   the new store to the household **and to the current selection** (client-orchestrated: `AddStore`
   then include it), so the zero-stores household can start a trip (AC4). [Source:
   `store_picker_sheet.dart`, `deferred-work.md` „reusable store picker … single-select in 2.6 …
   Story 3.1 extends it to multi-select".]

5. **The list transitions to In-Trip; it never returns to Open (Timo default — trip lifecycle
   `Active → Done`).** In-Trip is a one-way step from Open; a trip completes to **Done** (Story 3.4),
   never back to Open. So „at most one Active trip per list" is fully enforced by „list must be Open
   to start" — the list does **not** need to remember an `activeTripId` in **its aggregate state** for
   this story (YAGNI; fold `status` only). The `tripId` and `storeIds` ride the `TripStartedForList`
   event **as the payload the process manager needs** (exactly like `ItemMovedToList` carries
   name/note/quantity for the target add), not because the list aggregate reasons over them.
   [Source: ARCHITECTURE-SPINE.md #Deferred „Trip lifecycle is permanently Active → Done"; `ListStatus`.]

6. **No migration in 3.1.** The `shopping_list_read_model.status` column already stores the
   `ListStatus` enum name (V5); `IN_TRIP` is simply a newly-reachable value. The list read model needs
   only a `markInTrip(listId)` UPDATE — **no `ALTER TABLE`, no new column, no `active_trip_id`** (that
   is 3.2's if the grouped view needs it). Zero migrations keeps 3.1 bounded. [Source:
   `V5__shopping_list_read_model.sql`, `JdbcShoppingListReadModel`.]

7. **3.1 starts the trip and reflects In-Trip; it does NOT navigate to the trip screen (that is 3.2).**
   The observable 3.1 outcome is: the list moves to In-Trip (overview label + read-only detail) and a
   success confirmation. The store-grouped **trip screen** (with check-off, reroute) is Story 3.2 — no
   trip screen, no trip navigation, no trip read model in 3.1. A brief „Einkauf gestartet"
   confirmation is enough. [Source: epics.md Story 3.2; UX EXPERIENCE.md „Active trip" screen = CAP-9/10,
   Story 3.2/3.3.]

8. **`ShoppingTrip` is minimal in 3.1 — just enough to exist and hold the store set.** The aggregate
   folds `TripStarted` into `{tripId, householdId, listId, storeIds, status=ACTIVE}` and exposes
   `start(...)` + `rehydrate(...)`. A `TripStatus { ACTIVE, DONE }` enum exists from the aggregate's
   birth (mirrors `ListStatus`), but **only `ACTIVE` is reachable in 3.1** — `DONE` is Story 3.4's
   completion transition; do **not** fabricate a completion path to reach it early (mirrors the Story
   2.1 `ListStatus.IN_TRIP`/`DONE` restraint). In-trip mutations (check/reroute/postpone/complete)
   are Stories 3.2–3.4. [Source: `ShoppingList` (2.1), `ListStatus` (2.1 Cl. 1).]

9. **Carry the Epic-2 retro action items into this first Epic-3 story (retro §6).** These are DoD, not
   review-catch: **(1)** the optimistic-state check — starting a trip must optimistically reflect
   In-Trip **everywhere it is server-visible** (overview label, detail read-only, „starten" hidden),
   and inline-created stores must merge into the selection (Action 1, targeting the dominant Epic-2 bug
   class); **(2)** a base **error-advice contract test** for the new trip endpoint (bad input/domain
   exceptions → 4xx) + the extended DoD (a11y labels, no dead code/strings, fail-fast guards) as a
   pre-review checklist, not a review discovery (Actions 2/3); **(3)** the `isSubmitting`/re-entrancy
   guard on the start-trip client path from the first pass (Action 3); **(4)** the `TripStartProcessManager`
   gets the **2.4 concurrency-conflict-retry / idempotent-converge safety net from the start**, not in
   review (Action 6); **(5)** the new projector case (`TripStartedForList` → In-Trip) ships with a
   two-household isolation + replay-idempotency test in the same change (Action 4). [Source:
   `epic-2-retro-2026-08-28.md` §6.]

## Tasks / Subtasks

> Follow TDD (CLAUDE.md §6): failing test first at the lowest level that proves the behaviour, then
> the simplest code to pass. Keep the domain pure (AD-1). **Mirror the cited existing file for every
> new class** — the aggregate, event, command→handler, process-manager, read-model, query, controller,
> and Flutter patterns are all established (Stories 2.1, 2.4, 2.6); do not invent new ones. This story
> stands up **SGART's third aggregate** (`ShoppingTrip`) and its **second process manager**.

### Backend — shared kernel: the trip id + stream key (AC1)

- [x] **Task 1: `TripId` value object** — package `shared` (mirror `ShoppingListId` exactly)
  - [x] `record TripId(UUID value)` with `requireNonNull`, `generate()`, `fromString(String)`,
        `toString()`. Javadoc mirrors `ShoppingListId` (cross-context id, client-minted, carried in
        the start-trip command envelope so the response needs no body — read-your-writes).
- [x] **Task 2: `StreamId.forTrip(TripId)`** — `shared.StreamId`
  - [x] Add `public static StreamId forTrip(TripId tripId)` returning `new StreamId(StreamType.TRIP,
        tripId.value())` (the `TRIP` prefix already exists). Update the class Javadoc line that says
        „`forTrip` arrives with its id type in Epic 3" — it arrives now.
  - [x] `StreamIdTest`: `forTrip` builds `trip-{uuid}`.

### Backend — domain: the two events + the list transition + the ShoppingTrip aggregate (AC1, AC2, AC3, Cl. 1/5/8)

- [x] **Task 3: `TripStartedForList` event** — package `collaboration.domain.event` (mirror
      `ItemMovedToList` — it too carries payload for a process manager)
  - [x] `record TripStartedForList(EventId eventId, HouseholdId householdId, ShoppingListId listId,
        TripId tripId, List<StoreId> storeIds)` — `requireNonNull` all; **copy `storeIds` into an
        unmodifiable list** and require it **non-empty** (defence-in-depth; the handler also 400s on
        empty, AC3). Javadoc: raised on the **`list-{id}` stream** as the single guarded append of a
        trip start; folds the list Open → In-Trip; carries `tripId` + `storeIds` **as the payload the
        `TripStartProcessManager` needs** to create the trip (Cl. 1/5), not because the list reasons
        over stores; carries no personal data and no *who* (AD-5/AD-6, mirrors `ItemAdded`).
- [x] **Task 4: `TripStarted` event** — package `collaboration.domain.event` (mirror
      `ShoppingListCreated`)
  - [x] `record TripStarted(EventId eventId, TripId tripId, HouseholdId householdId, ShoppingListId
        listId, List<StoreId> storeIds)` — `requireNonNull` all; unmodifiable non-empty `storeIds`.
        Javadoc: the **marquee** trip event on the **`trip-{id}` stream**; the `ShoppingTrip`
        aggregate's sole state-producing event in 3.1; the process-manager-issued create (Cl. 1). No
        personal data / no *who*.
- [x] **Task 5: `ListStatus` transition — `ShoppingList.startTrip(...)`** (AC1, AC2, Cl. 1/5)
  - [x] `public void startTrip(TripId tripId, List<StoreId> storeIds, CommandId commandId)`:
        `requireNonNull` all; **`requireOpen()`** → **not Open throws a new
        `TripNotStartableException`** (AC2 — a second start finds In-Trip; a Done list also refused).
        `raise(new TripStartedForList(EventId.generate(), householdId, listId, tripId, storeIds))`.
        (≥1-store is enforced fail-fast in the handler as a 400, AC3 — but the event constructor also
        rejects empty, Task 3.) Javadoc mirrors `moveItem`: does **not** load or validate the trip
        aggregate (it does not exist yet — the PM creates it, Cl. 1); does **not** validate the stores
        exist (client picker + AD-3 reference-by-id).
  - [x] `apply(TripStartedForList)` → `this.status = ListStatus.IN_TRIP`. Register it in the
        `apply(...)` switch. **Do not** store `tripId`/`storeIds` in the aggregate (Cl. 5, YAGNI).
  - [x] **Reachable-transition tests now exist** — extend `ShoppingListTest` (fast, no infra):
        `startTrip_raisesTripStartedForList_andFoldsInTrip`; **`startTrip_onAnInTripList_throwsTripNotStartable`**
        (rehydrate `[ShoppingListCreated, TripStartedForList]`, at-most-one, AC2);
        `startTrip_onADoneList_throwsTripNotStartable`; and the **two deferred-work tests now reachable**:
        `rename_onAnInTripList_isPermitted` (the `rename` Javadoc already allows Open|In-Trip — prove it
        with a real In-Trip fold) and **`itemCommands_onAnInTripList_areRefused`** (add/update/remove/
        move/assign on a rehydrated In-Trip list all throw `ItemChangeNotPermittedException`, AC6 —
        closes the 2.3/2.6 „reachable non-Open" defer). Synthetic German data.
- [x] **Task 6: `TripStatus` enum** — `collaboration.domain` (mirror `ListStatus`)
  - [x] `enum TripStatus { ACTIVE, DONE }`; Javadoc mirrors `ListStatus`: only `ACTIVE` reachable in
        3.1 (`TripStarted` folds to it), `DONE` is Story 3.4's completion transition — do not fabricate
        a path to reach it early (Cl. 8).
- [x] **Task 7: `ShoppingTrip` aggregate** — `collaboration.domain` (mirror `ShoppingList` — creation
      + rehydrate + `apply`, extending `EventSourcedAggregate`)
  - [x] Fields: `TripId tripId; HouseholdId householdId; ShoppingListId listId; List<StoreId> storeIds;
        TripStatus status`. Private ctor `ShoppingTrip(StreamId)`.
  - [x] `public static ShoppingTrip start(TripId tripId, HouseholdId householdId, ShoppingListId
        listId, List<StoreId> storeIds, CommandId commandId)`: `requireNonNull` all; require `storeIds`
        non-empty; `new ShoppingTrip(StreamId.forTrip(tripId))`; `raise(new TripStarted(EventId.generate(),
        tripId, householdId, listId, storeIds))`; return. Javadoc: SGART's **third aggregate** (AD-3),
        created by the `TripStartProcessManager` (Cl. 1), references its list + stores **by id only**
        (AR2), never loads them.
  - [x] `public static ShoppingTrip rehydrate(StreamId, List<? extends DomainEvent>)` (mirror
        `ShoppingList.rehydrate`).
  - [x] Accessors (`tripId()`, `householdId()`, `listId()`, `storeIds()` returning an unmodifiable
        copy, `status()`). `apply(TripStarted)` folds all fields + `status = ACTIVE`; `default ->`
        throws „ShoppingTrip cannot apply unknown event type" (mirror `ShoppingList.apply`).
  - [x] `ShoppingTripTest` (fast, pure, no infra): `start_raisesTripStarted_withListAndStores`;
        `rehydrate_[TripStarted]_isActiveWithItsStores`; `start_withNoStores_throws`. Synthetic data.
- [x] **Task 8: domain exception `TripNotStartableException`** — `collaboration.domain.exception`
      (mirror `ItemChangeNotPermittedException`)
  - [x] Runtime exception; Javadoc: the list is not Open, so no trip may start (AC2). Thrown by
        `ShoppingList.startTrip`.

### Backend — application: command + handler + the exceptions (AC1, AC2, AC3, AC7, Cl. 1/9)

- [x] **Task 9: `StartTrip` command + `StartTripHandler`** — package `application.command` (mirror
      `MoveItem`/`MoveItemHandler`, DTO **beside** its handler per CLAUDE.md §8)
  - [x] `record StartTrip(ShoppingListId listId, TripId tripId, List<StoreId> storeIds, CommandId
        commandId, AggregateVersion basedOnVersion)` — `requireNonNull` all; unmodifiable `storeIds`.
  - [x] `StartTripHandler.handle(keycloakUserId, rawHouseholdId, rawListId, rawTripId, rawStoreIds,
        rawCommandId)`: translate ids via `CommandFieldTranslations` (add `toTripId` +
        `toStoreIdList`/loop `toStoreId`, 400 on malformed); **≥1 store else
        `InvalidTripStoreSelectionException` (400, AC3)** — fail fast **before** loading (mirror
        `MoveItemHandler`'s target≠source 400); `resolveMemberIdentity.resolve` (403); load `list-{id}`
        (404 empty / 404 cross-household, mirror `loadListOwnedBy`); use the **loaded** version as the
        expected version (AD-8); `list.startTrip(tripId, storeIds, commandId)` translating
        `TripNotStartableException` → `TripNotStartableApplicationException` (409) at the seam;
        `eventStore.append(loadedVersion, list.uncommittedEvents(), commandId)`.
  - [x] Wire the bean in `CollaborationApplicationConfig` (mirror `moveItemHandler`).
  - [x] Application exceptions (`application.exception`): `InvalidTripStoreSelectionException` (→ 400),
        `TripNotStartableApplicationException` (→ 409). Confirm `WriteErrorAdvice` maps them to the
        right status + a stable `code` (mirror `MoveTargetNotOpenException`/`InvalidMoveTargetException`);
        **add the mapping test** (Action 2 error-advice contract).
  - [x] `StartTripHandlerTest` (in-memory `EventStore` + fake `ResolveMemberIdentity`, mirror
        `MoveItemHandlerTest`): starts (a `TripStartedForList` appended on `list-{id}`); **non-member →
        403**; malformed id → 400; **empty stores → 400**; unknown list → 404; cross-household list →
        404; **In-Trip list → 409** (at-most-one, AC2); Done list → 409.

### Backend — process manager: create the trip (AC1, Cl. 1/9)

- [x] **Task 10: `TripStartProcessManager`** — package `application` (mirror `ItemMoveProcessManager`
      **including its bounded concurrency-retry / converge-on-conflict safety net from the start**,
      retro Action 6)
  - [x] `onTripStartedForList(TripStartedForList started)`: `derivedCommandId =
        CommandId.deterministicFrom(started.eventId())`; `ShoppingTrip.start(started.tripId(),
        started.householdId(), started.listId(), started.storeIds(), derivedCommandId)`;
        `eventStore.append(AggregateVersion.initial(StreamId.forTrip(started.tripId())),
        trip.uncommittedEvents(), derivedCommandId)`. The trip stream is **new**, so the only
        conflict is a **redelivery** (the trip already exists) — **catch `ConcurrencyConflictException`
        and treat it as converged** (already created, exactly-once via the deterministic id), logging
        at debug (the create-analogue of 2.4's „`DuplicateItemException` swallow"). Never
        `CommandId.generate()` here (would double-create on replay). Javadoc mirrors
        `ItemMoveProcessManager`: infra-free, `InMemoryEventStore`-testable, acts on the system's own
        behalf (no `ResolveMemberIdentity`).
  - [x] Wire the bean in `CollaborationApplicationConfig` (mirror `itemMoveProcessManager`).
  - [x] `TripStartProcessManagerTest` (in-memory `EventStore`): a `TripStartedForList` creates the
        `ShoppingTrip` on `trip-{id}` with the right list + stores; **re-processing the same event
        (replay) creates nothing new** (idempotent — the derived id + converge-on-conflict); the trip
        carries the event's stores.
- [x] **Task 11: route the event into the PM transport** — extend
      `CollaborationProcessManagerSubscription` (the existing `list-`-prefix subscription — DRY, one
      subscription, two PMs)
  - [x] Constructor also takes `TripStartProcessManager`; `react(...)` routes `ItemMovedToList` →
        `itemMoveProcessManager` (unchanged) **and** `TripStartedForList` → `tripStartProcessManager`.
        Update `CollaborationProcessManagerConfig` to inject both PMs (same `auto-start` flag).
  - [x] Extend the subscription's Javadoc: it now drives **two** process managers off the `list-`
        stream (both react to distinct events; no interaction). Keep the per-event log-and-skip.

### Backend — read side: list → In-Trip + overview includes In-Trip (AC5, AC7, Cl. 2/3/6)

- [x] **Task 12: `ShoppingListReadModel.markInTrip` + JDBC + projector case** (no migration, Cl. 6)
  - [x] `ShoppingListReadModel` port: add `void markInTrip(ShoppingListId listId)`. `JdbcShoppingListReadModel`:
        `UPDATE shopping_list_read_model SET status = :status WHERE list_id = :listId` with
        `ListStatus.IN_TRIP.name()` (mirror `renameList`'s single-column UPDATE).
  - [x] `ShoppingListReadModelProjector`: add `case TripStartedForList started ->
        readModel.markInTrip(started.listId())`. (The trip's own `TripStarted` is **not** projected in
        3.1 — Cl. 2; the projector's `list-` filter never even sees `trip-` events.)
  - [x] Extend `ShoppingListReadModelProjectorTest` (Testcontainers): `TripStartedForList` flips the
        list's `status` to `IN_TRIP`; **two-household isolation** (a start in household A never flips a
        household-B list) **and replay idempotency** (re-projecting the event is a converging UPDATE) —
        both in this same change (retro Action 4). No PII (status is not personal data).
- [x] **Task 13: `ListOpenLists` includes In-Trip + ordinal fix** (AC5, Cl. 3)
  - [x] Change the filter to `list.status() == ListStatus.OPEN || list.status() == ListStatus.IN_TRIP`.
        Update the Javadoc: the „active, not archived" set; the AC2 „Liste N" ordinal now counts
        Open **and** In-Trip lists in creation order (this is the `deferred-work.md` fix landing).
        Leave `ListDoneLists` unchanged (Done-only).
  - [x] Extend `ListOpenListsTest`: an In-Trip list is returned by „open"; the returned `status` is
        `IN_TRIP`; the ordinal counts an In-Trip list (a `[Open, In-Trip, Open]` fixture yields three
        summaries in creation order). Confirm `ListDoneListsTest` still excludes In-Trip.

### Backend — adapter.in: the start-trip endpoint (AC1, AC3, AC7, Cl. 9)

- [x] **Task 14: `TripController` — `POST .../lists/{listId}/trips`** (mirror the nesting +
      envelope-DTO shape of `ItemController`/`ShoppingListController`)
  - [x] `@RestController @RequestMapping("/api/v1/households/{householdId}/lists/{listId}/trips")`;
        `@PostMapping @ResponseStatus(HttpStatus.CREATED)` `start(@AuthenticationPrincipal Jwt jwt,
        @PathVariable householdId, @PathVariable listId, @RequestBody StartTripRequest request)`;
        identity from the JWT `sub` via `AuthenticatedCaller` (never the body/path, AR10/AD-5).
        Constructor-inject `StartTripHandler`. `record StartTripRequest(String tripId, List<String>
        storeIds, String commandId)` — **plain `String`s only** (no `..domain..` import, ArchUnit).
        Rationale for a new controller (not a method on `ShoppingListController`): the trip is a
        first-class aggregate whose controller Stories 3.2–3.4 fill (check-off, reroute, complete) —
        it earns its keep (CLAUDE.md §8), and 3.1 seeds it with the one start endpoint.
  - [x] `TripControllerTest` (MockMvc slice, mirror `ItemControllerTest`): **201** on start; **403**
        non-member; **400** malformed id / **400** empty stores; **404** unknown list; **409** In-Trip
        list. This doubles as the Action-2 error-advice contract coverage for the new endpoint.
- [x] **Task 15: register both events in `DomainEventJsonCodec`** (mirror `ItemAssignedToStore`)
  - [x] Add `TRIP_STARTED_FOR_LIST_TYPE` + `TRIP_STARTED_TYPE` stable tags; `typeTagFor` +
        `toJsonBytes` + `fromJsonBytes` payload records for both (a `storeIds` list → `List<String>`
        of UUID strings; round-trip through `StoreId.fromString`). Extend `DomainEventJsonCodecTest`:
        both events round-trip (incl. a multi-store `storeIds` list) with a **stable type tag** (never
        a Java class name).
- [x] **Task 16: ArchUnit** — run `HexagonalArchitectureTest`; confirm the new id (`shared`), the two
      events + `ShoppingTrip` aggregate + `TripStatus` + `TripNotStartableException` (`..domain..`),
      the command/handler/PM/app-exceptions (`..application..`), and `TripController` (imports no
      `..domain..` — `StartTripRequest` is all `String`) need **no** rule change.

### Flutter — data layer (AC1, AC4)

- [x] **Task 17: `features/trips/data/` — the trips API** (first `trips` feature; mirror `items_api.dart`)
  - [x] `TripsApi` + `HttpTripsApi.startTrip(String householdId, String listId, {required String
        tripId, required List<String> storeIds, required String commandId})` → `POST
        .../lists/{listId}/trips` with `{tripId, storeIds, commandId}` (reuse
        `AuthenticatedHttpClient.postJson`; add it if only `putJson`/`patchJson` exist — mirror the 2.6
        `putJson` addition). `FakeTripsApi` for tests (records the last start; can be armed to throw).
  - [x] `trips_api_test.dart`: the request shape (path + `{tripId, storeIds[], commandId}`) is correct;
        a server error surfaces as the shared `AppException`.

### Flutter — reusable store picker → multi-select (AC3, AC4, UX-DR22, Cl. 4)

- [x] **Task 18: multi-select store selection** — `features/stores/presentation/` (extend the reusable
      picker; keep 2.6's single-select intact, Cl. 4)
  - [x] Add a multi-select entry point (e.g. `showTripStoreSelectionSheet(...)` or a `multiSelect`
        mode on the existing sheet) returning `List<StoreSummary>` (**≥1**, `null`/empty on dismiss):
        checkbox rows over the household's **active** stores (48px, chain label via the reference
        cache) + the **same** persistent „+ Neues Geschäft" inline-create row (live advisory chain
        suggestion, `AddStore` on success → the new store is **added to the selection**, AC4) + a
        confirm button **disabled until ≥1 selected** (AC3, UX-DR17). Extract the shared inline-create
        row into a reusable widget if it keeps single- and multi-select DRY; **do not** duplicate
        `StoreChainMatcher`.
  - [x] Keys: `trip-store-selection-sheet`, `trip-store-option-{storeId}` (checkbox),
        `trip-store-selection-confirm`, reuse the picker's `store-picker-new-name-field` /
        `store-picker-add-new`.
  - [x] `store_multi_select_test.dart` (widget, fakes): shows active stores as checkboxes; selecting
        ≥1 enables confirm and returns them; confirm is disabled with zero selected; the „+ Neues
        Geschäft" flow with a live chain suggestion creates a store **and adds it to the selection**;
        a duplicate name surfaces the inline error without closing; **the existing 2.6 single-select
        `showStorePickerSheet` still returns one store on tap** (regression).

### Flutter — list-detail: „Einkauf starten" + In-Trip read-only + optimistic state (AC1, AC5, AC6, AC7, Cl. 7/9)

- [x] **Task 19: `ListDetailCubit.startTrip` + In-Trip read-only** (`list_detail/`; retro Actions 1/3)
  - [x] Add a `TripsApi` dependency (re-provided on the `push(...)` route alongside the existing APIs,
        mirror the 2.6 `StoresApi` re-provide). `Future<void> startTrip(List<String> storeIds)` with a
        dedicated `_startTripIntent = CommandIntent()` (freshened on change + after success, the Epic-1
        spent-id footgun); guard **`ready && !isSubmitting && isOpen`** (Action 3 re-entrancy guard).
        Mint `tripId` client-side; call `tripsApi.startTrip(...)`; **optimistically set the list status
        to In-Trip** in state (so the detail flips read-only and „starten" hides — Action 1); on
        failure **revert + inline `actionError`**; on success `complete()` + a „Einkauf gestartet"
        confirmation (Cl. 7 — no trip navigation).
  - [x] Derive `isReadOnly` / the „can edit items" flag from **`status != OPEN`** (In-Trip **and**
        Done are read-only for off-trip item commands, AC6) — today it keys off Done only; extend it.
        Thread `status` (or an `isOpen`/`isInTrip`) through `ListDetailState` if not already present.
  - [x] `list_detail_cubit_test.dart`: `startTrip` optimistically flips status → In-Trip (detail
        read-only, „starten" gone) + reverts on failure + freshens the intent + the `isSubmitting`
        guard drops a second tap; an In-Trip list loads read-only (no add/edit/remove/move/assign);
        `startTrip` is refused when not Open.
- [x] **Task 20: `list_detail_page.dart` — the „Einkauf starten" action** (AC1, AC6, UX-DR17)
  - [x] On an **Open** list, render a **„Einkauf starten"** action (tonal/peer button at the list end
        per UX-DR7/UX-DR17; print/share is Story 3.5, so it is the primary bottom action here). On tap
        → `showTripStoreSelectionSheet(...)` (Task 18, passing the already-loaded active stores +
        `StoresApi` + reference cache) → on a returned non-empty selection,
        `cubit.startTrip(selection.map((s) => s.storeId).toList())`. **Hidden** on In-Trip and Done
        (AC6). Key: `list-detail-start-trip`.
  - [x] The item rows' edit/remove/move/store-chip affordances become **inert** on an In-Trip list
        (the extended `isReadOnly`, AC6) — mirror the existing Done gating; no new per-row code beyond
        the flag.
- [x] **Task 21: overview In-Trip label** — `list_overview/` (AC5)
  - [x] The list row renders an **„Im Einkauf"** status label for an `IN_TRIP` summary (the summary
        already carries `status`; „Offen" now returns In-Trip lists, Task 13). Confirm the „Offen"
        filter shows In-Trip rows and the „Liste N" ordinal counts them (client derives the ordinal
        from array position — the server now includes In-Trip, so it is correct without client
        changes). `lists_view_test.dart`: an In-Trip summary shows the „Im Einkauf" label under „Offen"
        and is **not** shown under „Erledigt".
- [x] **Task 22: localization** — `l10n` (`app_de.arb` + `flutter gen-l10n`)
  - [x] `tripStartAction` („Einkauf starten"), `tripStoreSelectionTitle` („Geschäfte wählen"),
        `tripStoreSelectionHelper` („In welchen Geschäften kaufst du diesmal ein? Mindestens eines."),
        `tripStoreSelectionConfirm` („Einkauf starten"), `tripStartedConfirmation` („Einkauf
        gestartet"), the In-Trip status label (`listStatusInTrip` / „Im Einkauf"), and a11y
        labels/semantics for the „starten" button + the multi-select checkbox rows (48px, UX-DR5).
        **Reuse** the 2.6 „+ Neues Geschäft" / picker strings where they already exist (check
        `storePickerAddNewAction` etc. before adding). No hard-coded user-facing strings (Action 2 DoD).

### Tests & green build (CLAUDE.md §6)

- [x] **Task 23: extended-DoD sweep (retro Actions 1/2/3)** — before review: optimistic state reflects
      every server-visible effect of a start (overview label, detail read-only, „starten" hidden,
      inline-created store in the selection); a11y labels on every new interactive element; no dead
      code / no hard-coded strings; fail-fast guards on every new command path; the `isSubmitting`
      guard on the start path; the error-advice mapping test for the new endpoint exists.
- [x] **Task 24: full-suite green** — backend `./gradlew test` (incl. `HexagonalArchitectureTest`
      ArchUnit **and** the Testcontainers `ShoppingListReadModelProjectorTest`) **and** Flutter
      `flutter analyze` + `flutter test`, both **in full** (not per-file), per CLAUDE.md §6. State
      which suite ran and the counts (baseline: backend **415**, Flutter **418**).

### Review Findings

Code review 2026-08-29 (bmad-code-review, Opus 4.8; Blind Hunter + Edge Case Hunter + Acceptance Auditor).
All 7 ACs confirmed met. 4 patch, 0 defer, 5 dismissed.

- [x] [Review][Patch] `startTrip` reverts to an editable Open view on a 409 (optimistic-state drift) — on `trip.notStartable` / a concurrency conflict the server list is already In-Trip, but the cubit unconditionally flips `isReadOnly` back to `false`, restoring „Einkauf starten" over a list every follow-up edit will 409. Keep read-only when the conflict confirms In-Trip. [app/lib/features/lists/presentation/list_detail/list_detail_cubit.dart:493]
- [x] [Review][Patch] Move-target picker now offers In-Trip lists that `MoveItem` always rejects (409 dead-end) — broadening `ListOpenLists` to `OPEN || IN_TRIP` leaked In-Trip lists into the move picker, which filters only the source list. Exclude `status == 'IN_TRIP'` from the selectable targets (keep the full-enumeration ordinal). [app/lib/features/lists/presentation/list_detail/move_target_sheet.dart:143]
- [x] [Review][Patch] Duplicate store ids accepted end-to-end — no dedupe in `toStoreIdList`, the handler, the aggregate, or the event constructors (only empty is rejected); a hand-crafted `storeIds: ["s1","s1"]` persists a trip carrying the store twice (Story 3.2 renders two identical groups). Add a `distinct()` dedupe. [backend/src/main/java/de/sgart/collaboration/domain/event/TripStarted.java:36]
- [x] [Review][Patch] Tautological test `freshensTheIntentAfterASuccessfulStart` — asserts two fresh cubits mint distinct command ids (always true), never exercising same-cubit re-entrancy; passes even if `complete()` were removed. Tighten to the real invariant or remove the misleading assertion. [app/test/features/lists/presentation/list_detail/list_detail_cubit_test.dart:893]

Dismissed (5): `markInTrip` default-throw on the read-model port (deliberate ISP tradeoff, single real impl, documented — YAGNI to segregate); `markInTrip` silent no-op on 0 rows updated (create always precedes on the same stream — not a current defect); `CollaborationProcessManagerSubscription` 3-arg constructor "verify" (sole caller `CollaborationProcessManagerConfig` updated in-diff, compiles); Done→409 handler test missing (Done unreachable via command until 3.4, covered at domain level by `startTrip_onADoneList_throwsTripNotStartable`); a11y "thinly met" (accessible by construction — CheckboxListTile min-height + labelled button/StatusLabel).

## Dev Notes

### What is (and isn't) in this story — read first

3.1 is a **domain-heavy full vertical slice** that stands up **SGART's third aggregate**
(`ShoppingTrip`) and its **second process manager** (`TripStartProcessManager`). It adds: **two
domain events** (`TripStartedForList` on `list-{id}`, `TripStarted` on `trip-{id}`), one **aggregate
transition** (`ShoppingList.startTrip` → In-Trip), one **new aggregate** (`ShoppingTrip`, minimal),
one **command + handler** (`StartTrip`), one **process manager** (create-the-trip), a **list
read-model transition** (status → In-Trip, **no migration**), the **`ListOpenLists` In-Trip
inclusion + ordinal fix**, a new **`TripController`** (`POST .../trips`), and the Flutter **„Einkauf
starten"** flow with the **multi-select** extension of the 2.6 reusable store picker.

It **deliberately does not** build (Cl. 2/7): any **trip read model / projector / query / trip-
subscription** (Story 3.2 adds them with `fromStart`, projecting 3.1's trips retroactively); any
**trip screen or trip navigation** (Story 3.2); any **in-trip item action** — check/uncheck/reroute/
postpone/complete (Stories 3.2–3.4); any **migration** (Cl. 6). `DONE` on both `ListStatus` and
`TripStatus` stays unreachable until Story 3.4 (Cl. 8).

The crux ideas:

- **List-primary + a process manager (Cl. 1).** The list is the *only* place the „≤1 Active trip"
  invariant can be atomically guarded (one-stream optimistic concurrency), so `StartTrip` makes the
  **list transition the single guarded append** (`TripStartedForList`), and a **`TripStartProcessManager`**
  reacts to create the trip (`TripStarted`) with a **deterministic command id** — exactly the Story
  2.4 `ItemMoveProcessManager` shape, with **no orphaned-trip risk**. The `tripId`/`storeIds` ride the
  list event as the payload the PM needs (like `ItemMovedToList` carries name/note/quantity).
- **In-Trip is a reachable status now (Cl. 3/6, AC5/AC6).** This flips several things that were coded
  „for Epic 3" but never exercised: the list read model gets an `IN_TRIP` value, `ListOpenLists` must
  include In-Trip (and its „Liste N" ordinal now counts it — the `deferred-work.md` fix), the list
  detail becomes read-only for off-trip item edits (In-Trip joins Done), and the `rename`-allowed-in-
  In-Trip + `requireOpen`-refuses-in-In-Trip branches finally get **reachable** tests (closing 2.1/2.3
  defers).

Flow (start a trip):

```
member on an Open list taps „Einkauf starten"
  → trip store-selection sheet (multi-select): ☑ Edeka  ☐ Netto  [ + Neues Geschäft ]
       (or „+ Neues Geschäft" → StoresApi.addStore → StoreAdded on household-{id}, added to selection)
  → confirm (≥1) → cubit.startTrip([edekaId, …])   (mint tripId + commandId; optimistic → In-Trip)
      → POST …/lists/{listId}/trips {tripId, storeIds, commandId}
      → StartTripHandler: load list-{id}, requireOpen()  ← atomic ≤1-trip guard
          → ShoppingList.startTrip → TripStartedForList on list-{id}  (list folds → IN_TRIP)
  KurrentDB $all ──filter list-*──▶ CollaborationProcessManagerSubscription.react(TripStartedForList)
      → TripStartProcessManager  (commandId = deterministicFrom(eventId), exactly-once)
          → ShoppingTrip.start → TripStarted on trip-{id}   ← marquee event; NOT projected in 3.1
  KurrentDB $all ──filter list-*──▶ ShoppingListReadModelProjector.project(TripStartedForList)
      → shoppingListReadModel.markInTrip(listId)            (status → IN_TRIP; overview „Im Einkauf")
```

### Architecture patterns & constraints

- **AD-10 cross-aggregate via process manager + AD-3 reference-by-id.** The list transition and the
  trip creation are two aggregates; the effect crosses via `TripStartProcessManager` (Cl. 1), never a
  two-append handler. `ShoppingTrip` references its list + stores **by id only**, never loading them.
  [ARCHITECTURE-SPINE.md #AD-3/#AD-10]
- **AD-8 online load-then-append + atomic guard.** `StartTripHandler` reads `list-{id}`, uses the
  loaded version as the expected version, appends the guarded `TripStartedForList`; a concurrent write
  loses with `ConcurrencyConflictException` (→ 409) — which is *also* how „≤1 Active trip" holds under
  a race. The PM's trip append is on a **fresh** `trip-{id}` stream; the only conflict is a redelivery
  (converge as already-created). [#AD-8]
- **Deterministic command id = exactly-once (retro Action 6).** `CommandId.deterministicFrom(eventId)`
  in the PM makes replay idempotent; the fresh-stream `ConcurrencyConflictException` is caught and
  treated as converged. Never `CommandId.generate()` in the PM. [`ItemMoveProcessManager`]
- **AD-4 CQRS read-model-only.** `markInTrip` is written only by the projector; `ListOpenLists` has no
  side effects. The trip stream has **no** projector in 3.1 (Cl. 2). [#AD-4]
- **AD-5/AD-6 no PII, no audit.** Both events carry household/list/trip/store ids only — no `MemberId`,
  no *who* (mirrors `ItemAdded`); `MemberId` is used only at the handler seam. The list read model is
  queried by `household_id`. [#AD-5/#AD-6]
- **AD-11 ubiquitous language.** `ShoppingTrip` / `TripStarted` / `startTrip` / „Einkauf" (trip) / „Im
  Einkauf" (In-Trip) / „Geschäfte wählen". `HouseholdRole` unaffected. No abbreviations. [#AD-11]
- **Eventual consistency (AR3/NFR9).** The `markInTrip` projection and the trip creation both lag the
  `StartTrip` response — the client optimistically shows In-Trip (Cl. 7/9); the trip aggregate is not
  read in 3.1 anyway.

### The In-Trip-becomes-reachable ripple (Cl. 3/6) — do not miss a spot

Making `IN_TRIP` reachable touches five places that were written „for Epic 3" but never exercised —
verify each: (1) `ShoppingList.apply(TripStartedForList)` folds `IN_TRIP`; (2) `JdbcShoppingListReadModel.markInTrip`
writes it; (3) **`ListOpenLists` must include it** or the list vanishes from the overview and „Liste N"
under-counts (`deferred-work.md`); (4) the list detail's read-only flag must key off `status != OPEN`,
not `status == DONE`; (5) the reachable `rename`-allowed / item-commands-refused In-Trip tests
(previously deferred) now have a real In-Trip fold to assert against.

### Source tree — mirror these exact files

| New/changed | Mirror | Path |
| --- | --- | --- |
| `TripId` | `ShoppingListId` | `shared/` |
| `StreamId.forTrip` | (extend) | `shared/` |
| `TripStartedForList` (event) | `ItemMovedToList` | `collaboration/domain/event/` |
| `TripStarted` (event) | `ShoppingListCreated` | `collaboration/domain/event/` |
| `ShoppingList.startTrip` + `apply(TripStartedForList)` | (extend) `moveItem`/`apply` | `collaboration/domain/` |
| `ShoppingTrip` (aggregate) + `TripStatus` | `ShoppingList` + `ListStatus` | `collaboration/domain/` |
| `TripNotStartableException` | `ItemChangeNotPermittedException` | `collaboration/domain/exception/` |
| `StartTrip` + `StartTripHandler` | `MoveItem` + `MoveItemHandler` | `collaboration/application/command/` |
| `InvalidTripStoreSelectionException`, `TripNotStartableApplicationException` | `InvalidMoveTargetException`, `MoveTargetNotOpenException` | `collaboration/application/exception/` |
| `TripStartProcessManager` | `ItemMoveProcessManager` | `collaboration/application/` |
| `CollaborationProcessManagerSubscription` (route 2nd event) | (extend) | `collaboration/adapter/out/` |
| `CollaborationApplicationConfig` / `CollaborationProcessManagerConfig` (wire) | (extend) | `collaboration/adapter/out/` |
| `ShoppingListReadModel.markInTrip` / `JdbcShoppingListReadModel` | (extend) `renameList` | `collaboration/domain/readmodel/`, `adapter/out/` |
| `ShoppingListReadModelProjector` (`TripStartedForList` case) | (extend) | `collaboration/adapter/out/` |
| `ListOpenLists` (include In-Trip + ordinal) | (extend) | `collaboration/application/query/` |
| `TripController` (`POST .../trips`) | `ItemController` (nesting + DTO) | `collaboration/adapter/in/` |
| `DomainEventJsonCodec` (2 events) | (extend, mirror `ItemAssignedToStore`) | `collaboration/adapter/out/` |
| `CommandFieldTranslations.toTripId` / store-id list | (extend) | `collaboration/application/` |
| `TripsApi` / `HttpTripsApi` + `FakeTripsApi` | `items_api.dart` | `app/lib/features/trips/data/` |
| trip store multi-select | (extend) `store_picker_sheet.dart` | `app/lib/features/stores/presentation/` |
| `ListDetailCubit.startTrip` + In-Trip read-only + `ListDetailState` | (extend) | `app/lib/features/lists/presentation/list_detail/` |
| `list_detail_page.dart` („Einkauf starten") | (extend) | `app/lib/features/lists/presentation/list_detail/` |
| `lists_view.dart` („Im Einkauf" label) | (extend) | `app/lib/features/lists/presentation/list_overview/` |

### Package structure (CLAUDE.md §8)

New backend classes drop into the **existing** intent subpackages (`domain.event`, `domain.exception`,
`application.command` with the DTO beside its handler, `application.exception`); `ShoppingTrip` +
`TripStatus` sit at the `domain` root beside `ShoppingList`/`ListStatus` (the aggregate root + its
status VO, mirroring the pattern). The `TripStartProcessManager` sits at `application` root beside
`ItemMoveProcessManager`. `TripController` is a **new** `adapter.in` controller — justified because the
trip is a first-class aggregate whose controller Stories 3.2–3.4 fill (it earns its keep, not a
premature stereotype package). Flutter gets a **new** `features/trips/` feature (data-only in 3.1; the
trip screen is 3.2). No ArchUnit rule change (rules match `..domain..`/`..application..`; the
controller imports no `..domain..`).

### Testing standards

- **Domain first (fast, pure, no infra):** `ShoppingTripTest` (start/rehydrate/no-stores);
  `ShoppingListTest` (startTrip raises + folds In-Trip; **at-most-one** via In-Trip rehydrate; Done
  refused; **reachable** rename-in-In-Trip-allowed + item-commands-in-In-Trip-refused).
- **Process manager:** `TripStartProcessManagerTest` (in-memory `EventStore`) — creates the trip once;
  **replay is idempotent** (deterministic id + converge-on-conflict).
- **Handler:** `StartTripHandlerTest` (in-memory `EventStore` + fake `ResolveMemberIdentity`) —
  403/400(malformed)/400(empty stores)/404/404(cross-household)/409(In-Trip)/409(Done) + the happy
  append.
- **Projector/read model (Testcontainers):** `ShoppingListReadModelProjectorTest` — `TripStartedForList`
  → status In-Trip; **two-household isolation + replay idempotency in the same change** (retro Action
  4). `ListOpenListsTest` — In-Trip included + ordinal counts it.
- **Controller (MockMvc):** `TripControllerTest` — 201/403/400/404/409 (doubles as the Action-2
  error-advice contract for the endpoint). `DomainEventJsonCodecTest` — both events round-trip with a
  multi-store list + stable type tags.
- **Flutter (fakes only, no network):** `trips_api` request shape; the **multi-select** sheet
  (checkboxes, ≥1-confirm-gate, inline create adds to selection, single-select regression);
  `ListDetailCubit.startTrip` (optimistic In-Trip + revert + intent freshening + `isSubmitting`
  guard + refused-when-not-Open); the In-Trip list detail is read-only; `lists_view` „Im Einkauf"
  label under „Offen".
- **DSGVO:** synthetic German data only; explicit no-PII stance on the events (AC7).
- **Green build = full suite** for both modules; state which ran and the counts (baseline backend
  415 / Flutter 418).

### Deferred / do-not-build (premature-value discipline)

- **Trip read model / projector / query / `trip-` subscription** → **Story 3.2** (grouped view;
  `fromStart` projects 3.1's trips retroactively). Cl. 2.
- **Trip screen + trip navigation** → **Story 3.2**. 3.1 ends at „Einkauf gestartet" + In-Trip. Cl. 7.
- **In-trip item actions (check/uncheck/reroute/postpone)** → **Stories 3.2/3.3**. Cl. 8.
- **Trip completion (`TripStatus.DONE`, list → Done)** → **Story 3.4** (which also lands the deferred
  cached Done-archive invalidation fix + the reachable Done-rename test). Cl. 8.
- **`active_trip_id` on the list read model** → 3.2 if the grouped-view navigation needs it (not
  needed to *start*, Cl. 5/6).
- **Live-sync of the In-Trip transition across devices** → Epic 4 (SSE). MVP: the starter sees it
  optimistically; others refetch on overview/detail open.
- **Print/share of the (grouped) list** → Story 3.5.

### Project Structure Notes

- The start endpoint is **nested under the list** (`POST /api/v1/households/{householdId}/lists/{listId}/trips`)
  because a trip is started *from* a list (the linked-list requirement is structural, AC3). Inline
  store creation still uses the **household-scoped** `StoreController` (`POST .../stores`, Story 1.8),
  client-orchestrated (Cl. 4) — two endpoints, two aggregates.
- **Zero migrations** (Cl. 6): `status` is an existing string column; `IN_TRIP` is a newly-reachable
  value. If a later story needs `active_trip_id`, that ALTER is 3.2's, not 3.1's.
- The `CollaborationProcessManagerSubscription` now drives **two** PMs off the one `list-` prefix
  subscription (DRY) — both react to distinct events with no interaction.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story-3.1] — user story + BDD ACs (FR9 trip start, ≥1 store, ≤1 active trip, inline store).
- [Source: _bmad-output/planning-artifacts/epics.md#Story-3.2/3.3/3.4/3.5] — the trip read side / grouped view / in-trip actions / completion / print this story defers to.
- [Source: ARCHITECTURE-SPINE.md #AD-3/#AD-4/#AD-8/#AD-10/#AD-11 + #AR2; #Deferred „Trip lifecycle Active → Done"] — third aggregate, reference-by-id, atomic guard, cross-aggregate PM, exactly-once, no PII, one-way lifecycle.
- [Source: ux-designs/ux-sgart-2026-08-20/DESIGN.md UX-DR7/UX-DR17/UX-DR22; EXPERIENCE.md §3 „Active trip"/„List detail"; .working/screen-trip-lifecycle.html Frame 1] — „Einkauf starten" as a list-detail peer action, multi-select ≥1 stores, inline „+ Neues Geschäft", tonal/quiet actions.
- [Source: backend `ShoppingList.java` (moveItem/apply/requireOpen/ListStatus), `ItemMovedToList.java`, `ItemMoveProcessManager.java`, `CollaborationProcessManagerSubscription.java`, `CollaborationProcessManagerConfig.java`, `MoveItemHandler.java`, `CreateShoppingListHandler.java`, `ShoppingListController.java`, `ItemController.java`, `ShoppingListReadModelProjector.java`, `JdbcShoppingListReadModel.java`, `ListOpenLists.java`, `DomainEventJsonCodec.java`, `CommandFieldTranslations.java`, `StreamId.java`, `ShoppingListId.java`, `CommandId.java`, `EventSourcedAggregate.java`] — every pattern to mirror.
- [Source: app `features/stores/presentation/store_picker_sheet.dart`, `stores_cubit.dart`, `data/store_chain_matcher.dart`, `store_chain_reference_cache.dart`, `stores_api.dart`; `features/lists/presentation/list_detail/*`, `list_overview/lists_view.dart`; `shared/commands/command_intent.dart`, `shared/http/authenticated_http_client.dart`] — the client patterns to mirror; the 2.6 picker to extend to multi-select.
- [Source: _bmad-output/implementation-artifacts/2-1-…md, 2-4-…md, 2-6-…md] — the aggregate slice, the first process manager (2.4), the reusable picker + optimistic-state lessons (2.6).
- [Source: _bmad-output/implementation-artifacts/deferred-work.md] — the `ListOpenLists` In-Trip ordinal fix; the reachable In-Trip/Done rename + item-command tests; the picker multi-select extension; the trip/print consumers.
- [Source: _bmad-output/implementation-artifacts/epic-2-retro-2026-08-28.md §6] — carried action items 1/2/3/4/6 baked into Cl. 9 + the DoD tasks.
- [Source: CLAUDE.md §1–§8] — Clean Code, naming, DDD, CQRS, DSGVO, testing, package structure.

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (dev-story)

### Debug Log References

- Docker/Testcontainers available in this environment; `ShoppingListReadModelProjectorTest` ran
  for real against a `postgres:18.6` container (not skipped).
- `flutter gen-l10n` regenerated `lib/l10n/gen/app_localizations*.dart` after the `app_de.arb`
  additions (`tripStartAction`, `tripStoreSelectionTitle`, `tripStoreSelectionHelper`,
  `tripStoreSelectionConfirm`, `tripStartedConfirmation`, `listStatusInTrip`).

### Completion Notes List

- **Backend, full vertical slice as planned.** `TripId` (shared) + `StreamId.forTrip`;
  `TripStartedForList` (list-{id}) + `TripStarted` (trip-{id}) domain events (both non-empty
  `storeIds`, defence-in-depth); `ShoppingList.startTrip` (`Open → In-Trip`, `TripNotStartableException`
  guard, AC2); `TripStatus` + minimal `ShoppingTrip` aggregate (only `ACTIVE` reachable, Cl. 8);
  `StartTrip` command + `StartTripHandler` (400 empty-stores/malformed, 403, 404, 404 cross-household,
  409 In-Trip); `TripStartProcessManager` (deterministic command id, `ConcurrencyConflictException`
  converge-on-conflict — the create-analogue of the 2.4 `DuplicateItemException` swallow, retro Action
  6); `CollaborationProcessManagerSubscription` extended to route both `ItemMovedToList` and
  `TripStartedForList` to their respective process managers over the one `list-` subscription;
  `ShoppingListReadModel.markInTrip` (JDBC + projector case, no migration, Cl. 6); `ListOpenLists`
  extended to `OPEN ∪ IN_TRIP` (fixes the deferred "Liste N" ordinal under-count); new `TripController`
  (`POST .../trips`, 201, plain-`String` DTO); both events registered in `DomainEventJsonCodec` with
  stable type tags and multi-store round-trip coverage. ArchUnit (`HexagonalArchitectureTest`) passes
  unchanged — no new rule needed.
- **Regression fix found during Task 5 (not a review discovery):** `ShoppingList.rename`'s guard was
  `status != OPEN` even though its Javadoc already promised "Open (or In-Trip)" — Story 2.1 never
  exercised the In-Trip branch end-to-end. Fixed to `status != OPEN && status != IN_TRIP` so the
  reachable `rename_onAnInTripList_isPermitted` test (Task 5) actually passes; this was the "closes a
  2.1/2.3 deferred-work test" ripple the Dev Notes called out.
- **`DONE` list-status test fixture:** since no event yet drives `ListStatus.DONE` (Story 3.4), the
  `startTrip_onADoneList_throwsTripNotStartable` domain test forces the enum via a small reflection
  helper (`setStatus`) — a test-only fixture, not a fabricated aggregate transition (Cl. 8 compliant).
  The equivalent Done-list 409 case is not asserted in `StartTripHandlerTest`/`TripControllerTest` for
  the same reachability reason documented elsewhere in the suite (e.g. `ShoppingListItemsTest`).
- **Flutter, full vertical slice as planned.** New `features/trips/data/trips_api.dart`
  (`TripsApi`/`HttpTripsApi`) wired into `first_run_router.dart` alongside the other APIs.
  `store_picker_sheet.dart` refactored: the "+ Neues Geschäft" inline-create row (name field + live
  chain suggestion + submit) extracted into a shared `InlineCreateStoreRow` widget so the existing
  single-select `showStorePickerSheet` and the new multi-select `showTripStoreSelectionSheet` (`≥1`
  checkbox rows, confirm gated on selection, inline-created stores added to the selection) stay DRY
  without duplicating `StoreChainMatcher` — the single-select path is unchanged behaviourally (covered
  by a regression test). `ListDetailState.isReadOnly` made mutable via `copyWith` and reused as the
  single flag for both "Done" and "In-Trip" (both are off-trip-item-command read-only, AC5/AC6) —
  avoided adding a redundant `status`/`isOpen` field since the existing boolean already fully captures
  the needed state machine for this story. `ListDetailCubit.startTrip` mints `tripId` client-side via a
  `CommandIntent`, optimistically flips `isReadOnly` to `true` before the call (Cl. 7/9), reverts +
  surfaces `actionError` on failure, guarded by `ready && !isSubmitting && !isReadOnly` (retro Action
  3). `list_detail_page.dart` renders "Einkauf starten" (tonal, UX-DR7) only when `!isReadOnly`, opens
  the multi-select sheet, and shows a "Einkauf gestartet" `SnackBar` confirmation on success (no trip
  navigation, Cl. 7). `lists_view.dart`'s row status label now branches `OPEN` vs `IN_TRIP`
  ("Im Einkauf"); the Open-filter `onOpen` push now derives `isReadOnly` from the list's own `status`
  (previously hardcoded `false`) so a reopened In-Trip list opens read-only immediately.
- **DoD sweep (Task 23):** optimistic state verified against every server-visible effect (overview
  label via `onEditableReturn` refresh, detail read-only, action hidden, inline-created store in
  selection) with dedicated tests; every new interactive element uses the existing accessible
  `SgartButton`/`CheckboxListTile` primitives (no bespoke a11y code needed, consistent with the rest of
  the codebase); no hard-coded user-facing strings (grepped); the `isSubmitting` re-entrancy guard is
  in place and tested; the error-advice mapping is covered by `TripControllerTest`'s 400/403/404/409
  MockMvc assertions (mirrors the established pattern — no separate `WriteErrorAdviceTest` exists
  anywhere in the codebase to extend).
- **Full-suite green (Task 24):** backend `./gradlew test` — 415 → **455** (incl.
  `HexagonalArchitectureTest` ArchUnit and the Testcontainers `ShoppingListReadModelProjectorTest`,
  which ran against a real container, not skipped). Flutter `flutter analyze` (clean) + `flutter test`
  — 418 → **439**.

### File List

**Backend — new:**
- `backend/src/main/java/de/sgart/shared/TripId.java`
- `backend/src/main/java/de/sgart/collaboration/domain/event/TripStartedForList.java`
- `backend/src/main/java/de/sgart/collaboration/domain/event/TripStarted.java`
- `backend/src/main/java/de/sgart/collaboration/domain/TripStatus.java`
- `backend/src/main/java/de/sgart/collaboration/domain/ShoppingTrip.java`
- `backend/src/main/java/de/sgart/collaboration/domain/exception/TripNotStartableException.java`
- `backend/src/main/java/de/sgart/collaboration/application/command/StartTrip.java`
- `backend/src/main/java/de/sgart/collaboration/application/command/StartTripHandler.java`
- `backend/src/main/java/de/sgart/collaboration/application/exception/InvalidTripStoreSelectionException.java`
- `backend/src/main/java/de/sgart/collaboration/application/exception/TripNotStartableApplicationException.java`
- `backend/src/main/java/de/sgart/collaboration/application/TripStartProcessManager.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/in/TripController.java`
- `backend/src/test/java/de/sgart/shared/TripIdTest.java`
- `backend/src/test/java/de/sgart/collaboration/domain/ShoppingTripTest.java`
- `backend/src/test/java/de/sgart/collaboration/application/StartTripHandlerTest.java`
- `backend/src/test/java/de/sgart/collaboration/application/TripStartProcessManagerTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/in/TripControllerTest.java`

**Backend — changed:**
- `backend/src/main/java/de/sgart/shared/StreamId.java`
- `backend/src/main/java/de/sgart/collaboration/domain/ShoppingList.java`
- `backend/src/main/java/de/sgart/collaboration/application/CommandFieldTranslations.java`
- `backend/src/main/java/de/sgart/collaboration/application/query/ListOpenLists.java`
- `backend/src/main/java/de/sgart/collaboration/domain/readmodel/ShoppingListReadModel.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/JdbcShoppingListReadModel.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/ShoppingListReadModelProjector.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/CollaborationProcessManagerSubscription.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/CollaborationProcessManagerConfig.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/CollaborationApplicationConfig.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/DomainEventJsonCodec.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/in/WriteErrorAdvice.java`
- `backend/src/test/java/de/sgart/shared/StreamIdTest.java`
- `backend/src/test/java/de/sgart/collaboration/domain/ShoppingListTest.java`
- `backend/src/test/java/de/sgart/collaboration/application/ListOpenListsTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/out/ShoppingListReadModelProjectorTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/out/DomainEventJsonCodecTest.java`

**Flutter — new:**
- `app/lib/features/trips/data/trips_api.dart`
- `app/test/features/trips/data/trips_api_test.dart`
- `app/test/support/fake_trips_dependencies.dart`
- `app/test/features/stores/presentation/store_multi_select_test.dart`

**Flutter — changed:**
- `app/lib/features/households/presentation/first_run_router.dart`
- `app/lib/features/stores/presentation/store_picker_sheet.dart`
- `app/lib/features/lists/presentation/list_detail/list_detail_state.dart`
- `app/lib/features/lists/presentation/list_detail/list_detail_cubit.dart`
- `app/lib/features/lists/presentation/list_detail/list_detail_page.dart`
- `app/lib/features/lists/presentation/list_overview/lists_view.dart`
- `app/lib/l10n/app_de.arb`
- `app/lib/l10n/gen/app_localizations.dart` (generated)
- `app/lib/l10n/gen/app_localizations_de.dart` (generated)
- `app/test/features/lists/presentation/list_detail/list_detail_cubit_test.dart`
- `app/test/features/lists/presentation/list_detail/list_detail_page_test.dart`
- `app/test/features/lists/presentation/list_detail/fast_add_field_test.dart`
- `app/test/features/lists/presentation/list_detail/move_merge_dialog_test.dart`
- `app/test/features/lists/presentation/list_detail/move_target_sheet_test.dart`
- `app/test/features/lists/presentation/list_overview/lists_view_test.dart`

**Process/tracking:**
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-08-28: Story drafted (create-story, Opus 4.8) — Epic 3's first story / SGART's **third
  aggregate** (`ShoppingTrip`) + **second process manager** (`TripStartProcessManager`). Full vertical
  slice: `TripStartedForList` (list-{id}, guarded primary) + `TripStarted` (trip-{id}, PM-created),
  `ShoppingList.startTrip` Open→In-Trip transition, minimal `ShoppingTrip` aggregate, `StartTrip`
  command/handler, `ListOpenLists` In-Trip inclusion + „Liste N" ordinal fix, list read-model
  `markInTrip` (no migration), new `TripController` (`POST .../trips`), and the Flutter „Einkauf
  starten" flow extending the 2.6 reusable store picker to **multi-select** with inline store creation.
  9 LOCKED clarifications; **Timo decided (2026-08-28): (1) list-primary + process manager
  coordination; (2) defer all trip read-side to 3.2; (3) In-Trip lists under „Offen" with an „Im
  Einkauf" label.** Epic-2 retro action items 1/2/3/4/6 baked into the DoD (Cl. 9). Baseline backend
  415 / Flutter 418.
- 2026-08-29: Story implemented (dev-story, Sonnet 5) — all 24 tasks complete as a single full
  vertical slice, exactly as planned. Backend: `TripId`/`StreamId.forTrip`, `TripStartedForList` +
  `TripStarted` events, `ShoppingList.startTrip` (Open→In-Trip, AC2 guard), minimal `ShoppingTrip`
  aggregate, `StartTrip` command/handler, `TripStartProcessManager` (deterministic id +
  converge-on-conflict), the process-manager subscription extended to route two events, list
  read-model `markInTrip` (no migration), `ListOpenLists` In-Trip inclusion (fixes the "Liste N"
  ordinal under-count), new `TripController`, both events registered in the JSON codec. Found and
  fixed one pre-existing bug while implementing Task 5: `ShoppingList.rename`'s guard never actually
  matched its own "Open or In-Trip" Javadoc promise (Story 2.1 could never reach the In-Trip branch to
  notice) — now fixed and covered. Flutter: new `trips` feature (`TripsApi`), the reusable store
  picker's inline-create row extracted into a shared `InlineCreateStoreRow` widget and reused by a new
  multi-select `showTripStoreSelectionSheet` (single-select regression-tested, Cl. 4),
  `ListDetailCubit.startTrip` (optimistic In-Trip via the existing `isReadOnly` flag reused for both
  Done and In-Trip, `isSubmitting` guard, revert-on-failure), the "Einkauf starten" tonal action +
  "Einkauf gestartet" confirmation, and the overview's "Im Einkauf" status label. Epic-2 retro actions
  1/2/3/4/6 addressed inline (optimistic-state tests, error-advice contract via `TripControllerTest`,
  `isSubmitting` guard, projector isolation+idempotency tests, PM concurrency-retry pattern). Backend
  415→455 green (incl. ArchUnit + Testcontainers). Flutter 418→439 green (incl. analyze). Status →
  review.
