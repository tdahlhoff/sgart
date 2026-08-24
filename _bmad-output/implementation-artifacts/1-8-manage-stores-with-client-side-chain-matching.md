---
baseline_commit: e3838120e1b8fefc3f22a895c89fda44d26f35f0
---

# Story 1.8: Manage stores with client-side chain matching

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a member,
I want to add and remove the household's stores by free-form name with an advisory chain suggestion,
so that lists and trips can later be grouped by store.

## Acceptance Criteria

**AC1 — Add a store, unique per household**
**Given** the household's store list
**When** a member adds a store by free-form name
**Then** the name must be unique within the household among **active (non-archived)** stores (duplicate rejected with a client-localizable `400`), and `Store` is created as an **entity inside the `Household` aggregate** — only the `Household` root accepts the command (AR9 / AD-10).

**AC2 — Advisory client-side chain suggestion, offline after first load**
**Given** a member typing a store name
**When** the typed text matches the **cached `StoreChain` reference list**
**Then** a chain is suggested **inline** and can be **accepted / changed / cleared** — never forced, and **never decided server-side** — and the reference list **works offline after its first cached load** (FR3). The accepted chain (if any) is stored on the `Store`; clearing leaves it unlinked.

**AC3 — Remove archives, never deletes history**
**Given** a store that may be referenced by past trips/assignments
**When** a member removes it
**Then** it is **archived** (hidden from all future selection) **without deleting** the store or any historical trip/assignment that referenced it (FR3). Archiving is a soft state change, not a row delete.

**AC4 — Creation rules are reusable, not screen-bound** *(design constraint now; runtime verification deferred)*
**Given** any store picker (item assignment, trip start, in-trip reroute)
**When** a member adds a store inline from it
**Then** it uses **these same creation rules** (unique active name + advisory chain suggestion) and is added to the household — store creation is **not** limited to „Haushalt verwalten".
> The item-assignment / trip-start / reroute pickers do not exist until Epics 2–3. For Story 1.8 this AC is a **design constraint**: the add-store command, API, and cubit method must be built as a reusable unit (not embedded in the manage screen) so Stories 2.6 / 3.1 / 3.2 can mount the same creation path inline. Inline-from-picker is **verified** in those stories, not here (see Deferred/Scope).

**AC5 — Archived store falls back to „Noch nicht zugeordnet"** *(structural guarantee now; runtime verification deferred)*
**Given** an item on an Open list assigned to a store that is later **archived** *(the E6 case in the epic)*
**When** the list or a trip is viewed
**Then** the item **falls back to „Noch nicht zugeordnet"** (the archived store is no longer offered), so nothing points at a hidden store; historical **Done** trips keep their record.
> Items, lists, and trips do not exist until Epics 2–3. Story 1.8's structural guarantee is that **`ListStores` returns only active (non-archived) stores**, so every current and future picker/grouping naturally excludes archived stores — an item pointing at an archived store therefore has no active target and surfaces as unassigned. The item-fallback behaviour itself is **verified** in Epics 2–3, not here.

## Tasks / Subtasks

### Backend — Collaboration context: `Store` as an entity in the `Household` aggregate (AC1, AC3)

- [x] **Task 1 — `StoreName` value object + `StoreId`** (AC1)
  - [x] Add `StoreId` to `de.sgart.shared` mirroring `HouseholdId` (UUID-backed, `generate()`/`fromString()`; opaque, no domain meaning — AD-5/§2). It is cross-context (Collaboration owns the entity; pickers in later epics reference stores by id), so `shared` is the right home, exactly as `HouseholdId` is.
  - [x] Add `StoreName` to `de.sgart.collaboration.domain` mirroring `HouseholdName`: trimmed, non-blank, bounded (`MAX_LENGTH` — reuse 120 to match `household_read_model.name`), throws plain `IllegalArgumentException` for its own invariant (fail fast; no infra type in the domain — AD-1). Not personal data — it names a shop, not a person (copy the `HouseholdName` javadoc rationale).
  - [x] Represent the accepted chain as an **optional opaque reference** on the store. Recommended: a nullable `StoreChainId` (UUID-backed `Identifier`-style id from the reference list) carried on `StoreAdded`. **Do not** validate it against `storereference` (advisory, client-decided — AC2; and AD-2 forbids Collaboration reaching into `storereference.domain`). See Dev Notes → "Chain link representation" for the decision and the rejected alternatives.

- [x] **Task 2 — Domain events `StoreAdded` / `StoreArchived`** (AC1, AC3)
  - [x] `StoreAdded(EventId, HouseholdId, StoreId, StoreName, /* nullable */ StoreChainId)` and `StoreArchived(EventId, HouseholdId, StoreId)` — past tense, PascalCase, immutable records implementing `DomainEvent`, in `collaboration.domain` (mirror `HouseholdRenamed`). Both live on the **household stream** (`household-{id}`) since `Store` is an entity of `Household` (AD-10) — no new `StreamId.StreamType`.

- [x] **Task 3 — `Household.addStore(...)` and `Household.archiveStore(...)`** (AC1, AC3)
  - [x] Hold store state inside the aggregate: e.g. `Map<StoreId, StoreState>` where `StoreState` tracks `name`, optional `chainId`, and `archived`. Fold `StoreAdded`/`StoreArchived` in `apply(...)` (extend the existing `switch`).
  - [x] `addStore(MemberId requestedBy, StoreId storeId, StoreName name, StoreChainId chainOrNull, CommandId commandId)`: reject a name that **duplicates an active store's name** (case-insensitive, trimmed comparison — decide & document; see Dev Notes) by throwing a domain exception (`DuplicateStoreNameException`); otherwise `raise(new StoreAdded(...))`. Follow `Household.rename`'s `Objects.requireNonNull` + no-direct-mutation contract.
  - [x] `archiveStore(MemberId requestedBy, StoreId storeId, CommandId commandId)`: archiving an **already-archived or unknown** store raises nothing (convergent no-op — AD-8), mirroring `rename`'s no-op branch; archiving an active store raises `StoreArchived`.
  - [x] **Membership, not role**, gates these (any Member — AC1/CAP-3 says "Any Member"), unlike Admin-only rename. Enforce that `requestedBy` is a known member (present in `rolesByMember`); creation/archival is **not** Admin-restricted. Confirm against the epic ("Any Member can add/remove" — line 42) — do **not** copy rename's Admin check.

- [x] **Task 4 — Commands + handlers** (AC1, AC3, AC4)
  - [x] `AddStore` command record (`HouseholdId, StoreId, StoreName, /*nullable*/ StoreChainId, CommandId, AggregateVersion basedOnVersion`) and `ArchiveStore` command record — mirror `RenameHousehold` (envelope `Objects.requireNonNull`; `basedOnVersion` is the **loaded** stream version, online load-then-append — client-supplied `basedOnVersion` + offline queue is Epic 5).
  - [x] `AddStoreHandler` / `ArchiveStoreHandler` mirroring `RenameHouseholdHandler`: resolve caller `MemberId` via `ResolveMemberIdentity` (published port — AD-2), `Household.rehydrate`, invoke the aggregate method, append `uncommittedEvents()` under the loaded version only when non-empty. Return `void` (command → no domain data; the client generated the `StoreId` and knows the name — CQRS). Generate `StoreId` **client-side** and pass it in the envelope so the response needs no body (read-your-writes without a projection wait — same rationale as `HouseholdId` in create).
  - [x] Extend `CommandFieldTranslations` with `toStoreName` (`store.nameRequired` / `store.nameTooLong`) and `toStoreId`; add `InvalidStoreNameException` mirroring `InvalidHouseholdNameException` (carries the `ErrorDescriptor` code). Add `DuplicateStoreNameException` (domain) → application-level exception mapping to `store.duplicateName` (`409` Conflict — a uniqueness collision is a conflict, not a malformed request; confirm status choice in Dev Notes).
  - [x] **AC4 reusability:** keep `AddStoreHandler` free of any manage-screen assumption — it is the single creation path every later inline picker reuses.

- [x] **Task 5 — REST endpoints** (AC1, AC3)
  - [x] Add a `StoreController` under `/api/v1/households/{householdId}/stores` (stores are nested under their household — the aggregate they belong to): `POST` (add; `201`/`204`), `DELETE /{storeId}` or `POST /{storeId}/archive` (archive → `204`; prefer `DELETE` semantically, but note it **archives**, never deletes — document in the controller javadoc), and `GET` (list active stores — Task 7). Caller identity from JWT `sub` via `AuthenticatedCaller` only (AR10/AD-5) — never from body/path. Mirror `HouseholdController`'s DTO-record style and javadoc density.
  - [x] Extend `WriteErrorAdvice` (or add a store-specific `@RestControllerAdvice`) to map `InvalidStoreNameException` → `400`, `DuplicateStoreNameException`(app) → `409`, reuse existing `InvalidCommandEnvelopeException`/`ConcurrencyConflictException`/`NotAMemberException` handlers.

- [x] **Task 6 — Read model + projector** (AC1, AC3, AC5-structural)
  - [x] Flyway migration `V4__store_read_model.sql` (V3 belongs to storereference — Task 8; confirm numbering against `db/migration/`): `store_read_model(household_id UUID, store_id UUID, name VARCHAR(120), chain_id UUID NULL, archived BOOLEAN NOT NULL DEFAULT false, PRIMARY KEY (household_id, store_id))`. Header comment: household-scoped, **no personal data** (a shop name, not a person's — mirror V2's comment); covered by household-erasure read-model scrub (AD-7).
  - [x] `JdbcStoreReadModel` + a domain-owned `StoreReadModel` port (mirror `JdbcHouseholdReadModel` / `HouseholdNameReadModel`): `upsertStore(...)` (idempotent `ON CONFLICT`), `markArchived(...)`, and a query method returning **active stores only** (`WHERE archived = false`) for a household.
  - [x] Extend the projector fold to `StoreAdded` (upsert) / `StoreArchived` (set `archived = true`). **Decision:** extend `HouseholdReadModelProjector`'s subscription (it already subscribes to the `household-` stream prefix, which now carries store events) and add the two `case`s to `project(...)` — do **not** create a second subscription over the same prefix. Re-projection stays idempotent (upserts). Update the projector/config javadoc accordingly.
  - [x] Extend `DomainEventJsonCodec` with stable type tags `"StoreAdded"` / `"StoreArchived"` and payload records (round-trip the nullable `chainId`) — the codec is the only place that knows JSON (AD-1).

- [x] **Task 7 — `ListStores` query** (AC5-structural, AC4)
  - [x] `ListStores` application query (mirror `ListMyHouseholds`): resolve caller `MemberId` (or at minimum confirm membership) for the household via the Identity ACL, then return **active stores** (id + name + optional chainId) from the `StoreReadModel` port. Pure, side-effect-free (CQRS). This is the single active-store source the manage screen and every future picker read → the AC5 structural guarantee.
  - [x] Wire all new beans in `HouseholdApplicationConfig` / `HouseholdReadModelConfig` (constructor-inject `EventStore`, `ResolveMemberIdentity`, the read-model port).

### Backend — Store Reference context: seeded `StoreChain` reference list (AC2)

- [x] **Task 8 — Reference data + read endpoint** (AC2)
  - [x] Flyway migration `V3__store_chain_reference.sql`: `store_chain_reference(chain_id UUID PRIMARY KEY, name VARCHAR(120) NOT NULL)` **seeded** with the MVP single-market (German) chains — Edeka, Rewe, Aldi, Lidl, Netto, Penny, Kaufland, dm, Rossmann, Norma, Globus, tegut, Müller (extend as sensible; **no personal data**). Use fixed literal UUIDs in the seed so ids are stable across environments (a store's stored `chain_id` must resolve). Multi-market/additional data is explicitly out of scope (epic line 147).
  - [x] `storereference/adapter/out`: `JdbcStoreChainReferenceReadModel` (read-only) + a `storereference.domain` port. `storereference/application`: `ListStoreChains` query returning `[{chainId, name}]`.
  - [x] `storereference/adapter/in`: `StoreChainController` — `GET /api/v1/store-chains`, authenticated, **household-less** (reference data is global, like `IdentityController` is household-less), returning the full list (no pagination — MVP convention). This is the payload the client caches for offline client-side matching.
  - [x] Config class to wire the query + read model (mirror `HouseholdReadModelConfig`; no I/O at bean construction).

### Frontend — Flutter `stores` feature (AC1, AC2, AC3, AC4)

- [x] **Task 9 — Data layer** (AC1, AC2, AC3)
  - [x] `features/stores/data/store_summary.dart` — `{storeId, name, chainId?}`, `fromJson` failing fast to a mapped `AppException` (mirror `HouseholdSummary`).
  - [x] `features/stores/data/store_chain.dart` — `{chainId, name}` reference entry + `fromJson`.
  - [x] `features/stores/data/stores_api.dart` — `StoresApi` interface + `HttpStoresApi`: `listStores(householdId)`, `addStore(householdId, name, {chainId, commandId})`, `archiveStore(householdId, storeId, {commandId})`, `listStoreChains()`. Reuse `AuthenticatedHttpClient` verbs; `commandId` is the caller-supplied idempotency key reused across retries of the same intent (mirror `renameHousehold`'s doc + the `RenameHouseholdCubit` command-id-per-intent rule).
  - [x] `features/stores/data/store_chain_reference_cache.dart` — an interface + `SharedPreferences`-backed impl that persists the fetched reference list as JSON (mirror `ActiveHouseholdStore`). Behaviour: fetch-once from `listStoreChains()`, cache; subsequent loads / offline use the cache (**AC2 offline-after-first-load**). Keep it a simple cached-JSON store — the durable offline **queue** (Hive/SQLite) is Epic 5; do not build it here (YAGNI).

- [x] **Task 10 — Client-side chain matcher** (AC2)
  - [x] `features/stores/domain/store_chain_matcher.dart` — pure function: given the typed store name + the cached reference list, return the best advisory `StoreChain?` suggestion. Keep matching simple and deterministic: case-insensitive; suggest a chain when the typed name **starts with or contains** the chain name (e.g. „Aldi Süd" → „Aldi", „Edeka Schiedemann" → „Edeka"). No network, no server call — matching is 100% client-side (FR3, AC2). Unit-test the matching cases directly.

- [x] **Task 11 — Presentation** (AC1, AC2, AC3, AC4)
  - [x] `StoresCubit` + `StoresState` (mirror `HouseholdsCubit`/`RenameHouseholdCubit` patterns: depend only on interfaces, guard every `emit` with `isClosed`, map errors via `AppException`→`AppError`). Responsibilities: load active stores + reference list (from cache/api), add a store (with optional accepted chainId), archive a store, expose the live chain suggestion for the current input. Command-id-per-intent for add (regenerate when the name changes; reuse on same-name retry — copy `RenameHouseholdCubit._commandIdFor`).
  - [x] Manage-stores screen `features/stores/presentation/manage_stores_page.dart` matching `screen-household-manage.html` frame 3 („Geschäfte"): a list of active stores each showing name + chain badge (verdigris `t-chain` tint — DESIGN §4b row status label; reuse `StatusLabel` widget), a „Geschäft hinzufügen" free-form field that shows the advisory chain suggestion inline (bestätigen/ändern/löschen) then „Hinzufügen", and per-row remove that **archives** with the helper copy „Entfernen archiviert ein Geschäft — es verschwindet aus der Auswahl, vergangene Einkäufe bleiben erhalten." Reuse `SgartButton`, `SgartShapes`, theme tokens — no ad-hoc styling.
  - [x] **Navigation:** add a „Geschäfte" (store management) entry reachable from the switcher sheet. Recommended: a thin „Haushalt verwalten" screen hosting a „Geschäfte" row that opens `ManageStoresPage`, since Epic 4 grows the same hub with members/invites/roles (EXPERIENCE §3). Re-provide `StoresApi`/`StoresCubit` over pushed routes exactly as `_pushOverProviders` does (the Story 1.6 `ProviderNotFoundException` lesson). **Boy-Scout:** the `HouseholdSwitcherSheet` javadoc currently says the „Haushalt verwalten" hub "(members/invites/roles/**stores**) is Epic 4" — correct it: stores are Story 1.8; only members/invites/roles remain Epic 4.
  - [x] Provide `StoresApi` + the reference cache in the widget tree where `HouseholdsApi` is provided (`FirstRunRouter`/shell), so the manage screen and future pickers can `context.read` them.

- [x] **Task 12 — Localization + error copy** (AC1, AC2, AC3)
  - [x] Add German keys to `app_de.arb` (with `@`-descriptions) for: Geschäfte heading, add-store field label + submit, chain-suggestion accept/change/clear, archive confirmation + helper copy, empty-state, and the three error strings. Follow the existing `households*` key naming (`stores*`). Regenerate `app_localizations*.dart` (`flutter gen-l10n`).
  - [x] Extend `error_message_resolver.dart` with `store.nameRequired` / `store.nameTooLong` / `store.duplicateName` → their localized strings. (Note: `concurrency.staleVersion` remains unmapped/unreachable pre-Epic-4/5 — do **not** add it here; see deferred-work.md.)

### Testing (TDD — write the failing test first; CLAUDE.md §6)

- [x] **Task 13 — Backend unit tests (domain-first, no infrastructure)**
  - [x] `StoreNameTest` (blank/whitespace rejected, over-length rejected, trimmed) — mirror `HouseholdNameTest`.
  - [x] `HouseholdTest` additions: `addStore` raises `StoreAdded` (with/without chain); duplicate **active** name rejected; **re-adding a name after its store was archived is allowed**; `archiveStore` raises `StoreArchived`; archiving an already-archived/unknown store is a silent no-op; a non-member is rejected; add/archive are **not** Admin-gated (a Participant/any member succeeds). Assert on emitted events, not internal fields (behaviour, not implementation).
  - [x] `AddStoreHandlerTest` / `ArchiveStoreHandlerTest` (mirror `RenameHouseholdHandlerTest`, using `InMemoryEventStore`): envelope validation, member resolution (403 for non-member), duplicate → conflict mapping, no-op archive skips the append, concurrency conflict propagation.
  - [x] `ListStoresTest`: returns active stores only; excludes archived (the AC5 structural guarantee); side-effect-free.
  - [x] `DomainEventJsonCodecTest` additions: `StoreAdded`/`StoreArchived` round-trip incl. **null** and non-null `chainId`, stable type tags.
  - [x] Projector test additions: folding `StoreAdded` upserts, `StoreArchived` flips the flag; re-projection idempotent.
  - [x] Store Reference: `ListStoreChains` query test + `StoreChainController` test (returns the seeded list).
  - [x] `HexagonalArchitectureTest` stays green (Collaboration must not import `storereference.domain`; domains stay pure) — no new rule needed, but verify the boundary holds.
- [x] **Task 14 — Frontend tests**
  - [x] `StoreChainMatcher` unit tests (match/no-match/case-insensitive/prefix vs. contains).
  - [x] Reference-cache tests: fetches once then serves from cache; serves cache when the api throws (offline-after-first-load, AC2).
  - [x] `StoresCubit` tests (mirror `households_cubit_test`/`rename_household_cubit_test`): load, add with/without chain, duplicate → error state (no shell teardown), archive removes from active list, command-id reuse-vs-regenerate on retry, `isClosed`-guarded emits.
  - [x] `ManageStoresPage` widget test (mirror `rename_household_page_test`): renders active stores + chain badges, add flow shows suggestion + adds, archive shows the helper copy and archives; uses the widget-test harness + a fake `StoresApi`.
  - [x] `error_message_resolver_test` additions for the three new codes + fallback unchanged.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Opus 4.8)

### Debug Log References

- Backend full suite (unit + Testcontainers PostgreSQL/KurrentDB): `./gradlew test` → all green.
- Frontend full suite: `flutter test` → 173 tests green; `flutter analyze` → no issues.

### Completion Notes List

Implemented Story 1.8 end-to-end (backend Collaboration store entity + Store Reference context; Flutter `stores` feature), following the established Story 1.6/1.7 patterns.

**Open decisions — chosen defaults (all the recommended options):**
1. **Uniqueness scope & comparison:** unique among **active (non-archived)** stores, **case-insensitive + trimmed** (`Household.hasActiveStoreNamed`). Makes "re-add after archive" work; verified by a domain test.
2. **Archive HTTP verb:** `DELETE /{storeId}` → `204`, javadoc'd as "archives, never deletes". Command envelope (`commandId`) carried in the DELETE body; added `AuthenticatedHttpClient.deleteJson` (Boy Scout).
3. **Duplicate-name status:** `409 Conflict` with `store.duplicateName` (a uniqueness collision is a conflict). `InvalidStoreNameException` → `400`; envelope errors → `400`.
4. **Navigation host:** a thin „Haushalt verwalten" hub (`ManageHouseholdPage`) with a „Geschäfte" row opening `ManageStoresPage` — Epic 4 grows the same hub. Corrected the `HouseholdSwitcherSheet` javadoc: stores are Story 1.8; only members/invites/roles remain Epic 4.

**Key design points:**
- Chain link is a nullable opaque `StoreChainId` in `de.sgart.shared` (never a `storereference.domain` type), so `collaboration.domain` never imports `storereference.domain` (AD-2, still green in `HexagonalArchitectureTest`). Server never validates `chainId` (advisory / client-decided, AC2). Reference table seeded with **fixed literal UUIDs** so a stored `chainId` stays resolvable.
- `StoreId` minted **client-side** and carried in the `AddStore` envelope, so the `POST` response needs no body (read-your-writes). The cubit optimistically appends the added store.
- `Store` events (`StoreAdded`/`StoreArchived`) live on the **household stream** (AD-10). The projector's existing `household-` subscription already carries them — extended `HouseholdReadModelProjector.project(...)` and `DomainEventJsonCodec` rather than adding a second subscription. `chainId` round-trips a JSON `null` for unlinked stores.
- `ListStores` returns **active stores only** — the AC5 structural guarantee (verified by test); membership confirmed via `ResolveMemberIdentity` (403 for non-members).
- Client-side `StoreChainMatcher` (pure, offline): case-insensitive, starts-with beats whole-word-contains, longest name wins; word-boundary contains avoids spurious substring matches. Reference cache is **fetch-once then serve-from-cache** (offline-after-first-load, AC2); durable offline command queue is Epic 5 (not built — YAGNI).
- Command-id-per-intent for add (reuse on same-name retry, regenerate on name change) copied from `RenameHouseholdCubit`.

**Deferred (per Dev Notes, not built here):** `concurrency.staleVersion` client mapping (unreachable pre-Epic-4/5); offline queue/conflict UI (Epic 5); members/invites/roles hub (Epic 4); AC4 inline-from-picker and AC5 item-fallback runtime verification (Epics 2–3).

### File List

**Backend — new (main):**
- `backend/src/main/java/de/sgart/shared/StoreId.java`
- `backend/src/main/java/de/sgart/shared/StoreChainId.java`
- `backend/src/main/java/de/sgart/collaboration/domain/StoreName.java`
- `backend/src/main/java/de/sgart/collaboration/domain/StoreAdded.java`
- `backend/src/main/java/de/sgart/collaboration/domain/StoreArchived.java`
- `backend/src/main/java/de/sgart/collaboration/domain/DuplicateStoreNameException.java`
- `backend/src/main/java/de/sgart/collaboration/domain/NotAHouseholdMemberException.java`
- `backend/src/main/java/de/sgart/collaboration/domain/StoreView.java`
- `backend/src/main/java/de/sgart/collaboration/domain/StoreReadModel.java`
- `backend/src/main/java/de/sgart/collaboration/application/AddStore.java`
- `backend/src/main/java/de/sgart/collaboration/application/ArchiveStore.java`
- `backend/src/main/java/de/sgart/collaboration/application/AddStoreHandler.java`
- `backend/src/main/java/de/sgart/collaboration/application/ArchiveStoreHandler.java`
- `backend/src/main/java/de/sgart/collaboration/application/ListStores.java`
- `backend/src/main/java/de/sgart/collaboration/application/InvalidStoreNameException.java`
- `backend/src/main/java/de/sgart/collaboration/application/DuplicateStoreNameApplicationException.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/in/StoreController.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/JdbcStoreReadModel.java`
- `backend/src/main/java/de/sgart/storereference/domain/StoreChainReference.java`
- `backend/src/main/java/de/sgart/storereference/domain/StoreChainReferenceReadModel.java`
- `backend/src/main/java/de/sgart/storereference/application/ListStoreChains.java`
- `backend/src/main/java/de/sgart/storereference/adapter/out/JdbcStoreChainReferenceReadModel.java`
- `backend/src/main/java/de/sgart/storereference/adapter/out/StoreReferenceConfig.java`
- `backend/src/main/java/de/sgart/storereference/adapter/in/StoreChainController.java`
- `backend/src/main/resources/db/migration/V3__store_chain_reference.sql`
- `backend/src/main/resources/db/migration/V4__store_read_model.sql`

**Backend — modified (main):**
- `backend/src/main/java/de/sgart/collaboration/domain/Household.java` (store state + `addStore`/`archiveStore` + `apply` cases)
- `backend/src/main/java/de/sgart/collaboration/application/CommandFieldTranslations.java` (`toStoreName`/`toStoreId`/`toStoreChainIdOrNull`)
- `backend/src/main/java/de/sgart/collaboration/adapter/in/WriteErrorAdvice.java` (store error mappings)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/DomainEventJsonCodec.java` (store event tags/payloads)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/HouseholdReadModelProjector.java` (fold store events)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/HouseholdReadModelConfig.java` (`JdbcStoreReadModel` bean + projector wiring)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/HouseholdApplicationConfig.java` (add/archive handlers + `ListStores` beans)

**Backend — new (test):**
- `backend/src/test/java/de/sgart/collaboration/domain/StoreNameTest.java`
- `backend/src/test/java/de/sgart/collaboration/application/AddStoreHandlerTest.java`
- `backend/src/test/java/de/sgart/collaboration/application/ArchiveStoreHandlerTest.java`
- `backend/src/test/java/de/sgart/collaboration/application/ListStoresTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/in/StoreControllerTest.java`
- `backend/src/test/java/de/sgart/storereference/application/ListStoreChainsTest.java`
- `backend/src/test/java/de/sgart/storereference/adapter/in/StoreChainControllerTest.java`

**Backend — modified (test):**
- `backend/src/test/java/de/sgart/collaboration/domain/HouseholdTest.java` (store additions)
- `backend/src/test/java/de/sgart/collaboration/adapter/out/DomainEventJsonCodecTest.java` (store round-trips)
- `backend/src/test/java/de/sgart/collaboration/adapter/out/HouseholdReadModelProjectorTest.java` (store projection + new ctor)
- `backend/src/test/java/de/sgart/collaboration/adapter/out/HouseholdReadModelSubscriptionTest.java` (new ctor + TRUNCATE)

**Frontend — new (lib):**
- `app/lib/features/stores/data/store_summary.dart`
- `app/lib/features/stores/data/store_chain.dart`
- `app/lib/features/stores/data/stores_api.dart`
- `app/lib/features/stores/data/store_chain_reference_cache.dart`
- `app/lib/features/stores/domain/store_chain_matcher.dart`
- `app/lib/features/stores/presentation/stores_cubit.dart`
- `app/lib/features/stores/presentation/stores_state.dart`
- `app/lib/features/stores/presentation/manage_stores_page.dart`
- `app/lib/features/households/presentation/manage_household_page.dart`

**Frontend — modified (lib):**
- `app/lib/shared/http/authenticated_http_client.dart` (`deleteJson`)
- `app/lib/shared/errors/error_message_resolver.dart` (three store codes)
- `app/lib/l10n/app_de.arb` (+ regenerated `lib/l10n/gen/app_localizations*.dart`)
- `app/lib/features/households/presentation/first_run_router.dart` (provide `StoresApi` + reference cache)
- `app/lib/features/households/presentation/household_switcher_sheet.dart` („Haushalt verwalten" entry + javadoc correction)

**Frontend — new (test):**
- `app/test/support/fake_stores_dependencies.dart`
- `app/test/features/stores/domain/store_chain_matcher_test.dart`
- `app/test/features/stores/data/store_chain_reference_cache_test.dart`
- `app/test/features/stores/presentation/stores_cubit_test.dart`
- `app/test/features/stores/presentation/manage_stores_page_test.dart`

**Frontend — modified (test):**
- `app/test/shared/errors/error_message_resolver_test.dart` (three store codes)

### Change Log

- 2026-08-24: Implemented Story 1.8 (manage stores with client-side chain matching) — backend Collaboration store entity (domain/app/adapter + read model + projector), Store Reference seeded chain list + endpoint, Flutter `stores` feature (data/domain/presentation) with the „Haushalt verwalten → Geschäfte" hub, German copy + error codes, and full test coverage. Status → review.

## Dev Notes

Story 1.8 is the first story that adds an **entity inside an existing aggregate** (`Store` in `Household`, AD-10) and the first to touch the **Store Reference** supporting context. Everything below keeps you on the rails the previous seven stories laid — reuse them; do not reinvent.

### The exact patterns to copy (reuse, don't reinvent)

- **Aggregate + events + no-direct-mutation:** `Household.java` (`raise`/`apply`, `Objects.requireNonNull`, convergent no-op on rename). `Store` state lives *inside* `Household`; there is **no** `Store` aggregate/stream — it is an entity of `Household` (AD-10), so store events go on the `household-{id}` stream. [Source: backend/src/main/java/de/sgart/collaboration/domain/Household.java]
- **Command + online load-then-append handler:** `RenameHousehold` + `RenameHouseholdHandler` (loaded-version-as-expected-version, `ResolveMemberIdentity` port, append-only-when-non-empty, `void` return). [Source: backend/.../application/RenameHouseholdHandler.java]
- **Value object:** `HouseholdName` (trim/blank/length, plain `IllegalArgumentException`). [Source: backend/.../domain/HouseholdName.java]
- **Raw→domain translation + error codes:** `CommandFieldTranslations` + `InvalidHouseholdNameException` + `WriteErrorAdvice` (`{code,message,details}`, localized client-side). [Source: backend/.../application/CommandFieldTranslations.java, adapter/in/WriteErrorAdvice.java]
- **Read model + projector + JSON codec:** `JdbcHouseholdReadModel` / `HouseholdNameReadModel` port, `HouseholdReadModelProjector` (idempotent upserts, subscribes to the `household-` prefix — **extend it**, don't add a second subscription), `DomainEventJsonCodec` (stable string type tags). [Source: backend/.../adapter/out/*]
- **Query:** `ListMyHouseholds` (pure, composes Identity ACL port + read-model port). [Source: backend/.../application/ListMyHouseholds.java]
- **Bean wiring:** `HouseholdApplicationConfig` / `HouseholdReadModelConfig` (wired in `adapter.out` because they reference domain-owned ports; no I/O at construction — keeps `contextLoads()` green with infra down). [Source: backend/.../adapter/out/HouseholdApplicationConfig.java]
- **Read-only GET controller (for `/store-chains`):** `IdentityController` (`/me`) — authenticated, household-less, DTO record. [Source: backend/.../identity/adapter/in/IdentityController.java]
- **Flutter cubit/api/state/cache patterns:** `HouseholdsCubit` (interface deps, `isClosed` guard, `AppException`→`AppError`), `RenameHouseholdCubit` (**command-id per intent**: reuse on same-name retry, regenerate on change — copy `_commandIdFor` verbatim in spirit), `HttpHouseholdsApi` (Dio verbs via `AuthenticatedHttpClient`), `ActiveHouseholdStore` (`SharedPreferences` behind an interface — the model for the reference cache). [Source: app/lib/features/households/**]
- **Provider re-provisioning over pushed routes:** `HouseholdSwitcherSheet._pushOverProviders` (the Story 1.6 `ProviderNotFoundException` lesson). [Source: app/lib/features/households/presentation/household_switcher_sheet.dart]
- **UI kit + row status label:** `SgartButton`, `SgartAppBar`, `StatusLabel`, `SgartShapes`/theme tokens; the chain badge uses the verdigris chain tint (`t-chain` in the mockup) via `StatusLabel`. [Source: app/lib/shared/widgets/*, app/lib/theme/**, screen-household-manage.html]

### Architecture guardrails (must follow — build-time enforced)

- **AD-1 domain purity / AD-2 context isolation** are enforced by `HexagonalArchitectureTest`. Concretely: `collaboration` must **not** import `storereference.domain` (or vice-versa). The chain link on a `Store` is an **opaque id the client supplied**, not a reference to a `storereference` type — that is what keeps the boundary intact *and* satisfies AC2's "never decided server-side".
- **AD-10:** only the `Household` root accepts `AddStore`/`ArchiveStore`; no code loads or mutates a `Store` from outside the root.
- **AD-4:** command handlers append events only; the read model is projection-only. `ListStores` reads Postgres; it never touches the write model.
- **AD-8:** online load-then-append (loaded version = expected version). Client-supplied `basedOnVersion` and the offline queue are **Epic 5** — do not build queueing here.
- **AD-5/AD-6:** caller identity from JWT `sub` only; `MemberId` in payloads; **no PII**. Store names are household data (a shop name), not personal data — but they live in a household-scoped read model covered by household erasure (AD-7). No new PII columns.

### Chain link representation — decision

Persist the accepted chain as an **optional `chainId` only** (nullable UUID) on `StoreAdded` and in `store_read_model.chain_id`. The client resolves `chainId → display name` from its cached reference list (single source of chain names = the reference list; DRY). Rejected alternatives: (a) denormalizing the chain **name** onto the store — introduces a stale-copy/second-source-of-truth smell; (b) a `StoreChain` **value object referencing `storereference`** — violates AD-2. The server does **not** validate `chainId` against `store_chain_reference` (advisory / client-decided — AC2). Seed the reference table with **fixed literal UUIDs** so a stored `chainId` stays resolvable across environments.

### Open decisions to confirm while implementing (pick the recommended default, note it in Completion Notes)

1. **Uniqueness scope & comparison** *(recommended: unique among **active/non-archived** stores; case-insensitive, trimmed).* This makes "re-add after archive" work and matches "hidden from future selection". Confirm the epic's "unique per Household" is not meant to include archived rows — the recommended reading is that it is not.
2. **Archive HTTP verb** *(recommended: `DELETE /{storeId}` returning `204`, javadoc'd as "archives, never deletes").* `POST /{storeId}/archive` is the honest alternative if `DELETE`-that-doesn't-delete reads as misleading in review.
3. **Duplicate-name status** *(recommended: `409 Conflict` with `store.duplicateName`).* A uniqueness collision is a conflict, not a malformed request.
4. **Navigation host** *(recommended: a thin „Haushalt verwalten" screen with a „Geschäfte" row, since Epic 4 extends the same hub).* A direct „Geschäfte" switcher entry is the simpler alternative if the thin hub feels premature.

### Scope — what is and isn't in 1.8

- **In:** add/archive `Store` (domain + read model + REST), `ListStores` (active only), the seeded `StoreChain` reference endpoint + client cache + client-side matcher, the „Geschäfte" management screen, German copy + error codes, full test coverage.
- **Design-constraint-only (verified later):** AC4 inline-from-picker creation (build the reusable creation path; the item-assignment/trip-start/reroute pickers are Stories 2.6/3.1/3.2). AC5 item→„Noch nicht zugeordnet" fallback (guaranteed structurally by `ListStores` returning active stores only; items/lists/trips arrive in Epics 2–3).
- **Out (do not build):** the offline queue / conflict UI (Epic 5); the members/invites/roles part of „Haushalt verwalten" (Epic 4); multi-market or additional-language StoreChain data (epic line 147); onboarding's store step (Story 1.9 reuses this creation path).

### Previous-story intelligence (deferred-work.md)

- `concurrency.staleVersion` (409) is still **unmapped on the client** by design (unreachable until Epic 4/5). Add/archive can hit `ConcurrencyConflictException` in theory, but with a single member per household pre-Epic-4 it is not reachable — **do not** add conflict copy/retry UX here; leave it for Epic 4/5 (matches the Story 1.7 deferral).
- KurrentDB idempotency pre-check + append is non-atomic (latent, not reachable via fresh-stream flows). Adding a store to an **existing** household stream is a retry-against-existing-stream flow — but the client `commandId` still makes replay idempotent per AD-8; the latent race is a concurrency edge, not a correctness break for the single-writer MVP. Note it, don't fix it here.

### Project Structure Notes

- New backend packages already exist as empty scaffolding: `de.sgart.storereference.{domain,application,adapter.in,adapter.out}` — populate them. Collaboration store code goes in the existing `collaboration.{domain,application,adapter.in,adapter.out}`.
- Flyway numbering: existing max is `V2`. Recommended: `V3__store_chain_reference.sql` (storereference), `V4__store_read_model.sql` (collaboration). Confirm no gap before writing.
- New Flutter feature folder `app/lib/features/stores/{data,domain,presentation}` mirroring `features/households`; tests under `app/test/features/stores/**` with a `fake_stores_dependencies.dart` in `test/support` (mirror `fake_households_dependencies.dart`).
- Run backend tests with the project's Gradle wrapper; run Flutter tests with `~/tools/flutter/bin` on PATH (per project setup — `flutter test` does run locally). A red build blocks merge (CLAUDE.md §6).

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story-1.8] — story statement + AC1–AC5 (lines 383–409), FR3 (line 42), UX-DR22 reusable store picker (line 143).
- [Source: _bmad-output/planning-artifacts/architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md] — AD-2 (contexts), AD-4 (CQRS/ES), AD-8 (concurrency), AD-10 (`Store` is a `Household` entity), AD-11 (naming), Capability→Architecture map (Stores → Collaboration `Household` + Store Reference), Consistency Conventions, module layout.
- [Source: _bmad-output/planning-artifacts/ux-designs/ux-sgart-2026-08-20/EXPERIENCE.md] — IA (§2 „Haushalt verwalten" hosts stores; CAP-3 → „Haushalt verwalten → Geschäfte"), key screen „Haushalt verwalten" (§3, lines 119–122: removal archives, advisory chain).
- [Source: _bmad-output/planning-artifacts/ux-designs/ux-sgart-2026-08-20/DESIGN.md#§4b] — row status label; store chain = verdigris tint.
- [Source: .working/screen-household-manage.html frame 3 „Geschäfte"] — concrete store list + add + chain-suggestion + archive-helper copy.
- [Source: _bmad-output/implementation-artifacts/deferred-work.md] — `concurrency.staleVersion` client mapping deferred; KurrentDB non-atomic idempotency note.

## Review Findings

_Adversarial code review 2026-08-24 (Blind Hunter + Edge Case Hunter + Acceptance Auditor, Opus 4.8). Severities set at triage from real call-site consequence._

- [x] [Review][Patch] AC2 „ändern" (change) chain affordance not implemented — only accept + clear exist (`manage_stores_page.dart`; `StoresCubit` has `clearSuggestion()` but no select-a-different-chain method). Resolution (2026-08-24): add an explicit chain-override picker — tapping the suggestion lets the member choose another chain from the cached reference list (or clear). Add a `StoresCubit` method to set an explicit chain override and a picker UI on the add-store row. [medium]

- [x] [Review][Patch] Second store added in one screen session is silently dropped — cubit reuses a spent `commandId` [app/lib/features/stores/presentation/stores_cubit.dart:107]. On a successful add it sets `_commandIdName = null` but does not regenerate `_commandId`; `_commandIdFor` then returns the already-applied id for the next (different-name) add. `EventStore.append` dedups by `commandId` per household stream as a silent no-op success (`EventStore.java:27-32`, `InMemoryEventStore.java:36-37`), so the second store is never persisted (client optimistically shows it → vanishes on reload). Also lets a same-name re-add bypass the 409. Fix: regenerate `_commandId` on the success path. [HIGH]
- [x] [Review][Patch] `addStore` mints a fresh `storeId` on every API call [app/lib/features/stores/data/stores_api.dart:53], so a same-name retry after a lost success-response sends a new `storeId` while the server dedups on the reused `commandId` — the client's optimistic id then diverges from the persisted one (phantom id; archiving it no-ops until reload). Fix: mint the `storeId` once per add intent (in the cubit, like `commandId`) and pass it into the API. [medium]
- [x] [Review][Patch] Store-chain reference cache never self-heals [app/lib/features/stores/data/store_chain_reference_cache.dart:36,57]. A malformed/shape-changed cached JSON makes `_readCached` throw, `load` propagates, the cubit swallows it to `[]`, and the bad entry is never re-fetched or cleared → chain matching silently dead on that device (realistic trigger: a future `StoreChain.fromJson` field change breaking existing caches on upgrade). Also an empty first fetch is cached permanently. Fix: try/catch the decode and treat an unreadable or empty cache as no-cache (fall through to the API + overwrite). [medium]
- [x] [Review][Patch] `NotAHouseholdMemberException` has no `@ExceptionHandler` → HTTP 500 instead of 403 [backend/.../collaboration/domain/Household.java:149, adapter/in/WriteErrorAdvice.java]. Unreachable under consistent data (the handler resolves membership via the Identity ACL first, yielding 403 for genuine non-members), but a new domain exception reaching the controller with no mapping is a latent 500 under ACL/stream divergence. Fix: map it to 403. [low]
- [x] [Review][Patch] Dead `error` field in `_FailureBody` [app/lib/features/stores/presentation/manage_stores_page.dart]. `state.loadError` is captured but never read — the body always renders `errorGenericFallback`. Boy-Scout/KISS: either surface the real error or drop the field. [low]
- [x] [Review][Patch] `addStore` submits a whitespace-only/empty name to the server with no local guard [app/lib/features/stores/presentation/stores_cubit.dart:92]; the Add button is disabled only while submitting. Minor UX (a network round-trip for an obviously-invalid input). Fix: disable Add when the trimmed name is empty. [low]
- [x] [Review][Patch] Backend concurrency-conflict handler test claimed but absent — Task 13 marks „concurrency conflict propagation" [x], yet neither `AddStoreHandlerTest` nor `ArchiveStoreHandlerTest` covers `ConcurrencyConflictException` propagation (CLAUDE.md §6). Fix: add the missing test, or correct the checkbox. [low]

- [x] [Review][Defer] Chain matcher: short-chain prefix false positives (`"dm"` → „Dmitri's Kiosk") and word-boundary recognises only ASCII space, not hyphen/comma (`"City-Edeka"` → no suggestion) [app/lib/features/stores/domain/store_chain_matcher.dart:29,51-53] — deferred, advisory/clearable feature; matching-quality polish, not a correctness break.
- [x] [Review][Defer] `archiveStore` (cubit) uses a fresh random `commandId` per call [app/lib/features/stores/presentation/stores_cubit.dart:120] — deferred, converges safely today via the domain archive no-op; a per-intent id would only matter if that no-op were tightened. Consistency nit with the add/rename pattern.
- [x] [Review][Defer] Aggregate `addStore` does not guard against a reused/known `storeId`; `apply(StoreAdded)` unconditionally resets `archived=false` while the read-model upsert keeps `archived=true` [backend/.../collaboration/domain/Household.java:119,176] — deferred, unreachable via the shipped client (fresh client-minted UUIDs); the read-model's un-reset `archived` is correct for the reachable redelivery/re-projection case. Defensive-invariant gap only.
