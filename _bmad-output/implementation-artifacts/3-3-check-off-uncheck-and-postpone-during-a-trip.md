---
baseline_commit: a1b2a872
---

# Story 3.3: Check off, uncheck, and postpone during a trip

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a member,
I want to check items off and uncheck them, and postpone what I can't get — either onto another
list or flagged in place — while I shop,
so that the list reflects what actually happened in the aisle.

## Acceptance Criteria

Derived from **epics.md § Story 3.3** (FR9 in-trip actions), refined against **ARCHITECTURE-SPINE.md**
(AD-3, AD-4, AD-8, AD-10, AD-11, AR3), **UX-DR7 / EXPERIENCE.md §3 „Active trip"** and the
`screen-active-trip.html` artifact (per-item **checkbox** → „erledigt", a „**N von M erledigt**"
progress bar, „Noch nicht zugeordnet"), the **Story 3.2 in-trip pattern this story extends**
(`ShoppingList.rerouteItem` / `requireInTrip()` / `ItemRerouted`, the trip screen, `TripView`), the
**Story 2.4 cross-list move machinery this story mirrors for postpone** (`moveItem` /
`ItemMovedToList` / `ItemMoveProcessManager` / `MoveTargetNotOpenException`), and **Timo's decisions
(2026-08-29)** captured in the Clarifications. This is the **third Epic-3 story** — it introduces
SGART's first **item status** concept (Open / Done / Postponed), the **in-trip status mutations**,
and the **in-trip postpone-to-list process manager** that Story 3.4's leftover transfer will reuse.
Each AC is independently testable.

1. **Item status is a first-class concept — Open / Done / Postponed (AC1, foundation).** Items today
   carry no status (`ItemState` folds name/note/quantity/assignedStore only); this story adds an
   **`ItemStatus { OPEN, DONE, POSTPONED }`** to the `Item` inside the `ShoppingList` aggregate,
   folded on `ItemAdded` to **`OPEN`** (the default, Cl. 1). The status is projected to a **new
   `item_read_model.status` column** (V10, default `OPEN`) and surfaced through the existing item
   read shape (`ItemView` / `ListItems.ItemSummary` gain `status`), so both the trip view and the
   list-detail view carry it (off-trip it is always `OPEN` — no behaviour change on list detail).

2. **Check an item off / uncheck it during a trip (AC1, FR9).** Given an item during an **active**
   trip (the list is `IN_TRIP`), when a member **checks it off** its status becomes **`DONE`**; when
   they **uncheck** it, it returns to **`OPEN`**. These are **new `IN_TRIP`-gated commands on the
   `ShoppingList` aggregate** — `checkOffItem` (→ `ItemCheckedOff`) and `uncheckItem` (→
   `ItemUnchecked`) on the **`list-{id}`** stream — the **same pattern Story 3.2 established with
   `rerouteItem`** (`requireInTrip()`, the inverse of the `OPEN`-gated planning commands). Checking an
   already-`DONE` item / unchecking an already-`OPEN` item is a **convergent no-op** (raises nothing,
   AD-8). **This is the only place an item reaches `DONE`** — a `DONE` item always has a trip context
   (the list was `IN_TRIP` when it was checked). Check-off does **not** require a store assignment (an
   unassigned „Noch nicht zugeordnet" item is checkable).

3. **Postpone an item in place (AC2, FR9).** Given an item during an active trip, when a member
   **postpones it in place**, its status becomes **`POSTPONED`** (a new `IN_TRIP`-gated
   `postponeItemInPlace` → **`ItemPostponed`** on `list-{id}`; already-`POSTPONED` is a convergent
   no-op). The item **stays on this list** (it is not moved) but is visibly set aside — the „couldn't
   get it, not moving it elsewhere" case. `uncheckItem` returns a `POSTPONED` item to `OPEN` (the undo
   affordance); `checkOffItem` may still move a `POSTPONED` item to `DONE`.

4. **Postpone an item onto another list — existing or newly created (AC2, FR9, AD-10).** Given an item
   during an active trip, when a member postpones it **onto another list**, the item **leaves this
   list** and is **added to the target Open list** — a cross-aggregate effect carried by a **process
   manager** (AD-10), exactly like the Story 2.4 planning move but **`IN_TRIP`-gated**. This raises a
   **new `ItemPostponedToList`** event on `list-{id}` (distinct from the `OPEN`-gated `ItemMovedToList`
   — Timo, 2026-08-29, mirroring 3.2 Cl. 1's reroute-vs-assign split), carrying the item's
   name/note/quantity; the **`ItemMoveProcessManager` reacts to it** and issues the **same idempotent
   `AddItem`** on the target (deterministic command id from the trigger event, the concurrency-retry
   loop that already exists — retro Action 12). The target may be an **existing Open list** or a
   **newly created one** (client-orchestrated: `CreateShoppingList` first, then postpone to the
   returned id — the Story 2.4 „Neue Liste" two-step). **Only Open lists are valid targets** — an
   `IN_TRIP` or `DONE` target is rejected (`MoveTargetNotOpenException` → 409, reused from 2.4); a
   postpone whose target equals the source is a 400 (`InvalidMoveTargetException`, reused).

5. **The trip screen reflects check-off, postpone, and a „N von M erledigt" progress bar (AC1/AC2,
   UX-DR7).** Given the store-grouped trip screen (Story 3.2), when items carry a status, then each
   item row shows a **checkbox** (tap toggles `DONE`/`OPEN`); `DONE` rows render in the done treatment
   (`SgartColors` „Done / purchased" tint, strike/checked); a **postpone affordance** per item opens a
   target picker offering the household's **Open lists** + **„Neue Liste"** + **„Hier vormerken"**
   (postpone-in-place); and the header shows a **„N von M erledigt" progress bar** (N = `DONE` count,
   M = total items) — the bar Story 2.2/3.2 deferred until check-off exists. `POSTPONED` items render
   in a distinct set-aside treatment and are **excluded from the „remaining/open" set** (they count in
   M but not toward „still to get"). Every mutation is **optimistic** (the projection lags, AR3/NFR9)
   with revert-on-failure. **No „Einkauf abschließen" / completion in 3.3** (Cl. 2 — Story 3.4).

6. **Membership, isolation, eventual consistency & no personal data (AC5).** Every new command is
   **membership-gated** (non-member → **403**), a malformed id is **400**, an unknown/other-household
   list or item is **404**; a status change or postpone on a **not-`IN_TRIP`** list is **409** (a
   state conflict — the trip may have completed concurrently, Story 3.4 — mirroring 3.2's reroute 409).
   `ItemCheckedOff` / `ItemUnchecked` / `ItemPostponed` / `ItemPostponedToList` carry **no personal
   data and no *who*** — household/list/item (and, for postpone-to-list, target-list + item content)
   ids only (AD-5/AD-6, mirrors `ItemRerouted` / `ItemMovedToList`); `MemberId` is used only at the
   handler seam. The item read model is queried **by `household_id`** so one household's items never
   leak. Tests use synthetic, clearly-fake German data only.

## Clarifications (LOCKED)

Taken from the epic ACs, the ARCHITECTURE-SPINE, the Story 2.4 / 3.2 patterns, `deferred-work.md`, the
Epic-2 retro action items, and **Timo's decisions (2026-08-29)**. **If any is wrong, correct it before
`dev-story`.**

1. **Item status is a new `ItemStatus { OPEN, DONE, POSTPONED }` on the `Item` inside the
   `ShoppingList` aggregate (Timo, 2026-08-29 — the foundation).** Items have had no status until now
   (`ItemState` = name/note/quantity/assignedStore). Add a domain enum `ItemStatus` in
   `collaboration.domain` and a `status` field to the private `ItemState` record, folded to **`OPEN`**
   on `ItemAdded` (and preserved through `ItemUpdated` / `ItemAssignedToStore` / `ItemRerouted` folds
   exactly as `assignedStore` is preserved through `ItemUpdated`, Cl. 4 — only the status events
   change it). Three in-trip status transitions, each its **own past-tense event** on `list-{id}`
   (the codebase idiom — every mutation has a self-describing event, AD-11; there is no parameterized
   „StatusChanged{value}" precedent, and reroute-vs-assign already kept distinct events for one
   field): `ItemCheckedOff` (→ `DONE`), `ItemUnchecked` (→ `OPEN`), `ItemPostponed` (→ `POSTPONED`).
   All three are **`requireInTrip()`-gated** and **idempotent when already in the target status**
   (raise nothing, AD-8). Transitions are symmetric resets: `checkOff` → `DONE` from any non-`DONE`;
   `uncheck` → `OPEN` from any non-`OPEN` (the undo for both `DONE` and `POSTPONED`); `postponeInPlace`
   → `POSTPONED` from any non-`POSTPONED`. [Source: `ShoppingList.ItemState` / `rerouteItem` /
   `requireInTrip`; ARCHITECTURE-SPINE.md #AD-10/#AD-11; epics.md Story 3.3 AC1.]

2. **3.3's slice = item status + check-off/uncheck + all three postpone variants; completion → Story
   3.4 (Timo, 2026-08-29).** The trip screen gains the checkbox, the progress bar, and the postpone
   picker (in-place + to-existing + to-new). It does **not** add „Einkauf abschließen" / trip
   completion / `TripStatus.DONE` / list → Done / the leftover-review dialog (all Story 3.4, which also
   lands the deferred **cached Done-archive invalidation** and the reachable **Done-rename** test), and
   does **not** add print/share (Story 3.5) or cross-device live-sync (Epic 4 SSE — the actor sees
   their own changes optimistically; peers refetch on open). [Source: epics.md Story 3.4/3.5;
   `deferred-work.md` „Cached Done archive … Epic 3" / „DONE-rejects-rename … Epic 3".]

3. **Postpone builds all three variants, and postpone-to-list is a new `IN_TRIP`-gated
   `ItemPostponedToList` that converges on the existing `ItemMoveProcessManager` (Timo, 2026-08-29).**
   Postpone-in-place is a pure status change (Cl. 1). Postpone-to-list (existing or new target) is a
   cross-list move mirroring Story 2.4, but the write gate is `IN_TRIP`, so it is a **new event**
   `ItemPostponedToList` distinct from the `OPEN`-gated `ItemMovedToList` (the reroute-vs-assign
   precedent, 3.2 Cl. 1). The **`ItemMoveProcessManager` reacts to both** events, issuing the same
   idempotent `AddItem` on the target — extract a shared private `addItemToTarget(...)` so the two
   public reaction methods stay DRY; the bounded concurrency-retry loop already there is inherited
   (retro Action 12 — „apply the PM concurrency-conflict-retry from the start in Epic 3's
   postpone-to-list PM" is thus satisfied by reuse, not a new PM). The
   `CollaborationProcessManagerSubscription.react` routes `ItemPostponedToList` → the PM (same
   `list-` prefix it already watches). The `ShoppingList` **fold** for `ItemPostponedToList` is a
   **removal** (the item leaves this list, exactly like `ItemMovedToList`). **Rejected:** relaxing
   `moveItem` to allow `IN_TRIP` (one event, one gate) — it would conflate planning-move with
   in-trip-postpone in the log against the 3.2 precedent. [Source: `ItemMovedToList`,
   `ItemMoveProcessManager`, `MoveItemHandler`; 3.2 story Cl. 1; sprint-status open action item
   „postpone-to-list, leftover-transfer".]

4. **Every non-status fold preserves the item's status; only the three status events change it (the
   regression trap).** Just as `ItemUpdated`'s fold carries `assignedStore` forward (2.6 Cl. 7) and
   `ItemRerouted`/`ItemAssignedToStore` are the only writers of `assignedStore`, the item's `status`
   is **preserved** through `apply(ItemUpdated)` / `apply(ItemAssignedToStore)` / `apply(ItemRerouted)`
   (an edit or a reroute must never silently reset a `DONE`/`POSTPONED` item to `OPEN`), and the
   **projector** likewise: `ItemUpdated` / `ItemAssignedToStore` / `ItemRerouted` write name/note/
   quantity/store only — **only** `ItemCheckedOff` / `ItemUnchecked` / `ItemPostponed` write the
   `status` column. Cover this with a dedicated aggregate test (reroute a `DONE` item → still `DONE`)
   and a dedicated projector test (an `ItemRerouted`/`ItemUpdated` after a check leaves `status`
   untouched). [Source: `ShoppingList.apply(ItemUpdated)` (the „carry the assignment forward" comment);
   `ShoppingListReadModelProjector` `ItemAssignedToStore`/`ItemRerouted` cases; 2.6 story Cl. 7.]

5. **The in-trip state-conflict maps to one 409 exception shared by reroute + all status/postpone
   commands — generalize `requireInTrip()`'s exception (Boy Scout, DRY).** 3.2 added
   `ItemNotReroutableException` (→ 409) thrown by `requireInTrip()`. All the new in-trip commands call
   the **same** `requireInTrip()` gate, so they would all throw an exception literally named „not
   *reroutable*" — wrong for check-off. **Generalize:** rename `ItemNotReroutableException` →
   **`ItemNotDuringTripException`** (message „Items may only be changed during a trip, list is …") and
   its paired `ItemNotReroutableApplicationException` → **`ItemNotDuringTripApplicationException`**
   (still → 409, stable `code`), update `rerouteItem` + the 3.2 tests + `WriteErrorAdvice` mapping +
   the error-advice contract test. This is one gate, one exception for the whole in-trip phase — no
   three near-identical exceptions. (If minimizing churn is preferred at dev time, the fallback is to
   keep `ItemNotReroutableException` for reroute and add one shared status/postpone exception — but the
   generalize path is the clean one and is the recommendation.) [Source:
   `ItemNotReroutableException`/`ItemNotReroutableApplicationException`; `WriteErrorAdvice` 409 mapping;
   CLAUDE.md §1 Boy Scout / DRY.]

6. **Postpone-to-list reuses the Story 2.4 target rules verbatim — Open-only targets, self-target
   rejected, target validated server-side.** The postpone-to-list **handler mirrors
   `MoveItemHandler`**: it loads **both** the source (`IN_TRIP`) and target lists (household-scoped
   404), rejects a target that is not `OPEN` with **`MoveTargetNotOpenException` (409, reused)** and a
   target equal to the source with **`InvalidMoveTargetException` (400, reused)**, mutates only the
   source (`postponeItemToList`), and the PM adds to the target. „Only Open lists are offered" (epic
   E3) is enforced **both** client-side (the picker lists only Open lists) **and** server-side (the
   handler's `MoveTargetNotOpenException`). New-list postpone is client-orchestrated (create then
   postpone), so the target is always freshly `OPEN`. [Source: `MoveItemHandler` (loads both lists,
   `MoveTargetNotOpenException` / `InvalidMoveTargetException`); `move_target_sheet.dart` (the „Neue
   Liste" two-step + Open-list list).]

7. **The progress bar is client-computed; `POSTPONED` items are excluded from „remaining", counted in
   the total.** „N von M erledigt": **N = count(status == DONE)**, **M = total items**. A `POSTPONED`
   item is neither `DONE` nor „still to get" — render it in a distinct set-aside treatment (dimmed /
   its own visual state within its group or a „Vorgemerkt/Verschoben" grouping — a client design
   detail), exclude it from the open/remaining set, but keep it in **M** (it is still on the list). The
   progress values are **computed getters on `TripState`** (like `groups`/`unassignedItems`) — one
   source of truth, never stored. [Source: `screen-active-trip.html` („10 von 12 erledigt", the
   `prog`/`track` bar); `deferred-work.md` „the progress bar (checked/total) lands in Epic 3 with
   check-off"; `trip_state.dart` computed-getter pattern.]

8. **Check-off/uncheck/postpone endpoints extend `ItemController`, mirroring `/{itemId}/reroute` (200)
   — no new controller.** Add `POST /{itemId}/check-off`, `POST /{itemId}/uncheck`, `POST
   /{itemId}/postpone` (in-place), `POST /{itemId}/postpone-to-list` (body `{targetListId, commandId}`)
   — all under the existing `…/lists/{listId}/items` base, all returning **200** (a no-body mutation,
   the `reroute` choice, its closest in-trip sibling; `move` chose 204 — match reroute for the in-trip
   family and be consistent). Plain-`String` request DTOs (no `..domain..`, ArchUnit). [Source:
   `ItemController` (`/{itemId}/reroute` 200, `/{itemId}/move` 204); 3.2 story Task 13.]

9. **Carry the still-open Epic-2 retro action items into this story (DoD, not review-catch).** Per the
   3.2 Cl. 9 precedent: **(a)** optimistic-state — every check/uncheck/postpone reflects its
   server-visible effect immediately (the checkbox flips, the item leaves on postpone-to-list, the
   progress bar moves), inline-created postpone targets behave (Action 7); **(b)** an **error-advice
   contract test** for every new endpoint (bad input / each reachable domain exception → the right 4xx)
   + the extended DoD (a11y labels on the checkbox + postpone affordances ≥48px UX-DR5, no dead
   code/strings, fail-fast guards) as a pre-review checklist (Actions 2/3/8); **(c)** the
   `isSubmitting`/re-entrancy + spent-`CommandIntent` guards on **every** new client command path from
   the first pass (Action 9); **(d)** the **new `item_read_model.status` column** ships with a
   **two-household isolation + replay-idempotency** test in the same change (Action 10); **(e)** the
   postpone-to-list PM reaction reuses the established deterministic-id + bounded-retry pattern
   (Action 12). Action 11 (cached Done-archive invalidation) stays with Story 3.4 (no Done transition
   here). [Source: `epic-2-retro-2026-08-28.md` §6; sprint-status open action items.]

## Tasks / Subtasks

> Follow TDD (CLAUDE.md §6): failing test first at the lowest level that proves the behaviour, then
> the simplest code to pass. Keep the domain pure (AD-1). **Mirror the cited existing file for every
> new class** — the event, aggregate method, command→handler, projection, query field, controller,
> process-manager reaction, and Flutter patterns are all established (Stories 2.4, 2.6, 3.2); do not
> invent new ones. This story introduces **item status**, the **in-trip status mutations**, and the
> **in-trip postpone-to-list** reusing SGART's first process manager. Baseline: backend **507**,
> Flutter **462**.

### Backend — domain: item status + the four new events + the aggregate mutations (AC1–AC4, Cl. 1/3/4/5)

- [x] **Task 1: `ItemStatus` enum + status on `ItemState`** — `collaboration.domain` (mirror
      `ListStatus`'s shape/Javadoc)
  - [x] `enum ItemStatus { OPEN, DONE, POSTPONED }` with Javadoc: the item's in-trip lifecycle
        (Story 3.3); `OPEN` is the birth state (folded on `ItemAdded`), `DONE`/`POSTPONED` are reached
        only by the `IN_TRIP`-gated status events; distinct from `ListStatus` (the list's lifecycle).
  - [x] Add `ItemStatus status` to the private `ItemState` record; `apply(ItemAdded)` folds it to
        `OPEN`. **Preserve it** through `apply(ItemUpdated)` and the shared `assignStore(...)` helper
        (both must carry the existing `status` forward, exactly as `ItemUpdated` already carries
        `assignedStore` — Cl. 4). Update the `ItemState` constructor call sites accordingly.
- [x] **Task 2: the three status events** — `collaboration.domain.event` (mirror `ItemRerouted`)
  - [x] `record ItemCheckedOff(EventId eventId, HouseholdId householdId, ShoppingListId listId, ItemId
        itemId)`, `ItemUnchecked(… same fields)`, `ItemPostponed(… same fields)` — `requireNonNull`
        all. Javadoc each: the in-trip status transition, raised on `list-{id}` while the list is
        `IN_TRIP` (Cl. 1); no personal data / no *who* (AD-5/AD-6, mirrors `ItemRerouted`).
- [x] **Task 3: `ItemPostponedToList` event** — `collaboration.domain.event` (mirror `ItemMovedToList`)
  - [x] `record ItemPostponedToList(EventId eventId, HouseholdId householdId, ShoppingListId
        sourceListId, ItemId itemId, ShoppingListId targetListId, ItemName name, ItemNote note,
        Quantity quantity)` — `requireNonNull` all except nullable `note`. Javadoc: the **in-trip**
        postpone of an item onto another list (Story 3.3, AC4), on the source `list-{id}` stream,
        distinct from the `OPEN`-gated `ItemMovedToList` (Cl. 3) so the write-side gate is unambiguous;
        folds to a removal here; the target-side add is a separate `ItemAdded` raised by the
        `ItemMoveProcessManager` (AD-10). Carries the full item payload so the PM needs no reload.
- [x] **Task 4: generalize the in-trip 409 exception** (Cl. 5) — `collaboration.domain.exception`
  - [x] Rename `ItemNotReroutableException` → **`ItemNotDuringTripException`** (message „Items may only
        be changed during a trip, list is " + status). Update `requireInTrip()`, `rerouteItem`'s
        Javadoc, and every reference. (Fallback per Cl. 5 if churn is unwanted: keep the reroute
        exception and add one shared status/postpone exception.)
- [x] **Task 5: `ShoppingList.checkOffItem/uncheckItem/postponeItemInPlace` + folds** (AC2, AC3, Cl. 1/4)
  - [x] Three `public void`s, each `requireNonNull` + `requireInTrip()` + unknown item →
        `ItemNotFoundException` + **already-in-target-status convergent no-op** (`if (existing.status()
        == ItemStatus.DONE) return;` etc.) + else `raise(new ItemCheckedOff/Unchecked/Postponed(…))`.
        Javadoc mirrors `rerouteItem` but for status.
  - [x] `apply(ItemCheckedOff/ItemUnchecked/ItemPostponed)` — each folds `status` via a shared private
        `setStatus(itemId, ItemStatus)` helper (null-tolerant on a reordered/repaired stream, mirroring
        `assignStore`), **preserving** name/note/quantity/assignedStore. Register all three in the
        `apply(...)` switch.
  - [x] Extend `ShoppingListTest` (fast, pure): `checkOffItem_onAnInTripList_raisesItemCheckedOff_andFoldsToDone`;
        `uncheckItem_returnsAPostponedItemToOpen`; `postponeItemInPlace_raisesItemPostponed`;
        each `…_whenAlreadyInThatStatus_isAConvergentNoOp`; `checkOffItem_onAnOpenList_throwsItemNotDuringTrip`;
        `checkOffItem_forAnUnknownItem_throwsItemNotFound`; **`rerouteItem_onADoneItem_keepsStatusDone`**
        (Cl. 4 regression); `updateItem`-preserves-status is covered off-trip only where reachable.
- [x] **Task 6: `ShoppingList.postponeItemToList` + `apply(ItemPostponedToList)`** (AC4, Cl. 3/6)
  - [x] `public void postponeItemToList(ItemId itemId, ShoppingListId targetListId, CommandId
        commandId)`: `requireNonNull` all; `requireInTrip()`; unknown item → `ItemNotFoundException`;
        else `raise(new ItemPostponedToList(EventId.generate(), householdId, listId, itemId,
        targetListId, existing.name(), existing.note(), existing.quantity()))`. Does **not** validate
        `targetListId` (the handler does, mirroring `moveItem`, AD-10). `apply(ItemPostponedToList)`
        removes the item (mirror `apply(ItemMovedToList)`). Register in the switch.
  - [x] Extend `ShoppingListTest`: `postponeItemToList_onAnInTripList_raisesItemPostponedToList_andRemovesTheItem`;
        `postponeItemToList_onAnOpenList_throwsItemNotDuringTrip`; `…_forAnUnknownItem_throwsItemNotFound`.

### Backend — application: commands + handlers + PM reaction (AC2–AC4, AC5, Cl. 3/5/6/9)

- [x] **Task 7: three status commands + handlers** — `application.command` (mirror
      `RerouteItem`/`RerouteItemHandler`, DTO **beside** its handler)
  - [x] `record CheckOffItem/UncheckItem/PostponeItem(ShoppingListId listId, ItemId itemId, CommandId
        commandId, AggregateVersion basedOnVersion)` — `requireNonNull` all. Each handler mirrors
        `RerouteItemHandler`: translate ids (400) → resolve identity (403) → `loadListOwnedBy` (404
        empty / 404 cross-household) → loaded version (AD-8) → `list.checkOffItem(...)` translating
        `ItemNotFoundException` → 404 and **`ItemNotDuringTripException` → `ItemNotDuringTripApplicationException`
        (409)** at the seam → append only if `!uncommittedEvents().isEmpty()` (no-op skips).
  - [x] Rename `ItemNotReroutableApplicationException` → **`ItemNotDuringTripApplicationException`**
        (`application.exception`, → 409); confirm `WriteErrorAdvice` maps it (update the mapping + its
        **error-advice contract test**, Action 2). Wire the three beans in
        `CollaborationApplicationConfig` (mirror `rerouteItemHandler`). Reuse `CommandFieldTranslations`
        (`toItemId`/`toShoppingListId` exist — confirm, no new translator).
  - [x] `CheckOffItemHandlerTest` / `UncheckItemHandlerTest` / `PostponeItemHandlerTest` (in-memory
        `EventStore` + fake `ResolveMemberIdentity`, mirror `RerouteItemHandlerTest`): appends the right
        event on `list-{id}`; 403 / 400 / 404 / 404-cross-hh / **409 not-In-Trip** / unknown item 404 /
        already-in-status no-append.
- [x] **Task 8: `PostponeItemToList` + `PostponeItemToListHandler`** — `application.command` (mirror
      `MoveItem`/`MoveItemHandler`, loading **both** lists)
  - [x] `record PostponeItemToList(ShoppingListId sourceListId, ItemId itemId, ShoppingListId
        targetListId, CommandId commandId, AggregateVersion basedOnVersion)` — `requireNonNull` all.
  - [x] Handler mirrors `MoveItemHandler`: translate ids (400); `targetListId.equals(sourceListId)` →
        **`InvalidMoveTargetException` (400, reused)**; resolve identity (403); load source + target
        `loadListOwnedBy` (404); target not `OPEN` → **`MoveTargetNotOpenException` (409, reused)**;
        source loaded version (AD-8); `source.postponeItemToList(...)` translating
        `ItemNotDuringTripException` → 409 and `ItemNotFoundException` → 404; append the source's events.
        The **target add is the PM's job**, not this handler (single writer per append, the 2.4 rule).
  - [x] Wire the bean. `PostponeItemToListHandlerTest` (mirror `MoveItemHandlerTest`): raises
        `ItemPostponedToList` on the source; 403 / 400-malformed / 400-self-target / 404 source /
        404 target / 404 cross-hh / **409 source-not-In-Trip** / **409 target-not-Open** / unknown item 404.
- [x] **Task 9: `ItemMoveProcessManager` also reacts to `ItemPostponedToList`** (AC4, Cl. 3, Action 12)
  - [x] Extract a private `addItemToTarget(EventId trigger, ShoppingListId targetListId, ItemId,
        ItemName, ItemNote, Quantity)` from `onItemMovedToList` (the load-then-append + bounded
        concurrency-retry + `DuplicateItemException`/`ItemChangeNotPermittedException` swallows), and
        call it from both `onItemMovedToList` and a new `onItemPostponedToList(ItemPostponedToList)`.
        Update the class Javadoc: it now carries **both** planning-moves and in-trip-postpones (keep the
        name to limit churn; a rename to `ItemTransferProcessManager` is optional Boy-Scout, note it).
  - [x] `CollaborationProcessManagerSubscription.react`: route `ItemPostponedToList` →
        `itemMoveProcessManager.onItemPostponedToList(...)` (same `list-` prefix, already subscribed).
  - [x] Extend `ItemMoveProcessManagerTest` (in-memory `EventStore`): an `ItemPostponedToList` adds the
        item to the target exactly once; a re-delivery (deterministic id) is a silent no-op; the
        target-Open/`DuplicateItemException` race safety nets behave as for move.

### Backend — read side: item status column + the projection + the query field (AC1, AC5, Cl. 1/4/7/9)

- [x] **Task 10: `V10__item_status.sql`** — one additive `ALTER`
  - [x] `ALTER TABLE item_read_model ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'OPEN';` — comment:
        the item's in-trip lifecycle status (Story 3.3), written only by the status projections; no
        personal data (a status enum). Confirm `NoPersistedPersonalDataTest` (AD-6) still passes.
- [x] **Task 11: `ItemReadModel.setStatus` + `JdbcItemReadModel` + `ItemView.status`** (Cl. 1/4)
  - [x] Port: `void setStatus(ItemId itemId, ItemStatus status)`. `JdbcItemReadModel`: `insertItem`
        inserts `status = 'OPEN'`; `setStatus` = `UPDATE item_read_model SET status = :status WHERE
        item_id = :itemId`; **`updateItem` / `assignStore` leave `status` untouched** (Cl. 4). Map the
        new column in `itemsOf`. `ItemView` gains `ItemStatus status`.
- [x] **Task 12: projector status cases** (Cl. 1/4)
  - [x] `ShoppingListReadModelProjector.project`: `case ItemCheckedOff c -> itemReadModel.setStatus(c.itemId(),
        ItemStatus.DONE)`; `ItemUnchecked -> OPEN`; `ItemPostponed -> POSTPONED`;
        `ItemPostponedToList p -> itemReadModel.removeItem(p.itemId())` (mirror the `ItemMovedToList`
        case). `ItemUpdated`/`ItemAssignedToStore`/`ItemRerouted` cases **unchanged** (they must not
        write `status`, Cl. 4).
  - [x] Extend `ShoppingListReadModelProjectorTest` (Testcontainers): `ItemCheckedOff` sets
        `status=DONE`; `ItemUnchecked`/`ItemPostponed` set OPEN/POSTPONED; **an `ItemRerouted` or
        `ItemUpdated` after a check leaves `status` untouched** (Cl. 4); `ItemPostponedToList` removes
        the row; **two-household isolation** (a check in A never touches a B item) + **replay
        idempotency** for the status column, same change (Action 10).
- [x] **Task 13: thread `status` through the read shapes** (AC1)
  - [x] `ListItems.ItemSummary` gains `String status` (the enum name); `ListItems.toSummary` +
        `TripView`'s inline `ItemSummary` construction map `item.status().name()`. `ListItemsTest` /
        `TripViewTest` assert the status is present (OPEN off-trip; a checked item's `DONE` in the trip
        view). Controllers already serialize the summary shape — confirm the field flows to JSON.

### Backend — adapter.in + codec + ArchUnit (AC2–AC5, Cl. 8/9)

- [x] **Task 14: extend `ItemController`** (Cl. 8) — mirror `/{itemId}/reroute` (200)
  - [x] `POST /{itemId}/check-off`, `/{itemId}/uncheck`, `/{itemId}/postpone` → the three status
        handlers (`record …Request(String commandId)`); `POST /{itemId}/postpone-to-list` → the
        postpone-to-list handler (`record PostponeToListRequest(String targetListId, String
        commandId)`, mirror `MoveRequest`). All **200**. Identity from JWT `sub` via
        `AuthenticatedCaller`. Plain-`String` DTOs.
  - [x] `ItemControllerTest` (MockMvc, mirror the reroute/move tests): each endpoint → 200 / 400
        malformed / 403 non-member / 404 unknown list/item / **409 not-In-Trip**; postpone-to-list also
        → 400 self-target / 409 target-not-Open. These double as the **Action-2 error-advice contract**
        for the four new endpoints.
- [x] **Task 15: register the four events in `DomainEventJsonCodec`** (mirror the `ItemRerouted`/`ItemMovedToList`
      registration)
  - [x] Stable type tags (`ITEM_CHECKED_OFF_TYPE`, `ITEM_UNCHECKED_TYPE`, `ITEM_POSTPONED_TYPE`,
        `ITEM_POSTPONED_TO_LIST_TYPE`); `typeTagFor` + `toJsonBytes` + `fromJsonBytes` for each
        (postpone-to-list round-trips its name/note/quantity like `ItemMovedToList`). Extend
        `DomainEventJsonCodecTest`: all four round-trip with a **stable type tag** (never a class name).
- [x] **Task 16: ArchUnit** — run `HexagonalArchitectureTest`; confirm the four events + the renamed
      exception (`..domain..`), the four commands/handlers + app-exception (`..application..`), the
      projector/read-model changes (`..adapter.out..`), and the extended controller (`..adapter.in..`,
      no `..domain..` import) need **no** rule change.

### Flutter — data layer (AC1–AC4)

- [x] **Task 17: `Item.status` + `ItemsApi` mutations** — `features/lists/data/` (mirror `rerouteItem`)
  - [x] `Item` gains `final String status` (+ `fromJson` reads `status`, defaulting/validating like
        the other fields; a missing `status` → treat as `'OPEN'` for forward-compat, or require it —
        match the backend which always sends it). Update `Item`'s `==`/`hashCode`/const ctor + every
        construction site (incl. `TripCubit.reroute`'s optimistic rebuild — carry `status` forward).
  - [x] `ItemsApi.checkOffItem/uncheckItem/postponeItem(householdId, listId, itemId, {commandId})`
        (`POST …/items/{itemId}/check-off|uncheck|postpone`) and `postponeItemToList(householdId,
        listId, itemId, {required targetListId, required commandId})` (`POST …/items/{itemId}/postpone-to-list`).
        Extend `FakeItemsApi` (record last calls; armable to throw). Request-shape tests (paths + bodies);
        a server error surfaces as the shared `AppException`.

### Flutter — the trip screen: checkboxes, progress, postpone (AC5, Cl. 2/7/9)

- [x] **Task 18: `TripCubit` + `TripState` status support** — `features/trips/presentation/`
  - [x] `TripState` computed getters: `doneCount` (items with `status == 'DONE'`), `totalCount`
        (`items.length`), and the postpone/open partition (a `POSTPONED` item is excluded from the
        „remaining/open" set but stays in `totalCount`, Cl. 7). Keep the Cl. 7 store-grouping getters
        intact; decide how `POSTPONED` renders within a group vs a set-aside (a client detail).
  - [x] `TripCubit.checkOff(itemId)` / `uncheck(itemId)` (or a single `toggleDone(itemId)` branching on
        current status — one affordance, the checkbox), `postponeInPlace(itemId)`,
        `postponeToList(itemId, targetListId)` — each a dedicated `CommandIntent` (keyed on the item,
        freshened on change + after success), guarded `ready && !isSubmitting`, **optimistic** (flip
        the item's `status` / remove it on postpone-to-list), revert + `actionError` on failure
        (Actions 7/9). New-list postpone is orchestrated at the widget/page layer (create via
        `ShoppingListsApi`, then `postponeToList` the returned id — the 2.4 two-step).
  - [x] `trip_cubit_test.dart`: `checkOff` optimistically flips to DONE + reverts on failure + freshens
        the intent + the `isSubmitting` guard drops a second tap; `postponeInPlace` sets POSTPONED;
        `postponeToList` optimistically removes the item + reverts; `doneCount`/`totalCount` compute
        across statuses (a POSTPONED item counts in total, not in done).
- [x] **Task 19: `trip_screen.dart` — checkbox, progress bar, postpone affordance** (UX-DR7,
      `screen-active-trip.html`)
  - [x] Header gains the **„N von M erledigt" progress bar** (`prog`/`track`, Cl. 7). Each item row
        gains a **leading checkbox** (`trip-item-checkbox-{itemId}`, tap → `checkOff`/`uncheck`), a
        **done treatment** for `DONE` rows (`SgartColors` „Done/purchased" tint), and a **postpone
        affordance** (`trip-item-postpone-{itemId}`) opening the postpone target sheet (Task 20).
        `POSTPONED` rows render set-aside (Cl. 7). Keep 3.2's store groups / „Noch nicht zugeordnet" /
        reroute / add-store intact. **No „Einkauf abschließen"** (Cl. 2 — assert its absence). a11y
        labels/semantics on the checkbox + postpone affordance (≥48px, UX-DR5).
  - [x] `trip_screen_test.dart`: renders the progress bar with the right counts; tapping a checkbox
        checks off / unchecks; a DONE row shows the done treatment; the postpone affordance opens the
        sheet; **no „Einkauf abschließen" present** (Cl. 2 guard).
- [x] **Task 20: postpone target sheet** — reuse/mirror the 2.4 `move_target_sheet.dart`
      (`features/lists/presentation/list_detail/`) scoped to postpone
  - [x] A sheet offering **„Hier vormerken"** (postpone-in-place) + the household's **Open lists** (via
        `ShoppingListsApi` „Offen" set, excluding the current list) + **„＋ Neue Liste"** (the
        create-then-postpone two-step, mirroring `_NewListNameSheetBody`). Only Open lists are listed
        (epic E3). Extract/share the 2.4 sheet if it keeps both DRY without obscuring them; **do not**
        regress the 2.4 planning-move sheet.
  - [x] Widget test: the sheet lists „Hier vormerken" + only Open lists + „Neue Liste"; picking an
        existing list postpones to it; „Neue Liste" creates then postpones; „Hier vormerken" postpones
        in place; the 2.4 move sheet still behaves (regression).

### Flutter — localization (Cl. 9)

- [x] **Task 21: localization** — `l10n` (`app_de.arb` + `flutter gen-l10n`)
  - [x] `tripProgressLabel` („{done} von {total} erledigt"), `tripItemCheckOffSemantic` /
        `tripItemUncheckSemantic`, `tripItemPostponeAction` („Verschieben"), the postpone sheet title +
        „Hier vormerken" (`tripPostponeInPlace`) + „Verschoben/Vorgemerkt" set-aside label. **Reuse**
        the 2.4 „Neue Liste" / move-target / list-create strings and the „Noch nicht zugeordnet" string
        — check before adding. No hard-coded user-facing strings (Action 2 DoD).

### Tests & green build (CLAUDE.md §6)

- [x] **Task 22: extended-DoD sweep (retro Actions 2/3/7/8/9/10/12)** — before review: every
      check/uncheck/postpone reflects its server-visible effect optimistically; a11y labels on the
      checkbox + postpone affordances; no dead code / no hard-coded strings; fail-fast +
      `isSubmitting`/spent-intent guards on every new command path; the error-advice mapping tests for
      the four new endpoints exist; the `item_read_model.status` column has an isolation + replay test;
      the postpone-to-list PM reuses the deterministic-id + bounded-retry pattern.
- [x] **Task 23: full-suite green** — backend `./gradlew test` (incl. `HexagonalArchitectureTest`
      ArchUnit **and** the Testcontainers projector tests) **and** Flutter `flutter analyze` +
      `flutter test`, both **in full** (not per-file), per CLAUDE.md §6. State which suite ran and the
      counts (baseline: backend **507**, Flutter **462**).

## Dev Notes

### What is (and isn't) in this story — read first

3.3 is a **domain-heavy full vertical slice** that introduces **item status** — a concept SGART has
not had until now (the `Item` inside `ShoppingList` folded only name/note/quantity/assignedStore).
On top of that foundation it lands three things: **check-off / uncheck** (item → `DONE` / `OPEN`),
**postpone-in-place** (item → `POSTPONED`, stays on the list), and **postpone-to-list** (item leaves
onto an existing or new Open list, via the existing process manager). The trip screen (Story 3.2)
gains a **checkbox** per row, the **„N von M erledigt" progress bar** (deferred since 2.2), and a
**postpone target picker**.

It **deliberately does not** build (Cl. 2): **trip completion** / „Einkauf abschließen" /
`TripStatus.DONE` / list → Done / the leftover-review dialog (Story 3.4 — which also lands the
deferred cached-Done-archive invalidation and the reachable Done-rename test); **print/share** (Story
3.5); **cross-device live-sync** (Epic 4 SSE — the actor sees their own changes optimistically; peers
refetch on open).

The crux ideas:

- **Item status is a new `ItemStatus { OPEN, DONE, POSTPONED }` on the list's `Item` (Cl. 1).** Each
  transition is its own `IN_TRIP`-gated event on `list-{id}` — `ItemCheckedOff`, `ItemUnchecked`,
  `ItemPostponed` — folding the same `status` field. This is the **identical pattern Story 3.2
  established with `rerouteItem`/`requireInTrip()`** — 3.3 is the payoff of that groundwork. **The
  only place an item reaches `DONE`.**
- **Postpone-to-list is a new `IN_TRIP`-gated `ItemPostponedToList` that converges on the existing
  `ItemMoveProcessManager` (Cl. 3).** Distinct from the `OPEN`-gated `ItemMovedToList` (the
  reroute-vs-assign precedent), but the PM's idempotent target-add is shared — so the retro's „apply
  the PM concurrency-retry from the start in the postpone-to-list PM" is satisfied by **reuse**, and
  Story 3.4's leftover transfer inherits the same seam.
- **The regression trap is status preservation (Cl. 4).** Exactly like 2.6's „an edit must carry the
  store assignment forward", an `ItemUpdated` / `ItemAssignedToStore` / `ItemRerouted` must **never**
  reset a `DONE`/`POSTPONED` item to `OPEN` — in the aggregate fold **and** the projector. Only the
  three status events write `status`.

Flow (check-off + postpone):

```
member on the trip screen (Story 3.2)
  tap a checkbox  → POST …/items/{itemId}/check-off {commandId}   (optimistic → DONE)
    → CheckOffItemHandler: load list-{id}, requireInTrip() → ShoppingList.checkOffItem → ItemCheckedOff
    KurrentDB $all ─filter list-*─▶ ShoppingListReadModelProjector.project(ItemCheckedOff)
       → itemReadModel.setStatus(itemId, DONE)   (item_read_model.status; store/name untouched, Cl. 4)

  postpone an item („Verschieben")
    ├─ „Hier vormerken"     → POST …/items/{itemId}/postpone {commandId}          → ItemPostponed (→ POSTPONED)
    ├─ an existing Open list → POST …/items/{itemId}/postpone-to-list {targetListId, commandId}
    └─ „Neue Liste"          → POST …/lists {…} (CreateShoppingList) then postpone-to-list  ── client-orchestrated
         → PostponeItemToListHandler: load source (IN_TRIP) + target (must be OPEN)
              → ShoppingList.postponeItemToList → ItemPostponedToList on the source list-{id}
         KurrentDB $all ─filter list-*─▶ ItemMoveProcessManager.onItemPostponedToList
              → idempotent AddItem on the target (deterministic id, bounded concurrency-retry, AD-10)
```

### Architecture patterns & constraints

- **AD-10 in-trip mutation on the owning aggregate + cross-aggregate via a process manager.** Status
  changes mutate the `Item` inside its `ShoppingList` root; postpone-to-list mutates only the source
  and the PM adds to the target (single writer per append — the Story 2.4 rule). [#AD-10]
- **AD-8 online load-then-append + idempotency.** Every status/postpone handler reads the list stream,
  uses the loaded version as the expected version, and appends; the PM's target-add derives its command
  id deterministically from the trigger event and retries a lost concurrency race a bounded number of
  times (already implemented — inherited by postpone-to-list). Same-status / already-present are
  convergent no-ops that skip the append. [#AD-8]
- **AD-4 CQRS, projection-only.** `item_read_model.status` is written only by the projector's status
  cases; command handlers never write it. Eventually consistent — the trip screen updates
  optimistically and reverts on failure (AR3/NFR9). [#AD-4]
- **AD-5/AD-6 no PII, no audit.** The four new events carry ids (+ item content for postpone-to-list)
  only — no `MemberId`, no *who* (mirrors `ItemRerouted`/`ItemMovedToList`); `MemberId` is used only at
  the handler seam. The item read model is queried by `household_id`. [#AD-5/#AD-6]
- **AD-11 ubiquitous language.** `checkOffItem`/`ItemCheckedOff` / `uncheckItem`/`ItemUnchecked` /
  `postponeItemInPlace`/`ItemPostponed` / `postponeItemToList`/`ItemPostponedToList`; „erledigt" /
  „Verschieben" / „Hier vormerken". Postpone ≠ Reroute (3.2, changes *where*, not status). No
  abbreviations. [#AD-11]

### The write→read wiring (Cl. 1/4) — do not miss a spot

Introducing item status touches: (1) `ItemStatus` enum + `ItemState.status` in the aggregate (folded
`OPEN` on `ItemAdded`, preserved through every non-status fold); (2) four events + their
`apply(...)` cases; (3) the renamed `ItemNotDuringTripException` (was `ItemNotReroutableException`);
(4) `V10` — `item_read_model.status` column; (5) `ItemReadModel.setStatus` + `JdbcItemReadModel`
(insert `OPEN`, `setStatus` updates, `updateItem`/`assignStore` leave it alone); (6) the projector's
four new cases (three `setStatus`, one `removeItem`) with the existing cases **unchanged**; (7)
`ItemView.status` + `ListItems.ItemSummary.status` + `TripView`'s inline summary; (8) the
`ItemMoveProcessManager` shared target-add reacting to `ItemPostponedToList`; (9) the codec
registrations. The client resolves the **progress bar** and the postpone-vs-open partition from item
`status` — computed, never stored.

### Source tree — mirror these exact files

| New/changed | Mirror | Path |
| --- | --- | --- |
| `ItemStatus` (enum) | `ListStatus` | `collaboration/domain/` |
| `ItemState.status` + folds + `checkOffItem`/`uncheckItem`/`postponeItemInPlace`/`postponeItemToList` + `setStatus` helper | (extend) `ShoppingList` (`rerouteItem`/`assignStore`) | `collaboration/domain/` |
| `ItemCheckedOff`/`ItemUnchecked`/`ItemPostponed` (events) | `ItemRerouted` | `collaboration/domain/event/` |
| `ItemPostponedToList` (event) | `ItemMovedToList` | `collaboration/domain/event/` |
| `ItemNotDuringTripException` (rename of `ItemNotReroutableException`) | (rename) | `collaboration/domain/exception/` |
| `CheckOffItem`/`UncheckItem`/`PostponeItem` + handlers | `RerouteItem` + `RerouteItemHandler` | `collaboration/application/command/` |
| `PostponeItemToList` + handler | `MoveItem` + `MoveItemHandler` (loads both lists) | `collaboration/application/command/` |
| `ItemNotDuringTripApplicationException` (rename of `…Reroutable…`) | (rename) | `collaboration/application/exception/` |
| `ItemMoveProcessManager.onItemPostponedToList` + shared `addItemToTarget` | (extend) | `collaboration/application/` |
| `CollaborationProcessManagerSubscription` route `ItemPostponedToList` | (extend) | `collaboration/adapter/out/` |
| `ItemReadModel.setStatus` / `JdbcItemReadModel` / `ItemView.status` | (extend) `assignStore` | `collaboration/domain/readmodel/`, `adapter/out/` |
| projector status cases | (extend) `ShoppingListReadModelProjector` | `collaboration/adapter/out/` |
| `ListItems.ItemSummary.status` + `TripView` inline summary | (extend) | `collaboration/application/query/` |
| `V10__item_status.sql` | `V8`/`V9` | `resources/db/migration/` |
| `ItemController` (check-off/uncheck/postpone/postpone-to-list) | (extend, mirror `reroute`/`move`) | `collaboration/adapter/in/` |
| `DomainEventJsonCodec` (4 events) | (extend) | `collaboration/adapter/out/` |
| `Item.status` + `ItemsApi` mutations | (extend) `item.dart`, `items_api.dart` | `app/lib/features/lists/data/` |
| `TripCubit`/`TripState` status + progress + postpone | (extend) | `app/lib/features/trips/presentation/` |
| `trip_screen.dart` checkbox + progress + postpone affordance | (extend) | `app/lib/features/trips/presentation/` |
| postpone target sheet | (reuse/mirror) `move_target_sheet.dart` | `app/lib/features/lists/presentation/list_detail/` |

### Package structure (CLAUDE.md §8)

New backend classes drop into the **existing** intent subpackages (`domain`, `domain.event`,
`domain.exception`, `application` (the PM), `application.command` with the DTO beside its handler,
`application.exception`, `application.query`, `adapter.out`, `adapter.in`). No **new** controller (the
four endpoints extend `ItemController`) and no **new** Flutter feature package (the trip presentation
extends `features/trips/`; the postpone sheet reuses `features/lists/`). No ArchUnit rule change (rules
match `..domain..`/`..application..`; the controller imports no `..domain..`).

### Testing standards

- **Domain first (fast, pure, no infra):** `ShoppingListTest` — check-off/uncheck/postpone-in-place
  raise + fold; `IN_TRIP`-gated (Open list → `ItemNotDuringTripException`); same-status no-op; unknown
  item; **status preserved through reroute/update** (Cl. 4); postpone-to-list raises + removes.
- **Handlers (in-memory `EventStore` + fake `ResolveMemberIdentity`):** three status handler tests +
  `PostponeItemToListHandlerTest` (403/400/400-self/404×3/409-not-In-Trip/409-target-not-Open/no-op).
- **Process manager (in-memory `EventStore`):** `ItemMoveProcessManagerTest` — `ItemPostponedToList`
  adds to the target exactly once; deterministic-id re-delivery no-ops; the target-Open / duplicate
  race safety nets behave.
- **Projector/read model (Testcontainers):** `ShoppingListReadModelProjectorTest` — the three status
  cases write `status`; `ItemPostponedToList` removes; **reroute/update after a check leave `status`
  untouched** (Cl. 4); **isolation + replay idempotency** for the status column, same change (Action 10).
  `ListItemsTest`/`TripViewTest` — `status` present (OPEN off-trip; DONE in the trip view).
- **Controller (MockMvc):** `ItemControllerTest` — the four endpoints 200/400/403/404/409(+self/target)
  — double as the Action-2 error-advice contract. `DomainEventJsonCodecTest` — four events round-trip,
  stable tags.
- **Flutter (fakes only, no network):** `items_api` request shapes; `TripCubit` (optimistic
  check/uncheck/postpone-in-place/postpone-to-list + revert + intent freshen + `isSubmitting`;
  `doneCount`/`totalCount`); `trip_screen` (checkbox, progress bar, postpone affordance; **no
  „abschließen"**, Cl. 2 guard); the postpone target sheet (Open-only + „Neue Liste" + „Hier
  vormerken"; 2.4 regression).
- **DSGVO:** synthetic German data only; explicit no-PII stance on the four events (AC5).
- **Green build = full suite** for both modules; state which ran and the counts (baseline backend 507 /
  Flutter 462).

### Deferred / do-not-build (premature-value discipline)

- **Trip completion (`TripStatus.DONE`, list → Done + clear `active_trip_id`, „Einkauf abschließen",
  leftover review)** → **Story 3.4**, which also lands the deferred **cached Done-archive invalidation**
  (retro Action 11) and the reachable **Done-rename** test. Cl. 2.
- **Print / share the grouped list** → **Story 3.5**. Cl. 2.
- **Live-sync of check-off/postpone across devices** → **Epic 4** (SSE). MVP: the actor sees their own
  changes optimistically; peers refetch on open.
- **Un-postpone-in-place as its own verb** — folded into `uncheck` (POSTPONED → OPEN, Cl. 1); a
  dedicated event is YAGNI unless a distinct „un-vormerken" affordance is wanted later.
- **A dedicated „Vorgemerkt"/„Verschoben" grouping vs. an in-line dimmed row** — the exact set-aside
  visual for `POSTPONED` is a client design detail (Cl. 7); ship the simplest correct treatment.
- **Renaming `ItemMoveProcessManager` → `ItemTransferProcessManager`** — optional Boy-Scout; deferred
  to limit wiring/test churn (Cl. 3).

### Project Structure Notes

- The status endpoints are item-scoped (`POST …/lists/{listId}/items/{itemId}/{action}`) extending the
  3.2 `ItemController`; postpone-to-list carries `{targetListId, commandId}` (mirror `move`). All
  return 200 (the reroute convention for the in-trip family, Cl. 8).
- `V10` is **additive** (one `ALTER` with a `DEFAULT 'OPEN'`); no backfill, no rewrite of V5–V9.
  Existing rows read as `status = 'OPEN'`.
- No new subscription/projector — the status projections extend the existing list projector, and the
  postpone-to-list PM extends the existing `list-`-prefix process-manager subscription.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story-3.3] — user story + BDD ACs (check/uncheck → Done/Open; postpone → existing/new list or in place; only Open targets; PM idempotent add).
- [Source: _bmad-output/planning-artifacts/epics.md#Story-3.4/3.5] — completion + leftover transfer, print — this story defers to (and the leftover-transfer PM that reuses 3.3's postpone-to-list seam).
- [Source: ARCHITECTURE-SPINE.md #AD-3/#AD-4/#AD-8/#AD-10/#AD-11] — reference-by-id, projection-only, load-then-append + idempotency, in-trip mutation on the owning aggregate + cross-aggregate PM, ubiquitous language.
- [Source: ux-designs/ux-sgart-2026-08-20/EXPERIENCE.md §3 „Active trip"; DESIGN.md UX-DR7; .working/screen-active-trip.html] — per-item checkbox → „erledigt", the „N von M erledigt" progress bar, the ⋯ menu, the list-is-hero non-sticky layout.
- [Source: backend `ShoppingList.java` (`rerouteItem`/`requireInTrip`/`assignStore`/`apply`/`ItemState`), `ItemRerouted.java`, `ItemMovedToList.java`, `RerouteItemHandler.java`, `MoveItemHandler.java`, `ItemMoveProcessManager.java`, `CollaborationProcessManagerSubscription.java`, `ItemController.java`, `ShoppingListReadModelProjector.java`, `JdbcItemReadModel.java`, `ItemReadModel.java`, `ItemView.java`, `ListItems.java`, `TripView.java`, `DomainEventJsonCodec.java`, `ItemNotReroutableException.java`, `WriteErrorAdvice.java`, `V9__trip_read_model.sql`] — every pattern to mirror.
- [Source: app `features/lists/data/item.dart`, `items_api.dart`, `features/trips/presentation/trip_cubit.dart`/`trip_state.dart`/`trip_screen.dart`, `features/lists/presentation/list_detail/move_target_sheet.dart` (+ its „Neue Liste" two-step), `shared/commands/command_intent.dart`, `theme/tokens/sgart_colors.dart` (Done/purchased tint)] — the client patterns to mirror.
- [Source: _bmad-output/implementation-artifacts/3-2-…md] — the in-trip `requireInTrip`/`rerouteItem`/`ItemRerouted` pattern this story extends; the trip screen + `TripView` it builds on.
- [Source: _bmad-output/implementation-artifacts/2-4-…md] — the cross-list move (`moveItem`/`ItemMovedToList`/`ItemMoveProcessManager`/`MoveTargetNotOpenException`/„Neue Liste") this story mirrors for postpone-to-list.
- [Source: _bmad-output/implementation-artifacts/deferred-work.md] — „progress bar (checked/total) lands in Epic 3 with check-off"; „DONE-rejects-rename / read-only-archive e2e … Epic 3"; „Cached Done archive invalidation … Epic 3" (3.4).
- [Source: _bmad-output/implementation-artifacts/epic-2-retro-2026-08-28.md §6 + sprint-status open action items] — carried actions 2/3/7/8/9/10/12 baked into Cl. 9 + the DoD tasks.
- [Source: CLAUDE.md §1–§8] — Clean Code, naming, DDD, CQRS, DSGVO, testing, package structure.

## Dev Agent Record

### Agent Model Used

Claude Sonnet 4.6 (implementation), Claude Opus 4.8 (planning/review)

### Debug Log References

### Completion Notes List

- `postpone_target_sheet.dart` is a new file (not sharing `move_target_sheet.dart`) because the
  postpone affordance is IN_TRIP-gated and excludes IN_TRIP lists as targets — different enough from
  the OPEN-gated planning move to warrant its own sheet.
- `SgartShapes.spaceHalfUnit` (2px) used as the gap between the progress text and the
  `LinearProgressIndicator` in `_ProgressHeader` — `SgartShapes.space1` does not exist in the token
  set.
- `ShoppingListsApi` added to `TripScreen.push()` provider chain so the postpone sheet can read open
  lists; this required `RepositoryProvider<ShoppingListsApi>` to be added to the widget trees in
  `active_trips_view_test.dart` and `list_detail_page_test.dart` as well.
- POSTPONED items count in `totalCount` (M) but not `doneCount` (N) in the progress bar, per Cl. 7.
- Optimistic updates implemented via shared `_applyStatusChange()` helper in `TripCubit`;
  `postponeToList()` has its own path (optimistic removal, not a status flip).

### File List

**Backend (unchanged from Task 1–16 baseline, commit a1b2a87 + 3.3 backend work)**

**Flutter — new files**
- `app/lib/features/trips/presentation/postpone_target_sheet.dart`
- `app/test/features/trips/presentation/postpone_target_sheet_test.dart`

**Flutter — modified files**
- `app/lib/features/lists/data/item.dart` — added `status` field (default `'OPEN'`)
- `app/lib/features/lists/data/items_api.dart` — added 4 new mutation methods
- `app/lib/features/trips/presentation/trip_state.dart` — added `doneCount`/`totalCount` getters
- `app/lib/features/trips/presentation/trip_cubit.dart` — added status-change commands + 4 `CommandIntent` fields
- `app/lib/features/trips/presentation/trip_screen.dart` — progress bar, checkboxes, done treatment, postpone affordance
- `app/lib/l10n/app_de.arb` — 8 new localization strings
- `app/test/features/lists/data/item_test.dart` — 2 new tests for status parsing
- `app/test/features/trips/presentation/trip_cubit_test.dart` — 8 new tests
- `app/test/features/trips/presentation/trip_screen_test.dart` — 5 new tests, 1 test updated
- `app/test/features/trips/presentation/active_trips_view_test.dart` — added `ShoppingListsApi` provider
- `app/test/features/lists/presentation/list_detail/list_detail_page_test.dart` — added `ShoppingListsApi` provider
- `app/test/support/fake_items_dependencies.dart` — 4 new method stubs + tracking fields

## Change Log

- 2026-08-29: Flutter implementation complete (Tasks 17–23, Sonnet 4.6). Added `status` to `Item`,
  extended `ItemsApi` with 4 new mutation methods (`checkOffItem`, `uncheckItem`, `postponeItem`,
  `postponeItemToList`), added `doneCount`/`totalCount` to `TripState`, added 4 `CommandIntent`
  fields + `checkOff`/`uncheck`/`postponeInPlace`/`postponeToList` to `TripCubit`, rewrote
  `TripScreen` with progress bar + checkboxes + done treatment + postpone affordance, created
  `PostponeTargetSheet`, added 8 ARB strings. Full green build: backend 537 tests, Flutter 483 tests.
- 2026-08-29: Story drafted (create-story, Opus 4.8) — Epic 3's third story: introduces SGART's first
  **item status** (`ItemStatus { OPEN, DONE, POSTPONED }` on the list's `Item`), the **in-trip status
  mutations** (`checkOffItem`/`uncheckItem`/`postponeItemInPlace` → `ItemCheckedOff`/`ItemUnchecked`/
  `ItemPostponed`, `IN_TRIP`-gated on `ShoppingList`, extending 3.2's `rerouteItem` pattern), and
  **all three postpone variants** — in-place + to-existing + to-new — where postpone-to-list is a new
  `IN_TRIP`-gated `ItemPostponedToList` converging on the existing `ItemMoveProcessManager` (reused by
  Story 3.4's leftover transfer). Trip screen gains the checkbox, the „N von M erledigt" progress bar
  (deferred since 2.2), and a postpone target picker (Open lists + „Neue Liste" + „Hier vormerken").
  9 LOCKED clarifications; **Timo decided (2026-08-29): (1) build all three postpone variants in 3.3;
  (2) in-trip postpone-to-list as a new `ItemPostponedToList` event distinct from the OPEN-gated
  `ItemMovedToList`, converging on the existing move process manager (not by relaxing `moveItem`).**
  Item-status regression trap (Cl. 4): every non-status fold + projection preserves `status`; only the
  status events write it. Epic-2 retro actions 2/3/7/8/9/10/12 baked into the DoD (Cl. 9); completion +
  Done-archive invalidation + Done-rename test stay with Story 3.4 (Cl. 2). Baseline backend 507 /
  Flutter 462.
