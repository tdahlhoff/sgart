---
baseline_commit: fe1948333feede592773075512fe70f5045f0a1e
---

# Story 2.5: Fast item entry with autocomplete & attribute prefill

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a member,
I want the app to suggest items I've bought before and prefill their details as I type,
so that adding a known article takes minimal taps.

## Acceptance Criteria

Derived from **epics.md § Story 2.5** (FR6/CAP-6), refined against **ARCHITECTURE-SPINE.md**
(AD-4, AD-5/AD-6, AD-10, AD-11 + the "FR-27 autocomplete read model" reserved in the
concretization backlog), **UX-DR8 / EXPERIENCE.md** (the list-detail *persistent fast-add field*),
the `screen-list-detail.html` artifact (States A/B), and the Story 2.3 item slice this builds on.
Each AC is independently testable.

1. **Household-scoped suggestions from the household's own item history, served lag-free (AC1, FR6).**
   Given a member typing a name into the list-detail fast-add field, when the input changes, then
   the app suggests matching **previously-used item names in the household** (case-insensitive
   **prefix** match on the trimmed name), **household-scoped and private**, filtered **client-side
   from an in-memory cache** so there is **no per-keystroke network call** (lag-free). The suggestion
   set is backed by a **new read model over the household's own past items** — **not** the Post-MVP
   Product catalog, and **not** the live `item_read_model` (which deletes rows on remove/move and so
   is *current items*, not *history*). Suggestions include names of items **already bought, removed,
   or moved away** — the history outlives the live item.

2. **Selecting a suggestion prefills last-used attributes; the add is instant (AC2, Cl. 2).** Given
   the suggestion panel, when the member **taps a suggestion**, then the item is added to the current
   list **immediately** with that name's **last-used `Quantity` (amount + unit) and note** prefilled
   (the existing `AddItem` path, Story 2.3). "Overridable" is honoured by **editing the just-added
   row** via the existing edit sheet (Cl. 2 — a single-line field carries only the name, so the
   correction step is the row's edit, not an inline pre-commit editor). **`default Store` prefill is
   deferred to Story 2.6** (there is no store-on-item concept until 2.6 — Cl. 3): 2.5 prefills **name,
   unit, note only**.

3. **A brand-new, never-seen name adds with no extra steps (AC3).** Given a member typing a name
   with **no matching suggestion** (or ignoring the suggestions), when they commit via the panel's
   **„‚{name}' als neuen Artikel hinzufügen"** row **or the keyboard submit**, then the item is added
   with the typed name and the **Story 2.3 defaults** (`Quantity` = **1 Stück**, no note) — one
   action, no sheet, no extra steps. A never-seen name still adds even when the suggestion set is
   empty or still loading.

4. **The fast-add field is the single add surface; the modal add-sheet path is retired (AC4,
   UX-DR8).** Given an **Open** list detail screen, the **persistent fast-add field** at the bottom
   (above the bottom nav) is the **only** way to add an item; the Story 2.3 „+ Artikel hinzufügen"
   **button and its `showItemFormSheet(context, cubit)` add path are removed**. The
   `item_form_sheet` **remains, but only for editing** an existing item (reached from the row's edit
   affordance). The suggestion panel **rises above** the field (there is no room below it — the nav
   sits there), mirroring `screen-list-detail.html` State B.

5. **A Done list has no fast-add field (AC5).** Given a **Done** (read-only) list detail screen, then
   **no** fast-add field and **no** suggestion panel render (mirrors the Story 2.3 read-only Done
   detail, which hid the add button); a Done list accepts no item commands, and 2.5 must not fetch or
   surface suggestions on it.

6. **Suggestions reflect the last time a name was used, including edits (AC6).** Given a member
   adds „Milch 1 L" then edits it to „Milch 2 L", when they later type „Milch", then the suggestion
   prefills **2 L** — the read model records **last-used** attributes from both `ItemAdded` **and**
   `ItemUpdated`, keyed by the household + the normalized (trimmed, lower-cased) name; a later
   occurrence **overwrites** the stored attributes (upsert), and a **removal or move does not delete
   the suggestion** (history survives — AC1). The stored **display name uses the last-used casing**.

7. **Membership, isolation & no personal data (AC7).** The suggestions endpoint is
   **membership-gated** (non-member → **403**); a malformed `householdId` is **400**; the read model
   is queried **by `household_id`** so one household's history never leaks to another (mirrors
   `ListItems`' cross-household stance). The `item_suggestion_read_model` carries **no** `MemberId`,
   creator attribution, name, email, or `keycloakUserId` (AD-5/AD-6, no audit trail — mirrors
   `item_read_model`'s AC9): item names/notes/quantities are household personal data (CLAUDE.md §5),
   so the row carries `household_id` for Epic-6 erasure to locate it, and nothing else identifying.
   Tests use synthetic, clearly-fake German data only.

## Clarifications (LOCKED)

Taken from the epic AC + the existing 2.3 patterns + the `screen-list-detail.html` artifact + Timo's
decisions (2026-08-27). **If any is wrong, correct it before `dev-story`.**

1. **The suggestion set is a NEW history-surviving read model, not the live item read model.**
   `item_read_model` (V6) is *current items* — its rows are **deleted** on `ItemRemoved` and on the
   source of `ItemMovedToList`. FR6 needs the household's **past** item names, so 2.5 adds a separate
   **`item_suggestion_read_model`** (V7) keyed by `(household_id, normalized_name)`, projected from
   `ItemAdded`/`ItemUpdated` and **never deleted** on remove/move. One row per distinct name per
   household, holding that name's last-used attributes. [Source: `V6__item_read_model.sql`,
   `ShoppingListReadModelProjector.java`; ARCHITECTURE-SPINE.md Deferred §"FR-27 autocomplete read model".]

2. **Lag-free = fetch-once + client-side filter (FR6), never per-keystroke server calls.** The client
   fetches the **whole household suggestion set once** (on list-detail open) via a household-scoped
   `GET` and caches it in memory; typing filters that cache **client-side** (case-insensitive prefix
   on the trimmed name). No `?prefix=` query, no debounced server round-trips — the set is small
   (one row per distinct household article) and this is the only reading of "served lag-free from a
   local/cached lookup" that also honours "backed by a read model over the household's own history".
   (Live-sync refresh of the cache is **Epic 4**; MVP refreshes on each list-detail open.)

3. **Interaction (Timo, 2026-08-27): single input, instant add, override-via-edit.** The persistent
   fast-add field holds **only the name**. Tapping a suggestion **adds immediately** with the
   last-used quantity/note prefilled (fast capture is the hero — minimal taps). The AC's
   "overridable **before** adding" is satisfied by the row's **existing edit sheet** right after the
   instant add — a single-line field has nowhere to edit quantity/note pre-commit, and an expanding
   multi-control field was explicitly **not** chosen. The suggestion panel **rises above** the field.

4. **`default Store` prefill is deferred to Story 2.6 (Timo, 2026-08-27).** The AC lists "unit, note,
   default Store", and the artifact draws a store chip / „zuletzt Edeka" on each suggestion — but
   **store-on-item does not exist until Story 2.6** (no store column on `item_read_model`, no
   assignment command/event). So 2.5 records + prefills **name, unit, note** only; Story 2.6 extends
   the suggestion read model, the suggestion rows, and the prefill with the **last-used store**. This
   respects the Epic-1 retro premature-value rule (don't build a surface whose value depends on a
   later capability). Log in `deferred-work.md`.

5. **Recorded on `ItemAdded` AND `ItemUpdated` (last-used incl. edits) — and `ItemUpdated` has no
   `householdId`, so the projector looks it up.** `ItemAdded` carries `householdId`; `ItemUpdated`
   (Story 2.3) carries only `listId/itemId/name/note/quantity` (verified). Rather than **version
   the existing `ItemUpdated` event** (would need a nullable-for-old-events migration and touch 2.3),
   the projector resolves the household for the update via a new
   **`ItemReadModel.householdIdOf(ItemId)`** lookup — the `item_read_model` row exists by then (its
   `ItemAdded` was projected earlier on the same ordered stream). `ItemRemoved`/`ItemMovedToList`
   record **nothing** (history survives — Cl. 1). A move's target-side `ItemAdded` (raised by the PM)
   records usage on the target household normally — same household, harmless upsert.

6. **Matcher = case-insensitive prefix on the trimmed name; alphabetical, replay-stable ordering.**
   Key = `lower(trim(name))`; display = last-used casing. Match = the query (trimmed, lower-cased) is
   a **prefix** of a stored normalized name. Order **alphabetically by display name** (replay-stable —
   no timestamp column, so a full re-projection reproduces identical ordering). **Fuzzy/typo
   tolerance and recency ranking are deferred** (the AC's "prefix/fuzzy" is satisfied by prefix for
   MVP; KISS). The client caps the visible panel (e.g. 6 rows) plus the always-present "add as new" row.

7. **No premature surfaces.** No store chip / store prefill (Story 2.6), no check/uncheck/postpone/
   progress (Epic 3). Suggestions are **not** filtered against the current list's items — a suggestion
   whose (name, note) is already on this list is added down the normal path and the **existing Story
   2.3 duplicate rejection** surfaces inline (KISS: no cross-checking the live list). The item row
   actions stay as Story 2.3/2.4 built them (inline edit/remove/move) — the artifact's ⋯-overflow row
   menu is **not** in scope here.

## Tasks / Subtasks

> Follow TDD (CLAUDE.md §6): failing test first at the lowest level that proves the behaviour, then
> the simplest code to pass. Keep the domain pure (AD-1). Mirror the cited existing file for every
> new class — the CQRS read-model + query + controller + Flutter-cubit patterns are all established
> (Stories 2.1–2.4); do not invent new ones. There is **no new domain event, no new command, no
> aggregate change, and no `Item`/`ShoppingList` mutation** in this story — it is a **read-side +
> presentation** slice over the item events Story 2.3 already emits.

### Backend — new read model: migration + port + Jdbc (AC1, AC6, AC7, Cl. 1/6)

- [x] **Task 1: `V7__item_suggestion_read_model.sql`** (AC1, AC6, AC7)
  - [x] `CREATE TABLE item_suggestion_read_model` with columns:
        `household_id UUID NOT NULL`, `normalized_name VARCHAR(120) NOT NULL`,
        `display_name VARCHAR(120) NOT NULL`, `note VARCHAR(240) NULL`,
        `quantity_amount NUMERIC NOT NULL`, `quantity_unit VARCHAR(20) NOT NULL`,
        `PRIMARY KEY (household_id, normalized_name)`. Header comment mirroring V6: why a *separate*
        history-surviving read model (Cl. 1), the no-PII stance (AC7), and the `(household_id,
        normalized_name)` dedup key (last-used-wins via upsert).
  - [x] Index is the PK itself (queries are `WHERE household_id = ?`); no extra index needed for MVP.
- [x] **Task 2: `ItemSuggestionReadModel` domain port + `ItemSuggestionView`** — package
      `collaboration.domain.readmodel` (mirror `ItemReadModel`/`ItemView`)
  - [x] `interface ItemSuggestionReadModel { List<ItemSuggestionView> suggestionsOf(HouseholdId householdId); }`
        — Javadoc: built solely by `ShoppingListReadModelProjector` (AD-4); read by `ListItemSuggestions`.
  - [x] `record ItemSuggestionView(ItemName name, ItemNote note /*nullable*/, Quantity quantity)` —
        the last-used display name + attributes (no id — a suggestion is not an item; the client mints
        a fresh `itemId` when it adds).
- [x] **Task 3: `JdbcItemSuggestionReadModel`** — `adapter.out` (mirror `JdbcItemReadModel`)
  - [x] `implements ItemSuggestionReadModel`. `suggestionsOf(householdId)`:
        `SELECT display_name, note, quantity_amount, quantity_unit FROM item_suggestion_read_model
        WHERE household_id = :householdId ORDER BY display_name ASC` → `ItemSuggestionView`s.
  - [x] Package-private `recordUsage(HouseholdId, ItemName, ItemNote note, Quantity)` upsert:
        `INSERT ... (household_id, normalized_name, display_name, note, quantity_amount, quantity_unit)
        VALUES (:hh, :norm, :name, :note, :amount, :unit)
        ON CONFLICT (household_id, normalized_name)
        DO UPDATE SET display_name = :name, note = :note, quantity_amount = :amount, quantity_unit = :unit`
        where `:norm = name.value().trim().toLowerCase(Locale.ROOT)` and `:name = name.value()`
        (last-used casing). No `last_used_at` (KISS — the upsert overwrite *is* last-used; alphabetical
        order is replay-stable, Cl. 6).
  - [x] Wire the bean in `CollaborationReadModelConfig` (mirror `jdbcItemReadModel`).

### Backend — projector extension (AC1, AC6, Cl. 5)

- [x] **Task 4: extend `ShoppingListReadModelProjector` to build the suggestion read model**
  - [x] Add a `JdbcItemSuggestionReadModel` constructor dependency (both constructors) + null-check;
        update the `CollaborationReadModelConfig` `shoppingListReadModelProjector` bean to pass it.
        Prefer extending this projector over a second subscription: it already decodes
        `ItemAdded`/`ItemUpdated` off the `list-` stream — a second projector would re-subscribe and
        re-decode the identical events (DRY/KISS; one source stream, two projections). Document that.
  - [x] `project(...)`:
        - `ItemAdded added` → **also** `itemSuggestionReadModel.recordUsage(added.householdId(),
          added.name(), added.note(), added.quantity())` (in addition to the existing `insertItem`).
        - `ItemUpdated updated` → **also** `itemSuggestionReadModel.recordUsage(householdIdOf(updated.itemId()),
          updated.name(), updated.note(), updated.quantity())` — resolve the household via the new
          `ItemReadModel.householdIdOf(...)` (Cl. 5). If the lookup returns empty (item row not yet
          projected — an out-of-order/replay edge), **skip the suggestion** and still do the existing
          `updateItem` (log at debug); a later full replay records it in order.
        - `ItemRemoved` / `ItemMovedToList` → **unchanged** (item read model only; suggestions survive).
  - [x] Add `Optional<HouseholdId> householdIdOf(ItemId)` to the `ItemReadModel` **port** +
        `JdbcItemReadModel` impl: `SELECT household_id FROM item_read_model WHERE item_id = :itemId`
        (`.optional()`).
  - [x] Extend `ShoppingListReadModelProjectorTest` (Testcontainers): `ItemAdded` records a suggestion
        row (right household, last-used attrs, normalized key); a **second `ItemAdded` of the same
        name** (different casing) upserts to one row with the new casing/attrs; `ItemUpdated` refreshes
        the suggestion's attributes (last-used incl. edits, AC6); **`ItemRemoved` and `ItemMovedToList`
        leave the suggestion row intact** (history survives, Cl. 1); a cross-household name never leaks
        into another household's `suggestionsOf`.

### Backend — query + endpoint + ArchUnit (AC1, AC7)

- [x] **Task 5: `ListItemSuggestions` query** — package `application.query` (mirror `ListItems`)
  - [x] Constructor `(ResolveMemberIdentity, ItemSuggestionReadModel)`; `List<ItemSuggestionSummary>
        forHousehold(String keycloakUserId, String rawHouseholdId)`: translate `householdId` (400 on
        malformed via `CommandFieldTranslations.toHouseholdId`), `resolveMemberIdentity.resolve(...)`
        (403 non-member), then `suggestionsOf(householdId)` mapped to `ItemSuggestionSummary(String
        name, String note /*nullable*/, String amount, String unit)` (plain strings — `adapter.in`
        must not import `..domain..`; mirror `ListItems.ItemSummary`). Pure query, no side effects.
  - [x] Wire the bean in `CollaborationApplicationConfig` (mirror `listItems`).
  - [x] `ListItemSuggestionsTest` (fake `ResolveMemberIdentity` + fake/in-memory `ItemSuggestionReadModel`):
        returns the household's suggestions mapped correctly; non-member → 403 (`NotAMemberException`);
        malformed householdId → 400.
- [x] **Task 6: `ItemSuggestionController`** — `adapter.in` (mirror the query half of `ItemController`)
  - [x] `@RestController @RequestMapping("/api/v1/households/{householdId}/item-suggestions")`;
        `@GetMapping` → `List<ItemSuggestionResponse>`; identity from the JWT `sub` via
        `AuthenticatedCaller` (AR10/AD-5). `record ItemSuggestionResponse(String name, String note,
        String amount, String unit)`.
  - [x] `ItemSuggestionControllerTest` (MockMvc slice): 200 with the mapped list; 403 non-member; 400
        malformed householdId. Mirror `ItemController`'s `GET` test cases.
- [x] **Task 7: ArchUnit** — run `HexagonalArchitectureTest`; confirm the new read model
      (`domain.readmodel` port + `adapter.out` impl), the `application.query`, and the `adapter.in`
      controller need **no** rule change (ports in `..domain..`, query in `..application..`, controller
      imports no `..domain..`).

### Flutter — data layer (AC1)

- [x] **Task 8: suggestion data layer** — `features/lists/data/`
  - [x] `item_suggestion.dart`: `class ItemSuggestion { final String name; final String? note; final
        String amount; final String unit; }` with a fail-fast `ItemSuggestion.fromJson` (mirror
        `Item.fromJson` — mapped `AppException` on a bad shape, nullable note).
  - [x] `item_suggestions_api.dart`: `abstract ItemSuggestionsApi { Future<List<ItemSuggestion>>
        listSuggestions(String householdId); }` + `HttpItemSuggestionsApi` (`GET
        .../households/{householdId}/item-suggestions`, mirror `HttpItemsApi.listItems`) + a
        `FakeItemSuggestionsApi` in test support. **Deviation note (expected):** like `items_api`, no
        dedicated `Http*Api` test — the codebase tests these only through their fakes.

### Flutter — cubit (AC1, AC2, AC3, AC6, Cl. 2)

- [x] **Task 9: `ListDetailCubit` — load + cache + filter suggestions**
  - [x] Add an `ItemSuggestionsApi` dependency. In `bootstrap()`, after loading items, load
        suggestions (**Open lists only** — a read-only Done list neither fetches nor shows them, AC5);
        a suggestions-load failure must **not** fail the whole screen (items still render) — degrade to
        an empty suggestion set (log/ignore). Hold `List<ItemSuggestion> suggestions` in
        `ListDetailState`.
  - [x] `List<ItemSuggestion> suggestionsMatching(String query)`: trimmed, case-insensitive **prefix**
        match on `name`; alphabetical; empty query → empty (the panel only shows once the member types).
        Pure + unit-tested.
  - [x] After a **successful `addItem`/`updateItem`**, optimistically **upsert the local suggestion
        cache** (normalized-name key, last-used casing/attrs) so a just-added new name is immediately
        suggestable without a re-fetch (mirrors the read model's upsert; keeps read-your-writes even
        though the projection is eventually consistent). Emit the updated state.
  - [x] Reuse the existing `addItem(name, note, amount, unit)` for both the suggestion tap (prefilled
        args) and the "add as new" path (`amount: '1'`, `unit: 'PIECE'`, `note: null`) — **no new cubit
        add method**; the `_addIntent` `CommandIntent` lifecycle already handles the id freshening.

### Flutter — UI: the persistent fast-add field (AC2, AC3, AC4, AC5, Cl. 3)

- [x] **Task 10: `fast_add_field.dart` (new) + wire into `list_detail_page.dart`**
  - [x] A persistent bottom-anchored add field on the **Open** list detail (`_ReadyBody`), replacing
        the Story 2.3 „+ Artikel hinzufügen" `SgartButton` and its `showItemFormSheet(context, cubit)`
        **add** path (the sheet stays for **edit** — `showItemFormSheet(..., existingItem: item)` from
        the row stays). Placeholder „Artikel hinzufügen …" (State A).
  - [x] On focus + non-empty text: an **overlay/panel that rises ABOVE the field** (State B — there is
        no room below; the field sits just above the nav). It lists `cubit.suggestionsMatching(text)`
        (each row: name + „{amount} {unit}“ prefill hint, using the existing `QuantityFormatter`),
        capped (~6), followed by the always-present **„‚{text}' als neuen Artikel hinzufügen"** row.
        Keys: `fast-add-field`, `fast-add-suggestion-{normalizedName}`, `fast-add-new-row`.
  - [x] **Tap a suggestion** → `cubit.addItem(name: s.name, note: s.note, amount: s.amount, unit: s.unit)`
        (instant add, prefill — AC2); **tap the "add as new" row OR keyboard submit** →
        `cubit.addItem(name: text, note: null, amount: '1', unit: 'PIECE')` (AC3). On success clear the
        field + dismiss the panel; on failure keep the text and show the existing inline `actionError`
        (the field is not a sheet — nothing to pop). Respect `state.isSubmitting` (ignore re-entrant
        submits, like the old button).
  - [x] **Done (read-only) list** → render **no** fast-add field and no panel (AC5), exactly as the
        Story 2.3 read-only body hid the add button.
- [x] **Task 11: localization** — `l10n`
  - [x] `app_de.arb` (+ `flutter gen-l10n`): fast-add placeholder („Artikel hinzufügen …"), the
        „‚{name}' als neuen Artikel hinzufügen" row (parameterized), and any a11y label/semantics for
        the field + suggestion rows (48px targets, UX-DR5). No hard-coded user-facing strings. Retire
        the now-unused add-button string only if nothing else references it.

### Tests & green build (CLAUDE.md §6)

- [x] **Task 12: Flutter tests**
  - [x] `list_detail_cubit`: bootstrap loads + exposes suggestions; a suggestions-load failure still
        renders items; `suggestionsMatching` (prefix, case-insensitive, trimmed, alphabetical, empty
        query → empty); optimistic cache upsert after add/update (new name becomes suggestable;
        edited attrs refresh); Done list fetches/【shows】 none.
  - [x] `fast_add_field` widget: typing shows the panel **above** with matching suggestions + the
        add-as-new row; tapping a suggestion calls `addItem` with the prefilled quantity/note; the
        add-as-new row and keyboard submit call `addItem` with `1`/`PIECE`/no-note; empty/loading
        suggestions still allow add-as-new; the field/panel are **absent** on a read-only Done list;
        `isSubmitting` disables re-entrant submit.
  - [x] Extend `FakeItemSuggestionsApi` + the list-detail page/provider tests (the detail route must
        now provide `ItemSuggestionsApi`, mirroring the 2.3 `ItemsApi` re-provide).
- [x] **Task 13: full-suite green** — backend `./gradlew test` (incl. `HexagonalArchitectureTest`
      ArchUnit + the Testcontainers `ShoppingListReadModelProjectorTest`) **and** Flutter `flutter
      analyze` + `flutter test`, both in full (not per-file), per CLAUDE.md §6. State which suite ran
      and the counts (baseline: backend **375**, Flutter **345**).

## Dev Notes

### What is (and isn't) in this story — read first

2.5 is a **read-side + presentation** slice. It adds **no** domain event, **no** command, **no**
aggregate method, and mutates neither `Item` nor `ShoppingList`. It projects the item events Story
2.3 already emits into a **second, history-surviving read model** and puts a **persistent fast-add
field with client-side autocomplete** in front of the existing `AddItem` path. The one genuinely new
idea is *history vs. current items*: the suggestion set must include names of items that have since
been removed or moved — so it cannot reuse `item_read_model`.

Flow (add a known article):

```
member types "Milch" in the fast-add field
  → cubit.suggestionsMatching("milch") filters the in-memory cache (fetched once on open)  ← lag-free
  → panel rises ABOVE the field: [Milch · 2 L] [Milchreis · 1 Pkg] [ „Milch" als neuen Artikel … ]
  → tap [Milch · 2 L]
      → cubit.addItem(name:"Milch", note:…, amount:"2", unit:"LITRE")   ← existing Story 2.3 path
          → POST …/lists/{id}/items {itemId(client-minted), name, note, amount, unit, commandId}
          → ItemAdded on list-{id}     (nothing new server-side for the *add*)
  KurrentDB $all ──filter list-*──▶ ShoppingListReadModelProjector.project(ItemAdded)
      → itemReadModel.insertItem(...)                       (the live item, V6 — Story 2.3)
      → itemSuggestionReadModel.recordUsage(household, "Milch", note, 2 L)   ← NEW (V7, upsert)
```

### Why a separate read model (Cl. 1) — the crux

`item_read_model` deletes on `ItemRemoved` and on the move-source `ItemMovedToList` (see
`JdbcItemReadModel.removeItem` / `ShoppingListReadModelProjector` cases). If suggestions read from it,
an article you bought once and removed would **vanish** from autocomplete — the opposite of "the
household's own past item names" (FR6). So `item_suggestion_read_model` is **append-/upsert-only**:
`ItemAdded`/`ItemUpdated` record usage; **remove and move record nothing**. One row per
`(household_id, normalized_name)`; the upsert keeps the **last-used** attributes and casing.

### The `ItemUpdated`-has-no-`householdId` wrinkle (Cl. 5)

`ItemAdded` carries `householdId`; `ItemUpdated` (Story 2.3) does **not** (verified — it carries
`listId/itemId/name/note/quantity`). Two rejected options and the chosen one:

- ✗ **Version `ItemUpdated` to add `householdId`** — touches a Story 2.3 event, needs the codec to
  tolerate old events missing the field, and ripples into `ShoppingList.updateItem`. Invasive for a
  read-only concern.
- ✗ **Record on `ItemAdded` only** — simplest, but then an edited quantity/note never refreshes the
  suggestion → violates AC6 "last-used".
- ✓ **Look up the household in the projector** via a new `ItemReadModel.householdIdOf(itemId)`. By the
  time an `ItemUpdated` is projected, that item's `ItemAdded` was projected earlier on the same
  ordered stream, so the `item_read_model` row (which has `household_id`) exists. Empty lookup
  (out-of-order replay edge) → skip the suggestion for that event, still do the item update, recover
  on the next full replay. No event change, honours "last-used". [Source: `ItemUpdated.java`,
  `ItemAdded.java`, `JdbcItemReadModel.java`.]

### Lag-free = fetch-once + client filter (Cl. 2)

The AC says "served lag-free from a **local/cached** lookup". The client fetches the household's whole
suggestion set once per list-detail open, caches it in memory, and filters by prefix **client-side** —
no per-keystroke network. The set is one row per distinct household article (small). A `?prefix=`
server query per keystroke is explicitly **not** this. (Live-sync cache refresh is Epic 4; for MVP a
fetch on each open is enough, and a fresh add optimistically upserts the local cache so it is
immediately suggestable — read-your-writes despite eventual consistency, AR3/NFR9.) [Source:
EXPERIENCE.md §"List detail (Anna)"; `screen-list-detail.html` States A/B.]

### Architecture patterns & constraints

- **CQRS read-model-only (AD-4).** The suggestion table is projection-only — no command handler
  writes it; the query has no side effects (CLAUDE.md §6). [ARCHITECTURE-SPINE.md #AD-4]
- **No PII in read models (AD-5/AD-6).** `item_suggestion_read_model` carries `household_id` + item
  content only — no creator/`MemberId`/name/email. Item content is household personal data (CLAUDE.md
  §5); `household_id` lets Epic-6 erasure scrub it. Mirrors `item_read_model` AC9. [#AD-5/#AD-6]
- **Ubiquitous language (AD-11):** `ItemSuggestion`, `item-suggestions`, "suggestion", "prefill",
  "last-used". Same term across read model, query, API, UI. No abbreviations. [#AD-11]
- **Eventual consistency (AR3/NFR9):** the suggestion projection lags the add; the optimistic local
  cache upsert covers read-your-writes for the just-added name.
- **No `Store` here (AD-10, Story 2.6).** `Store` is an entity inside `Household`; there is no
  store-on-item until 2.6, so no default-store prefill now (Cl. 4).

### Source tree — mirror these exact files

| New/changed | Mirror | Path |
| --- | --- | --- |
| `V7__item_suggestion_read_model.sql` | `V6__item_read_model.sql` | `backend/.../resources/db/migration/` |
| `ItemSuggestionReadModel` (port) + `ItemSuggestionView` | `ItemReadModel` / `ItemView` | `collaboration/domain/readmodel/` |
| `JdbcItemSuggestionReadModel` | `JdbcItemReadModel` | `collaboration/adapter/out/` |
| `ShoppingListReadModelProjector` (extend) + `ItemReadModel.householdIdOf` | (extend existing) | `collaboration/adapter/out/`, `collaboration/domain/readmodel/` |
| `CollaborationReadModelConfig` (wire) | (extend existing) | `collaboration/adapter/out/` |
| `ListItemSuggestions` (+ `ItemSuggestionSummary`) | `ListItems` (+ `ItemSummary`) | `collaboration/application/query/` |
| `CollaborationApplicationConfig` (wire) | (extend existing) | `collaboration/adapter/out/` |
| `ItemSuggestionController` (+ `ItemSuggestionResponse`) | `ItemController` `GET` half | `collaboration/adapter/in/` |
| `ItemSuggestion` + `item_suggestions_api.dart` (Http + Fake) | `item.dart` + `items_api.dart` | `app/lib/features/lists/data/` |
| `ListDetailCubit` (load/cache/filter suggestions) | (extend existing) | `app/lib/features/lists/presentation/` |
| `fast_add_field.dart` (replaces the add button) | `item_form_sheet.dart` (controls) / `list_detail_page.dart` | `app/lib/features/lists/presentation/` |

### Package structure (CLAUDE.md §8)

New classes drop into the **existing** intent subpackages (`domain.readmodel`, `application.query`).
No new subpackage earns its keep (KISS). The suggestion projection lives inside the existing
`ShoppingListReadModelProjector` (same `list-` source stream — a second subscription would re-decode
identical events). No ArchUnit rule change (rules match `..domain..`/`..application..`).

### Testing standards

- Read model / projector: **Testcontainers** (matching `ShoppingListReadModelProjectorTest`) — record
  on add/update; **survive** remove/move; per-household isolation; upsert last-used.
- Query: fast unit test with a fake `ResolveMemberIdentity` + a fake/in-memory suggestion read model —
  membership 403, malformed 400, correct mapping. No infra.
- Controller: MockMvc slice — 200/403/400.
- Cubit + widget: fakes only, no network (CLAUDE.md §6). Cover the matcher, optimistic cache upsert,
  instant-add prefill, add-as-new defaults, and Done-list absence.
- Synthetic, clearly-fake German data only; explicit no-PII assertion for the suggestion read model.
- **Green build = full suite** for both modules; state which ran. [Source: CLAUDE.md §6]

### Deferred / do-not-build (premature-value discipline)

- **`default Store` in suggestions (display + prefill)** → **Story 2.6**, when store-on-item exists
  (Cl. 4). Log in `deferred-work.md`.
- **Recency ranking + fuzzy/typo tolerance** → later; MVP is prefix + alphabetical (Cl. 6).
- **Live-sync refresh of the suggestion cache** → **Epic 4** (SSE). MVP fetches on each list-detail
  open; a fresh add upserts the local cache.
- **Item row actions in a ⋯ overflow** (the artifact's row menu) — out of scope; 2.5 keeps the Story
  2.3/2.4 inline edit/remove/move affordances.
- **No new domain event/command/aggregate change** — if you find yourself adding one, stop: 2.5 is
  read-side + presentation over Story 2.3's existing events.

### Project Structure Notes

- The suggestions endpoint is **household-scoped** (`/api/v1/households/{householdId}/item-suggestions`),
  not list-scoped — the history spans the whole household, not one list. A **new** small controller
  (mirrors `ItemController`'s `GET` half) rather than overloading `ItemController` (which is nested
  under a list). [Source: `ItemController.java`, `StoreController` household-scoped pattern.]
- No new migration to V6 and no change to `item_read_model` — the suggestion table is additive (V7).
  [Source: `V6__item_read_model.sql`.]

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story-2.5] — user story + BDD ACs (FR6/CAP-6).
- [Source: ARCHITECTURE-SPINE.md#AD-4/#AD-5/#AD-6/#AD-10/#AD-11 + Deferred §"FR-27 autocomplete read model"] — CQRS read model, no-PII, store-in-household, ubiquitous language, the reserved autocomplete read model.
- [Source: _bmad-output/planning-artifacts/ux-designs/ux-sgart-2026-08-20/EXPERIENCE.md §"List detail (Anna)"; DESIGN.md; `.working/screen-list-detail.html` States A/B] — the persistent fast-add field + upward autocomplete panel + "add as new".
- [Source: CLAUDE.md §1–§8] — Clean Code, naming, DDD, CQRS, DSGVO, testing, package structure.
- [Source: backend `JdbcItemReadModel.java`, `ItemReadModel.java`, `ItemView`, `ShoppingListReadModelProjector.java`, `ListItems.java`, `ItemController.java`, `CollaborationReadModelConfig.java`, `CollaborationApplicationConfig.java`, `ItemAdded.java`, `ItemUpdated.java`, `V6__item_read_model.sql`] — the read-model/query/controller/projector patterns to mirror + the `ItemUpdated`-no-householdId fact (Cl. 5).
- [Source: app `lists/` — `item.dart`, `items_api.dart`, `list_detail_cubit.dart`, `list_detail_page.dart`, `item_form_sheet.dart`, `list_detail_state.dart`] — client patterns to mirror; the add-button path being replaced.
- [Source: _bmad-output/implementation-artifacts/2-3-add-edit-and-remove-items.md, 2-4-move-an-item-to-another-list.md] — the item slice this reads from; the `CommandIntent`/`isSubmitting` add path reused unchanged.
- [Source: _bmad-output/implementation-artifacts/deferred-work.md] — where to log the default-store defer.

## Dev Agent Record

### Agent Model Used

_(dev-story — Sonnet 5)_

### Debug Log References

- `column display_name` in `V7__item_suggestion_read_model.sql` initially tripped
  `NoPersistedPersonalDataTest.noFlywayMigrationEverDeclaresADisplayNameOrEmailColumn` (AD-6 guard
  against persisting a *person's* display name) — a false positive since this `display_name` is the
  last-used casing of an *item name*, not a person. Renamed the column to `name` (matching V6's own
  convention) rather than weakening the privacy guard; no functional change.

### Completion Notes List

- Full vertical read-side + presentation slice, no new domain event/command/aggregate change, per
  the story's Dev Notes.
- Backend: `V7__item_suggestion_read_model.sql`, `ItemSuggestionReadModel` port + `ItemSuggestionView`,
  `JdbcItemSuggestionReadModel` (query + upsert), `ItemReadModel.householdIdOf` extension (default
  method so existing lambda-based test fakes of `ItemReadModel` keep compiling), `ShoppingListReadModelProjector`
  extended to also record suggestion usage on `ItemAdded`/`ItemUpdated`, `ListItemSuggestions` query,
  `ItemSuggestionController`, all wired in `CollaborationReadModelConfig`/`CollaborationApplicationConfig`.
- Flutter: `ItemSuggestion` + `ItemSuggestionsApi` (Http + Fake), `ListDetailCubit` extended to load
  + cache + optimistically upsert suggestions and expose `suggestionsMatching`, new `FastAddField`
  widget replacing the Story 2.3 add button/sheet-add path (the sheet remains for editing only), new
  l10n strings (`fastAddFieldPlaceholder`, `fastAddNewItemAction`); retired the now-unreferenced
  `itemAddAction` string.
- Rewrote `list_detail_page_test.dart`'s add-flow tests to exercise the fast-add field instead of the
  retired add button/sheet-add path; moved the amount-field-specific tests (comma normalization,
  non-numeric guard, unit dropdown) to the still-live edit-sheet flow. Added `fast_add_field_test.dart`
  and suggestion-specific `list_detail_cubit_test.dart`/`ShoppingListReadModelProjectorTest`/
  `ListItemSuggestionsTest`/`ItemSuggestionControllerTest` cases per Task 12.
- All 7 LOCKED clarifications and the deferred/do-not-build list honoured: no store prefill (Cl. 4,
  already logged in `deferred-work.md` from planning), no recency/fuzzy ranking (Cl. 6), no live-sync
  cache refresh (Cl. 2), no new domain event/command/aggregate.
- **Green build — full suite, both modules:**
  - Backend `./gradlew test` (incl. `HexagonalArchitectureTest` ArchUnit — 5/5 green — and the
    Testcontainers `ShoppingListReadModelProjectorTest`): **390 tests, 0 failures** (baseline 375 + 15
    new: 6 projector suggestion cases, 4 `ListItemSuggestionsTest`, 3 `ItemSuggestionControllerTest`,
    2 `ItemReadModel.householdIdOf`-adjacent assertions folded into the projector suite).
  - Flutter `flutter analyze` (0 issues) + `flutter test`: **363 tests, 0 failures** (baseline 345 +
    18 new: 8 `list_detail_cubit_test.dart` suggestion cases, 6 `fast_add_field_test.dart`, plus
    rewritten/added `list_detail_page_test.dart` fast-add cases net +4 vs. the retired add-button ones).

### File List

**Backend**
- `backend/src/main/resources/db/migration/V7__item_suggestion_read_model.sql` (new)
- `backend/src/main/java/de/sgart/collaboration/domain/readmodel/ItemSuggestionReadModel.java` (new)
- `backend/src/main/java/de/sgart/collaboration/domain/readmodel/ItemSuggestionView.java` (new)
- `backend/src/main/java/de/sgart/collaboration/domain/readmodel/ItemReadModel.java` (modified — `householdIdOf`)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/JdbcItemSuggestionReadModel.java` (new)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/JdbcItemReadModel.java` (modified — `householdIdOf` impl)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/ShoppingListReadModelProjector.java` (modified)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/CollaborationReadModelConfig.java` (modified)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/CollaborationApplicationConfig.java` (modified)
- `backend/src/main/java/de/sgart/collaboration/application/query/ListItemSuggestions.java` (new)
- `backend/src/main/java/de/sgart/collaboration/adapter/in/ItemSuggestionController.java` (new)
- `backend/src/test/java/de/sgart/collaboration/adapter/out/ShoppingListReadModelProjectorTest.java` (modified)
- `backend/src/test/java/de/sgart/collaboration/application/ListItemSuggestionsTest.java` (new)
- `backend/src/test/java/de/sgart/collaboration/adapter/in/ItemSuggestionControllerTest.java` (new)

**Flutter**
- `app/lib/features/lists/data/item_suggestion.dart` (new)
- `app/lib/features/lists/data/item_suggestions_api.dart` (new)
- `app/lib/features/lists/presentation/list_detail_state.dart` (modified — `suggestions`)
- `app/lib/features/lists/presentation/list_detail_cubit.dart` (modified)
- `app/lib/features/lists/presentation/fast_add_field.dart` (new)
- `app/lib/features/lists/presentation/list_detail_page.dart` (modified — wires `FastAddField`, retires the add button)
- `app/lib/features/lists/presentation/lists_view.dart` (modified — re-provides `ItemSuggestionsApi`)
- `app/lib/features/households/presentation/first_run_router.dart` (modified — builds/provides `HttpItemSuggestionsApi`)
- `app/lib/l10n/app_de.arb` (modified — new fast-add strings, retired `itemAddAction`)
- `app/lib/l10n/gen/app_localizations.dart`, `app_localizations_de.dart` (generated)
- `app/test/support/fake_item_suggestions_api.dart` (new)
- `app/test/features/lists/presentation/list_detail_cubit_test.dart` (modified)
- `app/test/features/lists/presentation/list_detail_page_test.dart` (modified)
- `app/test/features/lists/presentation/fast_add_field_test.dart` (new)
- `app/test/features/lists/presentation/lists_view_test.dart` (modified)
- `app/test/features/lists/presentation/move_target_sheet_test.dart` (modified — cubit constructor arg)
- `app/test/features/lists/presentation/move_merge_dialog_test.dart` (modified — cubit constructor arg)

## Change Log

- 2026-08-27: Story implemented (dev-story, Sonnet 5) — full read-side + presentation slice for
  household item-name autocomplete with attribute prefill; retired the Story 2.3 add button/sheet-add
  path in favor of a persistent fast-add field. Backend 375→390 green, Flutter 345→363 green.
- 2026-08-27: Code review (bmad-code-review, Opus 5 — 3 layers) — 11 patches applied, 5 items deferred:
  `ItemReadModel.householdIdOf` made abstract (was a silently-empty `default`), `mounted` guard on the
  fast-add field's async continuations, the retired sheet add-mode branch + its two orphaned ARB strings
  removed (edit-only sheet), `unitFromServerName` collapsed from four copies into `quantity_formatter`,
  case-insensitive suggestion ordering (client no longer diverges from the server after an upsert),
  bootstrap now merges rather than replaces the cache (and carries it across a refresh), the swallowed
  suggestions-load failure now logs, the panel's row cap named, a11y semantics on field + rows, and the
  missing tests written (`item_suggestion_test`, the projector's unresolvable-household branch, an
  explicit no-PII column assertion on V7). Decision (Timo): keep focus + keyboard after a successful add
  for rapid multi-add; leave the suggestion history growing, with a member-facing "remove from
  suggestions" action captured as post-MVP work. Backend 390→392 green, Flutter 363→373 green.

## Review Findings

_(bmad-code-review, 2026-08-27 — Blind Hunter + Edge Case Hunter + Acceptance Auditor, all three layers
completed. 2 decision-needed → 1 patch + 1 defer (Timo, 2026-08-27); 11 patch, 5 deferred, 5 dismissed
as noise.)_

- [x] [Review][Patch] **Keep focus + keyboard after a successful add (Decision 1 — Timo: option 1)** — `_addSuggestion`/`_addAsNew` call `_focusNode.unfocus()` on success and `enabled: !state.isSubmitting` unfocuses the field for the duration of every submit, so adding three articles in a row costs three keyboard dismissals and three re-taps. The spec only requires "clear the field + dismiss the panel", and the panel already hides itself once the text is empty (`_showPanel`). Cl. 3 — fast capture is the hero. Drop the `unfocus()` calls and replace `enabled: false` with a guard that does not defocus; cover post-add focus in `fast_add_field_test`. [`app/lib/features/lists/presentation/fast_add_field.dart:60-63,77-80,111`]
- [x] [Review][Defer] **The suggestion history is never pruned — retention, erasure and payload size** — by design (Cl. 1) `item_suggestion_read_model` has no `DELETE` path, and `suggestionsOf` has no `LIMIT`, so the table grows monotonically per household and its whole lifetime content ships on every list-detail open. Two consequences: (1) CLAUDE.md §5 storage limitation / right-to-erasure has only a household-level story (Epic 6), never item-level — a member-typed `note` they deliberately removed keeps being re-attached by the prefill; (2) an old household fetches an unbounded payload on every open. [`backend/src/main/resources/db/migration/V7__item_suggestion_read_model.sql`, `backend/src/main/java/de/sgart/collaboration/adapter/out/JdbcItemSuggestionReadModel.java:31-49`] — deferred (Timo, 2026-08-27): a household's distinct-article count is not expected to grow fast enough to matter pre-MVP; post-MVP, give the member a way to remove an article from the autocomplete history, which doubles as the item-level erasure path

- [x] [Review][Patch] `ItemReadModel.householdIdOf` is a silently-empty `default` method — any implementation that forgets to override it disables AC6's last-used refresh with no compile or runtime signal, and the Javadoc's "mirrors other ports' minimal-fake-friendly defaults" is false (it is the only `default` in any read-model port). Task 4 specified port + impl. Make it abstract; the one lambda fake (`ListItemsTest:80`) and `ItemControllerTest.InMemoryItemReadModel` become explicit. [`backend/src/main/java/de/sgart/collaboration/domain/readmodel/ItemReadModel.java:33`]
- [x] [Review][Patch] Missing `mounted` guard after `await` in the fast-add field — popping the route while an add is in flight disposes `_controller`/`_focusNode`, and the continuation then calls `clear()`/`unfocus()` on them ("used after being disposed"). Every other async-after-await path in the codebase is guarded (`_safeEmit`/`isClosed`). [`app/lib/features/lists/presentation/fast_add_field.dart:60-63,77-80`]
- [x] [Review][Patch] The `item_form_sheet` add-mode branch is now unreachable dead code — the sole caller always passes `existingItem:`, so `_isEditing` is permanently true and `itemAddHeading`, `itemAddSubmitButtonLabel`, the `existingItem == null` controller defaults and the sheet's `cubit.addItem` call are dead. Only `itemAddAction` was retired from the ARB. CLAUDE.md §1 (no dead code) + Task 11. Require `existingItem` and drop the add branch + both orphaned strings. [`app/lib/features/lists/presentation/item_form_sheet.dart:16,35-41,43,92,114,175`, `app/lib/l10n/app_de.arb:402,407`]
- [x] [Review][Patch] `_unitFromServerName` copy-pasted into a fourth widget — identical private helpers now sit in `fast_add_field`, `list_detail_page`, `item_form_sheet` and `move_merge_dialog`. One piece of knowledge, four representations (CLAUDE.md §1 DRY + Boy Scout). Extract once next to `QuantityFormatter`. [`app/lib/features/lists/presentation/fast_add_field.dart:187`]
- [x] [Review][Patch] Client and server disagree on suggestion ordering — the server sorts `ORDER BY name ASC` under the DB collation, `_upsertSuggestion` re-sorts with Dart's `compareTo` (UTF-16 code units), so „Äpfel" and every lowercase name land differently. With the 6-row cap this can push a real match out of the visible panel after an add. Sort case-insensitively client-side and drop the port Javadoc's "replay-stable" claim (it depends on the deployment collation). [`app/lib/features/lists/presentation/list_detail_cubit.dart:337`, `backend/src/main/java/de/sgart/collaboration/domain/readmodel/ItemSuggestionReadModel.java`]
- [x] [Review][Patch] Untested paths — no `item_suggestion_test.dart` for `ItemSuggestion.fromJson`'s malformed-response path (the sibling `Item.fromJson` has `item_test.dart`); the projector's empty-`householdIdOf` branch (the silent skip, Cl. 5) is never exercised; the Testing-standards "explicit no-PII assertion for the suggestion read model" was not written (the generic `NoPersistedPersonalDataTest` only greps for `display_name`/`email`). [`app/test/features/lists/data/`, `backend/src/test/java/de/sgart/collaboration/adapter/out/ShoppingListReadModelProjectorTest.java`]
- [x] [Review][Patch] `bootstrap()`'s suggestion fetch replaces the cache wholesale — an add that succeeds while the fetch is still in flight has its optimistic upsert clobbered, and the just-added name silently drops out of autocomplete. Merge instead of replace. [`app/lib/features/lists/presentation/list_detail_cubit.dart:80`]
- [x] [Review][Patch] The suggestions-load failure is swallowed by a genuinely empty `catch` — the method's own doc says "log/ignore", the code only ignores, so a member whose autocomplete is silently broken produces no signal for anyone. [`app/lib/features/lists/presentation/list_detail_cubit.dart:81-84`]
- [x] [Review][Patch] `.take(6)` is an unnamed magic number that truncates silently — a member with seven „Milch…" articles cannot reach the seventh and gets no hint anything was hidden; the "add as new" row then produces a server-side duplicate rejection. Name the constant and rank an exact match first. [`app/lib/features/lists/presentation/fast_add_field.dart:91`]
- [x] [Review][Patch] No a11y label/semantics on the fast-add field or suggestion rows, though Task 11 lists them and is checked off — the field carries only `hintText`, the rows are bare `ListTile`s. (Touch targets themselves are fine: dense one-line `ListTile` is 48dp.) [`app/lib/features/lists/presentation/fast_add_field.dart:107-115,178-184`]

- [x] [Review][Defer] No shared transaction across `insertItem` + `recordUsage` — the two read models can diverge if the second write fails, since the projector logs-and-skips per event [`backend/src/main/java/de/sgart/collaboration/adapter/out/ShoppingListReadModelProjector.java:90-118`] — deferred, pre-existing projector design (no transaction boundary since Story 2.1)
- [x] [Review][Defer] `normalized_name VARCHAR(120)` exactly equals `ItemName.MAX_LENGTH`, and Unicode lowercasing can lengthen a string, so a pathological 120-char name fails the insert and is skipped on every replay [`backend/src/main/resources/db/migration/V7__item_suggestion_read_model.sql`] — deferred, vanishingly rare for German grocery content
- [x] [Review][Defer] The suggestion panel is an inline sibling in the page `Column`, so opening it shrinks the item list rather than floating over it; Task 10 says "overlay/panel" [`app/lib/features/lists/presentation/fast_add_field.dart:98-104`] — deferred, functionally "above" as the AC requires
- [x] [Review][Defer] `actionError` renders at the bottom of the scrolling item list, so a fast-add rejection on a long list can land off-screen while the field that caused it sits at the bottom [`app/lib/features/lists/presentation/list_detail_page.dart:99-105`] — deferred, pre-existing Story 2.3 placement

**Dismissed as noise (5):** the `entry as Map<String, dynamic>` cast (the convention in every `*_api.dart`); `Unit.valueOf` throwing on an unknown stored unit (mirrors `JdbcItemReadModel`; only our own enum writes that column); `FastAddField` taking the cubit by constructor inside a provider (matches `item_form_sheet`/`move_merge_dialog`); the retired `aBlankNameIsBlockedClientSide` sheet test (behaviour still covered by `updateItem_rejectsABlankNameWithoutCallingTheApi` plus the analogous rename-sheet widget test); `fast_add_field_test` lacking a Done-list case (covered at page level by `aReadOnlyDoneListShowsNoFastAddFieldNorEditRemoveMoveAffordances`).
