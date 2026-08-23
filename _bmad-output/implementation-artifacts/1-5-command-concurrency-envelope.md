---
baseline_commit: 1e1392563bc6972a7cdd70458cce91a959441f05
---

# Story 1.5: Command & concurrency envelope

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the developer,
I want one command/event/concurrency contract that every later command reuses,
so that live sync and the offline queue are additive layers, never a rewrite of existing handlers.

## Acceptance Criteria

1. **Every state-changing command flows command → aggregate → domain event(s), the events are appended to the event store under an expected-version (optimistic-concurrency) check, and read models are projection-only — never written by a command handler.** (AD-1, AD-4 / AR1, AR3)
   - The write path is a single reusable shape: a **command** is handled by an **aggregate root** that validates the business invariant and **emits one or more domain events**; nothing else mutates state. The aggregate never writes a database row directly — its only output is events.
   - Events are **appended to the event store under an expected-version check** (optimistic concurrency): the append succeeds only if the stream's current version equals the version the command was based on; otherwise it is rejected with a concurrency conflict (see AC2). The append of an aggregate's events is **atomic** — all events for one command land together or none do.
   - **Read models are projection-only.** The contract forbids a command handler from writing a PostgreSQL read model; read models are built solely by projectors subscribed to the event streams (AD-4). No read model or projector exists yet (the first is Story 1.6's household read model), so this AC is delivered as the **enforced contract + convention** the first projector realizes — not a built projection. Where cheap to do so, add a guardrail (ArchUnit and/or the port shape) that makes a direct read-model write from a handler structurally impossible.

2. **A client-originated command carries a `commandId` and a `basedOnVersion` (the target aggregate root's stream version); replay is idempotent and a stale write is rejected — and every command-emitting story from here on reuses this same envelope.** (AD-8 / AR7)
   - The command envelope carries a client **`commandId`** (opaque, client-supplied) and a **`basedOnVersion`** — the version of **the target aggregate root's own stream** the command was based on (never a related aggregate's version, AD-8). `basedOnVersion` is the expected-version token passed to the append.
   - **Stale write rejected:** if the stream has advanced past `basedOnVersion` when the append is attempted, the command is **rejected** with a concurrency conflict — never silently applied, never last-writer-wins, never auto-merged. (The coarse keep/discard *UI* is Epic 5; here the write layer only guarantees the rejection.)
   - **Replay is idempotent:** replaying a command whose `commandId` has already been applied is a **silent success no-op** — it never appends the events a second time and never surfaces a conflict. This is what makes Epic 5's offline queue a purely client-side layer that touches no existing handler.
   - The envelope is **cross-context and final**: it is defined once and **every later command-emitting story (1.6, Epic 2/3 lists & trips, …) reuses it unchanged**. Getting this shape right is the whole point of the story.

3. **A process-manager-issued command derives its `commandId` deterministically from the triggering event's id, so re-processing the triggering event on subscription/projection replay never double-applies.** (AD-10 / AR9)
   - Cross-aggregate effects are carried by **process managers** that react to a domain event by issuing a **new** command on another aggregate (e.g. later: `ItemPostponed{targetListId}` → add-item on the target list). Such a command's `commandId` is **derived deterministically from the triggering event's id** (a pure function: same event id → same `commandId`).
   - Therefore re-delivering the same triggering event on subscription/replay produces the **same `commandId`**, which the idempotency rule (AC2) collapses to a no-op — the effect is applied **exactly once**. No process manager or subscription exists yet (first one is Epic 2's item-move); this AC delivers the **deterministic-derivation contract + helper** the first process manager reuses, proven with synthetic events.

4. **Command → event emission and the concurrency/idempotency behavior are covered by fast unit tests.** (NFR6)
   - The envelope, the expected-version append (success + conflict), the idempotent-replay no-op, and the deterministic `commandId` derivation are all proven by **fast unit tests with no infrastructure** (no live event store, no Spring, no network) — using a **synthetic reference aggregate** that exists only in test scope. A red build blocks merge.

## Tasks / Subtasks

- [x] **Task 1 — Write-side envelope value types (shared kernel)** (AC: #2, #3)
  - [x] In `de.sgart.shared`, add the cross-context write-side identifiers, mirroring the existing `Identifier`/`MemberId` record style (UUID-backed, `Objects.requireNonNull`, `generate()`/`fromString()`/`toString()`):
    - `CommandId` — opaque client command id. Add `generate()`, `fromString(String)`, and **`deterministicFrom(EventId)`** — a pure, stable derivation (name-based **UUID v5** over the triggering event id, fixed namespace) so the same event id always yields the same `CommandId` (AC3).
    - `EventId` — opaque domain-event id (UUID-backed). Every emitted event carries one; it is the input to `CommandId.deterministicFrom`.
  - [x] Add **`AggregateVersion`** — the optimistic-concurrency token / `basedOnVersion`. A value object over a non-negative `long` with an explicit **"new stream" sentinel** (e.g. `AggregateVersion.initial()` meaning *no events yet* — the value an append of a brand-new aggregate expects). Reject negative values (fail fast). Provide `next()`/increment semantics used when replaying/appending.
  - [x] Add **`StreamId`** — the event-store stream key. Encodes the spine convention `household-{id}` / `list-{id}` / `trip-{id}` (AR10) as a typed value (`StreamId.forHousehold(HouseholdId)`, etc., or a `type + id` shape). No abbreviations; one unambiguous representation of a stream key so no handler hand-builds strings.
  - [x] One fast unit test per value type (`MoneyTest`/`MemberIdTest` style): validation, equality, round-trip `fromString`/`toString`, `AggregateVersion` sentinel + increment, and **`CommandId.deterministicFrom` is stable and collision-distinct** (same event id → same id; different event ids → different ids).

- [x] **Task 2 — Command & DomainEvent contracts (shared kernel)** (AC: #1, #2)
  - [x] Add the **`Command`** interface: exposes `commandId()` and `basedOnVersion()` (an `AggregateVersion`). It is the client-originated envelope every concrete command implements. Keep it a pure contract — no framework/transport types (this is the shared kernel; see Task 6 ArchUnit).
  - [x] Add the **`DomainEvent`** interface: exposes `eventId()` (an `EventId`) — the past-tense fact an aggregate emits. Keep it minimal (YAGNI); do **not** speculatively add `occurredOn`, `memberId`, sequence numbers, or metadata unless a value type already needs it — later events add their own fields. Document that concrete events are PascalCase past-tense and live in a context's `..domain..` (AR10).
  - [x] Add a fast unit test asserting the contracts’ shape is usable by a concrete synthetic command/event (exercised more fully via the reference aggregate in Task 5).

- [x] **Task 3 — Event-sourced aggregate base (shared kernel)** (AC: #1)
  - [x] Add **`EventSourcedAggregate`** — the reusable base every aggregate root extends. Responsibilities, kept minimal:
    - Track the aggregate's **current version** (`AggregateVersion`), starting at the new-stream sentinel.
    - **Rehydrate** from an ordered event history (replay: fold each event via an `apply(DomainEvent)` hook, advancing the version per event).
    - **Record uncommitted events** when a command mutates state (a protected `raise(event)` that applies the event and appends it to an in-flight list), and expose them for the append (`uncommittedEvents()` / `markEventsCommitted()`).
    - Keep **state mutation only inside `apply`** (so replay and live command handling take the identical path — the invariant that makes event sourcing correct).
  - [x] No business logic in the base — it is pure event-sourcing machinery reused by all contexts (parallels why `Money`/`MemberId` live in `shared`). Document the extension contract in Javadoc so Story 1.6's `Household` is a straight subclass.

- [x] **Task 4 — EventStore port + concurrency/idempotency contract (shared kernel)** (AC: #1, #2, #3)
  - [x] Define the **`EventStore`** port (interface owned by the shared kernel, no infrastructure types):
    - `append(StreamId, expectedVersion: AggregateVersion, events: List<DomainEvent>, commandId: CommandId)` — appends atomically **iff** the stream's current version equals `expectedVersion`; otherwise throws `ConcurrencyConflictException`. Idempotency: if `commandId` was already applied to this stream, the call is a **silent no-op success** (no duplicate append, no conflict) — AC2.
    - `readStream(StreamId): List<DomainEvent>` (ordered) — used to rehydrate an aggregate for replay/command handling.
    - Decide and **document in Javadoc** how `commandId` idempotency is recorded (recommended: the applied `commandId` is persisted alongside the append — in KurrentDB via event metadata / an idempotency key — so dedupe survives restart; the in-memory adapter mirrors this). The port contract, not the mechanism, is what 1.6+ depend on.
  - [x] Add **`ConcurrencyConflictException`** carrying the canonical `ErrorDescriptor` shape (stable `code`, e.g. `concurrency.staleVersion`; `message` log-only) — the same pattern Story 1.4 used for `identity.notAMember`. This is the write-side failure the Epic 5 conflict UI eventually renders.
  - [x] **Read-model guardrail:** ensure the port/handler shape makes it structurally impossible for a command handler to write a read model (the handler’s only persistence collaborator is the `EventStore`). Capture "read models are projection-only" as an enforced convention (AC1) — realized by the first projector in 1.6.

- [x] **Task 5 — Prove the contract with a synthetic reference aggregate (test scope only)** (AC: #1, #2, #3, #4)
  - [x] In **test scope only** (`backend/src/test/...`), add a tiny synthetic reference aggregate (e.g. a `CounterAggregate` with a single `Increment` command → `Incremented` event) that extends `EventSourcedAggregate` and emits a `DomainEvent`. It is a **fixture, not production code** — no real domain concept is invented in `main` (YAGNI; the first real aggregate is Story 1.6's `Household`).
  - [x] Add an **`InMemoryEventStore`** test double implementing the `EventStore` port: enforces expected-version on append, throws `ConcurrencyConflictException` on mismatch, and records applied `commandId`s per stream for idempotent no-op replay. Keep it in **test support** (there is no production caller of the port until Story 1.6 wires the real KurrentDB adapter — same "defer durable infra to the first writer" call Story 1.4 made for the PostgreSQL mapping table).
  - [x] Fast unit tests (behavioral names, AAA, `MoneyTest`/`ResolveMemberIdentityTest` style, no Spring/DB/network):
    - **command → event emission:** handling `Increment` on a fresh aggregate raises exactly one `Incremented`; replaying the stream rebuilds identical state and the correct `AggregateVersion`.
    - **expected-version append (happy path):** appending at the current version advances the stream version by the event count.
    - **stale write rejected:** appending with a `basedOnVersion` behind the stream's current version throws `ConcurrencyConflictException` and appends nothing (AC2).
    - **idempotent replay:** appending the same `commandId` twice applies the events **once** — second call is a silent no-op, stream version unchanged, no conflict (AC2).
    - **deterministic process-manager id:** `CommandId.deterministicFrom(eventId)` is stable across calls, so re-processing the same triggering event yields the same `commandId` → the idempotency rule collapses the re-delivery to a no-op (AC3). Two different event ids derive two different command ids.
    - **`basedOnVersion` is the target root's own stream version** — assert the token used is that stream's version (guards against AD-8's "never a related aggregate's version").

- [x] **Task 6 — Keep the architecture guardrails green (+ tighten for the kernel)** (AC: #1)
  - [x] Run the existing `HexagonalArchitectureTest` — all four rules stay green with the new `shared` types (the `..domain..` purity, layer-direction, and context-slice rules are unaffected because the envelope lives in `shared`, not a context domain).
  - [x] **Add a shared-kernel purity rule** (Boy Scout / fail fast): `de.sgart.shared..` must not depend on `io.kurrent..`, `com.eventstore..`, `org.springframework..`, `jakarta.persistence..`, or `..adapter..` — so the write-side kernel (`Command`, `DomainEvent`, `EventStore` port, `EventSourcedAggregate`, ids) can never absorb an infrastructure type. This is what lets the real KurrentDB adapter (Story 1.6) implement `EventStore` in `adapter.out` while the port stays clean.
  - [x] Do **not** loosen or restructure existing rules. If a new layer rule for "command handlers never depend on read-model/projection types" is cheap and meaningful now, add it; otherwise record it as the convention the first projector (1.6) will let us enforce (avoid speculative rules with nothing to constrain — YAGNI).

- [x] **Task 7 — Tests green locally (backend)** (AC: #4)
  - [x] `cd backend && ./gradlew test` green, including ArchUnit and the existing Story 1.1–1.4 suites (`MoneyTest`, `QuantityTest`, `MemberIdTest`, `HouseholdIdTest`, `ResolveMemberIdentityTest`, `IdentityControllerTest`, `ContextLoadsWithoutKeycloakTest`, `NoPersistedPersonalDataTest`, `SgartApplicationTest`). Zero new analyzer/compiler warnings.
  - [x] **No Flutter changes in this story** — 1.5 is backend-write-substrate only; the client offline queue that *consumes* this envelope is Epic 5. Confirm no `app/` files are touched. (If any doc/README note is warranted, keep it backend-scoped.)
  - [x] Run for real locally before review — do not assume green (memory `flutter-test-local`: Story 1.2 was once marked done on a review that never ran tests).

### Review Findings

- [x] [Review][Patch] **Decision (resolved by Timo 2026-08-23): strengthen the type.** `basedOnVersion` was not structurally validated as "this aggregate's own stream version" — `AggregateVersion` was a bare `long` with no association to a `StreamId`, so nothing prevented a caller from passing an unrelated aggregate's version as `expectedVersion`; the test named to prove AD-8's "never a related aggregate's version" guard (`EventStoreContractTest.basedOnVersionIsTheTargetRootsOwnStreamVersion`) only exercised the correct-usage path. Fix: fold `StreamId` into `AggregateVersion` itself (`AggregateVersion(StreamId, long)`), drop the now-redundant explicit `StreamId` parameter from `EventStore.append`/`ConcurrencyConflictException`, and rewrite the test to prove a cross-stream `basedOnVersion` cannot silently land on the wrong stream. [`backend/src/main/java/de/sgart/shared/AggregateVersion.java`, `backend/src/main/java/de/sgart/shared/EventStore.java`, `backend/src/main/java/de/sgart/shared/EventSourcedAggregate.java`, `backend/src/main/java/de/sgart/shared/ConcurrencyConflictException.java`, `backend/src/test/java/de/sgart/shared/support/InMemoryEventStore.java`, `backend/src/test/java/de/sgart/shared/support/CounterAggregate.java`, `backend/src/test/java/de/sgart/shared/EventStoreContractTest.java`]

- [x] [Review][Patch] `CounterAggregate.handle(Increment command)` ignores its `command` parameter — it never reads `commandId()` or `basedOnVersion()`, so the reference aggregate meant to "prove the contract" (Task 5) never actually threads the envelope's own fields through a command-handling method; every test re-derives `basedOnVersion` by hand when calling `eventStore.append(...)`. Dead parameter under CLAUDE.md §1 ("no dead code"). [`backend/src/test/java/de/sgart/shared/support/CounterAggregate.java:20-22`]
- [x] [Review][Patch] AC1's atomicity guarantee ("all events for one command land together or none do") is never exercised by a test with more than one event — every call site raises exactly one `Incremented` via `CounterAggregate.handle()`, so the `List<DomainEvent>` batch path through `EventStore.append` is implemented but unproven. [`backend/src/test/java/de/sgart/shared/EventStoreContractTest.java`, `backend/src/test/java/de/sgart/shared/support/CounterAggregate.java:20-22`]
- [x] [Review][Patch] `EventSourcedAggregate.replay()` has no guard against being invoked on a non-fresh aggregate (one with existing uncommitted events or a non-initial version) — a double `replay()` call, or `replay()` after `raise()`, silently corrupts the tracked version and rebuilt state. This is the base class every future aggregate (Story 1.6's `Household` onward) extends, so the gap propagates forward. [`backend/src/main/java/de/sgart/shared/EventSourcedAggregate.java:38-43`]
- [x] [Review][Patch] `InMemoryEventStore.append()` is not thread-safe: it uses a plain `HashMap`/`HashSet` with an unsynchronized check-then-write for both the expected-version check and the `commandId` idempotency check, so two concurrent calls could both pass either check. The port's Javadoc promises an "atomic" append and an idempotent no-op; the double honouring the "full port contract" should demonstrate the same guarantee it documents. [`backend/src/test/java/de/sgart/shared/support/InMemoryEventStore.java:29-45`]
- [x] [Review][Patch] `EventStoreContractTest.rejectsAnAppendWhoseBasedOnVersionIsBehindTheStream` hardcodes the magic string `"concurrency.staleVersion"` instead of referencing `ConcurrencyConflictException.ERROR_CODE`, which is `public` specifically so it can be reused — a DRY violation (CLAUDE.md §1). [`backend/src/test/java/de/sgart/shared/EventStoreContractTest.java:68`]
- [x] [Review][Patch] No test asserts that `readStream()` on an unseen/new `StreamId` returns an empty list, despite the `EventStore.readStream` Javadoc explicitly promising "Empty if the stream is new." [`backend/src/main/java/de/sgart/shared/EventStore.java:37`, `backend/src/test/java/de/sgart/shared/support/InMemoryEventStore.java:47-50`]
- [x] [Review][Patch] `EventSourcedAggregate.replay()` null-checks the history list itself but not its elements, so a `null` entry flows straight into `apply(null)` and NPEs deep inside a concrete aggregate instead of failing fast at the boundary (CLAUDE.md §1 Fail Fast). [`backend/src/main/java/de/sgart/shared/EventSourcedAggregate.java:38-43`]
- [x] [Review][Patch] `ConcurrencyConflictException`'s constructor does not null-check `streamId`/`expectedVersion`/`actualVersion` before using them to build the message — a `null` argument NPEs while formatting the failure message rather than failing fast with a clear cause. Low risk today (only ever constructed internally from validated values) but this is shared-kernel code future callers (the real KurrentDB adapter) will also construct. [`backend/src/main/java/de/sgart/shared/ConcurrencyConflictException.java:21-31`]

**All 9 patches applied 2026-08-23.** `./gradlew clean compileJava compileTestJava` — no compiler/analyzer warnings. `./gradlew test` — 76 tests, 0 failures, 0 errors, 0 skipped (67 baseline + 9 new: `AggregateVersion`'s stream-tied equality, `EventSourcedAggregate`'s non-fresh-replay and null-history guards, and `EventStoreContractTest`'s cross-stream-version, multi-event-atomicity, and unseen-stream-readStream cases).

## Dev Notes

### Scope & intent
**Pure write-substrate story — the second "contract, not volume" story after 1.4.** 1.4 stood up the *identity* substrate (JWT seam + ACL resolution contract). 1.5 stands up the *write-model* substrate: the **command → aggregate → domain event** shape, the **event-sourced aggregate base**, the **`EventStore` port** with **expected-version optimistic concurrency**, and the **`commandId` + `basedOnVersion` envelope** with **idempotent replay** and **deterministic process-manager command ids**. The deliverable is a **reusable, final contract** — every later command-emitting story (1.6 create-household, Epic 2 lists/items, Epic 3 trips) is a straight consumer, and Epic 5's offline queue becomes a purely client-side layer + conflict UI that **touches no existing handler**. That "additive, never a rewrite" property is the entire business value (the "so that").

**There is no real aggregate, event store wiring, read model, projector, process manager, or subscription in this story** — those arrive with their first real user (Story 1.6 onward). 1.5 ships the **contracts + the base machinery + an in-memory proof**, exactly as Story 1.4 shipped the ACL resolution *port + semantics + in-memory adapter* and deferred the PostgreSQL table and mint path to the first writer (1.6). Building a real KurrentDB client now, against a throwaway stream with no aggregate behind it, is speculative (YAGNI) and risks a rewrite once 1.6's `Household` reveals the real shape. See **Clarifications** for the two load-bearing scope calls (real KurrentDB now vs. defer; kernel placement).

### Source of truth: ARCHITECTURE-SPINE + epics (binding)
[Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md; epics.md; specs/spec-sgart/glossary.md]
- **AD-4 (spine line 82–86) / AR3 (epics line 97):** State changes **only** by appending events to the event store under an **expected-version check**; read models (PostgreSQL) are built by **projectors** subscribed to streams and are **never written directly** by command handlers; projections are eventually consistent. → drives AC1 (the append contract + read-models-projection-only convention).
- **AD-8 (spine line 106–110) / AR7 (epics line 101):** Each command carries the **target aggregate root's stream version** (the concurrency token is *always that one root's* stream version — never a related aggregate's) + a **client command-id**; on replay a **stale expected-version is rejected** (coarse keep/discard is the *UI*, Epic 5), and the **command-id makes replay idempotent** (a retry never double-applies). → drives AC2.
- **AD-10 (spine line 118–122) / AR9 (epics line 103):** Cross-aggregate effects go through **process managers** issuing **new** commands; a process-manager command is **idempotent** because its command-id is **derived deterministically from the triggering event's id**, so re-processing on subscription/replay never double-applies. → drives AC3.
- **AD-1 (spine line 64–68) / AR1:** Every state change is a command handled by an aggregate emitting domain events; the domain imports **no** framework/infra/transport type; infrastructure is reached only through **domain-owned ports**. The ArchUnit test already bans `io.kurrent..`/`com.eventstore..`/`org.springframework..` from `..domain..`. → the `EventStore` port is an interface; the real client is a driven adapter (1.6).
- **AR10 / conventions (spine line 135–138):** domain events **past-tense PascalCase**, stream keys `household-/list-/trip-{id}`; commands **imperative**, carrying `basedOnVersion` + `commandId` when client-originated; error shape `{code,message,details}`. → drives `StreamId`, the `Command`/`DomainEvent` contracts, and `ConcurrencyConflictException`'s `ErrorDescriptor`.
- **Glossary (binding):** *Offline Queue* — "each carries the target aggregate root's version and a client command-id"; *Live Sync* — SSE propagation. Use these exact terms; no abbreviations (`commandId`, not `cmdId`; `AggregateVersion`, not `aggVer`) (AD-11).

### The scaffold & contracts already in the repo (read before writing)
- `backend/src/main/java/de/sgart/shared/Identifier.java` — **the record pattern to mirror** for `CommandId`/`EventId`/`AggregateVersion`/`StreamId` (UUID-backed, `Objects.requireNonNull`, `generate()`/`fromString()`/`toString()`). `MemberId.java`/`HouseholdId.java` (Story 1.4) are the most recent examples of this style in `shared`.
- `backend/src/main/java/de/sgart/shared/ErrorDescriptor.java` — `record ErrorDescriptor(String code, String message, Map<String,Object> details)` + `.of(code, message)`. Use it inside `ConcurrencyConflictException` (`code = concurrency.staleVersion`), exactly as `NotAMemberException` used `identity.notAMember` in 1.4.
- `backend/src/main/java/de/sgart/shared/{Money,Quantity,Unit}.java` — existing value-object style in the kernel (records, factory methods, validation in the compact constructor). Match it.
- `backend/src/test/java/de/sgart/shared/MoneyTest.java`, `.../identity/application/ResolveMemberIdentityTest.java` — backend test style: JUnit 5 + AssertJ (`assertThat`/`assertThatThrownBy`), full-sentence behavioral method names (`resolvesKnownMappingToItsMemberId`). Match it for the envelope + reference-aggregate tests.
- `backend/src/test/java/de/sgart/architecture/HexagonalArchitectureTest.java` — the four permanent ArchUnit rules (`..domain..` purity incl. `io.kurrent..`/`com.eventstore..`; hexagonal layer direction with `withOptionalLayers(true)`; context-domain slice isolation). **Add** the new shared-kernel purity rule here; keep the existing four untouched and green.
- `backend/src/main/java/de/sgart/collaboration/**/package-info.java` — the Collaboration hexagonal packages already exist and are documented but **empty** (`domain` = "pure… aggregates, entities, value objects, domain events, and ports"). They are populated starting Story 1.6 — **do not populate `collaboration` in 1.5**; the envelope is cross-context and lives in `shared`.
- `backend/build.gradle.kts` — Spring Boot 4.1.0, Java 25, web + oauth2-resource-server, ArchUnit, spring-boot-starter-test. **No new dependency is required for 1.5** if the real KurrentDB client is deferred (recommended). If Clarification 1 is answered "wire KurrentDB now", add the KurrentDB Java client (`io.kurrent:kurrentdb-client`, the renamed EventStoreDB client) + a Testcontainers/integration-test dependency — but see the trap below.
- `docker-compose.yml` — **KurrentDB `25.1.4` and PostgreSQL `18.6` are already running services** (Story 1.1). No compose change is needed for the deferred-adapter path; they exist for 1.6 to wire against.

### Placement decision: the envelope lives in `shared` (recommended)
The write-side envelope (`Command`, `DomainEvent`, `EventId`, `CommandId`, `AggregateVersion`, `StreamId`, `EventStore` port, `ConcurrencyConflictException`, `EventSourcedAggregate`) is **reused by every context** — Collaboration now, Price Intelligence later. Placing it in `de.sgart.shared` mirrors the **exact reasoning Story 1.4 used to put `MemberId`/`HouseholdId` in `shared`**: cross-context vocabulary a single context's domain must not own, or the context-slice ArchUnit rule breaks. The shared kernel is "no *business* logic" — the envelope is infrastructure-agnostic **domain machinery**, not a business rule, so it fits (as `Money`/`Quantity` do). The alternative (put it in `collaboration.domain`) is rejected: Price Intelligence would then have to depend on the Collaboration domain, violating AD-2. This is Clarification 2 — recommended default is `shared`.

### The KurrentDB "eager-boot" trap (mirrors 1.4's `contextLoads` trap)
If Clarification 1 is answered "wire the real KurrentDB adapter now", **do not let a KurrentDB connection be established at Spring context startup** — the same class of failure that made a naive `issuer-uri` break `SgartApplicationTest` in Story 1.4 (it fetched Keycloak at boot; CI has no Keycloak). A KurrentDB `@Bean` that connects eagerly will fail `contextLoads()` when the container is down (it is, in CI). If you build the adapter: bind it under a **runtime profile only**, connect **lazily**, and prove behavior with **Testcontainers** (not the dev compose container) so the integration test owns its lifecycle — and add a "context loads with KurrentDB down" regression test, exactly like `ContextLoadsWithoutKeycloakTest`. **The recommended path (defer) sidesteps this entirely**: no client, no bean, nothing to break at boot; the whole contract is proven by fast in-memory unit tests (which is precisely what AC4/NFR6 asks for).

### Previous-story intelligence (Stories 1.1–1.4 — done)
[Source: implementation-artifacts/1-1…, 1-4-sign-in-with-keycloak-resolve-membership-identity.md]
- **Established "contract story" pattern (1.4):** ship the **port + semantics + in-memory adapter**, prove with **synthetic data + fast unit tests**, and **defer durable infrastructure to the first real writer (1.6)**. 1.5 follows it precisely for the write side. Timo **LOCKED** this deferral pattern for 1.4 — apply the same default here unless overridden.
- **`ErrorDescriptor` failure pattern (1.4):** `NotAMemberException` wrapped `ErrorDescriptor.of("identity.notAMember", …)`. Reuse the shape for `ConcurrencyConflictException("concurrency.staleVersion", …)`. The client already mirrors `{code,message,details}` as `AppError` (Story 1.3) — so a future Epic 5 conflict UI can localize `concurrency.staleVersion` with no backend change.
- **ArchUnit is a first-class guardrail (1.4 added `NoPersistedPersonalDataTest` with a bespoke rule).** Adding the shared-kernel purity rule here is squarely in that groove.
- **`shared` is where cross-context vocabulary goes (1.4 put `MemberId`/`HouseholdId` there for exactly the slice-rule reason).** Same call for the envelope.
- **Backend test reality:** 30/30 backend tests currently pass; keep them green. Java 25 + Spring Boot 4.1; `./gradlew test` runs ArchUnit + unit + the MockMvc security suite. No live Keycloak/KurrentDB needed for the recommended path.
- **Git:** solo, **direct-to-`main`** (no feature branches; that switches at beta/Epic 4 per memory `git-workflow`). Baseline for this story = `1e13925`.

### Latest tech notes (KurrentDB 25.x / Java 25 / Spring Boot 4.1)
- **KurrentDB = renamed EventStoreDB.** The compose image is `docker.kurrent.io/kurrent-latest/kurrentdb:25.1.4`; the Java client package renamed from `com.eventstore:db-client-java` to the KurrentDB client (`io.kurrent:kurrentdb-client`) — **the ArchUnit domain-purity rule already bans both `io.kurrent..` and `com.eventstore..`**, so whichever is used stays out of the domain/kernel. Only relevant **if** Clarification 1 = "wire now"; the recommended path adds no client at all.
- **KurrentDB optimistic concurrency maps directly to this envelope:** `appendToStream` takes an **expected revision** (`StreamState.NoStream` / `StreamRevision(n)`) and throws `WrongExpectedVersionException` on mismatch — a clean 1:1 with `AggregateVersion` (new-stream sentinel + numeric revision) and `ConcurrencyConflictException`. Idempotency is typically recorded via **event metadata / an idempotency key**; design the port so the in-memory double and the eventual KurrentDB adapter honor the identical contract. This mapping is the reason the port shape can be finalized now even while deferring the client — the risk that "the port won't map to KurrentDB" is low.
- **Deterministic `commandId` (AC3):** use a **name-based UUID (v5)** over the triggering event id with a fixed SGART namespace — deterministic, collision-resistant, no state. Java has no built-in v5 factory; implement the small SHA-1-based derivation (or a documented equivalent) in `CommandId.deterministicFrom`. A unit test pins its stability so a refactor can't silently change the derivation (which would break exactly-once).
- **No Flutter/native changes.** This story is entirely `backend/`.

### Project Structure Notes
```text
backend/src/main/java/de/sgart/shared/
  CommandId.java                     # opaque client command id; generate/fromString/deterministicFrom(EventId) (new)
  EventId.java                       # opaque domain-event id (new)
  AggregateVersion.java              # optimistic-concurrency token / basedOnVersion; new-stream sentinel (new)
  StreamId.java                      # typed event-store stream key: household-/list-/trip-{id} (new)
  Command.java                       # envelope interface: commandId() + basedOnVersion() (new)
  DomainEvent.java                   # event marker interface: eventId() (new)
  EventSourcedAggregate.java         # base: version tracking, replay(apply), raise/uncommitted events (new)
  EventStore.java                    # port: append(expected-version, idempotent by commandId), readStream (new)
  ConcurrencyConflictException.java  # wraps ErrorDescriptor(concurrency.staleVersion) (new)
backend/src/test/java/de/sgart/shared/
  CommandIdTest.java, EventIdTest.java, AggregateVersionTest.java, StreamIdTest.java  (new)
  EventSourcedAggregateTest.java     # replay/version/raise behavior (new)
  EventStoreContractTest.java        # expected-version + idempotency via InMemoryEventStore (new)
backend/src/test/java/de/sgart/shared/support/   # test-scope fixtures (NOT in main)
  InMemoryEventStore.java            # EventStore double: expected-version + commandId dedupe (new, test-only)
  CounterAggregate.java + Increment/Incremented   # synthetic reference aggregate proving the contract (new, test-only)
backend/src/test/java/de/sgart/architecture/
  HexagonalArchitectureTest.java     # ADD shared-kernel purity rule; keep existing four green (modified)
```
- Everything production ships in `de.sgart.shared` (cross-context kernel). The **reference aggregate and in-memory store are test-only** — no throwaway domain concept enters `main` (YAGNI). One class per concern (SRP); no abbreviations.
- **Do not touch** `collaboration`, `identity`, `storereference`, `priceintelligence`, `app/`, `docker-compose.yml`, or Keycloak in the recommended path.

### Testing standards
[Source: CLAUDE.md §6; ARCHITECTURE-SPINE Testing convention (line 146); NFR6]
- **Domain-first, fast, no infrastructure:** the entire contract is provable with pure unit tests (this is what AC4/NFR6 demands). No Spring, no DB, no network, no live KurrentDB in the recommended path.
- **Behavior, not structure:** assert emitted events, rebuilt state + version after replay, conflict-on-stale, no-op-on-duplicate-commandId, stable deterministic derivation. Full-sentence names, e.g. `rejectsAnAppendWhoseBasedOnVersionIsBehindTheStream`, `appliesTheSameCommandIdOnlyOnce`, `derivesTheSameCommandIdFromTheSameTriggeringEvent`, `rebuildsAggregateStateByReplayingItsEvents`.
- **CQRS coverage (CLAUDE.md §6):** test commands for the events they emit and the concurrency behavior; there is no query/read model yet, so the "queries return read models" half is first exercised in 1.6 — note it, don't fake it.
- **Synthetic data only:** the reference aggregate is an abstract counter; no personal data anywhere (there is none in the write substrate — a natural DSGVO-clean story). Arrange–Act–Assert; one behavioral focus per test; deterministic (the UUID-v5 derivation is deterministic by design; if any test needs a random id, generate it in Arrange).
- **Keep green:** all Story 1.1–1.4 backend suites (30/30) stay passing; the four existing ArchUnit rules stay green; the fifth (shared-kernel purity) goes green new. A red build blocks merge (NFR6).

### References
- [Source: epics.md#Story 1.5: Command & concurrency envelope] (lines 320–342) — user story + the four ACs
- [Source: epics.md] AR1/AR3 (lines 95, 97), AR7 (line 101), AR9 (line 103), AR10 (line 104), NFR5 (line 78), NFR6 (line 80) — the requirement IDs the ACs realize
- [Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md] AD-1 (line 64), AD-4 (line 82), AD-8 (line 106), AD-10 (line 118), AD-11 (line 124); Consistency Conventions — commands/events/streams (lines 135–138), State mutation (line 143), Error shape (line 141)
- [Source: specs/spec-sgart/glossary.md] — Offline Queue, Live Sync, MemberId (binding terms; "target aggregate root's version + client command-id")
- [Source: backend/src/main/java/de/sgart/shared/{Identifier,ErrorDescriptor,Money,MemberId}.java] — the record/value-object + error-shape patterns to mirror
- [Source: backend/src/test/java/de/sgart/architecture/HexagonalArchitectureTest.java] — the four permanent ArchUnit rules; where the new kernel-purity rule goes
- [Source: implementation-artifacts/1-4-sign-in-with-keycloak-resolve-membership-identity.md] — the "contract + in-memory + defer durable infra to 1.6" precedent, the `contextLoads`/eager-boot trap pattern, the `ErrorDescriptor`-in-exception pattern, and the `shared`-placement reasoning
- [Source: docker-compose.yml] — KurrentDB 25.1.4 + PostgreSQL 18.6 already running (for 1.6 to wire against)
- [Source: CLAUDE.md] — Clean Code, no-abbreviations naming, DDD/CQRS layering, TDD + fast domain-first unit tests
- [Source: memory `flutter-test-local`, `git-workflow`, `bmad-flow-state`] — run tests locally for real; direct-to-`main` pre-beta; resume point

## Clarifications (LOCKED by Timo 2026-08-23 — both confirmed as the recommended default)

1. **Real KurrentDB adapter now, or defer to Story 1.6 (first real writer)?** — ✅ **LOCKED: DEFER.** Ship the `EventStore` **port + expected-version/idempotency contract + `EventSourcedAggregate` base**, prove the *entire* behavior with a **test-only `InMemoryEventStore` + synthetic reference aggregate** (fast unit tests — exactly what AC4/NFR6 asks), and let **Story 1.6's `Household`** be the first component to wire the real `io.kurrent` client behind the unchanged port. This mirrors 1.4's LOCKED deferral of the PostgreSQL mapping table to its first writer, avoids the KurrentDB eager-boot/CI trap, and avoids building a client against a throwaway stream with no aggregate (YAGNI). *Alternative:* wire a thin real KurrentDB adapter now + one Testcontainers integration test, to validate the port against KurrentDB's real `appendToStream`/`WrongExpectedVersionException` API before 1.6 depends on it (de-risks the port at the cost of some throwaway wiring and the eager-boot trap).

2. **Envelope placement: `de.sgart.shared` (recommended) vs. `collaboration.domain`.** — ✅ **LOCKED: `shared`.** The envelope is cross-context (Collaboration + later Price Intelligence); putting it in `collaboration.domain` would force Price Intelligence to depend on the Collaboration domain, breaking the AD-2 context-slice ArchUnit rule — the same reasoning that put `MemberId`/`HouseholdId` in `shared` in 1.4. *Alternative:* `collaboration.domain` if you decide the write substrate is Collaboration-owned for MVP and Price Intelligence will get its own copy later (rejected by default — duplicates the kernel).

3. **Reference aggregate + in-memory store are test-scope only (recommended) vs. shipped in `main`.** — ✅ **LOCKED: test-only** (follows from #1's deferral). No production consumer of the `EventStore` port exists until 1.6, and inventing a `CounterAggregate` in `main` is a throwaway domain concept. *Alternative:* ship `InMemoryEventStore` in `adapter.out` as a dev profile (as 1.4 shipped `InMemoryMemberMappingRepository`) — only worth it if something in 1.5 actually needs a running store, which nothing does.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Claude Opus 4.8)

### Debug Log References

- `./gradlew test` — 67 tests, 0 failures, 0 errors, 0 skipped (baseline 33 kept green + 34 new).
- `./gradlew clean compileJava compileTestJava` — no compiler/analyzer warnings.
- Pinned deterministic derivation cross-checked against Python `uuid.uuid5` (identical SHA-1 name-based algorithm): namespace `6b1f4d2e-0c3a-5e77-9b8a-2d4c6f8a1e00`, event id `11111111-…-111111111111` → `9dfed3d1-9ae0-5191-adb4-31f7f93ecae8`.

### Completion Notes List

- **Scope respected (both clarifications LOCKED):** real KurrentDB adapter deferred to Story 1.6; the whole contract is proven by a test-only `InMemoryEventStore` + synthetic `CounterAggregate`. Envelope lives entirely in `de.sgart.shared` (cross-context kernel). No `collaboration`/`identity`/`app/`/`docker-compose.yml`/Keycloak files touched; no new dependency added; no bean connects at boot (eager-boot/CI trap sidestepped by design — nothing wires a store).
- **AC1 (command → aggregate → event; expected-version append; read-models-projection-only):** `EventSourcedAggregate` folds state only in `apply`, so replay and live handling share one path; the `EventStore` port is the handler's sole persistence collaborator (a handler structurally cannot write a read model — no read-model port exists). The projection-only rule is captured as the enforced convention the first projector (1.6) realizes.
- **AC2 (envelope + stale-write rejection + idempotent replay):** `Command` carries `commandId` + `basedOnVersion` (the target root's own stream version); append rejects a stale `basedOnVersion` with `ConcurrencyConflictException` (`concurrency.staleVersion`, `ErrorDescriptor` shape mirroring 1.4's `NotAMemberException`) and writes nothing; a re-applied `commandId` is a silent no-op — even after the stream advanced (proven by `aReplayedCommandIdNeverSurfacesAConflictEvenAfterTheStreamAdvanced`).
- **AC3 (deterministic process-manager command id):** `CommandId.deterministicFrom(EventId)` is a name-based UUID v5 (SHA-1, fixed SGART namespace) — pure and stable; same event id → same command id → idempotency collapses the re-delivery to a no-op. A pinned magic-value test prevents a silent refactor from changing the derivation.
- **AC4 (fast unit tests, NFR6):** all behavior proven with pure JUnit 5 + AssertJ, no Spring/DB/network. New ArchUnit rule `sharedKernelIsFreeOfInfrastructure` keeps the kernel free of framework/persistence/event-store/adapter types; the existing four rules stay green.
- **CQRS note:** commands are tested for the events they emit and the concurrency behavior; there is no query/read model yet, so the "queries return read models" half is first exercised in Story 1.6 (noted, not faked).

### File List

**Added — main (`backend/src/main/java/de/sgart/shared/`):**
- `EventId.java`
- `CommandId.java`
- `AggregateVersion.java`
- `StreamId.java`
- `Command.java`
- `DomainEvent.java`
- `EventSourcedAggregate.java`
- `EventStore.java`
- `ConcurrencyConflictException.java`

**Added — test (`backend/src/test/java/de/sgart/shared/`):**
- `EventIdTest.java`
- `CommandIdTest.java`
- `AggregateVersionTest.java`
- `StreamIdTest.java`
- `CommandAndDomainEventContractTest.java`
- `EventSourcedAggregateTest.java`
- `EventStoreContractTest.java`
- `support/CounterAggregate.java` (test-only synthetic reference aggregate)
- `support/InMemoryEventStore.java` (test-only `EventStore` double)

**Modified:**
- `backend/src/main/java/de/sgart/shared/package-info.java` (document the write-side kernel)
- `backend/src/test/java/de/sgart/architecture/HexagonalArchitectureTest.java` (add `sharedKernelIsFreeOfInfrastructure` rule; existing four untouched)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status → in-progress → review)

## Change Log

| Date | Change |
| --- | --- |
| 2026-08-23 | Story created via bmad-create-story. Pure write-model substrate: the command → aggregate → domain event envelope (`Command`/`DomainEvent`/`CommandId`/`EventId`/`AggregateVersion`/`StreamId`), the `EventSourcedAggregate` base, the `EventStore` port with expected-version optimistic concurrency + `commandId` idempotent replay, deterministic process-manager `commandId` derivation, and `ConcurrencyConflictException` — all in the `shared` kernel, proven by a test-only in-memory store + synthetic reference aggregate (fast unit tests, NFR6). Real KurrentDB adapter, read models/projectors, process managers, and any aggregate deferred to their first real user (Story 1.6+). Two load-bearing scope calls surfaced as Clarifications (defer KurrentDB; `shared` placement). Status → ready-for-dev. |
| 2026-08-23 | Both clarifications LOCKED by Timo (recommended defaults): (1) defer the real KurrentDB adapter to Story 1.6; prove the full contract with a test-only `InMemoryEventStore` + synthetic reference aggregate. (2) envelope lives in `de.sgart.shared` (cross-context kernel). #3 (reference aggregate + in-memory store are test-only) follows from #1. Ready for `bmad-dev-story` (Sonnet 5). |
| 2026-08-23 | Implemented (Opus 4.8): shared-kernel write-side envelope — `Command`/`DomainEvent` contracts, `CommandId` (with UUID-v5 `deterministicFrom`)/`EventId`/`AggregateVersion`/`StreamId` value types, `EventSourcedAggregate` base, `EventStore` port with expected-version optimistic concurrency + `commandId` idempotent replay, and `ConcurrencyConflictException` (`concurrency.staleVersion`). Proven by a test-only `InMemoryEventStore` + synthetic `CounterAggregate`; added ArchUnit `sharedKernelIsFreeOfInfrastructure` rule. 67 tests green (33 baseline + 34 new), zero warnings, no `app/`/context/infra files touched. Status → review. |
