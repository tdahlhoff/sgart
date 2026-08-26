---
baseline_commit: 8ffc3c9613593e2c3099d45f771677f729bdb4a6
---

# Story 2.1: Create and name multiple open lists

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a member,
I want to keep several open lists at once, named or auto-named,
so that parallel shops (e.g. „Wocheneinkauf" and „Getränke") don't collide.

## Acceptance Criteria

1. **A member creates a list in a household: it is created as `Open` with an optional name, more than one Open list may coexist, via command → `ShoppingListCreated`.** (FR4, AD-4, AD-3)
   - Creating a list is a **command** (`CreateShoppingList`) carrying the target `householdId`, the client-minted `listId`, an **optional** `name`, and the client `commandId` envelope (Story 1.5). It is appended to a **brand-new `list-{listId}` stream** under `AggregateVersion.initial(...)`.
   - **`ShoppingList` is the second real aggregate** in the Collaboration context (after `Household`) and is a **distinct aggregate** that references its household **by id only** (AD-3) — it never loads or mutates the `Household` aggregate. Only a **member** of that household may create a list; membership is enforced at the handler seam via the Identity ACL's published `ResolveMemberIdentity` port (`(keycloakUserId, householdId) → MemberId`, a non-member is a `403`, AD-2/AD-5).
   - The name is **optional** — a blank/absent name creates a valid **unnamed** list (that is the „Liste N" case, AC2), *not* an error. A present name is trimmed, non-blank-after-trim, and bounded (fail fast).
   - **No PII in the write path:** `ShoppingListCreated` carries `householdId`, `listId`, and the optional list name only — never `keycloakUserId`, display name, or email (AD-5/AD-6). A list name is not personal data (like a household name). The event does **not** record *who* created the list (no audit trail in MVP — YAGNI, mirroring `HouseholdRenamed`).
   - **Multiple Open lists coexist:** nothing limits a household to one list; two `CreateShoppingList` commands on two fresh streams both succeed and both show up in the household's list read model.

2. **An unnamed Open list displays as „Liste {N}" by creation order among Open/In-Trip lists — derived, never stored.** (FR4, FR13/AR10)
   - The number **N is the list's 1-based ordinal position** in the creation-ordered sequence of the household's **Open (and, once they exist, In-Trip)** lists — **counting named lists too**. Example: list A „Getränke" (created 1st), list B unnamed (created 2nd) → B renders as **„Liste 2"**, not „Liste 1". A **Done** list is excluded from this sequence.
   - The number is **derived, never persisted** (no `list_number` column, no stored label). The read model stores each list's own optional `name` plus enough ordering information (a stable creation timestamp/sequence) for the ordinal to be computed on read.
   - The word **„Liste" is user-facing German copy and must resolve through the localization layer** (AR10/FR13 — no hard-coded user-facing strings): a localized template like `listsDefaultName(number)` renders „Liste {N}". Only the *number* is derived; the label text is localization. **Derivation lives on the client** (it already owns the localization template and the ordered list from the query).

3. **A member sets or changes an Open (or In-Trip) list's name; a Done list's name cannot be changed.** (FR4)
   - Renaming is a **command** (`RenameShoppingList`) carrying `householdId`, `listId`, the new (required, non-blank) `name`, and the `commandId` envelope; it loads the `list-{listId}` stream and appends `ShoppingListRenamed` at the **loaded** version (online load-then-append, mirroring `RenameHousehold`; a client-supplied `basedOnVersion` + offline queue is Epic 5). A rename **to the current name is a convergent no-op** — nothing is raised (AD-8).
   - The name-change guard is a **domain invariant** modeled on `ListStatus`: a rename is permitted only when the list is `OPEN` (and, from Epic 3, `IN_TRIP`); a `DONE` list rejects the rename. **Scope boundary (see Clarification 1):** in Epic 2 a list is only ever `OPEN` — the `IN_TRIP`/`DONE` transitions are Epic 3 (a trip moves the list to In-Trip; completion moves it to Done). So 2.1 **codes the guard** and fully exercises the reachable **Open-path** rename; the `DONE`/`IN_TRIP` transitions and the end-to-end "Done cannot be renamed" coverage land in Epic 3 with the events that produce those states.
   - Only a **member** of the household may rename (same `ResolveMemberIdentity` seam as create); a rename is **not** Admin-gated (unlike `Household.rename`) — any member does daily list work (FR2/AR10: roles gate only governance).

4. **The write path (`list-{id}` streams in KurrentDB) and read path (a PostgreSQL `shopping_list` projection) are durably wired and proven, and the Spring context still loads when neither infrastructure is running.** (AD-4, NFR6, the "context-loads-with-infra-down" guardrail)
   - `CreateShoppingList`/`RenameShoppingList` append to the **existing `EventStore` port** (`KurrentDbEventStore`, Story 1.6 — reused unchanged) under expected-version. A **new projector** subscribes to the **`list-` streams** and folds `ShoppingListCreated`/`ShoppingListRenamed` into a **new `shopping_list` read model** (Flyway migration); command handlers **never** write the read model (AD-4). Proven against **real PostgreSQL + real KurrentDB via Testcontainers**, reusing the Story 1.6 test infrastructure.
   - The new projector follows the `HouseholdReadModelProjector` pattern exactly: a `SmartLifecycle` whose **auto-start is gated off by default** (constructing the bean does no I/O), so `contextLoads()` / `SgartApplicationTest` and the existing `ContextLoadsWithout{KurrentDb,Postgres}Test` stay green with both stores down. **No PII column** in the new table — extend `NoPersistedPersonalDataTest` to cover it.

## Tasks / Subtasks

- [x] **Task 1 — `ShoppingList` aggregate + value types + events (collaboration.domain)** (AC: #1, #3)
  - [x] Add `ShoppingListId` to `de.sgart.shared` (UUID-backed, `generate()`/`fromString(...)`, no domain meaning), mirroring `StoreId`/`HouseholdId` exactly — it is cross-context by design (Epic 3 trips reference a list by id). Add `StreamId.forList(ShoppingListId)` (the `StreamType.LIST` prefix already exists) — this is the reserved `forList` factory `StreamId`'s Javadoc names ("arrive with their id types in Epics 2 and 3").
  - [x] Add `ShoppingListName` value object in `collaboration.domain` (trimmed, non-blank, `MAX_LENGTH` — copy `HouseholdName`'s convention: throw plain `IllegalArgumentException`, never a custom domain exception; that translation to a client `code` is the application layer's job).
  - [x] Add `ListStatus { OPEN, IN_TRIP, DONE }` enum in `collaboration.domain` (ubiquitous language, glossary; Epic 2 only reaches `OPEN`). Constants only — the transitions are Epic 3.
  - [x] Add the aggregate `ShoppingList` extending `de.sgart.shared.EventSourcedAggregate` (same base + contract as `Household`): private constructor takes `StreamId`; `static create(ShoppingListId listId, HouseholdId householdId, ShoppingListName nameOrNull, CommandId)` validates (nulls; name may be null = unnamed) and `raise`s **`ShoppingListCreated`**; `static rehydrate(StreamId, history)` replays; state mutated **only** in `apply(DomainEvent)`. Hold `householdId`, nullable `name`, and `status` (folded to `OPEN` on `ShoppingListCreated`). Expose `householdId()` (the create/rename handlers assert it matches the request path — see Task 5).
  - [x] `rename(ShoppingListName newName, CommandId)`: guard `status == OPEN` else throw domain `ListNameChangeNotPermittedException`; **no-op** when `newName.equals(this.name)` (raise nothing, AD-8); else `raise(ShoppingListRenamed)`. Rename is **not** role-gated inside the aggregate (membership is the handler's job; the `ShoppingList` aggregate does not know household roles — it is a separate aggregate from `Household`).
  - [x] Add events in `collaboration.domain.event` (past-tense PascalCase, `implements DomainEvent`, each carries its own `EventId`): **`ShoppingListCreated`** (`eventId`, `householdId`, `listId`, nullable `ShoppingListName`) and **`ShoppingListRenamed`** (`eventId`, `listId`, `ShoppingListName newName`). Null-check every field except the intentionally-nullable name/`chainId`-style optional (document the nullable-name comment like `StoreAdded` does for `chainId`). Add domain exception `ListNameChangeNotPermittedException` in `collaboration.domain.exception` (mirror `RenameNotPermittedException`).
  - [x] Fast unit tests (pure, no infra): `create` with a name raises exactly `ShoppingListCreated` carrying it, status `OPEN`, version = 1; `create` with a `null` name raises an unnamed `ShoppingListCreated` (valid); `rename` on an OPEN list raises `ShoppingListRenamed`; rename-to-same-name is a no-op (nothing raised); replaying `[ShoppingListCreated, ShoppingListRenamed]` rebuilds identical state + version; **no event carries display name/email/keycloakUserId** (the AD-5/AD-6 guard, as `HouseholdTest` asserts). *(The `DONE`-rejects-rename branch is coded now but is verified end-to-end in Epic 3 — see Clarification 1; do not fabricate an Epic-3 transition event to test it here.)*

- [x] **Task 2 — Read model + projector (second projector; `list-` streams)** (AC: #1, #2, #4)
  - [x] Add the `shopping_list` read-model table via a **new Flyway migration** (`V5__shopping_list_read_model.sql`): `list_id (pk)`, `household_id`, `name` (nullable), `status`, and a **creation-order** column (`created_at timestamptz` or a sequence) so the AC2 ordinal is computable. Index `household_id`. **No PII column** (list_id/household_id opaque; name is a list name).
  - [x] Add the domain-owned read-model **port** `ShoppingListReadModel` in `collaboration.domain.readmodel` (mirror `StoreReadModel`) with a `ShoppingListView` record — expose the household's lists ordered by creation (the query filters/derives from there). For 2.1 the query needs **Open** lists; design the view to carry `status` so 2.2's Offen/Erledigt split reads through the same port.
  - [x] Add the JDBC adapter `JdbcShoppingListReadModel` in `collaboration.adapter.out` (mirror `JdbcStoreReadModel`): `upsertList(...)` (insert on created, keeping `created_at` stable across re-projection), `renameList(...)`, and the ordered read. Idempotent upserts (re-projecting an event is a safe no-op).
  - [x] Add `ShoppingListReadModelProjector` in `collaboration.adapter.out` — **a new projector** (do **not** overload `HouseholdReadModelProjector`, whose subscription filter is the `household-` prefix): same `SmartLifecycle` shape, **`autoStart` gated off by default**, resubscribe-on-drop, log-and-skip a bad event, filtering the **`list-` stream-name prefix** (`StreamId.StreamType.LIST.prefix() + "-"`). `project(DomainEvent)` folds `ShoppingListCreated` → `upsertList`, `ShoppingListRenamed` → `renameList`. Wire its Spring bean in the existing `adapter.out` config alongside the household projector.
  - [x] Testcontainers integration test (real PostgreSQL, reuse the 1.6 harness): projecting `ShoppingListCreated` yields the row (named and unnamed); `ShoppingListRenamed` updates only the name and preserves `created_at`; two lists in one household both project and come back in creation order; the table holds **no** PII column.

- [x] **Task 3 — `ListOpenLists` query (read side of CQRS)** (AC: #1, #2, #4)
  - [x] Add `ListOpenLists` in `collaboration.application.query` (mirror `ListStores`): compose `ResolveMemberIdentity` (membership check → `403` for a non-member) with `ShoppingListReadModel`, returning the household's **Open** lists **in creation order** as a `ShoppingListSummary` record of **plain `String`s / an ordinal-safe shape** (`listId`, nullable `name`, `status`, and the creation order — enough for the client to render „Liste N"). No side effects (CLAUDE.md §6 CQRS coverage). Never accept a `MemberId` from the client.
  - [x] Unit/integration test: returns exactly the caller's Open lists in creation order; a non-member is rejected; the query is side-effect free (a second call returns the same rows).

- [x] **Task 4 — Commands + handlers (create, rename)** (AC: #1, #3)
  - [x] Add `CreateShoppingList` command (`implements Command`; `householdId`, `listId`, nullable `ShoppingListName`, `commandId`, `basedOnVersion = AggregateVersion.initial(list stream)`) + `CreateShoppingListHandler` **beside it** in `collaboration.application.command` (package-structure rule §8): resolve caller `MemberId` via `ResolveMemberIdentity(keycloakUserId, householdId)` → `ShoppingList.create(listId, householdId, nameOrNull, commandId)` → `EventStore.append(initial, uncommitted, commandId)`. The client mints `listId` (read-your-writes), so the handler/REST return **no body** (like `AddStore`).
  - [x] Add `RenameShoppingList` command (`householdId`, `listId`, required `ShoppingListName`, `commandId`, loaded `basedOnVersion`) + `RenameShoppingListHandler` (mirror `RenameHouseholdHandler`): resolve member → `rehydrate` from `readStream(list-{id})` → **assert the loaded list's `householdId()` equals the path `householdId`** (a list id under the wrong household is a `404`/`403`, defense-in-depth) → `list.rename(newName, commandId)` (translating the domain `ListNameChangeNotPermittedException` → an application exception) → append at the loaded version only if events were raised (skip the no-op). Handle a `readStream` that is empty (unknown `listId`) as a **not-found** (a distinct `code`).
  - [x] Extend `CommandFieldTranslations` (DRY) with `toShoppingListId`, `toShoppingListNameOrNull` (create — blank/absent ⇒ `null`, valid), and `toShoppingListName` (rename — blank ⇒ `list.nameRequired` `400`; too long ⇒ `list.nameTooLong` `400`), plus `command.listIdRequired`/`command.listIdInvalid` codes.
  - [x] Add application exceptions in `collaboration.application.exception`: `InvalidShoppingListNameException` (mirror `InvalidHouseholdNameException`), `ListNameChangeNotPermittedApplicationException` (mirror `RenameNotPermittedApplicationException`), and a not-found (`ShoppingListNotFoundException` → `404`) for an unknown `listId`.
  - [x] Backend handler unit tests (in-memory `EventStore` + in-memory ACL doubles — the bulk of coverage): create emits `ShoppingListCreated` (named + unnamed) and is member-gated (`403` for a non-member); rename emits `ShoppingListRenamed`, is a no-op on the same name, `404`s an unknown list, and rejects a list whose loaded `householdId` differs from the path.

- [x] **Task 5 — REST (adapter.in) + error advice** (AC: #1, #2, #3)
  - [x] Add `ShoppingListController` in `collaboration.adapter.in`, nested under the household (mirror `StoreController`): `@RequestMapping("/api/v1/households/{householdId}/lists")`.
    - `POST` (body `{listId, name?, commandId}`) → `201`, no body (client-minted id).
    - `GET` → `200` `[{listId, name, status, createdAt|order}]` (the AC2 source — client derives „Liste N").
    - `PATCH /{listId}` (body `{name, commandId}`) → `204` (mirror the household rename shape: household rename is `PATCH /api/v1/households/{id}`).
    - Caller identity **only** from the JWT `sub` via `AuthenticatedCaller.fromJwt(jwt)` — never body/path (AR10, AD-5). The `SecurityConfig` chain already covers `/api/v1/**`.
  - [x] Add `@ExceptionHandler`s to the existing `WriteErrorAdvice` for `InvalidShoppingListNameException` (`400`), `ListNameChangeNotPermittedApplicationException` (`403`), and `ShoppingListNotFoundException` (`404`). `NotAMemberException` (`403`), `InvalidCommandEnvelopeException` (`400`), and `ConcurrencyConflictException` (`409`) are already mapped — reuse.
  - [x] MockMvc slice tests (`spring-security-test` `jwt()`, no live Keycloak): `POST` `201`; `GET` `200` returns the caller's lists; `PATCH` `204`; `401` unauthenticated; blank rename name `400` with `list.nameRequired`; a non-member `403`; unknown list `404`; caller-identity-from-`sub`-only.

- [x] **Task 6 — Flutter: minimal lists surface (client)** (AC: #1, #2, #3)
  - [x] Add a **`lists` feature** (`app/lib/features/lists/…`, BLoC per screen). **Data:** `ShoppingListSummary` (`listId`, nullable `name`, `status`, creation order) + `ShoppingListsApi` (`listOpenLists(householdId)`, `createList(householdId, {String? name, required listId, required commandId})`, `renameList(householdId, listId, name, {required commandId})`) over `AuthenticatedHttpClient` (`getJsonList`/`postJson`/`patchJson` already exist), mirroring `HttpHouseholdsApi`.
  - [x] **Presentation:** `ShoppingListsCubit` (mirror `HouseholdsCubit`/`AuthCubit`: constructor-injected `ShoppingListsApi`, **every `emit` guarded by `isClosed`**, `AppException`→`AppError` mapping) with `bootstrap()`/`refresh()`, `createList(name?)`, `renameList(listId, name)`; `ShoppingListsState`. **Use `CommandIntent` for the `commandId`/`listId` lifecycle — do NOT hand-roll it** (Epic-1 retro Action 1, already implemented at `app/lib/shared/commands/command_intent.dart`): the recurring Epic-1 footgun (dupe households 1.6, rename divergence 1.7, dropped second store 1.8) was exactly a mis-managed `commandId`. **Create** = a `CommandIntent(_hasResourceId: true)` (the paired resource is the client-minted `listId`): `beginAttempt(trimmedName ?? '')` (keyed on the name so an edited retry regenerates), read `commandId`/`resourceId`, POST, `complete()` on success so the next create can't reuse a spent id. **Rename** = a `CommandIntent()` keyed on the new name (`beginAttempt(trimmedNewName)`), `complete()` on success — exactly the `rename_household_cubit` shape.
  - [x] Replace `_ListsPlaceholder` in `HouseholdShell` with a **minimal `ListsView`**: the household's open lists (each row shows `name ?? localizations.listsDefaultName(orderIndex)` + a rename affordance), an **empty state** with a helpful CTA (UX-DR13), and a **„+ Neue Liste"** action opening a minimal create dialog/sheet (optional-name field). Scope the `BlocProvider<ShoppingListsCubit>` **to the active household** (key/re-create on switch, since the shell keeps tabs alive in an `IndexedStack`), provided a `ShoppingListsApi` the same way `FirstRunRouter` builds its `Dio`/api. **Do not** build the Offen/Erledigt segmented filter, item counts/progress, or the Done archive — those are **Story 2.2**.
  - [x] **Derived naming (client):** compute `orderIndex` as the 1-based position of each list within the full creation-ordered Open sequence (named lists included — see AC2's example), and render the localized „Liste N" only for lists with no explicit name.
  - [x] **Fail-fast client guard (retro action / deferred-work):** create's name is optional (blank ⇒ unnamed, a valid no-guard case); **rename** must early-return / disable submit on a blank/whitespace name and enforce `maxLength` (no pointless round-trip), consistent with the `list.nameRequired`/`list.nameTooLong` server codes.
  - [x] **l10n** (`app/lib/l10n/app_de.arb`, English keys / German values — language policy; regenerate `app_localizations*`): `listsCreateAction` („+ Neue Liste"), `listsDefaultName` (template „Liste {number}", with a `number` placeholder), `listsCreateNameHint` („Name (optional)"), `listsRenameAction` / rename dialog copy, `listsEmptyState` (calm plain-German CTA), and map `list.nameRequired` / `list.nameTooLong` / `list.nameChangeNotPermitted` / `shoppingList.notFound` in `app/lib/shared/errors/error_message_resolver.dart` to localized copy.
  - [x] Flutter widget/cubit tests (stub the HTTP boundary — no real network; reuse `test/support/widget_test_harness.dart`): creating a list refreshes and shows it; an unnamed list renders „Liste N" at the right ordinal (the named-list-in-between case); renaming updates the row; a blank rename is blocked client-side; an empty household shows the empty state; a `{code}` error maps to localized copy. Add `bloc_test` coverage for the cubit.

- [x] **Task 7 — Build wiring, guardrails, and green suites** (AC: #1, #2, #3, #4)
  - [x] No new backend deps expected (KurrentDB client, JDBC/Flyway/Postgres, Testcontainers all landed in 1.6). No new Flutter deps (`uuid`, `dio`, `flutter_bloc`, `bloc_test` present).
  - [x] Keep **all ArchUnit rules green** (`HexagonalArchitectureTest`): `collaboration.domain` (the aggregate, events, value types, `ListStatus`, the read-model port) stays pure — no `io.kurrent`, `org.springframework`, `jakarta.persistence`, `..adapter..`; the projector/JDBC/JSON live in `adapter.out`; the controller/advice in `adapter.in`; the mint/resolve stays behind the Identity **application** port. **Do not leak a `collaboration.domain` type (`ShoppingListName`, the domain exception) into `adapter.in`** — the 1.6 lesson: the handler translates the domain invariant to an `*ApplicationException`, and DTOs cross the boundary as plain `String`s (§8; the AdapterIn→Domain violation fixed in `7440f4e`). Extend `NoPersistedPersonalDataTest` to the `shopping_list` table.
  - [x] `package-info.java` stays accurate for each touched layer (§8). Add the `ShoppingListCreated`/`Renamed` to `DomainEventJsonCodec` with **stable `type` tags** (the wire format is decoupled from class names — 1.6 convention) so the projector can deserialize them; add a codec round-trip test.
  - [x] Run **both suites locally for real** before review (memory `backend-test-hygiene`, `flutter-test-local`). Backend: `cd backend && ./gradlew test` (unit + ArchUnit + Testcontainers — needs Docker). Client: `export PATH="$PATH:/home/timo/tools/flutter/bin"; cd app && flutter analyze && flutter test`. Confirm the context-loads-with-infra-down tests still pass with the containers stopped. Zero new analyzer/compiler warnings. Baseline green counts to keep: **backend 202, Flutter 245** (Epic 1 close).

## Dev Notes

### Scope & intent
**This is the first story of Epic 2 and introduces the `ShoppingList` aggregate — the second real aggregate in the Collaboration context.** The heavy durable infrastructure already exists (Story 1.6 wired real KurrentDB + PostgreSQL + Testcontainers + the first projector; Story 1.5 fixed the command/event/concurrency envelope). So 2.1 is **not** an integration-plumbing story like 1.6 — it is a **second vertical slice that reuses the proven contracts**: new aggregate → new command/event → append via the existing `EventStore` → **a second projector** → new read model → new query → a minimal client surface. The work is *following the `Household` patterns precisely for `ShoppingList`*, not inventing new shapes.

**Deliberate scope boundaries (build the create/rename slice; defer the rest of Epic 2):**
- **Minimal client, not the full overview.** Replace the `_ListsPlaceholder` with a *minimal* lists view (list the open lists, „+ Neue Liste", per-row rename, empty state). The **Listen overview screen with the segmented „Offen"/„Erledigt" filter, item counts/progress, and the Done archive is Story 2.2** (UX-DR11). Items are **Story 2.3**, move-to-list **2.4**, autocomplete **2.5**, store assignment **2.6**. Do not build any of those here.
- **`ListStatus` is modeled, but only `OPEN` is reachable.** `IN_TRIP` (trip start) and `DONE` (trip completion) are **Epic 3** transitions. 2.1 codes the enum and the rename guard so the ubiquitous language and the invariant exist from the start, but no Epic-2 command drives a list out of `OPEN`. See Clarification 1 — **do not** invent an Epic-3 completion/trip event just to reach `DONE` here (that would violate the retro's premature-value rule, action item #4).
- **No delete, no move, no items.** 2.1's ACs are create + auto-name + rename only. The list-detail ⋯ menu (rename/print/delete, UX-DR8) and the fast-add field are later stories.

### Source of truth: ARCHITECTURE-SPINE + epics + glossary (binding)
[Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md; epics.md §"Story 2.1"; specs/spec-sgart/glossary.md]
- **AD-3 (spine 76–80):** `Household`, `ShoppingList`, `ShoppingTrip` are **distinct aggregates in one context, referencing each other by id only** — never by object graph. → `ShoppingList` holds `householdId` (a `HouseholdId`), never a `Household`; it never loads or mutates the household. Its authorization ("is the caller a member?") is answered by the **Identity ACL** at the handler, not by reaching into `Household`.
- **AD-4 / AR3 (spine 82–86):** state changes **only** by appending events under an expected-version check; read models are built by **projectors** and **never written by command handlers**; projections are **eventually consistent**. → new projector + new read model; the create response carries no projected data (the client minted the id).
- **AD-1 / AR1 (spine 64–68):** every state change is a command handled by an aggregate emitting events; the domain imports **no** framework/infra/transport type. → `ShoppingList` + events + `ShoppingListName` + `ListStatus` stay pure; the ArchUnit rules already ban `io.kurrent`/`org.springframework`/`jakarta.persistence` from `..domain..`.
- **AD-5 / AD-6 (spine 88–98):** events/read models reference a person only by `MemberId`, and only when needed — **no display name/email/keycloakUserId**. A list has **no creator attribution** in MVP (YAGNI). List name and household name are **not** personal data.
- **AD-8 (spine 106–110):** client commands carry `commandId` + `basedOnVersion`; a stale expected-version is rejected; convergent actions (rename-to-same-name) are silent no-ops. → the rename no-op branch; the create uses `AggregateVersion.initial(list stream)`.
- **AD-10 / AR9:** `Item` is an entity **inside** the `ShoppingList` aggregate — but **items are Story 2.3**, not here. 2.1 creates the aggregate root only; **no process manager** is needed (move-to-list's process manager is Story 2.4). Do not build one here (YAGNI).
- **AR10 / conventions (spine 132–146):** events **past-tense PascalCase**; stream key **`list-{id}`**; commands **imperative** carrying the envelope; REST under **`/api/v1`**; error shape `{code, message, details}`; identity from JWT `sub`. **Event naming:** use **`ShoppingListCreated`/`ShoppingListRenamed`** (matching the `HouseholdCreated`/`HouseholdRenamed` precedent and the aggregate's own name); the epic's „`ListCreated`" (line 481) is shorthand for the same event.
- **Glossary (binding):** *ShoppingList* (a household's list of items; several Open at once; optional name; „Liste N" derived), *Open/In-Trip/Done* list lifecycle, *Member* (any member does daily work; roles gate only governance). Use these exact names, no abbreviations (`shoppingListId`, not `listId`-abbrev in *type* names — the field/variable `listId` is fine as the established short form of the full `ShoppingListId` type, consistent with `householdId`/`storeId`).

### The patterns already in the repo — read before writing (this is the whole job)
`ShoppingList` is a near-mechanical mirror of `Household`. Read and copy these:
- **Aggregate:** `backend/.../collaboration/domain/Household.java` — the `create`/`rehydrate`/`apply(switch on event)`/`raise` shape, the private-constructor-takes-`StreamId`, the no-op rename branch (`newName.equals(this.name)`), and the `HouseholdTest` no-PII assertion to copy. `ShoppingList` is **simpler** (no member-role map — a separate aggregate).
- **Value object:** `collaboration/domain/HouseholdName.java` — copy verbatim for `ShoppingListName` (trim, non-blank, `MAX_LENGTH`, plain `IllegalArgumentException`).
- **Events:** `collaboration/domain/event/{HouseholdRenamed,StoreAdded}.java` — `HouseholdRenamed` for the rename event (no "who"); `StoreAdded` for the nullable-field Javadoc convention (the optional list name mirrors the optional `chainId`).
- **Command + handler:** `collaboration/application/command/{RenameHousehold,RenameHouseholdHandler,CreateHousehold,CreateHouseholdHandler}.java` — the **rename handler is the exact template** (resolve member → rehydrate → loaded version → translate domain exception → append-if-events). Keep each command DTO **beside its handler** (§8).
- **Translations:** `collaboration/application/CommandFieldTranslations.java` — add the list translators here (DRY), following `toHouseholdName`/`toStoreName`/`toHouseholdId`.
- **Read model + projector + query:** `collaboration/adapter/out/{HouseholdReadModelProjector,JdbcStoreReadModel}.java`, `collaboration/domain/readmodel/StoreReadModel.java` + `StoreView`, `collaboration/application/query/ListStores.java` — `ListStores` is the query template; `HouseholdReadModelProjector` is the `SmartLifecycle`/`autoStart=false`/resubscribe/filter-by-prefix template (change the prefix to `list-` and make it a **separate** projector).
- **REST + advice:** `collaboration/adapter/in/{StoreController,WriteErrorAdvice}.java` — `StoreController` (nested-under-household, client-minted-id-so-201-no-body, `AuthenticatedCaller.fromJwt`) is the controller template; add the new `@ExceptionHandler`s to `WriteErrorAdvice`.
- **Migrations:** `backend/src/main/resources/db/migration/V4__store_read_model.sql` — the next number is **`V5__shopping_list_read_model.sql`**.
- **Client:** `app/lib/features/households/data/households_api.dart` + `presentation/households_cubit.dart` (+ `AuthCubit`) — the `ShoppingListsApi`/`ShoppingListsCubit` templates (`getJsonList`/`postJson`/`patchJson` on `AuthenticatedHttpClient` already exist; `isClosed`-guarded emit; `AppException`→`AppError`). `app/lib/features/households/presentation/first_run_router.dart` — how a feature builds its own `Dio`/api and provides a cubit. `app/lib/features/households/presentation/household_shell.dart` — the `_ListsPlaceholder` you replace (and the switcher re-provide pattern for scoping providers).
- **`commandId`/`listId` lifecycle — use the shared `CommandIntent` (do not hand-roll):** `app/lib/shared/commands/command_intent.dart` (Epic-1 retro Action 1) is the single abstraction Epic 2's write cubits must use — `beginAttempt(payloadKey)` reuses the id on the same payload / regenerates on a changed one, `complete()` freshens after a successful append, and a `_hasResourceId: true` intent pairs a client-minted resource id (here the `listId`) in lockstep. `rename_household_cubit.dart` and `stores_cubit.dart` are already refactored onto it — copy their usage. This kills the recurring Epic-1 footgun (dupe households, rename divergence, dropped second store) by construction.

### The `DONE`-rename guard is coded now but proven in Epic 3 (read carefully)
AC3 states a Done list cannot be renamed. In Epic 2 **a list is only ever `OPEN`** — the sole state-producing event is `ShoppingListCreated` (→ `OPEN`), and nothing transitions it further until Epic 3 (a trip → `IN_TRIP`; completion → `DONE`). So:
- **Do** model `ListStatus { OPEN, IN_TRIP, DONE }` and code `rename`'s guard (`status == OPEN` permitted). The vocabulary and invariant exist from the aggregate's birth.
- **Do not** invent an Epic-3 completion/trip-done event to force a `DONE` state for a test — that is exactly the premature-value trap the Epic-1 retro flagged (action item #4). The `DONE`/`IN_TRIP` transitions, and the end-to-end "renaming a Done list is rejected" coverage, are **Epic 3's** to add alongside the events that produce those states.
- In 2.1, unit tests cover the **reachable** behavior: create (named/unnamed) → `OPEN`, rename on `OPEN`, rename-to-same no-op. The guard is present and correct-by-construction; it simply has no reachable `DONE` input yet. Note this deferral in the Completion Notes so the reviewer expects it (and add a line to `deferred-work.md` pointing Epic 3 at wiring the guard's end-to-end test when it adds the Done transition).

### Read-your-writes & the projector auto-start (same trap as 1.6)
- The client **mints `listId`** and routes on it, so create returns `201` with no body and the minimal view can show the just-created list without waiting on the projection (eventual consistency, AR3/NFR9). The `GET` list refresh catches up.
- The new projector is a `SmartLifecycle` with **`autoStart = false`** by default (exactly like `HouseholdReadModelProjector`), so building the bean does no I/O and `contextLoads()` survives KurrentDB/Postgres being down (CI has neither). The Testcontainers integration test drives `projector.project(event)` directly against real PostgreSQL for determinism (the live subscription API is exercised by compilation + the vendor's own tests), mirroring how 1.6 tested its projector. **Do not** auto-start it in this story — no AC needs the live subscription running (the read-your-writes design means the client never depends on it), and eager start reopens the eager-boot-connection risk.

### Previous-story intelligence (Epic 1 done; retro 2026-08-25)
[Source: implementation-artifacts/1-6…1-11, epic-1-retro-2026-08-25.md, deferred-work.md]
- **The AdapterIn→Domain trap (fixed in `7440f4e`, and again mid-1.6):** the first REST pass on `Household` leaked `collaboration.domain` types into `adapter.in` and tripped `HexagonalArchitectureTest`. The fix pattern — the **handler translates a domain invariant into an `*ApplicationException`; DTOs cross the boundary as plain `String`s** — is now the established §8 seam. Apply it to `ShoppingList` from the start (translate `ListNameChangeNotPermittedException`; never pass `ShoppingListName` through the controller).
- **Retro action items relevant here (from sprint-status `action_items`):**
  - *Definition-of-Done extension (open):* `commandId` lifecycle correct (per-intent id, regenerated on payload change / after a successful append), error advice **mapped + tested** (every reachable domain/application exception → a 4xx, never a 500), a11y labels on new interactive widgets („+ Neue Liste", rename), **no dead strings/fields/stale comments**, client fail-fast guard on text inputs. Honor all of these.
  - *Base error-advice contract test (open):* consider asserting every new list endpoint maps missing/malformed input and each reachable exception to a 4xx (the action item wants this as a base contract test — at minimum cover it in the MockMvc slice).
  - *Premature-value rule (open):* the `DONE`-guard deferral above is a direct application — don't author what depends on Epic 3.
  - *One real end-to-end stack run early in Epic 2 (open, owner Dev):* needs `SGART_FLYWAY_ENABLED=true` + `SGART_PROJECTOR_AUTOSTART=true` against the docker-compose stack. Everything to date is Testcontainers/unit-verified only. **Consider doing this run as part of 2.1** (the first Epic-2 slice is the natural moment) to prove the projector actually subscribes and the read model fills against the real compose services — but it is a **verification chore, not an AC**; note the outcome in the Completion Notes.
- **Local test reality:** Flutter SDK at `/home/timo/tools/flutter/bin` (not on PATH). Run **both** suites for real (memory `backend-test-hygiene`: the backend suite was silently red for a stretch of Epic 1 because only `flutter test` was run). A green build means the **full** suite ran — say which.
- **Git:** solo, **direct-to-`main`** pre-beta (branches start at Epic 4/beta, memory `git-workflow`). Baseline = `8ffc3c9`.

### Latest tech notes
- No new dependencies. KurrentDB client (`io.kurrent:kurrentdb-client`), Flyway + Postgres, Testcontainers, and the Flutter stack all landed in 1.6 and are current as of the Epic-1 close. If touching any of them, honor CLAUDE.md §7 (bump to the newest *supported* version) — but this story adds none, so no bump is expected. `DomainEventJsonCodec` already maps events by a stable `type` tag; register the two new events there.
- **KurrentDB stream target:** the `EventStore.append` derives the target stream from the `AggregateVersion`'s `StreamId` (there is no separate stream param) — so `ShoppingList.create` must pass `StreamId.forList(listId)` to `super(...)`, and the create command's `basedOnVersion` is `AggregateVersion.initial(that stream)`.

### Project Structure Notes
```text
backend/src/main/java/de/sgart/
  shared/
    ShoppingListId.java                      # UUID id, cross-context (new; mirror StoreId)
    StreamId.java                            # + forList(ShoppingListId) (modified — factory the Javadoc reserved)
  collaboration/
    domain/
      ShoppingList.java                      # 2nd aggregate; extends EventSourcedAggregate (new)
      ShoppingListName.java                  # value object, optional/nullable use (new; mirror HouseholdName)
      ListStatus.java                        # enum { OPEN, IN_TRIP, DONE } (new)
      event/ShoppingListCreated.java         # DomainEvent: householdId, listId, name? (new)
      event/ShoppingListRenamed.java         # DomainEvent: listId, newName (new)
      exception/ListNameChangeNotPermittedException.java   # (new; mirror RenameNotPermittedException)
      readmodel/ShoppingListReadModel.java   # port + ShoppingListView (new; mirror StoreReadModel)
    application/
      command/CreateShoppingList.java + CreateShoppingListHandler.java   # (new — DTO beside handler)
      command/RenameShoppingList.java + RenameShoppingListHandler.java   # (new)
      query/ListOpenLists.java               # membership + read model -> open lists (new)
      exception/InvalidShoppingListNameException.java,
               ListNameChangeNotPermittedApplicationException.java,
               ShoppingListNotFoundException.java                        # (new)
      CommandFieldTranslations.java          # + toShoppingListId / *NameOrNull / *Name (modified)
    adapter/in/
      ShoppingListController.java            # POST/GET/PATCH /api/v1/households/{id}/lists (new)
      WriteErrorAdvice.java                  # + list @ExceptionHandlers (modified)
    adapter/out/
      ShoppingListReadModelProjector.java    # 2nd projector, `list-` prefix (new)
      JdbcShoppingListReadModel.java         # (new; mirror JdbcStoreReadModel)
      DomainEventJsonCodec.java              # + ShoppingListCreated/Renamed type tags (modified)
      (Spring config)                        # register the new projector/read-model beans (modified)
  resources/db/migration/V5__shopping_list_read_model.sql                # (new)
backend/src/test/java/de/sgart/…             # ShoppingListTest (domain), handler tests (in-memory doubles),
                                             # ListOpenLists test, projector/read-model Testcontainers test,
                                             # ShoppingListControllerTest (MockMvc); extend NoPersistedPersonalDataTest
app/lib/features/lists/
  data/shopping_list_summary.dart, shopping_lists_api.dart               # (new)
  presentation/shopping_lists_cubit.dart, shopping_lists_state.dart, lists_view.dart,
    create_list_dialog.dart, rename_list_dialog.dart                     # (new; minimal)
app/lib/features/households/presentation/household_shell.dart            # _ListsPlaceholder -> ListsView (modified)
app/lib/l10n/app_de.arb (+ generated)        # + lists copy & error codes (modified)
app/lib/shared/errors/error_message_resolver.dart                       # + list.* / shoppingList.* codes (modified)
app/test/features/lists/…                    # create/rename/derived-naming/empty-state widget+cubit tests (new)
```
- **`collaboration.domain` gains its second aggregate** — the ArchUnit layer-direction + slice rules apply unchanged (`..domain..` matches). Keep it pure; no `ShoppingListName`/domain-exception leak into `adapter.in` (§8). One class per concern (SRP); no abbreviations; reuse `shared` ids and the identity seam — **do not duplicate** `HouseholdId`/`MemberId`/`AggregateVersion`/`StreamId`/`AuthenticatedCaller`/`ResolveMemberIdentity`.

### Testing standards
[Source: CLAUDE.md §6; ARCHITECTURE-SPINE Testing convention (line 146); NFR6/NFR9]
- **Pyramid, base fast & infra-free.** The `ShoppingList` aggregate and both handlers are proven with **pure unit tests** using the in-memory `EventStore` + in-memory ACL — the bulk of coverage. Only the projector/read-model and the append round-trip use **Testcontainers**; REST uses MockMvc + `jwt()`.
- **CQRS coverage:** test the **commands** for the events they emit and the state change (`ShoppingListCreated` named/unnamed → `OPEN`; `ShoppingListRenamed`; the no-op); test the **query** (`ListOpenLists`) for the read model it returns, creation order, and that it is **side-effect free**.
- **Behavior, not structure** — full-sentence names, e.g. `creatingAListEmitsShoppingListCreatedWithTheOptionalName`, `anUnnamedListIsCreatedWithoutAName`, `renamingAnOpenListEmitsShoppingListRenamed`, `renamingToTheCurrentNameRaisesNothing`, `listsMineAreReturnedInCreationOrder`, `aNonMemberCannotCreateAList`, `renamingAnUnknownListIsNotFound`, `contextLoadsWithKurrentDbDown` (still green).
- **Derived-naming test (client):** the AC2 example — a **named** list created before an **unnamed** one makes the unnamed render „Liste 2" (ordinal counts named lists). Assert this explicitly; it is the easy thing to get wrong (counting only unnamed lists).
- **DSGVO explicit:** extend `NoPersistedPersonalDataTest` to the `shopping_list` table (no display-name/email column); assert no event/read row carries PII. **Synthetic data only** — fake household ids/list names (e.g. „Wocheneinkauf", „Getränke"), never real personal data.
- **Keep green:** all Epic-1 suites stay passing (backend **202**, Flutter **245** at Epic-1 close); update the shell test that currently asserts the `_ListsPlaceholder` copy (it becomes `ListsView`) and log it in the Change Log. A red build blocks merge (NFR6).

### References
- [Source: epics.md#Story 2.1: Create and name multiple open lists] (lines 471–489) — user story + the three ACs
- [Source: epics.md] FR4 (line 44), FR5 (line 46), FR13/AR10 (lines 62, 104); Epic 2 summary (lines 187–194)
- [Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md] AD-1 (64), AD-3 (76), AD-4 (82), AD-5 (88), AD-6 (94), AD-8 (106), AD-10 (118), AD-11 (124); Consistency Conventions (132–146); Capability→Architecture map "Lists…" (line 217)
- [Source: specs/spec-sgart/glossary.md] — ShoppingList, Open/In-Trip/Done, Member (binding names)
- [Source: ux-designs/ux-sgart-2026-08-20/EXPERIENCE.md; DESIGN.md] — UX-DR8 (list-detail, later), UX-DR11 (Listen overview = Story 2.2), UX-DR13 (empty states), UX-DR21 (row status label)
- [Source: implementation-artifacts/1-6-create-a-household-first-run-routing.md] — the aggregate/event/handler/projector/read-model/query/REST templates this story mirrors; the eager-boot-projector trap; read-your-writes
- [Source: implementation-artifacts/1-7-switch-select-rename-households.md] — the rename command/handler (`RenameHousehold`) online load-then-append template; the client rename cubit/dialog pattern
- [Source: implementation-artifacts/1-8-manage-stores-with-client-side-chain-matching.md] — `StoreController` nested-under-household + client-minted-id + `StoreReadModel`/`ListStores`/`JdbcStoreReadModel` templates
- [Source: backend/src/main/java/de/sgart/collaboration/**, de/sgart/shared/{StreamId,StoreId,HouseholdId,AggregateVersion,EventStore,EventSourcedAggregate,Command,DomainEvent,CommandId}.java] — the exact code to copy/extend
- [Source: backend/src/test/java/de/sgart/architecture/HexagonalArchitectureTest.java, de/sgart/identity/NoPersistedPersonalDataTest.java] — the ArchUnit + PII guards to keep green / extend
- [Source: app/lib/features/households/**, app/lib/features/stores/**, app/lib/shared/http/authenticated_http_client.dart, app/lib/shared/errors/error_message_resolver.dart] — the client feature, api, cubit, and error-mapping templates
- [Source: docker-compose.yml; .github/workflows/*.yml] — Postgres 18.6 / KurrentDB 25.1.4 / Keycloak 26.7; CI runs `./gradlew test` + `flutter analyze`/`flutter test`
- [Source: CLAUDE.md] — Clean Code, no-abbreviations naming, DDD/CQRS layering (§1–§4), DSGVO (§5), TDD/full-suite testing (§6), package structure (§8)
- [Source: memory `bmad-flow-state`, `backend-test-hygiene`, `flutter-test-local`, `git-workflow`, `language-policy`, `model-preferences`] — resume point (start Epic 2); run both suites for real; English keys/German values; direct-to-`main` pre-beta

## Clarifications (LOCKED by Timo 2026-08-26 — both confirmed via create-story)

1. **How to model list status & the Done-rename guard when In-Trip/Done are unreachable until Epic 3?** — ✅ **LOCKED: model status, Open-only reachable.** Introduce `ListStatus { OPEN, IN_TRIP, DONE }` (fixed glossary vocabulary) and code `rename`'s guard (permitted only when `OPEN`). Only `OPEN` is reachable in Epic 2 (`ShoppingListCreated` → `OPEN`); the `IN_TRIP`/`DONE` transitions and the end-to-end "Done cannot be renamed" coverage land in **Epic 3** with the events that produce those states. **Refinement noted during authoring:** the option text mentioned "a pure unit test proves the Done-guard by rehydrating a synthetic Done history" — that is only achievable once a status-changing event exists, which is Epic 3. Rather than fabricate an Epic-3 completion event now (which the Epic-1 retro's premature-value rule, action item #4, explicitly warns against), 2.1 ships the guard **code** and tests the reachable `OPEN` path; the synthetic-`DONE`-history guard test is deferred to Epic 3 and recorded in `deferred-work.md`. *Alternative (rejected):* defer `ListStatus` entirely and rename-always-allowed — leaves AC3's invariant unmodeled and forces a later retrofit into the aggregate's core.

2. **How much client UI should 2.1 ship?** — ✅ **LOCKED: minimal lists surface.** Replace the `_ListsPlaceholder` with a minimal view: the household's Open lists (derived „Liste N" / their names), a „+ Neue Liste" create action (optional name), and per-row rename, plus an empty state. The full **Listen overview — the segmented „Offen"/„Erledigt" filter, item counts/progress, and the Done archive — is Story 2.2** (UX-DR11). Items/move/autocomplete/store-assignment are Stories 2.3–2.6. *Alternative (rejected):* build the full overview now (merges 2.2, blurs the epic's story boundary) or ship backend + a bare create dialog only (leaves the shell with no way to see the lists just created).

### Authoring decisions the dev may treat as settled (not open questions)
- **Event names:** `ShoppingListCreated` / `ShoppingListRenamed` (mirrors `HouseholdCreated`/`HouseholdRenamed` and the aggregate name; the epic's „ListCreated" is shorthand).
- **REST shape:** nested under the household — `POST` / `GET` / `PATCH /{listId}` on `/api/v1/households/{householdId}/lists` (mirrors `StoreController` and the `PATCH` household-rename shape). Create returns `201` no body (client-minted `listId`); rename returns `204`.
- **Auto-naming derivation:** on the **client** (it owns the localization template and the ordered query result). The read model persists only the optional name + a stable creation order — never the derived number or label.
- **No process manager / no items / no delete** in 2.1 (Stories 2.3/2.4 and beyond).

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (dev-story)

### Debug Log References

- Full backend suite: `cd backend && ./gradlew test` — 257 tests, 0 failures, 0 errors (up from the Epic-1-close baseline of 202; includes `HexagonalArchitectureTest` and `NoPersistedPersonalDataTest`, both green).
- Full Flutter suite: `export PATH="$PATH:/home/timo/tools/flutter/bin"; cd app && flutter analyze && flutter test` — analyzer clean, 266 tests, 0 failures (up from 245).
- Real end-to-end stack run (retro action item, `docker compose up` + `SGART_FLYWAY_ENABLED=true`/`SGART_PROJECTOR_AUTOSTART=true`): created a household and two lists (named + unnamed) via REST against real KurrentDB/PostgreSQL/Keycloak, confirmed the projector auto-subscribed and filled `shopping_list_read_model` in creation order, renamed a list and saw the read model update, and exercised the `list.nameRequired` (400) and `shoppingList.notFound` (404) error paths for real. See Completion Notes for the dependency gap this run uncovered and fixed.

### Completion Notes List

- Implemented all four ACs: `ShoppingList` create/rename with `Open`-only multi-list coexistence (AC1), client-derived „Liste N" ordinal from the creation-ordered query array (AC2), the `OPEN`-only rename guard with a convergent no-op on same-name (AC3), and the full write/read path proven against real KurrentDB + PostgreSQL via Testcontainers plus a genuine local end-to-end run (AC4).
- Followed the `Household`/`Store` slice patterns precisely per the Dev Notes: `ShoppingList` mirrors `Household`'s aggregate shape (simpler — no member-role map); `ShoppingListReadModelProjector` is a **second, separate** `SmartLifecycle` projector filtering the `list-` stream prefix (never overloading `HouseholdReadModelProjector`); the REST/error-advice/`CommandFieldTranslations` seams mirror `StoreController`/`RenameHouseholdHandler` exactly, including the AdapterIn→Domain boundary (domain types never cross into `adapter.in`).
- **Clarification 1 honored as scoped:** `ListStatus { OPEN, IN_TRIP, DONE }` is modeled and `rename`'s guard is coded, but only the `OPEN` path is tested — no synthetic Epic-3 event was fabricated. Added a `deferred-work.md` entry pointing Epic 3 at the guard's end-to-end (`DONE`-rejects-rename) test once the trip-completion event exists.
- **Dependency-currency finding from the mandated real end-to-end run (CLAUDE.md §7):** Spring Boot 4 split Flyway's Spring integration into its own module, `org.springframework.boot:spring-boot-flyway`, no longer bundled with `spring-boot-starter-jdbc`. The backend had never had this dependency (since Story 1.6), so `SGART_FLYWAY_ENABLED=true` silently did nothing in every real deployment to date — CI/Testcontainers tests never caught it because they invoke `Flyway.configure()...migrate()` programmatically, bypassing Spring's autoconfiguration entirely. Added the missing dependency (`backend/build.gradle.kts`); the real run now shows all 5 migrations applying and the KurrentDB projector auto-subscribing and filling the read model live. This was a genuine, previously-undiscovered defect the Epic-1 retro's "one real end-to-end stack run" action item was designed to surface — first exercise of that action item, done as part of this story.
- Read-model ordering: `shopping_list_read_model.sequence_number` is a `BIGSERIAL` never rewritten on re-projection (idempotent upsert only touches `name`), giving a stable, monotonic creation order the AC2 ordinal derives from; the query returns the household's lists (not just Open) so 2.2's Offen/Erledigt split can reuse the same port unchanged.
- Flutter: `ShoppingListsCubit` uses the shared `CommandIntent` for both create (paired resource id) and rename, exactly as the Epic-1 retro's Action 1 prescribes — no hand-rolled id lifecycle. `HouseholdShell`'s `ShoppingListsCubit` provider is keyed on the active household id so a switch tears down and rebuilds it (the shell keeps tabs alive in an `IndexedStack`). Client-side guards added per the retro's DoD extension: rename disables submit on a blank name (`list.nameRequired`'s round-trip is never sent) and both create/rename fields set `maxLength: 120` matching `ShoppingListName.MAX_LENGTH`.
- All new interactive widgets (`lists-create-button`, `list-rename-button-*`, the create/rename sheet fields and submit buttons) carry stable `Key`s consistent with the rest of the codebase; the rename icon button carries a `tooltip` (a11y label) via `listsRenameAction`.
- No dead strings: removed the now-unused `shellTabListsPlaceholder` l10n key and the `_ListsPlaceholder` widget it belonged to, both replaced by the real `ListsView`.

### File List

**Backend — new**
- `backend/src/main/java/de/sgart/shared/ShoppingListId.java`
- `backend/src/main/java/de/sgart/collaboration/domain/ShoppingListName.java`
- `backend/src/main/java/de/sgart/collaboration/domain/ListStatus.java`
- `backend/src/main/java/de/sgart/collaboration/domain/ShoppingList.java`
- `backend/src/main/java/de/sgart/collaboration/domain/event/ShoppingListCreated.java`
- `backend/src/main/java/de/sgart/collaboration/domain/event/ShoppingListRenamed.java`
- `backend/src/main/java/de/sgart/collaboration/domain/exception/ListNameChangeNotPermittedException.java`
- `backend/src/main/java/de/sgart/collaboration/domain/readmodel/ShoppingListView.java`
- `backend/src/main/java/de/sgart/collaboration/domain/readmodel/ShoppingListReadModel.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/JdbcShoppingListReadModel.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/ShoppingListReadModelProjector.java`
- `backend/src/main/java/de/sgart/collaboration/application/query/ListOpenLists.java`
- `backend/src/main/java/de/sgart/collaboration/application/command/CreateShoppingList.java`
- `backend/src/main/java/de/sgart/collaboration/application/command/CreateShoppingListHandler.java`
- `backend/src/main/java/de/sgart/collaboration/application/command/RenameShoppingList.java`
- `backend/src/main/java/de/sgart/collaboration/application/command/RenameShoppingListHandler.java`
- `backend/src/main/java/de/sgart/collaboration/application/exception/InvalidShoppingListNameException.java`
- `backend/src/main/java/de/sgart/collaboration/application/exception/ListNameChangeNotPermittedApplicationException.java`
- `backend/src/main/java/de/sgart/collaboration/application/exception/ShoppingListNotFoundException.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/in/ShoppingListController.java`
- `backend/src/main/resources/db/migration/V5__shopping_list_read_model.sql`

**Backend — modified**
- `backend/src/main/java/de/sgart/shared/StreamId.java` (+ `forList(ShoppingListId)`)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/DomainEventJsonCodec.java` (+ `ShoppingListCreated`/`ShoppingListRenamed` wire mapping)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/HouseholdReadModelConfig.java` (+ `JdbcShoppingListReadModel`/`ShoppingListReadModelProjector` beans)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/HouseholdApplicationConfig.java` (+ create/rename handler + `ListOpenLists` beans)
- `backend/src/main/java/de/sgart/collaboration/application/CommandFieldTranslations.java` (+ `toShoppingListId`/`toShoppingListNameOrNull`/`toShoppingListName`)
- `backend/src/main/java/de/sgart/collaboration/adapter/in/WriteErrorAdvice.java` (+ list exception handlers)
- `backend/build.gradle.kts` (+ `org.springframework.boot:spring-boot-flyway` — see Completion Notes)

**Backend — tests (new)**
- `backend/src/test/java/de/sgart/collaboration/domain/ShoppingListNameTest.java`
- `backend/src/test/java/de/sgart/collaboration/domain/ShoppingListTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/out/ShoppingListReadModelProjectorTest.java`
- `backend/src/test/java/de/sgart/collaboration/application/ListOpenListsTest.java`
- `backend/src/test/java/de/sgart/collaboration/application/CreateShoppingListHandlerTest.java`
- `backend/src/test/java/de/sgart/collaboration/application/RenameShoppingListHandlerTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/in/ShoppingListControllerTest.java`

**Backend — tests (modified)**
- `backend/src/test/java/de/sgart/shared/StreamIdTest.java` (+ `forList` cases)
- `backend/src/test/java/de/sgart/collaboration/adapter/out/DomainEventJsonCodecTest.java` (+ list event round-trips)

**Flutter — new**
- `app/lib/features/lists/data/shopping_list_summary.dart`
- `app/lib/features/lists/data/shopping_lists_api.dart`
- `app/lib/features/lists/presentation/shopping_lists_state.dart`
- `app/lib/features/lists/presentation/shopping_lists_cubit.dart`
- `app/lib/features/lists/presentation/create_list_dialog.dart`
- `app/lib/features/lists/presentation/rename_list_dialog.dart`
- `app/lib/features/lists/presentation/lists_view.dart`

**Flutter — modified**
- `app/lib/features/households/presentation/household_shell.dart` (`_ListsPlaceholder` → household-scoped `ListsView`)
- `app/lib/features/households/presentation/first_run_router.dart` (+ `ShoppingListsApi` provided alongside `StoresApi`)
- `app/lib/l10n/app_de.arb` (+ `lists*` copy/errors; removed the now-dead `shellTabListsPlaceholder`)
- `app/lib/l10n/gen/app_localizations.dart`, `app/lib/l10n/gen/app_localizations_de.dart` (generated via `flutter gen-l10n`)
- `app/lib/shared/errors/error_message_resolver.dart` (+ `list.*`/`shoppingList.notFound` mappings)

**Flutter — tests (new)**
- `app/test/support/fake_shopping_lists_dependencies.dart`
- `app/test/features/lists/presentation/shopping_lists_cubit_test.dart`
- `app/test/features/lists/presentation/lists_view_test.dart`

**Flutter — tests (modified)**
- `app/test/features/households/presentation/household_shell_test.dart` (+ `ShoppingListsApi` provider; placeholder-copy assertion replaced with real `ListsView` assertion)
- `app/test/features/households/presentation/first_run_router_test.dart` (+ `ShoppingListsApi` provider)

**Process artifacts**
- `_bmad-output/implementation-artifacts/deferred-work.md` (+ Epic-3 `DONE`-guard test pointer)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status transitions)

## Change Log

| Date | Version | Description | Author |
|------|---------|-------------|--------|
| 2026-08-26 | 0.1 | Initial story draft — create-story context engine (Opus 4.8) | Timo |
| 2026-08-26 | 1.0 | Implemented Tasks 1–7: `ShoppingList` aggregate, second projector/read model, `ListOpenLists` query, create/rename commands + REST, minimal Flutter lists surface. Backend 202→257 tests, Flutter 245→266 tests, both suites green. Real end-to-end stack run performed (retro action item); uncovered and fixed a missing `spring-boot-flyway` dependency (Boot 4 modularization) that had silently disabled Flyway migration in every real deployment since Story 1.6. Status → review. | Claude Sonnet 5 (dev-story) |

## Review Findings

*Update 2026-08-26 (code-review, Opus 4.8): all 4 patch findings applied and both suites re-run green — backend 257→260, Flutter 266. 3 defers recorded in `deferred-work.md`. Status → done.*

Adversarial code review 2026-08-26 (Blind Hunter + Edge Case Hunter + Acceptance Auditor, Opus 4.8). Auditor verdict: spec-faithful — all four ACs met, both LOCKED clarifications honored. 4 patch, 3 defer, 6 dismissed.

### Patch (fixable without decision)

- [x] [Review][Patch] Read-model re-projection reverts an applied rename — `insertList` uses `ON CONFLICT (list_id) DO UPDATE SET name = EXCLUDED.name`, so a `fromStart` replay re-applies `ShoppingListCreated` and resets `name` to the creation-time value (a live GET mid-replay reads stale; a projector interrupted between Created and Renamed stays stale). The doc comment claims a "safe no-op" — it is not. Fix: `DO NOTHING` (rename owns all name updates; matches the aggregate semantics and the sibling `MemberJoined` upsert). [backend/src/main/java/de/sgart/collaboration/adapter/out/JdbcShoppingListReadModel.java:56]
- [x] [Review][Patch] Per-household read-model isolation is unproven at both layers — `JdbcShoppingListReadModel.listsOf` filters by `household_id` correctly but no test seeds two households and asserts `listsOf(h1)` excludes h2; and the controller test double `InMemoryShoppingListReadModel.listsOf` ignores its `householdId` argument, so the GET slice test would pass even on a leak. Add a two-household projector Testcontainers assertion and make the double honor the argument. [backend/src/test/java/de/sgart/collaboration/adapter/in/ShoppingListControllerTest.java:92]
- [x] [Review][Patch] Client sheets pop on failure (discarding typed input) and don't gate submit on `isSubmitting` — `_CreateListSheetBodyState._submit` (and the rename sheet) do `await createList(...); navigator.pop();` unconditionally; `createList` swallows errors into `actionError`, so a rejection closes the sheet and drops what the user typed, and a double-tap before the pop appends the same `listId` twice. Fix: pop only on success and gate the submit button on `state.isSubmitting`. [app/lib/features/lists/presentation/create_list_dialog.dart:37]
- [x] [Review][Patch] `shoppingList.notFound` breaks the `list.*` error-code family — every sibling code in the slice is `list.nameRequired`/`list.nameTooLong`/`list.nameChangeNotPermitted`; the not-found code alone uses a `shoppingList.` prefix (§2 ubiquitous language), forcing the Flutter resolver to special-case it. Rename to `list.notFound` across the exception, the controller test, and `error_message_resolver.dart`. [backend/src/main/java/de/sgart/collaboration/application/exception/ShoppingListNotFoundException.java]

### Defer (recorded, not actionable in 2.1)

- [x] [Review][Defer] `ListOpenLists` OPEN-only filter will under-count the "Liste N" ordinal in Epic 3 — the query's Javadoc/migration comment say the ordinal counts Open **and later In-Trip** lists, but `forHousehold` filters to `OPEN` before the client derives the ordinal. Consistent today (only OPEN is reachable); when Epic 3 makes `IN_TRIP` reachable, the ordinal source must include it. — deferred, forward-looking [backend/src/main/java/de/sgart/collaboration/application/query/ListOpenLists.java]
- [x] [Review][Defer] Projector partial-failure robustness (log-and-skip + BIGSERIAL ordering) — a `log-and-skip`ped `ShoppingListCreated` leaves the following `ShoppingListRenamed` UPDATE hitting 0 rows (rename lost until the next `fromStart` replay), and because `sequence_number` is BIGSERIAL-at-insert, a skipped-then-later create can invert creation order. Both recover on the next replay and need a narrow transient per-event failure to trigger. — deferred, pre-existing design (recovers on replay) [backend/src/main/java/de/sgart/collaboration/adapter/out/ShoppingListReadModelProjector.java:127]
- [x] [Review][Defer] `Household*Config` classes now wire the `ShoppingList` slice — `HouseholdApplicationConfig`/`HouseholdReadModelConfig` define the shopping-list handlers/query/projector beans; the names no longer describe their contents (§8). Spec-directed reuse; a rename to collaboration-context config is a later Boy-Scout cleanup touching the household slice too. — deferred, spec-directed [backend/src/main/java/de/sgart/collaboration/adapter/out/HouseholdApplicationConfig.java]
