# Story 7.4 — Consent Capture: Architect Design Note

**Author:** Winston (System Architect) · **Date:** 2026-09-17 · **Status:** draft for Timo's review
**Feeds:** `create-story` 7.4 · **Scope agreed with Timo (2026-09-17):** one story covering the
consent moment at first household create/join — the capture UI, a pseudonymous backend consent
record, server-side fail-fast enforcement, and the erasure/export + revocation hooks Epic 6 will wire.

**Ground truth:** `epics.md` §Epic 7 (Story 7.4), `prd.md` FR-29 (the consent clause), `CLAUDE.md` §5
(DSGVO/GDPR), `ARCHITECTURE-SPINE.md` (AD-5 identity ACL, AD-6 no-persisted-PII, AD-7
erasure-by-de-linking), `sprint-change-proposal-2026-09-13.md` (rev E), the shipped `identity` context
(`ProvisionAccount`/`ProvisionedAccount`/`ProvisionedAccountRepository`, `SweepNeverActivatedAccounts`,
`KeycloakAdminCreateAccount`, `SecurityConfig`), the shipped `collaboration` handlers
(`CreateHouseholdHandler`, `AcceptInviteHandler`), and the app's first-run routing
(`FirstRunRouter` → `CreateOrAwaitChoicePage` → create / await-invite).

**Decisions locked with Timo (2026-09-17):**

- **A — Storage:** a **backend `AccountConsent` record** in the `identity` context (pseudonymous:
  `keycloakUserId` + `noticeVersion` + `acceptedAt`), mirroring `ProvisionedAccount`. Auditable
  (CLAUDE.md §5), erasable/exportable, **no PII** (AD-6).
- **B — Enforcement:** **client gate + server fail-fast.** The UI blocks create/join until accepted,
  **and** `CreateHouseholdHandler` / `AcceptInviteHandler` reject the command when no consent record
  exists for the caller.
- **C — Revocation:** **routes to the Epic 6 erasure path.** Revoking consent to process household
  personal data means the person can no longer be in a household — i.e. account/data deletion. 7.4
  makes the record erasable and revocation-ready; the affordance lands with Epic 6.
- **D — Notice artifact:** a **short, versioned in-app privacy notice + terms.** 7.4 builds the
  capture against a `noticeVersion` reference (which drives future re-consent); the beta copy is
  drafted separately and finalized before launch. The record stores the accepted version.

---

## 1. The crux: an identity-context record gating a collaboration-context action, PII-free and erasable

Three constraints pull in different directions and the design has to satisfy all three at once:

1. **Consent belongs to the person/account** — that is the `identity` context (it already owns the
   account shell, its retention sweep, and the recovery-email lifecycle). So the record lives in
   `identity`, keyed by the stable `KeycloakUserId`.
2. **The moment that needs gating is a collaboration action** — the *first* processing of household
   personal data (CLAUDE.md §5 counts household membership as personal data) is
   `CreateHouseholdHandler` or `AcceptInviteHandler`, both in the `collaboration` context.
3. **No personal data may be persisted (AD-6)** and the whole thing must be **erasable/exportable
   (AD-7)** by Epic 6.

The resolution: consent is **recorded** by an `identity` command and **enforced** at the two
collaboration handlers through a **port**, so `collaboration` depends on an abstraction, never on
`identity`'s internals (hexagonal, AD-1/AD-2). The record is pseudonymous, so it satisfies AD-6 the
same way `ProvisionedAccount` does; erasure deletes it alongside the shell (AD-7).

> **Consent = a `RecordConsent` command in `identity` (writes one pseudonymous row); the create/accept
> handlers fail-fast through a `ConsentGate` port that asks `identity` whether a current consent row
> exists for the caller.**

The **legitimate-interest basis for the silent shell (7.1) is unchanged** — no household personal data
is processed before this moment, so nothing before the consent screen needs consent (FR-29 note,
epics.md §7.4 note). This design only introduces the *consent* basis for the household step.

## 2. The consent record (`identity` context, `V20`)

A small JDBC row model mirroring `ProvisionedAccount` exactly — **not** an event-sourced aggregate
(the `identity` context is a row model, not event-sourced):

```
AccountConsent(
  KeycloakUserId keycloakUserId,   // pseudonymous, already in MemberMapping (AD-6)
  String         noticeVersion,    // the privacy-notice/terms version accepted (Decision D)
  Instant        acceptedAt        // audit timestamp (CLAUDE.md §5 auditability)
)
```

- **Migration `V20__account_consent.sql`** — table `account_consent`, primary key `keycloak_user_id`
  (one current consent per account; a re-consent on a new notice version **overwrites** it, the same
  upsert discipline as the recovery-code store). No email/name column — the table name carries no
  PII-substring that would trip `NoPersistedPersonalDataTest` (same care as 7.3's `recovery_code`).
- **`domain`:** `AccountConsent` (record + invariants, fail-fast non-null), `AccountConsentRepository`
  (port: `record`, `findFor`, `deleteFor`).
- **`adapter.out`:** `JdbcAccountConsentRepository` + an `InMemoryAccountConsentRepository` test double
  (mirrors the `ProvisionedAccountRepository` pair).

Auditability (CLAUDE.md §5) is served by `acceptedAt` + `noticeVersion`; storage limitation is served
by erasure and by the record dying with the account.

## 3. Capture flow (client) + the `RecordConsent` command

**Command (CQRS — state change, returns nothing beyond success):**
`RecordConsent(keycloakUserId, noticeVersion)` in `identity.application` → upserts the row.
**Query (side-effect-free):** `GetConsentStatus(keycloakUserId) → { accepted: bool, acceptedVersion }`
so the client can decide whether to show the screen.

**Endpoints** (authenticated as the caller's own JWT — **no** new `SecurityConfig` permit rule; these
sit under the existing `/api/v1/**`.authenticated() surface, same as 7.3's recovery endpoints):
- `POST /api/v1/consent` — body `{ noticeVersion }` → `RecordConsent` → `204`.
- `GET  /api/v1/consent` — → `GetConsentStatus` read model.

**Client placement.** The gate lives at the `CreateOrAwaitChoicePage` fork (`FirstRunRouter` →
`needsChoice`). On entry the client reads `GET /api/v1/consent`; the consent screen is shown (and blocks
"Haushalt erstellen" / "Ich habe eine Einladung") **iff** not accepted **or** `acceptedVersion <
currentNoticeVersion` (re-consent). On accept → `POST /api/v1/consent` → proceed to create/await.
Because consent is server-recorded and keyed by `KeycloakUserId`, a relaunch/reinstall does **not**
re-prompt (the row persists) — only a notice-version bump does. One gate covers **both** create and
join (AC: "creating or joining").

## 4. Server-side enforcement (the `ConsentGate` port)

`collaboration` must fail-fast when a create/accept command arrives without a recorded consent — a
non-official client must not be able to skip the screen (Decision B).

- **`collaboration.application` defines the port** `ConsentGate.hasRecordedConsent(KeycloakUserId):
  boolean` (an *outbound* port — `collaboration` owns the abstraction, points inward per AD-1/AD-2).
- **`collaboration.adapter.out`** implements it by delegating to `identity`'s `GetConsentStatus`
  query. This is the one sanctioned synchronous cross-context read — consistent with the existing
  identity-ACL crossing (`MemberMapping` resolution: *mapping = access*).
- **`CreateHouseholdHandler` / `AcceptInviteHandler`** call the gate first and throw a
  `ConsentRequiredException` (a `collaboration` application exception) when it returns false. Translated
  at the `adapter.in` seam to a typed `ErrorDescriptor` (proposed HTTP **409** `consent.required`, so a
  stale client can recover by showing the screen; **not** 403, which reads as an auth failure).

Business rule stays in the application layer, not the controller (CLAUDE.md §3). The check is the whole
of it — commands otherwise unchanged; no new event, no `Household` aggregate change.

## 5. Security & privacy surface (AD-6 / AD-7, CLAUDE.md §5)

- **Data minimization / no PII (AD-6):** the row is `keycloakUserId` + version + timestamp — the same
  minimization set as `ProvisionedAccount`. No name, no email, no IP.
- **Purpose limitation:** the single documented purpose is "lawful-basis record for processing
  household personal data"; `noticeVersion` ties it to exactly what was agreed.
- **Auditability:** `acceptedAt` + `noticeVersion` are the audit trail; the record is queryable by the
  erasure/export use case.
- **Security by default:** authenticated-as-caller endpoints; no new open surface; encrypted in transit
  (TLS proxy, ADR-0002) and at rest (LUKS volume, ADR-0002) with the rest of Postgres.

## 6. Erasure & export inclusion (Epic 6 hook)

- **Erasure (AD-7):** `account_consent` joins the erasure checklist next to the `ProvisionedAccount`
  shell row — `AccountConsentRepository.deleteFor(keycloakUserId)` is called by Epic 6's erasure use
  case alongside the Keycloak account delete. 7.4 provides `deleteFor`; **Epic 6 wires it** (same
  division of labor 7.1 used for the shell row).
- **Export (portability):** the export set includes `{ noticeVersion, acceptedAt }` for the account.
  7.4 exposes it via `GetConsentStatus`/repository; Epic 6 assembles the export bundle.
- 7.4 adds a checklist bullet to `deferred-work.md` / the Epic 6 erasure story pointer so this is not
  forgotten (the AD-7 shell-row precedent).

## 7. Revocation = erasure (Decision C)

Revoking consent to process household personal data is, by construction, the erasure path: you cannot
remain in a household without the lawful basis for it. 7.4 therefore does **not** invent a distinct
"consent withdrawn but data retained" state (which would be an ill-defined half-membership no other
story models). It makes the record erasable/revocation-ready; the revoke affordance and its routing to
account deletion land with **Epic 6**. The design note records this so Epic 6 treats "revoke consent"
and "erase me" as one mechanism.

## 8. Remaining forks / open questions for Timo

The four majors (§Decisions) are resolved. Residual, smaller calls — sensible defaults proposed, flag
any you want to change:

- **F1 — Notice version format.** Proposed: a plain monotonic string (`"2026-beta-1"`), compared for
  equality (re-consent when `accepted != current`), not ordered parsing. KISS; ordering isn't needed.
- **F2 — Where the current `noticeVersion` is defined.** Proposed: a single backend config key
  (`sgart.identity.consent.notice-version`) returned by `GET /api/v1/consent` so the client never
  hard-codes it and a bump is a one-line deploy change. (Alternative: bake into the app build — rejected,
  couples re-consent to an app release.)
- **F3 — Consent screen copy + notice hosting.** Proposed: short in-app text (localized `.arb`) with a
  link to a hosted privacy notice on the netcup host (docs/first-real-world-test.md deploy seam). Copy
  drafted before beta, not in this story's code.
- **F4 — Analytics/consent for non-essential processing.** Out of scope: SGART does no analytics/
  tracking in beta, so 7.4 captures only the single service-processing consent. Noted so it isn't
  assumed missing.

## 9. Scope guards (YAGNI / KISS — CLAUDE.md §1)

7.4 does **NOT**: add a domain event or touch the `Household` aggregate; add a `SecurityConfig` permit
rule (endpoints are authenticated); build the erasure/export execution (Epic 6 owns it); build a
distinct revoke UI/state (Decision C → Epic 6); draft the legal privacy-policy text (Decision D → copy
task); add analytics consent (F4); or capture consent for the silent shell (legitimate interest, FR-29).

## 10. Buildable outline + test-manifest seeds

**Backend (`identity`):** `V20__account_consent.sql`; `AccountConsent` + `AccountConsentRepository`
(domain); `JdbcAccountConsentRepository` + `InMemoryAccountConsentRepository` (adapter.out);
`RecordConsent` (command) + `GetConsentStatus` (query) + `ConsentStatus` read model (application);
`ConsentController` (`POST`/`GET /api/v1/consent`) + error advice.
**Backend (`collaboration`):** `ConsentGate` port (application) + `IdentityConsentGate` (adapter.out
→ identity query); `ConsentRequiredException` + advice (`409 consent.required`); gate call added to
`CreateHouseholdHandler` and `AcceptInviteHandler`.
**App:** `ConsentApi` + `ConsentCubit`/state; `ConsentGatePage` (privacy notice + accept); wire into
`CreateOrAwaitChoicePage` (block create/await until accepted / on version bump); `app_de.arb` copy +
`error_message_resolver` arm for `consent.required`.

**Test manifest (seeds):**
- `recordConsent_storesTheAcceptedNoticeVersionAndTimestamp`
- `recordConsent_reAcceptWithNewVersion_overwritesTheRow`
- `getConsentStatus_noRow_returnsNotAccepted`
- `createHousehold_withoutRecordedConsent_isRejectedWith409ConsentRequired`
- `acceptInvite_withoutRecordedConsent_isRejectedWith409ConsentRequired`
- `createHousehold_withRecordedConsent_succeeds`
- `accountConsentRow_isDeletedByEraseFor` (the Epic 6 hook contract)
- `noPersistedPersonalData_accountConsentHoldsNoPii` (extend `NoPersistedPersonalDataTest`)
- App: `consentGate_blocksCreateAndJoinUntilAccepted`, `consentGate_notShownWhenAlreadyAcceptedCurrentVersion`,
  `consentGate_shownAgainWhenNoticeVersionBumped`.

## References

- `epics.md` §Epic 7 Story 7.4 · `prd.md` FR-29 · `CLAUDE.md` §5
- `ARCHITECTURE-SPINE.md` AD-5 / AD-6 / AD-7
- `epic-7-story-7.1-provisioning-design.md` (the `ProvisionedAccount` model this mirrors)
- Shipped: `de.sgart.identity.domain.ProvisionedAccount`, `…application.ProvisionAccount`,
  `de.sgart.collaboration.application.command.CreateHouseholdHandler` / `AcceptInviteHandler`,
  `app/lib/features/households/presentation/create_or_await_choice_page.dart`,
  `…/first_run_router.dart`
