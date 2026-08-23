# SGART — Smart Grocery And Receipt Tracker

Collaborative, privacy-first (DSGVO/GDPR) household grocery and receipt-tracking app.

This repository is a **greenfield scaffold** (Story 1.1). It stands up a consistent, runnable
substrate — a Spring Boot modular monolith, a Flutter client, and local infrastructure — so every
later story starts from the same shape. There is no business behaviour yet.

Architecture paradigm: **DDD + CQRS + Event Sourcing, hexagonal (ports & adapters), in a modular
monolith.** The authoritative design lives in
[`_bmad-output/planning-artifacts/architecture/.../ARCHITECTURE-SPINE.md`](_bmad-output/planning-artifacts/architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md).

## Repository layout

```text
backend/   Spring Boot 4.1 (Java 25) modular monolith
             src/main/java/de/sgart/
               collaboration/    domain / application / adapter/in / adapter/out   (core)
               storereference/   domain / application / adapter/in / adapter/out   (supporting)
               identity/         domain / application / adapter/in / adapter/out   (Keycloak ACL)
               priceintelligence/  reserved-empty (Post-MVP)
               shared/           shared kernel: Money, Quantity, Identifier, error shape
app/       Flutter 3.44 client, feature-first with BLoC
docker-compose.yml   Local infra: Keycloak, KurrentDB, PostgreSQL
.github/workflows/   CI pipeline
```

The domain layer never depends on any framework, persistence, or transport type. This is enforced
by an automated architecture test (`backend/.../HexagonalArchitectureTest.java`, AD-1) that fails
the build on violation.

## Prerequisites

| Tool | Version | Notes |
| --- | --- | --- |
| JDK | 25 (LTS) | e.g. Temurin/OpenJDK 25 |
| Flutter / Dart | Flutter 3.44 (stable) | bundles Dart |
| Docker + Compose | recent | for local infra |

The backend uses the Gradle **wrapper** (`./gradlew`) — no separate Gradle install needed.

## Backend

```bash
cd backend
./gradlew test    # compile + run unit and architecture/dependency tests
./gradlew build   # full build
./gradlew bootRun # run the application (needs local infra, see below) — listens on :8081
```

The backend listens on **`:8081`**, not the Spring Boot default `:8080` — Keycloak already owns
`:8080` in local dev, so both can run side by side.

The test suite includes:
- **Domain unit tests** — pure, no infrastructure (e.g. `MoneyTest`, `QuantityTest`).
- **Architecture tests** — domain purity, hexagonal dependency direction, and cross-context
  isolation (`HexagonalArchitectureTest`). These are dormant on empty packages and begin enforcing
  automatically as code is added.
- **Context smoke test** — the Spring context wires up (`SgartApplicationTest`).

## Client

```bash
cd app
flutter pub get
flutter analyze
flutter test
flutter run       # on a connected device/emulator
```

## Local infrastructure

```bash
cp .env.example .env    # dev-only credentials; .env is gitignored
docker compose up -d     # Keycloak, KurrentDB, PostgreSQL
docker compose ps        # all three report "healthy"
docker compose down      # stop (volumes persist)
```

| Service | Image | Port(s) | Purpose |
| --- | --- | --- | --- |
| PostgreSQL | `postgres:18.6` | 5432 | read-model projections (CQRS read side) |
| KurrentDB | `docker.kurrent.io/kurrent-latest/kurrentdb:25.1.4` | 2113 | event store (write model) |
| Keycloak | `quay.io/keycloak/keycloak:26.7.0` | 8080, 9000 | identity provider |

Credentials are **dev-only**. Production topology (managed secrets, TLS, backups, resource limits)
is deliberately deferred.

### Keycloak dev realm

Keycloak imports the **`sgart`** realm from [`keycloak/realm-sgart.json`](keycloak/realm-sgart.json)
on every `docker compose up` (`--import-realm`), so every machine and CI run gets an identical,
reproducible realm — nothing is configured by hand in the admin console.

- **Issuer:** `http://localhost:8080/realms/sgart`
- **JWKS:** `http://localhost:8080/realms/sgart/protocol/openid-connect/certs`
- **App client:** `sgart-app` — public, native (no client secret), Authorization Code + PKCE
  (S256) only, redirect URI `de.sgart.app://oauth/callback`
- **Dev sign-in users** (synthetic, clearly fake — CLAUDE.md §6): `anna@example.test` /
  `anna-dev-password`, `ben@example.test` / `ben-dev-password`

This realm is **dev-only** and must never be reused for production.

## Continuous integration

`.github/workflows/ci.yml` runs the backend build+tests and the Flutter analyze+test on every push
and pull request to `main`. A red build must block merge: enable **branch protection** on `main`
and require the `Backend (Spring Boot / Java 25)` and `Client (Flutter 3.44)` checks to pass. That
setting lives in the repository settings, not in the workflow file.
