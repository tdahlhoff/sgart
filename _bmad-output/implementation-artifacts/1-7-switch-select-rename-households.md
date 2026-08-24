---
baseline_commit: 64ded01de82e10feb5a3bdeccf75ea4310d7a1c1
---

# Story 1.7: Switch, select & rename households

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a person in more than one household,
I want an always-visible switcher (and, as an Admin, to rename a household),
so that I always know — and can change — which household I'm acting in.

## Acceptance Criteria

**AC1 — Persistent header shows the current household (UX-DR9)**
**Given** a person in ≥ 1 household
**When** any main screen is shown
**Then** the persistent header shows the **current household name**, and tapping it opens a switcher listing their households with the active one clearly marked („Aktiv").

**AC2 — Switching household switches header and content**
**Given** the switcher is open
**When** they pick another household
**Then** the header **and** content switch to it with a **brief confirmation**, so it is unmistakable which household is active.

**AC3 — An Admin can rename their household, and the name updates everywhere**
**Given** an Admin
**When** they rename the household
**Then** the name updates everywhere it is shown (header, switcher, home). *(Household deletion and role governance are covered in Epic 4.)*

**AC4 — Only an Admin may rename (domain-enforced)**
**Given** a member who is **not** an Admin of the household
**When** a rename is attempted for that household
**Then** the `Household` aggregate rejects it — rename is an Admin-only capability, enforced as a domain invariant, not merely hidden in the UI. *(No Participant members exist yet — invites are Epic 4 — so this is proven by a synthetic unit test; see Testing standards.)*

## Tasks / Subtasks

- [x] **Task 1 — `HouseholdRenamed` event + rename on the `Household` aggregate (AC3, AC4)**
  - [x] Add `collaboration/domain/HouseholdRenamed.java` — `record HouseholdRenamed(EventId eventId, HouseholdId householdId, HouseholdName newName) implements DomainEvent` (past-tense PascalCase, AR10); non-null guards mirroring `HouseholdCreated`. Carries **no** `MemberId` — a rename is not personal data; who renamed is not tracked as read-model state in MVP (YAGNI; Epic 4 governance may add an audit trail).
  - [x] Extend `Household` to **track member roles**: `apply(MemberJoined)` records `memberId → HouseholdRole` in a private `Map` (today a no-op with a "no member list until Epic 4" comment — this is the first invariant that actually needs it). `apply(HouseholdRenamed)` sets `this.name`.
  - [x] Add command method `Household.rename(MemberId requestedBy, HouseholdName newName, CommandId commandId)`: fail fast if `requestedBy` is unknown or **not `HouseholdRole.ADMIN`** → throw a **domain** exception (`RenameNotPermittedException` in `collaboration.domain`, extending `RuntimeException`; the domain stays free of the client-facing `code`/`ErrorDescriptor` — the application layer translates it, mirroring how `HouseholdName` throws a plain exception the handler maps). If the trimmed `newName` equals the current name, **raise nothing** (convergent no-op, AD-8 spirit) — no empty `HouseholdRenamed`.
  - [x] Unit tests (`HouseholdTest`): `anAdminRenamesTheHouseholdRaisingHouseholdRenamedWithTheNewName`, `aParticipantCannotRenameTheHousehold` (rehydrate from `HouseholdCreated` + a synthetic `MemberJoined(participantId, PARTICIPANT)`), `aNonMemberCannotRenameTheHousehold`, `renamingToTheSameNameRaisesNoEvent`, `renameFoldsSoThatSubsequentStateReflectsTheNewName`.

- [x] **Task 2 — `RenameHousehold` command + handler (AC3, AC4)**
  - [x] Add `collaboration/application/RenameHousehold.java` — `record RenameHousehold(HouseholdId householdId, HouseholdName newName, CommandId commandId, AggregateVersion basedOnVersion)` implementing `Command` (imperative name, carries the envelope, AR10/AD-8).
  - [x] Add `collaboration/application/RenameHouseholdHandler.java`:
    1. Resolve the caller's `MemberId` for this household via the Identity ACL's `ResolveMemberIdentity` port (published application port; **never** `identity.domain`, AD-2). `NotAMemberException` propagates for a non-member (→ 403).
    2. `eventStore.readStream(StreamId.forHousehold(householdId))` → `Household.rehydrate(...)`.
    3. Translate the raw name to `HouseholdName`, reusing the existing `InvalidHouseholdNameException` mapping (`household.nameRequired` / `household.nameTooLong`) so `adapter.in` never constructs the domain type (layering, as in `CreateHouseholdHandler`).
    4. `household.rename(memberId, name, commandId)`.
    5. `eventStore.append(household.version(), household.uncommittedEvents(), commandId)` — expected-version is the **loaded** stream version (online rename; client-supplied `basedOnVersion` + offline queue is Epic 5). A concurrent rename loses with the existing `ConcurrencyConflictException` (→ 409). If `rename` raised nothing (unchanged name), append an empty list is a no-op — guard by skipping the append when `uncommittedEvents()` is empty.
    6. Command returns `void` (CQRS: commands return no domain data; the client already knows the id and name).
  - [x] Translate `RenameNotPermittedException` (domain) into an application-facing error carrying code **`household.renameNotPermitted`** (a clean 403), mirroring where `NotAMemberException` lives in `identity.application`. *(Impl: `RenameNotPermittedApplicationException` in `collaboration.application`; the raw name/commandId translators were extracted into a shared `CommandFieldTranslations` helper reused by both handlers — DRY.)*
  - [x] Handler unit tests (`RenameHouseholdHandlerTest`, in-memory `EventStore` + in-memory ACL): `renamingAppendsHouseholdRenamedUnderTheLoadedExpectedVersion`, `rejectsARenameFromANonAdminMember`, `rejectsARenameFromANonMember`, `mapsABlankNameToNameRequired`, `mapsAnOverLongNameToNameTooLong`, `renamingToTheCurrentNameAppendsNothing`.

- [x] **Task 3 — REST endpoint + error advice (AC3, AC4)**
  - [x] `HouseholdController`: add `PATCH /api/v1/households/{householdId}` accepting `{name, commandId}`; caller identity from the JWT `sub` via `AuthenticatedCaller` (never the body, AR10/AD-5). Return `204 No Content` (a command). Parse/validate `commandId` in the handler (reuse the `command.commandIdRequired`/`command.commandIdInvalid` mapping via the shared `CommandFieldTranslations` helper).
  - [x] `WriteErrorAdvice`: map `household.renameNotPermitted` → **403**; `NotAMemberException` / `identity.notAMember` → **403** was already mapped (verified, not 500). Keep `concurrency.staleVersion` → **409** and the existing 400 mappings.
  - [x] `HouseholdControllerTest` (MockMvc + `spring-security-test` `jwt()`): `patchRenamesAndReturns204`, `patchWithABlankNameReturns400WithNameRequired`, `patchFromANonAdminReturns403WithRenameNotPermitted`, `patchWithAMalformedCommandIdReturns400`. (Non-Admin fixture: seed the in-memory ACL/event stream so the resolved member is a Participant — synthetic only.)

- [x] **Task 4 — Project `HouseholdRenamed` into the read model (AC3)**
  - [x] `HouseholdReadModelProjector.project(...)`: add a `case HouseholdRenamed` → `readModel.upsertHousehold(householdId, newName)` (verified `upsertHousehold` is `INSERT … ON CONFLICT … DO UPDATE SET name`, already name-updating — no change needed).
  - [x] `DomainEventJsonCodec`: register the `HouseholdRenamed` type tag (stable wire tag `"HouseholdRenamed"`) both directions; added `DomainEventJsonCodecTest` round-trip (all three events).
  - [x] Projector Testcontainers test: `projectingHouseholdRenamedUpdatesTheReadModelToTheNewName` in `HouseholdReadModelProjectorTest` — project `HouseholdCreated` then `HouseholdRenamed`, assert the read model reflects the **new** name.
  - [x] **No new migration** for rename (name column already exists). No role read model / role column added (Clarification C, deferred to Epic 4).

- [x] **Task 5 — Client: persistent header + household switcher sheet (AC1, AC2)**
  - [x] Introduce a **persistent shell** (`households/presentation/household_shell.dart`) hosting header + body. Header: the active household **name** as a tappable **switcher chip** (key `switcher-chip`, via a new optional `onTitleTap`/`titleKey` on `SgartAppBar`) + a **sync/offline status placeholder** in `SgartAppBar.actions` (key `sync-status-placeholder`, no logic). Body reuses the minimal `HouseholdHomePage` content (refactored to body-only, chrome removed; `current-household-name`/`sign-out-button` keys preserved).
  - [x] `households/presentation/household_switcher_sheet.dart`: bottom sheet from the chip listing **all** households (retained list), active marked „Aktiv" (key `switcher-active-badge`); tap another → `switchActive`. Hosts **„Neuen Haushalt erstellen"** (create flow) + **„Haushalt umbenennen"** (Task 6), both re-providing `HouseholdsApi`/`HouseholdsCubit` on the pushed route (P1). „Haushalt verwalten" hub → Epic 4.
  - [x] Reworked `HouseholdsCubit`/`HouseholdsState`: `home` → **`shell`** status carrying a **retained `households` list** + **`activeHousehold`**; added `switchActive` + `applyActiveHouseholdRename`.
  - [x] **Persist the active household on-device (Clarification B):** `ActiveHouseholdStore` interface (`readActive`/`writeActive`/`clear`) + `SharedPreferencesActiveHouseholdStore` impl; injected into the cubit (in-memory fake in tests). `bootstrap()` restores a stored-and-still-present active → shell (skips ≥2 selection); else 0 → choice · 1 → shell · ≥2 → selection. Writes on entry/switch/create. **Cleared on sign-out** — wired into `AuthCubit.signOut()` (DSGVO / AD-7).
  - [x] On switch: persist, header+body update, **brief confirmation** `SnackBar` (key `switch-confirmation`, „Zu {name} gewechselt").
  - [x] Added `shared_preferences: ^2.3.2` to `pubspec.yaml`.
  - [x] Widget/cubit tests (fake `ActiveHouseholdStore`): `theHeaderShowsTheActiveHouseholdName`, `tappingTheChipOpensTheSwitcherListingAllHouseholdsWithTheActiveOneMarked`, `pickingAnotherHouseholdSwitchesTheActiveOneAndShowsConfirmation` (`household_shell_test`), `aStoredLastActiveHouseholdIsRestoredOnLaunchSkippingSelection`, `aStoredHouseholdNoLongerInTheListFallsBackToRouting`, `switchingWritesTheNewActiveHouseholdToTheStoreAndUpdatesTheActiveOne` (`households_cubit_test`), `signOutClearsTheStoredActiveHousehold` (`auth_cubit_test`).

- [x] **Task 6 — Client: rename flow (AC3)**
  - [x] `households/data/households_api.dart`: added `Future<void> renameHousehold(...)` → `PATCH /api/v1/households/{householdId}` with `{name, commandId}`, over a new `patchJson` on `AuthenticatedHttpClient` (204-tolerant, same `{code,message,details}` → `AppError` mapping).
  - [x] `rename_household_cubit.dart` + `_state.dart`: mirror `CreateHouseholdCubit` — **one `commandId` per rename intent, reused across retries**. On success propagate the trimmed name (`applyActiveHouseholdRename` on `HouseholdsCubit`) so header/switcher/home update (AC3); on failure surface the mapped code.
  - [x] `rename_household_page.dart`: name field prefilled with the current name; submit → `renameHousehold`; on success pop + update the shell's active-household name + brief confirmation (key `rename-confirmation`, „Haushalt umbenannt"). Rename offered to the current member; backend enforces Admin-only (Clarification C).
  - [x] Error copy mapping: `household.renameNotPermitted` → „Nur Administratoren können den Haushalt umbenennen." in `error_message_resolver.dart` (+ test). Reuses existing name-error copy.
  - [x] `app_de.arb`: added the German keys (switcher heading/„Aktiv" badge/switch confirmation/chip tooltip/sync placeholder/„Neuen Haushalt erstellen"/„Haushalt umbenennen"/rename heading/submit/success/rename-not-permitted). English keys, German values.
  - [x] Widget/cubit tests: `renamingUpdatesTheNameEverywhereItIsShown`, `aRenameNotPermittedErrorShowsTheAdminOnlyMessage` (`rename_household_page_test`), `renameReusesOneCommandIdAcrossRetries` (`rename_household_cubit_test`).

- [x] **Task 7 — Guardrails & keep-green**
  - [x] `NoPersistedPersonalDataTest` stays green (no new PII column — no migration; the role map is transient aggregate state).
  - [x] ArchUnit hexagonal rules green: `HouseholdRenamed`/`rename`/`RenameNotPermittedException` are pure `collaboration.domain`; the handler calls only Identity **application** ports (`ResolveMemberIdentity`, `NotAMemberException`), never `identity.domain`; DTOs at `adapter.in` carry `String`, never the domain `HouseholdName`.
  - [x] Ran **both** suites for real: **backend 148 green** (was 129; +19), **client 143 green** (was 128; +15). `flutter analyze` clean.
  - [x] Updated the 1.6 client tests the shell now wraps (`first_run_router_test`, `create_household_page_test`, `households_cubit_test`) + the `AuthCubit` construction in the auth tests; logged in the Change Log.

## Dev Notes

### Scope & intent
Story 1.6 shipped the plumbing and *minimal* screens and **explicitly named 1.7 as the owner of the switcher + rename**: 1.6's Clarification 6 (LOCKED) says "the **switcher/rename is 1.7**", and `household_home_page.dart` calls itself "the seed Story 1.7's persistent switcher header grows from." So 1.7 is a **mostly-additive UX + one new write** on top of the working vertical slice — not new infrastructure. The backend already has the aggregate, event store, projector, read model, query, and REST slice; 1.7 adds **one command path** (`RenameHousehold` → `HouseholdRenamed`) and grows the client from single-screen routing into a **persistent shell with a switcher**.

**Deliberate scope boundaries (KISS/YAGNI, honor the epic):**
- **No role governance.** Promote/remove, last-Admin rule, delete household, members/invites/roles UI — all **Epic 4**. 1.7 only adds the **rename** capability and enforces **Admin-only** for it in the domain. The „Haushalt verwalten" hub the switcher will eventually host is **not** built here (only its „umbenennen" and „+ Neuen Haushalt" entries).
- **Active household persists on-device (Clarification B).** The last-active `householdId` is stored locally (`shared_preferences`, behind an `ActiveHouseholdStore` interface) so a relaunch returns to it, skipping the ≥2 selection screen when the stored household is still in the caller's list; otherwise 1.6's 0/1/≥2 routing applies. Cleared on sign-out (DSGVO / AD-7).
- **No offline rename.** The offline queue + client-supplied `basedOnVersion` is **Epic 5**. 1.7's rename is an online load-then-append (Clarification C).
- **No tabs / lists screen.** The shell body stays the minimal home; Listen/Einkauf/Profil tabs are Epic 2/3, Profil is 1.11.

### Source of truth: ARCHITECTURE-SPINE + epics + glossary (binding)
[Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md; epics.md; specs/spec-sgart/glossary.md]
- **AD-4 (spine 82–86):** state changes **only** by appending events under an expected-version check; read models are **projection-only**, never written by a command handler; eventually consistent. → `RenameHousehold` appends `HouseholdRenamed`; the projector (not the handler) updates the name read model.
- **AD-1 (spine 64–68):** every state change is a command handled by an aggregate emitting events; the domain imports no framework/infra/transport type. → `rename` + `HouseholdRenamed` + `RenameNotPermittedException` are pure `collaboration.domain`; the ArchUnit rules already ban infra imports there.
- **AD-2 (spine 70–74):** a context touches another only via its published application-layer port. → the rename handler resolves the caller's `MemberId` through the Identity **`ResolveMemberIdentity`** application port; it never imports `identity.domain` or the mapping table.
- **AD-5 / AD-6 (spine 88–98):** events/read models reference a person only by `MemberId`; **no persisted PII**. → `HouseholdRenamed` carries no member reference at all; the role map is transient aggregate state; no new PII column.
- **AD-8 (spine 106–110):** client commands carry the target root's stream version + a `commandId`; a stale version is rejected; the `commandId` makes replay idempotent; **convergent actions resolve silently**. → the rename handler appends under the loaded version; renaming to the current name raises nothing (convergent no-op); the client reuses one `commandId` per rename intent.
- **AD-10 / AD-11 (spine 118–128):** aggregate boundaries own their entities; **`HouseholdRole {Admin, Participant}`** — never the bare word "Member" for the role. → the Admin check is a `Household` invariant, keyed on the role recorded by `MemberJoined`.
- **AR10 / conventions (spine 132–146):** event **past-tense PascalCase** (`HouseholdRenamed`); command **imperative** (`RenameHousehold`); REST under **`/api/v1`**; error shape `{code,message,details}` with a client-facing `code`; identity from JWT `sub`, never the body.
- **UX-DR9 (epics AC + EXPERIENCE §3 "Household switcher"):** the household name is **always** in the header (never settings-buried); the active one is **unmistakable** („Aktiv"); one tap to switch; after switching, header **and** content change with a **brief confirmation**. [Source: ux-designs/ux-sgart-2026-08-20/EXPERIENCE.md lines 48–51, 87–91; .working/ia.md lines 22–37]

### The scaffold & contracts already in the repo (read before writing)
**Backend (Story 1.6 — reuse, extend minimally):**
- `collaboration/domain/Household.java` — the aggregate. `apply(MemberJoined)` is **currently a no-op** with a comment that no member list is needed "until Epic 4's governance"; 1.7 is the first invariant (Admin-only rename) that needs the role, so record `memberId → HouseholdRole` now. `apply(HouseholdCreated)` sets `name` — add an `apply(HouseholdRenamed)` beside it. `create(...)` is the factory pattern to mirror for the `rename(...)` command method (validate → `raise`). `rehydrate(streamId, history)` already exists for the handler to load.
- `collaboration/domain/HouseholdName.java` — throws a plain `IllegalArgumentException` (`MAX_LENGTH = 120`); the handler translates it (do **not** add a client `code` in the domain). `HouseholdCreated.java`/`MemberJoined.java` — the event record shape (`EventId eventId` first, non-null guards). `HouseholdRole.java` — `{ADMIN, PARTICIPANT}` (enum constants are `ADMIN`/`PARTICIPANT`; note `Household.create` uses `HouseholdRole.ADMIN`).
- `shared/EventSourcedAggregate.java` — `rename` calls `raise(event)`; the handler appends `uncommittedEvents()` under `version()` (the **loaded** version after `rehydrate`), then the store calls `markEventsCommitted()`. `version()` reports the current stream version — use it as the expected version for the append.
- `shared/EventStore.java` — `append(AggregateVersion expectedVersion, List<DomainEvent>, CommandId)` (atomic, expected-version, idempotent-by-`commandId`) + `readStream(StreamId)` (ordered, empty for new). Skip the append when there are no uncommitted events (unchanged-name no-op).
- `collaboration/application/CreateHouseholdHandler.java` — **the handler pattern to mirror**: `toCommandId`/`toHouseholdName` fail-fast translators mapping to `InvalidCommandEnvelopeException` (`command.commandIdRequired`/`command.commandIdInvalid`) and `InvalidHouseholdNameException` (`household.nameRequired`/`household.nameTooLong`). Reuse these — extract shared helpers if it keeps things DRY, but don't over-abstract for two callers (KISS).
- `collaboration/adapter/in/HouseholdController.java` (`POST`/`GET` today) + `WriteErrorAdvice.java` (`{code,message,details}` mapping) — add the `PATCH` method and the new code→status mappings here.
- `collaboration/adapter/out/{HouseholdReadModelProjector,JdbcHouseholdReadModel,DomainEventJsonCodec}.java` — add the `HouseholdRenamed` case + JSON type tag; verify `upsertHousehold` updates the name (upsert, not insert-only).
- `identity/application/ResolveMemberIdentity.java` — `resolve(KeycloakUserId, HouseholdId) → MemberId`, throwing `NotAMemberException` for a non-member. **This is the port the rename handler uses** to get the caller's `MemberId`; it takes `KeycloakUserId` (a `identity.domain` type) — check whether a `String`-taking published overload exists (as `MintMemberIdentity.mint`/`ListHouseholdsForCaller.forCaller` do) and add one if needed so `collaboration.application` never imports `identity.domain` (AD-2, the 1.6 boundary-safe-ports lesson).

**Client (Story 1.6 — extend routing into a shell):**
- `app/lib/features/households/presentation/first_run_router.dart` — builds the `Dio`/`HouseholdsApi` **once** in a `StatefulWidget` (do not regress this) and switches on `HouseholdsState`. The `home`/`selection` branches now lead into the **shell**; keep loading/needsChoice/failure.
- `households_cubit.dart`/`households_state.dart` — the routing brain. Add the retained list + `activeHousehold` + `switchActive`. Keep `_safeEmit`/`isClosed` guarding and the interface-only dependency (CLAUDE.md §6).
- `household_home_page.dart` — its current-household-name + sign-out becomes the **shell body**; the `current-household-name` key and `sign-out-button` key should be preserved for the existing tests (or update those tests and log it).
- `household_selection_page.dart` — the initial ≥2 picker; on tap it now enters the shell with the chosen active household.
- `create_household_cubit.dart`/`create_household_page.dart` — the **rename cubit/page mirror these** (one `commandId` per intent, trimmed-name propagation — the 1.6 review's P5/P7 patches). `create_or_await_choice_page.dart` re-provides `HouseholdsApi`/`HouseholdsCubit` for pushed routes (1.6 review P1) — the switcher's „+ Neuen Haushalt" and the rename page must do the same to avoid `ProviderNotFoundException`.
- `authenticated_http_client.dart` — has `getJson`/`getJsonList`/`postJson`; add `patchJson(path, body)` with the same `{code,message,details}` → `AppError` mapping. `error_message_resolver.dart` — add `household.renameNotPermitted` copy. `sgart_app_bar.dart` — `actions` slot exists for the right-hand sync/offline placeholder; the title becomes the tappable switcher chip.

### Rename authorization — the load-bearing invariant (read carefully)
Rename is **Admin-only**, and that rule lives in the **domain**, not the controller (CLAUDE.md §3: business rules in the domain model). The `Household` aggregate must know the caller's role to enforce it, so `apply(MemberJoined)` now records `memberId → HouseholdRole`. `rename(requestedBy, …)` throws `RenameNotPermittedException` unless `requestedBy` maps to `ADMIN`. The handler first resolves the caller's `MemberId` via the ACL (`ResolveMemberIdentity`) — a **non-member** never even reaches `rename` (`NotAMemberException` → 403); a **Participant member** reaches it and is rejected by the aggregate (`household.renameNotPermitted` → 403).

**Why prove AC4 with a unit test when the running app can't yet produce a non-Admin:** invites (Epic 4) are the only path to a `PARTICIPANT`, so today every member is the `ADMIN` creator. The invariant is still real and must be enforced now (the code path exists; Epic 4 will exercise it end-to-end). A pure aggregate test **can** construct the situation — rehydrate a `Household` from `HouseholdCreated` + a synthetic `MemberJoined(participantId, PARTICIPANT)` and assert `rename(participantId, …)` throws. This is the correct level (test pyramid, CLAUDE.md §6): the invariant is domain logic, so it is proven by a fast domain test, not an integration test needing a fake invite flow.

### Client-side role gating is deferred to Epic 4 (Clarification C)
The client does **not** learn the caller's per-household role in 1.7 — `HouseholdSummary`/`ListMyHouseholds` stay `{householdId, name}`, no role column is added to the membership read model. Rename is offered to the current member and the backend enforces Admin-only. Because every member is currently an Admin, no user is shown a rename they will be denied. When Epic 4 introduces Participants + role governance, **that** epic adds the role to the summary and gates the UI (role governance is explicitly Epic 4 in the epic text). Building a role read model now would be YAGNI and would pre-empt Epic 4's design.

### The eager-boot trap still applies (do not regress 1.6's guards)
1.6 made the KurrentDB client + PostgreSQL/Flyway + the projector **lazy / gated** so `contextLoads()` survives with the stores down (`ContextLoadsWithout{KurrentDb,Postgres}Test`, `SGART_FLYWAY_ENABLED`, `SGART_PROJECTOR_AUTOSTART`). 1.7 adds no new eager infrastructure — the rename path reuses the existing `EventStore`/projector beans. Keep the default/test context booting with neither store present; the new Testcontainers tests own their container lifecycle. Fast handler/aggregate tests use the in-memory `EventStore` + in-memory ACL doubles (the bulk of coverage).

### Previous-story intelligence (Stories 1.4–1.6 — done)
[Source: implementation-artifacts/1-4…1-6; deferred-work.md]
- **1.6 review lessons to carry forward:** (P1) pushed routes must re-provide `HouseholdsApi`/`HouseholdsCubit` — applies to the rename page and „+ Neuen Haushalt" from the switcher; (P3) idempotency = one `commandId` per intent reused across retries — the rename cubit mirrors this; (P5/P7) name errors get distinct codes and the persisted value is the **trimmed** name — the rename must show the trimmed name everywhere. (P4) missing/malformed `commandId` → localizable 400, not 500 — reuse the `command.commandId*` mapping on the PATCH path.
- **Boundary-safe published ports (1.6):** `MintMemberIdentity.mint`/`ListHouseholdsForCaller.forCaller` take a plain `String keycloakUserId` so `collaboration.application` never imports `identity.domain`. Give `ResolveMemberIdentity` the same treatment if it doesn't already expose a `String`-taking form.
- **`deferred-work.md`:** the two open items (KurrentDB idempotency-check/append non-atomicity; `NoPersistedPersonalDataTest` block-comment stripping) are **latent/test-only** and do **not** block 1.7. The non-atomic idempotency note becomes marginally more relevant now that rename is a **second write against an existing stream** — but it is still only reachable under concurrent duplicate `commandId` delivery to the same stream, which the online rename path doesn't produce (offline replay is Epic 5). Note it; don't fix it here unless a test surfaces it.
- **Local test reality (memory `flutter-test-local`):** Flutter SDK at `/home/timo/tools/flutter/bin` (not on PATH). Backend **129 green**, client **128 green** at 1.6. Run both for real; a red build blocks merge (NFR6).
- **Git (memory `git-workflow`):** solo, **direct-to-`main`** pre-beta (branches start at beta/Epic 4). Baseline for this story = `64ded01`.

### Latest tech notes
No backend dependencies. Backend reuses the existing KurrentDB client, `JdbcClient`/Flyway, and Testcontainers wiring from 1.6. Client reuses `dio`/`AuthenticatedHttpClient`, `flutter_bloc`, `bloc_test`, `uuid` (already present for `commandId`); the **one new client dependency** is `shared_preferences` for the last-active-household persistence (Clarification B) — behind an `ActiveHouseholdStore` interface so the cubit and its tests never touch the plugin directly. The switcher sheet is a standard `showModalBottomSheet`; the brief confirmation is a `SnackBar`; the shell is a plain `Scaffold` (no `go_router` — YAGNI; the nav shell with tabs is Epic 2).

### Project Structure Notes
```text
backend/src/main/java/de/sgart/collaboration/
  domain/
    HouseholdRenamed.java                    # DomainEvent: householdId, HouseholdName newName (new)
    RenameNotPermittedException.java         # domain exception: non-Admin rename (new)
    Household.java                           # + role map in apply(MemberJoined); apply(HouseholdRenamed); rename(...) (modified)
  application/
    RenameHousehold.java                     # Command: householdId, newName, commandId, basedOnVersion (new)
    RenameHouseholdHandler.java              # resolve member -> load -> rename -> append (new)
    RenameNotPermittedApplicationException?  # or reuse WriteErrorAdvice mapping of the domain exception (decide in impl)
  adapter/in/
    HouseholdController.java                  # + PATCH /api/v1/households/{id} (modified)
    WriteErrorAdvice.java                     # + household.renameNotPermitted->403, identity.notAMember->403 (modified)
  adapter/out/
    HouseholdReadModelProjector.java          # + case HouseholdRenamed -> upsert name (modified)
    DomainEventJsonCodec.java                 # + HouseholdRenamed type tag (modified)
    JdbcHouseholdReadModel.java               # verify upsertHousehold updates name (modified if insert-only)
backend/src/main/java/de/sgart/identity/application/
  ResolveMemberIdentity.java                  # + String-taking published overload if missing (modified)
app/lib/features/households/
  data/households_api.dart                     # + renameHousehold(id, name, commandId) (modified)
  presentation/household_shell.dart            # persistent header (switcher chip + status slot) + body (new)
  presentation/household_switcher_sheet.dart   # bottom sheet: switch / rename / + new household (new)
  presentation/rename_household_cubit.dart, rename_household_state.dart (new)
  presentation/rename_household_page.dart      # prefilled name field -> PATCH (new)
  presentation/households_cubit.dart, households_state.dart  # + retained list + activeHousehold + switchActive + restore-from-store (modified)
  presentation/first_run_router.dart           # home/selection -> shell (modified)
  presentation/household_selection_page.dart, household_home_page.dart  # feed the shell (modified)
  data/active_household_store.dart             # interface + shared_preferences impl; persist last-active (new)
app/lib/features/auth/presentation/auth_cubit.dart  # signOut() also clears ActiveHouseholdStore (modified)
app/lib/shared/http/authenticated_http_client.dart  # + patchJson(path, body) (modified)
app/lib/shared/errors/error_message_resolver.dart   # + household.renameNotPermitted copy (modified)
app/lib/l10n/app_de.arb                         # + switcher/rename German copy (modified)
app/test/features/households/…                  # switcher/switch/rename widget+cubit tests (new); update 1.6 routing tests
```
- One class per concern (SRP); no abbreviations. Reuse `shared` ids/envelope, the identity ACL ports, and the 1.6 client patterns — do **not** duplicate `MemberId`/`HouseholdId`/`AggregateVersion`/`StreamId`/`AuthenticatedCaller`/`CreateHouseholdCubit` shapes.

### Testing standards
[Source: CLAUDE.md §6; ARCHITECTURE-SPINE Testing convention (line 146)]
- **Pyramid base stays fast & infra-free:** the `Household` rename invariant (incl. the Admin-only rejection) and the `RenameHouseholdHandler` are proven with **pure unit tests** (in-memory `EventStore` + in-memory ACL). Only the projector/codec round-trip + the PATCH-through-to-read-model path use **Testcontainers**; the REST endpoint uses MockMvc + `spring-security-test` `jwt()`.
- **CQRS coverage:** test the **command** for the event it emits (`HouseholdRenamed` with the new name) and the state change (subsequent `name()` reflects it), and that a no-change rename emits **nothing**; the **query** side is unchanged, but assert the projector folds `HouseholdRenamed` so the read model name updates.
- **Behavior, not structure:** full-sentence names (examples in each task). Assert observable outcomes (events emitted, HTTP status + code, read-model name, header/switcher/home text), not internals.
- **DSGVO explicit:** `HouseholdRenamed` and the read model carry **no** PII; the role map is transient aggregate state, never persisted. `NoPersistedPersonalDataTest` must stay green (no new PII column). **Synthetic data only** — fake households/members, fake UUIDs.
- **AC4 proof:** a synthetic `PARTICIPANT` `MemberJoined` in a domain test — do not wait for Epic 4's invite flow to cover the Admin-only invariant.
- **Keep green:** backend 129 + client 128 stay passing; update the 1.6 routing tests that the shell now wraps and log them.

### References
- [Source: epics.md#Story 1.7: Switch, select & rename households] (lines 363–381) — user story + the three ACs (AC4 is the domain-enforcement of AC3's "Given an Admin")
- [Source: epics.md] FR1 (line 153); Epic 4 owns role governance/delete (line ~381 note) — the deferral boundary
- [Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md] AD-1 (64), AD-2 (70), AD-4 (82), AD-5 (88), AD-6 (94), AD-8 (106), AD-10 (118), AD-11 (124); Consistency Conventions (132–146)
- [Source: ux-designs/ux-sgart-2026-08-20/EXPERIENCE.md] Household switcher (lines 87–91), persistent header (48–51); [.working/ia.md] header + switcher map (lines 22–37)
- [Source: specs/spec-sgart/glossary.md] — Household, Member, MemberId, HouseholdRole {Admin, Participant}, Identity ACL (binding names)
- [Source: implementation-artifacts/1-6-create-a-household-first-run-routing.md] — the aggregate/event-store/projector/read-model/query/REST slice this story extends; Clarification 6 ("switcher/rename is 1.7"); review patches P1/P3/P4/P5/P7 (the client patterns to mirror); the eager-boot guards to preserve
- [Source: implementation-artifacts/1-4-…md] — `ResolveMemberIdentity`/`NotAMemberException`, `MemberMapping`, `AuthenticatedCaller` sub-only seam, the client HTTP/`AppError` patterns
- [Source: backend/src/main/java/de/sgart/collaboration/{domain/Household,domain/HouseholdName,domain/MemberJoined,domain/HouseholdRole,application/CreateHouseholdHandler,adapter/in/HouseholdController,adapter/out/HouseholdReadModelProjector}.java] — the exact code to extend
- [Source: CLAUDE.md] — Clean Code, no-abbreviations naming, DDD (business rules in the domain), CQRS, TDD + DSGVO testing
- [Source: memory `flutter-test-local`, `git-workflow`, `language-policy`, `model-preferences`, `bmad-flow-state`] — run tests locally; direct-to-`main`; English keys/German values; Sonnet 5 for impl; resume point

## Clarifications (LOCKED by Timo 2026-08-23)

**A. Rename authorization lives in the domain (Admin-only), proven by a synthetic unit test now.**
✅ **LOCKED: YES** (recommended default). The `Household` aggregate tracks member roles (via `apply(MemberJoined)`) and `rename(...)` rejects a non-Admin — a real domain invariant, unit-tested with a synthetic `PARTICIPANT` even though the running app can't yet produce one (invites are Epic 4). *Alternative (rejected):* enforce only in the controller / defer entirely to Epic 4 — puts a business rule outside the domain (violates CLAUDE.md §3) and leaves AC4 unproven.

**B. Persist the last-active household on-device so a relaunch returns to it.**
✅ **LOCKED: YES — persist locally** (Timo chose this over the session-scoped default; Rita switches households most and is the least tech-comfortable, so returning her to where she left off matters). Store the last-active `householdId` locally (`shared_preferences`) behind a small `ActiveHouseholdStore` interface so the cubit depends on an abstraction and tests use a fake (CLAUDE.md §6). On launch, after fetching the caller's households: **if** a stored last-active id is present **and** still in the fetched list → enter the shell with it (skip the ≥2 selection screen); **else** fall back to 1.6's routing (0 → choice · 1 → straight in · ≥2 → selection). Writing happens whenever the active household changes (initial entry, switch, create). **DSGVO:** the stored id references household membership (personal data), so **clear it on sign-out** (alongside `AuthCubit`'s session teardown) and it is covered by AD-7's "purge device/offline caches" on erasure — a fresh sign-in on the same device must never inherit the previous person's active household. Invalidation is handled by the "still in the fetched list" check (a household you left/were removed from silently falls back to routing). *Alternative (rejected):* session-scoped only — simpler but re-routes Rita through selection on every launch.

**C. No role in the client summary / no client-side role gating in 1.7 (deferred to Epic 4).**
✅ **LOCKED: YES** (recommended default). `ListMyHouseholds`/`HouseholdSummary` stay `{householdId, name}`; rename is offered to the current member and the backend enforces Admin-only. Every member is currently an Admin, so no one sees a rename they'd be denied. Epic 4 (role governance) adds role to the summary and gates the UI. *Alternative (rejected):* add a role read-model column + role in the summary now — pre-empts Epic 4's governance design for zero current benefit (YAGNI).

**D. Rename transport: online `PATCH /api/v1/households/{id}` with load-then-append; offline is Epic 5.**
✅ **LOCKED: YES** (recommended default). `PATCH {name, commandId}`; the handler loads the stream and appends under the loaded expected-version; the client reuses one `commandId` per rename intent (idempotent retry, AD-8). A concurrent rename loses with 409 `concurrency.staleVersion`. *Alternative (rejected):* client-supplied `basedOnVersion` + offline queue now — that is Epic 5's offline-resilience scope.

**E. Rename entry lives in the switcher sheet („Haushalt umbenennen"); the full „Haushalt verwalten" hub is Epic 4.**
✅ **LOCKED: YES** (recommended default). The switcher hosts switch · „Haushalt umbenennen" (active household) · „+ Neuen Haushalt erstellen". Members/invites/roles/stores („Haushalt verwalten") are Epic 4/1.8. *Alternative (rejected):* build the manage hub now — out of scope (Epic 4).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Opus 4.8) via bmad-dev-story.

### Debug Log References

- Backend full suite: **148 tests green** (baseline 129 + 19 new). `./gradlew test`.
- Client full suite: **143 tests green** (baseline 128 + 15 new). `flutter test`.
- `flutter analyze`: **No issues found**.

### Completion Notes List

- **All 4 ACs satisfied.** AC1/AC2 (persistent header + switch) via the Flutter shell + switcher sheet; AC3 (Admin rename updates everywhere) via `HouseholdRenamed` → projector → read model and `applyActiveHouseholdRename` on the client; AC4 (domain-enforced Admin-only) via `Household.rename` throwing `RenameNotPermittedException`, proven by a synthetic `PARTICIPANT` unit test (`aParticipantCannotRenameTheHousehold`) since invites are Epic 4.
- **All 5 Clarifications honored:** A (domain Admin-only, synthetic test), B (on-device `ActiveHouseholdStore` behind an interface, restore-on-launch + clear-on-sign-out), C (no client role gating; summary stays `{householdId, name}`), D (online PATCH load-then-append under the loaded version), E (rename lives in the switcher; no „Haushalt verwalten" hub).
- **DRY refactor (Boy Scout):** extracted the raw-name/commandId translators into `CommandFieldTranslations`, reused by both `CreateHouseholdHandler` and `RenameHouseholdHandler`.
- **Layering:** added a `String`-taking `ResolveMemberIdentity.resolve` overload so `collaboration.application` never imports `identity.domain` (AD-2). The domain exception is translated to `RenameNotPermittedApplicationException` (`household.renameNotPermitted`) in the application layer because `adapter.in`'s `WriteErrorAdvice` may not reach into the domain (hexagonal rule).
- **No new migration / no new PII** — the rename reuses the existing name column via the already-`ON CONFLICT DO UPDATE` upsert; the role map is transient aggregate state.
- **1.6 guards preserved:** no new eager infrastructure; the shell/switcher/rename reuse the existing `EventStore`/projector/ACL. Pushed routes (rename, „+ Neuen Haushalt") re-provide `HouseholdsApi`/`HouseholdsCubit` (P1). One `commandId` per rename intent reused across retries (P3).

### File List

**Backend — new:**
- `backend/src/main/java/de/sgart/collaboration/domain/HouseholdRenamed.java`
- `backend/src/main/java/de/sgart/collaboration/domain/RenameNotPermittedException.java`
- `backend/src/main/java/de/sgart/collaboration/application/RenameHousehold.java`
- `backend/src/main/java/de/sgart/collaboration/application/RenameHouseholdHandler.java`
- `backend/src/main/java/de/sgart/collaboration/application/RenameNotPermittedApplicationException.java`
- `backend/src/main/java/de/sgart/collaboration/application/CommandFieldTranslations.java`
- `backend/src/test/java/de/sgart/collaboration/application/RenameHouseholdHandlerTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/out/DomainEventJsonCodecTest.java`

**Backend — modified:**
- `backend/src/main/java/de/sgart/collaboration/domain/Household.java` (role map in `apply(MemberJoined)`; `apply(HouseholdRenamed)`; `rename(...)`)
- `backend/src/main/java/de/sgart/collaboration/application/CreateHouseholdHandler.java` (use `CommandFieldTranslations`)
- `backend/src/main/java/de/sgart/identity/application/ResolveMemberIdentity.java` (`String`-taking overload)
- `backend/src/main/java/de/sgart/collaboration/adapter/in/HouseholdController.java` (`PATCH`)
- `backend/src/main/java/de/sgart/collaboration/adapter/in/WriteErrorAdvice.java` (`household.renameNotPermitted` → 403)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/HouseholdReadModelProjector.java` (`case HouseholdRenamed`)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/DomainEventJsonCodec.java` (`HouseholdRenamed` type tag)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/HouseholdApplicationConfig.java` (`RenameHouseholdHandler` bean)
- `backend/src/test/java/de/sgart/collaboration/domain/HouseholdTest.java` (rename tests)
- `backend/src/test/java/de/sgart/collaboration/adapter/in/HouseholdControllerTest.java` (PATCH tests)
- `backend/src/test/java/de/sgart/collaboration/adapter/out/HouseholdReadModelProjectorTest.java` (rename projection test)

**Client — new:**
- `app/lib/features/households/data/active_household_store.dart`
- `app/lib/features/households/presentation/household_shell.dart`
- `app/lib/features/households/presentation/household_switcher_sheet.dart`
- `app/lib/features/households/presentation/rename_household_cubit.dart`
- `app/lib/features/households/presentation/rename_household_state.dart`
- `app/lib/features/households/presentation/rename_household_page.dart`
- `app/test/features/households/presentation/household_shell_test.dart`
- `app/test/features/households/presentation/rename_household_cubit_test.dart`
- `app/test/features/households/presentation/rename_household_page_test.dart`

**Client — modified:**
- `app/pubspec.yaml` (`shared_preferences`)
- `app/lib/features/households/data/households_api.dart` (`renameHousehold`)
- `app/lib/shared/http/authenticated_http_client.dart` (`patchJson`)
- `app/lib/features/households/presentation/households_cubit.dart` (store, `shell`, `switchActive`, `applyActiveHouseholdRename`, restore-on-launch)
- `app/lib/features/households/presentation/households_state.dart` (`home` → `shell` + retained list)
- `app/lib/features/households/presentation/first_run_router.dart` (build store; `shell` branch)
- `app/lib/features/households/presentation/household_home_page.dart` (body-only content)
- `app/lib/features/auth/presentation/auth_cubit.dart` (clear store on sign-out)
- `app/lib/features/auth/presentation/auth_gate.dart` (inject store)
- `app/lib/shared/widgets/sgart_app_bar.dart` (tappable title chip)
- `app/lib/shared/errors/error_message_resolver.dart` (`household.renameNotPermitted` copy)
- `app/lib/l10n/app_de.arb` (switcher/rename German keys) + regenerated `app/lib/l10n/gen/*`
- `app/test/support/fake_households_dependencies.dart` (`renameHousehold`, `FakeActiveHouseholdStore`)
- `app/test/features/households/presentation/households_cubit_test.dart`
- `app/test/features/households/presentation/first_run_router_test.dart`
- `app/test/features/households/presentation/create_household_page_test.dart`
- `app/test/features/households/presentation/create_or_await_choice_page_test.dart`
- `app/test/features/auth/presentation/auth_cubit_test.dart` (+ `signOutClearsTheStoredActiveHousehold`)
- `app/test/features/auth/presentation/auth_gate_body_test.dart`
- `app/test/features/auth/presentation/sign_in_page_test.dart`
- `app/test/shared/errors/error_message_resolver_test.dart`

## Change Log

| Date | Change |
| --- | --- |
| 2026-08-23 | Story created via bmad-create-story. Additive UX + one new write on the 1.6 slice: `HouseholdRenamed` event + Admin-only `rename` on the `Household` aggregate (role now tracked via `apply(MemberJoined)`), `RenameHousehold` command/handler resolving the caller's `MemberId` through the Identity ACL, `PATCH /api/v1/households/{id}` + error advice, projector fold → name read model, and the Flutter **persistent shell + household switcher sheet** (switch with brief confirmation, „Aktiv" marker) + **rename flow** (prefilled, one `commandId` per intent, updates the name everywhere). Five load-bearing decisions surfaced as Clarifications A–E. Status → ready-for-dev. |
| 2026-08-23 | Clarifications LOCKED by Timo. A, C, D, E locked to the recommended defaults (domain-enforced Admin-only proven by a synthetic PARTICIPANT test; no client role gating → Epic 4; online PATCH load-then-append, offline → Epic 5; rename in the switcher, „Haushalt verwalten" → Epic 4). **B changed from the recommended default:** the active household is now **persisted on-device** (`shared_preferences` behind an `ActiveHouseholdStore` interface) so a relaunch returns to the last-active household — Timo chose this over session-scoped for Rita's multi-household flow. Task 5 + Dev Notes + Project Structure updated: restore-on-launch (with a still-in-list invalidation check), write-on-change, and **clear-on-sign-out** (DSGVO / AD-7); one new client dependency (`shared_preferences`). Ready for `bmad-dev-story` (Sonnet 5 per model-preferences). |
| 2026-08-24 | **Code-reviewed & done (bmad-code-review, Opus 4.8; adversarial Blind Hunter · Edge Case Hunter · Acceptance Auditor).** Auditor verified all 4 ACs, all 5 clarifications, and AD-2/4/5/6/8. Suites re-run green by the reviewer. **8 patches applied:** (1) rename `commandId` keyed to the payload — a fresh id when the name changes so an edited retry can't dedupe against a lost-response append; (2) guarded `ActiveHouseholdStore` read/write in `HouseholdsCubit` so a device-storage error falls back to routing / best-effort persist instead of the failure screen; (3) `AuthCubit.signOut` local teardown wrapped so it always reaches the gate; (4) switcher chip given an accessible label + tooltip, wiring the previously-dead `householdsSwitcherChipTooltip`; (5) malformed `householdId` in `PATCH` now a fail-fast 400 (`command.householdIdInvalid`) via `CommandFieldTranslations.toHouseholdId`, not a 500; (6) new ArchUnit rule bans `collaboration.application` → `identity.domain` (AD-2 guardrail); (7) rename success uses a single `pop()` instead of `popUntil(isFirst)`; (8) `applyActiveHouseholdRename` → `applyHouseholdRename(householdId, name)`, keyed on the renamed id. New regression tests for (1), (4), (5) + the AD-2 rule; the rename-page test now mirrors the pushed-over-shell flow. 1 finding deferred (client 409 conflict copy → Epic 5, logged in `deferred-work.md`), 2 dismissed. Backend **150 green** (+2), client **145 green** (+2), `flutter analyze` clean. Status → done. |
| 2026-08-23 | **Implemented (bmad-dev-story, Opus 4.8).** Backend: `HouseholdRenamed` event + Admin-only `Household.rename` (role now tracked in `apply(MemberJoined)`), `RenameHousehold` command/handler resolving the caller's `MemberId` via a new `String`-taking `ResolveMemberIdentity` overload, `PATCH /api/v1/households/{id}` → 204 with `household.renameNotPermitted` → 403 advice, projector fold + `HouseholdRenamed` JSON type tag → name read model. DRY: extracted `CommandFieldTranslations` (shared by both handlers). Client: reworked `HouseholdsCubit`/state into a persistent **shell** (`home` → `shell`, retained list + `activeHousehold`, `switchActive`, restore-from-`ActiveHouseholdStore` on launch, clear-on-sign-out in `AuthCubit`), `HouseholdShell` (switcher chip via `SgartAppBar.onTitleTap` + status placeholder) + `HouseholdSwitcherSheet` (switch w/ „Zu {name} gewechselt" `SnackBar`, „Aktiv" badge, rename + create entries), rename cubit/state/page (one `commandId` per intent, updates the name everywhere), `patchJson`, German l10n keys, `household.renameNotPermitted` copy. **1.6 routing tests updated** where the shell now wraps `HouseholdHomePage`/selection; `AuthCubit` construction updated for the new store dependency. Backend **148 green** (+19), client **143 green** (+15), `flutter analyze` clean. Status → review. |

## Review Findings

### Review Findings (code review 2026-08-24 — adversarial: Blind Hunter · Edge Case Hunter · Acceptance Auditor, all Opus 4.8)

Suites verified green independently by the reviewer: **backend 148**, **client 143**, `flutter analyze` clean. All 4 ACs + all 5 LOCKED clarifications + AD-2/4/5/6/8 verified by the Acceptance Auditor. Result: **0 decision-needed, 8 patch, 1 defer, 2 dismissed**.

_Patch findings (unchecked = open):_

- [x] [Review][Patch] Reused `commandId` diverges client from server when the name is edited after a lost-response failure (medium) [app/lib/features/households/presentation/rename_household_cubit.dart:15] — one `commandId` is minted per form instance and reused for every `submit(name)`. If the first append reached the server (`HouseholdRenamed("B")`) but the response was lost (client shows failure), the user edits to "C" and resubmits with the **same** `commandId`; `EventStore.append` dedupes per `(stream, commandId)` as a **silent no-op success** (verified in `EventStore.java`), returns 204, and the client optimistically shows "C" while the server keeps "B" until the next refresh. Fix: regenerate the `commandId` when the trimmed name differs from the last attempt (key idempotency to the payload, not the form instance).
- [x] [Review][Patch] Unguarded `ActiveHouseholdStore` read/write turns a storage error into a hard failure screen / silent non-persist (medium) [app/lib/features/households/presentation/households_cubit.dart:42] — `readActive()` (in `_routeForHouseholds`) and `writeActive()` (in `_enterShell`) run inside `bootstrap`'s try with no local guard, so a device-storage error emits `HouseholdsState.failure` even though `listMyHouseholds()` succeeded (retry re-hits the same error → stuck). On `switchActive` the un-awaited `writeActive` throw becomes an unhandled async error and the switch is never persisted (next launch restores the old household) while the UI shows the confirmation. Fix: guard the store ops — fall back to normal routing on read failure; log/ignore write failure without failing the shell.
- [x] [Review][Patch] `signOut` can abort before reaching the unauthenticated gate if `store.clear()` throws (medium) [app/lib/features/auth/presentation/auth_cubit.dart:69] — the new `await _activeHouseholdStore.clear()` sits before `_tokens = null` / `_safeEmit(unauthenticated)` with no try/catch, so a storage failure leaves the UI on the authenticated shell with a half-torn-down session — contradicting the method's own "Local sign-out must still succeed" guarantee (DSGVO / AD-7). Fix: wrap the local teardown so the state transition always emits (mirror the existing `endSession` try/catch).
- [x] [Review][Patch] Switcher chip has no accessible label and its authored string is dead (medium) [app/lib/shared/widgets/sgart_app_bar.dart:43] — `_switcherChip` is a bare `InkWell` + `arrow_drop_down` with no `Tooltip`/`Semantics`, so a screen-reader user hears only the household name with no hint it switches households. The string authored for it, `householdsSwitcherChipTooltip` ("Haushalt wechseln"), is referenced only in `app_de.arb` (`app/lib/l10n/app_de.arb:174`), never in `lib/`. Fix: wrap the chip in `Semantics(button: true, label: …)`/`Tooltip` using the existing string.
- [x] [Review][Patch] Malformed `householdId` in the PATCH path yields 500 instead of a clean 4xx (low) [backend/src/main/java/de/sgart/collaboration/application/RenameHouseholdHandler.java:50] — `HouseholdId.fromString(rawHouseholdId)` → `UUID.fromString` throws a plain `IllegalArgumentException` that `WriteErrorAdvice` does not map, so `PATCH /api/v1/households/not-a-uuid` → 500 (Fail Fast, CLAUDE.md §1). `commandId`/name are already fail-fast-translated to 400; the path id is not. Fix: translate the path id fail-fast to a mapped 400 (or 404) + regression test. (Client never sends a bad id, so low user impact.)
- [x] [Review][Patch] No ArchUnit rule guards `collaboration.application` → `identity.domain` (low) [backend/src/test/java/.../HexagonalArchitectureTest.java] — the code honors AD-2 (the `String`-taking `ResolveMemberIdentity` overload keeps `KeycloakUserId` inside Identity), but the existing hexagonal test only constrains `*.domain` ↔ `*.domain`; nothing would catch a future `collaboration.application → identity.domain` import — the exact boundary the overload exists to protect. Fix: add `noClasses().that().resideInAPackage("..collaboration.application..").should().dependOnClassesThat().resideInAPackage("..identity.domain..")`.
- [x] [Review][Patch] Rename success uses `popUntil(isFirst)` — broader than needed, inconsistent with the switch flow (low) [app/lib/features/households/presentation/rename_household_page.dart:64] — correct today (rename sits directly above the shell), but `popUntil((r) => r.isFirst)` will tear down any intermediate routes a future create→rename chain pushes; the sibling `_switchTo` uses a single `pop()`. Fix: single `Navigator.pop()`.
- [x] [Review][Patch] `applyActiveHouseholdRename` ignores which household was renamed (low) [app/lib/features/households/presentation/households_cubit.dart:75] — takes only `newName` and rewrites `state.activeHousehold` unconditionally; correct only because rename is offered solely for the active household. A future flow renaming a non-active household would rename the wrong local entry. Fix: pass the renamed `householdId` (the page already holds it) and update by id.

_Deferred:_

- [x] [Review][Defer] Concurrent-rename 409 (`concurrency.staleVersion`) is unmapped on the client → generic error (low) [app/lib/shared/errors/error_message_resolver.dart] — deferred: unreachable in 1.7 (every household has exactly one member until Epic 4 invites; two concurrent renamers cannot exist), and Epic 5 owns offline/conflict UX. Mirrored in `deferred-work.md`.

_Dismissed as noise:_

- `RenameHousehold` record built then immediately destructured in the handler (KISS nit) — the `Command` record is spec-mandated (Task 2, CQRS envelope); building it in the handler is harmless indirection, not a defect.
- Dual trim authority (Dart `.trim()` + `HouseholdName.trim()`) — backend is authoritative and always stores the trimmed name; divergence only under exotic Unicode-whitespace differences between Dart and Java. Negligible / YAGNI.
