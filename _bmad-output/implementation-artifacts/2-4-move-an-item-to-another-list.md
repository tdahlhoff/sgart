---
baseline_commit: e19e30f0ec23f8ea3bcce76016ee59f3cab3c1fe
---

# Story 2.4: Move an item to another list

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a member,
I want to move an item from an Open list to another (existing or new) Open list while planning,
so that I can reorganize my parallel lists without any buying/trip context.

## Acceptance Criteria

Derived from **epics.md § Story 2.4** (FR5), refined against **ARCHITECTURE-SPINE.md** (AD-4, AD-8,
**AD-10 — the process-manager rule this story realises for the first time**, AD-11) and the Story
2.3 item slice this builds on. Each AC is independently testable.

1. **Move to an existing Open list, cross-aggregate via a process manager (AC1).** Given an item on
   an **Open** source list, when a member moves it to another **existing Open** list, then the item
   is **removed from the source** by a `MoveItem` command that raises **`ItemMovedToList`** on the
   source stream, and **added to the target** by a **process manager** that reacts to that event and
   issues an **idempotent `AddItem`** on the target list (AD-10). The item **stays Open** — a move
   is never a status change. The two aggregates are mutated in **two separate appends** (source, then
   target) — never one cross-stream transaction (the `EventStore` appends to one stream only, AD-4/AD-8).

2. **The process-manager add is exactly-once under replay (AC2).** The process manager derives the
   `AddItem` command id **deterministically from the triggering `ItemMovedToList` event id**
   (`CommandId.deterministicFrom(EventId)`), so re-processing the event on a subscription restart or
   catch-up replay **never double-adds** to the target — the `EventStore`'s command-id idempotency
   collapses the redelivered add to a silent no-op (AD-10, AD-8). The moved item **keeps its
   `ItemId`** across the move (same entity identity carried in the event payload).

3. **Move to a new list = client two-step (AC3).** Given a member moving an item and choosing a
   **new** list as the target, then the client **creates the list first** (the existing
   `CreateShoppingList` command — client-minted `listId`), **then** issues the move to that new
   `listId`. There is **no** server-side compound "move-to-new-list" command (KISS/read-your-writes:
   the client already mints the id and orchestrates create-then-move).

4. **Same name+note on the target = tell the member, offer a quantity merge, never a duplicate (AC4,
   E2).** Given the member picks a target that **already holds** an item with the **same (name, note)**
   key (trimmed, case-insensitive — the Story 2.3 uniqueness rule, FR5), then the **client detects the
   collision** (it reads the target's items), **tells the member** the article is already on that
   list, and **asks whether to adjust the quantity**. The target's **existing** article is **kept**
   (no duplicate is ever created); if the member chooses to adjust, the client issues an
   **`UpdateItem` on the target** (the merged quantity — see Clarification 3), and either way the
   article is **removed from the source** (an `UpdateItem`(target) + `RemoveItem`(source), both
   existing Story-2.3 commands — **no** `MoveItem`/process-manager path for this branch). Server-side
   race safety net: if a stale pre-check nonetheless routes a colliding item down the clean-move path,
   the process manager **swallows the resulting `DuplicateItemException` as convergent success** — no
   duplicate is created and the source removal still stands (the quantity prompt simply does not
   appear in that rare race).

5. **Only Open lists are valid move targets (AC5, E3).** Given the pick-a-target step, when the
   member picks a target, then **only Open lists** are offered — the source list itself is excluded,
   and **Done / In-Trip lists are never offered**. Server-side (defense-in-depth), a move whose
   target list is **not Open** is **rejected (409, `list.moveTargetNotOpen`)** by `MoveItemHandler`
   **before** the source is mutated — so a stale client can never strand an item by moving it onto a
   non-Open list. A move whose target **equals the source** is a fail-fast **400
   (`list.moveTargetSameAsSource`)**. As in Story 2.3 AC5, a **reachable** non-Open target
   (Done/In-Trip) is **unreachable in Epic 2** (no Epic-2 event drives a list out of `OPEN`); the
   guard is coded and unit-tested against a **synthetic** non-Open target, and the reachable
   end-to-end test is **deferred to Epic 3**.

6. **Off-trip an item is always Open — no check/uncheck, no postpone (AC6).** Given an Open list
   off-trip, when a member views item actions, then the row exposes **only** edit / remove / **move**
   — there is **no check/uncheck and no postpone** (buying → Done and deferral → Postpone are
   trip-only, CAP-9/CAP-10, Epic 3). Those affordances must **not** appear. This reaffirms the Story
   2.3 boundary; 2.4 adds exactly one new affordance (move) and nothing trip-shaped.

7. **Move UI on the list detail screen (AC7).** On the Story 2.3 list detail screen, each item row on
   an **Open** list gains a **move affordance**; tapping it opens a **target picker** listing the
   household's **other Open lists** (source excluded) plus a **„＋ Neue Liste"** option. Picking
   „Neue Liste" prompts for a name, creates the list, then moves. When the picked target **already
   holds** the article (name+note), a **quantity-merge dialog** appears (AC4) — otherwise the item
   moves straight through. The move is **optimistic** (the row disappears from the source detail
   immediately) with an inline **error + revert** on rejection. A **Done** list stays **read-only** —
   no move affordance renders (mirrors 2.3's read-only Done detail).

8. **Membership, isolation, envelope & concurrency (AC8).** The move endpoint is **membership-gated**
   (non-member → **403**); a **source** or **target** `listId` under a **different** household is a
   **404** (defense-in-depth, like `AddItemHandler`); a malformed id/envelope is **400**; the source
   append uses the **loaded** source-list-stream version as the expected version (online
   load-then-append, AD-8) and a lost race is **409**; the `commandId` makes a client retry of the
   move **idempotent**, and the process-manager add is idempotent by its derived id (AC2).

9. **No personal data (AC9).** `ItemMovedToList` carries **no** `MemberId`, creator attribution,
   display name, email, or `keycloakUserId` (AD-5/AD-6; no audit trail in MVP, YAGNI — mirrors
   `ItemAdded`/`ItemRemoved`). It carries `household_id` + source/target `list_id` so Epic-6 erasure
   can still locate the read-model rows it moves between. Tests use synthetic, clearly-fake data only.

## Clarifications (LOCKED)

These decisions are locked for implementation. They were taken from the epic AC + the existing
2.3/AD-10 patterns; **if any is wrong, correct it before `dev-story`.**

1. **The move is one source-side event (`ItemMovedToList`) + a process-manager-issued `AddItem` on
   the target — this is the story's core architectural step.** SGART's first **process manager**
   (AD-10). The source `ShoppingList.moveItem(...)` raises `ItemMovedToList` (folds to *remove* the
   item from the source, exactly like `ItemRemoved`) carrying the full item payload
   (`householdId, sourceListId, itemId, targetListId, name, note, quantity`). A new
   `ItemMoveProcessManager` (application) reacts to that event and issues `AddItem` on the **target**
   list. The target add is a **normal `ItemAdded`** — no new target-side event type, no new
   target-facing aggregate method (**reuse the existing `ShoppingList.addItem`**). Mirror the
   `ShoppingListReadModelProjector`'s KurrentDB `SmartLifecycle` subscription for the PM's transport
   (a second, independent subscription over the `list-` prefix).

2. **Process-manager exactly-once = `CommandId.deterministicFrom(eventId)`; the item keeps its
   `ItemId`.** The kernel already ships `CommandId.deterministicFrom(EventId)` (name-based UUIDv5)
   built for exactly this (see its Javadoc — "the exactly-once mechanism for process managers,
   AD-10"). The PM uses `CommandId.deterministicFrom(moved.eventId())` for its `AddItem`, so
   redelivery/replay dedupes at the `EventStore`. The moved item's **`ItemId` is preserved** across
   the move (carried in the event → same id in the target's `ItemAdded`) — trivially replay-stable
   (no new id derivation) and matches the "this item moved" mental model.

3. **E2 target collision = client-orchestrated quantity-merge, decided by the member (Timo's call,
   2026-08-27).** The AC permits "rejected **/** deduped"; SGART does **neither silently** — it
   **asks the member**. Because the merge is interactive (a quantity prompt), a process manager
   cannot own it (the PM has no member to ask); so the **collision branch is client-orchestrated**
   with the existing Story-2.3 commands:
   - When the member picks a target, the client **reads the target's items**
     (`ItemsApi.listItems(targetListId)`) and checks for a **(name, note)** match (trimmed,
     case-insensitive — mirror the aggregate's `hasItemKeyed`).
   - **On a match:** show a dialog — „‚{name}' ist schon auf ‚{targetList}' ({targetQuantity}). Menge
     anpassen?" — with an editable quantity (amount + unit, reusing the `item_form_sheet` controls),
     **pre-filled with the sum** of source + target quantities **when the units match**, else the
     target's current quantity. The member confirms an adjustment, keeps the target quantity
     unchanged, or cancels. On confirm-with-adjustment → **`UpdateItem`(target, new quantity)** then
     **`RemoveItem`(source)**; on confirm-unchanged → just **`RemoveItem`(source)**. The target's
     **existing** article is always kept; **no duplicate** is ever created. (Order: update the target
     first, then remove the source — a failure never strands the article on neither list.)
   - **On no match:** the clean-move path (`MoveItem` → `ItemMovedToList` → PM `AddItem`, Cl. 1).
   - **Server race safety net:** the pre-check is eventually-consistent; if a colliding item is
     nonetheless sent down the clean-move path, the PM **swallows the `DuplicateItemException` as
     convergent success** (no duplicate, source removal stands; the quantity prompt just does not
     appear in that rare race). This is why the handler needs **no** cross-aggregate name+note bridge.

4. **Target validity (E3) is enforced by loading the target aggregate in `MoveItemHandler` before
   the source is mutated.** The handler loads **both** the source and the **target** `ShoppingList`
   aggregates. Loading the target root is normal (AD-10 forbids loading an *Item* outside its list,
   and *mutating* another aggregate directly — reading the target root for a routing decision, then
   mutating it only via the PM's command, honors that). Handler order: translate → `targetListId !=
   sourceListId` else **400** → resolve membership (403) → load source (empty/cross-household → 404)
   → load target (empty/cross-household → 404) → `target.status() != OPEN` → **409
   `list.moveTargetNotOpen`** → `source.moveItem(itemId, targetListId, commandId)` (source item
   unknown → 404) → append to the **source** stream under the source's loaded version + `commandId`.
   The target is **never** appended-to by the handler — that is the PM's job (single writer per append).

5. **`ItemMovedToList` rides the existing `list-{id}` stream, the existing projector, and the
   existing codec.** No new `StreamType` (KISS): the event flows through the projector's `list-`
   subscription and the PM's (new) `list-` subscription alike. Extend `ShoppingListReadModelProjector`
   (`ItemMovedToList` → `itemReadModel.removeItem(itemId)` on the **source**; the target insert
   arrives via the PM's `ItemAdded`) and `DomainEventJsonCodec` (type tag + payload record + round-trip).
   **No new migration** — `item_read_model` (V6) is unchanged; a move is a `removeItem` on the source
   followed by an `insertItem` on the target (both already exist on `JdbcItemReadModel`).

6. **No premature surfaces.** Still no autocomplete/attribute-prefill (Story 2.5), no store
   assignment (Story 2.6), no check-off/postpone/progress (Epic 3). 2.4 adds **only** the move
   affordance + target picker. Done lists stay read-only. Respects the Epic-1 retro premature-value rule.

## Tasks / Subtasks

> Follow TDD (CLAUDE.md §6): failing test first at the lowest level that proves the behaviour, then
> the simplest code to pass. Keep the domain pure (AD-1). Mirror the cited existing file for every
> new class — the patterns are established (Story 2.3); do not invent new ones. The **process
> manager** is the one genuinely new pattern — its Dev Notes section below is the spec for it.

### Backend — domain (AC1, AC5, AC9)

- [x] **Task 1: `ItemMovedToList` domain event** — package `collaboration.domain.event`
  - [x] `ItemMovedToList(EventId, HouseholdId householdId, ShoppingListId sourceListId, ItemId itemId,
        ShoppingListId targetListId, ItemName name, ItemNote note /*nullable*/, Quantity quantity)`
        `implements DomainEvent`. `Objects.requireNonNull` on every component **except `note`**
        (nullable-by-absence, like `ItemAdded`). Javadoc: records a planning-time move; folds to a
        *removal* on the source; the target add is a separate `ItemAdded` raised by the process
        manager (AD-10); no creator / no PII (mirror `ItemAdded`).
- [x] **Task 2: `ShoppingList.moveItem(...)`** (AC1, AC5, AC9)
  - [x] `public void moveItem(ItemId itemId, ShoppingListId targetListId, CommandId commandId)`:
        null-checks; `requireOpen()` (source must be Open, else `ItemChangeNotPermittedException`);
        look up `itemsById.get(itemId)` — unknown → `ItemNotFoundException`; raise `ItemMovedToList`
        carrying the found item's `name`, `note`, `quantity` (from its `ItemState`) plus
        `householdId`, `this.listId` as `sourceListId`, and `targetListId`. Do **not** validate the
        target here (the source aggregate does not own the target — that is the handler's job, Cl. 4).
  - [x] `apply(...)`: add `case ItemMovedToList moved -> itemsById.remove(moved.itemId())` (same fold
        as `ItemRemoved` — the item leaves the source aggregate).
  - [x] Extend `ShoppingListItemsTest`: move raises `ItemMovedToList` with the exact payload;
        move of an unknown item throws `ItemNotFoundException`; **replay** `[…, ItemMovedToList]`
        rebuilds state with the item gone; no-PII assertion for `ItemMovedToList`. **Deviation:** the
        non-Open (synthetic `DONE`) source branch is coded but not unit-tested — no synthetic Epic-3
        event exists to drive the aggregate into `DONE` (identical, pre-existing constraint documented
        in this same test class for add/update/remove); see Completion Notes.

### Backend — application: command + handler (AC1, AC5, AC8)

- [x] **Task 3: `MoveItem` command DTO** — package `application.command`, beside its handler
  - [x] `MoveItem(ShoppingListId sourceListId, ItemId itemId, ShoppingListId targetListId,
        CommandId commandId, AggregateVersion basedOnVersion) implements Command`. `requireNonNull`
        all (none nullable). Javadoc mirrors `AddItem`: `basedOnVersion` is the **source** list's
        loaded stream version (AD-8).
- [x] **Task 4: application exceptions** (AC5) — package `application.exception`
  - [x] `MoveTargetNotOpenException` (→ **409**, code `list.moveTargetNotOpen`) and
        `InvalidMoveTargetException` (→ **400**, code `list.moveTargetSameAsSource`) — mirror the
        existing `*ApplicationException` types (each wraps an `ErrorDescriptor` with a client code).
        Reuse `ShoppingListNotFoundException` (404), `ItemNotFoundApplicationException` (404),
        `ItemChangeNotPermittedApplicationException` (403 — non-Open **source**) — **do not** add new
        types for those.
- [x] **Task 5: `MoveItemHandler`** (AC1, AC5, AC8) — mirror `AddItemHandler`
  - [x] Signature: `handle(String keycloakUserId, String rawHouseholdId, String rawSourceListId,
        String rawItemId, String rawTargetListId, String rawCommandId)`.
  - [x] Exact order (Cl. 4): translate raw fields → **if `targetListId.equals(sourceListId)` throw
        `InvalidMoveTargetException` (400)** → `resolveMemberIdentity.resolve(...)` (403) → load
        **source** by `StreamId.forList(sourceListId)`; empty **or** `householdId` mismatch →
        `ShoppingListNotFoundException` (404) → load **target** by `StreamId.forList(targetListId)`;
        empty **or** `householdId` mismatch → `ShoppingListNotFoundException` (404) → **if
        `target.status() != ListStatus.OPEN` throw `MoveTargetNotOpenException` (409)** → capture
        source's loaded version → `source.moveItem(itemId, targetListId, commandId)` (catch
        `ItemChangeNotPermittedException` → 403, `ItemNotFoundException` → 404 application types) →
        `eventStore.append(sourceLoadedVersion, source.uncommittedEvents(), commandId)`.
  - [x] Handler tests (`InMemoryEventStore` + fake `ResolveMemberIdentity`): success removes the item
        from the source & raises `ItemMovedToList`; non-member 403; **source**-not-found 404;
        **target**-not-found 404; **cross-household source** 404; **cross-household target** 404;
        same-list 400; unknown source item 404; concurrent source write 409. Assert the handler appends
        to the **source** stream only (the target add is the PM's job — proven in Task 6, not here).
        **Deviation:** the non-Open (synthetic `DONE`) target 409 branch is coded but not
        unit-tested — same constraint as above (no synthetic Epic-3 event); see Completion Notes.

### Backend — application: the process manager (AC1, AC2, AC4) — **the new pattern**

- [x] **Task 6: `ItemMoveProcessManager`** — package `application` (per §8 module layout:
      `application/` holds "command handlers, query handlers, **process managers**")
  - [x] Constructor takes the `EventStore` port only (no `ResolveMemberIdentity` — a move is already
        authorized by `MoveItemHandler`'s membership check; the PM acts on the system's behalf, **not**
        a caller, so it does **no** membership resolution).
  - [x] `public void onItemMovedToList(ItemMovedToList moved)`: load the **target** by
        `StreamId.forList(moved.targetListId())`; if the target stream is empty **skip + log**
        (target vanished — Epic-2-unreachable, defensive); else rehydrate, capture the target's loaded
        version, and call `target.addItem(moved.itemId(), moved.name(), moved.note(),
        moved.quantity(), CommandId.deterministicFrom(moved.eventId()))`, then
        `eventStore.append(targetLoadedVersion, target.uncommittedEvents(), derivedCommandId)`.
  - [x] **Swallow, do not propagate:** catch `DuplicateItemException` → **convergent success**, log at
        debug, append nothing — this is the **race safety net** for a stale client pre-check (AC4 /
        Cl. 3); the common collision is handled client-side with the quantity prompt, so this branch is
        rarely hit. Catch `ItemChangeNotPermittedException` (target no longer Open — Epic-2-unreachable)
        → **log at warn, append nothing** (compensation is Epic-3's, see Deferred). Let
        `ConcurrencyConflictException` propagate to the subscription's log-and-skip (a later catch-up
        replay retries with the same derived id → idempotent).
  - [x] Tests (`InMemoryEventStore`, no infra): `onItemMovedToList` appends `ItemAdded` to the target
        with the derived command id; **processing the same event twice appends only once**
        (idempotency via `deterministicFrom` + the store's command-id dedupe — assert the target
        stream has one `ItemAdded`); a target that already has the (name,note) → **no append, no
        throw** (dedupe); a vanished target stream → no append, no throw (logged).

### Backend — adapter.out: PM subscription + projector + codec (AC1, AC2)

- [x] **Task 7: `CollaborationProcessManagerSubscription`** (or similarly named) — `adapter.out`,
      **mirror `ShoppingListReadModelProjector` structurally**
  - [x] A `SmartLifecycle` bean, auto-start gated by the same flag pattern (default off; construction
        does no I/O so `contextLoads()` survives KurrentDB down). Subscribe to `subscribeToAll` with a
        `SubscriptionFilter` on the `LIST` stream prefix (a **second, independent** subscription from
        the projector's — same prefix, different consumer). On each event: decode via
        `DomainEventJsonCodec`; **only** `ItemMovedToList` triggers `processManager.onItemMovedToList(...)`
        (every other event type is ignored). Same `onCancelled` → resubscribe-with-delay and
        per-event try/catch **log-and-skip** as the projector (replay recovers via the derived-id
        idempotency).
  - [x] Wire the PM bean in `CollaborationApplicationConfig` and the subscription lifecycle bean in a
        new `CollaborationProcessManagerConfig` (mirror how `CollaborationReadModelConfig` wires the
        projector). Keep the flag consistent with the projector's auto-start flag.
- [x] **Task 8: projector + codec extension** (AC1)
  - [x] `ShoppingListReadModelProjector.project(...)`: add
        `case ItemMovedToList moved -> itemReadModel.removeItem(moved.itemId())` (source removal; the
        target insert arrives as the PM's `ItemAdded`, already handled). No new read-model method.
  - [x] `DomainEventJsonCodec`: add the `ItemMovedToList` type tag + payload record +
        `toJsonBytes`/`fromJsonBytes` (note nullable; `amount` as `String` via `toPlainString`; `unit`
        as enum name; `householdId`, `sourceListId`, `targetListId`, `itemId` as UUID strings). Mirror
        `ItemAddedPayload`.
  - [x] Extend `DomainEventJsonCodecTest` (round-trip incl. null note) and
        `ShoppingListReadModelProjectorTest` (Testcontainers): `ItemMovedToList` removes the source
        read-model row; an **end-to-end move** (source `ItemMovedToList` + target `ItemAdded`) leaves
        exactly one row, under the **target** `list_id`, same `item_id` (AC2 identity preserved).

### Backend — adapter.in + ArchUnit (AC7, AC8)

- [x] **Task 9: `ItemController` move endpoint + error advice**
  - [x] Add to the existing `ItemController` (`/api/v1/households/{householdId}/lists/{listId}/items`):
        `@PostMapping("/{itemId}/move")` → **204**, body `MoveItemRequest(targetListId, commandId)`,
        path `listId` is the **source**. Identity from the JWT `sub` via `AuthenticatedCaller`
        (AR10/AD-5). Mirror the existing `POST`/`DELETE` handlers.
  - [x] `WriteErrorAdvice`: map `MoveTargetNotOpenException` → **409**, `InvalidMoveTargetException` →
        **400** (the reused 404/403/409 types already map).
  - [x] `ItemControllerTest` (MockMvc slice): move happy path (204) + each new/reused error status
        (400 same-list, 404 source/target-not-found, 403 non-member). Mirror the existing item cases.
        **Deviation:** the 409 target-not-open branch is not exercised at this level either, same
        synthetic-DONE constraint; see Completion Notes.
- [x] **Task 10: ArchUnit** — run `HexagonalArchitectureTest`; confirm the new `application`
      process-manager class and `adapter.out` subscription need **no** rule change (PM is `..application..`;
      subscription is `adapter.out` and imports the application PM + domain event, both allowed
      inward), and `adapter.in` still imports no `..domain..`.

### Flutter — move affordance + target picker (AC3, AC4, AC6, AC7)

- [x] **Task 11: item data layer** — `features/lists/data/items_api.dart`
  - [x] Added `Future<void> moveItem(String householdId, String listId, String itemId, {required
        String targetListId, required String commandId})` (both trailing ids named, matching
        `addItem`/`updateItem`'s own style on this interface — a minor signature deviation from the
        story's fully-positional draft) → `POST
        .../lists/{sourceListId}/items/{itemId}/move` with `{targetListId, commandId}`. Mirror the
        existing `addItem`/`removeItem` methods on `HttpItemsApi`; extend `FakeItemsApi`.
        **Deviation:** no dedicated `items_api_test.dart` — the codebase has no direct `Http*Api`
        test for any feature (verified: `HttpShoppingListsApi`/`HttpItemsApi` are untested at that
        layer everywhere); `FakeItemsApi` + the cubit tests are the established test surface.
- [x] **Task 12: list detail cubit — clean move + collision merge** — `list_detail_cubit.dart`
  - [x] `Future<void> moveItem(String itemId, String targetListId)` (**clean, non-collision path**):
        **optimistic** removal of the row from the source detail state + inline `actionError` +
        **revert** on failure + `isClosed` guards + the shared `isSubmitting` serialization guard (as
        `removeItem` got in 2.3 review). Use a `CommandIntent` keyed on `(itemId, targetListId)` —
        reused on retry, freshened after success (mirror the add/update/remove intents).
  - [x] `Future<void> mergeIntoTarget(...)` (**collision path, Cl. 3**): update-then-remove, optimistic
        source removal + revert on failure. Each leg carries its own `CommandIntent`. **Never create a
        duplicate** — this path never adds to the target. **Deviation:** signature is
        `mergeIntoTarget(String sourceItemId, String targetListId, Item targetItem, {String?
        adjustedAmount, String? adjustedUnit})`, not `..., String targetItemId, Quantity?
        adjustedQuantity)` — this codebase has no Dart `Quantity` type (quantities are plain
        `amount`/`unit` strings, see `Item`/`ItemsApi`); taking the full `targetItem` (not just its id)
        also gives the update call the target's existing name/note it must preserve. `null`/`null`
        amount+unit = "no adjustment" (maps to the spec's `adjustedQuantity == null`).
  - [x] Collision detection helper `findCollisionOnTarget(Item item, String targetListId)`: fetches
        `itemsApi.listItems(householdId, targetListId)` and returns any item whose (name, note)
        matches (trimmed, case-insensitive — mirrors the aggregate's `hasItemKeyed`). Used by the UI to
        branch clean-move vs merge.
- [x] **Task 13: move UI — affordance, target picker, quantity-merge dialog** — `features/lists/presentation`
  - [x] `_ItemRow`: added a **move** trailing action (`Icons.drive_file_move_outline`, keyed
        `item-move-button-{itemId}`) — **Open lists only**; a read-only Done row renders **no** move
        affordance (same `isReadOnly` guard as edit/remove).
  - [x] `move_target_sheet.dart` (new): a modal sheet listing the **other Open lists** (source
        excluded; each row's "Liste N" fallback uses the ordinal from the *unfiltered* list so it
        matches the overview's own numbering) with a **„＋ Neue Liste"** entry and an empty state.
        Picking „Neue Liste" prompts for a name (own self-contained sheet, `_NewListNameSheetBody") →
        `shoppingListsApi.createList(...)` (client-minted `listId`+`commandId`) → moves to it (AC3
        two-step; a brand-new list never collides → clean move).
  - [x] On a chosen **existing** target: runs the collision helper. **No match** →
        `cubit.moveItem(...)` (clean move). **Match** → opens `move_merge_dialog.dart` (new) with an
        editable **amount + unit** (reusing the `item_form_sheet` quantity-control pattern),
        **pre-filled with the sum when units match**, else the target's current quantity; two actions
        („Menge aktualisieren & verschieben" / „Unverändert übernehmen"). **Deviation:** built as a
        **modal bottom sheet**, not an `AlertDialog` — this codebase has no `AlertDialog` anywhere and
        every existing prompt (`item_form_sheet`, `create_list_dialog`, `rename_list_dialog`) is a
        sheet; matching that keeps the visual language consistent (DESIGN §4). No explicit „Abbrechen"
        button either, for the same reason — dismissing the sheet (tap outside/swipe down) is every
        other sheet's cancel affordance in this app; a dedicated cancel button would be the odd one out.
  - [x] Re-provided `ItemsApi` + `ShoppingListsApi` on the detail route (`lists_view.dart`
        `_openListDetail`), mirroring 2.3's `ItemsApi` re-provide.
- [x] **Task 14: localization + error mapping** — `l10n`
  - [x] German strings added to `app_de.arb` + regenerated via `flutter gen-l10n`: move action/tooltip,
        target-picker heading + empty state, the merge-sheet heading/message/two action labels.
  - [x] `error_message_resolver.dart`: mapped `list.moveTargetNotOpen` and
        `list.moveTargetSameAsSource`. No hard-coded user-facing strings.

### Tests & green build (CLAUDE.md §6)

- [x] **Task 15: Flutter tests** — `list_detail_cubit`: clean `moveItem` (optimistic remove,
      error+revert, intent reuse/freshen, read-only no-op) **and** `mergeIntoTarget` (update-then-remove
      order, no-adjust = remove-only, revert on either leg's failure, read-only no-op); collision
      helper (matches trimmed/case-insensitively, absent-note vs present-note distinct); `move_target_sheet`
      widget (only other Open lists, excludes source, empty state, collision → merge sheet, no-collision
      → clean move, new-list create-then-clean-move); `move_merge_dialog` widget (pre-fills sum when
      units match / target quantity when not, both confirm actions call the right cubit path, dismiss
      calls neither); `_ItemRow` move affordance present on Open / **absent** on read-only Done.
      Extended `FakeItemsApi` (added `itemsByListId` per-list override, needed so a test can give a
      move's target list different items from the cubit's own source list) and used the existing
      `FakeShoppingListsApi`. 43 new/changed test cases across 4 files.
- [x] **Task 16: full-suite green** — backend `./gradlew test`: **375 tests, 0 failures, 0 errors**
      (incl. `HexagonalArchitectureTest` ArchUnit and the Testcontainers `ShoppingListReadModelProjectorTest`,
      Docker available). Flutter: `flutter analyze` — **no issues found**; `flutter test` — **345
      tests, all passed**. Both suites ran in full (not per-file), per CLAUDE.md §6.

## Dev Notes

### The process manager — the one new pattern in this story (read this first)

This is SGART's **first process manager** (AD-10). Everything else in 2.4 mirrors Story 2.3. The PM
exists because a move spans **two aggregates** (source `ShoppingList`, target `ShoppingList`) and the
`EventStore` appends to **one stream per call** (AD-4/AD-8) — so the move is inherently two appends,
sequenced by an event, not one transaction.

**Scope of the PM: the clean (non-collision) move only.** The collision branch (target already holds
the article) is **client-orchestrated** (`UpdateItem`(target) + `RemoveItem`(source), Cl. 3) because
the quantity merge is interactive — a process manager has no member to prompt. The PM path below is
the no-collision case; its `DuplicateItemException` swallow is only the safety net for a stale
client pre-check.

Flow (happy path — clean move, no collision):

```
member → POST …/lists/{source}/items/{itemId}/move {targetListId, commandId}
  MoveItemHandler: membership ✓ → load source ✓ → load target ✓ (Open) → source.moveItem(...)
    → append ItemMovedToList to  list-{source}   (source stream)   → 204 to the client
  KurrentDB $all ──filter list-*──▶ two independent subscribers:
    (a) ShoppingListReadModelProjector.project(ItemMovedToList) → itemReadModel.removeItem(itemId)
    (b) ItemMoveProcessManager.onItemMovedToList(...) → target.addItem(itemId,…,
          CommandId.deterministicFrom(eventId)) → append ItemAdded to  list-{target}
  KurrentDB $all ──filter list-*──▶ ShoppingListReadModelProjector.project(ItemAdded)
          → itemReadModel.insertItem(target, itemId, …)
  net read model: item_read_model row for itemId now under list_id = target
```

Why it is safe:

- **Exactly-once add (AC2):** the PM's `AddItem` command id is `CommandId.deterministicFrom(eventId)`
  of the `ItemMovedToList`. The subscription is catch-up-**from-start** on every (re)subscribe (like
  the projector), so it re-sees historical move events on restart — the derived id makes the target
  `EventStore.append` a **silent no-op** the second time (AD-10 / `EventStore` idempotency contract).
  Never use `CommandId.generate()` in the PM — that would double-add on replay.
- **Ordering:** `ItemMovedToList` (source) commits **before** the PM's `ItemAdded` (target), so in
  `$all` order the projector always removes the source row **before** inserting the target row — the
  read model never briefly shows the item on both, and a full replay reproduces the same end state.
- **Decoupled subscribers:** the projector and the PM are two independent subscriptions over the same
  `list-` prefix. The PM only **writes events**; the projector independently projects them. No
  projector→PM or PM→projector call. This mirrors `HouseholdReadModelProjector` vs
  `ShoppingListReadModelProjector` already being separate subscriptions.
- **No membership in the PM:** the move was authorized once, at `MoveItemHandler` (AC8). The PM is a
  system reaction, has no caller/JWT, and does **no** `ResolveMemberIdentity` — it uses the
  `EventStore` port directly (load target → `addItem` → append). This keeps it a pure, infra-free,
  `InMemoryEventStore`-testable application component (the KurrentDB subscription that drives it lives
  in `adapter.out`, exactly as the projector's subscription does).

[Source: ARCHITECTURE-SPINE.md #AD-10, #AD-4, #AD-8; `shared/CommandId.java` (`deterministicFrom`),
`shared/EventStore.java` (idempotency contract), `adapter/out/ShoppingListReadModelProjector.java`.]

### Architecture patterns & constraints (carried from 2.3)

- **DDD + CQRS + Event Sourcing, hexagonal, modular monolith** (AD-1/AD-2). Domain stays pure. State
  changes only via command → aggregate → event under an expected-version check (AD-4); read models are
  projection-only. [Source: ARCHITECTURE-SPINE.md #Design-Paradigm, #AD-1, #AD-4]
- **`Item` is an entity inside the `ShoppingList` aggregate (AD-10).** A move is the canonical
  cross-aggregate effect AD-10 names ("`ItemPostponed{targetListId}` → add-item on the target list") —
  2.4 realises the planning-time sibling of it. [Source: ARCHITECTURE-SPINE.md #AD-10]
- **`Quantity` + `Unit` (AD-9)** reused as-is (the moved item carries its existing `Quantity`).
- **Optimistic concurrency + idempotency (AD-8).** The source append uses the source's loaded version;
  the client `commandId` makes a retried move idempotent; the PM's derived id makes the target add
  idempotent. [Source: ARCHITECTURE-SPINE.md #AD-8]
- **No PII in events (AD-5/AD-6).** `ItemMovedToList` carries ids + item content only — no creator/
  `MemberId`/name/email. Item content is household personal data (CLAUDE.md §5); the read-model rows it
  moves already carry `household_id`/`list_id` for Epic-6 erasure. [Source: CLAUDE.md §5; #AD-5]
- **Ubiquitous language (AD-11):** `MoveItem`, `ItemMovedToList`, `ItemMoveProcessManager`, "move",
  "target list", "source list". No abbreviations; same term across domain, event, API, UI. [#AD-11]

### Source tree — mirror these exact files

| New/changed class | Mirror | Path |
| --- | --- | --- |
| `ItemMovedToList` | `ItemAdded` / `ItemRemoved` | `collaboration/domain/event/` |
| `ShoppingList.moveItem` + `apply(ItemMovedToList)` | `ShoppingList.removeItem` + `apply(ItemRemoved)` | `collaboration/domain/ShoppingList.java` |
| `MoveItem` (DTO) | `AddItem` | `collaboration/application/command/` |
| `MoveItemHandler` | `AddItemHandler` (+ two aggregate loads) | `collaboration/application/command/` |
| `MoveTargetNotOpenException`, `InvalidMoveTargetException` | `DuplicateItemApplicationException` / `InvalidItemQuantityException` | `collaboration/application/exception/` |
| **`ItemMoveProcessManager`** | *(new pattern)* — closest is `AddItemHandler`'s load-then-append, minus membership | `collaboration/application/` |
| `CollaborationProcessManagerSubscription` | `ShoppingListReadModelProjector` (SmartLifecycle) | `collaboration/adapter/out/` |
| `CollaborationProcessManagerConfig` | `CollaborationReadModelConfig` | `collaboration/adapter/out/` |
| projector + codec extension | (extend existing) | `collaboration/adapter/out/` |
| `ItemController` move endpoint + `MoveItemRequest` | existing `ItemController` `POST`/`DELETE` | `collaboration/adapter/in/` |
| Flutter `moveItem` | `items_api.dart` `addItem`/`removeItem` | `app/lib/features/lists/data/` |
| Flutter cubit `moveItem` | `list_detail_cubit.dart` remove/update | `app/lib/features/lists/presentation/` |
| `move_target_sheet.dart` | `item_form_sheet.dart` / `create_list_dialog` | `app/lib/features/lists/presentation/` |
| `move_merge_dialog.dart` (collision quantity merge) | `item_form_sheet.dart` (quantity controls) | `app/lib/features/lists/presentation/` |
| cubit `mergeIntoTarget` (reuses 2.3 `updateItem`/`removeItem`) | `list_detail_cubit.dart` update+remove | `app/lib/features/lists/presentation/` |

### Package structure (CLAUDE.md §8)

New classes drop into the **existing** intent subpackages (`domain.event`, `application.command`,
`application.exception`). The **process manager** goes at the `application` layer **root** (the §8
module layout lists "process managers" beside command/query handlers; a single PM does not yet earn
its own `application.processmanager` subpackage — KISS/YAGNI, add it only when a second PM arrives).
The KurrentDB subscription is `adapter.out` (infrastructure), same layer as the projector. No ArchUnit
rule change (rules match `..domain..` / `..application..`).

### Testing standards

- Domain: fast pure unit tests, no infra (mirror `ShoppingListItemsTest`). Assert events raised + state
  after replay, not internals.
- **Process manager: unit-tested with `InMemoryEventStore` only** — no KurrentDB. Prove: appends the
  target `ItemAdded`; double-processing appends once (idempotency); dedupe-swallow on a pre-existing
  target item; non-Open target swallow. This is the highest-value new test surface — cover every branch.
- Handler: `InMemoryEventStore` + fake `ResolveMemberIdentity`; assert the **source** stream change and
  **every** error status incl. both cross-household 404s and the same-list 400 / target-not-open 409.
- Codec/projector: Testcontainers (matching the existing files) — round-trip + the end-to-end move
  read-model outcome (one row, under the target list).
- Synthetic, clearly-fake German data only; no real personal data. No-PII assertion for `ItemMovedToList`.
- **Green build = full suite** for both modules; state which ran. [Source: CLAUDE.md §6]

### Deferred / do-not-build (premature-value discipline)

- **Reachable non-Open move target (Done/In-Trip) e2e** — coded + synthetic-tested now (409); the
  reachable test waits for Epic 3's trip events (same caveat as 2.3 AC5 / the `rename` `DONE` branch).
  Log in `deferred-work.md`.
- **Compensation when a target goes non-Open *mid-move*** — the PM logs-and-drops the add if the
  target is no longer Open by the time it processes (Epic-2-unreachable — no Epic-2 event moves a list
  out of `OPEN`). A real compensation (e.g. return the item to the source, or surface a conflict) is
  **Epic 3's**, once non-Open lists are reachable. Note it in `deferred-work.md`.
- **Auto-merge / server-side quantity combine** — E2 is resolved as a **client-orchestrated,
  member-confirmed** quantity merge (Cl. 3, Timo 2026-08-27); no server auto-sum, no PM-driven merge
  (the PM only ever does the clean-move add + the race-safety dedupe swallow). Do **not** build a
  server merge command.
- **Preserved-`ItemId` read-model edge** — reusing the `ItemId` across the move relies on the
  source `removeItem` projecting before the target `insertItem` (guaranteed by `$all` commit order).
  If a skipped-then-replayed source removal ever inverted that, `insertItem`'s `ON CONFLICT (item_id)
  DO NOTHING` would leave the row under the old `list_id` until the next full replay. Same class as the
  projector's existing log-and-skip caveats (2.1 review). Note; don't build around it.
- **Autocomplete/prefill (2.5), store assignment (2.6), check/uncheck/postpone/progress (Epic 3)** —
  out of scope; their affordances must not appear. 2.4 adds only the move affordance + target picker.

### Project Structure Notes

- The move endpoint is a sub-resource action on the source item
  (`POST …/items/{itemId}/move`) — a state change routed through the source aggregate, consistent with
  the item command style. The `targetListId` rides the request body, not the path (it belongs to a
  *different* aggregate than the path's source list). [Source: `ItemController.java`]
- The second `list-`-prefix subscription (PM) alongside the projector's is deliberate and consistent
  with the existing one-subscription-per-consumer pattern; both are `SmartLifecycle`, both default-off
  in tests, so `contextLoads()` and the slice tests never touch KurrentDB. [Source:
  `ShoppingListReadModelProjector.java`, `HouseholdReadModelProjector.java`]
- No new migration and no `item_read_model` schema change — a move is `removeItem(source)` +
  `insertItem(target)` on the existing V6 table. [Source: `V6__item_read_model.sql`, `JdbcItemReadModel.java`]

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story-2.4] — user story + BDD ACs (FR5), E2/E3 tags.
- [Source: ARCHITECTURE-SPINE.md#AD-10] — process managers, idempotent PM commands, `Item`-in-`ShoppingList`.
- [Source: ARCHITECTURE-SPINE.md#AD-4/AD-8/AD-9/AD-11] — command→event, optimistic concurrency + idempotency, value objects, ubiquitous language.
- [Source: CLAUDE.md §1–§8] — Clean Code, naming, DDD, CQRS, DSGVO, testing, package structure.
- [Source: backend `shared/CommandId.java` (`deterministicFrom`), `shared/EventStore.java`, `ShoppingList.java`, `AddItemHandler.java`, `AddItem.java`, `ShoppingListReadModelProjector.java`, `DomainEventJsonCodec.java`, `JdbcItemReadModel.java`, `ItemController.java`, `WriteErrorAdvice.java`, `CommandFieldTranslations.java`, `CollaborationReadModelConfig.java`, `CollaborationApplicationConfig.java`] — patterns to mirror.
- [Source: app `lists/` feature — `items_api.dart`, `list_detail_cubit.dart`, `list_detail_page.dart`, `item_form_sheet.dart`, `shopping_lists_api.dart`, `error_message_resolver.dart`, `app_de.arb`] — client patterns to mirror.
- [Source: _bmad-output/implementation-artifacts/2-3-add-edit-and-remove-items.md] — the item slice this extends (aggregate/handler/projector/codec/controller/Flutter patterns; the `isSubmitting`/`CommandIntent` review fixes to carry forward).
- [Source: _bmad-output/implementation-artifacts/deferred-work.md] — non-Open reachability defers, projector log-and-skip caveats.

## Dev Agent Record

### Agent Model Used

Sonnet 5 (dev-story)

### Debug Log References

None — no failures required a debug log; the Testcontainers-backed projector test and both full
suites (backend + Flutter) ran green on first full run after the implementation.

### Completion Notes List

- SGART's first process manager delivered per the story's design: source `ShoppingList.moveItem`
  raises `ItemMovedToList` (folds as a removal, mirrors `ItemRemoved`); `ItemMoveProcessManager`
  reacts via a second, independent `list-`-prefix KurrentDB subscription
  (`CollaborationProcessManagerSubscription`) and issues an idempotent `AddItem` on the target keyed
  by `CommandId.deterministicFrom(eventId)` — exactly-once under replay, proven by
  `ItemMoveProcessManagerTest`.
- E2 (target already holds the item's key) is resolved client-side per Clarification 3: the
  `move_target_sheet` runs a collision pre-check (`ListDetailCubit.findCollisionOnTarget`) and, on a
  match, opens `move_merge_dialog` instead of moving — `UpdateItem`(target) + `RemoveItem`(source),
  never a `MoveItem`. The server-side `DuplicateItemException` swallow in the process manager is only
  the race safety net for a stale pre-check, proven separately.
- **Deviation — synthetic non-Open (`DONE`) branches not unit-tested.** `ShoppingList.moveItem`'s
  source guard and `MoveItemHandler`'s target-`409` guard are coded but, like every sibling
  `DONE`-branch in this codebase (`ShoppingListTest`, `ShoppingListItemsTest` already document this
  for rename/add/update/remove), cannot be synthetically constructed — no Epic-2 event drives a list
  out of `OPEN`, and the codebase deliberately does not add a test-only production hook to fabricate
  one (YAGNI). Followed the established precedent rather than inventing a new mechanism; noted inline
  at each affected test class. The reachable end-to-end test is deferred to Epic 3, same as the
  Story 2.1/2.3 precedent.
- **Deviation — Flutter `mergeIntoTarget` signature.** Implemented as `mergeIntoTarget(String
  sourceItemId, String targetListId, Item targetItem, {String? adjustedAmount, String?
  adjustedUnit})` rather than the story's `(..., String targetItemId, Quantity? adjustedQuantity)` —
  this Flutter codebase has no `Quantity` type (quantities are plain `amount`/`unit` strings
  everywhere, e.g. `Item`, `ItemsApi`); passing the full target `Item` also supplies the name/note the
  update call must preserve unchanged.
- **Deviation — quantity-merge and new-list-name prompts built as bottom sheets, not `AlertDialog`s.**
  This app has no `AlertDialog` anywhere; every existing prompt (`item_form_sheet`,
  `create_list_dialog`, `rename_list_dialog`) is a modal bottom sheet (DESIGN §4). Built
  `move_merge_dialog.dart` and the new-list-name prompt the same way for visual consistency, and
  correspondingly rely on the sheet's own dismiss (tap outside/swipe down) as "Abbrechen" — no
  existing sheet in this app has an explicit cancel button either.
- **Deviation — no dedicated `items_api` HTTP test.** Verified the codebase has no direct `Http*Api`
  test for any feature (`HttpShoppingListsApi`, `HttpItemsApi`, etc. are only exercised through their
  `Fake*Api` doubles in cubit/widget tests) — followed that precedent for `moveItem` too.
- `FakeItemsApi` gained an `itemsByListId` per-list override (on top of the existing single
  `itemsToReturn`) so a test can give a move's *target* list different items from the cubit's own
  *source* list — needed once `findCollisionOnTarget` reads a second list through the same fake.
- **Backend — full suite green**: `./gradlew test` (incl. `HexagonalArchitectureTest` ArchUnit and
  the Testcontainers-backed `ShoppingListReadModelProjectorTest`, Docker available) — **375 tests, 0
  failures, 0 errors** (up from Story 2.3's 347).
- **Flutter — full suite green**: `flutter analyze` — no issues; `flutter test` — **345 tests, 0
  failures** (up from Story 2.3's 319).

### File List

**Backend — new**
- `backend/src/main/java/de/sgart/collaboration/domain/event/ItemMovedToList.java`
- `backend/src/main/java/de/sgart/collaboration/application/command/MoveItem.java`
- `backend/src/main/java/de/sgart/collaboration/application/command/MoveItemHandler.java`
- `backend/src/main/java/de/sgart/collaboration/application/exception/MoveTargetNotOpenException.java`
- `backend/src/main/java/de/sgart/collaboration/application/exception/InvalidMoveTargetException.java`
- `backend/src/main/java/de/sgart/collaboration/application/ItemMoveProcessManager.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/CollaborationProcessManagerSubscription.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/CollaborationProcessManagerConfig.java`

**Backend — changed**
- `backend/src/main/java/de/sgart/collaboration/domain/ShoppingList.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/ShoppingListReadModelProjector.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/DomainEventJsonCodec.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/CollaborationApplicationConfig.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/in/ItemController.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/in/WriteErrorAdvice.java`

**Backend — tests (new)**
- `backend/src/test/java/de/sgart/collaboration/application/MoveItemHandlerTest.java`
- `backend/src/test/java/de/sgart/collaboration/application/ItemMoveProcessManagerTest.java`

**Backend — tests (changed)**
- `backend/src/test/java/de/sgart/collaboration/domain/ShoppingListItemsTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/out/DomainEventJsonCodecTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/out/ShoppingListReadModelProjectorTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/in/ItemControllerTest.java`

**Flutter — new**
- `app/lib/features/lists/presentation/move_target_sheet.dart`
- `app/lib/features/lists/presentation/move_merge_dialog.dart`

**Flutter — changed**
- `app/lib/features/lists/data/items_api.dart`
- `app/lib/features/lists/presentation/list_detail_cubit.dart`
- `app/lib/features/lists/presentation/list_detail_page.dart`
- `app/lib/features/lists/presentation/lists_view.dart`
- `app/lib/shared/errors/error_message_resolver.dart`
- `app/lib/l10n/app_de.arb` (`app/lib/l10n/gen/*.dart` regenerated via `flutter gen-l10n`, gitignored)

**Flutter — tests (new)**
- `app/test/features/lists/presentation/move_target_sheet_test.dart`
- `app/test/features/lists/presentation/move_merge_dialog_test.dart`

**Flutter — tests (changed)**
- `app/test/features/lists/presentation/list_detail_cubit_test.dart`
- `app/test/features/lists/presentation/list_detail_page_test.dart`
- `app/test/features/lists/presentation/lists_view_test.dart`
- `app/test/support/fake_items_dependencies.dart`

**Story tracking**
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `_bmad-output/implementation-artifacts/2-4-move-an-item-to-another-list.md`

## Review Findings

Code review 2026-08-27 (Blind Hunter + Edge Case Hunter + Acceptance Auditor, all three layers — domain-heavy slice with the new process-manager pattern). 5 patch, 3 deferred, 4 dismissed as noise.

- [x] [Review][Patch] **Process manager swallows `ConcurrencyConflictException` on the target append → moved item silently stranded on neither list (data loss)** [backend/.../collaboration/application/ItemMoveProcessManager.java:83] — `onItemMovedToList` catches only `DuplicateItemException` and `ItemChangeNotPermittedException`. Per the `EventStore` contract, command-id idempotency is checked *before* the version check, so on the *first* processing the derived id is not yet applied: any concurrent write to the target list between the PM's `readStream` (line 56) and its `append` (line 83) throws `ConcurrencyConflictException`. That propagates to `CollaborationProcessManagerSubscription.onEvent`'s `catch (RuntimeException)` (log-and-skip) — the live subscription advances and never re-delivers, while the source has already removed the item. The class comment's "a later catch-up replay retries" only holds on `onCancelled`/restart, not under a healthy live subscription. Fix: retry on `ConcurrencyConflictException` inside the PM (re-read target → re-derive the same id → re-append; the derived-id dedupe makes the retry safe).
- [x] [Review][Patch] **Merge quantity ≥ 1000 corrupted ~1000× by the German thousands separator** [app/lib/features/lists/presentation/move_merge_dialog.dart:72] — `_initialAmountText` sums the quantities and formats via `NumberFormatter.format` (`NumberFormat.decimalPattern('de_DE')`), which groups thousands with `.`: a sum of 1600 renders `"1.600"`. `_normalizedAmount` (line 92) only maps `,`→`.`, leaving `"1.600"`, and `double.tryParse("1.600")` yields **1.6**, which `_isAmountValid` accepts — so merging 800 g + 800 g silently writes 1.6 g to the target. Reached on any unit-matching merge summing to ≥ 1000 (common for grams/millilitres), no race required. Fix: pre-fill without grouping (and ideally parse/sum with `Decimal` rather than `double` — see the dismissed float-precision note).
- [x] [Review][Patch] **Collision-merge partial failure (target update succeeds, source remove fails) reverts as if nothing happened → risk of double-count on retry** [app/lib/features/lists/presentation/list_detail_cubit.dart:250] — `mergeIntoTarget` updates the target then removes the source; if the remove leg throws, the catch restores `originalItems` (source row reappears) but the target quantity was already bumped. The generic `actionError` gives no hint the target changed, so a member re-opening the merge dialog re-sums the *already-summed* target and can double-count (mitigated only by the amount being a visible editable field). Fix: on a post-update failure, surface a specific message ("target quantity already updated; only the removal failed") so the member does not blindly re-merge; the update leg also re-runs redundantly on a full retry.
- [x] [Review][Patch] **Target sheet has no re-entrancy guard across the awaited collision read, and opens the merge sheet on a just-popped context** [app/lib/features/lists/presentation/move_target_sheet.dart:153] — `_selectTarget` awaits `findCollisionOnTarget` with the `ListTile` still tappable; a double-tap fires it twice and the second `navigator.pop()` (line 167) pops the underlying list-detail page. It also passes the post-`pop` `sheetContext` to `showMoveMergeDialog`, mounting a new modal on a deactivating element. Fix: add a `_selecting` re-entrancy flag and confirm the context is still valid after the pop (or route the merge sheet through the retained `navigator`).
- [x] [Review][Patch] **AC2 idempotency test does not exercise the `deterministicFrom` command-id dedupe it claims to prove** [backend/.../collaboration/application/ItemMoveProcessManagerTest.java] — `processingTheSameEventTwiceAppendsOnlyOnce` re-processes with the item already on the target, so the second call short-circuits on the swallowed `DuplicateItemException` and never reaches the `append` whose derived-id dedupe is the AC2/Task-6 mechanism. Add a test for the remove-then-replay path (item added to target, then removed, then the move event replayed) asserting the derived command id makes the re-append a no-op.

- [x] [Review][Defer] **PM `DuplicateItemException` swallow silently discards the moved item's quantity in the stale-pre-check race** [backend/.../collaboration/application/ItemMoveProcessManager.java:70] — deferred, spec-accepted (Cl. 3) and tied to the Epic-3 mid-move compensation defer; rare but the member's merge intent is lost with only a `log.debug`.
- [x] [Review][Defer] **PM subscription discards the `Subscription` handle → not cancelled on `stop()` (resource leak)** [backend/.../collaboration/adapter/out/CollaborationProcessManagerSubscription.java:102] — deferred, pre-existing pattern (the `ShoppingListReadModelProjector` subscription has the identical leak); fix both consistently.
- [x] [Review][Defer] **`MoveItemRequest` has no null/body validation → 500 instead of 400 on an empty body** [backend/.../collaboration/adapter/in/ItemController.java:146] — deferred, pre-existing controller-wide pattern (no sibling request DTO is `@Valid`d); harden the whole controller together.

**Dismissed as noise (4):** AC5's "unit-tested against a synthetic non-Open target" clause (documented deviation, matches the 2.1/2.3 no-reachable-non-OPEN precedent); Cl. 3 merge-prompt copy split across heading + message (cosmetic — the "Menge anpassen?" question moved to the heading); the read-model "neither list" window during a move (inherent to the CQRS/PM design, documented in Deferred); standalone `double` precision drift (folded into the thousands-separator patch above).
