---
baseline_commit: d1a4bc8
status: done
---

# Story 7.4: Consent capture

Status: done

<!-- Note: Validation is optional. Run validate-create-story for a quality check before dev-story. -->

## Story

As the operator,
I want explicit, revocable consent captured before household data (personal data under CLAUDE.md §5)
is processed,
so that frictionless provisioning has a documented GDPR lawful basis.

This is **Epic 7's fourth build story**. It adds the **consent moment** at first household
create/join, on top of the silent account shell (7.1) and the recovery UI (7.2/7.3). The silent shell
keeps its **legitimate-interest** basis (providing the service the person just opened); this story adds
the **consent** basis for the first processing of household personal data.

It is a **full-stack** story but a **small** one: a pseudonymous `AccountConsent` record in the
`identity` context (new `V20` migration + repository + a `RecordConsent` command and a
`GetConsentStatus` query), server-side **fail-fast** enforcement at the two `collaboration` handlers
via a `ConsentGate` port, and one native consent screen gating `CreateOrAwaitChoicePage`. It changes
**no** domain event, **no** aggregate, and adds **no** `SecurityConfig` permit rule.

It does **not** build the erasure/export execution or the revoke UI (Epic 6 owns both) and does **not**
touch invites' transport (Story 7.5). Keep those decoupled.

**Feeds directly off the architect design note:** `epic-7-story-7.4-consent-capture-design.md`
(Winston, 2026-09-17) — the four majors were **resolved with Timo** (A: backend `AccountConsent` row;
B: client gate + server fail-fast; C: revocation routes to Epic 6 erasure; D: short versioned in-app
notice). This story pins those into buildable tasks; §-refs below point at the note's detail.

## Context — what already exists vs. what 7.4 adds

**Already built (reuse verbatim, do NOT recreate):**

*Backend (`de.sgart.identity`):*
- `ProvisionedAccount` (record: `keycloakUserId` + `provisionedAt`) + `ProvisionedAccountRepository`
  (+ `JdbcProvisionedAccountRepository` and the `InMemory…` double) — **the exact shape the new
  `AccountConsent` record + repository mirror** (design §2). Do not invent a different persistence style.
- `AuthenticatedCaller` — resolves the caller's `KeycloakUserId` from the JWT; the consent endpoints and
  the gate both key off it.
- `SecurityConfig` — the `/api/v1/**`.authenticated() matcher already covers the new consent endpoints;
  7.4 adds **no** new permit rule (design §3).
- `NoPersistedPersonalDataTest` — the pseudonymous-only migration/table scan; extend it for
  `account_consent` (design §5).

*Backend (`de.sgart.collaboration`):*
- `CreateHouseholdHandler`, `AcceptInviteHandler` — the first two commands that process household
  personal data; the **gate call goes at the top of each** (design §4). No other change to them.

*App (Flutter):*
- `FirstRunRouter` → `FirstRunRouterBody` → `CreateOrAwaitChoicePage` (the `needsChoice` branch) — the
  0-household gateway where the consent screen lands (design §3). Reuse the provider wiring already there.
- `create_or_await_choice_page.dart` — offers "Haushalt erstellen" and "Ich habe eine Einladung"; both
  paths pass through the gate.
- `error_message_resolver.dart` + `app_de.arb` — the localized-error convention the `consent.required`
  arm follows.

**What 7.4 adds:** the `AccountConsent` record (`V20`) + repository; `RecordConsent` command +
`GetConsentStatus` query + `ConsentController` (`POST`/`GET /api/v1/consent`); a `ConsentGate` port in
`collaboration` + its identity-delegating adapter + `ConsentRequiredException` (→ `409`); the gate call
in the two handlers; a `ConsentApi` + `ConsentCubit` + `ConsentGatePage` on the app, wired into
`CreateOrAwaitChoicePage`.

## Locked Decisions (Timo, 2026-09-17 — from the design note)

- **D-A — Storage:** a backend `AccountConsent` row in `identity` (`keycloakUserId` + `noticeVersion`
  + `acceptedAt`), pseudonymous, mirroring `ProvisionedAccount`. No PII (AD-6).
- **D-B — Enforcement:** client gate **and** server fail-fast — `CreateHouseholdHandler` /
  `AcceptInviteHandler` reject with `409 consent.required` when no consent row exists for the caller.
- **D-C — Revocation:** routes to the **Epic 6 erasure** path (no distinct withdrawn-but-retained
  state); 7.4 makes the record erasable/revocation-ready only.
- **D-D — Notice:** a short, **versioned** in-app privacy notice + terms; the record stores the accepted
  `noticeVersion`; a re-consent fires when the current version is newer than the accepted one.
- **D-E — Notice version source (§8 F2):** the current `noticeVersion` comes from a backend config key
  returned by `GET /api/v1/consent`, so a bump is a one-line deploy, not an app release.

## Acceptance Criteria

Derived from `epics.md` §Epic 7 Story 7.4 with the design note's resolved decisions applied.

1. **AC1 — Consent is captured at the first-household moment.** Given a person at the 0-household
   gateway (`CreateOrAwaitChoicePage`), when they have not yet accepted the current notice version, then
   the consent screen is shown and **blocks** both "create household" and "accept invite" until they
   explicitly accept. On accept, `POST /api/v1/consent { noticeVersion }` (authenticated as the caller)
   records the consent and returns `204`. Consent is attached to **this moment** — the first processing
   of household personal data.

2. **AC2 — The consent record is pseudonymous and versioned (D-A, AD-6).** Given an accepted consent,
   then exactly one `account_consent` row exists for the caller's `KeycloakUserId`, holding
   `notice_version` + `accepted_at` and **no PII** (no name/email/IP column). A re-accept on a newer
   notice version **overwrites** the row (one current consent per account). `GET /api/v1/consent`
   returns `{ accepted, acceptedVersion, currentVersion }` (side-effect-free).

3. **AC3 — Server fail-fast enforcement (D-B).** Given no consent row for the caller, when
   `CreateHouseholdHandler` or `AcceptInviteHandler` runs, then it is rejected with a typed
   `ConsentRequiredException` mapped to **HTTP 409 `consent.required`** (so a stale client recovers by
   showing the screen), and **no household/membership state is created**. Given a valid consent row, the
   same commands proceed unchanged.

4. **AC4 — Relaunch/reinstall does not re-prompt; a notice bump does (D-D, D-E).** Given a caller who
   already accepted the current version, when they return to the gateway, then the consent screen is
   **not** shown (the row persists server-side, keyed by `KeycloakUserId`). Given the deployment's
   `noticeVersion` is bumped above the accepted version, then the screen **is** shown again and a fresh
   `RecordConsent` overwrites the row.

5. **AC5 — GDPR: erasable, exportable, revocation-ready (D-C, CLAUDE.md §5, AD-7).** Given a consent
   row, then `AccountConsentRepository.deleteFor(keycloakUserId)` removes it (the hook Epic 6's erasure
   use case calls alongside the Keycloak-account delete — **7.4 provides it, Epic 6 wires it**), and
   `{ noticeVersion, acceptedAt }` is exposed for Epic 6's export bundle. The design records that
   **revocation = erasure** (no separate withdrawn state). A checklist pointer is added so Epic 6 does
   not miss the row. **No** revoke UI is built here.

6. **AC6 — Security surface & green build.** The consent endpoints add **no** new unauthenticated
   surface and **no** new `SecurityConfig` permit rule (authenticated as the caller). When the story
   completes, **both** suites are named and green: backend `./gradlew test` (incl. ArchUnit +
   Testcontainers — the cross-context `ConsentGate` must not break the AD-1/AD-2 layer rules) **and** app
   `flutter test` + `flutter analyze`. Any dependency touched stays on its current supported major
   (CLAUDE.md §7).

## Tasks / Subtasks

### Backend — the consent record (`V20` + port + adapters) — AC2, AC5

- [x] `V20__account_consent.sql`: create `account_consent` (`keycloak_user_id text primary key`,
      `notice_version text`, `accepted_at timestamptz`). **No email/name/IP column** (design §5). One
      current consent per account (upsert/replace on re-accept). Latest migration is `V19`, so this is
      `V20`.
- [x] `AccountConsent` (domain record + fail-fast non-null invariants) + `AccountConsentRepository`
      (port: `record(consent)` upsert, `findFor(id)`, `deleteFor(id)`) — mirror `ProvisionedAccount` /
      `ProvisionedAccountRepository` exactly.
- [x] `JdbcAccountConsentRepository` + `InMemoryAccountConsentRepository` (test double) — mirror the
      `ProvisionedAccountRepository` pair.
- [x] `NoPersistedPersonalDataTest`: add `account_consent` to the pseudonymous-only whitelist; assert it
      holds **no** email/name column (design §5).

### Backend — command, query, endpoints — AC1, AC2, AC4

- [x] `RecordConsent(keycloakUserId, noticeVersion)` command in `identity.application` — upserts the row
      (CQRS: state change, returns nothing beyond success).
- [x] `GetConsentStatus(keycloakUserId)` query + a `ConsentStatus` read model
      (`{ accepted, acceptedVersion, currentVersion }`) — side-effect-free; reads the row + the config
      `noticeVersion`.
- [x] `sgart.identity.consent.notice-version` config key (D-E) — the single source of the current
      version; returned by the query, never hard-coded on the app.
- [x] `ConsentController`: `POST /api/v1/consent { noticeVersion }` → `RecordConsent` → `204`;
      `GET /api/v1/consent` → `ConsentStatus`. Both authenticated as the caller (**no new permit rule**).
      Request record for the body.
- [x] Application + controller tests (fast, no infra): record stores version + timestamp; re-accept with
      a newer version overwrites; `GET` with no row returns not-accepted; `GET` returns the current
      config version.

### Backend — server-side gate in `collaboration` — AC3, AC6

- [x] `ConsentGate.hasRecordedConsent(KeycloakUserId): boolean` — an **outbound port** in
      `collaboration.application` (collaboration owns the abstraction; points inward, AD-1/AD-2).
- [x] `IdentityConsentGate` (`collaboration.adapter.out`) — implements the port by delegating to
      `identity`'s `GetConsentStatus` query. The one sanctioned synchronous cross-context read
      (consistent with the identity-ACL `MemberMapping` crossing).
- [x] `ConsentRequiredException` (collaboration application exception) + advice mapping →
      **`409 consent.required`** `ErrorDescriptor`.
- [x] Add the gate call at the top of `CreateHouseholdHandler` and `AcceptInviteHandler` — fail-fast
      before any state change. No other handler change; no new event.
- [x] Tests: `createHousehold` / `acceptInvite` without consent → `409 consent.required`, no state
      created; with consent → succeeds. An ArchUnit check confirms the gate keeps `collaboration`
      depending on its own port, not on `identity` internals (AD-1/AD-2).

### App — consent API + cubit + gate screen — AC1, AC4

- [x] `ConsentApi` (over `AuthenticatedHttpClient`): `getStatus()`, `accept(noticeVersion)`. Mirror
      `account_email_api.dart` / `identity_api.dart` shape.
- [x] `ConsentCubit` + state: loads status; `accept()` → records + moves to accepted. Small, SRP —
      separate from `AuthCubit`/`HouseholdsCubit`.
- [x] `ConsentGatePage`: the short privacy notice + terms text (localized) with a link to the hosted
      notice (design §8 F3) and an explicit **accept** action; a11y labels on every interactive widget.
- [x] Wire into `CreateOrAwaitChoicePage`: on entry read status; if not-accepted **or**
      `acceptedVersion != currentVersion`, show `ConsentGatePage` and block "create" / "I have an
      invite" until accepted; on accept proceed to the existing create/await paths.
- [x] `app_de.arb`: consent screen title/body/accept-label/notice-link + the `consent.required` error
      copy; regenerate l10n. `error_message_resolver` arm for `consent.required`. No hard-coded strings.

### App — widget/cubit tests — AC1, AC3, AC4

- [x] Cubit tests (fakes, no network): status load; accept records + flips state.
- [x] Widget tests: the gate **blocks both** create and join until accepted; **not** shown when already
      accepted the current version; **shown again** when the current version is newer; a `409
      consent.required` from a stale-client create surfaces the gate (not a generic error).

### Full build — AC6

- [x] Run and name green: **both** backend `./gradlew test` (incl. ArchUnit + Testcontainers) **and**
      app `flutter test` + `flutter analyze`. A partial run is not a green build (CLAUDE.md §6;
      `backend-test-hygiene`).

### Definition of Done (standing, per retros)

- [x] No dead strings/fields/stale comments; the new port has both a real and an in-memory impl.
- [x] Fail-fast on the gate (server) and forgiving/clear consent UX (client); a11y labels on all new
      interactive widgets.
- [x] `commandId`/`basedOnVersion` N/A (`RecordConsent` is an identity row mutation, not an
      event-sourced CQRS-bus command).
- [x] Privacy DoD (Epic-4/GDPR action): tests prove the no-PII guarantee (`NoPersistedPersonalDataTest`
      + a column assertion) and the `deleteFor` erasure hook contract — not left to review.
- [x] Cross-context DoD: an ArchUnit test proves `collaboration` reaches consent only through its own
      port (AD-1/AD-2), never importing `..identity..domain..`.
- [x] Both touched suites named at completion; a red build blocks (CLAUDE.md §6).

## Dev Notes

### Ground truth — read these before coding
- **`epic-7-story-7.4-consent-capture-design.md`** (Winston, 2026-09-17) — the whole buildable design;
  §1 the cross-context crux, §2 the record, §3 capture flow + endpoints, §4 the gate, §5 privacy, §6
  erasure/export hook, §7 revocation = erasure, §8 residual forks, §9 scope guards, §10 the footprint.
  This story is its faithful decomposition — when in doubt, the note governs.
- Story 7.1 file + `epic-7-story-7.1-provisioning-design.md` — the `ProvisionedAccount` row model +
  "derived, never stored" discipline the consent record mirrors; the legitimate-interest basis for the
  silent shell that this story leaves unchanged.
- `ARCHITECTURE-SPINE.md` AD-1/AD-2 (hexagonal layer/dependency rules the `ConsentGate` must respect),
  AD-5 (`KeycloakUserId` pseudonym), AD-6 (no persisted PII), AD-7 (erasure by de-linking; the shell-row
  precedent the consent row follows).

### The crux — an identity record gating a collaboration action, PII-free and erasable (design §1)
- Consent **belongs to the account** → lives in `identity` (keyed by `KeycloakUserId`). The moment it
  gates is a **collaboration** action (`CreateHouseholdHandler`/`AcceptInviteHandler`). Bridge the two
  with a **`ConsentGate` port** owned by `collaboration`, implemented by an adapter that calls
  `identity`'s query — `collaboration` never imports `identity` internals (AD-1/AD-2). This is the whole
  architectural risk of the story; the ArchUnit check guards it.
- The record is pseudonymous, so AD-6 holds the same way it does for `ProvisionedAccount`; erasure
  deletes it with the shell (AD-7).

### GDPR / privacy (CLAUDE.md §5, AD-6/AD-7)
- Data minimization: `keycloakUserId` + `noticeVersion` + `acceptedAt` — nothing else. Purpose
  limitation: the single documented purpose is the lawful-basis record for processing household personal
  data. Auditability: `acceptedAt` + `noticeVersion`. Revocation = erasure (D-C) — do **not** model a
  withdrawn-but-retained half-state. Export/erasure *execution* is Epic 6; 7.4 provides `deleteFor` +
  the read model and adds the Epic-6 checklist pointer.

### Scope guards (KISS / YAGNI — CLAUDE.md §1)
- **In 7.4:** the `AccountConsent` record + `V20`, `RecordConsent`/`GetConsentStatus` + endpoints, the
  `ConsentGate` port + gate calls, the app consent screen + wiring, l10n/a11y, the privacy + cross-context
  tests.
- **Not in 7.4:** the erasure/export *feature* (Epic 6), a revoke UI or withdrawn state (Epic 6), any
  domain event or `Household` aggregate change, a `SecurityConfig` permit rule, analytics/tracking
  consent (SGART does none in beta — design §8 F4), the legal privacy-policy copy (a separate copy task,
  design §8 F3), and anything about invites' transport (7.5).

### Testing standards (CLAUDE.md §6)
- **Application (fast, no infra):** `RecordConsent` upsert + re-consent overwrite; `GetConsentStatus`
  no-row and version reporting; the gate returns false/true correctly.
- **Handlers:** create/accept without consent → `409`, no state; with consent → succeed.
- **Privacy:** `NoPersistedPersonalDataTest` covers `account_consent`; a column assertion proves no PII;
  the `deleteFor` hook removes the row.
- **Architecture:** ArchUnit proves `collaboration` reaches consent only through its port (AD-1/AD-2).
- **App:** gate blocks both create + join; not shown when current-version-accepted; re-shown on a
  version bump; a `409 consent.required` surfaces the gate.
- Synthetic, clearly-fake data only — never real personal data (CLAUDE.md §6 DSGVO-in-tests).

## Test Manifest (task → named test)

| Task / AC | Named test |
|-----------|------------|
| AC1/AC2 record | `recordConsent_storesTheAcceptedNoticeVersionAndTimestamp` |
| AC2 re-consent | `recordConsent_reAcceptWithNewerVersion_overwritesTheRow` |
| AC2/AC4 status | `getConsentStatus_noRow_returnsNotAccepted`, `getConsentStatus_returnsTheConfiguredCurrentVersion` |
| AC2 no-PII | `noPersistedPersonalData_accountConsentHoldsNoPii`, `NoPersistedPersonalDataTest` (account_consent has no email/name column) |
| AC3 create gate | `createHousehold_withoutRecordedConsent_isRejectedWith409ConsentRequired`, `createHousehold_withRecordedConsent_succeeds` |
| AC3 accept gate | `acceptInvite_withoutRecordedConsent_isRejectedWith409ConsentRequired` |
| AC3/AC6 arch | `collaborationReachesConsentOnlyThroughItsOwnPort` (ArchUnit) |
| AC5 erasure hook | `accountConsentRow_isDeletedByDeleteFor` |
| AC6 security | `consentEndpoints_requireAuth_andAddNoUnauthenticatedSurface` |
| AC1/AC4 app gate | `consentGate_blocksCreateAndJoinUntilAccepted`, `consentGate_notShownWhenCurrentVersionAlreadyAccepted`, `consentGate_shownAgainWhenNoticeVersionBumped` |
| AC3 app 409 | `createFlow_serverConsentRequired_surfacesTheGate` |

## Project Structure Notes

- **Backend (`de.sgart.identity`):** `domain` — `AccountConsent`, `AccountConsentRepository`;
  `adapter.out` — `JdbcAccountConsentRepository`, `InMemoryAccountConsentRepository`; `application` —
  `RecordConsent`, `GetConsentStatus`, `ConsentStatus`; `adapter.in` — `ConsentController` (+ error
  advice reuse). `V20__account_consent.sql`. Flat packages are fine (small addition, KISS §8).
- **Backend (`de.sgart.collaboration`):** `application` — `ConsentGate` port + `ConsentRequiredException`;
  `adapter.out` — `IdentityConsentGate`; gate calls added to `CreateHouseholdHandler` /
  `AcceptInviteHandler`; advice maps `ConsentRequiredException` → `409`.
- **No change:** any domain event, the `Household` aggregate, `SecurityConfig`'s permit rules, the
  `keycloak-authenticator` SPI, `MemberMapping`'s schema.
- **App (Flutter):** `ConsentApi` under `features/consent/data/` (or `features/auth/data/` if a
  dedicated feature folder is overkill — dev's call, keep it discoverable); `ConsentCubit`/state +
  `ConsentGatePage` under the matching `presentation/`; wiring in
  `features/households/presentation/create_or_await_choice_page.dart`; new `app_de.arb` strings +
  `l10n/gen/*`.

## References
- `epic-7-story-7.4-consent-capture-design.md` (the design note — governs)
- `epics.md` §Epic 7 Story 7.4 · `prd.md` FR-29 (consent clause) · `sprint-change-proposal-2026-09-13.md` (rev E)
- Story 7.1 + `epic-7-story-7.1-provisioning-design.md` (the `ProvisionedAccount` model this mirrors)
- `ARCHITECTURE-SPINE.md` AD-1/AD-2 (hexagonal), AD-5/AD-6/AD-7 (identity/PII/erasure)
- Backend: `ProvisionedAccount` · `ProvisionedAccountRepository` · `AuthenticatedCaller` ·
  `SecurityConfig` · `NoPersistedPersonalDataTest` · `CreateHouseholdHandler` · `AcceptInviteHandler`
- App: `create_or_await_choice_page.dart` · `first_run_router.dart` · `account_email_api.dart`
  (client shape) · `error_message_resolver.dart`

## Questions for Timo (non-blocking — four majors resolved 2026-09-17; residual defaults from design §8)

- **F1 — Notice version format** — default: a plain string compared for equality (re-consent when
  `accepted != current`), not ordered parsing. OK?
- **F3 — Consent screen copy + notice hosting** — default: short in-app localized text + a link to a
  hosted notice on the netcup host; the legal copy is a separate task before beta, not this story's code.
- **App feature folder** — default: a small `features/consent/` (data + presentation). Prefer folding
  into `features/auth/`?

## Change Log

| Date | Change | By |
|------|--------|-----|
| 2026-09-17 | Story drafted from `epic-7-story-7.4-consent-capture-design.md` (four majors pre-resolved with Timo: backend `AccountConsent` row; client gate + server fail-fast; revocation → Epic 6 erasure; short versioned in-app notice) + epics.md §Epic 7 Story 7.4 / prd.md FR-29, grounded in the shipped `identity` row model (`ProvisionedAccount`/repository, `AuthenticatedCaller`, `SecurityConfig`, `NoPersistedPersonalDataTest`) and the `collaboration` handlers (`CreateHouseholdHandler`, `AcceptInviteHandler`) + the app's `CreateOrAwaitChoicePage` gateway. Full-stack, small. Status → draft (ready for dev on Timo's go) | Winston (Opus 4.8) |
| 2026-09-17 | Implemented end-to-end. Backend `identity`: `V20__account_consent.sql`, `AccountConsent`/`AccountConsentRepository` (domain) + `JdbcAccountConsentRepository`/`InMemoryAccountConsentRepository` (adapter.out), `RecordConsent`/`GetConsentStatus`/`ConsentStatus` (application), `ConsentController` (`POST`/`GET /api/v1/consent`, no new `SecurityConfig` rule), `sgart.identity.consent.notice-version` config key (default `2026-beta-1`), `NoPersistedPersonalDataTest` extended. Backend `collaboration`: `ConsentGate` port (application) + `IdentityConsentGate` (adapter.out, delegates to `GetConsentStatus`) + `ConsentRequiredException` → `409 consent.required` in `WriteErrorAdvice`; gate call added at the top of `CreateHouseholdHandler`/`AcceptInviteHandler`; a new `HexagonalArchitectureTest` rule (`collaborationReachesConsentOnlyThroughItsOwnPort`) guards the crossing. App: `ConsentApi`/`ConsentCubit`/`ConsentState`/`ConsentGatePage` under `features/consent/`, `ConsentGatedChoicePage` wraps the existing `CreateOrAwaitChoicePage` unchanged and is wired into `FirstRunRouterBody`'s `needsChoice` branch; `app_de.arb` + `error_message_resolver` gained the `consent.required` arm; a stale-client `409` from the onboarding wizard's create step reloads the gate and pops back to it (`ConsentCubit.load()` re-provided by value across the wizard's push boundary, guarded-optional so `CreateOrAwaitChoicePage`'s own standalone tests stay unchanged). Both suites green: backend `./gradlew test` (incl. ArchUnit + Testcontainers) and app `flutter test` (716 tests) + `flutter analyze` (no issues). Deferred-work checklist pointer added for Epic 6 (`deferred-work.md`, "Deferred from: 7-4-consent-capture"). Status → done | Claude Sonnet 5 |
| 2026-09-17 | Implemented the seven non-deferred review action items. **Deep-link gate + 409 recovery** (AC1/AC3 completeness): `_PendingInviteLinkRouter` now awaits `ConsentApi.getStatus()` before routing a cold-start/OS-deep-link invite, opening the new `ConsentGatedAwaitInvitePage` (mirrors `ConsentGatedChoicePage`, its own pushed route) when consent is still needed instead of reaching `AwaitInvitePage` ungated; extracted the shared `ConsentLoadingPage`/`ConsentFailurePage` widgets (`consent_gate_status_pages.dart`) so both gates render identically (DRY). `AwaitInvitePage` also gained a `consent.required` 409 listener (mirrors `OnboardingWizardPage`'s create-side recovery) that reloads the gate and pops back; `openAwaitInvitePage` now re-provides an ancestor `ConsentCubit` across its push boundary (guarded-optional) so the "I have an invite" choice gets the same recovery. **Server-stamped notice version**: `RecordConsent` now takes `currentNoticeVersion` in its constructor (mirrors `GetConsentStatus`) and stamps it itself; `ConsentController.accept` takes no request body; the app's `ConsentApi.accept()` dropped its `noticeVersion` parameter to match (`FakeConsentApi` now derives the accepted version from `statusToReturn.currentVersion`, mirroring the real server). **Test/hygiene fixes**: added `IdentityConsentGateTest` (backend, pure unit), `consentRequiredResolvesToItsOwnLocalizedCopyNotTheGenericFallback` (app resolver test), replaced fully-qualified `KeycloakUserId`/`AuthenticatedCaller` references with imports, and moved the AD-1 shared-kernel Javadoc back onto `sharedKernelIsFreeOfInfrastructure` in `HexagonalArchitectureTest`. Both suites green: backend `./gradlew test` (incl. ArchUnit + Testcontainers) and app `flutter test` (717 tests) + `flutter analyze` (no issues). The three `[Review][Defer]` items are unchanged (still deferred, tracked in `deferred-work.md`) | Claude Sonnet 5 |

## Review Findings

Code review 2026-09-17 (Opus 4.8, four parallel layers: Blind Hunter, Edge Case Hunter, Verification
Gap, Acceptance Auditor). Severities re-graded during triage; each finding verified against the code.

- [x] [Review][Patch] (Decision resolved 2026-09-17 → gate deep-link + add 409 recovery) Accept-invite consent flow is incomplete (AC1/AC3) — the consent gate fronts
  only the `HouseholdsStatus.needsChoice` branch (`ConsentGatedChoicePage`). A cold-start/OS deep-link
  invite is routed by `_PendingInviteLinkRouter._consumeAndRoute → openAwaitInvitePage`
  [app/lib/features/households/presentation/first_run_router.dart:204] with no consent check, so an
  unconsented caller reaches `AwaitInvitePage` without ever seeing `ConsentGatePage`. On the resulting
  server `409 consent.required`, the accept path renders the generic `consentRequiredError` copy inline
  with no route back to the gate — unlike the create path, which reloads the gate and pops to it
  (`onboarding_wizard_page.dart`). Server fail-fast keeps data safe (no household/membership created),
  so this is a UX/AC1-completeness gap, not a data-protection hole. Decide: gate the deep-link accept
  path client-side and/or add a 409→gate recovery to the accept path, or accept the server-only
  guarantee for invites and document it.
- [x] [Review][Patch] (Decision resolved 2026-09-17 → drop `noticeVersion` from the POST body; `RecordConsent` stamps the deployment's `currentNoticeVersion`) Server persists the client-supplied `noticeVersion` unvalidated
  [backend/src/main/java/de/sgart/identity/application/RecordConsent.java:record;
  backend/src/main/java/de/sgart/identity/adapter/in/ConsentController.java:accept] — `RecordConsent`
  null-checks but does not blank-check or compare against the deployment's `currentNoticeVersion`, and
  `ConsentController` records the request body verbatim. Consequences: (a) a client can record consent
  for a fabricated/never-deployed version that still satisfies the presence-only backend gate
  (`IdentityConsentGate.hasRecordedConsent` → `.accepted()`), weakening the lawful-basis proof; (b) a
  missing `noticeVersion` → `Objects.requireNonNull` NPE surfaces as an opaque 500 rather than 400; (c)
  a blank `""` is stored by `JdbcAccountConsentRepository` (raw INSERT) but rejected by
  `InMemoryAccountConsentRepository` (constructs `AccountConsent`, which throws) — prod/test divergence.
  Decide the contract: reject a non-current version (400), or drop the body and stamp the server's
  `currentNoticeVersion`. (Note: server enforcement being row-existence only matches AC3/AC4 as
  written — version re-consent is client-side by design — but that is exactly why an unvalidated
  recorded version is load-bearing here.)
- [x] [Review][Defer] (Decision resolved 2026-09-17 → accept placeholder for in-review; tracked pre-beta task added to deferred-work) Privacy-notice "link" is inert placeholder text (AC1, design §3/§8 F3)
  [app/lib/features/consent/presentation/consent_gate_page.dart:noticeUrl] — `noticeUrl =
  'sgart.example/privacy'` is scheme-less and rendered as a non-tappable `Text`. Deferring a URL
  launcher is a defensible YAGNI call, but AC1 lists a link to the hosted notice and informed consent
  needs the notice reachable before accepting — beta cannot ship with a non-openable notice. Decide:
  accept the placeholder for in-review with a tracked pre-beta task, or wire a real launch now. (Minor
  DRY: the `label: $noticeUrl` string is built identically twice.)

- [x] [Review][Patch] consent.required error-copy mapping has no resolver test — add
  `consentRequiredResolvesToItsOwnLocalizedCopyNotTheGenericFallback` mirroring the sibling per-code
  tests [app/test/shared/errors/error_message_resolver_test.dart]; without it, a regression in the
  `'consent.required' => consentRequiredError` arm (the string the accept path shows) ships silently.
- [x] [Review][Patch] No unit test for `IdentityConsentGate` — the sanctioned cross-context delegation
  is only exercised via hand-rolled fakes in handler tests
  [backend/src/main/java/de/sgart/collaboration/adapter/out/IdentityConsentGate.java].
- [x] [Review][Patch] Orphaned ArchUnit Javadoc — the AD-1 shared-kernel Javadoc was separated from
  `sharedKernelIsFreeOfInfrastructure` by the new rule's block; move it back so each rule carries its
  own doc [backend/src/test/java/de/sgart/architecture/HexagonalArchitectureTest.java:91].
- [x] [Review][Patch] Fully-qualified inline class references instead of imports (CLAUDE.md §2/§8)
  [backend/src/main/java/de/sgart/identity/adapter/in/ConsentController.java:callerId;
  backend/src/test/java/de/sgart/identity/application/GetConsentStatusTest.java].
- [x] [Review][Patch] `_ConsentFailurePage` discards `state.error` and always renders
  `errorGenericFallback` [app/lib/features/consent/presentation/consent_gated_choice_page.dart].

- [x] [Review][Defer] No retention period documented for `account_consent` (CLAUDE.md §5 storage
  limitation) [backend/src/main/resources/db/migration/V20__account_consent.sql] — deferred: the story
  otherwise documents GDPR posture thoroughly; retention/audit-history policy for the consent record is
  a design decision (interacts with the AC2-mandated single-row upsert) better settled at the Epic 6
  erasure/export design than patched here.
- [x] [Review][Defer] `deleteFor` erasure hook is provided but unwired and untested end-to-end for
  right-to-erasure [backend/src/main/java/de/sgart/identity/domain/AccountConsentRepository.java] —
  deferred: matches AC5 (7.4 provides `deleteFor`, Epic 6 wires it); already tracked in deferred-work
  and on the Epic-4/Epic-7 GDPR critical path.

### Rejected

- (false) "Only `app_de.arb` updated — other locales/template may break `gen-l10n`" — `app_de.arb` **is**
  the `template-arb-file` (`app/l10n.yaml`) and the app is single-locale; the five keys land in the
  template. Build is green.
- (false) "`NoPersistedPersonalDataTest` whitelist not updated for `account_consent`" — the test uses a
  per-migration file-scan style; the diff adds `noPersistedPersonalData_accountConsentHoldsNoPii`
  reading `V20`, matching the existing pattern. Green build confirms it.
- (spec-consistent) "Backend gate ignores version currency" — matches AC3 ("no consent row" → reject)
  and AC4 (version re-consent is client-side); enforcing version server-side would change the spec.
  Surfaced instead as context for the unvalidated-`noticeVersion` decision above.
- (spec-consistent) "Consent upsert overwrites prior consent history (audit)" — AC2 explicitly mandates
  one current consent per account (overwrite on re-accept); folded into the retention defer note.
- (low) "Consent check runs before household-name validation" — fail-fast on consent before doing any
  work is defensible, no reachable harm shown, and both suites are green.

## Review Findings — Re-review 2026-09-17 (round 2)

Second code review (Opus 4.8, four parallel layers: Blind Hunter, Edge Case Hunter, Verification Gap,
Acceptance Auditor), after the seven round-1 patches + two resolved decisions. **Both resolved-decision
fixes verified correct:** server-stamped notice version (`RecordConsent` stamps `currentNoticeVersion`,
body-less `POST`, `FakeConsentApi` mirrors it) and the deep-link consent gate + create-path 409 recovery.
Per-AC audit: AC1–AC6 substantively met. New findings below; each verified against the code.

- [x] [Review][Patch] Deep-link accept path drops the invite on a consent-status network error
  [app/lib/features/households/presentation/first_run_router.dart:180-191] — `_routeToAcceptFlow` awaits
  `ConsentApi.getStatus()` with no try/catch inside `unawaited(...)`, and only after
  `widget.cubit.consume()` already took the link. On a cold-start/offline deep link `getStatus()` throws
  → unhandled async error, the consumed link is silently lost, and the user gets no gate, no accept, and
  no error — worse than the pre-7.4 synchronous `openAwaitInvitePage`. Every other status call is wrapped
  by `ConsentCubit.load/accept` (→ `ConsentFailurePage` retry); this one is not. Fix: catch the failure
  and open the gated await page (its cubit then surfaces the failure/retry) so the link survives into the
  pushed route.
- [x] [Review][Patch] Accept-side 409 `consent.required` recovery is untested
  [app/test/features/households/presentation/await_invite_page_test.dart] — the new `AwaitInvitePage`
  listener (await_invite_page.dart:114-122) that reloads consent + pops to the gate on
  `AcceptInviteStatus.failure && code == 'consent.required'` has no widget test; only the create-side
  (`createFlow_serverConsentRequired_surfacesTheGate`) and the deep-link pre-gate are covered. This
  load-bearing half of the fix-2 recovery can regress silently (CLAUDE.md §6). Add: push `AwaitInvitePage`
  under a `BlocProvider<ConsentCubit>` (FakeConsentApi), set `invitesApi.acceptInviteError` to a
  `consent.required` `AppException`, tap join, assert the route popped and `getStatusCallCount` incremented.
- [x] [Review][Patch] `ConsentGatePage` is not scrollable — overflow on large text / short screens
  [app/lib/features/consent/presentation/consent_gate_page.dart] — heading + body + link + button sit in a
  `Column` with two `Spacer`s and no `SingleChildScrollView`; under a large OS text scale or a short
  viewport the Spacers collapse and the fixed content overflows (RenderFlex), potentially hiding the accept
  button on this keystone a11y screen. Every comparable page (onboarding name step, recover/confirm-email,
  create-household, await-invite) wraps content in `SingleChildScrollView`; this one uniquely does not.
  Fix with the repo's recipe (SingleChildScrollView + ConstrainedBox(minHeight) + IntrinsicHeight so the
  Spacers still center on tall screens).
- [x] [Review][Patch] Two minor test-coverage gaps [app/test/features/consent/...;
  backend/.../identity/adapter/in/ConsentControllerTest.java] — (a) no widget test exercises
  `ConsentGatedAwaitInvitePage`'s loading/failure branches (or the shared `ConsentFailurePage`); (b) the
  `ConsentControllerTest` GET tests assert `accepted`/`acceptedVersion` but never `currentVersion`, though
  AC2's read model promises it. Add the two assertions/tests.

- [x] [Review][Defer] Consent withdrawal = full-account erasure only — possible GDPR Art 7(3)
  [app/lib/features/consent/, design §7] — deferred: revocation-routes-to-erasure is locked decision D-C
  and Epic 6 owns erasure/revocation. Art 7(3) requires withdrawal be "as easy as" giving; full-account
  deletion may not satisfy that. Worth re-confirming with Timo at the Epic 6 design; not actionable in 7.4.
- [x] [Review][Defer] No retention period for `account_consent`
  [backend/src/main/resources/db/migration/V20__account_consent.sql] — deferred: already tracked (round-1
  defer, deferred-work.md); settle retention + audit-history at Epic 6.
- [x] [Review][Defer] Inert privacy-notice link (`sgart.example/privacy`)
  [app/lib/features/consent/presentation/consent_gate_page.dart] — deferred: already tracked (round-1
  defer, deferred-work.md); pre-beta gate — informed consent needs a reachable notice.

### Rejected (round 2)

- (spec-consistent) BH "server gate enforces consent presence, not version currency" — matches AC3 (no row
  → reject) + AC4 (version re-consent is client-side by design, via GET `currentVersion`); enforcing version
  server-side would edit the spec. `IdentityConsentGateTest` documents the intent.
- (spec-consistent) BH "re-accept overwrites consent history (auditability)" — AC2 explicitly mandates one
  current consent per account (upsert); the audit-history question folds into the retention defer.
- (spec-consistent) BH "`deleteFor` is production-unused (dead code)" — AC5 mandates 7.4 provides it and
  Epic 6 wires it; tracked in deferred-work.
- (false) BH "no direct test that `WriteErrorAdvice` maps `ConsentRequiredException` → 409" —
  `HouseholdControllerTest`/`InviteControllerTest` both assert 409 `consent.required` through the full
  MockMvc seam, which exercises the advice.
- (false, code) AA "AC1/Tasks prose still says `POST { noticeVersion }`" — the code correctly dropped the
  body per the resolved decision; the stale prose is in this story doc (a doc-hygiene edit to AC1/Tasks,
  advisable but not a code defect).
- (false) AA "Epic-6 checklist pointer not evidenced" — the `deferred-work.md` pointer exists; it was
  outside the code-only diff under review.
- (low) AA+BH "deep-link 409-during-accept loses the consumed invite / reloads a disposed cubit" — rare
  stale-client race (version bump between choice screen and accept), server keeps data safe (no membership
  created), and the fix (re-offer the consumed link) needs product judgment + more than a direct
  correction. Recovery is not symmetric with the create path; revisit if the race proves real.
- (low) BH "`accept()` uses `postJson` (Map-returning) for a 204 endpoint; no `postJsonVoid`" — works (dio
  null → `const {}`); a convention nit whose fix adds public surface.
- (low) BH "deep-link gated accept double-fetches `GET /api/v1/consent`" — one extra idempotent GET on a
  rare path; the fix restructures the pre-route check.
- (low) BH "name overload: `ConsentStatus` (Dart+Java), `ConsentState`, `ConsentGateStatus`,
  `GetConsentStatus`" — readability nit; the Dart/Java `ConsentStatus` pair is a deliberate transport
  mirror; a rename touches many files with no everyday harm.
