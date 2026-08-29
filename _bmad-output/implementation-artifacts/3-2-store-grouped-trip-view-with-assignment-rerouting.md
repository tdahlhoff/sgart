---
baseline_commit: b1b72272
---

# Story 3.2: Store-grouped trip view with assignment & rerouting

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a member,
I want items grouped by store with an unassigned section, and to assign or reroute them between the
trip's stores (adding a store on the spot),
so that I pick up the right things at each store while I shop.

## Acceptance Criteria

Derived from **epics.md § Story 3.2** (FR9/CAP-9 store-grouped trip view + assignment/rerouting),
refined against **ARCHITECTURE-SPINE.md** (AD-3, AD-4, AD-8, AD-10, AD-11, AR2), **UX-DR7 / UX-DR17 /
UX-DR22 / EXPERIENCE.md §3 „Active trip"**, the `screen-active-trip.html` artifact (store groups,
„Noch nicht zugeordnet", per-item „Zuordnen"/reroute, ⋯ „Geschäft hinzufügen / umleiten"), the
Story 3.1 write side it consumes (`ShoppingTrip`, `TripStarted`, list `IN_TRIP`, `item_read_model.store_id`
from 2.6), and **Timo's decisions (2026-08-29)** captured in the Clarifications. This is **the second
Epic-3 story** — it lands the **trip read side deferred from 3.1 (Cl. 2)**, the **first in-trip
mutations**, and **SGART's first trip screen**. Each AC is independently testable.

1. **Store-grouped active-trip view with a „Noch nicht zugeordnet" section (AC1, FR9).** Given an
   **active** trip, when it is viewed, then items are shown **grouped by the trip's stores** — one
   group per store the trip selected (Story 3.1) or spontaneously added (AC3), in add order, each
   group listing the items assigned to that store — plus a **„Noch nicht zugeordnet"** section for
   items with **no assignment, an assignment to a store not in this trip, or an assignment to an
   archived store** (Cl. 7, the 1.8 E6 fallback). An empty trip-store group still renders its header
   (so it is a visible reroute target, Cl. 5). Items show **name / quantity / note** and their store
   context; **no check-off, no progress bar, no completion** in 3.2 (Cl. 2 — those are Stories
   3.3/3.4). The view is served by the **trip read side this story adds** (`trip_store_read_model` +
   `ShoppingTripReadModelProjector`, `fromStart`, which projects the **3.1-created** `TripStarted`
   streams retroactively — the ES way, Cl. 2/4).

2. **Assign an unassigned item / reroute an assigned item to a store in the trip (AC2, FR9).** Given
   an item in the trip, when a member assigns it to a trip store (from „Noch nicht zugeordnet") or
   **reroutes** it from one trip store to another, then its **store assignment updates** and it moves
   under that store's group. This is a **new in-trip command on the `ShoppingList` aggregate** —
   `ShoppingList.rerouteItem(itemId, storeId)`, **gated on the list being `IN_TRIP`** (the inverse of
   2.6's off-trip `assignItemToStore`, which requires `OPEN` and 3.1 deliberately refuses when
   In-Trip) — raising a **new `ItemRerouted`** event on the **`list-{id}`** stream; the projector
   folds it to the **existing `item_read_model.store_id`** (one source of truth for item→store,
   Cl. 1). **Reroute is distinct from Postpone** (Story 3.3): reroute changes *where*, never the
   item's status. Same-store reroute is a **convergent no-op** (raises nothing, AD-8).

3. **Add a store to the trip spontaneously, incl. inline creation (AC3, FR3/CAP-3, UX-DR22).** Given
   a member during a trip, when they add a store — either an existing household store or a **brand-new
   one created inline** (free-form name + advisory client-side chain suggestion, the **same rules as
   Story 1.8/2.6/3.1**) — then that store **becomes part of the trip** (a new **`StoreAddedToTrip`**
   event on the **`trip-{id}`** stream via **`ShoppingTrip.addStore`**, the trip's **first in-trip
   mutation**) and appears as a group that can receive assignments (AC2). Adding a store already in
   the trip is a **convergent no-op** (raises nothing). Inline creation is **client-orchestrated**:
   `AddStore` on the household (Story 1.8), then `AddStoreToTrip` on the trip — two commands, two
   aggregates (mirrors 3.1's inline create, AD-3/AD-10).

4. **Reach the trip screen from the list and from the „Einkauf" tab (AC4).** Given a member, when they
   **start a trip** (Story 3.1) then the app **navigates to the trip screen** (the navigation 3.1
   deferred, Cl. 7→here); when they **tap an „Im Einkauf" list row** in the „Listen" overview then it
   **opens that list's trip screen** (using the list summary's new **`activeTripId`**); and the
   **„Einkauf" bottom-nav tab** (a placeholder until now) shows an **active-trips index** — one row per
   In-Trip list (list name + „Im Einkauf" + item count), each opening its trip screen, with a **calm
   empty state** when none (Cl. 3). The index **reuses `ListOpenLists`** filtered to `IN_TRIP` (DRY —
   no new household-scoped trip endpoint, Cl. 4).

5. **Membership, isolation, eventual consistency & no personal data (AC5).** Every new command is
   **membership-gated** (non-member → **403**), a malformed id is **400**, an unknown/other-household
   list or trip is **404**; rerouting on a **not-In-Trip** list is **409**
   (`ItemNotReroutableException`, a state conflict mirroring 3.1's `TripNotStartable` — the trip may
   have completed concurrently). `ItemRerouted` and `StoreAddedToTrip` carry **no personal data and no
   *who*** — household/list/trip/store/item ids only (AD-5/AD-6, mirrors `ItemAssignedToStore`);
   `MemberId` is used only at the handler seam. The trip read models are queried **by `household_id`**
   so one household's trip never leaks to another. The reroute/add-store client paths are
   **optimistic** (the projection lags, AR3/NFR9) with revert-on-failure. Tests use synthetic,
   clearly-fake German data only.

## Clarifications (LOCKED)

Taken from the epic ACs, the ARCHITECTURE-SPINE, the Story 2.6/3.1 patterns, `deferred-work.md`, the
Epic-2 retro action items, and **Timo's decisions (2026-08-29)**. **If any is wrong, correct it
before `dev-story`.**

1. **In-trip reroute lives on the `ShoppingList` aggregate as a new `IN_TRIP`-gated `ItemRerouted`
   event — NOT a trip-owned `StoreAssignment`, NOT a relaxation of 2.6's `assignItemToStore` (Timo,
   2026-08-29 — the crux).** The item's store is **one concept, owned by the `ShoppingList`** (2.6 put
   it on the `Item` as `item.assignedStore` → `item_read_model.store_id`). In-trip routing is a **new
   command** `ShoppingList.rerouteItem(itemId, storeId, commandId)` **gated on `IN_TRIP`** (a
   `requireInTrip()` helper, the inverse of `requireOpen()`), raising **`ItemRerouted`** on
   `list-{id}`; the projector folds it to the **same** `item_read_model.store_id` via the existing
   `itemReadModel.assignStore(...)` write. This keeps a **single source of truth** for item→store and
   sets up **Story 3.3's** in-trip check-off/postpone as the **identical `IN_TRIP`-gated pattern** on
   the same aggregate. Rejected: a trip-owned `StoreAssignment` on `trip-{id}` (the ER-diagram's
   `SHOPPING_TRIP ||--o{ STORE_ASSIGNMENT` is names-only, not aggregate-binding — AD-3 §"attribute
   invariants are ADs, not this diagram") — it would create two sources of truth and still leave 3.3's
   status check-off on the list. [Source: `ShoppingList.assignItemToStore`/`requireOpen`;
   ARCHITECTURE-SPINE.md #AD-4/#AD-10; epics.md Story 3.2 AC2 „StoreAssignment updates … Reroute is
   distinct from Postpone".]

2. **3.2's slice = grouped view + reroute + spontaneous add-store; check-off/uncheck/postpone + the
   progress bar → Story 3.3; completion → Story 3.4 (Timo, 2026-08-29).** The trip screen renders
   store groups + „Noch nicht zugeordnet" + item rows (name/qty/note) + per-item assign/reroute + „+
   Geschäft" (add-store), and **nothing else**: **no functional checkboxes**, **no „X von Y erledigt"
   progress bar** (both meaningless without check-off — 3.3), and **no „Einkauf abschließen"** (3.4).
   Items are read-only rows w.r.t. status in 3.2. This matches the epic's own 3.2/3.3 boundary
   (3.3 = „Check off, uncheck, and postpone during a trip"). [Source: epics.md Story 3.3/3.4;
   `screen-active-trip.html` (the checkbox/progress/„abschließen" are 3.3/3.4).]

3. **Reach the trip screen from the list (auto-nav on start + tap In-Trip row) AND the „Einkauf" tab =
   an active-trips index reusing `ListOpenLists` (Timo, 2026-08-29).** Starting a trip (Story 3.1,
   which today ends at a „Einkauf gestartet" toast) now **navigates** to the trip screen; tapping an
   „Im Einkauf" row in „Listen" opens that list's trip screen; and the **„Einkauf" bottom-nav tab**
   (currently `_ShoppingPlaceholder`) becomes an **active-trips index**. Because a household can hold
   **several** In-Trip lists at once (at-most-one-trip is *per list*), the tab lists them — one row per
   In-Trip list → its trip screen — with a calm empty state. The index **reuses the `ListOpenLists`
   read** the overview already loads, **filtered to `IN_TRIP`** (which now carries `activeTripId` for
   navigation, Cl. 4) — no new household-scoped trip query/endpoint. Store-count-per-row on the index
   („2 Geschäfte") is **deferred** (shown on the trip screen; keep the index to name + item count).
   [Source: `household_shell.dart` (Einkauf placeholder); EXPERIENCE.md §Nav („Einkauf" tab = trips);
   `ListOpenLists`.]

4. **Trip read side = `active_trip_id` on the list read model + a `trip_store_read_model`; NO
   `trip_read_model` header (Timo/YAGNI).** The store-grouped view needs two things 3.1 did not
   persist: the **trip's store set** and a **navigation key list→trip**. Land both leanly:
   **(a)** `ALTER shopping_list_read_model ADD active_trip_id UUID NULL`, **set by the existing list
   projector** on `TripStartedForList` (which already carries `tripId`) — powers the overview-tap +
   Einkauf-index navigation with **no join** (the list is the 1:1 anchor of an active trip); Story 3.4
   clears it on completion. **(b)** a new **`trip_store_read_model(trip_id, store_id, sequence_number)`**
   maintained by a **new `ShoppingTripReadModelProjector`** on the **`trip-` prefix**, `fromStart`, so
   it **retroactively projects the 3.1-created `TripStarted` streams** (Cl. 2's promise) — `TripStarted`
   → insert the initial store rows, `StoreAddedToTrip` → insert the added row (idempotent upsert).
   A **`trip_read_model` header table is deliberately NOT added** (household_id/list_id/status are all
   derivable via the list's `active_trip_id`; a header would duplicate them — KISS/YAGNI). The
   grouped-view query is **list-scoped**: `GET …/lists/{listId}/trips/active` resolves the list
   (household-gated), reads its `active_trip_id` (→ `tripId`), then its stores (`trip_store_read_model`)
   + its items (`item_read_model`). [Source: `V5`/`V8` migration style;
   `ShoppingListReadModelProjector` (mirror for the new trip projector); `deferred-work.md`
   „`active_trip_id` on the list read model → 3.2 if navigation needs it".]

5. **The reroute target is the trip's stores + inline create; the server does NOT validate the store
   is in the trip.** The reroute picker offers **only the trip's current stores** (AC2 „a store *in the
   trip*") plus the **„+ Neues Geschäft"** inline row, whose success **adds the store to the household
   (`AddStore`) AND to the trip (`AddStoreToTrip`) AND selects it** as the reroute target — so routing
   to a brand-new store is one flow. Server-side, `ShoppingList.rerouteItem` **does not** validate that
   `storeId` is one of the trip's stores (the `ShoppingList` aggregate does not know the trip's store
   set — separate aggregate, AD-3; mirrors `assignItemToStore`/`moveItem` not validating their target
   exists). Client picker + read-side grouping enforce it. [Source: `ShoppingList.assignItemToStore`
   „does not validate the store exists"; UX-DR22.]

6. **Reroute updates `item_read_model.store_id` only — it does NOT rewrite the suggestion's
   `default_store_id`.** 2.6 records a name's last-used store on *planning* assignment
   (`ItemAssignedToStore` → `recordDefaultStore`). Reroute is a **trip-time correction of where an item
   is bought this run**, not a statement of the article's planning default, so the projector's
   `ItemRerouted` case writes **only** `item_read_model.store_id` (no `nameOf` lookup, no
   `recordDefaultStore`). If members later want in-trip routing to also update the planning default,
   add the `recordDefaultStore` call then. [Source: `ShoppingListReadModelProjector`
   `ItemAssignedToStore` case; Story 2.6 AC6.]

7. **Grouping fallback for items whose store is not a live trip store.** An item assigned (planning,
   2.6) to a store that is **not in this trip**, or to a store that has since been **archived**
   (1.8 E6 / 2.6 AC4), renders under **„Noch nicht zugeordnet"** in the trip (you are not visiting that
   store this run — reroute it to a trip store). The **client** does this bucketing: group by
   `item.storeId` **iff** that id is in the trip's store set **and** the store resolves in the
   household's active stores; otherwise → unassigned. Server returns the raw `storeIds` + items; no
   server-side archived-store filtering. [Source: Story 1.8 E6/AC5, 2.6 AC4; `list_detail_cubit.storeFor`.]

8. **`reroute`-not-`IN_TRIP` → 409 via a dedicated `ItemNotReroutableException`; distinct from the
   off-trip assign's 403.** `assignItemToStore`-when-not-`OPEN` maps to **403**
   (`ItemChangeNotPermittedApplicationException`), but an in-trip **reroute** failing because the list
   is no longer `IN_TRIP` is a **state conflict** (the trip completed concurrently, Story 3.4) — map it
   to **409**, mirroring 3.1's `TripNotStartable` (409). This needs a **new domain exception**
   `ItemNotReroutableException` (a single exception cannot map to both 403 and 409), paired with
   `ItemNotReroutableApplicationException` (→ 409). [Source: `AssignItemToStoreHandler`
   (403 mapping); `StartTripHandler` (409 mapping).]

9. **Carry the Epic-2 retro action items into this story (retro §6, still open).** DoD, not
   review-catch: **(1)** optimistic-state — reroute and add-store must optimistically reflect their
   server-visible effect (the item moves groups; the new store's group appears), and inline-created
   stores merge into the trip + selection (Action 1); **(2)** an **error-advice contract test** for
   every new endpoint (bad input/domain exceptions → 4xx) + the extended DoD (a11y labels, no dead
   code/strings, fail-fast guards) as a pre-review checklist (Actions 2/3); **(3)** the
   `isSubmitting`/re-entrancy + spent-`CommandIntent` guards on every new client command path from the
   first pass (Action 3); **(4)** **both** new read models (`trip_store_read_model`, the
   `active_trip_id` column) ship with a **two-household isolation + replay-idempotency** test in the
   same change (Action 4); **(5)** the `AddStoreToTripHandler` reuses the established online
   load-then-append + `ConcurrencyConflictException` handling (Action 6). [Source:
   `epic-2-retro-2026-08-28.md` §6; sprint-status open action items.]

## Tasks / Subtasks

> Follow TDD (CLAUDE.md §6): failing test first at the lowest level that proves the behaviour, then
> the simplest code to pass. Keep the domain pure (AD-1). **Mirror the cited existing file for every
> new class** — the event, aggregate method, command→handler, read-model/projector, query,
> controller, and Flutter patterns are all established (Stories 2.6, 3.1); do not invent new ones.
> This story lands the **trip read side** (deferred from 3.1, Cl. 2), the **first in-trip mutations**,
> and **SGART's first trip screen**. Baseline: backend **455**, Flutter **439**.

### Backend — domain: the two new events + the two aggregate mutations (AC2, AC3, Cl. 1/5)

- [x] **Task 1: `ItemRerouted` event** — package `collaboration.domain.event` (mirror
      `ItemAssignedToStore` exactly)
  - [x] `record ItemRerouted(EventId eventId, HouseholdId householdId, ShoppingListId listId, ItemId
        itemId, StoreId storeId)` — `requireNonNull` all. Javadoc: the **in-trip** re-routing of an
        item to a store, raised on the **`list-{id}`** stream by `ShoppingList.rerouteItem` while the
        list is `IN_TRIP` (Cl. 1); distinct event from `ItemAssignedToStore` (planning-time, `OPEN`)
        so the write-side gate is unambiguous, but the **read side converges** on the same
        `item_read_model.store_id`. No personal data / no *who* (AD-5/AD-6, mirrors `ItemAssignedToStore`).
- [x] **Task 2: `StoreAddedToTrip` event** — package `collaboration.domain.event` (mirror `TripStarted`)
  - [x] `record StoreAddedToTrip(EventId eventId, TripId tripId, HouseholdId householdId, StoreId
        storeId)` — `requireNonNull` all. Javadoc: a store added to an active trip (AC3), the trip's
        first in-trip mutation, on the **`trip-{id}`** stream; carries the store **by id** (AR2). No
        personal data / no *who*.
- [x] **Task 3: `ShoppingList.rerouteItem(...)` + `apply(ItemRerouted)` + `ItemNotReroutableException`**
      (AC2, AC5, Cl. 1/8)
  - [x] `public void rerouteItem(ItemId itemId, StoreId storeId, CommandId commandId)`:
        `requireNonNull` all; **`requireInTrip()`** (new private helper: `if (status != ListStatus.IN_TRIP)
        throw new ItemNotReroutableException("Items may only be rerouted during a trip, list is " +
        status)`); unknown item → `ItemNotFoundException`; **same-store reroute is a convergent no-op**
        (`if (storeId.equals(existing.assignedStore())) return;` — mirrors `assignItemToStore`);
        else `raise(new ItemRerouted(EventId.generate(), householdId, listId, itemId, storeId))`.
        Javadoc mirrors `assignItemToStore` but for the `IN_TRIP` phase; does **not** validate the
        store exists or is in the trip (Cl. 5, AD-3).
  - [x] `apply(ItemRerouted rerouted)` — fold `assignedStore` exactly like the `ItemAssignedToStore`
        case (null-tolerant on a reordered/repaired stream; extract a shared private helper
        `assignStore(itemId, storeId)` if it keeps both cases DRY without obscuring them). Register in
        the `apply(...)` switch.
  - [x] `ItemNotReroutableException` — `collaboration.domain.exception` (mirror
        `TripNotStartableException`): runtime exception; Javadoc: the list is not `IN_TRIP`, so no item
        may be rerouted (AC2/AC5, a state conflict → 409).
  - [x] Extend `ShoppingListTest` (fast, pure): `rerouteItem_onAnInTripList_raisesItemRerouted_andFolds`
        (rehydrate `[Created, ItemAdded, TripStartedForList]`, reroute → event + fold);
        `rerouteItem_toTheSameStore_isAConvergentNoOp` (rehydrate with a prior `ItemRerouted`/`ItemAssignedToStore`,
        re-route to the same store → raises nothing); `rerouteItem_onAnOpenList_throwsItemNotReroutable`
        (a not-yet-In-Trip list refuses reroute — the inverse of `assignItemToStore` needing `OPEN`);
        `rerouteItem_forAnUnknownItem_throwsItemNotFound`. Synthetic German data.
- [x] **Task 4: `ShoppingTrip.addStore(...)` + `apply(StoreAddedToTrip)` + `TripNotActiveException`**
      (AC3, AC5)
  - [x] `public void addStore(StoreId storeId, CommandId commandId)`: `requireNonNull` all; require
        **`ACTIVE`** (`if (status != TripStatus.ACTIVE) throw new TripNotActiveException(...)` — a new
        `collaboration.domain.exception`; `DONE` is unreachable until Story 3.4, so this guard is
        defensive, mirroring the `ListStatus.DONE` restraint); **already-in-trip is a convergent
        no-op** (`if (storeIds.contains(storeId)) return;`); else `raise(new StoreAddedToTrip(
        EventId.generate(), tripId, householdId, storeId))`. Javadoc: the trip's first in-trip mutation.
  - [x] `apply(StoreAddedToTrip added)`: append `added.storeId()` to a mutable copy of `storeIds`
        (fold into a fresh unmodifiable list, mirroring the `TripStarted` fold). Register in the
        `apply(...)` switch.
  - [x] Extend `ShoppingTripTest` (fast, pure): `addStore_onActiveTrip_raisesStoreAddedToTrip_andFolds`
        (rehydrate `[TripStarted]`, add a store → event + `storeIds` grows in order);
        `addStore_forAStoreAlreadyInTheTrip_isAConvergentNoOp` (raises nothing);
        `addStore_foldsInAddOrder` (rehydrate `[TripStarted, StoreAddedToTrip]` → the added store is
        last). Synthetic data.

### Backend — application: commands + handlers (AC2, AC3, AC5, Cl. 1/9)

- [x] **Task 5: `RerouteItem` + `RerouteItemHandler`** — package `application.command` (mirror
      `AssignItemToStore`/`AssignItemToStoreHandler`, DTO **beside** its handler per CLAUDE.md §8)
  - [x] `record RerouteItem(ShoppingListId listId, ItemId itemId, StoreId storeId, CommandId commandId,
        AggregateVersion basedOnVersion)` — `requireNonNull` all.
  - [x] `RerouteItemHandler.handle(keycloakUserId, rawHouseholdId, rawListId, rawItemId, rawStoreId,
        rawCommandId)`: translate ids (400 on malformed); `resolveMemberIdentity.resolve` (403); load
        `list-{id}` (404 empty / 404 cross-household, the `AssignItemToStoreHandler` pattern); use the
        **loaded** version (AD-8); `list.rerouteItem(...)` translating `ItemNotFoundException` →
        `ItemNotFoundApplicationException` (404) and **`ItemNotReroutableException` → a new
        `ItemNotReroutableApplicationException` (409)** at the seam; append only if
        `!list.uncommittedEvents().isEmpty()` (same-store no-op skips the append).
  - [x] `ItemNotReroutableApplicationException` — `application.exception` (→ **409**, mirror
        `TripNotStartableApplicationException`). Confirm `WriteErrorAdvice` maps it to 409 + a stable
        `code`; **add the mapping test** (Action 2 error-advice contract).
  - [x] Wire the bean in `CollaborationApplicationConfig` (mirror `assignItemToStoreHandler`).
  - [x] `RerouteItemHandlerTest` (in-memory `EventStore` + fake `ResolveMemberIdentity`, mirror
        `AssignItemToStoreHandlerTest`): reroutes (an `ItemRerouted` appended on `list-{id}`);
        non-member → 403; malformed id → 400; unknown list → 404; cross-household list → 404;
        **Open (not-In-Trip) list → 409**; unknown item → 404; same-store → no append.
- [x] **Task 6: `AddStoreToTrip` + `AddStoreToTripHandler`** — package `application.command` (mirror
      `StartTripHandler`'s load-then-append, but loading the **`ShoppingTrip`** aggregate)
  - [x] `record AddStoreToTrip(TripId tripId, StoreId storeId, CommandId commandId, AggregateVersion
        basedOnVersion)` — `requireNonNull` all.
  - [x] `AddStoreToTripHandler.handle(keycloakUserId, rawHouseholdId, rawTripId, rawStoreId,
        rawCommandId)`: translate ids (400); `resolveMemberIdentity.resolve` (403); load `trip-{id}`
        via `StreamId.forTrip(tripId)` → 404 empty / 404 cross-household (`!trip.householdId().equals(
        householdId)`, mirror `loadListOwnedBy`); use the **loaded** version (AD-8);
        `trip.addStore(storeId, commandId)`; append only if uncommitted events exist (no-op skips).
        New `TripNotFoundException` (`application.exception`, → 404, mirror `ShoppingListNotFoundException`).
  - [x] Wire the bean in `CollaborationApplicationConfig`.
  - [x] `AddStoreToTripHandlerTest` (in-memory `EventStore` + fake identity): adds a store (a
        `StoreAddedToTrip` appended on `trip-{id}`); non-member → 403; malformed id → 400; unknown
        trip → 404; cross-household trip → 404; store already in trip → no append.
  - [x] `CommandFieldTranslations` already has `toTripId`/`toStoreId`/`toItemId` (Stories 2.6/3.1) — no
        new translator; confirm and reuse.

### Backend — read side: trip stores + list `active_trip_id` + the reroute projection (AC1, AC4, AC5, Cl. 4/6/7)

- [x] **Task 7: `V9__trip_read_model.sql`** — the two additive changes (no `trip_read_model` header, Cl. 4)
  - [x] `ALTER TABLE shopping_list_read_model ADD COLUMN active_trip_id UUID NULL;` — comment: the list's
        currently-active trip (Story 3.2), set by the list projector on `TripStartedForList`, cleared on
        completion (Story 3.4); the 1:1 navigation key list→trip; a **reference** to the `ShoppingTrip`
        aggregate (AR2), not FK-constrained; no personal data (a trip id is household content, AC5).
  - [x] `CREATE TABLE trip_store_read_model (trip_id UUID NOT NULL, store_id UUID NOT NULL,
        sequence_number BIGSERIAL, PRIMARY KEY (trip_id, store_id));` + `CREATE INDEX
        idx_trip_store_read_model_trip ON trip_store_read_model (trip_id);` — comment: the trip's store
        set in add order (Story 3.2, projected from `TripStarted`/`StoreAddedToTrip` on `trip-{id}`);
        `sequence_number` gives the group order; no personal data. Confirm `NoPersistedPersonalDataTest`
        (AD-6) passes (no name/email column — grep-guard).
- [x] **Task 8: list read model `active_trip_id` + `ListOpenLists` navigation key** (AC4, Cl. 3/4)
  - [x] `ShoppingListReadModel.markInTrip` → change to **`markInTrip(ShoppingListId listId, TripId
        tripId)`**; `JdbcShoppingListReadModel`: `UPDATE shopping_list_read_model SET status = :status,
        active_trip_id = :tripId WHERE list_id = :listId`. `ShoppingListReadModelProjector`'s
        `TripStartedForList` case passes `started.tripId()`. (Story 3.4 will add a `markDone`/clear.)
  - [x] `ShoppingListView` + `ListOpenLists.ShoppingListSummary` gain a nullable **`activeTripId`
        (String)**; `ListOpenLists`'s SELECT returns `active_trip_id` (null for `OPEN`, set for
        `IN_TRIP`). `JdbcShoppingListReadModel.listsOf` maps it. Leave `ListDoneLists` unchanged.
  - [x] Extend tests: `ShoppingListReadModelProjectorTest` (Testcontainers) — `TripStartedForList`
        sets **both** `status=IN_TRIP` **and** `active_trip_id`; **two-household isolation** (a start
        in A never touches a B list) + **replay idempotency** (re-projection is a converging UPDATE),
        same change (Action 4). `ListOpenListsTest` — an In-Trip summary carries its `activeTripId`; an
        Open summary's is null.
- [x] **Task 9: `TripStoreReadModel` port + `JdbcTripStoreReadModel`** — `domain.readmodel` /
      `adapter.out` (mirror `ItemReadModel`/`JdbcItemReadModel`'s shape)
  - [x] Port: `void addStore(TripId tripId, StoreId storeId)` (idempotent upsert — `ON CONFLICT
        (trip_id, store_id) DO NOTHING`, so `sequence_number` stays stable across replay) and
        `List<StoreId> storesOf(TripId tripId)` (ordered by `sequence_number`). Javadoc: built solely
        by `ShoppingTripReadModelProjector` (AD-4).
- [x] **Task 10: `ShoppingTripReadModelProjector`** — NEW projector, `adapter.out` (mirror
      `ShoppingListReadModelProjector` **structurally**: `SmartLifecycle`, auto-start flag default off,
      `fromStart`, resubscribe, per-event log-and-skip)
  - [x] Subscription filter = the **`TRIP`** stream prefix (`StreamId.StreamType.TRIP.prefix() + "-"`),
        so it sees `trip-{id}` events only — **`fromStart` retroactively projects the 3.1-created
        `TripStarted` streams** (Cl. 2/4). `project(...)`: `case TripStarted started -> started.storeIds()
        .forEach(s -> tripStoreReadModel.addStore(started.tripId(), s))`; `case StoreAddedToTrip added ->
        tripStoreReadModel.addStore(added.tripId(), added.storeId())`; `default -> {}` (no other trip
        events in 3.2). Javadoc: the **third projector** (after household + list), the trip read side
        3.1 deferred (Cl. 2).
  - [x] Wire the bean + its config (mirror `CollaborationReadModelConfig`'s `shoppingListReadModelProjector`
        wiring incl. the shared `auto-start` flag). Add to the same `SmartLifecycle` startup set.
  - [x] `ShoppingTripReadModelProjectorTest` (Testcontainers): a `TripStarted` projects its stores into
        `trip_store_read_model` in order (proving the **retroactive `fromStart`** projection of a 3.1
        trip); `StoreAddedToTrip` appends a store; **replay idempotency** (re-projecting adds nothing);
        **two-household isolation** (two trips' store sets never mix). No PII.
- [x] **Task 11: list projector `ItemRerouted` case** (AC2, Cl. 1/6)
  - [x] `ShoppingListReadModelProjector.project`: add `case ItemRerouted rerouted ->
        itemReadModel.assignStore(rerouted.itemId(), rerouted.storeId())` — **reuses** the 2.6
        read-model write; **does not** call `recordDefaultStore` (Cl. 6 — reroute ≠ planning default).
  - [x] Extend `ShoppingListReadModelProjectorTest` (Testcontainers): `ItemRerouted` updates
        `item_read_model.store_id`; **the suggestion `default_store_id` is untouched** by a reroute
        (Cl. 6); replay-idempotent.
- [x] **Task 12: `TripView` query** — `application.query` (compose the ACL + three read models, mirror
      `ListItems`)
  - [x] `TripView.forList(String keycloakUserId, String rawHouseholdId, String rawListId)`: translate
        ids (400); `resolveMemberIdentity.resolve` (403); resolve the list **household-scoped**
        (`ListOpenLists`/read-model lookup) — if the list is **not `IN_TRIP`** (no `active_trip_id`),
        throw a **`TripNotFoundException` (404)** („no active trip for this list"); else read its
        `active_trip_id` (→ `tripId`), the trip's `storesOf(tripId)`, and the list's `itemsOf(householdId,
        listId)`. Return a `TripViewResult` record: `tripId`, `listId`, `storeIds` (ordered `List<String>`),
        `items` (reuse the `ListItems.ItemSummary` shape — id/name/note/amount/unit/storeId; **do not**
        re-derive it, extract/share it if needed). **Grouping is the client's job** (Cl. 7) — the query
        returns the flat store set + items. Plain `String`s in the result (adapter.in has no `..domain..`).
  - [x] Wire the bean in a read config (mirror `listItems`/`listOpenLists` wiring). `TripViewTest`
        (in-memory/fake read models or Testcontainers per the `ListItemsTest`/`ListOpenListsTest`
        precedent): returns the trip's ordered stores + the list's items; non-member → 403; a list with
        no active trip → 404; cross-household list → 404/empty (no leak).

### Backend — adapter.in: the trip read + mutation endpoints (AC1, AC2, AC3, AC5, Cl. 9)

- [x] **Task 13: extend `TripController` + `ItemController`** (mirror the existing method + DTO shapes)
  - [x] `TripController` (base `…/lists/{listId}/trips`): add **`@GetMapping("/active")`**
        `activeTrip(...)` → `TripView.forList(...)` → `200` `TripViewResponse` (tripId, listId,
        storeIds[], items[]); **`@PostMapping("/{tripId}/stores") @ResponseStatus(CREATED)`**
        `addStore(...)` → `AddStoreToTripHandler` (`record AddStoreToTripRequest(String storeId, String
        commandId)`). Identity from the JWT `sub` via `AuthenticatedCaller` (AR10/AD-5). Plain-`String`
        DTOs (no `..domain..`, ArchUnit).
  - [x] `ItemController` (base `…/lists/{listId}/items`): add **`@PostMapping("/{itemId}/reroute")`**
        `reroute(...)` → `RerouteItemHandler` (`record RerouteItemRequest(String storeId, String
        commandId)` — mirror `AssignStoreRequest`). Choose `200 OK` (a mutation returning no body,
        like `move`) — confirm against the existing `move` mapping and match it.
  - [x] Controller slice tests (MockMvc, mirror `ItemControllerTest`/`TripControllerTest`): `GET
        /active` → **200** with the grouped payload / **404** when the list has no active trip / **403**
        non-member; `POST /{tripId}/stores` → **201** / **400** malformed / **403** / **404** unknown
        trip / cross-household; `POST /{itemId}/reroute` → **200** / **400** / **403** / **404** unknown
        list/item / **409** not-In-Trip. These double as the **Action-2 error-advice contract** for the
        three new endpoints.
- [x] **Task 14: register `ItemRerouted` + `StoreAddedToTrip` in `DomainEventJsonCodec`** (mirror the
      3.1 `TripStarted` registration)
  - [x] Stable type tags (`ITEM_REROUTED_TYPE`, `STORE_ADDED_TO_TRIP_TYPE`); `typeTagFor` +
        `toJsonBytes` + `fromJsonBytes` payload records for both; round-trip through the id VOs. Extend
        `DomainEventJsonCodecTest`: both round-trip with a **stable type tag** (never a Java class name).
- [x] **Task 15: ArchUnit** — run `HexagonalArchitectureTest`; confirm the two new events + the two
      aggregate exceptions (`..domain..`), the two commands/handlers + app-exceptions + the `TripView`
      query (`..application..`), the new projector + JDBC read model (`..adapter.out..`), and the
      extended controllers (`..adapter.in..`, no `..domain..` import) need **no** rule change.

### Flutter — data layer (AC1, AC2, AC3, AC4)

- [x] **Task 16: extend `TripsApi` + `ItemsApi`; add the trip-view models** — `features/trips/data/`,
      `features/lists/data/` (mirror `items_api.dart`)
  - [x] `TripsApi.activeTrip(String householdId, String listId) → TripView` (`GET
        …/lists/{listId}/trips/active`) and `addStoreToTrip(String householdId, String listId, String
        tripId, {required String storeId, required String commandId})` (`POST
        …/lists/{listId}/trips/{tripId}/stores`). New `TripView` model (tripId, listId, `List<String>
        storeIds`, `List<Item>` items — **reuse the existing `Item` model** the list detail uses;
        `fromJson`). `ItemsApi.rerouteItem(String householdId, String listId, String itemId, {required
        String storeId, required String commandId})` (`POST …/lists/{listId}/items/{itemId}/reroute`).
        Extend `FakeTripsApi`/`FakeItemsApi` (record last calls; armable to throw).
  - [x] Tests: request shapes (paths + bodies) for `activeTrip`, `addStoreToTrip`, `rerouteItem`; a
        server error surfaces as the shared `AppException`.

### Flutter — the trip screen (AC1, AC2, AC3, AC5, Cl. 2/5/7/9)

- [x] **Task 17: `TripCubit` + `TripState`** — `features/trips/presentation/` (BLoC per screen; mirror
      `ListDetailCubit` for load + optimistic command patterns)
  - [x] Load: `TripsApi.activeTrip(householdId, listId)` **and** `StoresApi.listStores(householdId)`
        (for store name/chain resolution, mirror `list_detail_cubit`'s `_loadStores` + `storeFor`).
        Derive the grouped view: for each `tripStoreId` (in order) a group of items whose `storeId ==
        tripStoreId` **and** the store resolves in active stores; all other items (unassigned, non-trip
        store, archived store) → the „Noch nicht zugeordnet" bucket (Cl. 7). Empty trip-store groups
        still listed (Cl. 5).
  - [x] `reroute(String itemId, String storeId)`: dedicated `CommandIntent` keyed on `(itemId,
        storeId)` (freshen on change + after success); guard `ready && !isSubmitting`; **optimistically**
        move the item to the target group; `itemsApi.rerouteItem(...)`; revert + `actionError` on
        failure (Action 1/3).
  - [x] `addStoreToTrip(...)` for an **existing** store and `createAndAddStoreToTrip(name, chainId)` for
        **inline create** (client-orchestrated: `StoresApi.addStore` → `TripsApi.addStoreToTrip`),
        each with its own `CommandIntent` + `isSubmitting` guard; optimistically add the store's group
        + merge the new `StoreSummary` into `state.stores` (read-your-writes, mirror
        `list_detail_cubit.assignStore`'s store-merge). Revert on failure.
  - [x] `trip_cubit_test.dart`: load groups items by trip store + „Noch nicht zugeordnet" (incl. a
        non-trip-store and an unassigned item both bucketed unassigned, Cl. 7); `reroute`
        optimistically moves an item + reverts on failure + freshens the intent + the `isSubmitting`
        guard drops a second tap; `createAndAddStoreToTrip` adds the group + merges the store; an empty
        trip-store group renders.
- [x] **Task 18: `trip_screen.dart`** — the store-grouped view (UX-DR7, `screen-active-trip.html`)
  - [x] Header: „Aktiver Einkauf" kicker + the list name; **no progress bar** (Cl. 2). Body: one
        section per trip store (header = store name · chain via `storeFor`, + item count „N Artikel")
        then its item rows (name / quantity / note), then the **„Noch nicht zugeordnet"** section.
        Item rows are **status-read-only** (no checkbox — Cl. 2); each shows a **store chip / „Zuordnen"**
        (unassigned) or reroute affordance → the reroute picker (Task 19). A **„Geschäft hinzufügen"**
        action (peer/tonal, per the ⋯ „Geschäft hinzufügen / umleiten") → the store picker in
        add-to-trip mode. Empty-trip state (shouldn't happen — a trip has ≥1 store) and an empty-items
        „noch nichts zugeordnet" calm state. Keys: `trip-screen`, `trip-store-group-{storeId}`,
        `trip-unassigned-group`, `trip-item-{itemId}`, `trip-item-reroute-{itemId}`, `trip-add-store`.
  - [x] `trip_screen_test.dart` (widget, fakes): renders store groups + „Noch nicht zugeordnet";
        an item under its store; tapping reroute opens the picker; „Geschäft hinzufügen" opens the
        picker; **no checkbox / no progress bar / no „Einkauf abschließen"** present (Cl. 2 guard).
- [x] **Task 19: reroute / add-store picker** — reuse the 2.6 single-select picker scoped to the trip
      (extend, keep 2.6/3.1 intact, Cl. 5)
  - [x] Reroute: `showStorePickerSheet(...)` (the 2.6 single-select) **passed the trip's stores** (not
        all household stores) + the shared **`InlineCreateStoreRow`** (extracted in 3.1) whose success
        callback here **creates the store on the household, adds it to the trip, and returns it selected**
        (Cl. 5). Add-store (from „Geschäft hinzufügen"): the same picker to add an existing/new store to
        the trip without an item context. **Do not** duplicate `StoreChainMatcher`; **do not** regress
        the 2.6 (list-detail assign) or 3.1 (trip-start multi-select) paths.
  - [x] Widget test: the reroute picker lists only the trip's stores + „+ Neues Geschäft"; inline create
        adds the store to the trip and selects it; the 2.6 single-select + 3.1 multi-select paths still
        behave (regression).

### Flutter — navigation & the „Einkauf" tab (AC4, Cl. 3)

- [x] **Task 20: navigate to the trip screen from the list** (AC4)
  - [x] `ListDetailCubit.startTrip` already returns success — on success, **navigate** to the trip
        screen (`list_detail_page` pushes `TripScreen` with `householdId`/`listId`, re-providing
        `TripsApi`/`ItemsApi`/`StoresApi` on the route, mirror the 2.6 store-api re-provide). Keep the
        „Einkauf gestartet" confirmation. `lists_view` — an **„Im Einkauf" row tap** pushes `TripScreen`
        using the summary's `activeTripId` + `listId` (Open rows still open list detail).
  - [x] Tests: starting a trip navigates to the trip screen; tapping an In-Trip row opens it; tapping an
        Open row still opens list detail.
- [x] **Task 21: „Einkauf" tab = active-trips index** — `household_shell.dart` (AC4, Cl. 3)
  - [x] Replace `_ShoppingPlaceholder` with an **active-trips index**: a thin cubit/view reading
        `ShoppingListsApi` (the `ListOpenLists` „Offen" set) **filtered to `IN_TRIP`**; one row per
        In-Trip list (list name / „Liste N" + „Im Einkauf" + item count) → pushes `TripScreen`; a calm
        **empty state** („Noch kein Einkauf aktiv" + a hint to start one from a list) when none. Keyed on
        the active household id like the Listen tab. **Store count per row is deferred** (Cl. 3).
  - [x] `active_trips_view_test.dart`: shows one row per In-Trip list with its name + item count; a row
        tap navigates to the trip; the empty state renders when there are no In-Trip lists.
- [x] **Task 22: `ShoppingListSummary` carries `activeTripId`** — `features/lists/data/`
  - [x] Add the nullable `activeTripId` to the client list-summary model + `fromJson` (the backend now
        returns it, Task 8). Used by Task 20/21 for navigation. Existing lists tests updated for the
        new field (null for Open).
- [x] **Task 23: localization** — `l10n` (`app_de.arb` + `flutter gen-l10n`)
  - [x] `tripScreenTitle`/kicker („Aktiver Einkauf"), `tripUnassignedGroupLabel` („Noch nicht
        zugeordnet"), `tripItemAssignAction` („Zuordnen"), `tripItemRerouteAction` („Umleiten"),
        `tripAddStoreAction` („Geschäft hinzufügen"), `tripStoreItemCount` („{count} Artikel"),
        `tripStoreSelectionTitle` reuse, the active-trips index title + empty state
        (`shellTabShoppingActiveTitle`, `tripsIndexEmpty…`), and a11y labels/semantics for the reroute
        affordance + add-store + index rows (48px, UX-DR5). **Reuse** the 3.1/2.6 „+ Neues Geschäft" /
        picker / „Im Einkauf" strings — check before adding. No hard-coded user-facing strings (Action 2 DoD).

### Tests & green build (CLAUDE.md §6)

- [x] **Task 24: extended-DoD sweep (retro Actions 1/2/3/4/6)** — before review: reroute + add-store
      optimistically reflect their server-visible effect (item moves group; new store's group appears;
      inline store merged into the trip + selection); a11y labels on every new interactive element; no
      dead code / no hard-coded strings; fail-fast guards + the `isSubmitting` re-entrancy + spent-intent
      guards on every new command path; the error-advice mapping tests for the three new endpoints exist;
      **both** new read models (`trip_store_read_model`, `active_trip_id`) have an isolation + replay test.
- [x] **Task 25: full-suite green** — backend `./gradlew test` (incl. `HexagonalArchitectureTest`
      ArchUnit **and** the Testcontainers projector tests: `ShoppingListReadModelProjectorTest` +
      the new `ShoppingTripReadModelProjectorTest`) **and** Flutter `flutter analyze` + `flutter test`,
      both **in full** (not per-file), per CLAUDE.md §6. State which suite ran and the counts (baseline:
      backend **455**, Flutter **439**).

### Review Findings

_Code review 2026-08-29 (Opus 4.8, 3 parallel layers: Blind Hunter, Edge Case Hunter, Acceptance
Auditor). 6 patch, 3 defer, 4 dismissed as noise. (1 finding began as decision-needed — the „Liste N"
labeling approach — resolved by Timo to compute the true global ordinal, now a patch.)_

- [x] [Review][Patch] Active-trips „Liste N" ordinal dropped for unnamed In-Trip lists
      [app/lib/features/trips/presentation/active_trips_view.dart:70,88 + active_trips_cubit.dart:26] —
      the Einkauf tab labels an unnamed In-Trip list with `listsArchiveUnnamedFallback` = bare „Liste"
      (no number), while the overview derives „Liste N" via `listsDefaultName(index+1)` over the full
      open-lists sequence (OPEN + IN_TRIP). AC4 / Cl. 3 calls for „Liste N". **Resolution (Timo):**
      compute the true global ordinal — preserve each In-Trip list's index in the unfiltered
      `listOpenLists` result through the cubit/state, then render `listsDefaultName(ordinal)` in the
      view (row name + `TripScreen.push` title), matching the overview exactly.
- [x] [Review][Patch] Dead production code: `TripCubit.createAndAddStoreToTrip` is never wired to any
      widget [app/lib/features/trips/presentation/trip_cubit.dart:149] — the ~40-line create-then-add
      orchestration is referenced only by its own test; the real inline-create flow creates the store
      inside the picker's `InlineCreateStoreRow` and adds it via `onInlineStoreCreated → addStoreToTrip`.
      Redundant given the sheet owns the household create. Violates CLAUDE.md §1 no-dead-code / YAGNI
      (and contradicts Task 24's "no dead code" claim). Delete the method + its unit test.
- [x] [Review][Patch] `_openAddStorePicker` fires `AddStoreToTrip` twice for an inline-created store
      [app/lib/features/trips/presentation/trip_screen.dart:150-166] — the `onInlineStoreCreated` hook
      adds the store to the trip, then the sheet pops the same store as `selected` and the
      `if (selected != null) cubit.addStoreToTrip(selected)` adds it again. Second POST is a server-side
      convergent no-op but a redundant round-trip + `isSubmitting` flash; uncovered by tests. Drop the
      hook on the add-store picker (the returned selection already adds once).
- [x] [Review][Patch] Reroute after a failed trip-side add strands the item as unassigned and clears the
      add error [app/lib/features/trips/presentation/trip_screen.dart:134-148 + trip_cubit.dart:111-141] —
      in `_openReroutePicker`, if the inline-create's `addStoreToTrip` fails, `addStoreToTrip` reverts
      `storeIds` (no rethrow) yet the sheet still pops the store, so `reroute` routes the item to a store
      no longer in the trip (→ „Noch nicht zugeordnet") and its `clearActionError` wipes the add-store
      error. Guard: only reroute when the target actually landed in `state.storeIds`.
- [x] [Review][Patch] `TripView.fromJson` does not guard non-object `items` entries [app/lib/features/trips/data/trips_api.dart:24-38]
      — it validates `storeIds` entries are `String` but only checks `items is! List`, then does
      `entry as Map<String,dynamic>`, so a malformed item entry throws a raw `TypeError` instead of the
      mapped `trips.malformedResponse` contract. Add `|| items.any((e) => e is! Map)` to the guard.
- [x] [Review][Patch] No controller/mapping test for `TripNotActiveApplicationException` (409)
      [backend/.../adapter/in/WriteErrorAdvice.java:162] — the mapping exists and the domain guard is
      tested, but no MockMvc test asserts the 409 (defensive: `DONE` is unreachable until Story 3.4).
      Minor gap vs Cl. 9 Action 2 ("error-advice contract test for every new endpoint"); add the mapping test.
- [x] [Review][Defer] Cross-projector transient: `GET /active` can return a valid `tripId` with an empty
      store set [backend/.../application/query/TripView.java:61-71; app trip_cubit bootstrap + trip_state.dart:82-94]
      — `active_trip_id` (list projector) and `trip_store_read_model` (separate new trip projector) are
      independently eventually-consistent, so right after start (and while the client's stores are still
      loading) every item renders as „Noch nicht zugeordnet" with empty groups, no client handling.
      Deferred, inherent to the CQRS/ES model — a client retry/settle affordance is a follow-up.
- [x] [Review][Defer] Add-store endpoint ignores the `{listId}` path segment
      [backend/.../adapter/in/TripController.java:81-93] — `POST .../lists/{listId}/trips/{tripId}/stores`
      never validates the trip belongs to `listId` (handler resolves via household + tripId only),
      unlike the sibling list-scoped `GET .../active`. URL contract looseness; deferred (membership is
      the real gate).
- [x] [Review][Defer] `ShoppingTripReadModelProjector` replays `fromStart` on every start and every
      reconnect [backend/.../adapter/out/ShoppingTripReadModelProjector.java:141] — no checkpoint/stored
      position; O(all trip events) per restart/resubscribe. Correct (idempotent upserts) and mirrors the
      existing list projector, so a repo-wide scalability item, deferred.

## Dev Notes

### What is (and isn't) in this story — read first

3.2 is a **domain-heavy full vertical slice** that lands **three things 3.1 deliberately deferred**:
the **trip read side** (`fromStart` projector that retroactively projects 3.1's `TripStarted` streams,
Cl. 2/4), the **first in-trip mutations** (reroute an item; add a store to the trip), and **SGART's
first trip screen** (store-grouped, „Noch nicht zugeordnet", reroute, spontaneous add-store) with its
**navigation** (start → trip screen; In-Trip row → trip screen; „Einkauf" tab = active-trips index).

It **deliberately does not** build (Cl. 2): **check-off / uncheck / postpone** or the **„X von Y
erledigt" progress bar** (Story 3.3 — the same `IN_TRIP`-gated pattern on `ShoppingList` this story
establishes with `rerouteItem`); **trip completion** / „Einkauf abschließen" / `TripStatus.DONE` /
list → Done (Story 3.4 — which also clears `active_trip_id` and lands the deferred cached-Done-archive
invalidation); **print/share** (Story 3.5); **live-sync** of trip changes across devices (Epic 4 SSE —
the actor sees their own reroute/add optimistically; peers refetch on open).

The crux ideas:

- **In-trip reroute is a new `IN_TRIP`-gated event on the `ShoppingList` (Cl. 1).** The item's store is
  one concept, owned by the list since 2.6. `rerouteItem` is `requireInTrip()` (the inverse of 2.6's
  `requireOpen()` assign), raising `ItemRerouted` on `list-{id}`; the projector folds it to the **same**
  `item_read_model.store_id`. One source of truth for item→store, and **Story 3.3's** check-off/postpone
  is the identical pattern. **Not** a trip-owned `StoreAssignment` (two sources of truth).
- **The trip read side is minimal + `fromStart` (Cl. 4).** No `trip_read_model` header — the list is the
  1:1 anchor of an active trip, so `active_trip_id` on `shopping_list_read_model` (set by the **list**
  projector, which already handles `TripStartedForList`) is the navigation key, and a new
  `trip_store_read_model` (a **new** `trip-`-prefix projector, `fromStart`) holds just the store set.
  The grouped view is a **list-scoped** query joining the list's items with the trip's stores.
- **Two aggregates, two streams, two projectors, cleanly separated.** `ItemRerouted` is a
  `ShoppingList` event on `list-{id}` → the **list** projector (reuses `assignStore`). `StoreAddedToTrip`
  is a `ShoppingTrip` event on `trip-{id}` → the **new trip** projector. No cross-talk.

Flow (view + reroute + add-store):

```
member starts a trip (3.1)  ──▶ push Trip screen  (or taps an „Im Einkauf" row / the „Einkauf" tab)
  Trip screen loads: GET …/lists/{listId}/trips/active   (TripView: tripId, storeIds[], items[])
                     + GET …/stores  (StoresApi, for store names/chains)
     → group items by trip store; unassigned/non-trip/archived → „Noch nicht zugeordnet" (Cl. 7)

reroute an item  (tap store chip → picker over the trip's stores + „+ Neues Geschäft")
  → POST …/lists/{listId}/items/{itemId}/reroute {storeId, commandId}   (optimistic move)
  → RerouteItemHandler: load list-{id}, requireInTrip()
       → ShoppingList.rerouteItem → ItemRerouted on list-{id}
  KurrentDB $all ──filter list-*──▶ ShoppingListReadModelProjector.project(ItemRerouted)
       → itemReadModel.assignStore(itemId, storeId)   (item_read_model.store_id; NOT the suggestion, Cl. 6)

add a store to the trip  („Geschäft hinzufügen" / „+ Neues Geschäft")
  → (inline) POST …/stores {…}  (AddStore on household-{id}, Story 1.8)   ── client-orchestrated
  → POST …/lists/{listId}/trips/{tripId}/stores {storeId, commandId}
  → AddStoreToTripHandler: load trip-{id}, ShoppingTrip.addStore → StoreAddedToTrip on trip-{id}
  KurrentDB $all ──filter trip-*──▶ ShoppingTripReadModelProjector.project(StoreAddedToTrip)
       → tripStoreReadModel.addStore(tripId, storeId)   (trip_store_read_model)
```

### Architecture patterns & constraints

- **AD-10 in-trip mutation on the owning aggregate + AD-3 reference-by-id.** Reroute mutates the
  `Item` inside its `ShoppingList` root (never from the trip); add-store mutates the `ShoppingTrip`
  root. Neither validates the other aggregate (the list doesn't know the trip's stores; the trip
  references its stores by id). [#AD-3/#AD-10]
- **AD-8 online load-then-append.** Both handlers read the target stream, use the loaded version as the
  expected version, and append; the `AddStoreToTripHandler` mirrors `StartTripHandler` (loading the
  trip). Same-store/already-present are convergent no-ops that skip the append. [#AD-8]
- **AD-4 CQRS, projection-only, `fromStart`.** `active_trip_id`, `trip_store_read_model`, and the
  reroute's `store_id` update are written only by projectors. The new trip projector's `fromStart`
  catch-up is what **retroactively projects the 3.1-created trips** (Cl. 2/4) — writing a `trip-{id}`
  stream that nothing projected in 3.1 was correct and expected. [#AD-4]
- **AD-5/AD-6 no PII, no audit.** `ItemRerouted`/`StoreAddedToTrip` carry ids only — no `MemberId`, no
  *who* (mirrors `ItemAssignedToStore`/`ItemAdded`); `MemberId` is used only at the handler seam. Trip
  read models are queried by `household_id`. [#AD-5/#AD-6]
- **AD-11 ubiquitous language.** `rerouteItem` / `ItemRerouted` / „Umleiten"; `addStore` (trip) /
  `StoreAddedToTrip` / „Geschäft hinzufügen"; „Noch nicht zugeordnet" (unassigned); „Aktiver Einkauf"
  (active trip). Reroute ≠ Postpone (3.3). No abbreviations. [#AD-11]
- **Eventual consistency (AR3/NFR9).** The reroute/add-store projections lag the response — the trip
  screen updates optimistically (Cl. 9) and reverts on failure.

### The read-side wiring (Cl. 4) — do not miss a spot

Making the store-grouped view work touches: (1) `V9` — `active_trip_id` column + `trip_store_read_model`;
(2) the **list** projector's `TripStartedForList` case now also sets `active_trip_id` (extend
`markInTrip` to take `tripId`); (3) `ListOpenLists`/`ShoppingListView` carry `activeTripId` (nav key);
(4) a **new** `trip-`-prefix projector + `TripStoreReadModel` for the store set; (5) the list projector's
**new** `ItemRerouted` case (reuse `assignStore`, skip the suggestion write, Cl. 6); (6) the `TripView`
query composing list + items + trip stores. The client resolves store **names** from the household store
list (`StoresApi` + `storeFor`, the 2.6 pattern) — the query returns store **ids** only.

### Source tree — mirror these exact files

| New/changed | Mirror | Path |
| --- | --- | --- |
| `ItemRerouted` (event) | `ItemAssignedToStore` | `collaboration/domain/event/` |
| `StoreAddedToTrip` (event) | `TripStarted` | `collaboration/domain/event/` |
| `ShoppingList.rerouteItem` + `apply(ItemRerouted)` + `requireInTrip` | (extend) `assignItemToStore`/`requireOpen` | `collaboration/domain/` |
| `ShoppingTrip.addStore` + `apply(StoreAddedToTrip)` | (extend) | `collaboration/domain/` |
| `ItemNotReroutableException`, `TripNotActiveException` | `TripNotStartableException` | `collaboration/domain/exception/` |
| `RerouteItem` + `RerouteItemHandler` | `AssignItemToStore` + `AssignItemToStoreHandler` | `collaboration/application/command/` |
| `AddStoreToTrip` + `AddStoreToTripHandler` | `StartTrip` + `StartTripHandler` (loads the trip) | `collaboration/application/command/` |
| `ItemNotReroutableApplicationException` (409), `TripNotFoundException` (404) | `TripNotStartableApplicationException`, `ShoppingListNotFoundException` | `collaboration/application/exception/` |
| `TripView` (query) | `ListItems` (composes ACL + read models) | `collaboration/application/query/` |
| `TripStoreReadModel` / `JdbcTripStoreReadModel` | `ItemReadModel` / `JdbcItemReadModel` | `collaboration/domain/readmodel/`, `adapter/out/` |
| `ShoppingTripReadModelProjector` (`trip-` prefix, `fromStart`) | `ShoppingListReadModelProjector` | `collaboration/adapter/out/` |
| `ShoppingListReadModel.markInTrip(listId, tripId)` + `ItemRerouted` case + `activeTripId` | (extend) | `collaboration/domain/readmodel/`, `adapter/out/`, `application/query/ListOpenLists` |
| `V9__trip_read_model.sql` | `V8__item_store_assignment.sql` | `resources/db/migration/` |
| `TripController` (GET `/active`, POST `/{tripId}/stores`) | (extend) | `collaboration/adapter/in/` |
| `ItemController` (POST `/{itemId}/reroute`) | (extend, mirror `move`/`store`) | `collaboration/adapter/in/` |
| `DomainEventJsonCodec` (2 events) | (extend) | `collaboration/adapter/out/` |
| `TripsApi` (`activeTrip`, `addStoreToTrip`) + `TripView` model | (extend) `items_api.dart` | `app/lib/features/trips/data/` |
| `ItemsApi.rerouteItem` | (extend) | `app/lib/features/lists/data/` |
| `TripCubit` / `TripState` / `trip_screen.dart` | `ListDetailCubit` / `list_detail_page.dart` | `app/lib/features/trips/presentation/` |
| reroute/add-store store picker | (extend) `store_picker_sheet.dart` + `InlineCreateStoreRow` | `app/lib/features/stores/presentation/` |
| active-trips index + „Einkauf" tab | (extend) `household_shell.dart` | `app/lib/features/households/presentation/` |
| In-Trip row → trip nav; start → trip nav | (extend) `lists_view.dart`, `list_detail_page.dart` | `app/lib/features/lists/presentation/` |
| `ShoppingListSummary.activeTripId` | (extend) | `app/lib/features/lists/data/` |

### Package structure (CLAUDE.md §8)

New backend classes drop into the **existing** intent subpackages (`domain.event`, `domain.exception`,
`application.command` with the DTO beside its handler, `application.exception`, `application.query`,
`adapter.out`, `adapter.in`). `ShoppingTrip.addStore` extends the existing aggregate; the new trip
projector + JDBC read model sit beside the list ones in `adapter.out`. No **new** controller (the trip
read/mutation endpoints extend the 3.1 `TripController`; reroute extends `ItemController`) and no **new**
Flutter feature package (the trip **presentation** lands in the existing `features/trips/`). No ArchUnit
rule change (rules match `..domain..`/`..application..`; the controllers import no `..domain..`).

### Testing standards

- **Domain first (fast, pure, no infra):** `ShoppingListTest` (reroute raises + folds; In-Trip-gated;
  same-store no-op; unknown item); `ShoppingTripTest` (addStore raises + folds in order; already-present
  no-op).
- **Handlers (in-memory `EventStore` + fake `ResolveMemberIdentity`):** `RerouteItemHandlerTest`
  (403/400/404/404-cross-hh/409-not-In-Trip/no-op); `AddStoreToTripHandlerTest`
  (403/400/404/404-cross-hh/no-op).
- **Projectors/read models (Testcontainers):** the **new** `ShoppingTripReadModelProjectorTest`
  (`TripStarted` projects stores `fromStart` = retroactive 3.1 projection; `StoreAddedToTrip` appends;
  **isolation + replay idempotency** in the same change, Action 4); `ShoppingListReadModelProjectorTest`
  (`TripStartedForList` sets `active_trip_id`; `ItemRerouted` updates `store_id` and **leaves the
  suggestion `default_store_id` untouched**, Cl. 6; isolation + idempotency). `ListOpenListsTest`
  (`activeTripId` present for In-Trip, null for Open). `TripViewTest` (grouped payload; 403; 404 no-trip;
  cross-household).
- **Controller (MockMvc):** `TripControllerTest` (GET `/active` 200/404/403; POST `/{tripId}/stores`
  201/400/403/404/409-ish) + `ItemControllerTest` (POST `/{itemId}/reroute` 200/400/403/404/409) —
  double as the Action-2 error-advice contract. `DomainEventJsonCodecTest` (both events round-trip,
  stable tags).
- **Flutter (fakes only, no network):** `trips_api`/`items_api` request shapes; `TripCubit` (grouping incl.
  the Cl. 7 fallback, optimistic reroute + revert + intent freshen + `isSubmitting`, inline
  create-and-add-to-trip merge); `trip_screen` (groups + „Noch nicht zugeordnet"; **no checkbox/progress/
  abschließen**, Cl. 2 guard); the reroute picker (trip-scoped + inline create; 2.6/3.1 regression); the
  active-trips index (rows + empty state); navigation (start → trip; In-Trip row → trip; Open row → detail).
- **DSGVO:** synthetic German data only; explicit no-PII stance on the two events (AC5).
- **Green build = full suite** for both modules; state which ran and the counts (baseline backend 455 /
  Flutter 439).

### Deferred / do-not-build (premature-value discipline)

- **Check-off / uncheck / postpone + the „X von Y erledigt" progress bar** → **Story 3.3** (the same
  `IN_TRIP`-gated `ShoppingList` command pattern this story establishes with `rerouteItem`). Cl. 2.
- **Trip completion (`TripStatus.DONE`, list → Done + clear `active_trip_id`, „Einkauf abschließen",
  leftover review)** → **Story 3.4**, which also lands the deferred **cached Done-archive invalidation**
  and the reachable **Done-rename** test. Cl. 2.
- **Print / share the grouped list** → **Story 3.5**. Cl. 2.
- **Live-sync of trip/reroute/add-store across devices** → **Epic 4** (SSE). MVP: the actor sees their own
  changes optimistically; peers refetch on open.
- **Reroute rewriting the article's planning `default_store_id`** — reroute writes only
  `item_read_model.store_id` (Cl. 6); add the `recordDefaultStore` call later if wanted.
- **Store-count-per-row on the „Einkauf" active-trips index** („2 Geschäfte") — the index shows name +
  item count; store count shows on the trip screen. Cl. 3.
- **A `trip_read_model` header table** — not needed while the list is the 1:1 active-trip anchor (Cl. 4);
  add it if a trip ever needs household-scoped querying independent of its list.
- **Archived-trip-store name resolution** — a store in the trip that is later archived won't resolve a
  name via the active-stores list; such an item falls to „Noch nicht zugeordnet" (Cl. 7). A dedicated
  „(archiviertes Geschäft)" label is polish; revisit with Story 3.4/3.5 if it bites.

### Project Structure Notes

- The grouped-view read is **list-scoped** (`GET …/lists/{listId}/trips/active`) — the client always
  holds the `listId`, and the list is the 1:1 anchor of its active trip (Cl. 4). Add-store is
  `POST …/lists/{listId}/trips/{tripId}/stores` (extends the 3.1 `TripController` base); reroute is
  `POST …/lists/{listId}/items/{itemId}/reroute` (extends `ItemController`) — both reuse existing
  controllers, no new one.
- `V9` is **additive** (one `ALTER` + one `CREATE TABLE`); no backfill, no rewrite of V5–V8. Existing
  rows read as `active_trip_id = NULL`.
- The new `ShoppingTripReadModelProjector` is a **third** `SmartLifecycle` projector on its own `trip-`
  subscription (default-off auto-start flag), alongside the household + list projectors — same structure,
  no shared subscription (distinct prefixes).

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story-3.2] — user story + BDD ACs (grouped view, assign/reroute, spontaneous store).
- [Source: _bmad-output/planning-artifacts/epics.md#Story-3.3/3.4/3.5] — check-off/postpone, completion, print this story defers to.
- [Source: ARCHITECTURE-SPINE.md #AD-3/#AD-4/#AD-8/#AD-10/#AD-11 + #AR2] — reference-by-id, projection-only + fromStart, load-then-append, in-trip mutation on the owning aggregate, no PII, ubiquitous language.
- [Source: ux-designs/ux-sgart-2026-08-20/EXPERIENCE.md §3 „Active trip"; DESIGN.md UX-DR7/UX-DR17/UX-DR22; .working/screen-active-trip.html] — store groups + „Noch nicht zugeordnet", per-item „Zuordnen"/reroute, ⋯ „Geschäft hinzufügen / umleiten", list-is-hero, no sum, non-sticky actions.
- [Source: backend `ShoppingList.java` (assignItemToStore/requireOpen/apply/ItemAssignedToStore case), `ShoppingTrip.java`, `ItemAssignedToStore.java`, `TripStarted.java`, `AssignItemToStoreHandler.java`, `StartTripHandler.java`, `ItemController.java`, `TripController.java`, `ShoppingListReadModelProjector.java`, `JdbcShoppingListReadModel.java`, `JdbcItemReadModel.java`, `ItemReadModel.java`, `ListItems.java`, `ListOpenLists.java`, `DomainEventJsonCodec.java`, `CommandFieldTranslations.java`, `V8__item_store_assignment.sql`] — every pattern to mirror.
- [Source: app `features/trips/data/trips_api.dart`, `features/stores/presentation/store_picker_sheet.dart` (+ `InlineCreateStoreRow`), `features/lists/presentation/list_detail/*` (storeFor, assignStore optimistic + store-merge, startTrip), `list_overview/lists_view.dart`, `households/presentation/household_shell.dart` (Einkauf placeholder), `shared/commands/command_intent.dart`] — the client patterns to mirror.
- [Source: _bmad-output/implementation-artifacts/3-1-…md] — the ShoppingTrip aggregate + TripStarted + list IN_TRIP + the deferred trip read side (Cl. 2) + the 3.1 store picker → multi-select refactor (`InlineCreateStoreRow`).
- [Source: _bmad-output/implementation-artifacts/2-6-…md] — item→store assignment on the list (`item_read_model.store_id`), the reusable single-select store picker.
- [Source: _bmad-output/implementation-artifacts/deferred-work.md] — `active_trip_id` on the list read model → 3.2 if navigation needs it; the reusable store picker reused for in-trip reroute; AC3's trip store-grouping consumer is Epic 3.
- [Source: _bmad-output/implementation-artifacts/epic-2-retro-2026-08-28.md §6 + sprint-status open action items] — carried actions 1/2/3/4/6 baked into Cl. 9 + the DoD tasks.
- [Source: CLAUDE.md §1–§8] — Clean Code, naming, DDD, CQRS, DSGVO, testing, package structure.

## Dev Agent Record

### Agent Model Used

Sonnet 5 (dev-story)

### Debug Log References

None — no blocking failures. One pre-existing-test fix needed after the `ShoppingListReadModel.markInTrip`
signature change (added `tripId`): the three Story 3.1 `ShoppingListReadModelProjectorTest` cases
asserting `TripStartedForList` behavior expected `activeTripId: null` and needed updating to expect the
started trip's id instead (they now capture the generated `TripId` and assert against it).

### Completion Notes List

- All 25 tasks implemented as planned, full vertical slice (backend domain → application → read side →
  adapter.in → Flutter data/presentation/navigation), TDD throughout.
- Backend: `ItemRerouted` (list-{id}) + `StoreAddedToTrip` (trip-{id}) events; `ShoppingList.rerouteItem`
  (`requireInTrip`, convergent same-store no-op, folds via a shared `assignStore` helper extracted from
  the existing `ItemAssignedToStore` case — Cl. 1's one-source-of-truth); `ShoppingTrip.addStore`
  (`requireActive`, convergent already-present no-op); `RerouteItemHandler`/`AddStoreToTripHandler`
  (mirroring `AssignItemToStoreHandler`/`StartTripHandler`); `V9` migration (`active_trip_id` column +
  `trip_store_read_model` table, no header table per Cl. 4); the new `ShoppingTripReadModelProjector`
  (third projector, `trip-` prefix, `fromStart` — proven to retroactively project a 3.1-created
  `TripStarted` stream in `ShoppingTripReadModelProjectorTest`); `TripView` query composing the list's
  `active_trip_id` + the trip's stores + the list's items (grouping is deliberately the client's job,
  Cl. 7); `TripController` GET `/active` (200) + POST `/{tripId}/stores` (201) and `ItemController` POST
  `/{itemId}/reroute` (200, per the story's explicit status list) extended onto the existing controllers
  (no new controller); both new events registered in `DomainEventJsonCodec` with stable type tags.
- Flutter: `TripsApi.activeTrip`/`addStoreToTrip` + `TripView` model, `ItemsApi.rerouteItem`; `TripCubit`/
  `TripState` (grouping computed as `TripState.groups`/`unassignedItems` getters — one source of truth for
  the Cl. 7 bucketing rule — with optimistic reroute/add-store/inline-create-and-add, `CommandIntent` +
  `isSubmitting` re-entrancy guards on every command path); `trip_screen.dart` (store groups + „Noch
  nicht zugeordnet", no checkbox/progress/„abschließen" per Cl. 2); the reroute/add-store picker reuses
  the 2.6 `showStorePickerSheet` unchanged for existing callers, extended with an optional
  `onInlineStoreCreated` hook so the trip's inline-create path can also call `AddStoreToTrip` before the
  sheet pops (Cl. 5, regression-tested against the 2.6/3.1 paths); navigation (start → trip screen,
  „Im Einkauf" row → trip screen, „Einkauf" tab → new `ActiveTripsCubit`/`ActiveTripsView` reusing
  `ShoppingListsApi.listOpenLists` filtered to `IN_TRIP`, no new endpoint per Cl. 4); `ShoppingListSummary`
  gained `activeTripId`; the dead `_ShoppingPlaceholder` widget and its now-unused
  `shellTabShoppingPlaceholder` string were removed rather than left alongside the real index.
- DoD sweep (Task 24 / retro Actions 1–4/6): optimistic reroute/add-store/inline-create all verified in
  cubit + widget tests; `isSubmitting` re-entrancy guard tested (`reroute_ignoresAReentrantCallWhileSubmitting`);
  error-advice mapping tests exist for all three new endpoints (400/403/404/409) in
  `TripControllerTest`/`ItemControllerTest`, doubling as the contract coverage; both new read models
  (`trip_store_read_model`, `active_trip_id`) carry isolation + replay-idempotency tests
  (`ShoppingTripReadModelProjectorTest.twoTripsStoreSetsNeverMix`/`reProjectingTheSameEventsIsIdempotent`;
  `ShoppingListReadModelProjectorTest.aTripStartedForListNeverFlipsAListInAnotherHousehold`/
  `reProjectingTripStartedForListIsIdempotent` plus the new `ItemRerouted` isolation/idempotency pair);
  `AddStoreToTripHandler` mirrors the established online load-then-append pattern.
- Green build: backend `./gradlew test` (incl. `HexagonalArchitectureTest` ArchUnit and both Testcontainers
  projector suites) — **507 tests, 0 failures, 0 errors** (baseline 455). Flutter `flutter analyze`
  (0 issues) + `flutter test` — **462 tests, 0 failures** (baseline 439).

### File List

**Backend — new:**
- `backend/src/main/java/de/sgart/collaboration/domain/event/ItemRerouted.java`
- `backend/src/main/java/de/sgart/collaboration/domain/event/StoreAddedToTrip.java`
- `backend/src/main/java/de/sgart/collaboration/domain/exception/ItemNotReroutableException.java`
- `backend/src/main/java/de/sgart/collaboration/domain/exception/TripNotActiveException.java`
- `backend/src/main/java/de/sgart/collaboration/domain/readmodel/TripStoreReadModel.java`
- `backend/src/main/java/de/sgart/collaboration/application/command/RerouteItem.java`
- `backend/src/main/java/de/sgart/collaboration/application/command/RerouteItemHandler.java`
- `backend/src/main/java/de/sgart/collaboration/application/command/AddStoreToTrip.java`
- `backend/src/main/java/de/sgart/collaboration/application/command/AddStoreToTripHandler.java`
- `backend/src/main/java/de/sgart/collaboration/application/exception/ItemNotReroutableApplicationException.java`
- `backend/src/main/java/de/sgart/collaboration/application/exception/TripNotActiveApplicationException.java`
- `backend/src/main/java/de/sgart/collaboration/application/exception/TripNotFoundException.java`
- `backend/src/main/java/de/sgart/collaboration/application/query/TripView.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/JdbcTripStoreReadModel.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/ShoppingTripReadModelProjector.java`
- `backend/src/main/resources/db/migration/V9__trip_read_model.sql`
- `backend/src/test/java/de/sgart/collaboration/adapter/out/ShoppingTripReadModelProjectorTest.java`
- `backend/src/test/java/de/sgart/collaboration/application/RerouteItemHandlerTest.java`
- `backend/src/test/java/de/sgart/collaboration/application/AddStoreToTripHandlerTest.java`
- `backend/src/test/java/de/sgart/collaboration/application/TripViewTest.java`

**Backend — changed:**
- `backend/src/main/java/de/sgart/collaboration/domain/ShoppingList.java` (`rerouteItem`, `requireInTrip`,
  `apply(ItemRerouted)`, shared `assignStore` fold helper)
- `backend/src/main/java/de/sgart/collaboration/domain/ShoppingTrip.java` (`addStore`, `apply(StoreAddedToTrip)`)
- `backend/src/main/java/de/sgart/collaboration/domain/readmodel/ShoppingListReadModel.java` (`markInTrip(listId, tripId)`)
- `backend/src/main/java/de/sgart/collaboration/domain/readmodel/ShoppingListView.java` (`activeTripId`)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/JdbcShoppingListReadModel.java` (`markInTrip`, `listsOf` selects `active_trip_id`)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/ShoppingListReadModelProjector.java` (`ItemRerouted` case, `markInTrip(tripId)`)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/DomainEventJsonCodec.java` (`ItemRerouted`/`StoreAddedToTrip` round-trip)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/CollaborationApplicationConfig.java` (new handler/query beans)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/CollaborationReadModelConfig.java` (new read-model/projector beans)
- `backend/src/main/java/de/sgart/collaboration/adapter/in/TripController.java` (GET `/active`, POST `/{tripId}/stores`)
- `backend/src/main/java/de/sgart/collaboration/adapter/in/ItemController.java` (POST `/{itemId}/reroute`)
- `backend/src/main/java/de/sgart/collaboration/adapter/in/ShoppingListController.java` (`activeTripId` in the summary response)
- `backend/src/main/java/de/sgart/collaboration/adapter/in/WriteErrorAdvice.java` (three new exception mappings)
- `backend/src/main/java/de/sgart/collaboration/application/query/ListOpenLists.java` (`activeTripId` in `ShoppingListSummary`)
- `backend/src/main/java/de/sgart/collaboration/application/query/ListDoneLists.java` (same shared summary shape)
- `backend/src/test/java/de/sgart/collaboration/domain/ShoppingListTest.java` (reroute tests)
- `backend/src/test/java/de/sgart/collaboration/domain/ShoppingTripTest.java` (addStore tests)
- `backend/src/test/java/de/sgart/collaboration/adapter/out/ShoppingListReadModelProjectorTest.java` (`ItemRerouted` cases + `activeTripId` fixes)
- `backend/src/test/java/de/sgart/collaboration/adapter/out/DomainEventJsonCodecTest.java` (two new round-trip tests)
- `backend/src/test/java/de/sgart/collaboration/adapter/in/ItemControllerTest.java` (reroute endpoint tests)
- `backend/src/test/java/de/sgart/collaboration/adapter/in/TripControllerTest.java` (active-trip + add-store endpoint tests)
- `backend/src/test/java/de/sgart/collaboration/adapter/in/ShoppingListControllerTest.java` (`ShoppingListView` 5-arg fixture updates)
- `backend/src/test/java/de/sgart/collaboration/application/ListOpenListsTest.java` (`activeTripId` test + fixture updates)
- `backend/src/test/java/de/sgart/collaboration/application/ListDoneListsTest.java` (fixture updates)

**Flutter — new:**
- `app/lib/features/trips/presentation/trip_state.dart`
- `app/lib/features/trips/presentation/trip_cubit.dart`
- `app/lib/features/trips/presentation/trip_screen.dart`
- `app/lib/features/trips/presentation/active_trips_state.dart`
- `app/lib/features/trips/presentation/active_trips_cubit.dart`
- `app/lib/features/trips/presentation/active_trips_view.dart`
- `app/test/features/trips/presentation/trip_cubit_test.dart`
- `app/test/features/trips/presentation/trip_screen_test.dart`
- `app/test/features/trips/presentation/active_trips_view_test.dart`

**Flutter — changed:**
- `app/lib/features/trips/data/trips_api.dart` (`TripView` model, `activeTrip`, `addStoreToTrip`)
- `app/lib/features/lists/data/items_api.dart` (`rerouteItem`)
- `app/lib/features/lists/data/shopping_list_summary.dart` (`activeTripId`)
- `app/lib/features/stores/presentation/store_picker_sheet.dart` (optional `onInlineStoreCreated` hook)
- `app/lib/features/lists/presentation/list_detail/list_detail_page.dart` (navigate to `TripScreen` after start)
- `app/lib/features/lists/presentation/list_overview/lists_view.dart` (In-Trip row → `TripScreen`)
- `app/lib/features/households/presentation/household_shell.dart` (Einkauf tab → `ActiveTripsView`, placeholder removed)
- `app/lib/l10n/app_de.arb` (new trip-screen/active-trips-index strings; `shellTabShoppingPlaceholder` removed)
- `app/test/support/fake_trips_dependencies.dart` (`activeTrip`/`addStoreToTrip` fakes)
- `app/test/support/fake_items_dependencies.dart` (`rerouteItem` fake)
- `app/test/features/trips/data/trips_api_test.dart` (`activeTrip`/`addStoreToTrip` request-shape tests)
- `app/test/features/stores/presentation/store_picker_sheet_test.dart` (`onInlineStoreCreated` regression test)
- `app/test/features/lists/presentation/list_detail/list_detail_page_test.dart` (start-trip navigation test + provider wiring)
- `app/test/features/lists/presentation/list_overview/lists_view_test.dart` (In-Trip row navigation test)
- `app/test/features/households/presentation/household_shell_test.dart` (Einkauf tab now asserts the active-trips index)

## Change Log

- 2026-08-29: Story drafted (create-story, Opus 4.8) — Epic 3's second story: lands the **trip read
  side deferred from 3.1** (`trip_store_read_model` + a new `trip-`-prefix `ShoppingTripReadModelProjector`,
  `fromStart` retroactively projecting 3.1's `TripStarted` streams; `active_trip_id` on the list read
  model), the **first in-trip mutations** (`ShoppingList.rerouteItem` → `ItemRerouted`, `IN_TRIP`-gated;
  `ShoppingTrip.addStore` → `StoreAddedToTrip`), the **`TripView` grouped-view query**, and **SGART's
  first trip screen** (store groups + „Noch nicht zugeordnet" + reroute + spontaneous add-store) with
  navigation (start → trip; „Im Einkauf" row → trip; „Einkauf" tab = active-trips index reusing
  `ListOpenLists`). 9 LOCKED clarifications; **Timo decided (2026-08-29): (1) in-trip reroute on the
  `ShoppingList` aggregate as a new `IN_TRIP`-gated `ItemRerouted` event (one source of truth), not a
  trip-owned StoreAssignment; (2) slice = grouped view + reroute + add-store, deferring check-off/postpone/
  progress to 3.3 and completion to 3.4; (3) reach the trip screen from the list + the „Einkauf" tab as an
  active-trips index.** Epic-2 retro actions 1/2/3/4/6 baked into the DoD (Cl. 9). Baseline backend 455 /
  Flutter 439.
- 2026-08-29: Story implemented (dev-story, Sonnet 5) — all 25 tasks landed in one vertical slice as
  planned: `ItemRerouted`/`StoreAddedToTrip` events, `ShoppingList.rerouteItem`/`ShoppingTrip.addStore`,
  the `V9` read side (`active_trip_id` + `trip_store_read_model` + the new `fromStart`
  `ShoppingTripReadModelProjector` proving 3.1's retroactive-projection promise), the `TripView` query,
  the extended `TripController`/`ItemController` endpoints, and the Flutter trip screen + reroute/add-store
  picker extension + navigation (start → trip; „Im Einkauf" row → trip; „Einkauf" tab → the new
  active-trips index). Backend 455→507 green (incl. `HexagonalArchitectureTest` ArchUnit and both
  Testcontainers projector suites). Flutter 439→462 green (incl. `flutter analyze`, 0 issues).
