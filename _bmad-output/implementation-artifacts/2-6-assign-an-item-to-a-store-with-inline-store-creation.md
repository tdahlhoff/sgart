---
baseline_commit: b3362d594dcdf9c4a0ccaab595d2c43e6674672f
---

# Story 2.6: Assign an item to a store (with inline store creation)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a member,
I want to assign an item to a store while planning — and add a new store on the fly,
so that the list groups by store even before a trip, without leaving to manage stores first.

## Acceptance Criteria

Derived from **epics.md § Story 2.6** (FR5/CAP-5 store-organization + FR3/CAP-3 inline store
creation + FR6/CAP-6 default-store prefill), refined against **ARCHITECTURE-SPINE.md** (AD-3, AD-4,
AD-8, AD-9, AD-10, AD-11), **UX-DR8 / UX-DR22 / EXPERIENCE.md** (the list-detail store chip + the
reusable store picker with inline „+ Neues Geschäft"), the `screen-list-detail.html` artifact (the
`.chip` / `.chip.ghost "+ Geschäft"` rows + the suggestion „zuletzt Edeka" chip), the Story 2.3 item
slice, the Story 2.5 suggestion slice this extends, and the Story 1.8 store slice this reuses. Each
AC is independently testable.

1. **Assign an item on an Open list to an existing household store (AC1, FR5).** Given an item on an
   **Open** list, when a member opens its store picker and taps an existing household store, then the
   item is assigned to that store via **command → `ItemAssignedToStore`** (a new event on the list's
   own `list-{id}` stream — `Item` is an entity inside the `ShoppingList` aggregate, AD-10). The item
   row then shows that **store chip**; an **unassigned** item shows the ghost **„+ Geschäft"** chip.
   Re-assigning to a **different** store replaces the assignment (last-wins); assigning to the
   **same** store again is a **convergent no-op** (raises nothing, AD-8).

2. **Add a new store inline from the picker and assign it in one flow (AC2, FR3/CAP-3, UX-DR22).**
   Given the store picker, when the needed store does not exist yet, then the member can add a new
   store **inline** — free-form name + the **advisory client-side chain suggestion** (accept / change
   / clear, never forced), under the **same rules as Story 1.8** (unique-per-household name, duplicate
   rejected) — created in the household via the **existing `AddStore`** command (Household aggregate)
   and **immediately assigned** to the item, **without leaving for „Haushalt verwalten"**. Inline
   creation + assignment is a **client-orchestrated two-step** (create-then-assign), mirroring Story
   2.4's new-list move target.

3. **The assignment carries into the trip's grouped view and the print layout (AC3, FR9/FR11).** Given
   an assigned item, when it later appears in a trip's store-grouped view or the print layout (Epic 3),
   then it is grouped under its assigned store — the assignment is **persisted on the item** (a
   `store_id` on the read model + the folded aggregate state), not a trip-only concern. *(Epic 2 builds
   the persistence + the off-trip surface only; the trip/print consumers are Epic 3. This AC is
   satisfied here by the assignment being durable and queryable, with a projector/read-model test
   proving it survives; no trip UI is built in 2.6.)*

4. **An archived store falls back to „+ Geschäft" (AC4, Story 1.8 AC E6).** Given an item assigned to a
   store that is later **archived** (Story 1.8), when the list detail is viewed, then the row **falls
   back to the „+ Geschäft" unassigned chip** — the archived store is no longer offered in the picker
   and no longer shown as the item's chip. This is resolved **client-side**: the client renders the
   chip name by resolving the item's `storeId` against the household's **active** store list (the same
   list the picker offers); an id not in that active list renders as unassigned. The stored `storeId`
   is left intact (no rewrite) — a historical Done trip keeps its record (Story 1.8 E6).

5. **A Done list assigns nothing (AC5).** Given a **Done** (read-only) list detail screen, then **no**
   store chip is tappable and **no** picker opens — a Done list accepts no item commands (mirrors the
   Story 2.3/2.5 read-only Done detail, which hid add/edit/remove and the fast-add field). The
   `assignItemToStore` command is refused on a non-Open list (`ItemChangeNotPermittedException`),
   mirroring add/update/remove/move.

6. **Selecting a suggestion prefills the last-used store (AC6, FR6, Story 2.5 Cl. 4 deferral).** Given
   the fast-add suggestion panel, when a member taps a suggestion whose name has a **last-used store**,
   then the item is added (the Story 2.5 instant add) **and** immediately assigned to that store
   (add-then-assign) — **provided the store is still active** (an archived last-used store is skipped:
   the item adds unassigned, AC4). The suggestion row shows the **„zuletzt {Geschäft}" chip**. A name
   with **no** last-used store adds unassigned exactly as in Story 2.5. The last-used store is recorded
   on the **history-surviving `item_suggestion_read_model`** (V7) — a new nullable `default_store_id`,
   projected from `ItemAssignedToStore`, upsert last-wins by `(household_id, normalized_name)`.

7. **Membership, isolation & no personal data (AC7).** The assign command is **membership-gated**
   (non-member → **403**), a malformed id is **400**, an unknown list is **404**, and a list under
   another household is **404** (mirrors `UpdateItemHandler`). The read side is queried **by
   `household_id`** so one household's assignments never leak to another. The new `store_id` /
   `default_store_id` columns carry **no** personal data (a store id is household content, not a
   person — AD-5/AD-6, mirrors `item_read_model` AC9). Tests use synthetic, clearly-fake German data
   only.

## Clarifications (LOCKED)

Taken from the epic ACs + the existing 2.3/2.4/2.5/1.8 patterns + the `screen-list-detail.html`
artifact + Timo's decisions (2026-08-28). **If any is wrong, correct it before `dev-story`.**

1. **Assignment is a NEW `ShoppingList` command/event — NOT a cross-aggregate process manager.** The
   `Item` holds a bare `StoreId` **reference** (AR2 — aggregates reference each other by id only). A
   new `AssignItemToStore` command → `ItemAssignedToStore` event on the **`list-{id}` stream**, folded
   into the item's state on the root (AD-10). The aggregate **does NOT validate that the store exists
   in the household** — the `Household` is a separate aggregate this root never loads (mirrors
   `moveItem`, which does not validate `targetListId`). Validity is enforced **client-side** (the
   picker only offers active household stores) and by the **read-side archived-store fallback** (AC4).
   No process manager: assignment stores a reference *inside* `ShoppingList`; it is not an effect on
   the `Household` aggregate. [Source: `ShoppingList.moveItem`, ARCHITECTURE-SPINE.md #AD-3/#AD-10;
   Story 1.8 AC E6.]

2. **Inline store creation reuses the existing Story 1.8 `AddStore` (Household), client-orchestrated.**
   „+ Neues Geschäft" in the picker calls the **existing** `AddStore` command (Household aggregate,
   Story 1.8 — unique-name rule, advisory chain suggestion) then `AssignItemToStore` (ShoppingList) —
   **two separate commands the client sequences**, exactly like Story 2.4's „new list target =
   create-then-move". **No new store-creation code server-side.** The picker embeds the Story 1.8
   chain-suggestion matching (`StoreChainMatcher` + the cached reference list). [Source:
   `AddStoreHandler`, `StoresCubit.addStore`, `StoreChainMatcher`; epics.md Story 1.8 AC5 „any store
   picker … same creation rules".]

3. **Reassign-only — no explicit „remove assignment" (Timo, 2026-08-28).** Correcting a wrong store =
   pick the right one in the picker (reassign, last-wins). There is **no** clear-to-unassigned action
   and **no** null-store event in MVP — the epic ACs require only assign + inline-create, and the
   archived-store fallback (AC4) already covers a store that vanishes. `ItemAssignedToStore` carries a
   **required** `storeId` (YAGNI). *(A member-facing „Zuordnung entfernen" can follow post-MVP if
   wanted — log the option in `deferred-work.md`.)*

4. **A moved item starts unassigned on the target — the assignment does NOT travel (Timo,
   2026-08-28).** Story 2.4's `ItemMovedToList` (and the `ItemMoveProcessManager`'s target-side
   `AddItem`) are **unchanged** — they carry name/note/quantity only. A moved assigned item shows „+
   Geschäft" on the target and is re-assigned in one tap. KISS; no versioning of the 2.4 event, no
   widening of the process-manager payload. [Source: `ItemMovedToList`, `ItemMoveProcessManager`.]

5. **Default-store prefill IS built in 2.6 (Timo, 2026-08-28) — the Story 2.5 Cl. 4 deferral lands
   here.** 2.6 is the last Epic-2 story, so its committed home is now. Extend `item_suggestion_read_model`
   (V7) with a nullable `default_store_id`, record it from `ItemAssignedToStore`, surface it on the
   suggestion rows (AC6), and prefill via add-then-assign. [Source: `deferred-work.md` §"planning of
   story-2.5"; Story 2.5 Cl. 4.]

6. **The projector resolves the assigned item's NAME via an `item_read_model` lookup (mirrors 2.5's
   `householdIdOf`).** The suggestion read model is keyed by `(household_id, normalized_name)`, so
   recording a default store needs the item's **name**. `ItemAssignedToStore` carries `householdId`
   (it is a new event — free to include, like `ItemAdded`) + `listId` + `itemId` + `storeId`, but not
   the name. The projector resolves the name via a new **`ItemReadModel.nameOf(ItemId)`** lookup — the
   `item_read_model` row exists by then (its `ItemAdded` projected earlier on the same ordered stream).
   Empty lookup (out-of-order replay edge) → **skip the suggestion default-store**, still do the
   `item_read_model` `store_id` update; a later full replay recovers it. Mirrors 2.5 Cl. 5 exactly.

7. **`ItemUpdated` must PRESERVE an existing store assignment (regression trap).** In the aggregate,
   `apply(ItemUpdated)` currently **replaces** the whole `ItemState` — it must now **carry forward the
   existing `assignedStore`** (an edit changes name/note/quantity, never the store). Likewise the
   read-side: the projector's `updateItem` must **not** touch `store_id`, and `recordUsage`'s
   suggestion upsert must **not** touch `default_store_id` (an add/update never knows the store). Only
   `ItemAssignedToStore` writes those two columns. This is the one place a naïve implementation
   silently wipes an assignment — cover it with an explicit test (edit an assigned item → still
   assigned).

8. **The store picker is the first realization of the reusable UX-DR22 component.** Build a focused,
   reusable **store picker sheet** (`features/stores/presentation/`) that lists active household
   stores + „+ Neues Geschäft" inline creation with the advisory chain suggestion — consumed by
   list-detail here, and by Story 3.1 (trip start) / 3.2 (in-trip reroute) later. Do **not** overbuild
   for those later stories (YAGNI) — a clean, parameterized sheet that returns/creates a store is
   enough; multi-select (trip start needs ≥1 store) is Story 3.1's extension, not 2.6's.

9. **No premature surfaces.** No check/uncheck/postpone/progress (Epic 3); no trip/print UI (Epic 3 —
   AC3 is satisfied by durable persistence + a read-model test, not a screen); no live-sync refresh of
   the client store/suggestion caches (Epic 4 — MVP refetches on list-detail open, and a fresh
   assign optimistically updates the local caches for read-your-writes). The item row's edit/remove/
   move affordances stay as Stories 2.3/2.4 built them; 2.6 adds only the store chip + picker.

## Tasks / Subtasks

> Follow TDD (CLAUDE.md §6): failing test first at the lowest level that proves the behaviour, then
> the simplest code to pass. Keep the domain pure (AD-1). Mirror the cited existing file for every new
> class — the command→handler, event, read-model, query, controller, and Flutter-cubit patterns are
> all established (Stories 1.8, 2.1–2.5); do not invent new ones. This story **does** add a new domain
> event + command + aggregate method (the write side of assignment) — unlike the read-only Story 2.5.

### Backend — domain: the assignment event + aggregate method (AC1, AC5, Cl. 1/7)

- [x] **Task 1: `ItemAssignedToStore` event** — package `collaboration.domain.event` (mirror
      `ItemAdded`/`ItemMovedToList`)
  - [x] `record ItemAssignedToStore(EventId eventId, HouseholdId householdId, ShoppingListId listId,
        ItemId itemId, StoreId storeId)` — all `requireNonNull` (storeId **required**, Cl. 3). Javadoc:
        lives on the `list-{id}` stream (`Item` is inside `ShoppingList`, AD-10); carries `householdId`
        so the projector can record the suggestion default store without a lookup (only the name is
        looked up, Cl. 6); carries no personal data — a household/list/item/store id, never a person
        (AD-5/AD-6); records no *who* (no audit trail, mirrors `ItemAdded`).
- [x] **Task 2: `ShoppingList.assignItemToStore(...)` + fold** (AC1, AC5, Cl. 1/7)
  - [x] `public void assignItemToStore(ItemId itemId, StoreId storeId, CommandId commandId)`:
        `requireNonNull` all; `requireOpen()` (Done/In-Trip → `ItemChangeNotPermittedException`, AC5);
        unknown item → `ItemNotFoundException`; **already assigned to the same store → convergent no-op**
        (raise nothing, AD-8 — compare the folded `assignedStore`); else `raise(new ItemAssignedToStore(
        EventId.generate(), householdId, listId, itemId, storeId))`. **Does NOT validate the store
        exists** (Cl. 1 — separate aggregate). Javadoc mirrors `moveItem`'s "does not validate … that
        is client/read-side".
  - [x] Extend the private `ItemState` record with a nullable `StoreId assignedStore`; update its two
        current construction sites: `apply(ItemAdded)` → `assignedStore = null`; **`apply(ItemUpdated)`
        → carry forward the existing state's `assignedStore`** (Cl. 7 — the regression trap: an edit
        must not wipe the store). Add `apply(ItemAssignedToStore)` → replace the item's state with the
        same name/note/quantity + the new `assignedStore`.
  - [x] Register `ItemAssignedToStore` in the `apply(...)` switch.
- [x] **Task 3: aggregate unit tests** — extend `ShoppingListTest` (fast, no infra)
  - [x] `assignItemToStore_raisesItemAssignedToStore` (right household/list/item/store); reassign to a
        different store raises a new event; **assigning the same store again raises nothing**
        (convergent no-op); unknown item → `ItemNotFoundException`; a non-Open list →
        `ItemChangeNotPermittedException`; **editing an assigned item (`updateItem`) preserves the
        assignment** (rehydrate `[ItemAdded, ItemAssignedToStore, ItemUpdated]` → assign a *different*
        store still raises, proving the state kept the store after the edit — or assert via a same-store
        no-op after an edit). Synthetic German data.

### Backend — application: command + handler + endpoint (AC1, AC5, AC7)

- [x] **Task 4: `AssignItemToStore` command + `AssignItemToStoreHandler`** — package
      `application.command` (mirror `UpdateItem`/`UpdateItemHandler`, DTO **beside** its handler per
      CLAUDE.md §8)
  - [x] `record AssignItemToStore(ShoppingListId listId, ItemId itemId, StoreId storeId, CommandId
        commandId, AggregateVersion basedOnVersion)` — `requireNonNull` all.
  - [x] `AssignItemToStoreHandler.handle(keycloakUserId, rawHouseholdId, rawListId, rawItemId,
        rawStoreId, rawCommandId)`: translate ids via `CommandFieldTranslations` (`toStoreId` already
        exists — line 89; 400 on malformed), `resolveMemberIdentity.resolve` (403), load `list-{id}`
        (404 empty / cross-household), rehydrate, `assignItemToStore`, append under the **loaded**
        version (AD-8), skip the append when no event was raised (no-op, mirrors `UpdateItemHandler`).
        Translate `ItemNotFoundException` → `ItemNotFoundApplicationException` (404) and
        `ItemChangeNotPermittedException` → `ItemChangeNotPermittedApplicationException` (403) at the
        seam (both `*ApplicationException` types exist).
  - [x] Wire the handler bean in `CollaborationApplicationConfig` (mirror `updateItemHandler`).
  - [x] `AssignItemToStoreHandlerTest` (in-memory `EventStore` + fake `ResolveMemberIdentity`, mirror
        `UpdateItemHandlerTest`): assigns (event appended); non-member → 403; malformed id → 400;
        unknown list → 404; cross-household list → 404; non-Open list → 403; unknown item → 404;
        same-store no-op appends nothing.
- [x] **Task 5: `ItemController` — the assign endpoint** (mirror the `move` endpoint)
  - [x] `@PutMapping("/{itemId}/store")` `@ResponseStatus(NO_CONTENT)` `assignStore(...)` taking
        `@RequestBody AssignStoreRequest(String storeId, String commandId)`; identity from the JWT
        `sub` via `AuthenticatedCaller`. Constructor-inject `AssignItemToStoreHandler`.
  - [x] `ItemControllerTest` (MockMvc slice): 204 on assign; 403 non-member; 400 malformed; 404 unknown
        list/item. Mirror the existing `move` cases.

### Backend — read side: item store_id + suggestion default_store_id (AC1, AC3, AC4, AC6, AC7, Cl. 5/6/7)

- [x] **Task 6: `V8__item_store_assignment.sql`** — one migration, two `ALTER TABLE`s, clearly commented
  - [x] `ALTER TABLE item_read_model ADD COLUMN store_id UUID NULL;` — comment: the item's assigned
        store (Story 2.6), nullable = unassigned („+ Geschäft"); a *reference* to a `Store` entity in
        the `Household` aggregate (AR2), not FK-constrained (separate aggregate + archived stores stay
        referenced, Story 1.8 E6); no personal data (a store id is household content, AC7).
  - [x] `ALTER TABLE item_suggestion_read_model ADD COLUMN default_store_id UUID NULL;` — comment: the
        name's last-used store (Story 2.6, AC6 — the Story 2.5 Cl. 4 deferral); nullable = never
        assigned; upsert last-wins from `ItemAssignedToStore`, untouched by the add/update `recordUsage`
        (Cl. 7).
  - [x] Confirm the migration passes `NoPersistedPersonalDataTest` (a `store_id` column is not a
        person's display-name/email — no false positive expected; verify).
- [x] **Task 7: read-model port + view extensions** (`domain.readmodel`)
  - [x] `ItemView` → add nullable `StoreId storeId`. `ItemSuggestionView` → add nullable `StoreId
        defaultStore`. Update Javadocs (nullable = unassigned / no last-used store).
  - [x] `ItemReadModel` port: `itemsOf` now returns the `storeId`; add `void assignStore(ItemId,
        StoreId)` (the projector's UPDATE) and `Optional<ItemName> nameOf(ItemId)` (Cl. 6 lookup, sibling
        of `householdIdOf` — **abstract**, not a silent `default`, same Fail-Fast reasoning as
        `householdIdOf`).
  - [x] `ItemSuggestionReadModel` port: `suggestionsOf` now returns the `defaultStore`; add package-doc
        note that `recordDefaultStore` is the only writer of that column.
- [x] **Task 8: JDBC adapters** (`adapter.out`, mirror existing methods)
  - [x] `JdbcItemReadModel`: `itemsOf` selects `store_id` → `ItemView`; `insertItem` leaves `store_id`
        NULL (unchanged INSERT — a new item is unassigned); `updateItem` **must not touch `store_id`**
        (Cl. 7 — it only sets name/note/qty); new `assignStore(itemId, storeId)`:
        `UPDATE item_read_model SET store_id = :storeId WHERE item_id = :itemId`; new `nameOf(itemId)`:
        `SELECT name FROM item_read_model WHERE item_id = :itemId` (`.optional()`).
  - [x] `JdbcItemSuggestionReadModel`: `suggestionsOf` selects `default_store_id` → `ItemSuggestionView`;
        `recordUsage`'s upsert **unchanged** (must NOT set/clear `default_store_id`, Cl. 7); new
        package-private `recordDefaultStore(HouseholdId, ItemName, StoreId)`:
        `INSERT ... (household_id, normalized_name, name, quantity_amount, quantity_unit, default_store_id)
        VALUES (...) ON CONFLICT (household_id, normalized_name) DO UPDATE SET default_store_id = :storeId`
        — but the row already exists (the name was added before it could be assigned), so in practice
        this only ever hits the `DO UPDATE` branch; still provide sane INSERT values (or, simpler and
        DRYer, `UPDATE item_suggestion_read_model SET default_store_id = :storeId WHERE household_id = :hh
        AND normalized_name = :norm` — the row is guaranteed present; **prefer the plain UPDATE**, and
        note why: the suggestion row is created by `recordUsage` on the item's earlier `ItemAdded`).
- [x] **Task 9: extend `ShoppingListReadModelProjector`** (mirror the existing item cases)
  - [x] Add `case ItemAssignedToStore assigned ->`: `itemReadModel.assignStore(assigned.itemId(),
        assigned.storeId())` **and** record the suggestion default store — resolve the name via
        `itemReadModel.nameOf(assigned.itemId())`; if present, `itemSuggestionReadModel.recordDefaultStore(
        assigned.householdId(), name, assigned.storeId())`; if empty, **skip the suggestion**, still do
        the item `assignStore`, log at debug (Cl. 6 out-of-order edge).
  - [x] Confirm `project(ItemUpdated)` still calls only `updateItem` (which no longer touches `store_id`)
        + `recordUsage` (which no longer touches `default_store_id`) — no store wipe (Cl. 7).
  - [x] Extend `ShoppingListReadModelProjectorTest` (Testcontainers): `ItemAssignedToStore` sets the
        item's `store_id` **and** the suggestion's `default_store_id`; **reassign** overwrites both;
        **editing an assigned item (`ItemUpdated`) leaves `store_id` and `default_store_id` intact**
        (Cl. 7); an assign whose item row is missing (unresolvable `nameOf`) still sets the item
        `store_id` and skips the suggestion; per-household isolation (a store id never leaks across
        households); an explicit **no-PII** assertion is already covered by the V8 column names.

### Backend — query + endpoint field threading (AC1, AC3, AC6, AC7)

- [x] **Task 10: `ListItems` + `ItemController.ItemResponse` carry `storeId`**
  - [x] `ListItems.ItemSummary` → add nullable `String storeId`; `toSummary` maps
        `item.storeId() == null ? null : item.storeId().toString()`. `ItemController.ItemResponse` →
        add `storeId`; map it in `list(...)`. Extend `ListItemsTest` + `ItemControllerTest` GET cases.
- [x] **Task 11: `ListItemSuggestions` + `ItemSuggestionController.ItemSuggestionResponse` carry
      `defaultStoreId`**
  - [x] `ListItemSuggestions.ItemSuggestionSummary` → add nullable `String defaultStoreId`; map from
        `ItemSuggestionView.defaultStore`. `ItemSuggestionResponse` → add `defaultStoreId`. Extend
        `ListItemSuggestionsTest` + `ItemSuggestionControllerTest`.
- [x] **Task 12: ArchUnit** — run `HexagonalArchitectureTest`; confirm the new event/command/handler,
      the read-model port additions, the query field, and the controller endpoint need **no** rule
      change (event/port in `..domain..`, command+handler in `..application..`, controller imports no
      `..domain..` — `AssignStoreRequest` uses plain `String`s).

### Flutter — data layer (AC1, AC4, AC6)

- [x] **Task 13: model + API extensions** — `features/lists/data/`
  - [x] `Item` → add nullable `String? storeId`; `Item.fromJson` reads it (nullable, like `note`);
        update `==`/`hashCode`. `ItemSuggestion` → add nullable `String? defaultStoreId`;
        `ItemSuggestion.fromJson` reads it.
  - [x] `ItemsApi` + `HttpItemsApi`: `Future<void> assignStore(String householdId, String listId,
        String itemId, {required String storeId, required String commandId})` → `PUT
        .../lists/{listId}/items/{itemId}/store` with `{storeId, commandId}` (mirror `moveItem`'s
        `POST .../move`). Extend `FakeItemsApi`.
  - [x] `item_test.dart` / `item_suggestion_test.dart`: `storeId`/`defaultStoreId` round-trip + a
        malformed (non-string, non-null) value fails fast.

### Flutter — reusable store picker (AC1, AC2, AC4, UX-DR22, Cl. 2/8)

- [x] **Task 14: `store_picker_sheet.dart` (new, reusable)** — `features/stores/presentation/`
  - [x] A bottom sheet listing the household's **active** stores (each a 48px tap target, chain label
        via the reference cache like manage-stores) + a persistent **„+ Neues Geschäft"** inline-create
        row: a name field with the **live advisory chain suggestion** (`StoreChainMatcher` +
        `StoreChainReferenceCache`, accept/change/clear — reuse the Story 1.8 pieces; do **not**
        duplicate `StoreChainMatcher`). On „hinzufügen" it calls `StoresApi.addStore` (unique-name rule
        server-side) and, on success, returns the new store as the selection. On tapping an existing
        store it returns that store. Returns the chosen `StoreSummary` (id + name) to the caller.
        Keys: `store-picker-sheet`, `store-picker-option-{storeId}`, `store-picker-new-name-field`,
        `store-picker-add-new`.
  - [x] Keep it focused (Cl. 8): single-select, returns one store; no trip multi-select (Story 3.1).
        Depends only on `StoresApi` + `StoreChainReferenceCache` + `StoreChainMatcher` (+ the household's
        already-loaded active stores passed in) so tests use fakes (CLAUDE.md §6).
  - [x] `store_picker_sheet_test.dart` (widget, fakes): shows active stores; tapping one returns it;
        the „+ Neues Geschäft" flow with a live chain suggestion creates + returns a store; a duplicate
        name surfaces the inline error without closing; a blank name is guarded client-side.

### Flutter — list-detail cubit + page (AC1, AC4, AC5, AC6, Cl. 9)

- [x] **Task 15: `ListDetailCubit` — load stores, assign, prefill** (`list_detail/`)
  - [x] Add a `StoresApi` dependency. In `bootstrap()` (Open list only, AC5 — a Done list neither loads
        stores nor shows chips) load the household's active stores after items; a stores-load failure
        must **not** fail the screen (items still render; chips degrade to „+ Geschäft" — log/ignore,
        like the 2.5 suggestions-load degrade). Hold `List<StoreSummary> stores` in `ListDetailState`.
  - [x] `StoreSummary? storeFor(String? storeId)`: resolve an item's `storeId` against `state.stores`;
        returns `null` for unassigned **or an archived/absent id** (AC4 — the „+ Geschäft" fallback is
        "no active store resolved").
  - [x] `Future<void> assignStore(String itemId, String storeId)` with a new `_assignIntent =
        CommandIntent()` keyed on `(itemId, storeId)` (reused across retries, freshened on change + after
        success — the Epic-1 spent-command-id footgun). Guard `ready && !isSubmitting && !isReadOnly`.
        Optimistically set the item's `storeId` in state; on success `complete()`; on failure revert +
        inline `actionError`. Optionally upsert the local suggestion cache's `defaultStore` for that
        item's name (read-your-writes for the „zuletzt" chip) — via `ItemSuggestionCache`.
  - [x] `Future<void> addItemFromSuggestion(ItemSuggestion s)`: add via the existing `addItem` path
        (prefilled name/note/qty — Story 2.5), then **if `s.defaultStoreId` resolves to an active store**
        (`storeFor` non-null), `assignStore(addedItemId, s.defaultStoreId!)` (add-then-assign, AC6). Needs
        the just-added item id — have `addItem` return it (or expose it) rather than only a `bool`; keep
        the „add as new" path (no suggestion → no store) unchanged. An archived last-used store is
        skipped (item stays unassigned, AC4).
  - [x] `list_detail_state.dart` → add `stores`; thread through `copyWith`/`ready`/`loading`/`failure`.
- [x] **Task 16: `list_detail_page.dart` — the store chip + picker wiring** (AC1, AC4, AC5)
  - [x] Each `_ItemRow` renders a **store chip** (`storeFor(item.storeId)?.name`) or a ghost **„+
        Geschäft"** chip when unresolved (AC4). On an **Open** list the chip is tappable → open
        `showStorePicker(...)` → on a returned store, `cubit.assignStore(item.itemId, store.storeId)`.
        On a **Done** list the chip is **not** tappable and no picker opens (AC5) — mirror the existing
        `isReadOnly` gating of edit/remove/move. Key: `item-store-chip-{itemId}`.
  - [x] The `push(...)` route must **re-provide `StoresApi`** (+ the chain reference cache) alongside the
        existing `ItemsApi`/`ItemSuggestionsApi`/`ShoppingListsApi`, and construct `ListDetailCubit` with
        `storesApi` (mirror the 2.5 `ItemSuggestionsApi` re-provide). Update the first-run router /
        `lists_view` providers accordingly.
  - [x] `FastAddField` suggestion rows: when `suggestion.defaultStoreId` resolves to an active store,
        show the **„zuletzt {Geschäft}" chip** (AC6); tapping the suggestion routes through
        `cubit.addItemFromSuggestion(s)` (add-then-assign).
- [x] **Task 17: localization** — `l10n`
  - [x] `app_de.arb` (+ `flutter gen-l10n`): the „+ Geschäft" ghost chip (`itemStoreUnassignedChip`),
        the picker title („Geschäft wählen"), reuse/add „+ Neues Geschäft" (`storeAddNewAction` — check
        if a Story 1.8 string already exists before adding), the „zuletzt {store}" suggestion chip
        (`suggestionLastUsedStore`, parameterized), and a11y labels/semantics for the tappable chip +
        picker rows (48px, UX-DR5). No hard-coded user-facing strings.

### Tests & green build (CLAUDE.md §6)

- [x] **Task 18: Flutter tests**
  - [x] `list_detail_cubit`: bootstrap loads stores; a stores-load failure still renders items;
        `storeFor` resolves active / returns null for archived-or-absent (AC4); `assignStore` optimistic
        set + revert-on-failure + intent freshening; `addItemFromSuggestion` assigns when the last-used
        store is active and **skips** when it is archived; Done list loads no stores and refuses assign.
  - [x] `list_detail_page`: an assigned row shows the store chip; an unassigned/archived row shows „+
        Geschäft"; tapping the chip on an Open list opens the picker and assigns; a Done list's chip is
        inert; a suggestion with an active last-used store shows „zuletzt …" and add-then-assigns.
  - [x] `store_picker_sheet` (Task 14). Extend `FakeItemsApi` (assignStore) + the list-detail
        page/provider tests to provide `StoresApi`.
- [x] **Task 19: full-suite green** — backend `./gradlew test` (incl. `HexagonalArchitectureTest`
      ArchUnit + the Testcontainers `ShoppingListReadModelProjectorTest`) **and** Flutter `flutter
      analyze` + `flutter test`, both in full (not per-file), per CLAUDE.md §6. State which suite ran
      and the counts (baseline: backend **392**, Flutter **373**).

### Review Findings

_(bmad-code-review, Opus 4.8, 2026-08-28 — 3 layers: Blind Hunter + Edge Case Hunter + Acceptance Auditor. 3 patches, 3 dismissed. Backend was well-guarded; every finding of substance is on the Flutter optimistic-state side.)_

- [x] [Review][Patch] Inline-created store is not merged into `state.stores`, so an item assigned to a just-created store immediately renders the „+ Geschäft" ghost chip as if the assign failed (breaks the AC1/AC2 one-flow promise + Cl. 9 read-your-writes) — the new store is also absent from a re-opened picker and the „zuletzt" resolution until a full reload. **Fixed:** `assignStore` now takes an optional `StoreSummary store`; the page passes the picker's return, and an unknown store is merged into `state.stores` in the optimistic emit. Regression test `registersAnInlineCreatedStoreSoTheAssignedChipResolves`. [app/lib/features/lists/presentation/list_detail/list_detail_page.dart:170] [app/lib/features/lists/presentation/list_detail/list_detail_cubit.dart:412]
- [x] [Review][Patch] Editing an assigned item optimistically wipes its store chip (client-side Cl. 7 violation): `updateItem` rebuilt the `Item` without `storeId`, so the row flipped to „+ Geschäft" until the screen re-bootstrapped (backend preserved it — a pure read-your-writes divergence). **Fixed:** the optimistic rebuild now carries `item.storeId` forward. Regression test `updateItem_preservesTheItemsStoreAssignmentOptimistically`. [app/lib/features/lists/presentation/list_detail/list_detail_cubit.dart:265]
- [x] [Review][Patch] `apply(ItemAssignedToStore)` dereferenced `existing.name()`/`.note()`/`.quantity()` with no null guard, while its sibling `apply(ItemUpdated)` was made null-tolerant in this same diff. Unreachable via the command path, a defensive-consistency gap. **Fixed:** skip the put when `existing == null`, mirroring the `ItemUpdated` null-tolerance. [backend/src/main/java/de/sgart/collaboration/domain/ShoppingList.java:305]

_Dismissed (with rationale):_ (1) `addItemFromSuggestion` returns `true`/clears the field even if the follow-on assign fails — by design per its docstring (the add did succeed; the assign surfaces its own `actionError`). (2) `storeFor` collapsing archived vs. unassigned means a transient stores-load failure skips the suggestion prefill — self-consistent, since the same failure also hides the „zuletzt" chip, so nothing is falsely promised (documented AC4 degradation). (3) `assignStore`'s `isSubmitting` guard silently drops a chip tap while another command is in flight — consistent with the established add/update/remove/move serialization pattern (commands must serialize on the stream version).

## Dev Notes

### What is (and isn't) in this story — read first

2.6 is a **full vertical slice** with a genuine write side (unlike the read-only Story 2.5). It adds:
one **domain event** (`ItemAssignedToStore`), one **command + handler** (`AssignItemToStore`), one
**aggregate method** (`ShoppingList.assignItemToStore`), one **migration** (V8 — two `ALTER`s), read-
model/query/controller field threading, and the Flutter store chip + **reusable picker** with inline
store creation. It also lands the **default-store prefill** deferred from Story 2.5 (Cl. 5). It does
**not** touch the `Household` aggregate's write side (inline creation reuses the existing `AddStore`),
does **not** add a process manager (Cl. 1), and does **not** change Story 2.4's move (Cl. 4).

The two crux ideas:

- **Assignment is a reference stored inside `ShoppingList`, not a cross-aggregate effect (Cl. 1).**
  The `Item` gets a bare `StoreId`. The aggregate never checks the store exists — validity is the
  client picker's job (offers only active stores) and the read-side fallback's (an archived id renders
  as „+ Geschäft", AC4). This mirrors `moveItem`, which stores a `targetListId` it never validates.
- **An edit must not wipe the assignment (Cl. 7).** `store_id` / `default_store_id` are written **only**
  by `ItemAssignedToStore`; `ItemAdded` sets them null, `ItemUpdated` leaves them alone — in the
  aggregate fold *and* in the two JDBC read models. This is the one silent-regression trap; test it.

Flow (assign an item, then prefill from history):

```
member taps the „+ Geschäft" chip on „Batterien AA"
  → store picker sheet: [Edeka] [Netto] … [ + Neues Geschäft ]
  → tap [Edeka]  (or „+ Neues Geschäft" → StoresApi.addStore → StoreAdded on household-{id}, then ↓)
      → cubit.assignStore(itemId, edekaId)
          → PUT …/lists/{id}/items/{itemId}/store {storeId, commandId}
          → AssignItemToStore → ShoppingList.assignItemToStore → ItemAssignedToStore on list-{id}
  KurrentDB $all ──filter list-*──▶ ShoppingListReadModelProjector.project(ItemAssignedToStore)
      → itemReadModel.assignStore(itemId, edekaId)                      (V6 store_id)
      → name = itemReadModel.nameOf(itemId)  ("Batterien AA")
      → itemSuggestionReadModel.recordDefaultStore(household, name, edekaId)  (V7 default_store_id)

later: member types „Batter…" → suggestion [Batterien AA · 4 St. · zuletzt Edeka]
  → tap → addItem(…) then (Edeka still active) assignStore(newItemId, edekaId)   ← add-then-assign
```

### Architecture patterns & constraints

- **AD-10 entity-inside-aggregate + AD-3 reference-by-id.** `Item` is inside `ShoppingList`; `Store`
  is inside `Household`. Assignment stores the `Store`'s id inside the `Item` — no cross-aggregate
  load, no FK (Cl. 1). [ARCHITECTURE-SPINE.md #AD-3/#AD-10]
- **AD-8 online load-then-append.** `AssignItemToStoreHandler` reads the `list-{id}` stream, uses the
  loaded version as the expected version, appends (a same-store no-op appends nothing). The client
  sends only `{storeId, commandId}` — no client `basedOnVersion` (the handler computes it), exactly
  like every existing item command. Add-then-assign is therefore just two sequential API calls. [#AD-8]
- **AD-4 CQRS read-model-only.** `store_id` / `default_store_id` are written only by the projector; the
  queries have no side effects. [#AD-4]
- **AD-5/AD-6 no PII.** A store id is household content, not a person — the new columns mirror
  `item_read_model` AC9; `household_id` remains the erasure locator. [#AD-5/#AD-6]
- **AD-11 ubiquitous language.** `assignItemToStore` / `ItemAssignedToStore` / „Geschäft"; „zuletzt"
  for last-used; „+ Neues Geschäft" for inline creation (matches Story 1.8). No abbreviations. [#AD-11]
- **Eventual consistency (AR3/NFR9).** The `store_id` projection lags the assign — the optimistic
  local set (cubit) covers read-your-writes for the just-assigned chip and the „zuletzt" prefill.

### The `ItemUpdated`-preserves-store trap (Cl. 7) — do not skip

Three places replace/upsert an item's attributes and must **carry the store forward**, not reset it:
1. `ShoppingList.apply(ItemUpdated)` — copy `assignedStore` from the existing `ItemState`.
2. `JdbcItemReadModel.updateItem` — its `UPDATE` sets name/note/qty only, never `store_id`.
3. `JdbcItemSuggestionReadModel.recordUsage` — its upsert sets name/note/qty only, never
   `default_store_id`.
Only `ItemAssignedToStore` → `apply(ItemAssignedToStore)` / `assignStore` / `recordDefaultStore` write
the store columns. A single Testcontainers test (edit an assigned item → still assigned) plus one
aggregate test guard this.

### Source tree — mirror these exact files

| New/changed | Mirror | Path |
| --- | --- | --- |
| `ItemAssignedToStore` (event) | `ItemMovedToList` / `ItemAdded` | `collaboration/domain/event/` |
| `ShoppingList.assignItemToStore` + `ItemState.assignedStore` | (extend) `moveItem`/`apply` | `collaboration/domain/` |
| `AssignItemToStore` + `AssignItemToStoreHandler` | `UpdateItem` + `UpdateItemHandler` | `collaboration/application/command/` |
| `CollaborationApplicationConfig` (wire) | (extend) | `collaboration/adapter/out/` |
| `ItemController` (`PUT .../store`) | (extend) the `move` endpoint | `collaboration/adapter/in/` |
| `V8__item_store_assignment.sql` | `V6`/`V7` | `backend/.../resources/db/migration/` |
| `ItemView.storeId` / `ItemSuggestionView.defaultStore` | (extend) | `collaboration/domain/readmodel/` |
| `ItemReadModel.assignStore`/`nameOf`, `ItemSuggestionReadModel` | (extend, mirror `householdIdOf`) | `collaboration/domain/readmodel/` |
| `JdbcItemReadModel` / `JdbcItemSuggestionReadModel` | (extend) | `collaboration/adapter/out/` |
| `ShoppingListReadModelProjector` (`ItemAssignedToStore` case) | (extend) | `collaboration/adapter/out/` |
| `ListItems.ItemSummary.storeId` / `ListItemSuggestions.…defaultStoreId` | (extend) | `collaboration/application/query/` |
| `Item.storeId` / `ItemSuggestion.defaultStoreId` + `ItemsApi.assignStore` | (extend) `item.dart`/`items_api.dart` | `app/lib/features/lists/data/` |
| `store_picker_sheet.dart` (new, reusable) | `stores_management_view.dart` (chain-suggestion pieces) | `app/lib/features/stores/presentation/` |
| `ListDetailCubit` (stores/assign/prefill) + `ListDetailState.stores` | (extend) | `app/lib/features/lists/presentation/list_detail/` |
| `list_detail_page.dart` (store chip + picker), `FastAddField` („zuletzt" chip) | (extend) | `app/lib/features/lists/presentation/list_detail/` |

### Package structure (CLAUDE.md §8)

New classes drop into the **existing** intent subpackages (`domain.event`, `application.command` with
the DTO beside its handler, `domain.readmodel`). The assignment projection lives inside the existing
`ShoppingListReadModelProjector` (same `list-` source stream — DRY, no second subscription). No new
subpackage earns its keep (KISS). No ArchUnit rule change (rules match `..domain..`/`..application..`).

### Testing standards

- **Domain first:** `ShoppingListTest` — fast, pure, no infra (assign, reassign, same-store no-op,
  Done-refuses, unknown-item, edit-preserves-store).
- **Handler:** in-memory `EventStore` + fake `ResolveMemberIdentity` — 403/400/404/no-op mapping.
- **Projector/read model:** Testcontainers (`ShoppingListReadModelProjectorTest`) — store_id +
  default_store_id set/reassigned; **edit preserves both** (Cl. 7); unresolvable name skips suggestion;
  per-household isolation.
- **Query/controller:** fast unit + MockMvc slices — field threading + 204/403/400/404.
- **Flutter:** fakes only, no network — `storeFor` (active/archived/absent), `assignStore` optimistic
  + revert + intent, `addItemFromSuggestion` (assign vs. skip archived), Done-list inertness, the
  reusable picker (existing tap, inline create with chain suggestion, duplicate, blank guard).
- **DSGVO:** synthetic German data only; explicit no-PII stance on the new columns (AC7).
- **Green build = full suite** for both modules; state which ran and the counts.

### Deferred / do-not-build (premature-value discipline)

- **„Zuordnung entfernen" (clear-to-unassigned)** → post-MVP (Cl. 3 — reassign covers correction). Log
  in `deferred-work.md`.
- **Assignment surviving a move** → not built (Cl. 4 — a moved item starts unassigned).
- **Trip store-grouping / print grouping (AC3 consumers)** → Epic 3 (Stories 3.1/3.2/3.5). 2.6 makes
  the assignment durable + queryable; no trip/print UI here.
- **Live-sync refresh of the client store/suggestion caches** → Epic 4 (SSE). MVP refetches on open;
  a fresh assign optimistically updates the local caches.
- **Picker multi-select (trip needs ≥1 store)** → Story 3.1 extends the reusable picker (Cl. 8).
- **Recency ranking / fuzzy suggestions** → still deferred (Story 2.5 Cl. 6).
- **FK / `ON DELETE` for `store_id`, `item_read_model` cleanup** → Epic-6 erasure (already logged from
  Story 2.3 review).

### Project Structure Notes

- The assign endpoint is **nested under the item** (`PUT
  /api/v1/households/{householdId}/lists/{listId}/items/{itemId}/store`) — assignment is an item
  command on the list aggregate (mirrors the `move` endpoint on `ItemController`). Inline store
  creation uses the **household-scoped** `StoreController` (`POST .../households/{householdId}/stores`,
  Story 1.8) — two endpoints, two aggregates, client-orchestrated (Cl. 2).
- V8 is additive (`ALTER TABLE ADD COLUMN … NULL`) — no backfill, no rewrite of V6/V7. Existing rows
  read as unassigned.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story-2.6] — user story + BDD ACs (FR5 store-org, FR3 inline creation).
- [Source: _bmad-output/planning-artifacts/epics.md#Story-1.8 AC5/AC E6] — "any store picker … same creation rules"; archived-store fallback to „Noch nicht zugeordnet".
- [Source: ARCHITECTURE-SPINE.md #AD-3/#AD-4/#AD-8/#AD-10/#AD-11 + #AR2] — reference-by-id, CQRS read-model-only, online load-then-append, entity-inside-aggregate, ubiquitous language.
- [Source: ux-designs/ux-sgart-2026-08-20/DESIGN.md UX-DR8/UX-DR22; EXPERIENCE.md; .working/screen-list-detail.html] — the store chip / „+ Geschäft" ghost chip, the reusable store picker with inline „+ Neues Geschäft", the suggestion „zuletzt Edeka" chip.
- [Source: backend `ShoppingList.java` (moveItem/apply/ItemState), `ItemAdded.java`, `ItemMovedToList.java`, `UpdateItem.java`, `UpdateItemHandler.java`, `AddItemHandler.java`, `ItemController.java`, `ListItems.java`, `ItemReadModel.java` (householdIdOf), `ItemView.java`, `ItemSuggestionReadModel.java`, `ItemSuggestionView.java`, `ShoppingListReadModelProjector.java`, `CommandFieldTranslations.java` (toStoreId), `V6__item_read_model.sql`, `V7__item_suggestion_read_model.sql`, `StoreAdded.java`, `StoreView.java`] — the exact patterns to mirror + the `ItemUpdated`-replaces-state fact (Cl. 7).
- [Source: app `lists/data/item.dart`, `items_api.dart`, `item_suggestion.dart`; `lists/presentation/list_detail/*`; `stores/presentation/stores_cubit.dart`, `stores_management_view.dart`; `stores/data/store_summary.dart`, `store_chain_matcher.dart`, `store_chain_reference_cache.dart`, `stores_api.dart`] — client patterns to mirror; the Story 1.8 chain-suggestion pieces the picker reuses.
- [Source: _bmad-output/implementation-artifacts/2-3-…md, 2-4-…md, 2-5-…md] — the item slice this extends; `CommandIntent`/`isSubmitting`, the 2.4 create-then-move two-step, the 2.5 `householdIdOf` lookup + suggestion read model.
- [Source: _bmad-output/implementation-artifacts/deferred-work.md] — the Story 2.5 default-store defer (now realized here); where to log the „Zuordnung entfernen" post-MVP option.
- [Source: CLAUDE.md §1–§8] — Clean Code, naming, DDD, CQRS, DSGVO, testing, package structure.

## Dev Agent Record

### Agent Model Used

_(dev-story — Sonnet 5)_

### Debug Log References

- Backend full suite green: `./gradlew test` — 415 tests (incl. `HexagonalArchitectureTest` ArchUnit
  and the Testcontainers `ShoppingListReadModelProjectorTest`), up from the 392 baseline.
- Flutter full suite green: `flutter analyze` (no issues) + `flutter test` — 416 tests, up from the
  373 baseline.

### Completion Notes List

- Full vertical slice landed as planned: `ItemAssignedToStore` event + `ShoppingList.assignItemToStore`
  (convergent no-op on same-store reassign, `ItemChangeNotPermittedException` on non-Open, mirrors
  `moveItem`'s no cross-aggregate validation) + `AssignItemToStore`/`AssignItemToStoreHandler` +
  `PUT .../items/{itemId}/store`.
- V8 (`item_read_model.store_id`, `item_suggestion_read_model.default_store_id`) + projector
  `ItemAssignedToStore` case (`assignStore` + `recordDefaultStore` via new `ItemReadModel.nameOf`
  lookup, out-of-order-replay-safe skip mirroring the Story 2.5 `householdIdOf` pattern).
- Cl. 7 regression trap covered explicitly: `ShoppingList.apply(ItemUpdated)` now carries the folded
  `assignedStore` forward; `JdbcItemReadModel.updateItem` and `JdbcItemSuggestionReadModel.recordUsage`
  are untouched (never write the store columns) — proven by a dedicated aggregate test
  (`updatingAnAssignedItemPreservesItsStoreAssignment`) and a dedicated projector test
  (`editingAnAssignedItemLeavesTheStoreIdAndDefaultStoreIdIntact`).
- `ListItems`/`ListItemSuggestions` + their controllers thread `storeId`/`defaultStoreId` end to end.
- Flutter: new reusable `store_picker_sheet.dart` (`features/stores/presentation/`) — active-store
  list + persistent "+ Neues Geschäft" inline row reusing `StoreChainMatcher`/
  `StoreChainReferenceCache` (no duplicated chain-matching logic), single-select, returns/creates a
  `StoreSummary` (Cl. 8). Added `AuthenticatedHttpClient.putJson` (mirrors `patchJson`) for the new
  `PUT .../store` call.
- `ListDetailCubit`: loads active stores on `bootstrap()` (Open list only, best-effort degrade on
  failure, mirrors the suggestions-load degrade); `storeFor` resolves an id against the active list
  (`null` = unassigned or archived, AC4); `assignStore` optimistic-sets + reverts-on-failure + a
  dedicated `CommandIntent`; `addItem` was refactored behind a private `_addItemInternal` returning
  the added item's id (kept the public `addItem` bool signature to avoid churning ~15 existing
  call-sites) so `addItemFromSuggestion` can add-then-assign when the suggestion's `defaultStoreId`
  resolves to an active store (AC6), skipping silently when archived (AC4).
- `ItemSuggestionCache` gained `withDefaultStore` (the client mirror of `recordDefaultStore`) and
  `upserted` now carries an existing entry's `defaultStoreId` forward — the client-side mirror of the
  Cl. 7 protection, so an edit/add never wipes the "zuletzt" chip either.
- `list_detail_page.dart`: item rows render a tappable store chip (`item-store-chip-{itemId}`, 48px
  target, ghost "+ Geschäft" when unresolved) that opens the picker on an Open list and is inert on
  Done (AC5); `push()` re-provides `StoresApi` + `StoreChainReferenceCache` alongside the existing
  APIs. `fast_add_field.dart`: suggestion rows show a "zuletzt {Geschäft}" chip when the last-used
  store is still active, and tapping a suggestion now routes through `addItemFromSuggestion`.
- New l10n keys added (`itemStoreUnassignedChip`, `itemStoreAssignAction`, `storePickerTitle`,
  `storePickerAddNewAction`, `storePickerNewStoreNameFieldLabel`, `storePickerAddSubmitButtonLabel`,
  `suggestionLastUsedStore`); the picker's duplicate/blank error codes reuse the existing
  `store.nameRequired`/`store.nameTooLong`/`store.duplicateName` mappings (no new error-resolver
  entries needed).
- Deferred to `deferred-work.md` (Cl. 3): a member-facing "Zuordnung entfernen" (clear-to-unassigned)
  action, post-MVP.

### File List

**Backend — new**
- `backend/src/main/java/de/sgart/collaboration/domain/event/ItemAssignedToStore.java`
- `backend/src/main/java/de/sgart/collaboration/application/command/AssignItemToStore.java`
- `backend/src/main/java/de/sgart/collaboration/application/command/AssignItemToStoreHandler.java`
- `backend/src/main/resources/db/migration/V8__item_store_assignment.sql`
- `backend/src/test/java/de/sgart/collaboration/application/AssignItemToStoreHandlerTest.java`

**Backend — changed**
- `backend/src/main/java/de/sgart/collaboration/domain/ShoppingList.java`
- `backend/src/main/java/de/sgart/collaboration/domain/readmodel/ItemView.java`
- `backend/src/main/java/de/sgart/collaboration/domain/readmodel/ItemSuggestionView.java`
- `backend/src/main/java/de/sgart/collaboration/domain/readmodel/ItemReadModel.java`
- `backend/src/main/java/de/sgart/collaboration/domain/readmodel/ItemSuggestionReadModel.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/JdbcItemReadModel.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/JdbcItemSuggestionReadModel.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/ShoppingListReadModelProjector.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/DomainEventJsonCodec.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/CollaborationApplicationConfig.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/in/ItemController.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/in/ItemSuggestionController.java`
- `backend/src/main/java/de/sgart/collaboration/application/query/ListItems.java`
- `backend/src/main/java/de/sgart/collaboration/application/query/ListItemSuggestions.java`
- `backend/src/test/java/de/sgart/collaboration/domain/ShoppingListItemsTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/out/ShoppingListReadModelProjectorTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/out/DomainEventJsonCodecTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/in/ItemControllerTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/in/ItemSuggestionControllerTest.java`
- `backend/src/test/java/de/sgart/collaboration/application/ListItemsTest.java`
- `backend/src/test/java/de/sgart/collaboration/application/ListItemSuggestionsTest.java`

**Flutter — new**
- `app/lib/features/stores/presentation/store_picker_sheet.dart`
- `app/test/features/stores/presentation/store_picker_sheet_test.dart`

**Flutter — changed**
- `app/lib/shared/http/authenticated_http_client.dart`
- `app/lib/features/lists/data/item.dart`
- `app/lib/features/lists/data/item_suggestion.dart`
- `app/lib/features/lists/data/items_api.dart`
- `app/lib/features/lists/presentation/list_detail/list_detail_state.dart`
- `app/lib/features/lists/presentation/list_detail/list_detail_cubit.dart`
- `app/lib/features/lists/presentation/list_detail/list_detail_page.dart`
- `app/lib/features/lists/presentation/list_detail/fast_add_field.dart`
- `app/lib/features/lists/presentation/list_detail/item_suggestion_cache.dart`
- `app/lib/l10n/app_de.arb`
- `app/test/support/fake_items_dependencies.dart`
- `app/test/features/lists/data/item_test.dart`
- `app/test/features/lists/data/item_suggestion_test.dart`
- `app/test/features/lists/presentation/list_detail/list_detail_cubit_test.dart`
- `app/test/features/lists/presentation/list_detail/list_detail_page_test.dart`
- `app/test/features/lists/presentation/list_detail/fast_add_field_test.dart`
- `app/test/features/lists/presentation/list_detail/item_suggestion_cache_test.dart`
- `app/test/features/lists/presentation/list_detail/move_merge_dialog_test.dart`
- `app/test/features/lists/presentation/list_detail/move_target_sheet_test.dart`
- `app/test/features/lists/presentation/list_overview/lists_view_test.dart`

**Story tracking**
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `_bmad-output/implementation-artifacts/deferred-work.md`

## Change Log

- 2026-08-28: Story drafted (create-story, Opus 4.8) — full vertical slice: new `AssignItemToStore`
  command / `ItemAssignedToStore` event on `ShoppingList`, `item_read_model.store_id` +
  `item_suggestion_read_model.default_store_id` (V8), reusable store picker with inline `AddStore`
  creation, and the default-store prefill deferred from Story 2.5. 9 LOCKED clarifications; Timo decided
  (2026-08-28): build the default-store prefill here (2.6 is Epic 2's last story), reassign-only (no
  explicit clear), and a moved item starts unassigned. Baseline backend 392 / Flutter 373.
- 2026-08-28: Story implemented (dev-story, Sonnet 5) — full vertical slice landed as planned: write
  side (event/command/handler/aggregate method), V8 read-side (item store_id + suggestion
  default_store_id) with the Cl. 7 preserve-on-edit trap covered by dedicated aggregate + projector
  tests, query/controller field threading, and the Flutter reusable store picker + list-detail chip +
  fast-add "zuletzt" chip + add-then-assign. Backend 392→415 green (incl. ArchUnit + Testcontainers).
  Flutter 373→416 green (incl. analyze).
