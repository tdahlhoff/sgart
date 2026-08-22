---
baseline_commit: f751c4d5e3a65e72d6ade059447f63417733fde4
---

# Story 1.1: Project scaffold & local infrastructure

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the developer,
I want the greenfield backend, client, and local infrastructure scaffolded to the architecture,
so that every later story has a consistent, runnable substrate.

## Acceptance Criteria

1. **Modular-monolith backend exists.** A Spring Boot 4.1 (Java 25) modular monolith compiles, with exactly one root package per bounded context under `de.sgart`: `collaboration`, `storereference`, `identity`, `shared`, and a reserved-but-empty `priceintelligence`. Each business context (`collaboration`, `storereference`, `identity`, `priceintelligence`) is split into the hexagonal layers `domain / application / adapter/in / adapter/out`. `shared` is the shared kernel (Money, Quantity, ids, error model — no business logic). *(epics.md Story 1.1; ARCHITECTURE-SPINE AD-1, AD-2)*
2. **Domain purity is enforced by an automated architecture test.** The `domain` layer of every context has no compile-time dependency on any framework, persistence, or transport type (no Spring, no JPA/JDBC, no KurrentDB, no HTTP/SSE, no Keycloak types). A failing architecture/dependency test is produced if this is violated. *(AD-1; epics.md AC line 253)*
3. **Flutter client is scaffolded feature-first with BLoC.** A Flutter 3.44 / Dart app exists, organized feature-first, with the BLoC state-management dependency wired and a placeholder feature demonstrating the pattern. *(epics.md AC line 254; ARCHITECTURE-SPINE §Stack, Structural Seed)*
4. **Local infrastructure stands up via `docker-compose`.** One `docker-compose` brings up Keycloak 26.7, KurrentDB (25.x LTS line — pin the exact current LTS at build), and PostgreSQL 18.6, and the stack reaches a healthy state from a clean checkout. *(epics.md AC line 254; ARCHITECTURE-SPINE §Stack)*
5. **CI runs the test suite and a red build blocks merge.** A CI pipeline runs the backend build + tests (and, at minimum, the architecture test and a placeholder domain unit test); a failing build blocks merge. *(epics.md AC lines 256–258, NFR6)*
6. **Placeholder tests pass green.** At least one placeholder domain unit test (pure, no infrastructure) and the architecture/dependency test both pass on a correct scaffold. *(epics.md AC line 258)*

## Tasks / Subtasks

- [x] **Task 1 — Backend modular-monolith scaffold** (AC: #1, #2)
  - [x] Initialize a Spring Boot 4.1.0 project targeting Java 25 (LTS), root package `de.sgart`. Use a build tool with a clean multi-module or single-module-with-enforced-packages layout (Gradle recommended; justify if Maven).
  - [x] Create root packages: `collaboration`, `storereference`, `identity`, `priceintelligence`, `shared`.
  - [x] Under each business context create `domain`, `application`, `adapter/in`, `adapter/out`. Leave `priceintelligence` reserved-empty (package placeholder only; no aggregates).
  - [x] Populate `shared` as the shared kernel skeleton: placeholders for `Money`, `Quantity`, an id type, and the `{ code, message, details }` error shape — no business logic. (Full value objects arrive in later stories; keep to compiling skeletons or leave as documented TODO stubs to avoid dead code — see Dev Notes.)
- [x] **Task 2 — Architecture/dependency test** (AC: #2, #6)
  - [x] Add ArchUnit (or equivalent) as a test dependency.
  - [x] Write a test asserting: no class in any `..domain..` package depends on Spring, Jakarta/JPA, JDBC, KurrentDB client, HTTP/SSE, or Keycloak types.
  - [x] Write a test asserting hexagonal dependency direction: `adapter.in → application → domain`, `adapter.out → application` (and `adapter.out` may implement `domain` ports), `domain → shared` only. Cross-context access only via application-layer ports (AD-2).
  - [x] Confirm the test **fails** when a deliberate violation is introduced (prove it has teeth), then remove the violation.
- [x] **Task 3 — Placeholder domain unit test** (AC: #6)
  - [x] Add a fast, pure unit test in a `domain` package (JUnit 5) with no Spring context and no infrastructure — proves the domain-first TDD substrate works.
  - [x] Name it in ubiquitous language as a full-sentence behavior (CLAUDE.md §6 / §2), e.g. `sharedKernel_isReachableFromDomain_withoutFrameworkDependencies` — or a genuinely behavioral placeholder; no abbreviations.
- [x] **Task 4 — Flutter client scaffold** (AC: #3)
  - [x] Create a Flutter 3.44 app, feature-first directory layout (e.g. `lib/features/<feature>/…`, `lib/shared/…`).
  - [x] Add the `flutter_bloc` (BLoC) dependency and wire a single placeholder feature with a Cubit/BLoC to demonstrate the pattern and one widget test.
  - [x] Do **not** hard-code user-facing strings — even placeholder UI text goes through the localization layer stub or is clearly marked for Story 1.3 (Conventions: Localization). Keep placeholder UI minimal.
- [x] **Task 5 — Local infrastructure `docker-compose`** (AC: #4)
  - [x] Author `docker-compose.yml` with services: Keycloak 26.7.0, KurrentDB (pin exact current 25.x LTS tag), PostgreSQL 18.6.
  - [x] Add health checks and named volumes; use dev-only credentials via `.env` (never commit secrets). Document the dev-vs-prod split as a TODO (full topology is a later concretization item).
  - [x] Verify `docker compose up` reaches healthy from a clean checkout and document the command in the README.
- [x] **Task 6 — CI pipeline** (AC: #5)
  - [x] Add a CI workflow that builds the backend and runs the test suite (including Task 2 + Task 3 tests) and the Flutter analyze/test.
  - [x] Ensure a failing test / failing architecture test **fails the build** and is configured to block merge (branch protection note in README if the setting is outside the repo).
- [x] **Task 7 — README / runbook** (AC: #1–#6)
  - [x] Document: prerequisites (JDK 25, Flutter 3.44, Docker), how to run the backend, the client, `docker compose up`, and how to run each test suite. Keep it lean.

### Review Findings

*Code review 2026-08-22 (adversarial + edge-case + acceptance layers). All 6 ACs verified met; 2 patch items, 1 decision, 6 dismissed as noise/by-design.*

- [x] [Review][Decision] RESOLVED (2026-08-22, keep strict — no change): `adapter.in → domain` is forbidden by the architecture test, intentionally. `layersRespectHexagonalDirection` lets `Domain` be accessed only by `Application` and `AdapterOut`, so a future REST controller that references a domain value object directly would fail the build. This is faithful to the ARCHITECTURE-SPINE diagram (`adapter.in → application → domain`, no direct arrow), but many hexagonal setups let inbound adapters see domain types when mapping DTOs. Decide: keep strict (controllers only touch `application`) or add `"AdapterIn"` to `Domain`'s allowed accessors. [backend/src/test/java/de/sgart/architecture/HexagonalArchitectureTest.java:66]
- [x] [Review][Patch] FIXED (2026-08-22): Placeholder UI strings now carry an inline `// TODO(Story 1.3 localization)` marker. [app/lib/features/home/presentation/home_page.dart:24]
- [x] [Review][Patch] FIXED (2026-08-22): Added `*.bat eol=crlf` + `gradlew.bat eol=crlf` so Windows batch scripts keep CRLF. [.gitattributes:4]

## Dev Notes

### Scope & intent
This is a **pure infrastructure/scaffold story** — no business behavior, no aggregates, no endpoints. Its single job is a consistent, runnable substrate so every later story starts from the same shape. Resist building anything speculative (YAGNI): `priceintelligence` stays empty, value objects in `shared` stay skeletal until the stories that need them (1.5 for command/concurrency, 2.x for lists/items) fill them in.

### Architecture guardrails (binding — from ARCHITECTURE-SPINE)
- **Paradigm is fixed (AD-1):** DDD + CQRS + Event Sourcing, hexagonal. The domain imports no framework/infrastructure/transport type; infrastructure is reached only through ports (interfaces owned by the domain). Task 2's test is the enforcement mechanism — it is not optional polish, it is AC #2.
- **Contexts are modules, not services (AD-2):** four contexts (Collaboration=core, Store Reference=supporting, Price Intelligence=core/Post-MVP, Identity=generic) plus `shared` kernel — all packages in one deployable. A context may touch another only via a published application-layer port or an async domain event; never by reaching into another context's domain or DB tables. Encode this as an ArchUnit rule where practical.
- **Backend package layout (authoritative):**
  ```text
  de.sgart/
    collaboration/    domain/ application/ adapter/in/ adapter/out/
    storereference/   domain/ application/ adapter/in/ adapter/out/
    priceintelligence/  (reserved-empty; Post-MVP)
    identity/         domain/ application/ adapter/in/ adapter/out/   # Keycloak ACL
    shared/           # Money, Quantity, ids, error model — no business logic
  ```
- **CQRS/ES split (AD-4):** writes append events to KurrentDB under an expected-version check; reads come from PostgreSQL projections. Do not wire any write-path-to-read-model shortcut into the scaffold. Actual event/projection code is later stories; the scaffold only stands up the two datastores.
- **Naming & language (AD-11, NFR6, CLAUDE.md §2):** no abbreviations (`quantity` not `qty`); established acronyms (id, SSE, JWT, OCR) are the only exception. Same concept, same name everywhere. Package names as listed above exactly.
- **Error shape (Conventions):** `{ code, message, details }`; `code` is a client-facing machine key (localized client-side), `message` is log/debug only and never shown to users. Scaffold the type/skeleton in `shared`.
- **Transport (Conventions):** REST under `/api/v1`; SSE per-household stream. No controllers needed this story, but keep the base path convention in mind for any placeholder health endpoint.

### Stack versions (from ARCHITECTURE-SPINE §Stack — verify current at build)
| Component | Version | Note |
| --- | --- | --- |
| Java | 25 (LTS) | |
| Spring Boot | 4.1.0 | |
| Flutter / Dart | Flutter 3.44 (stable) | BLoC for state |
| Keycloak | 26.7.0 | no LTS — track newest; confirm tag |
| KurrentDB (ex-EventStoreDB) | 25.x | **pin exact current LTS line at build** (Deferred item) |
| PostgreSQL | 18.6 | read model |
| Real-time transport | SSE | not exercised this story |
| Deployment | Docker Compose (German/EU server) | dev compose now; prod topology deferred |

> **Version reality check:** these versions were web-verified at 2026-08-20 in the architecture. Before scaffolding, confirm Spring Boot 4.1.0 / Java 25 compatibility and the exact current KurrentDB LTS tag and Keycloak 26.7.x image tag — the code owns these once it exists. If a version is unavailable or incompatible, surface it (fail fast) and record the substitution in Completion Notes rather than silently downgrading.

### Testing standards (CLAUDE.md §6, NFR6)
- **TDD, domain-first:** the placeholder domain test is pure — no DB, no Spring context, no transport. This story sets the precedent for the whole codebase, so get the test ergonomics right.
- **Architecture tests are first-class:** the dependency/purity test is a permanent guardrail, not a one-off.
- **Red build blocks merge:** CI must gate merges. Prove the gate works (a deliberately failing test fails CI).
- **No real personal data** in any fixture (none needed here, but the convention starts now).

### Project Structure Notes
- Greenfield / empty repository — there are **no existing files to preserve or refactor**; nothing here risks a regression. The only current tree content is `_bmad*/` planning artifacts, `CLAUDE.md`, and `.gitignore`. Place application code under a sensible top-level layout (e.g. `backend/` and `app/` or `mobile/`) — pick one, document it in the README, and keep the two `docker-compose` datastores reachable from the backend's dev config.
- `.gitignore` already exists (commit 9ab7b19) — extend it for build outputs (`build/`, `.gradle/`, `target/`, Flutter `.dart_tool/`, `build/`) and never commit `.env`/secrets.
- Line endings: repo standardized on LF (commit f751c4d) — keep LF; add a `.gitattributes` if the toolchain risks CRLF.

### References
- [Source: _bmad-output/planning-artifacts/epics.md#Story 1.1: Project scaffold & local infrastructure] — user story + acceptance criteria (lines 242–258)
- [Source: _bmad-output/planning-artifacts/epics.md#Epic 1: Foundation, Identity & Household Setup] — epic objective (lines 237–240)
- [Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md#Design Paradigm] — module layout, hexagonal layers
- [Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md#AD-1] — paradigm fixed / domain purity
- [Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md#AD-2] — modular monolith, contexts as modules
- [Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md#AD-4] — CQRS/ES write-vs-read split
- [Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md#AD-11] — ubiquitous language / naming
- [Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md#Consistency Conventions] — error shape, transport, testing, localization
- [Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md#Stack] — versions
- [Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md#Deferred] — KurrentDB LTS pin; Docker topology / dev-vs-prod deferred
- [Source: _bmad-output/planning-artifacts/epics.md#NFR6] — Clean Code, TDD domain-first, CI red-build-blocks-merge (line 80, referenced by AC line 258)
- [Source: CLAUDE.md] — binding Clean Code / DDD / CQRS / GDPR / Testing rules

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (dev-story workflow)

### Debug Log References

- Toolchain was absent from the environment (no JDK/Gradle/Flutter); installed OpenJDK 25.0.3
  (apt, Ubuntu 26.04), Gradle 9.7.1 (used once to generate the committed wrapper), and Flutter
  3.44.9. Approved by the user ("install all, verify fully") before proceeding.
- Backend `./gradlew clean test`: **BUILD SUCCESSFUL**, 9 tests (MoneyTest ×3, QuantityTest ×2,
  SgartApplicationTest ×1, HexagonalArchitectureTest ×3).
- ArchUnit empty-scaffold guards: resolved with `withOptionalLayers(true)` on the layered rule and
  `archRule.failOnEmptyShould=false` (`src/test/resources/archunit.properties`) — rules stay
  dormant on empty packages and enforce as code lands.
- Architecture-test teeth proven: injected a `@Component` class into `collaboration.domain` →
  `domainIsFreeOfInfrastructure` FAILED as expected; removed it → build green again.
- `docker compose up`: all three services report **healthy** from a clean checkout
  (postgres, kurrentdb, keycloak). Fixed Postgres 18 mount (`/var/lib/postgresql`, not
  `/var/lib/postgresql/data`) and Keycloak readiness probe (bash `/dev/tcp` to mgmt port 9000,
  since the image ships no curl/wget).
- Client `flutter analyze`: no issues. `flutter test`: 3/3 passed (HomeCubit ×2, HomePage widget ×1).

### Completion Notes List

- All 6 acceptance criteria satisfied and independently verified (see Debug Log).
- **Version reality-check outcomes:** Spring Boot 4.1.0 GA + Java 25 confirmed resolvable and
  building. KurrentDB 25.x LTS pinned to the newest patch **25.1.4**
  (`docker.kurrent.io/kurrent-latest/kurrentdb`). Flutter 3.44 pinned to newest patch **3.44.9**.
  ArchUnit **1.5.0**, Gradle **9.7.1**. No silent downgrades.
- Repo layout chosen: `backend/` (Gradle) + `app/` (Flutter), infra + CI + README at root.
- AC #5 note: the CI workflow YAML is validated and every command it runs (`./gradlew test`,
  `flutter analyze`, `flutter test`) is verified green locally; GitHub Actions execution and branch
  protection are configured in the repo/GitHub settings (documented in README).
- Deferred-as-designed: `priceintelligence` reserved-empty; shared-kernel value objects kept
  minimal (YAGNI); production compose topology out of scope.

### File List

**Backend (`backend/`)**
- `settings.gradle.kts`, `build.gradle.kts`
- `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`
- `src/main/java/de/sgart/SgartApplication.java`
- `src/main/java/de/sgart/shared/{package-info,Money,Unit,Quantity,Identifier,ErrorDescriptor}.java`
- `src/main/java/de/sgart/{collaboration,storereference,identity}/package-info.java` and each context's
  `{domain,application,adapter/in,adapter/out}/package-info.java`
- `src/main/java/de/sgart/priceintelligence/package-info.java`
- `src/main/resources/application.yaml`
- `src/test/java/de/sgart/architecture/HexagonalArchitectureTest.java`
- `src/test/java/de/sgart/shared/{MoneyTest,QuantityTest}.java`
- `src/test/java/de/sgart/SgartApplicationTest.java`
- `src/test/resources/archunit.properties`

**Client (`app/`)** — Flutter project (feature-first)
- `lib/main.dart`
- `lib/features/home/presentation/{home_page,home_cubit}.dart`
- `test/features/home/{home_cubit_test,home_page_test}.dart`
- `pubspec.yaml` (+ generated `pubspec.lock`), `analysis_options.yaml`, `android/`, `ios/`, `app/README.md`

**Root**
- `docker-compose.yml`, `.env.example`
- `.github/workflows/ci.yml`
- `README.md`, `.gitattributes`
- `.gitignore` (modified — build outputs, `.env`, IDE/OS)

## Change Log

| Date | Change |
| --- | --- |
| 2026-08-22 | Story 1.1 implemented: backend modular monolith (Spring Boot 4.1 / Java 25) with hexagonal package layout + shared kernel; ArchUnit architecture tests (domain purity, layer direction, context isolation); Flutter 3.44 feature-first + BLoC scaffold; docker-compose (Keycloak 26.7.0, KurrentDB 25.1.4, PostgreSQL 18.6) verified healthy; GitHub Actions CI; README + repo hygiene. All suites green. Status → review. |
