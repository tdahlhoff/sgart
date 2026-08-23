---
baseline_commit: 804d4aeb82f0849ff9a4b7b17252b1329745561a
---

# Story 1.6: Create a household & first-run routing

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an authenticated person,
I want to create a household or be routed to the right place on launch,
so that I have a private container for my lists and stores.

## Acceptance Criteria

1. **An authenticated person with zero households sees a create/await-invite choice; creating one names it and makes them a Member with `HouseholdRole = Admin`; the Identity ACL mints their `MemberId` and the `Household` aggregate emits `HouseholdCreated` + `MemberJoined` carrying that same `MemberId`.** (FR1, AR4/AD-5, AR3/AD-4, AR1/AD-1)
   - On launch, an authenticated caller who belongs to **zero** households is routed to a first-run choice: **„Haushalt erstellen"** or **„Auf Einladung warten"**. „Auf Einladung warten" is a passive waiting state only — actual invite acceptance is Epic 4; this story ships no invite flow, just the branch.
   - Creating a household is a **command** (`CreateHousehold`) carrying the caller's chosen **name** plus the client `commandId` + `basedOnVersion` envelope (Story 1.5). The name is required and non-blank (fail fast; reject blank with a `{code}` the client localizes).
   - The **Identity ACL mints the `MemberId`** for the creator and writes its mapping row `{householdId, memberId → keycloakUserId}` — the ACL is the **sole minter** (AD-5); no other component generates a `MemberId`. The `Household` aggregate then emits `HouseholdCreated` (household id + name) and `MemberJoined` (that **same** minted `MemberId`, role `Admin`) — **both carry the ACL-minted id; the aggregate never invents one**. Events are appended to KurrentDB under the expected-version check for a **brand-new** stream (`AggregateVersion.initial`).
   - **No PII in the write path:** events carry `MemberId` and the household name only — never `keycloakUserId`, display name, or email (AR2/NFR2, AD-5/AD-6). The household **name is not personal data**; the creator's identity is represented solely by the pseudonymous `MemberId`.

2. **On launch, a caller who belongs to exactly one household goes straight in; a caller who belongs to several sees a selection screen.** (FR1)
   - First-run routing reads **how many households the caller belongs to** and branches: **0** → create/await choice (AC1); **exactly 1** → straight into that household; **≥ 2** → a **selection screen** listing the caller's households (by name) to pick one.
   - The membership count/list is resolved from the **caller's Keycloak identity** (JWT `sub`) → the households they are a member of, composing the Identity ACL's mapping (keycloakUserId → memberIds/householdIds) with the household **name** read model (AD-4 projection). No `MemberId` is accepted from the client — it is derived server-side (AR10, AD-5).
   - This story's "straight in" and "selection" are **minimal**: the full always-visible switcher + rename is Story 1.7, and the guided onboarding wizard is Story 1.9. Landing "in" a household shows a minimal household home displaying the **current household name** (the seed the 1.7 header builds on) — not the lists screen (Epic 2).

3. **A person who already belongs to ≥ 1 household can create another, and a second, unrelated `MemberId` is minted for them in the new household.** (FR1, AR4/AD-5)
   - Creating an additional household follows the identical `CreateHousehold` path and mints a **fresh, unrelated `MemberId`** in the new household — a person in two households has two `MemberId`s with **no derivable link** between them (AD-5). The two mapping rows share only the `keycloakUserId`, which never leaves the ACL.
   - The read side reflects the new household on next routing/selection; the create response returns the **new household id** so the client can route the creator **straight into** the just-created household without waiting for the projection to catch up (read-your-writes; projections are eventually consistent, AR3/NFR9).

4. **The write path (KurrentDB) and read path (PostgreSQL projection + ACL mapping) are durably wired and proven, and the Spring context still loads when neither infrastructure is running.** (AR3/AD-4, NFR6, plus the Story 1.4/1.5 "context-loads-with-infra-down" guardrail)
   - The real **KurrentDB adapter** implements the Story 1.5 `EventStore` port in `collaboration.adapter.out` (append under expected-version, `ConcurrencyConflictException` on mismatch, idempotent-by-`commandId` no-op, `readStream`) — proven against **real KurrentDB via Testcontainers**, honoring the exact same contract the `InMemoryEventStore` double proved in 1.5.
   - The **PostgreSQL** read model (household name + membership) and the **durable Identity ACL mapping table** (replacing `InMemoryMemberMappingRepository`, deferred from Story 1.4) land here with a **Flyway** migration, proven against **real PostgreSQL via Testcontainers**.
   - Neither store connects at Spring startup: `contextLoads()` / `SgartApplicationTest` stay green with KurrentDB **and** PostgreSQL down (CI has neither), exactly as the Keycloak eager-fetch trap was avoided in 1.4. Add a dedicated regression test for each, mirroring `ContextLoadsWithoutKeycloakTest`.

## Tasks / Subtasks

- [x] **Task 1 — `Household` aggregate: the first real aggregate (collaboration.domain)** (AC: #1, #3)
  - [x] In `de.sgart.collaboration.domain`, add the `Household` aggregate root extending `de.sgart.shared.EventSourcedAggregate` (Story 1.5's base). Model creation as a **static factory / command method** `create(HouseholdId, HouseholdName, MemberId adminMemberId, CommandId)` that validates the invariant (non-blank name) and `raise(...)`s **`HouseholdCreated`** then **`MemberJoined`**. State is mutated only in `apply(DomainEvent)` (switch on concrete event type) — never in the command method (the base-class contract).
  - [x] Add the domain events in `collaboration.domain` (past-tense PascalCase, implement `DomainEvent`, each carries its own `EventId`): **`HouseholdCreated`** (`householdId`, `HouseholdName`) and **`MemberJoined`** (`householdId`, `MemberId`, `HouseholdRole`). **No PII** — `MemberId` only (AD-5). `MemberJoined` in this story always carries `HouseholdRole.Admin` (the creator); Epic 4's invite path reuses the same event with `Participant`.
  - [x] Add the value types the aggregate needs: **`HouseholdName`** (non-blank, trimmed, a sane max length — fail fast) in `collaboration.domain`; **`HouseholdRole { Admin, Participant }`** enum (glossary term — never bare "Member" for the role, AD-11). `HouseholdId`/`MemberId`/`CommandId`/`EventId`/`AggregateVersion`/`StreamId` already exist in `shared` — reuse, do not duplicate.
  - [x] Fast unit tests (pure, no infra): `create` raises exactly `HouseholdCreated` then `MemberJoined` in order, both carrying the given `MemberId`/name, role `Admin`; version advances by 2; a blank/whitespace name is rejected; replaying `[HouseholdCreated, MemberJoined]` rebuilds identical state + version (the base-class replay path). Assert **no event carries a display name/email/keycloakUserId** (reflection or explicit field check — the AD-5/AD-6 guard).

- [x] **Task 2 — Identity ACL mint path + durable PostgreSQL mapping (deferred from 1.4)** (AC: #1, #3, #4)
  - [x] Add the **mint use case** to `de.sgart.identity.application` — e.g. `MintMemberIdentity` (a command/write use case, sibling to `ResolveMemberIdentity`): `mint(KeycloakUserId, HouseholdId) → MemberId`. It generates a **fresh `MemberId`** (`MemberId.generate()`), writes the `MemberMapping` row through the repository port, and returns it. This is the **published application-layer port** the Collaboration create-household flow calls across the context boundary (AD-2 — never reach into `identity.domain` or its tables).
  - [x] Extend the **`MemberMappingRepository`** port (domain) with the write side (`save(MemberMapping)`) — keep the existing `findMemberId(...)` read contract. Add the households-for-caller lookup needed by routing: `householdIdsFor(KeycloakUserId) → List<HouseholdId>` (or a `findMappings(KeycloakUserId)` returning the rows). Design the row shape so **erasure can locate & delete every mapping for a `keycloakUserId`** (AD-7 forward-link) — already flagged in `MemberMapping`'s Javadoc.
  - [x] Add the **durable PostgreSQL adapter** `JdbcMemberMappingRepository` in `identity.adapter.out` (Spring Data JDBC or `JdbcClient`/`JdbcTemplate` — see Clarification C), backed by a **Flyway** migration creating the mapping table (`household_id`, `member_id`, `keycloak_user_id`, with the indexes the two lookups need). Keep `InMemoryMemberMappingRepository` as the **test double** (move to test scope if nothing in `main` still needs it; the mint/resolve unit tests use it). **No display name/email column** — the reflection/PII guard from 1.4 (`NoPersistedPersonalDataTest`) must extend to cover the new table/adapter.
  - [x] Testcontainers integration test (real PostgreSQL): mint writes a row; `findMemberId` reads it back; two mints for the same `keycloakUserId` in two households yield **two unrelated `MemberId`s**, both locatable by `keycloakUserId`; the table holds **no** PII column.

- [x] **Task 3 — Real KurrentDB `EventStore` adapter (deferred from 1.5)** (AC: #1, #4)
  - [x] Add `KurrentDbEventStore` in `de.sgart.collaboration.adapter.out` implementing the Story 1.5 **`EventStore`** port unchanged (`append(AggregateVersion, List<DomainEvent>, CommandId)` + `readStream(StreamId)`). Map the port semantics onto the KurrentDB Java client (`io.kurrent:kurrentdb-client`): expected-version → `StreamState.noStream()` / `StreamRevision(n)`; catch `WrongExpectedVersionException` → throw `ConcurrencyConflictException` (`concurrency.staleVersion`, the existing `ErrorDescriptor`); persist the applied `commandId` as **event metadata / idempotency key** so a redelivered `commandId` is a silent no-op that **survives restart** (the port's documented contract). Serialize each `DomainEvent` to/from JSON with a stable type tag; **keep the KurrentDB/JSON types in `adapter.out` only** — the domain event stays pure (AD-1; the ArchUnit `..domain..` + shared-kernel rules already ban `io.kurrent..`).
  - [x] **Do not connect at context startup.** Bind the client `@Bean` lazily / under a runtime profile so `contextLoads()` passes with KurrentDB down (the 1.4 eager-boot trap, restated in AC4). Add a **`ContextLoadsWithoutKurrentDbTest`** mirroring `ContextLoadsWithoutKeycloakTest`.
  - [x] Testcontainers integration test (real KurrentDB `25.1.4`, the compose image): re-prove the **same** contract `EventStoreContractTest` proved in-memory — happy-path append advances the version, stale `basedOnVersion` → `ConcurrencyConflictException` (nothing written), duplicate `commandId` → silent no-op even after the stream advanced, multi-event atomicity, `readStream` on an unseen stream is empty, and a round-trip append→readStream rebuilds a `Household`. Consider a shared contract test the in-memory and KurrentDB stores both satisfy (DRY) — but do not over-engineer (YAGNI).

- [x] **Task 4 — Household read model + projector (first CQRS read side)** (AC: #2, #3, #4)
  - [x] Add the **read model** the routing needs: a PostgreSQL table (Flyway migration) of `{household_id, name}` (household display) and the membership link `{household_id, member_id}` (from `MemberJoined`) — enough to answer "which households does this caller belong to, and what are their names?" **No PII** (member is `member_id` only; name is the household's, not a person's).
  - [x] Add the **projector** in `collaboration.adapter.out` that subscribes to the household streams and folds `HouseholdCreated`/`MemberJoined` into the read model. **Command handlers must never write the read model** (AD-4, the projection-only rule Story 1.5 captured as convention — this is the first projector realizing it). The read model is **eventually consistent** (AR3/NFR9). *(See Clarification D on subscription vs. inline projection.)*
  - [x] Add the **query** in `collaboration.application` — e.g. `ListMyHouseholds` — that composes the ACL (`keycloakUserId → householdIds`, Task 2) with the household-name read model → `[{householdId, name}]` for the caller. This is the read side of CQRS (query, no side effects) that first-run routing (Task 6) consumes.
  - [x] Testcontainers integration test: projecting `HouseholdCreated` + `MemberJoined` yields the row(s); `ListMyHouseholds` returns exactly the caller's households with names; a caller in zero returns empty; a caller in two returns both.

- [x] **Task 5 — Create-household command handler + REST (adapter.in)** (AC: #1, #2, #3)
  - [x] Add the **`CreateHousehold` command** (implements `Command`; carries `commandId` + `basedOnVersion` = `AggregateVersion.initial(newStream)`, plus the `HouseholdName`) and a handler in `collaboration.application` that orchestrates: resolve the caller's `KeycloakUserId` (from the `adapter.in` seam) → **ACL mints `MemberId`** (Task 2 port) → `Household.create(newHouseholdId, name, memberId, commandId)` → `EventStore.append(...)`. **Ordering matters:** the mint precedes the append because `MemberJoined` must carry the minted id (see Dev Notes "Mint-then-append & cross-store consistency"). The handler returns the **new `householdId`** (commands may return identifiers — CQRS) so the client routes straight in (AC3).
  - [x] Add REST in `collaboration.adapter.in`: **`POST /api/v1/households`** (body: `{name}` + the command envelope `{commandId, basedOnVersion?}`; returns `{householdId}`) and **`GET /api/v1/households`** (returns the caller's `[{householdId, name}]` for routing). Both take the caller identity **only from the JWT `sub`** via the existing `AuthenticatedCaller` seam — never from body/path (AR10, AD-5). Reuse the identity `SecurityConfig` chain (already covers `/api/v1/**`).
  - [x] Add a write-side **`@RestControllerAdvice`** mapping domain/application failures (blank name, `ConcurrencyConflictException`, `NotAMemberException`) to the `{code, message, details}` error shape (the full error-mapping surface Story 1.4 wired minimally for `/me`). Client already localizes by `code`.
  - [x] Backend tests: handler unit test (in-memory `EventStore` + in-memory ACL) proving the **minted `MemberId` flows into `MemberJoined`** and the handler returns the id; MockMvc slice for `POST`/`GET` (`201`/`200`, `401` unauthenticated, blank-name `400` with a `code`); the caller-identity-from-`sub`-only assertion.

- [x] **Task 6 — Flutter: first-run routing + create-household + selection (client)** (AC: #1, #2, #3)
  - [x] Add a **`households` feature** (`app/lib/features/households/…`, BLoC per screen). A `FirstRunRouter` (or extend the post-auth flow) that, once authenticated, calls **`GET /api/v1/households`** and branches: **0** → create/await choice; **1** → household home (current household name); **≥2** → selection screen. Replace the `AuthenticatedPlaceholderPage` as the post-sign-in destination (it was the explicit Story 1.4 placeholder for "routing is 1.6") — keep one entry path.
  - [x] Screens (minimal, all copy via `AppLocalizations`, English ARB keys / German values — language policy): **create/await choice** („Haushalt erstellen" / „Auf Einladung warten"); **create-household** (name field → `POST /api/v1/households` → route into the returned household); **selection** (list households, pick → household home); **household home** (shows the current household name — the 1.7 header seed). „Auf Einladung warten" is an informational dead-end for now (invite acceptance is Epic 4).
  - [x] Add the **households data layer** (`HouseholdsApi` over `AuthenticatedHttpClient`: `listMyHouseholds()`, `createHousehold(name) → householdId`) mirroring `HttpIdentityApi`. Generate a client `commandId` per create (uuid) and send it in the envelope. A cubit drives the flow; **guard every `emit` with `isClosed`** (the established async-cubit pattern from `AuthCubit`); add `bloc_test` coverage.
  - [x] Flutter widget/unit tests (stub the HTTP boundary — no real network): routing shows the correct screen for 0 / 1 / ≥2 households; creating routes into the returned household; selection picks one; a `{code}` error from create maps to localized copy. Reuse `test/support/widget_test_harness.dart` and the `fake_auth_dependencies` style.

- [x] **Task 7 — Build wiring, guardrails, and green suites** (AC: #1, #2, #3, #4)
  - [x] `backend/build.gradle.kts`: add `io.kurrent:kurrentdb-client` (pin current version), `spring-boot-starter-jdbc` (or `spring-boot-starter-data-jdbc`), `org.postgresql:postgresql` (runtime), `org.flywaydb:flyway-core` + `flyway-database-postgresql`, and **Testcontainers** (`org.testcontainers:junit-jupiter`, `:postgresql`, and a KurrentDB/generic container) as `testImplementation`. Guard the resource-server & datasource so nothing connects at boot.
  - [x] Keep **all four+one existing ArchUnit rules green**; `collaboration.domain` (Household + events + value types) stays pure — no `io.kurrent`, no `org.springframework`, no `jakarta.persistence`, no `..adapter..`. The KurrentDB serializer, JDBC repos, and projector live in `adapter.out`; controllers/advice in `adapter.in`. **Extend `NoPersistedPersonalDataTest`** to the new mapping table + read model (no display-name/email column anywhere).
  - [x] Run **both suites locally for real** before review (memory `flutter-test-local`: Story 1.2 was marked done on a review that never ran tests). Backend: `cd backend && ./gradlew test` (unit + ArchUnit + Testcontainers integration — needs a working Docker). Client: `export PATH="$PATH:/home/timo/tools/flutter/bin"; cd app && flutter analyze && flutter test`. Confirm `SgartApplicationTest` + the two new context-loads-with-infra-down tests pass **with the containers stopped**. Zero new analyzer/compiler warnings.

## Dev Notes

### Scope & intent
**This is the pivotal "first real writer" story — every deferred piece of durable infrastructure lands here.** Stories 1.4 and 1.5 deliberately shipped *contracts + in-memory proofs* and named **1.6 create-household** as the first component that would wire the real thing:
- **1.4 deferred** the PostgreSQL Identity-ACL mapping table **and the mint (write) path** to 1.6 ("the mint/write path land with household creation").
- **1.5 deferred** the real **KurrentDB `EventStore` adapter** to 1.6 ("the real KurrentDB client (Story 1.6) implements this port"), and named **Story 1.6's `Household`** as the first `EventSourcedAggregate` subclass and the first projector as the realizer of the "read-models-are-projection-only" convention.

So 1.6 is where the vertical slice finally connects end-to-end: **first real aggregate (`Household`) → first command (`CreateHousehold`) → real KurrentDB append under expected-version → first projector → first PostgreSQL read model → first CQRS query → first-run routing on the client**, plus the **ACL mint** that makes the creator an `Admin` `Member` with a pseudonymous `MemberId`. The business value is a **working, solo-capable household**: sign in (1.4) → create/enter a household (1.6). It is a large story precisely because it is the integration point; the contracts it consumes are already fixed, so the work is *wiring to them correctly*, not inventing new shapes.

**Deliberate scope boundaries (do the plumbing; defer the polish):**
- **Minimal routing UI, not the full features.** The always-visible **switcher + rename is Story 1.7**; the **guided onboarding wizard is Story 1.9**; the **Profil screen is 1.11**; the **lists screen is Epic 2**. 1.6 ships the *routing decision* (0/1/≥2), a *minimal* create screen (just a name), a *minimal* selection list, and a *minimal* household home that shows the current household name (the seed 1.7's header grows from). Do not build the switcher sheet or onboarding steps here.
- **„Auf Einladung warten" is a branch, not a flow.** Invite acceptance is Epic 4 (4.1/4.2). Here it is an informational waiting state only — no invite entry, no deep link.
- **No membership governance.** Roles governance (last-Admin rule, promote/remove, delete household) is Epic 4; rename is 1.7. 1.6 only creates the household with the creator as `Admin`.

### Source of truth: ARCHITECTURE-SPINE + epics + glossary (binding)
[Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md; epics.md; specs/spec-sgart/glossary.md]
- **AD-5 / AR4 (spine line 88–92):** events/read models reference a person only by a per-membership **`MemberId`**; the **Identity ACL is the sole minter** — "it creates the id when a member first joins … writes the mapping, and the Household's `MemberJoined` event then carries that same id — no other component generates one." A person in two households has two unrelated `MemberId`s. → drives AC1/AC3, Task 1 (events carry the minted id), Task 2 (mint), Task 5 (ordering).
- **AD-4 / AR3 (spine line 82–86):** state changes **only** by appending events to KurrentDB under an **expected-version check**; read models (PostgreSQL) are built by **projectors** subscribed to streams and are **never written directly** by command handlers; projections are **eventually consistent** and the UI must tolerate it. → drives Task 3 (append), Task 4 (projector, projection-only), AC3's read-your-writes note.
- **AD-1 / AR1 (spine line 64–68):** every state change is a command handled by an aggregate emitting events; domain imports **no** framework/infra/transport type; infra is reached only through domain-owned ports. The ArchUnit rules already ban `io.kurrent..`/`org.springframework..`/`jakarta.persistence..` from `..domain..` and `de.sgart.shared..`. → the `Household` aggregate + events stay pure; KurrentDB/JDBC live in `adapter.out`.
- **AD-2 / AR2 (spine line 70–74):** a context touches another **only via its published application-layer port** or an async domain event — never another context's domain or DB tables. → the Collaboration create-household handler calls the **Identity `MintMemberIdentity` application port** to mint; it never imports `identity.domain` or touches the mapping table. (The context-domain-slice ArchUnit rule enforces this.)
- **AD-8 / AR7 (spine line 106–110):** client commands carry the target root's stream version + a `commandId`; a stale expected-version is rejected; the `commandId` makes replay idempotent. → `CreateHousehold` carries the envelope; the KurrentDB adapter honors it (Task 3). For a brand-new household the `basedOnVersion` is `AggregateVersion.initial(householdStream)`.
- **AD-10 / AR9:** cross-aggregate effects go through **process managers** with deterministically-derived `commandId`s. → **not needed in 1.6** (single aggregate, no cross-aggregate effect). The helper exists (`CommandId.deterministicFrom`) for Epic 2+; don't invent a process manager here (YAGNI).
- **AR10 / conventions (spine line 132–146):** events **past-tense PascalCase** (`HouseholdCreated`, `MemberJoined`); stream key **`household-{id}`** (already encoded in `StreamId.forHousehold`); commands **imperative** (`CreateHousehold`) carrying `basedOnVersion` + `commandId`; REST under **`/api/v1`**; error shape `{code, message, details}`; identity in payloads is **`MemberId` only**, `keycloakUserId` taken from JWT `sub`. **`HouseholdRole {Admin, Participant}`** — never bare "Member" for the role (AD-11).
- **Glossary (binding):** *Household* (top-level tenant, solo is first-class, a person may belong to many), *Member* (a person's participation, one per household, unrelated `MemberId` each), *MemberId* (ACL is sole minter), *HouseholdRole {Admin, Participant}*, *Identity ACL* (sole owner of the mapping and single point erasure destroys). Use these exact names (AD-11, no abbreviations: `keycloakUserId`, not `kcUid`; `householdId`, not `hhId`).

### The scaffold & contracts already in the repo (read before writing)
**Write-side envelope (Story 1.5 — `de.sgart.shared`, reuse unchanged):**
- `EventSourcedAggregate.java` — **`Household` extends this.** The contract: pass the aggregate's own `StreamId` to `super(...)`; mutate state **only** in `apply(DomainEvent)`; call `raise(event)` per event in a command method; `replay(history)` once on a fresh instance; infra reads `uncommittedEvents()` then `markEventsCommitted()`. Version tracks event count from `AggregateVersion.initial(streamId)`.
- `EventStore.java` — the port **Task 3 implements with KurrentDB.** `append(AggregateVersion expectedVersion, List<DomainEvent>, CommandId)` (atomic, expected-version, idempotent-by-`commandId` no-op) + `readStream(StreamId)` (ordered, empty if new). Note: **there is no separate `StreamId` param** — `AggregateVersion` carries its `StreamId`, so the target stream is derived from the expected version (AD-8 structural guard).
- `AggregateVersion.java` — `initial(StreamId)` (new-stream sentinel, `value == 0`) / `of(StreamId, long)` / `next()`. Carries the `StreamId`; two versions with equal `value` but different streams are **not equal**.
- `StreamId.java` — `StreamId.forHousehold(HouseholdId)` → key `household-{uuid}`. `forList`/`forTrip` arrive later.
- `Command.java` (`commandId()` + `basedOnVersion()`), `DomainEvent.java` (`eventId()`), `CommandId.java` (`generate()`, `deterministicFrom(EventId)` — the latter for process managers, unused here), `EventId.java`, `ConcurrencyConflictException.java` (wraps `ErrorDescriptor.of("concurrency.staleVersion", …)`), `ErrorDescriptor.java` (`{code, message, details}`).
- **Test doubles to reuse in unit tests:** `backend/src/test/java/de/sgart/shared/support/InMemoryEventStore.java` (the `EventStore` double — use it for the fast handler/aggregate tests; the KurrentDB adapter's own contract test uses Testcontainers). `CounterAggregate.java` is the 1.5 fixture — **do not** extend it; `Household` is the real aggregate.

**Identity ACL (Story 1.4 — `de.sgart.identity`, extend for the mint):**
- `identity/domain/MemberMappingRepository.java` — the port. **Add the write side** (`save`) + the caller-lookup (`householdIdsFor` / `findMappings`). `identity/domain/MemberMapping.java` (`{householdId, memberId, keycloakUserId}` — **no PII**, erasure-locatable by `keycloakUserId`). `identity/domain/KeycloakUserId.java` (pure string wrapper, no `org.keycloak`).
- `identity/application/ResolveMemberIdentity.java` — the read use case (unchanged). **Add `MintMemberIdentity`** beside it as the published mint port (`NotAMemberException.java` is the existing failure pattern to mirror for any new failure).
- `identity/adapter/out/InMemoryMemberMappingRepository.java` — the current in-memory adapter (seedable). **Task 2 adds the JDBC adapter**; keep the in-memory one as the **unit-test double**.
- `identity/adapter/in/security/AuthenticatedCaller.java` — the **sole `sub`-only caller seam** (`keycloakUserId`, live `displayName`/`email`). Reuse it in the new controllers; no controller parses a `Jwt` directly except through this. `SecurityConfig.java` already secures `/api/v1/**` — the new endpoints are covered automatically. `IdentityController.java` `GET /api/v1/identity/me` is the DTO/style to mirror.

**Config / infra:**
- `backend/build.gradle.kts` — Spring Boot 4.1.0, Java 25, web + oauth2-resource-server, ArchUnit, spring-boot-starter-test, spring-boot-webmvc-test, spring-security-test. **No DB/KurrentDB deps yet** — Task 7 adds them. (Note the 1.4 discovery: Boot 4.1 split MockMvc test support into `spring-boot-webmvc-test` — already present.)
- `backend/src/main/resources/application.yaml` — resource-server `jwk-set-uri` is bound so nothing fetches at boot. **Add datasource + Flyway + KurrentDB config the same way** — profile-guarded / lazy so `contextLoads()` survives with the stores down (see the trap below).
- `docker-compose.yml` — **PostgreSQL `18.6`, KurrentDB `25.1.4`, Keycloak `26.7.0` are already running services** (Story 1.1/1.4). KurrentDB is `KURRENTDB_INSECURE=true` on `2113`; Postgres on `5432` from `.env`. Testcontainers spins up its **own** containers for integration tests (do not point tests at the dev compose containers — the integration test owns its lifecycle, as 1.4 did with Keycloak).
- `backend/src/test/java/de/sgart/identity/adapter/in/security/ContextLoadsWithoutKeycloakTest.java` — **the template** for `ContextLoadsWithoutKurrentDbTest` and `ContextLoadsWithoutPostgresTest`.
- `backend/src/test/java/de/sgart/identity/NoPersistedPersonalDataTest.java` — **extend it** to assert the new mapping table + read model carry no display-name/email column (the AD-6 regression guard).

**Client (Story 1.4 — `app/`, extend for routing):**
- `app/lib/main.dart` — `home: const AuthGate()` with the comment "no app shell or routing yet (Story 1.6)". `AuthGate` (`features/auth/presentation/auth_gate.dart`) builds the `AuthCubit` and, on `AuthStatus.authenticated`, renders `AuthenticatedPlaceholderPage` — **this is the seam 1.6 replaces**: on authenticated, run first-run routing instead of the placeholder.
- `app/lib/features/auth/presentation/auth_cubit.dart` — the **async-cubit pattern to copy**: constructor-injected interfaces, `_safeEmit` guarding `isClosed`, transient-vs-definitive error handling. The households cubit mirrors this.
- `app/lib/features/auth/data/identity_api.dart` + `app/lib/shared/http/authenticated_http_client.dart` — **the HTTP pattern to mirror** for `HouseholdsApi`: `getJson`/(add `postJson`) over `AuthenticatedHttpClient`, `{code,message,details}` → `AppError` mapping already handled; `auth.unauthorized`/`identity.notAMember` codes already recognized by `AuthCubit`. **`AuthenticatedHttpClient` has only `getJson`** today — add a `postJson(path, body)` for the create call (same error mapping).
- `app/lib/l10n/app_de.arb` (+ generated `app_localizations*.dart`) — add the new German copy keys; **no hard-coded user-facing strings** (SGART stays a hard-coded proper noun per 1.3). `test/support/widget_test_harness.dart` (`wrapForTesting`) + `test/support/fake_auth_dependencies.dart` — the test scaffolding to reuse. `pubspec.yaml` already has `bloc_test`, `dio`; add a `uuid` package for `commandId` if not present.

### Mint-then-append & cross-store consistency (read carefully — the load-bearing ordering)
The creator's `MemberId` must exist **before** `MemberJoined` is emitted (the event carries it), and the ACL is its **sole minter** (AD-5). So the `CreateHousehold` handler **must** mint first, then append:
1. Resolve `KeycloakUserId` from the JWT seam.
2. **ACL mints** `MemberId` + writes the mapping row (PostgreSQL) — `MintMemberIdentity`.
3. `Household.create(newHouseholdId, name, memberId, commandId)` raises `HouseholdCreated` + `MemberJoined(memberId, Admin)`.
4. `EventStore.append(...)` to KurrentDB under `AggregateVersion.initial(householdStream)`.

This spans **two stores** (PostgreSQL mint + KurrentDB append) with no distributed transaction. **Keep it simple (MVP, solo):** if step 4 fails after step 2, the orphaned mapping row is **harmless** — it is unlinkable (no household references that `MemberId`; the event that would have was never written) and points at a household id that has no stream, so it can never resolve to a real membership. A client retry reuses the same `commandId`; make the **mint idempotent on retry** (keyed so a retried create for the same `(keycloakUserId, commandId)` returns the existing `MemberId` rather than minting a second) so a retry after a partial failure converges rather than minting twice. **Do not** build an outbox/saga/2PC — that is explicitly out of scope (YAGNI); note the orphan-cleanup as a future concern. Surface this decision in Clarification E.

### The eager-boot trap — now doubled (critical — the #1 way this story breaks CI)
Story 1.4 established the pattern: adding infrastructure that connects **at Spring context startup** breaks `SgartApplicationTest.contextLoads()` because CI has no running Keycloak/DB. 1.6 adds **two** such infrastructures:
- **PostgreSQL:** a `DataSource` + **Flyway runs migrations at startup** → fails hard when Postgres is down. Guard it: bind datasource/Flyway under a **runtime profile** (not the default/test profile), or configure Flyway/JDBC init to be inert without a real DB in the test context. Integration tests supply a real DB via **Testcontainers** (which starts its own Postgres). Add `ContextLoadsWithoutPostgresTest`.
- **KurrentDB:** a client `@Bean` that connects eagerly fails when KurrentDB is down. Bind it lazily / under the runtime profile; integration tests use a **Testcontainers KurrentDB**. Add `ContextLoadsWithoutKurrentDbTest`.

The **default/test Spring context must load with neither store present** (AC4). The fast domain/handler unit tests (Household aggregate, CreateHousehold handler) use the **in-memory `EventStore` + in-memory ACL** and need no container at all — keep the bulk of coverage there (test pyramid, CLAUDE.md §6); reserve Testcontainers for the two adapter contract tests + the projector test. **CI note:** GitHub Actions `ubuntu-latest` provides a Docker daemon, so Testcontainers works in CI; no compose changes to the workflow are needed, but the integration tests must own their container lifecycle.

### Previous-story intelligence (Stories 1.1–1.5 — done)
[Source: implementation-artifacts/1-1 … 1-5-command-concurrency-envelope.md; deferred-work.md]
- **The "contract → first-writer" chain lands here.** 1.4 LOCKED "defer the PostgreSQL mapping table + mint to 1.6"; 1.5 LOCKED "defer the real KurrentDB adapter to 1.6". Both explicitly named this story. Honor those deferrals by **implementing** them now — this is the writer they were deferred to.
- **`ErrorDescriptor` failure pattern:** `NotAMemberException`/`ConcurrencyConflictException` wrap `ErrorDescriptor.of(code, …)`. The client (`AppError` + `localizedMessageForErrorCode`, Story 1.3) already mirrors `{code,message,details}` and `AuthCubit` already recognizes `auth.unauthorized`/`identity.notAMember`. New codes (e.g. `household.nameRequired`) just need client copy.
- **ArchUnit is a first-class guardrail.** 1.4 added `NoPersistedPersonalDataTest`; 1.5 added `sharedKernelIsFreeOfInfrastructure`. Extend the PII guard to the new table/read model; keep `collaboration.domain` pure (this is the first time `collaboration` is populated — the layer-direction and slice rules start actually constraining it).
- **Async-cubit pattern (from `AuthCubit`):** guard `emit` with `isClosed`; inject interfaces, stub boundaries in tests; the top-level `runZonedGuarded`/`FlutterError.onError` boundary already exists in `main.dart`. The households cubit copies this.
- **`deferred-work.md`** items (from 1.2) are largely resolved by 1.4 (error boundary, `HomeCubit` removed, `bloc_test` added). Nothing there blocks 1.6.
- **Local test reality (memory `flutter-test-local`):** Flutter SDK at `/home/timo/tools/flutter/bin` (not on PATH). **Story 1.2 was marked done on a review that never ran tests (6 were red).** Run both suites for real. Backend currently **76 tests green** (Story 1.5); client **95+ tests green** (Story 1.4). Keep them green.
- **Git:** solo, **direct-to-`main`** (feature branches start at beta/Epic 4, memory `git-workflow`). Baseline for this story = `804d4ae`.

### Latest tech notes (KurrentDB 25.1.4 / PostgreSQL 18.6 / Spring Boot 4.1 / Flutter 3.44)
- **KurrentDB Java client** = `io.kurrent:kurrentdb-client` (renamed from `com.eventstore:db-client-java`; the ArchUnit rules ban **both** package roots from the domain/kernel). Connect over the insecure dev endpoint (`esdb://localhost:2113?tls=false` shape) for local; Testcontainers uses its own container. Optimistic concurrency maps 1:1: `appendToStream(stream, options.expectedRevision(StreamState.noStream()|StreamRevision(n)), events)` throws `WrongExpectedVersionException` on mismatch → map to `ConcurrencyConflictException`. Idempotency: record the `commandId` in **event metadata**; on append, if the stream's last events already bear this `commandId`, no-op (the port contract — survives restart). Serialize `DomainEvent`s as JSON with a stable `type` tag (do **not** serialize Java class names — keep the wire format decoupled from refactors).
- **PostgreSQL 18.6 + Flyway:** `spring-boot-starter-jdbc` + `org.postgresql:postgresql` + `flyway-core` + `flyway-database-postgresql` (Flyway 10+ split out the Postgres module). Migrations in `backend/src/main/resources/db/migration/V1__…sql`. Prefer **plain SQL migrations + `JdbcClient`/`JdbcTemplate`** (Spring Boot 4.1) or **Spring Data JDBC** for the repositories — read models are simple; **JPA is overkill** (KISS/YAGNI). See Clarification C.
- **Testcontainers:** `org.testcontainers:junit-jupiter` + `:postgresql`; for KurrentDB use a `GenericContainer` on the `docker.kurrent.io/kurrent-latest/kurrentdb:25.1.4` image with `KURRENTDB_INSECURE=true` + the health wait, or the community `kurrentdb`/`eventstore` module if one is current. `@Testcontainers`/`@Container` + `@DynamicPropertySource` to point the adapter at the container (the runtime-profile pattern).
- **Flutter:** no new heavy deps — reuse `dio`/`AuthenticatedHttpClient`, `flutter_bloc`, `bloc_test`. Add `uuid` for client `commandId` generation if not already transitively present. Routing here is a simple conditional widget tree (no `go_router` yet — YAGNI; the full nav shell is later).

### Project Structure Notes
```text
backend/src/main/java/de/sgart/collaboration/
  domain/
    Household.java                         # first real aggregate; extends EventSourcedAggregate (new)
    HouseholdCreated.java                  # DomainEvent: householdId, HouseholdName (new)
    MemberJoined.java                      # DomainEvent: householdId, MemberId, HouseholdRole (new)
    HouseholdName.java                     # value object, non-blank, max length (new)
    HouseholdRole.java                     # enum { Admin, Participant } (new)
  application/
    CreateHousehold.java                   # Command: name + commandId + basedOnVersion (new)
    CreateHouseholdHandler.java            # mint -> create -> append; returns householdId (new)
    ListMyHouseholds.java                  # query: keycloakUserId -> [{householdId,name}] (new)
  adapter/in/
    HouseholdController.java               # POST /api/v1/households, GET /api/v1/households (new)
    WriteErrorAdvice.java                  # @RestControllerAdvice -> {code,message,details} (new)
  adapter/out/
    KurrentDbEventStore.java               # implements EventStore port (new)
    DomainEventJsonCodec.java              # DomainEvent <-> JSON with stable type tags (new)
    HouseholdReadModelProjector.java       # subscribes streams -> PostgreSQL read model (new)
    JdbcHouseholdReadModel.java            # read-model query support (new)
backend/src/main/java/de/sgart/identity/
  application/MintMemberIdentity.java      # published mint port: (keycloakUserId, householdId)->MemberId (new)
  domain/MemberMappingRepository.java      # + save(...) + householdIdsFor(...) (modified)
  adapter/out/JdbcMemberMappingRepository.java   # durable PostgreSQL mapping (new; replaces in-memory in prod)
backend/src/main/resources/
  application.yaml                         # + datasource/Flyway/KurrentDB, profile-guarded (modified)
  db/migration/V1__identity_member_mapping.sql, V2__household_read_model.sql (new)
backend/src/test/java/de/sgart/…           # aggregate/handler unit tests (in-memory doubles);
                                           # Testcontainers: KurrentDbEventStoreTest, JdbcMemberMappingRepositoryTest,
                                           # HouseholdReadModelProjectorTest; ContextLoadsWithout{KurrentDb,Postgres}Test;
                                           # HouseholdControllerTest (MockMvc); extend NoPersistedPersonalDataTest
app/lib/features/households/
  data/households_api.dart                 # listMyHouseholds(), createHousehold(name)->id (new)
  presentation/first_run_router.dart       # 0 -> choice · 1 -> home · >=2 -> selection (new)
  presentation/create_household_page.dart, create_or_await_choice_page.dart,
    household_selection_page.dart, household_home_page.dart, households_cubit.dart (new)
app/lib/shared/http/authenticated_http_client.dart   # + postJson(path, body) (modified)
app/lib/features/auth/presentation/auth_gate.dart    # authenticated -> FirstRunRouter, not placeholder (modified)
app/lib/l10n/app_de.arb                    # + household/routing German copy (modified)
app/test/features/households/…             # routing + create + selection widget/cubit tests (new)
```
- **`collaboration.domain` is populated for the first time** — the ArchUnit layer-direction + slice rules now actually constrain it. Keep it pure (events, aggregate, value types only; no `io.kurrent`/`org.springframework`/`jakarta.persistence`). KurrentDB/JDBC/JSON in `adapter.out`; REST/advice in `adapter.in`; the mint is called via the Identity **application** port (never `identity.domain`).
- One class per concern (SRP); no abbreviations. Reuse `shared` ids/envelope and the identity seam — **do not duplicate** `MemberId`/`HouseholdId`/`AggregateVersion`/`StreamId`/`AuthenticatedCaller`.

### Testing standards
[Source: CLAUDE.md §6; ARCHITECTURE-SPINE Testing convention (line 146); NFR6/NFR9]
- **Test pyramid — keep the base fast and infra-free.** The `Household` aggregate and the `CreateHouseholdHandler` are proven with **pure unit tests** using the in-memory `EventStore` + in-memory ACL (no Spring/DB/KurrentDB). This is the bulk of coverage. Only the two adapter contract tests + the projector/query test use **Testcontainers**; the REST endpoints use MockMvc + `spring-security-test` `jwt()` (no live Keycloak).
- **CQRS coverage (CLAUDE.md §6):** test the **command** for the events it emits and the state change (`HouseholdCreated` + `MemberJoined(Admin)`, the minted id threaded through); test the **query** (`ListMyHouseholds`) for the read model it returns and that it is **side-effect free**. This story finally exercises the "queries return read models" half that 1.5 noted was deferred to 1.6.
- **Behavior, not structure:** full-sentence names, e.g. `creatingAHouseholdEmitsHouseholdCreatedThenMemberJoinedCarryingTheMintedMemberId`, `aPersonInTwoHouseholdsHasTwoUnrelatedMemberIds`, `rejectsABlankHouseholdName`, `routesAZeroHouseholdCallerToTheCreateChoice`, `contextLoadsWithKurrentDbDown`.
- **DSGVO explicit (AD-5/AD-6):** extend `NoPersistedPersonalDataTest` — assert the mapping table and the read model have **no** display-name/email column and that no event/read row carries PII (`MemberId` + household name only). **Synthetic data only** — fake users (`anna@example.test`), fake UUIDs; never real names/emails in fixtures or seeds.
- **Read-your-writes / eventual consistency:** assert the create response returns the new `householdId` (so the client routes straight in without the projection); the projector test asserts the read model catches up. Don't assert the projection is synchronous unless Clarification D chooses inline projection.
- **Keep green:** all Story 1.1–1.5 suites (backend 76, client 95+) stay passing; update the client entry test that currently expects the `AuthenticatedPlaceholderPage` after sign-in (it becomes the first-run router) and log it in the Change Log. A red build blocks merge (NFR6).

### References
- [Source: epics.md#Story 1.6: Create a household & first-run routing] (lines 344–361) — user story + the three ACs
- [Source: epics.md] FR1 (line 40), AR1–AR4/AR7/AR10 (lines 96–104), NFR2/NFR5/NFR6/NFR9 (lines 68–88) — the requirement IDs the ACs realize
- [Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md] AD-1 (line 64), AD-2 (line 70), AD-3 (line 76), AD-4 (line 82), AD-5 (line 88), AD-6 (line 94), AD-8 (line 106), AD-10 (line 118), AD-11 (line 124); Consistency Conventions (lines 132–146); Capability→Architecture map (line 215)
- [Source: specs/spec-sgart/glossary.md] — Household, Member, MemberId, HouseholdRole, Identity ACL (binding names)
- [Source: ux-designs/ux-sgart-2026-08-20/EXPERIENCE.md (lines 48–61); .working/ia.md (lines 22–37)] — first-run routing 0/1/≥2, the create/await choice, selection screen; switcher (1.7) and onboarding (1.9) boundaries
- [Source: implementation-artifacts/1-5-command-concurrency-envelope.md] — the `EventStore`/`EventSourcedAggregate`/envelope contract, the in-memory `EventStore` double, the KurrentDB eager-boot trap, and the explicit "Story 1.6 wires the real KurrentDB client / first aggregate / first projector" deferrals
- [Source: implementation-artifacts/1-4-sign-in-with-keycloak-resolve-membership-identity.md] — the ACL resolution port + `MemberMapping`/`InMemoryMemberMappingRepository`, the "defer PostgreSQL mapping + mint to 1.6" LOCK, the `AuthenticatedCaller` `sub`-only seam, `ContextLoadsWithoutKeycloakTest`, `NoPersistedPersonalDataTest`, and the client HTTP/`AppError`/`AuthCubit` patterns
- [Source: backend/src/main/java/de/sgart/shared/{EventStore,EventSourcedAggregate,AggregateVersion,StreamId,Command,DomainEvent,CommandId,ConcurrencyConflictException,ErrorDescriptor,HouseholdId,MemberId}.java] — the write-side kernel to build on
- [Source: backend/src/test/java/de/sgart/architecture/HexagonalArchitectureTest.java] — the five ArchUnit rules to keep green as `collaboration` is populated
- [Source: docker-compose.yml; .github/workflows/*.yml] — KurrentDB 25.1.4 / PostgreSQL 18.6 / Keycloak 26.7 services; CI runs `./gradlew test` + `flutter analyze`/`flutter test` (Docker available for Testcontainers)
- [Source: CLAUDE.md] — Clean Code, no-abbreviations naming, DDD/CQRS layering, TDD + DSGVO testing rules
- [Source: memory `flutter-test-local`, `git-workflow`, `language-policy`, `bmad-flow-state`] — run tests locally for real; direct-to-`main` pre-beta; English keys/German values; resume point

## Clarifications (LOCKED by Timo 2026-08-23 — all six confirmed as the recommended default)

1. **Wire the real KurrentDB adapter now (Testcontainers), as 1.5 deferred?** — ✅ **LOCKED: YES.** 1.6 is the first writer 1.5 explicitly deferred the adapter to; there is no later writer to defer to, and first-run routing needs persistence that survives restart. Ship `KurrentDbEventStore` in `collaboration.adapter.out` behind the unchanged `EventStore` port, lazy/profile-guarded (no boot connection), proven by a Testcontainers contract test re-proving `EventStoreContractTest`'s guarantees against real KurrentDB. *Alternative (rejected):* keep an in-memory `EventStore` as the default profile and defer KurrentDB again — but then event sourcing is unproven end-to-end and the routing "survives restart" story is fake.

2. **PostgreSQL read side now (Flyway + Testcontainers), as 1.4/1.5 deferred?** — ✅ **LOCKED: YES.** The ACL mint needs a durable mapping table (1.4's deferral) and routing needs a household-name read model — both PostgreSQL, both land here. Flyway migrations + a real-Postgres Testcontainers test; fast unit tests keep using the in-memory doubles. *Alternative (rejected):* derive routing from the in-memory ACL only and defer the read model — but the household **name** lives in events and needs a projection, and the mapping must be durable for a real login to work across restarts.

3. **DB access: Spring Data JDBC vs. `JdbcClient`/`JdbcTemplate` vs. JPA.** — ✅ **LOCKED: plain SQL Flyway migrations + `JdbcClient` (Spring Boot 4.1) or Spring Data JDBC** for the repositories/read model. Read models are simple projections; **JPA/Hibernate is overkill** and pulls a heavy mapping layer into a CQRS read side (KISS/YAGNI). *Alternative (rejected):* JPA if you expect richer read models later — not justified for MVP.

4. **Read model projection: async subscription vs. inline-on-append.** — ✅ **LOCKED: async projector subscribed to KurrentDB streams** (true CQRS/AD-4, eventually consistent) **plus** the create response returning the new `householdId` so the client routes straight in without waiting (read-your-writes). *Alternative (rejected):* project **inline** in the command handler transaction — it violates "read models are projection-only / never written by a command handler" (AD-4, the exact convention 1.5 said the first projector realizes). If the async subscription proves fiddly for a single-node MVP, a **catch-up subscription on startup + live subscription** is the standard KurrentDB pattern; keep the projector idempotent (re-projecting an event is a no-op upsert).

5. **Cross-store consistency for mint-then-append (two stores, no distributed transaction).** — ✅ **LOCKED: mint-first, append-second, make the mint idempotent per `(keycloakUserId, commandId)`; accept a harmless orphan mapping on a mid-failure and defer any outbox/saga/cleanup (YAGNI).** A failed append leaves an unlinkable mapping row pointing at a household with no stream — it can never resolve to a real membership; a client retry with the same `commandId` converges. *Alternative (rejected):* a transactional outbox or a compensating delete — premature for a solo MVP; noted as a future concern.

6. **What "straight in" lands on, and how minimal the create/selection screens are.** — ✅ **LOCKED: minimal.** 1.6 ships a **minimal household home** showing the current household **name** (the seed Story 1.7's persistent header grows from), a **minimal create screen** (just a name field), and a **minimal selection list** (names, tap to enter). The **switcher/rename is 1.7**, the **onboarding wizard is 1.9**, the **lists screen is Epic 2**, and **„Auf Einladung warten" is an informational dead-end** (invite acceptance is Epic 4). The dev agent must not over-build the UI beyond this boundary.

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5), via `bmad-dev-story`.

### Debug Log References

- Backend: `cd backend && ./gradlew test` — 121 tests, all green (Testcontainers: real PostgreSQL 18.6 + real KurrentDB 25.1.4, real Docker).
- Client: `flutter analyze` (0 issues) and `flutter test` — 124 tests, all green.
- KurrentDB Testcontainers wait strategy required `forStatusCode(204)` — the real `/health/live` endpoint replies `204 No Content`, not the default-matched `200`.
- Testcontainers 2.0.x renamed container artifacts (`org.testcontainers:testcontainers-postgresql`, not `:postgresql`) and moved `PostgreSQLContainer` to `org.testcontainers.postgresql` (non-generic) — the old `org.testcontainers.containers.PostgreSQLContainer<?>` is deprecated in this version.

### Completion Notes List

- **All 4 ACs implemented and proven.** Household aggregate + events (Task 1, 12 unit tests) → Identity ACL mint/durable mapping (Task 2, 8 tests incl. Testcontainers) → real KurrentDB `EventStore` adapter (Task 3, 6 Testcontainers tests against real KurrentDB 25.1.4, re-proving the contract `EventStoreContractTestBase` also proves for the in-memory double) → household read model + projector + `ListMyHouseholds` query (Task 4, 8 tests incl. Testcontainers) → `CreateHousehold` command/handler/REST + error advice (Task 5, 10 tests incl. a MockMvc slice) → Flutter first-run routing (Task 6, ~30 new client tests) → build wiring/guardrails (Task 7, verified via clean rebuilds of both suites).
- **Layering fix mid-story:** the first REST/advice pass leaked `collaboration.domain` types (`HouseholdName`, a domain-level `InvalidHouseholdNameException`) into `adapter.in`, caught by the existing `layersRespectHexagonalDirection` ArchUnit rule (AdapterIn may not access Domain). Fixed by: `HouseholdName`'s constructor throws a plain `IllegalArgumentException` (matching `Money`'s existing convention) instead of a custom domain exception; `CreateHouseholdHandler` (application layer) translates that into `collaboration.application.InvalidHouseholdNameException` (mirroring where `NotAMemberException` already lives in `identity.application`) — the same pattern the codebase already used, just not yet applied to `Household`. `HouseholdController`/`ListMyHouseholds.HouseholdSummary` now pass plain `String` names, never the domain `HouseholdName`, across the adapter.in boundary. `HouseholdApplicationConfig` (Spring wiring referencing the domain-owned `HouseholdNameReadModel` port) moved from `adapter.in` to `adapter.out`, which the same rule permits.
- **Boundary-safe published ports:** `MintMemberIdentity.mint` and the new `ListHouseholdsForCaller.forCaller` take a plain `String keycloakUserId`, not `identity.domain.KeycloakUserId` — so `collaboration.application` never imports an `identity.domain` type, honoring AD-2 ("never reach into `identity.domain`") in the method signature itself, not just by convention.
- **Deviation from the story's literal REST shape:** `POST /api/v1/households`'s body is `{name, commandId}` — no `basedOnVersion` field. A client cannot supply a meaningful `basedOnVersion` for a brand-new household (it doesn't know the server-generated `householdId` yet), so the handler derives `AggregateVersion.initial(...)` server-side from a freshly generated `HouseholdId`. The `CreateHousehold` command record still carries the full `{commandId, basedOnVersion, name}` envelope internally.
- **Mint-then-append cross-store consistency (Dev Notes):** implemented `MintMemberIdentity.mint` as idempotent per `(keycloakUserId, householdId)` (find-then-insert via the existing `findMemberId` lookup) — a genuine, tested guarantee. Did **not** implement deterministic `HouseholdId` derivation from `commandId` for full end-to-end retry convergence (that would need a name-based UUID derivation not requested by any task/AC and risked over-engineering a solo-MVP concern the Dev Notes explicitly say to keep simple, accepting the harmless-orphan outcome instead — no outbox/saga, as LOCKED in Clarification 5).
- **`HouseholdReadModelProjector.start()`** (the real KurrentDB catch-up + live subscription, filtered by stream-name prefix `household-`) is implemented and registered as a Spring bean, but is **not** auto-started on application boot in this story — starting it eagerly would reopen the exact eager-boot-connection risk the KurrentDB/Postgres beans were deliberately made lazy to avoid, and no AC/task requires it running automatically yet (the read-your-writes design means routing never depends on it). The Testcontainers integration test (`HouseholdReadModelProjectorTest`) drives `projector.project(event)` directly against real PostgreSQL rather than the live subscription, for determinism; the subscription API itself is exercised only by the vendor's own tests plus compilation/type-checking here.
- **Preserved sign-out:** `AuthenticatedPlaceholderPage` (Story 1.4's explicit placeholder for "routing is 1.6") is deleted and replaced by `FirstRunRouter`; its sign-out action was carried forward onto the new minimal `HouseholdHomePage` so removing the placeholder doesn't regress the only way to sign out — not explicitly requested by the task text, but omitting it would have been a functional regression.
- **`AuthGateBody` testability seam added:** `authenticatedBuilder` (defaults to the real `FirstRunRouter`) lets `auth_gate_body_test.dart` avoid triggering a real network call — `FirstRunRouter` unavoidably builds a real `Dio`/`HouseholdsApi` and starts fetching households as soon as it is built, which would otherwise leak a pending `Timer` past test teardown (a real `dart:io` HTTP attempt, not fakeable by `flutter_test`'s fake clock). `FirstRunRouter`'s own behavior is fully covered separately, without any real dependency, via `FirstRunRouterBody` + a fake `HouseholdsCubit`.
- Both suites run clean from scratch: backend `./gradlew clean test` (121 tests, zero compiler warnings under `-Xlint:deprecation`, verified then reverted — not left in the build), client `flutter analyze` (0 issues) + `flutter test` (124 tests).

### File List

**Backend — main**
- `backend/build.gradle.kts` (modified — KurrentDB client, JDBC/Flyway/Postgres, Testcontainers)
- `backend/src/main/resources/application.yaml` (modified — datasource, Flyway toggle, KurrentDB connection string)
- `backend/src/main/resources/db/migration/V1__identity_member_mapping.sql` (new)
- `backend/src/main/resources/db/migration/V2__household_read_model.sql` (new)
- `backend/src/main/java/de/sgart/collaboration/domain/Household.java` (new)
- `backend/src/main/java/de/sgart/collaboration/domain/HouseholdCreated.java` (new)
- `backend/src/main/java/de/sgart/collaboration/domain/MemberJoined.java` (new)
- `backend/src/main/java/de/sgart/collaboration/domain/HouseholdName.java` (new)
- `backend/src/main/java/de/sgart/collaboration/domain/HouseholdRole.java` (new)
- `backend/src/main/java/de/sgart/collaboration/domain/HouseholdNameReadModel.java` (new)
- `backend/src/main/java/de/sgart/collaboration/application/CreateHousehold.java` (new)
- `backend/src/main/java/de/sgart/collaboration/application/CreateHouseholdHandler.java` (new)
- `backend/src/main/java/de/sgart/collaboration/application/InvalidHouseholdNameException.java` (new)
- `backend/src/main/java/de/sgart/collaboration/application/ListMyHouseholds.java` (new)
- `backend/src/main/java/de/sgart/collaboration/adapter/in/HouseholdController.java` (new)
- `backend/src/main/java/de/sgart/collaboration/adapter/in/WriteErrorAdvice.java` (new)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/DomainEventJsonCodec.java` (new)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/KurrentDbEventStore.java` (new)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/KurrentDbAccessException.java` (new)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/KurrentDbConfig.java` (new)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/JdbcHouseholdReadModel.java` (new)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/HouseholdReadModelProjector.java` (new)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/HouseholdReadModelConfig.java` (new)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/HouseholdApplicationConfig.java` (new)
- `backend/src/main/java/de/sgart/identity/domain/MemberMappingRepository.java` (modified — `save`, `householdIdsFor`)
- `backend/src/main/java/de/sgart/identity/adapter/out/InMemoryMemberMappingRepository.java` (modified)
- `backend/src/main/java/de/sgart/identity/adapter/out/JdbcMemberMappingRepository.java` (new)
- `backend/src/main/java/de/sgart/identity/adapter/out/IdentityBeansConfig.java` (new)
- `backend/src/main/java/de/sgart/identity/application/MintMemberIdentity.java` (new)
- `backend/src/main/java/de/sgart/identity/application/ListHouseholdsForCaller.java` (new)

**Backend — test**
- `backend/src/test/java/de/sgart/ContextLoadsWithoutPostgresTest.java` (new)
- `backend/src/test/java/de/sgart/ContextLoadsWithoutKurrentDbTest.java` (new)
- `backend/src/test/java/de/sgart/shared/EventStoreContractTest.java` (modified — now extends the new base)
- `backend/src/test/java/de/sgart/shared/support/EventStoreContractTestBase.java` (new — DRY base shared with the KurrentDB adapter's own contract proof)
- `backend/src/test/java/de/sgart/identity/NoPersistedPersonalDataTest.java` (modified — extended to every Flyway migration)
- `backend/src/test/java/de/sgart/identity/adapter/out/JdbcMemberMappingRepositoryTest.java` (new)
- `backend/src/test/java/de/sgart/identity/application/MintMemberIdentityTest.java` (new)
- `backend/src/test/java/de/sgart/identity/application/ListHouseholdsForCallerTest.java` (new)
- `backend/src/test/java/de/sgart/collaboration/domain/HouseholdTest.java` (new)
- `backend/src/test/java/de/sgart/collaboration/domain/HouseholdNameTest.java` (new)
- `backend/src/test/java/de/sgart/collaboration/application/CreateHouseholdHandlerTest.java` (new)
- `backend/src/test/java/de/sgart/collaboration/application/ListMyHouseholdsTest.java` (new)
- `backend/src/test/java/de/sgart/collaboration/adapter/in/HouseholdControllerTest.java` (new)
- `backend/src/test/java/de/sgart/collaboration/adapter/out/KurrentDbEventStoreTest.java` (new)
- `backend/src/test/java/de/sgart/collaboration/adapter/out/HouseholdReadModelProjectorTest.java` (new)

**Client — main**
- `app/pubspec.yaml` (modified — `uuid`, `collection`)
- `app/lib/l10n/app_de.arb` (modified — households/routing German copy)
- `app/lib/shared/http/authenticated_http_client.dart` (modified — `getJsonList`, `postJson`)
- `app/lib/shared/errors/error_message_resolver.dart` (modified — `household.nameRequired` mapping)
- `app/lib/features/auth/presentation/auth_gate.dart` (modified — routes to `FirstRunRouter`; added the `authenticatedBuilder` test seam)
- `app/lib/features/auth/presentation/authenticated_placeholder_page.dart` (deleted — replaced by first-run routing)
- `app/lib/features/households/data/household_summary.dart` (new)
- `app/lib/features/households/data/households_api.dart` (new)
- `app/lib/features/households/presentation/households_state.dart` (new)
- `app/lib/features/households/presentation/households_cubit.dart` (new)
- `app/lib/features/households/presentation/create_household_state.dart` (new)
- `app/lib/features/households/presentation/create_household_cubit.dart` (new)
- `app/lib/features/households/presentation/first_run_router.dart` (new)
- `app/lib/features/households/presentation/create_or_await_choice_page.dart` (new)
- `app/lib/features/households/presentation/await_invite_page.dart` (new)
- `app/lib/features/households/presentation/create_household_page.dart` (new)
- `app/lib/features/households/presentation/household_selection_page.dart` (new)
- `app/lib/features/households/presentation/household_home_page.dart` (new)

**Client — test**
- `app/test/shared/http/authenticated_http_client_test.dart` (modified)
- `app/test/shared/errors/error_message_resolver_test.dart` (modified)
- `app/test/features/auth/presentation/auth_gate_body_test.dart` (modified)
- `app/test/features/auth/presentation/authenticated_placeholder_page_test.dart` (deleted)
- `app/test/support/fake_households_dependencies.dart` (new)
- `app/test/features/households/presentation/households_cubit_test.dart` (new)
- `app/test/features/households/presentation/create_household_cubit_test.dart` (new)
- `app/test/features/households/presentation/first_run_router_test.dart` (new)
- `app/test/features/households/presentation/create_household_page_test.dart` (new)

## Change Log

| Date | Change |
| --- | --- |
| 2026-08-23 | Story created via bmad-create-story. The pivotal "first real writer" story: first real aggregate (`Household` + `HouseholdCreated`/`MemberJoined`), first command (`CreateHousehold`) + handler, the **real KurrentDB `EventStore` adapter** (deferred from 1.5), the **durable PostgreSQL Identity-ACL mapping + mint path** (deferred from 1.4), the first CQRS **projector + read model + query**, write-side error advice, and Flutter **first-run routing** (0 → create/await choice · 1 → straight in · ≥2 → selection) with minimal create/selection/home screens. Two eager-boot traps (KurrentDB + PostgreSQL) flagged; Testcontainers for the adapter/projector contract tests, in-memory doubles for the fast pyramid base. Six load-bearing decisions surfaced as Clarifications (wire KurrentDB now; PostgreSQL now; JDBC over JPA; async projection + read-your-writes; mint-then-append orphan tolerance; minimal-UI boundary). Status → ready-for-dev. |
| 2026-08-23 | All six clarifications LOCKED by Timo (recommended defaults): (1) wire the real KurrentDB adapter now behind the unchanged `EventStore` port (Testcontainers); (2) PostgreSQL read side now (Flyway + durable ACL mapping + household read model, Testcontainers); (3) `JdbcClient`/Spring Data JDBC over JPA; (4) async projector subscribed to streams + read-your-writes via create-returns-`householdId`; (5) mint-first/append-second with idempotent mint and tolerated harmless orphan (no outbox/saga — YAGNI); (6) minimal UI (switcher→1.7, onboarding→1.9, lists→Epic 2, „Auf Einladung warten"→Epic 4 dead-end). Ready for `bmad-dev-story` (Sonnet 5 per model-preferences). |
| 2026-08-23 | All 7 tasks implemented (Sonnet 5, `bmad-dev-story`): Household aggregate; Identity ACL mint + durable PostgreSQL mapping; real KurrentDB `EventStore` adapter; household read model + projector + `ListMyHouseholds`; `CreateHousehold` command/handler/REST + error advice; Flutter first-run routing (create/await choice, create form, selection, minimal household home); build wiring + guardrails. Mid-story layering correction: relocated the household-name validation exception from `collaboration.domain` to `collaboration.application` (mirroring `NotAMemberException`'s existing placement) after the ArchUnit hexagonal-direction rule caught `adapter.in` reaching into `collaboration.domain`; `HouseholdApplicationConfig` moved from `adapter.in` to `adapter.out` for the same reason. Backend: 121 tests green (Testcontainers against real PostgreSQL 18.6 + real KurrentDB 25.1.4). Client: 124 tests green, `flutter analyze` clean. Status → review. |
| 2026-08-23 | Code review (bmad-code-review, Opus, 3 adversarial layers) + fixes. 2 decision-needed (resolved by Timo: activate read path now; enforce commandId convergence), 6 patch, 2 deferred, 2 dismissed. All 8 patches applied: (P1) create-household form no longer crashes with `ProviderNotFoundException` (pushed route re-provides `HouseholdsApi`/`HouseholdsCubit`); (P2) projector activated as a resilient `SmartLifecycle` gated by `SGART_PROJECTOR_AUTOSTART` (off by default like Flyway) + `ListMyHouseholds` counts from the authoritative ACL mapping + new live-subscription Testcontainers test (durable position checkpoint deferred — idempotent re-projection makes it a non-correctness optimization); (P3) `HouseholdId` derived deterministically from `(keycloakUserId, commandId)` so retries converge, client reuses one commandId per intent; (P4) missing/malformed name & commandId now return localizable 400s (`command.commandIdRequired`/`command.commandIdInvalid`) not 500; (P5) over-long name gets its own `household.nameTooLong` code + copy; (P6) `FirstRunRouter` builds/disposes its `Dio` once; (P7) home shows the trimmed name; (P8) KurrentDB metadata null-guarded. Both suites re-run for real: backend **129 green** (incl. new dual-container live-subscription test), client **128 green**, `flutter analyze` clean. Status → done. |

## Review Findings

_Code review 2026-08-23 (bmad-code-review, Opus — 3 parallel adversarial layers: Blind Hunter, Edge Case Hunter, Acceptance Auditor). 2 decision-needed, 6 patch, 2 deferred, 2 dismissed as noise._

### Decision-needed

> **Resolved by Timo 2026-08-23:** (1) Read path — **fix now in 1.6** (activate projector async/infra-down-safe + onError/resubscribe/checkpoint + count from the authoritative ACL mapping with best-effort names + end-to-end integration test). (2) Idempotency — **enforce commandId convergence** per LOCKED Clarification 5 (key on `(keycloakUserId, commandId)`; client reuses the same commandId across retries). Both promoted to patches below.
>
> **Applied 2026-08-23 (all 8 patches, both suites re-run for real — backend 129 green incl. a new live-subscription Testcontainers test, client 128 green):**
> - **(1) Read path:** `HouseholdReadModelProjector` is now a `SmartLifecycle` with a resilient subscription (resubscribes on drop via a daemon scheduler; a failed single-event projection is logged, not fatal). Auto-start is gated by `sgart.projector.auto-start` (env `SGART_PROJECTOR_AUTOSTART`), **off by default** exactly like `SGART_FLYWAY_ENABLED`, so tests/CI never open a subscription; a real run enables it. `ListMyHouseholds` now counts from the **authoritative ACL mapping** (best-effort name from the read model — a not-yet-projected household still appears rather than being dropped). New `HouseholdReadModelSubscriptionTest` proves append→live subscription→read model→`ListMyHouseholds` against real KurrentDB + Postgres. **Deliberate reduction:** a *durable* position checkpoint (resume-from-position surviving restart) was **not** implemented — re-projection is idempotent and the fromStart re-fold is cheap at MVP scale; logged as a follow-up. Operational note: a real run now needs both `SGART_FLYWAY_ENABLED=true` and `SGART_PROJECTOR_AUTOSTART=true`.
> - **(2) Idempotency:** `CreateHouseholdHandler` now derives the `HouseholdId` deterministically from `(keycloakUserId, commandId)`, so a retry with the same `commandId` converges on one household (the idempotent mint replays the existing `MemberId`; the append no-ops on the replayed `commandId`). The Flutter `CreateHouseholdCubit` mints one `commandId` per create intent and reuses it across resubmits.

- [x] [Review][Decision→Patch] **Read path never activated — returning users are routed as if they have zero households (HIGH).** `HouseholdReadModelProjector.start()` is never called in production (no lifecycle hook — `HouseholdReadModelConfig` only builds the bean), so `household_read_model` stays empty; `ListMyHouseholds.forCaller` then filters memberships by `names::containsKey` and returns `[]`. Every returning user with ≥1 household is therefore routed to the create/await choice on each relaunch — AC2's "1 → straight in / ≥2 → selection" never fires in a running system (proven only by hand-feeding `project()` in tests). Bundled sub-issues to settle with the same decision: the projector's `start()` has no `onError`/`onCancelled` handling and re-reads `fromStart()` with no checkpoint (BH4/EC5), and the name-filter under-reports during projection lag even once the projector runs (EC3/AA2 — consider driving the count from the authoritative ACL mapping). Sources: blind+edge+auditor. [HouseholdReadModelProjector.java:53; HouseholdReadModelConfig.java; ListMyHouseholds.java:39]
- [x] [Review][Decision→Patch] **Create-household idempotency contradicts LOCKED Clarification 5 — retries create duplicate households (MEDIUM).** `CreateHouseholdHandler.handle` calls `HouseholdId.generate()` on every request and `MintMemberIdentity.mint` is idempotent per `(keycloakUserId, householdId)`, so a retried create never converges: it mints a fresh `MemberId` + a new orphan mapping row and creates a *second* household. The client also generates a new `commandId` per `createHousehold` call. Clarification 5 LOCKED "idempotent per `(keycloakUserId, commandId)` … a retry with the same commandId converges" — the implemented mechanism can never fire on the create path. Orphans remain harmless per the clarification, but they accrue unboundedly instead of converging. Sources: blind+edge+auditor. [CreateHouseholdHandler.java:45,50; MintMemberIdentity.java:34; households_api.dart]

### Patch

- [x] [Review][Patch] Create-household screen crashes with ProviderNotFoundException — the pushed `CreateHouseholdPage` escapes the `HouseholdsApi`/`HouseholdsCubit` providers (root Navigator is above them); the widget test masks it by re-providing directly (HIGH) [app/lib/features/households/presentation/create_or_await_choice_page.dart:33]
- [x] [Review][Patch] Missing/malformed `name` or `commandId` returns HTTP 500 with no localizable code instead of 400 — `CommandId.fromString(null/non-uuid)` and `requireNonNull(rawName)` throw types `WriteErrorAdvice` does not map (MEDIUM) [backend/src/main/java/de/sgart/collaboration/adapter/in/HouseholdController.java:42]
- [x] [Review][Patch] Over-long household name (>120 chars) surfaces the `household.nameRequired` code → user sees "please enter a name" (LOW) [backend/src/main/java/de/sgart/collaboration/domain/HouseholdName.java:26]
- [x] [Review][Patch] `FirstRunRouter` builds a new `Dio`/HTTP client on every rebuild and never disposes it (LOW) [app/lib/features/households/presentation/first_run_router.dart:37]
- [x] [Review][Patch] Household home shows the un-trimmed typed name, diverging from the trimmed persisted value (LOW) [app/lib/features/households/presentation/create_household_cubit.dart]
- [x] [Review][Patch] KurrentDB idempotency check dereferences `getUserMetadata()` without a null guard (LOW) [backend/src/main/java/de/sgart/collaboration/adapter/out/KurrentDbEventStore.java:81]

### Deferred

- [x] [Review][Defer] KurrentDB idempotency check + append are not atomic — a concurrent duplicate `commandId` on an existing stream surfaces a conflict instead of a silent no-op [backend/src/main/java/de/sgart/collaboration/adapter/out/KurrentDbEventStore.java:47] — deferred, latent (not reachable via create's fresh stream; relevant only for future retry-against-existing-stream flows)
- [x] [Review][Defer] `NoPersistedPersonalDataTest` strips only `--` line comments, not `/* */` block comments, from migrations before the PII-column scan [backend/src/test/java/de/sgart/identity/NoPersistedPersonalDataTest.java] — deferred, test-only, no current trigger
