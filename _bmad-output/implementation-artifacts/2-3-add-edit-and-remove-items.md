---
baseline_commit: bb78d318cae4dfcbc9b7cd8db422c494165f08d0
---

# Story 2.3: Add, edit, and remove items

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a member,
I want to add items to an Open list with a quantity and an optional note, and later edit or remove them,
so that the list captures exactly what to buy.

## Acceptance Criteria

Derived from **epics.md § Story 2.3** (FR5), refined against **ARCHITECTURE-SPINE.md** (AD-4, AD-8,
AD-9, AD-10, AD-11) and the existing Story 2.1/2.2 slice. Each AC is independently testable.

1. **Add an item (AC1).** Given an **Open** list, when a member adds an item with a **name**, a
   **Quantity** (`amount` + `Unit`), and an **optional note**, then the item is created **inside the
   `ShoppingList` aggregate** (AD-10) via an `AddItem` command that raises `ItemAdded`. `amount` must
   be **> 0** and `Unit` must be from the controlled vocabulary (`Unit` enum, AD-9) — a non-positive
   amount or a free-text/unknown unit is a fail-fast **400**, never persisted. The `itemId` is minted
   **client-side** and carried in the envelope (read-your-writes, like `listId`/`storeId`).

2. **Exact duplicate rejected; name+note is the key (AC2).** Items are keyed by **(name, note)**.
   Given a list that already holds an item with the same name **and** the same note, when a member
   adds an exact duplicate, then it is **rejected (409)**. „Milch" with note „Bio" and „Milch" with no
   note **coexist** (different keys). Comparison is trimmed and case-insensitive (mirrors
   `Household.addStore`'s active-name uniqueness); an absent note is a distinct key from any present
   note.

3. **Edit an item (AC3).** Given an existing item on an **Open** list, when a member updates its
   name, note, and/or quantity, then the change applies via an `UpdateItem` command raising
   `ItemUpdated`. An update that leaves name+note+quantity unchanged is a **convergent no-op** (raises
   nothing, mirrors `rename`). An update whose new (name, note) would **collide with a different**
   existing item is **rejected (409)**. Updating a **missing** item is a **404**.

4. **Remove an item (AC4).** Given an existing item on an **Open** list, when a member removes it,
   then a `RemoveItem` command raises `ItemRemoved` and the read-model row is deleted. Removing an
   **unknown/already-removed** item is a **convergent no-op (204)** (idempotent delete, AD-8; mirrors
   `archiveStore`).

5. **A Done list accepts no item commands (AC5).** Add / update / remove targeting a list that is not
   `OPEN` is **rejected (403)** by the aggregate (`ItemChangeNotPermittedException`). The guard is
   coded and unit-tested against a synthetic `DONE` state; a **reachable**-`DONE` end-to-end test is
   **deferred to Epic 3** (no Epic-2 event drives a list out of `OPEN` — same reachability caveat as
   `rename`'s `DONE` branch, Story 2.1 Clarification 1). `IN_TRIP` item behaviour is an Epic-3
   decision — 2.3 permits item commands **only** while `OPEN`.

6. **List detail screen (AC6).** Tapping a list row on the Listen overview opens a **list detail
   screen** showing the list's items in creation order — each row shows **name · quantity · optional
   note** — with an empty state, an add affordance, and per-row edit/remove affordances. A **Done**
   list opens **read-only** (no add/edit/remove). Off-trip there is **no check/uncheck and no
   postpone** (buying/deferral are Epic 3) and **no store assignment** (Story 2.6) — those affordances
   do not appear.

7. **Overview item count (AC7).** Each **Open** row on the Listen overview shows its **item count**
   (0 for an empty list). The checked/total **progress bar stays Epic 3** (no check-off capability
   exists yet). Archive (Done) rows show no count/progress (Epic-3 polish).

8. **Membership, isolation, envelope & concurrency (AC8).** Every item endpoint is
   **membership-gated** (non-member → **403**); a `listId` under a **different** household is a
   **404** (defense-in-depth, like `RenameShoppingListHandler`); a malformed id/quantity/envelope is
   **400**; the append uses the **loaded** list-stream version as the expected version (online
   load-then-append, AD-8) and a lost race is **409**; the `commandId` makes retries **idempotent**
   (a retried add never double-adds).

9. **No personal data (AC9).** `ItemAdded`/`ItemUpdated`/`ItemRemoved` carry **no** `MemberId`,
   creator attribution, display name, email, or `keycloakUserId` (AD-5/AD-6; no audit trail in MVP,
   YAGNI — mirrors `ShoppingListCreated`). The `item_read_model` carries `household_id` + `list_id` so
   Epic-6 erasure can locate and scrub it. Tests use synthetic, clearly-fake data only.

## Clarifications (LOCKED)

These decisions are locked for implementation. They were taken from the epic AC + existing patterns;
if any is wrong, correct it before `dev-story`.

1. **Quantity is required; the add form defaults to `1 Stück` (PIECE).** Per AC1 and AD-9, every
   `Item` holds a non-null `Quantity`. The client add form pre-fills `amount = 1`, `unit = Stück` so
   fast capture still works — the member adjusts only when they care. `amount` is a `BigDecimal`
   (fractional allowed, e.g. `0,5 kg`); `Quantity` already rejects `amount ≤ 0` and non-`Unit` values.
   *(Confirmed with product: full vertical slice + required-quantity-with-default.)*

2. **Item identity/dedup key is (name, note), trimmed + case-insensitive; absent note ≠ present
   note.** Reject an exact duplicate on add (409) and a colliding update (409). Mirrors
   `Household.hasActiveStoreNamed` (`toLowerCase(Locale.ROOT)`). Modelled as folded state inside the
   aggregate (a private `ItemState` record keyed by `ItemId`), **exactly mirroring `StoreState`** —
   AD-10's "`Item` is an entity inside `ShoppingList`" is realised the same way `Store` is realised
   inside `Household` (consistency / Boy-Scout).

3. **Item events ride the existing `list-{id}` stream and the existing `ShoppingListReadModelProjector`.**
   No new `StreamType`, no second projector (KISS): item events already flow through the projector's
   `list-` prefix subscription. Extend the projector's `project(...)` switch and `DomainEventJsonCodec`
   with the three item events; add a new `item_read_model` table (`V6`), `JdbcItemReadModel`, and a
   `ListItems` query.

4. **Overview item count lights up now; check-off progress stays Epic 3.** 2.3 produces the item data,
   so `itemCount` is threaded through the existing overview read path (`ShoppingListView` →
   `ListOpenLists.ShoppingListSummary` → controller response → Flutter `ShoppingListSummary`) via a
   `COUNT` over `item_read_model`. This closes the deferred-work item from the 2.2 dev-story. The
   progress bar (checked/total) is **not** built (Epic 3 owns check-off).

5. **2.3 delivers a real-but-minimal list detail screen; no premature surfaces.** No autocomplete /
   attribute prefill (Story 2.5), no move-to-list (Story 2.4), no store assignment (Story 2.6), no
   check-off / postpone / progress (Epic 3). Done lists open read-only. This respects the Epic-1 retro
   premature-value rule (don't author a surface whose value depends on a later-epic capability).

## Tasks / Subtasks

> Follow TDD (CLAUDE.md §6): write the failing test first at the lowest level that proves the
> behaviour, then the simplest code to pass. Keep the domain pure (AD-1). Mirror the cited existing
> file for every new class — the patterns are established; do not invent new ones.

### Backend — shared kernel & domain (AC1–AC5, AC9)

- [x] **Task 1: `ItemId` in `shared`** (AC1, AC8)
  - [x] Add `de.sgart.shared.ItemId` mirroring `StoreId` verbatim (UUID-backed, `generate()`,
        `fromString`, `toString`). Justify in the Javadoc the same way `StoreId` does (client-minted,
        carried in the envelope; entity inside an aggregate per AD-10).
- [x] **Task 2: item value objects** (AC1, AC2, AC3)
  - [x] `de.sgart.collaboration.domain.ItemName` mirroring `ShoppingListName` (trim, non-blank,
        `MAX_LENGTH = 120`, plain `IllegalArgumentException`). Required — no nullable convention.
  - [x] `de.sgart.collaboration.domain.ItemNote` mirroring `ShoppingListName` **but nullable-by-absence**
        (an absent note is a `null` reference, not an instance — like the unnamed-list convention).
        Trim, non-blank when present, `MAX_LENGTH = 240`.
  - [x] Reuse `de.sgart.shared.Quantity` + `Unit` as-is (already present; no change).
  - [x] Unit tests: `ItemNameTest`, `ItemNoteTest` (trim, blank rejected/absent, length bound) —
        mirror `ShoppingListNameTest`.
- [x] **Task 3: item domain events** (AC1, AC3, AC4, AC9) — package `collaboration.domain.event`
  - [x] `ItemAdded(EventId, HouseholdId, ShoppingListId, ItemId, ItemName, ItemNote /*nullable*/, Quantity)`.
  - [x] `ItemUpdated(EventId, ShoppingListId, ItemId, ItemName, ItemNote /*nullable*/, Quantity)`.
  - [x] `ItemRemoved(EventId, ShoppingListId, ItemId)`.
  - [x] All `implements DomainEvent`, `Objects.requireNonNull` on non-nullable components, Javadoc noting
        the nullable note and the "no creator / no PII" stance (mirror `ShoppingListCreated`).
- [x] **Task 4: item domain exceptions** (AC2, AC3, AC5) — package `collaboration.domain.exception`
  - [x] `DuplicateItemException`, `ItemNotFoundException`, `ItemChangeNotPermittedException` —
        mirror `ListNameChangeNotPermittedException` (plain domain exceptions, message only).
- [x] **Task 5: extend the `ShoppingList` aggregate** (AC1–AC5, AC9)
  - [x] Hold items as `Map<ItemId, ItemState> itemsById` (insertion-ordered `LinkedHashMap`); private
        nested `record ItemState(ItemName name, ItemNote note, Quantity quantity)` — mirror `StoreState`.
  - [x] `addItem(ItemId, ItemName, ItemNote, Quantity, CommandId)`: null-checks (note nullable);
        `requireOpen()` else `ItemChangeNotPermittedException`; reject a (name,note) duplicate with
        `DuplicateItemException` (case-insensitive, mirror `hasActiveStoreNamed`); raise `ItemAdded`.
  - [x] `updateItem(ItemId, ItemName, ItemNote, Quantity, CommandId)`: `requireOpen()`; unknown id →
        `ItemNotFoundException`; unchanged name+note+quantity → convergent no-op (raise nothing);
        collision with a **different** item → `DuplicateItemException`; else raise `ItemUpdated`.
  - [x] `removeItem(ItemId, CommandId)`: `requireOpen()`; unknown/already-removed → convergent no-op;
        else raise `ItemRemoved`.
  - [x] `apply(...)`: fold `ItemAdded` (put), `ItemUpdated` (replace), `ItemRemoved` (remove).
  - [x] Extend `ShoppingListTest` (or a sibling `ShoppingListItemsTest`) for every branch above,
        including replay-rebuilds-identical-state and the no-personal-data assertion for the 3 events.
        Test the `DONE`-rejects branch against a **synthetic** `DONE` state only if reachable without an
        Epic-3 event; otherwise **defer** the reachable test to Epic 3 and log it (see Dev Notes →
        Deferred).

### Backend — application layer (AC1–AC5, AC8)

- [x] **Task 6: field translators** — extend `CommandFieldTranslations` (DRY; add + update share)
  - [x] `toItemId` (required, `command.itemIdRequired`/`command.itemIdInvalid`), `toItemName`
        (required), `toItemNoteOrNull` (optional — blank ⇒ `null`), `toQuantity(rawAmount, rawUnit)`
        (parse `BigDecimal` + `Unit`; bad value ⇒ fail-fast 400). Mirror `toShoppingListName` /
        `toStoreChainIdOrNull`.
- [x] **Task 7: application exceptions** (AC2, AC3, AC5) — package `application.exception`
  - [x] `DuplicateItemApplicationException` (→409), `ItemNotFoundApplicationException` (→404),
        `ItemChangeNotPermittedApplicationException` (→403), `InvalidItemNameException` (→400),
        `InvalidItemQuantityException` (→400) — mirror the existing `*ApplicationException` types
        (each carries an `ErrorDescriptor` with a client code). This is the AD-1/§8 translation seam so
        `adapter.in` never imports `..domain..`.
- [x] **Task 8: commands + handlers** (AC1, AC3, AC4, AC8) — package `application.command`, DTO beside handler
  - [x] `AddItem` + `AddItemHandler`, `UpdateItem` + `UpdateItemHandler`, `RemoveItem` +
        `RemoveItemHandler`. Each handler mirrors `RenameShoppingListHandler`: translate raw fields →
        resolve membership via `ResolveMemberIdentity` (403) → load `ShoppingList` by
        `StreamId.forList(listId)` → empty history **or** `householdId` mismatch ⇒
        `ShoppingListNotFoundException` (404) → capture loaded version → call the aggregate (catch domain
        exceptions, translate to the application exceptions from Task 7) → append under the loaded
        version + `commandId` only when `uncommittedEvents()` is non-empty (no-op skips the append).
  - [x] Handler tests (`InMemoryEventStore` + fake `ResolveMemberIdentity`): success raises the event;
        non-member 403; list-not-found 404; **cross-household 404**; duplicate 409 (add) / collision 409
        (update); update-missing 404; remove-unknown no-op (no append); idempotent retry (same
        `commandId`) does not double-append (idempotency itself is proven by the `InMemoryEventStore`
        contract, not re-tested per handler — mirrors `AddStoreHandlerTest`/`RenameShoppingListHandlerTest`).
- [x] **Task 9: `ListItems` query** (AC6, AC8) — package `application.query`
  - [x] `ListItems.forList(keycloakUserId, rawHouseholdId, rawListId)`: resolve membership (403), read
        `item_read_model` filtered by `(household_id, list_id)` in creation order; return a
        plain-`String` summary record (`ItemSummary(itemId, name, note, amount, unit)`) so `adapter.in`
        needs no domain import (mirror `ListOpenLists.ShoppingListSummary`). A list in another household
        yields an empty list (no data leak). Test: membership gate + returns items + cross-household empty.

### Backend — adapters (AC1–AC8)

- [x] **Task 10: read model port + JDBC + migration** (AC1–AC4, AC7)
  - [x] `V6__item_read_model.sql`: `item_read_model(item_id UUID PK, list_id UUID NOT NULL, household_id
        UUID NOT NULL, name VARCHAR(120) NOT NULL, note VARCHAR(240) NULL, quantity_amount NUMERIC NOT
        NULL, quantity_unit VARCHAR(20) NOT NULL, sequence_number BIGSERIAL, created_at TIMESTAMPTZ NOT
        NULL DEFAULT now())` + index on `list_id`. Header comment mirroring `V5` (CQRS/AD-4, no personal
        data, idempotent upsert keeps `sequence_number` stable).
  - [x] `domain.readmodel.ItemView(ItemId, ItemName, ItemNote /*nullable*/, Quantity)` and
        `domain.readmodel.ItemReadModel` port (`List<ItemView> itemsOf(HouseholdId, ShoppingListId)` —
        householdId added so the port itself enforces the cross-household no-data-leak guarantee).
  - [x] `adapter.out.JdbcItemReadModel implements ItemReadModel`: `itemsOf` (ordered by
        `sequence_number`), `insertItem` (`ON CONFLICT (item_id) DO NOTHING`), `updateItem`
        (`UPDATE name/note/quantity`), `removeItem` (`DELETE`). Mirror `JdbcShoppingListReadModel`
        idempotency comments.
- [x] **Task 11: thread item count through the overview read path** (AC7)
  - [x] Add `itemCount` to `ShoppingListView`; `JdbcShoppingListReadModel.listsOf` `LEFT JOIN`s a
        `COUNT` from `item_read_model` (0 when none). Thread it through
        `ListOpenLists.ShoppingListSummary` + `ListDoneLists` + `ShoppingListController`'s
        `ShoppingListSummaryResponse`. Keep Done rows carrying the count (harmless; UI hides it).
  - [x] Update the existing `ListOpenListsTest` / `ListDoneListsTest` / projector test fixtures for the
        new field.
- [x] **Task 12: extend the projector + codec** (AC1, AC3, AC4)
  - [x] `ShoppingListReadModelProjector`: inject `JdbcItemReadModel`; extend `project(...)` to fold
        `ItemAdded`→`insertItem`, `ItemUpdated`→`updateItem`, `ItemRemoved`→`removeItem`. Wire the new
        dependency in `CollaborationReadModelConfig` (the read-model/projector wiring); add the item
        handlers, `ListItems`, and `JdbcItemReadModel` bean to `CollaborationApplicationConfig` /
        `CollaborationReadModelConfig` respectively.
  - [x] `DomainEventJsonCodec`: add type tags + payload records + `toJsonBytes`/`fromJsonBytes` for the
        3 item events (note nullable, `amount` as `String` via `toPlainString`, `unit` as enum name).
        Mirror `ShoppingListCreatedPayload`.
  - [x] Extend `ShoppingListReadModelProjectorTest` for item folding (Testcontainers style, matching the
        rest of the file) + a dedicated item-count-via-`listsOf` test.
- [x] **Task 13: `ItemController` + error advice** (AC1–AC8)
  - [x] New `adapter.in.ItemController` at
        `/api/v1/households/{householdId}/lists/{listId}/items`: `POST` add (201), `GET` list, `PATCH
        /{itemId}` update (204), `DELETE /{itemId}` remove (204). Identity only from the JWT `sub` via
        `AuthenticatedCaller` (AR10/AD-5). DTOs: `AddItemRequest(itemId, name, note, amount, unit,
        commandId)`, `UpdateItemRequest(name, note, amount, unit, commandId)`, `ItemResponse(itemId,
        name, note, amount, unit)` — `amount` as decimal `String`, `unit` as enum name. Mirror
        `ShoppingListController`.
  - [x] `WriteErrorAdvice`: map the 5 new application exceptions to 409/404/403/400/400.
  - [x] `ItemControllerTest` (MockMvc slice) — happy paths + each error status + membership 403 +
        cross-household 404. Mirror `ShoppingListControllerTest`.
- [x] **Task 14: ArchUnit** — run `HexagonalArchitectureTest`; confirm the new `..domain..` /
      `..application..` subpackages need no rule change and that `adapter.in` imports no `..domain..`.

### Flutter — list detail screen + item capture (AC1–AC7)

- [x] **Task 15: item data layer** — `features/lists/data`
  - [x] `item.dart` — `Item{itemId, name, note?, amount /*String*/, unit /*String enum name*/}` with a
        fail-fast `fromJson` mirroring `ShoppingListSummary.fromJson` (mapped `AppException` on a bad
        shape; `note` nullable).
  - [x] `items_api.dart` — `ItemsApi` interface + `HttpItemsApi`: `listItems`, `addItem` (mints/carries
        `itemId`+`commandId`), `updateItem`, `removeItem`. Mirror `shopping_lists_api.dart`. Registered
        `ItemsApi` in DI in `first_run_router.dart` alongside `ShoppingListsApi`.
  - [x] Added `itemCount` (int) to `ShoppingListSummary` (Flutter) + `fromJson` (default 0); optimistic
        create keeps the default `itemCount: 0`.
- [x] **Task 16: list detail cubit/state** — `features/lists/presentation`
  - [x] `list_detail_cubit.dart` + `list_detail_state.dart`: bootstrap → `listItems`; `addItem`,
        `updateItem`, `removeItem` with optimistic apply + inline `actionError` on rejection + `isClosed`
        guards. Uses `CommandIntent` per intent (add: `hasResourceId: true` mints `itemId`, keyed on the
        payload tuple; update: single intent keyed on `(itemId, name, note, amount, unit)`; remove: single
        intent keyed on `itemId`, mirroring `StoresCubit._archiveIntent`) — reused on retry, freshened
        after success. Mirrors `shopping_lists_cubit.dart`.
- [x] **Task 17: list detail UI** — `features/lists/presentation`
  - [x] `list_detail_page.dart`: item rows (name · quantity formatted de-DE via the existing
        `QuantityFormatter` · optional note), empty state, add button, per-row edit/remove; **Done list
        read-only** (no affordances render at all). Reuses `SgartButton`, `SgartShapes`, 48px targets,
        a11y tooltips/labels.
  - [x] `item_form_sheet.dart`: name field, quantity **amount** field + **unit dropdown** (Stück / Gramm
        / Kilogramm / Milliliter / Liter / Packung, reusing the existing `formatting.Unit` + label
        catalog), optional note field; client-side fail-fast guard (blank name disables submit) so no
        pointless round-trip; defaults to `1 Stück`. One sheet for add + edit.
  - [x] Navigation: `_ListRow.onTap`/`_ArchiveRow.onTap` → push `MaterialPageRoute` to `ListDetailPage`,
        re-providing `ItemsApi` + a `ListDetailCubit(itemsApi, householdId, listId, isReadOnly)`
        (mirrors `HouseholdShell._openSwitcher`'s re-provide pattern). Rename stays the trailing icon
        button; a Done row opens read-only.
  - [x] Overview: shows `itemCount` on each Open `_ListRow` (e.g. „3 Artikel"); archive rows unchanged
        (no count shown, per Clarification 4/Epic-3 polish).
- [x] **Task 18: localization** — `l10n`
  - [x] Added German strings to `app_de.arb` (detail heading, add/edit/remove actions, name/amount/unit
        /note labels, item-count `„{count} Artikel"` with an ICU plural, empty-items state) and ran
        `flutter gen-l10n`. Mapped the 8 new error codes in `error_message_resolver.dart`; `list.notFound`
        copy already existed from 2.1. No hard-coded user-facing strings.

### Tests & green build (CLAUDE.md §6)

- [x] **Task 19: Flutter tests** — added `item_test.dart` (`Item.fromJson` parsing incl. malformed-shape
      `AppException`); `list_detail_cubit_test.dart` (19 tests: add/edit/remove, optimistic apply, error
      surfacing, read-only no-ops, intent reuse/freshen); `list_detail_page_test.dart` (10 tests: form,
      unit dropdown, empty state, read-only Done, error+retry); extended `lists_view_test.dart` (3 new
      tests: item count display, Open-row and Done-row navigation to the detail screen); added
      `fake_items_dependencies.dart` (`FakeItemsApi`, mirrors `FakeShoppingListsApi`).
- [x] **Task 20: full-suite green** — see Completion Notes below for exact suite/counts.

## Dev Notes

### Architecture patterns & constraints

- **DDD + CQRS + Event Sourcing, hexagonal, modular monolith** (AD-1/AD-2). Domain stays pure — no
  framework/persistence/transport import. State changes only via command → aggregate → event under an
  expected-version check (AD-4); read models are projection-only. [Source: ARCHITECTURE-SPINE.md
  #Design-Paradigm, #AD-1, #AD-4]
- **`Item` is an entity inside the `ShoppingList` aggregate (AD-10).** Never loaded/mutated from
  outside the root; only the root accepts item commands. This story realises AD-10 the same way `Store`
  is realised inside `Household` — folded `ItemState` keyed by `ItemId`, dedup + no-op guards on the
  root. Cross-aggregate effects (Story 2.4 move-to-list) will use a process manager later, **not** this
  story. [Source: ARCHITECTURE-SPINE.md #AD-10; backend `Household.addStore`/`StoreState`]
- **`Quantity` + `Unit` value objects (AD-9)** already exist in `shared` and enforce `amount > 0` and a
  controlled unit vocabulary — reuse them; free-text units are impossible by construction. [Source:
  ARCHITECTURE-SPINE.md #AD-9; `shared/Quantity.java`, `shared/Unit.java`]
- **Optimistic concurrency + idempotency (AD-8).** Handlers do an online load-then-append using the
  **loaded** list-stream version; a lost race is a `ConcurrencyConflictException` → 409. The `commandId`
  makes a retried command idempotent at the `EventStore`, not the aggregate. [Source:
  ARCHITECTURE-SPINE.md #AD-8; `RenameShoppingListHandler`]
- **No PII in events (AD-5/AD-6).** Item events carry ids + item content only — no creator, no
  `MemberId`, no display name/email. Item content (purchase planning) is treated as **household personal
  data** (CLAUDE.md §5); the read model carries `household_id`/`list_id` for Epic-6 erasure locability,
  and no personal datum is duplicated. [Source: CLAUDE.md §5; ARCHITECTURE-SPINE.md #AD-5]
- **Ubiquitous language (AD-11):** `Item`, `Quantity`, `Unit`, `ItemAdded`/`ItemUpdated`/`ItemRemoved`,
  `AddItem`/`UpdateItem`/`RemoveItem`. No abbreviations. Same term in domain, events, read model, API,
  UI. [Source: ARCHITECTURE-SPINE.md #AD-11]

### Source tree — mirror these exact files

| New/changed class | Mirror | Path |
| --- | --- | --- |
| `ItemId` | `StoreId` | `backend/.../shared/` |
| `ItemName` / `ItemNote` | `ShoppingListName` | `backend/.../collaboration/domain/` |
| `ItemAdded`/`Updated`/`Removed` | `ShoppingListCreated`/`StoreAdded` | `collaboration/domain/event/` |
| `DuplicateItemException` etc. | `ListNameChangeNotPermittedException` | `collaboration/domain/exception/` |
| `ShoppingList.addItem/updateItem/removeItem` + `ItemState` | `Household.addStore/archiveStore` + `StoreState` | `collaboration/domain/ShoppingList.java` |
| `Add/Update/RemoveItem(+Handler)` | `AddStore(+Handler)` / `RenameShoppingListHandler` | `collaboration/application/command/` |
| `*ApplicationException` (5) | `DuplicateStoreNameApplicationException` etc. | `collaboration/application/exception/` |
| `ListItems` | `ListOpenLists` | `collaboration/application/query/` |
| `ItemView` / `ItemReadModel` | `ShoppingListView` / `ShoppingListReadModel` | `collaboration/domain/readmodel/` |
| `JdbcItemReadModel` | `JdbcShoppingListReadModel` | `collaboration/adapter/out/` |
| `V6__item_read_model.sql` | `V5__shopping_list_read_model.sql` | `backend/.../resources/db/migration/` |
| projector/codec extension | (extend existing) | `collaboration/adapter/out/` |
| `ItemController` | `ShoppingListController` | `collaboration/adapter/in/` |
| Flutter `Item`/`items_api` | `ShoppingListSummary`/`shopping_lists_api` | `app/lib/features/lists/data/` |
| Flutter detail cubit/page/sheet | `shopping_lists_cubit`/`lists_view`/`create_list_dialog` | `app/lib/features/lists/presentation/` |

### Package structure (CLAUDE.md §8)

New classes drop into the **existing** intent subpackages (`domain.event`, `domain.exception`,
`domain.readmodel`, `application.command`, `application.query`, `application.exception`) — no new
stereotype packages, no ArchUnit change (rules match `..domain..` / `..application..`). Keep each
command DTO beside its handler.

### Testing standards

- Domain: fast pure unit tests, no infrastructure (mirror `ShoppingListTest`). Assert observable
  behaviour (events raised, state after replay), not internals.
- Commands: assert the state change + emitted event; queries: assert the read model, side-effect free.
- Isolate `EventStore`/`ResolveMemberIdentity`/read model behind the existing fakes/`InMemoryEventStore`.
- Synthetic, clearly-fake German data only; no real personal data. Add a no-personal-data assertion for
  the 3 new events.
- **Green build = full suite** for every touched module: backend `./gradlew test` (incl. ArchUnit) **and**
  `flutter test` + `flutter analyze`. State which ran. [Source: CLAUDE.md §6]

### Deferred / do-not-build (premature-value discipline)

- **Reachable-`DONE` item-rejection e2e** — coded + synthetic-tested now; the reachable test waits for
  Epic 3's trip-completion event (same as `rename`'s `DONE` branch, Story 2.1 Cl.1). Log it in
  `deferred-work.md`.
- **`IN_TRIP` item behaviour** — 2.3 permits item commands only while `OPEN`; whether an in-trip list
  accepts item edits is an Epic-3 decision.
- **Progress bar (checked/total)** — Epic 3 (needs check-off). Only the plain item **count** ships now.
- **Autocomplete / attribute prefill** (2.5), **move-to-list** (2.4), **store assignment** (2.6),
  **check/uncheck/postpone** (Epic 3) — out of scope; their affordances must not appear.
- **Archive-row count/progress** — Epic-3 polish; Done rows show neither.

### Project Structure Notes

- The list detail screen is pushed as a route from the overview `_ListRow` — the shell's
  `IndexedStack` tabs are unchanged. Re-provide `ItemsApi` + `ListDetailCubit` on the pushed route (the
  overview already does this pattern for its sheets). [Source: `household_shell.dart`, `lists_view.dart`]
- `item_read_model` denormalises `household_id` (not on the `list-` stream key) so `ListItems` filters
  by `(household_id, list_id)` in one query and Epic-6 erasure can locate rows — accepted denormalisation.
- Overview item count is derived (`COUNT`), not a stored counter — single source of truth stays the
  item rows (no second count to keep consistent).

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story-2.3] — user story + BDD ACs (FR5).
- [Source: _bmad-output/planning-artifacts/architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md#AD-4/AD-8/AD-9/AD-10/AD-11]
- [Source: CLAUDE.md §1–§8] — Clean Code, naming, DDD, CQRS, DSGVO, testing, dependency currency, package structure.
- [Source: backend `ShoppingList.java`, `Household.java`, `RenameShoppingListHandler.java`, `AddStoreHandler.java`, `JdbcShoppingListReadModel.java`, `ShoppingListReadModelProjector.java`, `DomainEventJsonCodec.java`, `CommandFieldTranslations.java`, `WriteErrorAdvice.java`, `ShoppingListController.java`] — patterns to mirror.
- [Source: app `lists/` feature — `shopping_lists_cubit.dart`, `lists_view.dart`, `shopping_lists_api.dart`, `shopping_list_summary.dart`, `household_shell.dart`] — client patterns to mirror.
- [Source: _bmad-output/implementation-artifacts/deferred-work.md] — item-count carry-forward + `DONE`-branch reachability defers.

## Review Findings

Code review 2026-08-27 (bmad-code-review, Opus 4.8 — Blind Hunter + Edge Case Hunter + Acceptance
Auditor). 0 decision-needed, 7 patch, 4 deferred, 4 dismissed. Severity in brackets. **All 7 patches
applied** and both suites re-run green (backend `./gradlew test` incl. ArchUnit + Testcontainers;
Flutter `flutter analyze` + `flutter test` = 321). The shared-`_removeIntent` finding (LOW) is
resolved by the `removeItem` `isSubmitting` guard — commands are now serialized, so the single intent
never overlaps. Regression tests added: backend scale-insensitive no-op + read-side cross-household
empty; Flutter comma-decimal normalization + non-numeric-amount client block.

- [x] [Review][Patch] **[HIGH] German decimal amount silently rejected** — the de-DE UI renders
      `0,5 kg` (comma) and the amount field is a decimal keyboard, but the client sends the raw text
      and the backend does `new BigDecimal(rawAmount)` (dot only), so `1,5` → `NumberFormatException`
      → `item.quantityInvalid` (400). Fractional amounts — the reason `Quantity` uses `BigDecimal` —
      are unreachable in the app's only locale. Fix: normalise `,`→`.` (and validate) before send.
      Fold in the amount-not-client-validated gap (blank/non-numeric amount submits and only fails
      server-side, unlike the blank-name guard). [app/lib/features/lists/presentation/item_form_sheet.dart:67]
- [x] [Review][Patch] **[MEDIUM] `removeItem` skips the `isSubmitting` guard → concurrent-command
      409 races** — unlike `addItem`/`updateItem`, `removeItem` neither reads nor sets `isSubmitting`
      and the row calls it fire-and-forget, so a remove concurrent with another command loads the same
      stream version and the loser gets a spurious `ConcurrencyConflictException` (409) for a valid
      action. [app/lib/features/lists/presentation/list_detail_cubit.dart:149]
- [x] [Review][Patch] **[MEDIUM] Overview item count goes stale after the user's own edit** — the
      overview push has no `.then(refresh)`; adding/removing items on the detail screen mutates only
      `ListDetailCubit`, so returning to the overview still shows the old `itemCount` (e.g. „0 Artikel")
      until a manual refresh. AC7 count is correct on the server, stale in the UI after the very action
      that changes it. Fix: refresh `ShoppingListsCubit` on pop. [app/lib/features/lists/presentation/lists_view.dart:197]
- [x] [Review][Patch] **[MEDIUM] Cross-household item-read no-leak is asserted nowhere** — Task 9
      specified a "cross-household empty" test; the guarantee lives in `JdbcItemReadModel.itemsOf`'s
      `WHERE household_id=… AND list_id=…`, but `ListItemsTest` uses an in-memory double that ignores
      `householdId` and the projector test only queries the matching household. Add the read-side
      cross-household-empty test. [backend/src/test/java/de/sgart/collaboration/application/ListItemsTest.java]
- [x] [Review][Patch] **[LOW] `updateItem` no-op check is `BigDecimal`-scale-sensitive** — the
      convergent-no-op guard uses `existing.quantity().equals(quantity)`; `Quantity` is a record whose
      `equals` inherits scale-sensitive `BigDecimal.equals`, so `1` "updated" to `1.0` (name/note
      unchanged) skips the no-op branch and emits a spurious `ItemUpdated` (event-log noise, violates
      AD-8 convergence intent). Fix: compare unit + `amount().compareTo(...) == 0`. [backend/src/main/java/de/sgart/collaboration/domain/ShoppingList.java:160]
- [x] [Review][Patch] **[LOW] Single shared `_removeIntent` loses idempotent-retry id under
      concurrent removes** — one `CommandIntent` keyed only on `itemId` is shared by all rows; a second
      remove's `complete()` clears the first's in-flight id, so a retried remove mints a fresh command
      id instead of reusing it. Correctness survives only because `removeItem` is a convergent no-op —
      the AD-8 dedup guarantee is silently lost. [app/lib/features/lists/presentation/list_detail_cubit.dart:42]
- [x] [Review][Patch] **[LOW] `InvalidItemNameException` carries the note-too-long error** —
      `toItemNoteOrNull` throws `InvalidItemNameException("item.noteTooLong", …)` for an over-long
      *note*; a type named for the *name* field raising for the *note* field violates the
      intention-revealing-name rule (CLAUDE.md §2). Fix: a note-specific exception (both still → 400).
      [backend/src/main/java/de/sgart/collaboration/application/CommandFieldTranslations.java]
- [x] [Review][Defer] **[LOW] Edit unit fallback silently coerces an unknown server unit to PIECE**
      [app/lib/features/lists/presentation/item_form_sheet.dart:41] — deferred, latent: the Dart and
      Java `Unit` enums match exactly today, so unreachable; a guardless `?? Unit.piece` on the save
      path would corrupt the quantity if the vocabularies ever diverge.
- [x] [Review][Defer] **[LOW] `DELETE` carries a JSON request body** [backend/src/main/java/de/sgart/collaboration/adapter/in/ItemController.java]
      — deferred, pre-existing pattern: mirrors the archive endpoint; DELETE bodies are discouraged and
      stripped by some proxies (the `commandId` would arrive null → envelope 400). Latent portability risk.
- [x] [Review][Defer] **[LOW] `item_read_model` has no FK / `ON DELETE` to the list/household**
      [backend/src/main/resources/db/migration/V6__item_read_model.sql] — deferred to Epic 6: bare
      `UUID NOT NULL` + plain index; list-deletion / household-erasure will orphan item rows. The
      migration comment already reasons about erasure locability but stops short of the cascade.
- [x] [Review][Defer] **[LOW] `quantity_amount NUMERIC` has no precision/scale bound**
      [backend/src/main/resources/db/migration/V6__item_read_model.sql] — deferred: only `amount.signum()>0`
      is enforced, so an arbitrarily large/high-scale decimal can be persisted. No functional break; a
      soft input-validation ceiling to add when convenient.

## Dev Agent Record

### Agent Model Used

Sonnet 5 (dev-story)

### Debug Log References

None — no failures required a debug log; the Testcontainers-backed projector test and the full
Gradle suite ran green on first full run after the implementation.

### Completion Notes List

- Full vertical slice delivered per the story's Clarifications: `Item` realised as a folded entity
  inside `ShoppingList` (mirrors `Household`/`StoreState`), (name, note) dedup key, item events on
  the existing `list-{id}` stream/projector, `itemCount` threaded through the overview read path,
  and a real-but-minimal Flutter list detail screen with no premature Epic-3/2.4/2.5/2.6 surfaces.
- `ShoppingList.rename` was refactored to share the new private `requireOpen()` guard with the item
  commands (DRY) — its externally observable behavior (403 via
  `ListNameChangeNotPermittedException` on a non-`Open` list) is unchanged.
- `ItemReadModel.itemsOf` takes `(HouseholdId, ShoppingListId)` rather than the list id alone (a
  small deviation from the story's Task 10 signature): this lets the read side itself enforce the
  AC8 cross-household no-data-leak guarantee via the SQL `WHERE`, rather than relying on the query
  layer to post-filter — consistent with the same defense-in-depth the write handlers apply.
- AC5's reachable-`DONE` item-rejection end-to-end test is deferred to Epic 3 per the story's
  Dev Notes (no Epic-2 event drives a list out of `Open` yet, same as Story 2.1 Clarification 1);
  the guard itself is coded (`requireOpen()`) and unit-tested via `ShoppingListItemsTest`.
- **Backend — full suite green**: `./gradlew test` (incl. `HexagonalArchitectureTest` ArchUnit and
  the Testcontainers-backed `ShoppingListReadModelProjectorTest`) — **347 tests, 0 failures** (up
  from 271 at Story 2.2).
- **Flutter — full suite green**: `flutter analyze` — no issues; `flutter test` — **319 tests, 0
  failures** (up from 282 at Story 2.2).

### File List

**Backend — new**
- `backend/src/main/java/de/sgart/shared/ItemId.java`
- `backend/src/main/java/de/sgart/collaboration/domain/ItemName.java`
- `backend/src/main/java/de/sgart/collaboration/domain/ItemNote.java`
- `backend/src/main/java/de/sgart/collaboration/domain/event/ItemAdded.java`
- `backend/src/main/java/de/sgart/collaboration/domain/event/ItemUpdated.java`
- `backend/src/main/java/de/sgart/collaboration/domain/event/ItemRemoved.java`
- `backend/src/main/java/de/sgart/collaboration/domain/exception/DuplicateItemException.java`
- `backend/src/main/java/de/sgart/collaboration/domain/exception/ItemNotFoundException.java`
- `backend/src/main/java/de/sgart/collaboration/domain/exception/ItemChangeNotPermittedException.java`
- `backend/src/main/java/de/sgart/collaboration/domain/readmodel/ItemView.java`
- `backend/src/main/java/de/sgart/collaboration/domain/readmodel/ItemReadModel.java`
- `backend/src/main/java/de/sgart/collaboration/application/command/AddItem.java`
- `backend/src/main/java/de/sgart/collaboration/application/command/AddItemHandler.java`
- `backend/src/main/java/de/sgart/collaboration/application/command/UpdateItem.java`
- `backend/src/main/java/de/sgart/collaboration/application/command/UpdateItemHandler.java`
- `backend/src/main/java/de/sgart/collaboration/application/command/RemoveItem.java`
- `backend/src/main/java/de/sgart/collaboration/application/command/RemoveItemHandler.java`
- `backend/src/main/java/de/sgart/collaboration/application/exception/DuplicateItemApplicationException.java`
- `backend/src/main/java/de/sgart/collaboration/application/exception/ItemNotFoundApplicationException.java`
- `backend/src/main/java/de/sgart/collaboration/application/exception/ItemChangeNotPermittedApplicationException.java`
- `backend/src/main/java/de/sgart/collaboration/application/exception/InvalidItemNameException.java`
- `backend/src/main/java/de/sgart/collaboration/application/exception/InvalidItemQuantityException.java`
- `backend/src/main/java/de/sgart/collaboration/application/query/ListItems.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/JdbcItemReadModel.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/in/ItemController.java`
- `backend/src/main/resources/db/migration/V6__item_read_model.sql`

**Backend — changed**
- `backend/src/main/java/de/sgart/collaboration/domain/ShoppingList.java`
- `backend/src/main/java/de/sgart/collaboration/domain/readmodel/ShoppingListView.java`
- `backend/src/main/java/de/sgart/collaboration/application/CommandFieldTranslations.java`
- `backend/src/main/java/de/sgart/collaboration/application/query/ListOpenLists.java`
- `backend/src/main/java/de/sgart/collaboration/application/query/ListDoneLists.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/JdbcShoppingListReadModel.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/ShoppingListReadModelProjector.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/DomainEventJsonCodec.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/CollaborationApplicationConfig.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/CollaborationReadModelConfig.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/in/ShoppingListController.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/in/WriteErrorAdvice.java`

**Backend — tests (new)**
- `backend/src/test/java/de/sgart/collaboration/domain/ItemNameTest.java`
- `backend/src/test/java/de/sgart/collaboration/domain/ItemNoteTest.java`
- `backend/src/test/java/de/sgart/collaboration/domain/ShoppingListItemsTest.java`
- `backend/src/test/java/de/sgart/collaboration/application/AddItemHandlerTest.java`
- `backend/src/test/java/de/sgart/collaboration/application/UpdateItemHandlerTest.java`
- `backend/src/test/java/de/sgart/collaboration/application/RemoveItemHandlerTest.java`
- `backend/src/test/java/de/sgart/collaboration/application/ListItemsTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/in/ItemControllerTest.java`

**Backend — tests (changed)**
- `backend/src/test/java/de/sgart/collaboration/application/ListOpenListsTest.java`
- `backend/src/test/java/de/sgart/collaboration/application/ListDoneListsTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/in/ShoppingListControllerTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/out/ShoppingListReadModelProjectorTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/out/DomainEventJsonCodecTest.java`

**Flutter — new**
- `app/lib/features/lists/data/item.dart`
- `app/lib/features/lists/data/items_api.dart`
- `app/lib/features/lists/presentation/list_detail_state.dart`
- `app/lib/features/lists/presentation/list_detail_cubit.dart`
- `app/lib/features/lists/presentation/list_detail_page.dart`
- `app/lib/features/lists/presentation/item_form_sheet.dart`

**Flutter — changed**
- `app/lib/features/lists/data/shopping_list_summary.dart`
- `app/lib/features/lists/presentation/lists_view.dart`
- `app/lib/features/households/presentation/first_run_router.dart`
- `app/lib/shared/errors/error_message_resolver.dart`
- `app/lib/l10n/app_de.arb`

**Flutter — tests (new)**
- `app/test/features/lists/data/item_test.dart`
- `app/test/features/lists/presentation/list_detail_cubit_test.dart`
- `app/test/features/lists/presentation/list_detail_page_test.dart`
- `app/test/support/fake_items_dependencies.dart`

**Flutter — tests (changed)**
- `app/test/features/lists/presentation/lists_view_test.dart`
