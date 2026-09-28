# Epic 8 Context: Beta Hardening

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Close the gaps surfaced by the first hands-on emulator test passes (manual testing 2026-09-20)
so the already-shipped scope is genuinely beta-ready. Epic 8 adds **no new product scope**: it
makes the session effectively permanent on a trusted device, gives each person a self-chosen
per-household nickname shown throughout the household, fixes the household-management crash that blocks invites, reduces
invites to one replaceable household code, replaces the 24-word recovery phrase with a short
token, turns on real recovery emails (beta SMTP + a local mail catcher), and closes two
engineering gaps a real multi-user beta exposes (a live-sync projector throw and the provisioning
epic's missing critical-path tests). It hardens FR15 (device session/identity), FR2 (invites &
membership), and FR7 (live sync). Epic numbers are **stable IDs**, not execution order — Epic 8
runs after Epic 7 and before Epics 5 & 6.

## Stories

- Story 8.1: Fix the „Haushalt verwalten" crash so invites are reachable
- Story 8.2: Session never expires on a trusted device (silent device-credential re-auth)
- Story 8.3: Choose a per-household nickname at onboarding, shown throughout the household
- Story 8.4: Single replaceable household invite code
- Story 8.5: Short recovery token instead of a 24-word phrase
- Story 8.6: Real recovery emails — beta SMTP + local mail catcher
- Story 8.7: Hide the household selector on the Profile tab
- Story 8.8: Fix the live-sync projector stream-filter throw
- Story 8.9: Provisioning epic (Epic 7) critical-path test coverage

## Requirements & Constraints

- **No new scope.** Every story hardens shipped behavior; do not introduce new product
  capabilities. Prefer the smallest change that makes the existing feature beta-worthy.
- **Personal data discipline (DSGVO).** Nickname, email, purchase history, receipt contents,
  and household membership are personal data. Keycloak-sourced identity data (name, email) is read
  live from the JWT/Keycloak and never persisted; anything newly stored needs a documented purpose,
  a lawful basis, and must be locatable for erasure/export. Domain events carry only `MemberId` —
  never a name, nickname, or email.
- **Green build means both suites.** At story completion run backend `./gradlew test` (incl. the
  ArchUnit architecture tests) **and** the app's `flutter test` / `flutter analyze`. A partial or
  single-module run is not a green build; always name which suite ran.
- **Every bug fix starts with a failing regression test** that reproduces the defect (several
  Epic 8 stories are explicitly defect fixes: 8.1, 8.8).
- **Recovery secret entropy floor** ≥ 120 bits for the short token (8.5) — a deliberate
  security-vs-usability trade-off; device key is KDF-derived from the token.
- **No migration burden.** No real beta has run (emulator-only, no persisted data), so the event
  store can start from zero — schema/credential changes need no back-fill.

## Technical Decisions

- **Hexagonal + event-sourced.** Bounded contexts (`collaboration`, `identity`, `storereference`,
  `shared`) split into `domain / application / adapter.in / adapter.out`; domain stays free of
  framework/persistence/transport types (ArchUnit AD-1/AD-2). Commands → aggregate → events
  appended to KurrentDB under expected-version; PostgreSQL read models are projection-only.
- **Identity ACL** resolves `(keycloakUserId, householdId) → MemberId` before the domain is
  touched; the opaque user id comes only from the JWT `sub`.
- **Browserless auth (Epic 7).** Sign-in is a custom Direct-Grant flow, not the AppAuth browser
  redirect. Silent access-token refresh + retry already ship (`spec-auth-refresh-and-signout-fixes`);
  8.2 extends this with silent **device-credential** re-auth and a single shared in-flight refresh.
- **No persisted PII, with one named exception (AD-6 rev F, from 8.3).** A self-chosen,
  per-household nickname is persisted in the identity context's mutable `membership_nickname`
  table, keyed `(keycloakUserId, householdId)`. It is never written to an event or an
  event-derived projection, has no uniqueness constraint, and is deleted on account erasure and on
  membership de-link (`deleteFor` / `deleteForMembership`, mirroring the mapping-table shape) and
  included in export. `NoPersistedPersonalDataTest` carries an explicit, named exemption for this
  table only — do not widen it. Collaboration stays PII-free: it resolves nicknames only through
  the published `ResolveMembershipNicknames` identity port (plain-type signatures, AD-2), and
  there are no Keycloak Admin lookups on the roster read path.
- **Invite model (8.4)** moves to one replaceable per-household code enforced in the `Household`
  domain via the known creator; accept does not consume it and there is no TTL.
- **Projector stream filters (8.8)** must be built with a single fanout regex — never chain
  `addStreamNamePrefix` twice (mirrors Story 4.4's fix) or KurrentDB throws
  `IllegalStateException: Filter type is already set`.
- **Mail (8.6)** is gated by `sgart.identity.mail.enabled`; beta uses real SMTP with SPF/DKIM
  (ADR-0002) and no plaintext credentials; local dev uses a Mailpit container wired by `start.sh`.

## UX & Interaction Patterns

- **Profil is personal-only** (UX-DR6/UX-DR14): no household management and no top-bar household
  selector on the Profile tab; the selector stays on the household-scoped Listen/Einkauf tabs.
- **Household management hub** must open from the top-bar switcher and expose reachable members
  and invites rows; the invite entry point should be discoverable (promoted action or descriptive
  subtitle).
- **Onboarding** has a required „Wie möchtest du genannt werden?" nickname step in both entry points
  (create-household and join); German, plain-language, validated client- and server-side
  (non-blank/whitespace, length-bounded), editable later from Profile. The Profile header and member
  roster show the active household's nickname (JWT name only as a fallback when unset).
- All user-facing strings resolve through the key-based localization layer (`de-DE`), never
  hard-coded; errors are shown via localized copy keyed by `code`.

## Cross-Story Dependencies

- **8.4 depends on 8.1** — the invite entry point lives inside the household-management hub that
  8.1 makes reachable.
- **8.2** builds on the shipped access-token refresh (`spec-auth-refresh-and-signout-fixes`) and
  the Epic 7 device-credential Direct-Grant challenge.
- **8.5 → 8.6/8.9** — the short recovery token replaces the phrase seeded at first create/join;
  8.6 makes the recovery email deliverable and 8.9 adds the recover-by-email test coverage.
- **8.9** back-fills required tests for Epic 7 (provisioning, recover-by-email, erasure/export,
  retention sweep, endpoint rate-limiting); erasure/export scope now also includes the 8.3
  `membership_nickname` rows.
