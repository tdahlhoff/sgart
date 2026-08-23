---
baseline_commit: d9c6ba2cf1a78aff1fb7a049d97b31fa4ba36567
---

# Story 1.4: Sign in with Keycloak & resolve membership identity

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a person,
I want to sign in through the household's Keycloak,
so that I'm authenticated without SGART ever holding my credentials.

## Acceptance Criteria

1. **A person authenticates against Keycloak; the client obtains a JWT and stores no credentials; the backend validates that JWT at `adapter.in` and takes the opaque user id only from the token `sub`.** (FR-covered via Epic 1 identity substrate; NFR4, AR10, AD-5)
   - The Flutter app performs an OpenID Connect **Authorization Code + PKCE** flow against the SGART Keycloak realm (public/native client, **no client secret on the device**). SGART never sees or stores a password — only the resulting tokens, held in **OS secure storage** (Keychain / Keystore), never in plain `SharedPreferences` or app state that outlives the session.
   - The backend is a **JWT resource server**: every request under `/api/v1/**` (except explicitly public paths) requires a valid Keycloak-signed JWT — signature verified against Keycloak's JWKS and issuer/audience checked; an absent, malformed, expired, or wrong-issuer token is rejected with `401`.
   - The authenticated principal exposes the Keycloak user id **only from the token `sub` claim**. No endpoint reads a `keycloakUserId` (or email/name) from a request body, path, or query — a single `adapter.in` seam is the sole source of the caller's identity. (AR10: "`keycloakUserId` never in request body/path — taken from the JWT `sub`".)

2. **Within a household context, the Identity ACL resolves `(keycloakUserId, householdId) → MemberId` before any domain code runs, and display name/email are read live from the JWT/Keycloak and never persisted.** (AR4/AD-5, AR5/AD-6)
   - The `identity` context publishes an **application-layer resolution port** that maps `(KeycloakUserId, HouseholdId) → MemberId`. It is the single, reusable seam every later household-scoped backend command/query calls to turn the authenticated caller into the pseudonymous `MemberId` **before the Collaboration domain is touched** — no other component derives a `MemberId`.
   - The ACL is the **sole owner** of the mapping `{householdId, memberId → keycloakUserId}` and the **sole minter** of a `MemberId` (AD-5). **Minting is not triggered in this story** — no household or membership exists yet (creation mints the first `MemberId` in Story 1.6; invite-acceptance in Story 4.2). This story delivers the resolution *contract and behavior*: resolving a **known** mapping returns its `MemberId`; an **unknown** `(keycloakUserId, householdId)` resolves to "not a member" (surfaced as an authorization failure, never a silent new id). Behavior is proven with **synthetic seeded mappings** against the port.
   - Display name and email are **read live** from JWT claims for display only and are **never written** to any SGART store, event, or read model. A person's identity data leaves the system the moment the request ends.

3. **Signing out clears the session/token on the device.** (AC from epics; NFR4)
   - A sign-out action deletes all tokens from secure storage and returns the app to the unauthenticated sign-in gate; a subsequent protected call has no bearer and is rejected. Ending the Keycloak SSO session (end-session / token revocation) is performed where the chosen OIDC library supports it; at minimum the local tokens are destroyed.

## Tasks / Subtasks

- [x] **Task 1 — Keycloak realm & app client (dev infrastructure)** (AC: #1)
  - [x] Create an **SGART realm** with a **public OIDC client** for the Flutter app: standard flow (Authorization Code) enabled, **PKCE (S256) required**, public client (no secret), native/mobile redirect URIs (a custom scheme, e.g. `de.sgart.app://oauth/callback`). Keep the realm **dev-only** and documented as such.
  - [x] Make the realm **reproducible**, not hand-clicked: export it as `keycloak/realm-sgart.json`, mount it into the Keycloak service in `docker-compose.yml`, and add `--import-realm` to the start command so every machine/CI gets identical config. Seed **1–2 synthetic test users** (clearly fake, e.g. `anna@example.test`) for manual sign-in — never real personal data (NFR2, CLAUDE.md §6).
  - [x] Record the issuer URL (`http://localhost:8080/realms/sgart`) and JWKS URL. Update `.env.example` / README "Continuous integration"/setup notes with the realm-import step and the dev sign-in users. Confirm `docker compose up` brings Keycloak up **with the realm already present** (health check still green).

- [x] **Task 2 — Backend: JWT resource-server validation at `adapter.in`** (AC: #1)
  - [x] Add `spring-boot-starter-oauth2-resource-server` (pulls in `spring-boot-starter-security`) to `backend/build.gradle.kts`, and `spring-security-test` as a `testImplementation`.
  - [x] Add a **`SecurityFilterChain`** in `de.sgart.identity.adapter.in.security` (auth is the identity context's inbound concern): stateless, `/api/v1/**` requires authentication, JWT resource-server mode. Keep any actuator/health path public if one exists. CSRF disabled (stateless bearer API). This config is the **only** place transport-level auth lives (AD-1 — framework/security types stay in `adapter.in`, never in `application`/`domain`).
  - [x] Configure the decoder via **`jwk-set-uri`** (not `issuer-uri`) in `application.yaml` under a dev/local profile, **so the Spring context does not eagerly fetch OIDC discovery at boot**. ⚠️ See Dev Notes "Do not break `contextLoads`": adding resource-server naively makes the existing `SgartApplicationTest` fetch Keycloak at startup and fail when it is down. The security config must let the context load offline (test profile supplies a static/derivable decoder or `spring.security.oauth2.resourceserver.jwt` is only bound under the runtime profile).
  - [x] Provide a single **`adapter.in` seam that extracts the caller's `keycloakUserId` from the `sub` claim** and the live display name/email from claims (`preferred_username`/`name`, `email`) — e.g. a small `AuthenticatedCaller` resolver from the `JwtAuthenticationToken`/`@AuthenticationPrincipal Jwt`. Nothing downstream reads identity from anywhere but this seam (AR10).

- [x] **Task 3 — shared kernel: cross-context identity value objects** (AC: #2)
  - [x] Add `MemberId` and `HouseholdId` to `de.sgart.shared` as typed, opaque identifiers (wrap `Identifier`/UUID; PascalCase, no abbreviations). They live in **`shared`, not `identity.domain`**, because Collaboration events and read models reference them and a context's domain must not depend on another context's domain (AR2 / the ArchUnit slice rule). Mirror the existing `Identifier` record style (null-checked, `generate()`/`fromString()`).
  - [x] Do **not** put `KeycloakUserId` in `shared` — it is Keycloak-specific and only the Identity ACL may know it (AD-5). It belongs in the `identity` context (see Task 4). Add a fast unit test per new value object (validation + equality), matching `MoneyTest` style.

- [x] **Task 4 — Identity ACL: resolution port + behavior** (AC: #2)
  - [x] In `de.sgart.identity.domain`, add the pure value object `KeycloakUserId` (string wrapper, non-blank, **no `org.keycloak` import** — it is a plain id the ACL owns) and the mapping value object `MemberMapping` (`householdId`, `memberId`, `keycloakUserId`).
  - [x] Define the **resolution port** in `de.sgart.identity.application` — e.g. `ResolveMemberIdentity` (query use case) backed by a `MemberMappingRepository` **port owned by the domain** (interface only; no persistence types). Semantics: `resolve(KeycloakUserId, HouseholdId) → Optional<MemberId>` (empty = "not a member of that household"). The application service converts empty → a domain/authorization failure using the canonical `ErrorDescriptor` shape (a stable `code`, e.g. `identity.notAMember`; `message` log-only) — never a silent mint.
  - [x] Provide an **in-memory `MemberMappingRepository` adapter** in `de.sgart.identity.adapter.out` for this story (seeded synthetic mappings). **Durable persistence (PostgreSQL) + the mint (write) path are deferred to the first writer — Story 1.6 create-household** (see Clarifications). Document this boundary in the port's Javadoc so the later story adds the JDBC/JPA adapter without changing the contract.
  - [x] Keep the ACL the **sole** place a `MemberId` is minted/resolved (AD-5). Add a package-private/reserved mint seam only if it costs nothing to signal intent; do not build the write path speculatively (YAGNI).

- [x] **Task 5 — Backend: authenticated identity probe endpoint (vertical slice)** (AC: #1, #2, #3)
  - [x] Add `GET /api/v1/identity/me` in `de.sgart.identity.adapter.in` returning the **live** caller identity from JWT claims: `{ keycloakUserId (sub), displayName, email }`. This is the end-to-end proof that JWT validation + `sub`-only extraction + live-claims reading work, **and** the client's post-login identity source (reused by the Profil screen 1.11 and the header). It persists **nothing** and touches **no** domain — it is deliberately household-less (no `MemberId` resolution here, because no household exists yet).
  - [x] Return `401` when unauthenticated. Do **not** echo the raw JWT or any claim beyond the three fields. Confirm the response carries no `Set-Cookie`/session (stateless).

- [x] **Task 6 — Flutter: OIDC sign-in, secure token storage, authenticated client, sign-out** (AC: #1, #3)
  - [x] Add the OIDC + secure-storage + HTTP dependencies (see Dev Notes "Latest tech notes" for the recommended set and the decision flagged in Clarifications). Configure the native platforms for the custom redirect scheme (Android manifest intent-filter / iOS `CFBundleURLTypes`).
  - [x] Implement an **auth feature** (`app/lib/features/auth/…`, BLoC per screen per AR-Stack): a `SignInCubit`/`AuthBloc` that runs Authorization Code + PKCE, stores tokens via `flutter_secure_storage`, exposes authenticated/unauthenticated state, and a **sign-out** that wipes secure storage (+ end-session where supported) and returns to the gate.
  - [x] Add an **authenticated HTTP client** that injects `Authorization: Bearer <access_token>` and maps a backend `{code,message,details}` error body to the existing client **`AppError`** + `localizedMessageForErrorCode` (this is the REST-error-mapping seam **deferred from Story 1.3 to 1.4** — wire it now, minimally, for the `me` call). No token in query/path or logs.
  - [x] Minimal UI proving the flow (no full app shell yet — routing is Story 1.6, Profil is 1.11): an **unauthenticated sign-in gate** ("Anmelden") → on success a small **authenticated placeholder** that calls `GET /identity/me` and shows the live **display name** with an **"Abmelden"** (sign-out) action. All copy via the localization layer (ARB keys English, German values — language policy); reuse `SgartButton`, theme tokens, and the test harness from Stories 1.2/1.3. Replace/absorb the Story 1.1/1.3 placeholder `HomePage` demo behind the auth gate rather than leaving two competing entry screens.

- [x] **Task 7 — Tests** (AC: #1, #2, #3)
  - [x] **Backend security (integration, no live Keycloak):** MockMvc/`@SpringBootTest` with `spring-security-test` `jwt()` post-processors — `GET /api/v1/identity/me` returns `200` + the three live claim fields with a valid mock JWT (assert `keycloakUserId == sub`); returns `401` with **no** token and with a malformed token. Assert no identity is read from body/path (the endpoint exposes no such parameter). Prove the context still loads with Keycloak **down** (keep `SgartApplicationTest` green).
  - [x] **Identity ACL (fast unit tests, pure):** seed synthetic `MemberMapping`s; `resolve(known keycloakUserId, householdId)` → the mapped `MemberId`; `resolve(unknown, …)` → empty/`identity.notAMember` failure (never a new id); a person mapped in **two** households resolves to **two different** `MemberId`s (AD-5). No framework/persistence in these tests.
  - [x] **shared value objects (fast unit tests):** `MemberId`/`HouseholdId` validation, equality, `fromString`/`generate` — `MoneyTest` style.
  - [x] **No-persisted-PII guarantee (explicit privacy test):** assert the `/me` path and the ACL write nothing containing display name/email/`keycloakUserId` to any store; assert events/read models (none yet) and the mapping repository never receive a display name or email. Frame it as the regression guard AD-6 demands. Synthetic data only.
  - [x] **Flutter (widget/unit):** the auth gate renders "Anmelden"; a fake/stubbed auth service drives authenticated state → the placeholder shows the display name from a stubbed `/me`; **sign-out clears the (fake) secure store and returns to the gate**; the bearer interceptor attaches the token and a `401`/error body maps to the localized `AppError` copy. Stub the OIDC/secure-storage/HTTP boundaries — no real network or Keycloak in tests (isolate external systems, CLAUDE.md §6).
  - [x] `./gradlew test` (backend, incl. ArchUnit) green; `flutter analyze` clean + `flutter test` green. Run both **locally for real** before moving to review (`export PATH="$PATH:/home/timo/tools/flutter/bin"; cd app && flutter analyze && flutter test`; `cd backend && ./gradlew test`). [See memory `flutter-test-local`.]

## Dev Notes

### Scope & intent
**First backend-touching story** — Stories 1.1–1.3 were Flutter-client-only. 1.4 stands up the **identity substrate** the whole backend rests on: Keycloak realm/app-client, Spring Security **JWT resource-server** validation at `adapter.in`, the `sub`-only caller seam, and the **Identity ACL resolution contract** `(keycloakUserId, householdId) → MemberId`. It is proven end-to-end by a thin authenticated `/identity/me` slice and a Flutter sign-in/sign-out gate. The value is the **reusable contract**, not volume — every later household-scoped command/query calls the ACL resolver, and every protected endpoint sits behind this filter chain.

**Deliberate scope boundaries (decisions taken while writing this story — see Clarifications):**
- **No household, no membership, no minting yet.** Households are created in Story 1.6 (creator → first Admin, first `MemberId` minted); invite-acceptance mints in Story 4.2. There is nothing to mint or durably persist here. 1.4 ships the resolution **port + semantics + in-memory adapter** tested with synthetic mappings, exactly as Story 1.3 shipped the client error-resolver mechanism before any real error codes existed. The **PostgreSQL mapping table + the mint/write path land with their first writer (Story 1.6).**
- **`/identity/me` is a genuine slice, not a throwaway.** It is the app's live-identity source (Profil 1.11, header) and the honest way to prove AC1+AC2's "read live, never persist" without inventing a household. It is deliberately household-less and touches no domain.
- **No app shell / routing / Profil yet.** CAP-1 first-run routing is Story 1.6; the Profil screen is 1.11. 1.4's UI is only the sign-in gate + a minimal authenticated placeholder — enough to prove sign-in→token→authenticated call→sign-out.
- **REST error-mapping seam (deferred from 1.3) starts here, minimally.** Story 1.3 explicitly deferred backend `{code,message,details}` → client `AppError` mapping to "Stories 1.4/1.5". Wire it for the `/me` call only; the full `@RestControllerAdvice` on the write side arrives with the first commands (1.5/1.6).

### Source of truth: ARCHITECTURE-SPINE + SPEC (binding)
[Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md; specs/spec-sgart/SPEC.md; glossary.md]
- **AD-5 / AR4 (line 92 spine):** events/read models reference a person only by a per-membership **`MemberId`**; the **Identity ACL is the sole minter** (on join) and owns the sole mapping `{householdId, memberId → keycloakUserId}`, resolving `(keycloakUserId, householdId) → memberId` **per request**; a person in two households has two unrelated `MemberId`s. → drives AC2 and the ACL port.
- **AD-6 / AR5 (line 94 spine):** SGART **never persists display name or email** — resolved live from Keycloak/JWT claims for display only. → drives AC2's "read live, never persist" and the explicit privacy test.
- **Auth convention (spine Consistency Conventions, line 144):** "JWT from Keycloak validated at `adapter.in`; ACL resolves `MemberId` before the domain is touched (AD-5)." **Ids convention (line 138):** "`keycloakUserId` never in request body/path — taken from the JWT `sub`." → drives AC1's single-seam rule.
- **Transport (line 145):** REST under `/api/v1`. → `/api/v1/identity/me`.
- **AD-1 / AR1:** domain is pure; framework/security/transport/identity-provider types live only in adapters. The ArchUnit test already **bans `org.keycloak..` and `org.springframework..` from `..domain..`** — the security config, resource-server, and Keycloak coupling stay in `adapter.in`/`adapter.out`. Keep `KeycloakUserId` a plain string wrapper (no `org.keycloak` import) so `identity.domain` stays pure.
- **AD-2 / AR2 + the ArchUnit slice rule** (`de.sgart.(*).domain..` must not depend on each other): this is **why `MemberId`/`HouseholdId` go in `shared`, not `identity.domain`** — Collaboration will reference `MemberId` and must not depend on the identity domain.
- **Glossary (binding):** `MemberId`, `Member`, `HouseholdRole {Admin, Participant}`, **Identity ACL** ("translating a Keycloak JWT into a Household-scoped MemberId; sole owner of the mapping and the single point erasure destroys"). Use these exact names (AD-11, no abbreviations).
- **Erasure forward-link (AD-7 / AR6):** erasure later destroys "the Identity-ACL mapping rows" — design the mapping repository so a `keycloakUserId`'s rows are locatable and deletable. Don't build erasure here (Epic 6), but don't design a store that can't support it. `[[bmad-flow-state]]`

### The scaffold & contracts already in the repo (read before writing)
- `backend/build.gradle.kts` — Spring Boot 4.1.0, Java 25 toolchain, `spring-boot-starter-web`, ArchUnit. **Add** `spring-boot-starter-oauth2-resource-server` (+ transitive security) and `spring-security-test`. Keep the existing web starter and ArchUnit deps.
- `backend/src/main/resources/application.yaml` — currently only `spring.application.name`. Add the resource-server decoder config **profile-guarded** so the context loads offline (see below).
- `backend/src/test/java/de/sgart/SgartApplicationTest.java` — the `@SpringBootTest contextLoads()` smoke test. **This is the trap:** a naive `issuer-uri` makes Spring fetch Keycloak's OIDC discovery at startup, and this test fails when Keycloak is down (it is, in CI). Prevent it — see "Do not break contextLoads".
- `backend/src/test/java/de/sgart/architecture/HexagonalArchitectureTest.java` — permanent ArchUnit guardrails; `..domain..` may not touch `org.springframework..`/`org.keycloak..`/`..adapter..`; context domains may not depend on each other; layers must respect `adapter.in → application → domain`, `adapter.out ⟶ implements domain ports`. **Your placement must keep all four rules green.**
- `backend/src/main/java/de/sgart/shared/Identifier.java` — the id record pattern to mirror for `MemberId`/`HouseholdId` (UUID-backed, `Objects.requireNonNull`, `generate()`/`fromString()`/`toString()`).
- `backend/src/main/java/de/sgart/shared/ErrorDescriptor.java` — `record ErrorDescriptor(String code, String message, Map<String,Object> details)` + `ErrorDescriptor.of(code, message)`. Use it for the `identity.notAMember`/auth failures; the client already mirrors this shape (`AppError`) since Story 1.3.
- `backend/.../identity/**/package-info.java` — the identity context's hexagonal packages already exist and are documented (`adapter.in` = "REST controllers… transport types live only here"; `application` = "orchestrates the domain through ports"; `domain` = "pure"; `adapter.out` = "implement domain ports"). Populate them; don't restructure.
- `backend/src/test/java/de/sgart/shared/MoneyTest.java` — backend test style: JUnit 5, AssertJ (`assertThat`/`assertThatThrownBy`), behavioral method names (`euroFactory_producesAmountInEuroMinorUnits`). Match it.
- `docker-compose.yml` — Keycloak `26.7.0` `start-dev`, health via `/dev/tcp` on 9000, admin from `.env`. Add the realm mount + `--import-realm`. Keep the health check.
- `.env.example` — dev-only admin creds. Add any new dev vars (realm name is static; document the sign-in users).

### Previous-story intelligence (Stories 1.1–1.3 — done)
[Source: implementation-artifacts/1-1…, 1-2…, 1-3-localization-layer-de-de-formatting.md; deferred-work.md]
- **Story 1.3 deferred exactly this story's client piece:** "the REST layer and backend error mapping arrive with Stories 1.4/1.5" and "REST error mapping → 1.4/1.5". The client `AppError` + `localizedMessageForErrorCode` already exist and are wire-ready (JSON-compatible `code`/`message`/`details`). Wire the `/me` client call through them.
- **Deferred-work backlog (`deferred-work.md`) — address the ones your code touches:** (a) **no top-level error boundary** in `main.dart` (bare `runApp`, no `FlutterError.onError`/`runZonedGuarded`) — the auth boot is a natural place to add one; (b) **`HomeCubit` has no `isClosed` guard** and becomes the copy-pattern for async cubits — your `AuthBloc`/`SignInCubit` does async work (network) after which `emit` can fire post-dispose, so **guard emits** and set the right example; (c) **`bloc_test` is not yet a dev dependency** despite BLoC-per-screen — add it and use it for the auth cubit. Leaving these for the auth cubit to repeat would spread the smell (Boy Scout Rule, CLAUDE.md §1).
- **Client conventions (keep):** feature-first `lib/features/<feature>/presentation/…`, `lib/shared/` for cross-feature; `flutter analyze` stays "No issues found"; **no hard-coded user-facing strings** — every string via `AppLocalizations` (English ARB keys, German values); tests assert behavior + exact values via `test/support/widget_test_harness.dart` (`wrapForTesting`, theme + localization delegates). Reuse `SgartButton` and theme tokens.
- **"SGART" stays a hard-coded proper noun** (Story 1.3 decision) — the auth screen's brand/title may use it directly; all other copy is localized.
- **Local test reality (memory `flutter-test-local`):** Flutter SDK at `/home/timo/tools/flutter/bin`, not on PATH. **Story 1.2 was once marked done on a review that never ran tests (6 were red).** Run both suites for real before review.
- **Git patterns:** solo, **direct-to-`main`** (no feature branches); commits are focused (impl, then review-fixes). Baseline for this story = `d9c6ba2`.

### Do not break `contextLoads` (critical — the #1 way this story breaks CI)
Adding `spring-boot-starter-oauth2-resource-server` with `spring.security.oauth2.resourceserver.jwt.issuer-uri` makes Spring **eagerly** call Keycloak's `/.well-known/openid-configuration` at context startup. In CI (`./gradlew test`) Keycloak is **not running**, so the existing `@SpringBootTest` `contextLoads()` would fail. Prevent it:
- Prefer **`jwk-set-uri`** over `issuer-uri`, and/or bind the resource-server config only under the **runtime profile** (not the default/test profile), so the test context has a `SecurityFilterChain` but no network dependency.
- In security tests, mint identity with **`spring-security-test`** (`SecurityMockMvcRequestPostProcessors.jwt()`), which injects a pre-authenticated `Jwt` and needs **no** decoder/network.
- Add a test asserting the context loads with Keycloak unreachable, so a future refactor can't silently reintroduce the eager fetch.

### Latest tech notes (Keycloak 26.7 / Spring Boot 4.1 / Flutter 3.44)
- **Spring Boot 4.1 resource server:** `spring-boot-starter-oauth2-resource-server` + a `SecurityFilterChain` bean (component-based security config; Boot 4.x has no `WebSecurityConfigurerAdapter`). Keycloak realm roles arrive under `realm_access.roles` — role mapping is **not needed in 1.4** (no authorization-by-role yet; `HouseholdRole` governance is Epic 4). Keep the converter minimal or default.
- **Keycloak 26.7 realm import:** mount the exported realm JSON at `/opt/keycloak/data/import/` and start with `--import-realm` (works with `start-dev`). Public client + `Proof Key for Code Exchange` set to `S256`. Issuer for a realm named `sgart` on the dev host = `http://localhost:8080/realms/sgart`.
- **Flutter OIDC (recommended set — flagged in Clarifications):** `flutter_appauth` (wraps the maintained AppAuth native SDKs; Authorization Code + PKCE for native, custom-scheme redirect) + `flutter_secure_storage` (Keychain/Keystore token storage) + an HTTP client. For HTTP, **`dio`** is recommended over `http` because bearer injection and `{code,message,details}` → `AppError` mapping are exactly interceptor concerns that recur in every later story; `http` is the lighter alternative if you prefer to defer interceptors. Add **`bloc_test`** (dev) for the auth cubit.
- **Native redirect config:** Android needs an intent-filter for the custom scheme (and `appAuthRedirectScheme` manifest placeholder for `flutter_appauth`); iOS needs `CFBundleURLTypes`. These are the only native-project edits; document them.
- **Token handling:** store access + refresh tokens in secure storage only; never log tokens; sign-out deletes them (and calls end-session/revocation where supported). SGART storing the **token** (not credentials) satisfies "SGART stores no credentials" — the password only ever reaches Keycloak's own login page.

### Project Structure Notes
```text
keycloak/realm-sgart.json                                  # dev realm export, imported by compose (new)
backend/src/main/java/de/sgart/shared/
  MemberId.java                                            # shared, cross-context pseudonym id (new)
  HouseholdId.java                                         # shared, cross-context household id (new)
backend/src/main/java/de/sgart/identity/
  domain/KeycloakUserId.java                               # ACL-owned, pure string wrapper (new)
  domain/MemberMapping.java                                # {householdId, memberId, keycloakUserId} (new)
  domain/MemberMappingRepository.java                      # port (interface, no infra) (new)
  application/ResolveMemberIdentity.java                   # (keycloakUserId, householdId) -> MemberId (new)
  adapter/in/security/SecurityConfig.java                  # SecurityFilterChain, JWT resource server (new)
  adapter/in/security/AuthenticatedCaller.java             # sub -> keycloakUserId + live claims seam (new)
  adapter/in/IdentityController.java                       # GET /api/v1/identity/me (new)
  adapter/out/InMemoryMemberMappingRepository.java         # synthetic-seedable adapter (new; PG in 1.6)
backend/src/test/java/de/sgart/identity/…                  # ACL unit + security integration tests (new)
backend/src/test/java/de/sgart/shared/…                    # MemberId/HouseholdId tests (new)
app/lib/features/auth/
  presentation/sign_in_page.dart, auth_gate.dart, auth_bloc.dart (or sign_in_cubit.dart)  (new)
  data/…                                                   # OIDC + secure-storage + authenticated client (new)
app/lib/shared/http/                                       # bearer interceptor + error-body -> AppError (new)
app/test/features/auth/…                                   # auth cubit + gate + sign-out tests (new)
```
- Backend: security/auth is the **identity context's** inbound concern → `identity/adapter/in/security`. `MemberId`/`HouseholdId` are cross-context vocabulary → `shared`. One class per concern (SRP); no abbreviations (`keycloakUserId`, not `kcUid`).
- Client: an `auth` **feature** (BLoC per screen), an authenticated HTTP client under `lib/shared/http/` (cross-feature). Fold the 1.1/1.3 `HomePage` demo behind the auth gate — one entry path, not two.

### Testing standards
[Source: CLAUDE.md §6; ARCHITECTURE-SPINE Testing convention]
- **Domain/ACL first:** the resolver and value objects are pure fast unit tests — no Spring, no DB, no Keycloak. Security is an integration test using `spring-security-test`'s mock `jwt()` — still no live Keycloak.
- **Behavior, not structure:** assert `200`/`401`, the three live claim fields, `keycloakUserId == sub`, two-households-two-MemberIds, unknown→not-a-member. Name tests as full behavioral sentences (`resolvesKnownMappingToItsMemberId`, `rejectsRequestWithoutAToken`, `resolvesTheSamePersonToDifferentMemberIdsInDifferentHouseholds`).
- **DSGVO explicit:** the "no persisted PII" test is a first-class privacy guarantee (AD-6), not an afterthought. **Synthetic data only** — fake users (`anna@example.test`), fake UUIDs; never real names/emails in fixtures, realm export, or seeds (CLAUDE.md §5–§6).
- **Keep green:** all Story 1.1–1.3 suites (backend `MoneyTest`/`QuantityTest`/ArchUnit/`contextLoads`; client 83 tests) stay passing; update the client entry/home tests deliberately where the auth gate replaces the placeholder, and log it in the Change Log.
- **Isolate boundaries (client):** stub OIDC, secure storage, and HTTP in widget/unit tests — no real network. A red build blocks merge (NFR6).

### References
- [Source: epics.md#Story 1.4: Sign in with Keycloak & resolve membership identity] (lines 300–318) — user story + ACs
- [Source: epics.md] NFR4 (line 76), AR4 (line 98), AR5 (line 99), AR10 (line 104), AR-Setup (line 94), AR-Stack (line 105) — requirement IDs the ACs realize
- [Source: specs/spec-sgart/SPEC.md] Constraints "Identity delegated to Keycloak" (line 84), "DSGVO by design" (line 82); CAP-1/CAP-2 context (lines 23–29)
- [Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md] AD-5 (line 92), AD-6 (line 94), AD-1 (line 68), AD-2 (line 74), AD-7 (line 104); Consistency Conventions — Auth (line 144), Ids (line 138), Transport (line 145)
- [Source: specs/spec-sgart/glossary.md] — MemberId, Member, Identity ACL, HouseholdRole (binding names)
- [Source: backend/src/main/java/de/sgart/shared/{Identifier,ErrorDescriptor}.java; …/identity/**/package-info.java; …/architecture/HexagonalArchitectureTest.java] — the id/error patterns, package layout, and the ArchUnit guardrails to keep green
- [Source: docker-compose.yml; .env.example; .github/workflows/ci.yml] — Keycloak service, dev creds, CI (backend `./gradlew test` + client `flutter analyze`/`flutter test`)
- [Source: implementation-artifacts/1-3-localization-layer-de-de-formatting.md; deferred-work.md] — AppError/error-mapping deferral to 1.4, cubit `isClosed`/`bloc_test`/error-boundary backlog, test-harness + local-test reality
- [Source: CLAUDE.md] — Clean Code, no-abbreviations naming, DDD/CQRS layering, TDD + DSGVO testing rules
- [Source: memory `flutter-test-local`, `language-policy`, `bmad-flow-state`] — run tests locally with PATH set; English keys/German values; resume point + locked decisions

## Clarifications (LOCKED by Timo 2026-08-23 — all four confirmed as the recommended default)

1. **Durable ACL mapping persistence — defer to first writer (Story 1.6).** ✅ **LOCKED.** 1.4 ships the resolution **port + semantics + in-memory adapter** proven with synthetic mappings; the **PostgreSQL mapping table + the mint (write) path land with household creation (1.6)**, the first component that actually creates a `MemberId`. Do **not** build the PostgreSQL/Flyway mapping table in this story (no reader/writer exists yet — YAGNI).

2. **Flutter OIDC/HTTP library set.** ✅ **LOCKED:** `flutter_appauth` + `flutter_secure_storage` + **`dio`** (interceptor for bearer + `{code,message,details}`→`AppError` mapping) + `bloc_test` (dev). Use this set; add the native redirect config for `flutter_appauth` (`appAuthRedirectScheme` placeholder / iOS `CFBundleURLTypes`).

3. **Reproducible Keycloak realm via committed export + `--import-realm`.** ✅ **LOCKED.** Commit `keycloak/realm-sgart.json` (dev-only, **synthetic users only**, no secrets) and import it in Compose so every machine/CI is identical.

4. **`GET /api/v1/identity/me` as the vertical-slice proof.** ✅ **LOCKED.** Household-less, live-claims-only probe returning `{ keycloakUserId, displayName, email }` — the end-to-end auth proof **and** the canonical post-login live-identity source (reused by Profil 1.11 + header). Keep the shape stable.

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- Verified realm import end-to-end: `docker compose up -d keycloak` → healthy; realm/client/PKCE/protocol-mapper confirmed via the Keycloak admin REST API; issuer + JWKS endpoints checked with `curl`.
- Verified the `contextLoads`-with-Keycloak-down trap is actually avoided: ran `./gradlew test` with the `keycloak` container stopped — green (in addition to the dedicated `ContextLoadsWithoutKeycloakTest` using an unroutable `jwk-set-uri`).
- Ran a real end-to-end check outside the test suite: started the backend on `:8081` (`./gradlew bootRun --args='--server.port=8081'`) alongside live Keycloak, fetched a genuine password-grant token for the seeded `anna@example.test` user (direct-access-grants temporarily toggled live via the admin API only, never persisted to `realm-sgart.json`), and confirmed `GET /api/v1/identity/me` returns `200` with the live claims for a valid token and `401` for a malformed/absent one.
- Discovered mid-story that Spring Boot 4.1 split MockMvc test support into a new module/package (`org.springframework.boot:spring-boot-webmvc-test`, `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`) — resolved via `spring-boot-dependencies`'s POM, not documented in the story.
- Discovered the backend's default port (`8080`) collides with Keycloak's — added `server.port: 8081` (override via `SGART_SERVER_PORT`) and documented it in the README; not called out in the original story notes.

### Completion Notes List

- **Backend (Tasks 1–5, 7):** Keycloak dev realm (`keycloak/realm-sgart.json`) reproducibly imported via `--import-realm`, with a public PKCE-only client and a `sgart-backend` audience protocol mapper (added beyond the story text — needed to make AC1's "audience checked" requirement real rather than aspirational). Spring Security JWT resource server validates signature (JWKS), issuer, and audience via a manually built `NimbusJwtDecoder` (`SecurityConfig`) that stays lazy — proven safe with Keycloak down both by a dedicated regression test and by manually running the full suite with the container stopped. `AuthenticatedCaller` is the sole `adapter.in` seam extracting `sub`/`name`/`preferred_username`/`email`; it never crosses into `domain` or `adapter.out` (enforced by a dedicated ArchUnit rule in `NoPersistedPersonalDataTest`, alongside a reflection check that `MemberMapping` carries no display-name/email field). `MemberId`/`HouseholdId` live in `shared`; `KeycloakUserId`/`MemberMapping`/`MemberMappingRepository` in `identity.domain`; `ResolveMemberIdentity`/`NotAMemberException` in `identity.application`; `InMemoryMemberMappingRepository` in `identity.adapter.out` (synthetic-seeded only — durable persistence deferred to Story 1.6 per the locked clarification). `GET /api/v1/identity/me` is household-less, persists nothing, and was validated against a real Keycloak-issued token in addition to its MockMvc suite. All 4 ArchUnit rules stay green; 30/30 backend tests pass.
- **Flutter (Task 6, 7):** Added `flutter_appauth` (Authorization Code + PKCE, native redirect scheme `de.sgart.app://oauth/callback` wired into the Android manifest placeholder and iOS `CFBundleURLTypes`), `flutter_secure_storage`, `dio`, and `bloc_test`. New `auth` feature: `OidcClient`/`SecureTokenStorage`/`IdentityApi` interfaces (real impls `AppAuthOidcClient`/`FlutterSecureTokenStorage`/`HttpIdentityApi`) keep `AuthCubit` testable without touching real OIDC, device storage, or the network. `AuthCubit` guards every `emit` with an `isClosed` check (Boy Scout fix — the deferred-work backlog flagged `HomeCubit`'s missing guard as the pattern later async cubits would copy) and resumes a stored session via `bootstrap()`. `AuthenticatedHttpClient` (`shared/http`) injects the bearer token and maps `{code,message,details}` error bodies to `AppError`, wiring the REST-error-mapping seam Story 1.3 deferred to 1.4. Replaced the Story 1.1/1.3 `HomePage` placeholder with `AuthGate` as the app's single entry path (`main.dart`), removing `home_page.dart`/`home_cubit.dart` and their tests, and updated `test/app_test.dart`'s two localization assertions to the new sign-in-gate copy. Also added a top-level `runZonedGuarded` + `FlutterError.onError` boundary in `main.dart` (another deferred-work item flagged as natural for this story's auth boot). 95/95 Flutter tests pass; `flutter analyze` is clean.
- **Scope boundaries honored:** no household/membership minting, no PostgreSQL mapping table, no app shell/routing/Profil screen — all deferred exactly as locked in Clarifications 1–4.
- Both suites were run for real locally (not assumed green) per the memory `flutter-test-local` warning about Story 1.2's review having skipped this.

### File List

**Backend — new**
- `backend/src/main/java/de/sgart/shared/MemberId.java`
- `backend/src/main/java/de/sgart/shared/HouseholdId.java`
- `backend/src/main/java/de/sgart/identity/domain/KeycloakUserId.java`
- `backend/src/main/java/de/sgart/identity/domain/MemberMapping.java`
- `backend/src/main/java/de/sgart/identity/domain/MemberMappingRepository.java`
- `backend/src/main/java/de/sgart/identity/application/ResolveMemberIdentity.java`
- `backend/src/main/java/de/sgart/identity/application/NotAMemberException.java`
- `backend/src/main/java/de/sgart/identity/adapter/out/InMemoryMemberMappingRepository.java`
- `backend/src/main/java/de/sgart/identity/adapter/in/IdentityController.java`
- `backend/src/main/java/de/sgart/identity/adapter/in/security/package-info.java`
- `backend/src/main/java/de/sgart/identity/adapter/in/security/SecurityConfig.java`
- `backend/src/main/java/de/sgart/identity/adapter/in/security/AuthenticatedCaller.java`
- `backend/src/main/java/de/sgart/identity/adapter/in/security/AudienceValidator.java`
- `backend/src/test/java/de/sgart/shared/MemberIdTest.java`
- `backend/src/test/java/de/sgart/shared/HouseholdIdTest.java`
- `backend/src/test/java/de/sgart/identity/domain/KeycloakUserIdTest.java`
- `backend/src/test/java/de/sgart/identity/application/ResolveMemberIdentityTest.java`
- `backend/src/test/java/de/sgart/identity/adapter/in/IdentityControllerTest.java`
- `backend/src/test/java/de/sgart/identity/adapter/in/security/ContextLoadsWithoutKeycloakTest.java`
- `backend/src/test/java/de/sgart/identity/NoPersistedPersonalDataTest.java`

**Backend — modified**
- `backend/build.gradle.kts` (oauth2-resource-server, spring-security-test, webmvc-test)
- `backend/src/main/resources/application.yaml` (jwk-set-uri, issuer, audience, server.port)

**Infra — new/modified**
- `keycloak/realm-sgart.json` (new)
- `docker-compose.yml` (Keycloak realm mount + `--import-realm`)
- `.env.example` (realm/issuer/JWKS/dev-user documentation)
- `README.md` (Keycloak dev-realm section, backend port note)

**Flutter — new**
- `app/lib/shared/http/app_exception.dart`
- `app/lib/shared/http/authenticated_http_client.dart`
- `app/lib/shared/http/backend_config.dart`
- `app/lib/features/auth/data/oidc_tokens.dart`
- `app/lib/features/auth/data/oidc_client.dart`
- `app/lib/features/auth/data/app_auth_oidc_client.dart`
- `app/lib/features/auth/data/keycloak_config.dart`
- `app/lib/features/auth/data/secure_token_storage.dart`
- `app/lib/features/auth/data/flutter_secure_token_storage.dart`
- `app/lib/features/auth/data/caller_identity.dart`
- `app/lib/features/auth/data/identity_api.dart`
- `app/lib/features/auth/presentation/auth_state.dart`
- `app/lib/features/auth/presentation/auth_cubit.dart`
- `app/lib/features/auth/presentation/auth_gate.dart`
- `app/lib/features/auth/presentation/sign_in_page.dart`
- `app/lib/features/auth/presentation/authenticated_placeholder_page.dart`
- `app/test/shared/http/authenticated_http_client_test.dart`
- `app/test/support/fake_auth_dependencies.dart`
- `app/test/features/auth/presentation/auth_cubit_test.dart`
- `app/test/features/auth/presentation/sign_in_page_test.dart`
- `app/test/features/auth/presentation/authenticated_placeholder_page_test.dart`
- `app/test/features/auth/presentation/auth_gate_body_test.dart`

**Flutter — modified**
- `app/pubspec.yaml` (flutter_appauth, flutter_secure_storage, dio, bloc_test)
- `app/lib/main.dart` (AuthGate as entry point, error boundary)
- `app/lib/l10n/app_de.arb` (auth* keys replacing home* keys)
- `app/android/app/build.gradle.kts` (appAuthRedirectScheme manifest placeholder)
- `app/ios/Runner/Info.plist` (CFBundleURLTypes)
- `app/test/app_test.dart` (localization assertions updated to the sign-in gate)

**Flutter — deleted**
- `app/lib/features/home/presentation/home_page.dart`
- `app/lib/features/home/presentation/home_cubit.dart`
- `app/test/features/home/home_page_test.dart`
- `app/test/features/home/home_cubit_test.dart`

## Change Log

| Date | Change |
| --- | --- |
| 2026-08-23 | Story created via bmad-create-story. First backend-touching story: Keycloak realm/app-client, Spring Security JWT resource-server validation at `adapter.in` with `sub`-only caller seam, `shared` `MemberId`/`HouseholdId`, Identity ACL resolution port `(keycloakUserId, householdId) → MemberId` (in-memory adapter; synthetic mappings), live-claims `/api/v1/identity/me` slice, and Flutter OIDC (Auth Code + PKCE) sign-in/secure-token-storage/authenticated-client/sign-out. Scope boundaries recorded (mapping persistence + minting deferred to 1.6; no app shell/routing/Profil yet; REST error-mapping seam from 1.3 wired minimally). Status → ready-for-dev. |
| 2026-08-23 | All 4 clarifications LOCKED by Timo (recommended defaults): (1) defer PostgreSQL mapping table + minting to Story 1.6; (2) client libs `flutter_appauth` + `flutter_secure_storage` + `dio` + `bloc_test`; (3) committed dev realm export imported via `--import-realm`; (4) `/api/v1/identity/me` `{keycloakUserId, displayName, email}` as canonical live-identity source. Ready for `bmad-dev-story` (Sonnet 5). |
| 2026-08-23 | Implemented (Sonnet 5): all 7 tasks complete. Backend — Keycloak dev realm (public PKCE client + `sgart-backend` audience mapper), Spring Security JWT resource server (lazy `jwk-set-uri` decoder with explicit issuer+audience validation, proven safe with Keycloak down), `shared` `MemberId`/`HouseholdId`, Identity ACL (`KeycloakUserId`, `MemberMapping`, `MemberMappingRepository` port, `ResolveMemberIdentity`, in-memory adapter), `GET /api/v1/identity/me`, and a dedicated no-persisted-PII regression test (ArchUnit + reflection). Flutter — `auth` feature (OIDC Authorization Code + PKCE via `flutter_appauth`, `flutter_secure_storage`, `AuthCubit` with an `isClosed` guard and session bootstrap, `AuthenticatedHttpClient` wiring the Story 1.3 error-mapping seam), `AuthGate` replacing the `HomePage` placeholder as the single entry point, plus a top-level error boundary in `main.dart` (both addressing items from the Story 1.2 `deferred-work.md` backlog). 30/30 backend tests and 95/95 Flutter tests pass; `flutter analyze` clean; both suites run locally for real, plus a manual end-to-end check against live Keycloak with a genuine token. Status → review. |
