---
stepsCompleted: [1, 2, 3, 4]
inputDocuments:
  - specs/spec-sgart/SPEC.md
  - specs/spec-sgart/glossary.md
  - _bmad-output/planning-artifacts/prds/prd-sgart-2026-08-06/prd.md
  - _bmad-output/planning-artifacts/prds/prd-sgart-2026-08-06/addendum.md
  - _bmad-output/planning-artifacts/architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md
  - _bmad-output/planning-artifacts/ux-designs/ux-sgart-2026-08-20/DESIGN.md
  - _bmad-output/planning-artifacts/ux-designs/ux-sgart-2026-08-20/EXPERIENCE.md
  - CLAUDE.md
---

# sgart - Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for **SGART** (Smart Grocery
And Receipt Tracker), decomposing the requirements from the canonical **SPEC** (CAP-1…14),
the **Architecture spine** (AD-1…11), and the **UX design contract** (DESIGN.md +
EXPERIENCE.md) into implementable stories.

**Scope:** MVP only. Post-MVP capabilities (receipts & OCR, price intelligence / Product
pinning / price-based routing, analytics dashboard, configurable notifications, list
templates, geolocation store discovery) are explicitly deferred and are **not** turned into
stories here — see "Deferred (out of MVP)" at the end of the inventory.

**Canonical requirement source:** `SPEC.md` capabilities `CAP-1…14`. PRD `FR-*` numbers are
cited only as supporting detail. Where they conflict, SPEC and the Architecture spine win
(e.g. Trip lifecycle is permanently `Active → Done`; PRD FR-15 pause/resume is dropped).

## Requirements Inventory

### Functional Requirements

*Each FR corresponds to a SPEC capability (CAP-n) and is the canonical functional statement.*

FR1 (CAP-1): **Households & first-run routing.** An authenticated person with no Household can create one (becoming its first Admin) or wait for an invite; on launch, routing depends on how many Households they belong to — zero → create/await-invite choice; exactly one → straight in; several → a selection screen. *(PRD FR-1)*

FR2 (CAP-2): **Invite & membership lifecycle with roles.** Any Member can invite a person by email; the invitee authenticates via the link and joins as `Participant`. `HouseholdRole {Admin, Participant}` gates only governance actions (both roles do all daily work). A second *pending* invite to the same email is rejected; an expired invite cannot be redeemed; the last Admin cannot leave, be removed, or be demoted; only an Admin can remove other Members, promote to Admin, or delete the Household. *(PRD FR-2, FR-3)*

FR3 (CAP-3): **Store management with client-side chain matching.** Any Member can add/remove the Household's Stores by free-form name (unique per Household; duplicate rejected). An advisory `StoreChain` suggestion is matched client-side as the user types (accept / change / clear — never forced or decided server-side). The StoreChain reference list works offline after its first cached load. Removing a Store **archives** it (hidden from future selection) without deleting historical Trips or assignments that referenced it. *(PRD FR-4, FR-5)*

FR4 (CAP-4): **Multiple shopping lists with optional auto-naming.** A Household can hold several **Open** Lists at once, each with an optional name. An unnamed List displays as "Liste {N}" by creation order among Open/In-Trip Lists (derived, never stored). A List's name can be set or changed anytime except when Done. *(PRD FR-6)*

FR5 (CAP-5): **Item management & off-trip list organization.** Any Member can add, update, and remove Items (name, Quantity, optional note) on an Open List, and **move** an Item to another (existing or new) List. Quantity > 0; an exact name+note duplicate on the same List is rejected; off-trip an Item stays **Open** — check/uncheck (→ Done/Open) and **Postpone** occur only within a Trip (FR9/FR10), so **Done** always carries a Store/Trip context; a Done List accepts no Item commands. *(PRD FR-7, FR-8; CAP-5 revised 2026-08-22 — off-trip check/uncheck/postpone dropped in favor of move-to-list.)*

FR6 (CAP-6): **Fast item entry with autocomplete & attribute prefill.** As a Member types an Item name, the app autocompletes from the Household's own past Item names (prefix/fuzzy), Household-scoped and private, served lag-free from a local/cached lookup; selecting a suggestion prefills last-used attributes (unit, note, default Store), each overridable; a brand-new, never-seen name adds with no suggestion and no extra steps. *(PRD FR-27)*

FR7 (CAP-7): **Real-time live sync.** Changes to Lists, Items, and Trips propagate to all connected Members' devices within a few seconds under normal connectivity, with no manual refresh; the client automatically re-establishes the live connection after a drop and reconciles current state. *(PRD FR-10)*

FR8 (CAP-8): **Offline queue with bounded conflict surfacing.** Actions taken offline are captured locally, replayed in order on reconnect with a visible pending count; replay is idempotent (an ambiguous retry never double-applies); a queued command whose target aggregate advanced meanwhile is rejected and the Member gets an inline re-apply/discard choice; convergent/idempotent actions resolve silently. *(PRD FR-11, FR-26)*

FR9 (CAP-9): **Shopping trips: start, store-grouped view, routing & in-trip actions.** A Member can start a Trip from an Open List across one or more selected Stores (requires a linked List and ≥ 1 Store; moves the List to In-Trip; at most one Active Trip per List), see Items grouped by Store with a "Not yet assigned" section, assign or reroute Items between Stores (including a spontaneously added Store), and check/uncheck/postpone Items during the Trip (synced live). *(PRD FR-12, FR-13, FR-14)*

FR10 (CAP-10): **Complete a trip with leftover review.** Trip completion is a guided, user-triggered dialog that confirms "finished?", then for each remaining open Item offers TRANSFER (to an existing or new List) or DISCARD; TRANSFER places Items automatically; on completion the List moves to Done and becomes immutable; the system never force-completes a Trip. *(PRD FR-16)*

FR11 (CAP-11): **Print & share the list.** A Member can print the current List via the native OS print dialog in the grouped-by-store layout (matching the on-screen layout, including "Not yet assigned"), and secondarily share it as an in-memory PDF; no PDF is persisted to device storage in either path. *(PRD FR-17, FR-18)*

FR12 (CAP-12): **Content-free notifications.** Push pings carry no Household data and only prompt the app to fetch real state from the Household's own server, on a fixed MVP default set: invitation received (always on), list changed (debounced to at most one ping per List per ~5-minute interval), and trip started/completed; delivery goes through a swappable push adapter (MVP: FCM/APNs). *(PRD FR-19)*

FR13 (CAP-13): **German-first, i18n-ready UI with locale selection.** The MVP ships a complete German UI with no untranslated/placeholder strings in primary flows and no hard-coded user-facing strings (all resolve through a localization layer keyed by Locale); currency/date/number/quantity formatting follows the active Locale (`de-DE` → "1,09 €"); a Member can view and change their own Locale in-app, updating language and formatting without reinstall; Locale is per-user, defaulting from the device and falling back to `de-DE`. *(PRD FR-24, FR-25)*

FR14 (CAP-14): **Data-subject rights: erasure & export.** A person's personal data can be located, exported (in a portable form), and erased on request; erasure destroys the Identity-ACL mapping, scrubs read models and device/offline caches, and deletes the Keycloak account, leaving only unlinkable pseudonyms in the event log with no personal data recoverable; erasure, export, and retention each have explicit tests. *(CLAUDE.md §5; PRD data-protection)*

### NonFunctional Requirements

*From SPEC Constraints and cross-cutting quality attributes.*

NFR1: **No US-cloud dependency, ever.** Self-hosted on a dedicated German/EU server with no US hyperscaler in the data path (bends hosting, provider, push, OCR, geodata choices).

NFR2: **DSGVO by design.** Data minimization; **events carry no personal data** — a Household-scoped pseudonymous `MemberId` only, never `keycloakUserId`, email, or name (display name/email read live from Keycloak/JWT, never persisted); erasure is by de-linking without rewriting history.

NFR3: **Content-free push through a swappable adapter.** Push payloads carry only a wake-and-fetch signal; routing them through FCM/APNs is an accepted MVP carve-out to NFR1; push is a swappable port (self-hosted UnifiedPush is a public-phase option).

NFR4: **Identity delegated to Keycloak (self-hosted).** SGART stores no credentials and references people only by an opaque id taken from the JWT `sub` — never from a request body or path.

NFR5: **Fixed architecture paradigm.** DDD + CQRS + Event Sourcing, hexagonal, in a modular monolith; state changes only via command → aggregate → event under optimistic concurrency; read models are projection-only. Binding contract = `ARCHITECTURE-SPINE.md`.

NFR6: **Clean Code + binding ubiquitous language.** No abbreviations; names reflect purpose; one term, one meaning (`glossary.md` binding); TDD with domain-first fast unit tests and synthetic data only. Full rules in `CLAUDE.md`.

NFR7: **Mobile-first.** iOS + Android via Flutter/BLoC; web exists only as an invite-acceptance fallback in v1, never as a daily client.

NFR8: **Curated cohort; solo is first-class.** MVP onboards a friends-&-family cohort only, with no public self-signup; a household of one is fully supported alongside multi-person households.

NFR9: **Sync reliability & eventual consistency.** Live changes appear within a few seconds under normal connectivity; the client auto-reconnects and reconciles after a drop; offline replay is idempotent (no double-apply); read models are eventually consistent and the UI must tolerate that.

NFR10: **Accessibility for a genuinely mixed cohort.** 48px minimum interactive targets; honor OS Dynamic Type / text scaling; high-contrast, calm, plain-language German; forgiving, reversible flows; primary actions never live only in an overflow menu. *(Concrete implementation captured as UX-DR5.)*

### Additional Requirements

*From the Architecture spine (AD-1…11, conventions, stack, setup). These constrain how stories are implemented.*

- **AR-Setup (greenfield — no external starter template):** Epic 1 scaffolds a **Spring Boot 4.1 modular monolith** with the context packages `collaboration` (core), `storereference` (supporting), `priceintelligence` (core, **reserved/empty for MVP**), `identity` (generic ACL), `shared` (Money, Quantity, ids, error model) — each hexagonal (domain/application/adapter.in/adapter.out); plus a **Flutter 3.44** app split by feature with **BLoC per screen**; plus **Docker Compose** wiring KurrentDB, PostgreSQL, and Keycloak. No commercial/open-source starter template is used.
- **AR1 (AD-1):** Every state change is a command handled by an aggregate that emits domain events; domain code imports no framework/infrastructure/transport type; infrastructure is reached only through domain-owned ports.
- **AR2 (AD-2, AD-3):** Four contexts are packages in one deployable; a context touches another only via its published application-layer port or an async domain event — never another context's domain or DB tables. `Household`, `ShoppingList`, `ShoppingTrip` live in the Collaboration context as distinct aggregates referencing each other by id only.
- **AR3 (AD-4):** Writes append events to **KurrentDB** under an expected-version check; **PostgreSQL** read models are built by projectors subscribed to the streams and are never written directly by command handlers; projections are eventually consistent.
- **AR4 (AD-5):** All events/read models reference a person only by a per-membership opaque `MemberId`; the **Identity ACL is the sole minter** (on invite acceptance) and owns the sole mapping `{householdId, memberId → keycloakUserId}`, resolving `(keycloakUserId, householdId) → memberId` per request; a person in two households has two unrelated `MemberId`s.
- **AR5 (AD-6):** Never persist display name/email — resolved live from Keycloak/JWT for display only. `MemberInvited` carries `inviteId` + `HMAC(stable per-deployment secret, normalizedEmail)` (for the no-duplicate-pending-invite check); the raw email lives only in a mutable side-store, purged on accept/expiry/erasure; delivery is Keycloak's.
- **AR6 (AD-7):** Erasure = destroy the Identity-ACL mapping rows + scrub PostgreSQL read models + purge device/offline caches + delete the Keycloak account; the event log is never rewritten (orphaned `MemberId`s are unlinkable → anonymized). Crypto-shredding for PII-in-events is reserved (Post-MVP only).
- **AR7 (AD-8):** Each queued command carries the target aggregate root's stream version + a client `commandId`; on replay a stale expected-version is rejected → coarse keep/discard (FR8); the `commandId` makes replay idempotent.
- **AR8 (AD-9):** `Money` = integer minor units (cents) + ISO currency code (MVP EUR-only, explicit; no floating-point arithmetic); `Quantity` = amount + `Unit` from a controlled, extensible vocabulary (piece, gram, kilogram, millilitre, litre, pack, …); free-text units are rejected.
- **AR9 (AD-10):** `Item` is an entity inside the `ShoppingList` aggregate; `Store` is an entity inside `Household`; only the root accepts commands; cross-aggregate effects go through **process managers** issuing idempotent new commands (deterministic `commandId` derived from the triggering event id).
- **AR10 (AD-11 + conventions):** Ubiquitous language enforced; `HouseholdRole {Admin, Participant}` (never bare "Member" for the role); domain events past-tense PascalCase with stream keys `household-/list-/trip-{id}`; commands imperative carrying `basedOnVersion` + `commandId` when client-originated; error shape `{code, message, details}` (`code` → localized client-side; `message` never shown to users); REST under `/api/v1`; SSE per-household stream for live sync; no pagination in MVP; timestamps stored UTC ISO-8601, formatted client-side per Locale.
- **AR-Stack:** Java 25, Spring Boot 4.1.0, Flutter 3.44 / Dart, Keycloak 26.7.0, KurrentDB 25.x (pin current LTS at build), PostgreSQL 18.6, SSE transport, BLoC client state; Docker Compose on a dedicated German/EU server.

### UX Design Requirements

*From DESIGN.md (visual system) and EXPERIENCE.md (IA, screens, journeys). Each is specific enough to become a story with testable ACs.*

**Design system / tokens**
UX-DR1: **Color system as tokens** — brand + semantic roles (primary/success/warning/error, background/surface, text primary/secondary, border), a warm-neutral ramp, and **theme-following chrome** (light chrome in light mode, dark chrome in dark), with explicit `on.*` colors per fill; implement **both light and dark themes**; one-hue-one-meaning; **no gradients**.
UX-DR2: **Typography** — **Inter** throughout (bundled asset preferred for offline, or `google_fonts`), the weight roles and type scale from DESIGN §2, and **tabular figures** for quantities/counts (prices post-MVP); de-DE number/date formatting via `intl`.
UX-DR3: **Shape / elevation / density tokens** — "System 3": radius (card 14 / button 12 / control 6 / pill 999), flat-forward elevation (hairline + one faint shadow tier, no Material ladder), 4px spacing base, comfortable-compact density.
UX-DR4: **Button component** — **text-only (no icons)**; labels **wrap to two lines at large text sizes, never clip/shrink**; variants: primary (filled baltic), secondary (outlined), tonal (quiet/terminal, non-sticky).

**Accessibility**
UX-DR5: **Accessibility overlay** — 48px minimum interactive targets with padded hit areas for small glyphs; honor OS Dynamic Type (`MediaQuery.textScaler`) with reflow; high contrast; primary actions never overflow-only; forgiving/reversible flows; plain-language German copy. *(An in-app **"Größere Darstellung" larger-display preference is deferred to the first fast-follow** — and may be dropped entirely; the MVP relies on OS Dynamic Type for larger text.)*

**App shell / navigation**
UX-DR6: **App shell** — 3-tab labelled bottom nav (**Listen / Einkauf / Profil**, never icon-only); persistent header with **household switcher** (left, current household always visible) + **sync/offline status** (right); "Haushalt verwalten" (members/invites/roles + stores) lives in the switcher sheet; **Profil is purely personal**.

**Key screens**
UX-DR7: **Active-trip screen** — list-is-hero; store-grouped with a "Noch nicht zugeordnet" section; quiet **non-sticky tonal "Einkauf abschließen"** at the list end + mirrored in ⋯; live check-off; offline/sync chip; conflict badge; **no sum/prices**.
UX-DR8: **List-detail screen** — persistent **fast-add field** with autocomplete + attribute-prefill + "add as new"; peer actions **"Einkauf starten"** and **"Drucken / Teilen"**; items showing name/quantity/note + store chip (and "+ Geschäft" for unassigned); ⋯ menu (rename / print+share / delete).
UX-DR9: **Household switcher sheet** — bottom sheet from the header chip; active household unmistakable ("Aktiv"); one-tap switch; after switch, header **and** content change with a brief confirmation; hosts "Haushalt verwalten" + "Neuen Haushalt erstellen".
UX-DR10: **First-run / onboarding wizard** — gentle one-step-at-a-time flow (create → name with "change later" reassurance → add stores with advisory chain suggestion, skippable → invite, optional) with a progress bar, plain German, privacy stated up front, and skip options where the spec allows.
UX-DR11: **Listen overview screen** — multiple Open Lists with an **"Erledigt" filter** for Done lists (read-only archive); "+ Neue Liste".
UX-DR12: **Conflict-resolution UI (offline replay)** — a **non-blocking reconnect summary banner** („N Änderungen konnten nicht übernommen werden") **and** an inline „Konflikt" badge on affected items, both opening the **same resolution sheet**; **coarse, per-command keep-mine / discard — no field-level merge** (CAP-8 / AD-8); each conflict shows *your change ↔ current value (by whom)*, with the other member's name resolved live (AD-6); convergent/idempotent actions (same article added twice, same item checked, simultaneous *online* edits) resolve **silently** and never surface here; pink = conflict (distinct from amber offline/pending); plain, non-blaming German copy.

**Cross-cutting UI states**
UX-DR13: **State components (reused across screens)** — live-sync indicator, offline pending-count, and **empty states for every primary surface** (no lists / no stores / no items / no households resolved by first-run — *E7*), each with a helpful next-step CTA, and **degrading to quiet for solo** (no invite-nags; solo is not a lesser mode). *(Conflict handling is its own screen — UX-DR12.)*

**Supporting screens (full MVP coverage)**
UX-DR14: **Profil screen** — personal-only: „Sprache & Region" (Locale), **„Größere Darstellung"** toggle, notifications-info (fixed MVP defaults), „Abmelden". No household management here. *(CAP-13/12)*
UX-DR15: **Meine Daten — export & erasure** — data export (portable) and account/data erasure as separate, plain-language actions; erasure confirm is **clearly irreversible** with a Klartext list of effects (danger primary + quiet cancel) and enforces the **last-Admin guard** (block until role handed over); copy reflects de-linking/anonymized traces (AD-7). *(CAP-14)*
UX-DR16: **Haushalt verwalten** — members list with **role labels** (Admin/Mitglied) and the **last-Admin invariant** stated; invites with pending state and **one pending invite per email**; new members join as „Mitglied"; **store management** where add reuses the advisory chain suggestion and **removal archives** (hidden from selection, history preserved — never hard delete). *(CAP-2/3)*
UX-DR17: **Trip start** — multi-select the Stores for this trip (**≥ 1 required**), with per-store item counts; unassigned items surface under „Noch nicht zugeordnet" in the trip. *(CAP-9)*
UX-DR18: **Trip completion dialog** — guided, **multi-step**: a positive „erledigt"-summary, then per remaining open Item a **Übernehmen / Verwerfen** choice with a selectable transfer target (existing/new List); „Doch noch weiter einkaufen" keeps the trip open (**never force-completed**); on finish the List is **Done & immutable** and transferred items sit on a fresh List. *(CAP-10)*
UX-DR19: **Print preview & Share** — a Drucken / Als PDF teilen entry; **native OS print** in the **grouped-by-store** layout (matching on-screen, incl. „Noch nicht zugeordnet", with tick boxes); secondary **in-memory PDF** share; explicit **no-file-saved** guarantee in both paths. *(CAP-11)*
UX-DR20: **Invite acceptance (recipient)** — the deep-link/web-fallback path: who invited you + which household, **join as „Mitglied"**, privacy stated, and a calm decline. *(CAP-2)*
UX-DR21: **List-row status label component** — the dense, tinted, second-line status label reused for member roles, store chains, and similar row attributes (DESIGN §4b); names keep full width; no „Du" self-marker.
UX-DR22: **Store picker component** — a reusable store selector used for item assignment, trip start, and in-trip reroute; lists existing household stores and offers **„+ Neues Geschäft"** to create one **inline** (free-form + advisory chain suggestion, CAP-3), added to the household and selected on the spot — so a member never has to leave to „Haushalt verwalten" first. *(Realized across Stories 1.8, 2.6, 3.1, 3.2.)*

### Deferred (out of MVP — not decomposed into stories)

Receipts & OCR; price intelligence (Product, Price Observation, pinning, price-based routing); analytics dashboard; configurable notification settings & receipt-scanned notifications; list duplicate/template (fast-follow); geolocation store discovery; additional UI languages & multi-market StoreChain data; full web client; crypto-shredding implementation; **Trip pause/resume (permanently dropped, not deferred)**. The `priceintelligence` module is reserved (empty) in the scaffold but nothing is built for it.

### FR Coverage Map

| FR | Capability | Epic |
|---|---|---|
| FR1 | Households & first-run routing | Epic 1 |
| FR2 | Invite & membership & roles | Epic 4 |
| FR3 | Store management + chain match | Epic 1 |
| FR4 | Multiple lists (auto-naming) | Epic 2 |
| FR5 | Item management & move-to-list | Epic 2 |
| FR6 | Fast entry / autocomplete | Epic 2 |
| FR7 | Real-time live sync | Epic 4 |
| FR8 | Offline queue & conflict surfacing | Epic 5 |
| FR9 | Shopping trips (grouped, routing) | Epic 3 |
| FR10 | Complete trip w/ leftover review | Epic 3 |
| FR11 | Print & share | Epic 3 |
| FR12 | Content-free notifications | Epic 4 |
| FR13 | German-first / locale | Epic 1 |
| FR14 | Erasure & export | Epic 6 |

*All 14 FRs mapped. NFRs and ARs are foundational and realized primarily in Epic 1 (scaffold,
paradigm, identity, localization layer, design system), then upheld across every epic per the
Architecture spine and CLAUDE.md.*

## Epic List

### Epic 1: Foundation, Identity & Household Setup
A person can install SGART, sign in via Keycloak, and **create, select, or switch between
households** — choosing their language and adding the stores they shop at — ending with a
working household (solo fully supported) ready for lists. This epic also stands up the
greenfield foundation it all rests on: the **modular-monolith backend** (Collaboration /
Store Reference / Identity ACL / shared kernel; `priceintelligence` reserved-empty), the
**Flutter/BLoC client**, **Docker Compose** (Keycloak, KurrentDB, PostgreSQL), the
**design system** (color/type/shape tokens + shared components, incl. the text-only button,
accessibility overlay, and list-row status label), and the **localization layer** (German-first,
`de-DE` formatting, no hard-coded strings) with in-app Locale selection. Store removal archives.
**FRs covered:** FR1, FR3, FR13.
*(Realizes AR-Setup, AR1–AR5, AR8–AR10, AR-Stack; NFR1–NFR8, NFR10; UX-DR1–UX-DR6, UX-DR9, UX-DR10, UX-DR14, store part of UX-DR16.)*

### Epic 2: Shared Lists & Fast Capture
A household can keep **several open lists at once** (optional names, auto „Liste N") and fill
them fast — adding items with quantity and optional note, and moving items between lists while
planning — with **autocomplete and attribute prefill** from the household's own history so
capture beats a scribbled note. *(Checking off and postponing happen within a trip — Epic 3.)* Delivers the Listen overview (with the „Erledigt" filter) and the list
detail screen.
**FRs covered:** FR4, FR5, FR6.
*(UX-DR8, UX-DR11, UX-DR21.)*

### Epic 3: Shopping Trips & Print
A member can **shop a trip** against a list across one or more selected stores — items grouped
by store with a „Noch nicht zugeordnet" section, assigning / rerouting and checking off as they
go — and finish with a **guided leftover review** that transfers or discards open items (never
force-completed; the list becomes Done & immutable). The same list can be **printed or shared as
an in-memory PDF** in the grouped-by-store layout for analog shopping, with no file saved.
**FRs covered:** FR9, FR10, FR11.
*(UX-DR7, UX-DR17, UX-DR18, UX-DR19.)*

### Epic 4: Live Collaboration & Membership
Households become **multi-person**: any member **invites** others by email (one pending invite
per address; email never stored in events) and they join as **Participants** under
`HouseholdRole {Admin, Participant}` with the **last-Admin protected**; everyone's lists and
trips **update in real time** across devices via SSE without a manual refresh; and **content-free
push pings** keep members coordinated without carrying any household data.
**🚩 Beta-ready milestone** — this is the first point the product's actual differentiator (a shared,
live household truth) is fully realized; Epics 1–3 are the solo-capable substrate beneath it.
**FRs covered:** FR2, FR7, FR12.
*(UX-DR16 members part, UX-DR20, live-sync part of UX-DR13.)*

### Epic 5: Offline Resilience & Conflict
Members can keep adding and checking off **with no signal** — actions queue locally with a
visible **pending count** and replay **idempotently** on reconnect; when an offline change no
longer fits because someone changed the same thing first, it surfaces as a **calm, coarse
keep-mine / discard** choice (a non-blocking reconnect banner + inline „Konflikt" badge → one
resolution sheet) — never a silent overwrite, never a field-merge; convergent actions resolve
silently.
**FRs covered:** FR8.
*(UX-DR12, offline part of UX-DR13; realizes AR6/AR7 concurrency = AD-8.)*

### Epic 6: Data Protection — Export & Erasure
A person can **export a portable copy** of their data and **fully erase** themselves — erasure
de-links their pseudonymous `MemberId`, scrubs PostgreSQL read models, purges device/offline
caches, and deletes the Keycloak account, leaving only **unlinkable** traces in the immutable
event log (guarded by the last-Admin invariant); export, erasure, and retention each have
**explicit tests**.
**FRs covered:** FR14.
*(UX-DR15; realizes AD-5/AD-6/AD-7, NFR2.)*

---

## Epic 1: Foundation, Identity & Household Setup

Stand up the greenfield foundation and deliver a working, solo-capable household: sign in,
create/select/switch households, choose language, and add stores. *(FR1, FR3, FR13.)*

### Story 1.1: Project scaffold & local infrastructure

As the developer,
I want the greenfield backend, client, and local infrastructure scaffolded to the architecture,
So that every later story has a consistent, runnable substrate.

**Acceptance Criteria:**

**Given** an empty repository
**When** the scaffold is created
**Then** a Spring Boot 4.1 (Java 25) modular monolith exists with one package per bounded context — `collaboration`, `storereference`, `identity`, `shared`, and a reserved-empty `priceintelligence` — each split into `domain / application / adapter.in / adapter.out`
**And** the domain layer has no compile-time dependency on any framework, persistence, or transport type (enforced by an architecture test, AD-1)
**And** a Flutter 3.44 app is scaffolded feature-first with BLoC, and a `docker-compose` stands up Keycloak 26.7, KurrentDB, and PostgreSQL 18.6.

**Given** the scaffold
**When** the test suite runs in CI
**Then** a placeholder domain unit test and an architecture/dependency test pass, and a red build blocks merge (NFR6).

### Story 1.2: Design system & theming foundation

As a user,
I want the app to render in one consistent, legible visual system in light and dark,
So that every screen feels calm, on-brand, and readable.

**Acceptance Criteria:**

**Given** the design tokens from `DESIGN.md`
**When** the theme is applied
**Then** color (brand + semantic + warm-neutral ramp, theme-following chrome, on-colors), typography (Inter, weights, tabular figures), and shape/elevation/density (System 3) are available as tokens and used by shared components (UX-DR1–UX-DR3).

**Given** the shared component set
**When** a screen uses a primary action, a status label, or a tappable row
**Then** the **text-only button** (labels wrap, never shrink, UX-DR4), the **list-row status label** (dense tinted second line, UX-DR21), and a **48px minimum target** are honored, and the app honors OS Dynamic Type / text scaling (UX-DR5, NFR10).

**Given** an explicit theme choice or the OS setting
**When** the app renders
**Then** light and dark themes both resolve correctly with legible contrast.

### Story 1.3: Localization layer & de-DE formatting

As a user,
I want all text and formatting to come from a locale-driven layer,
So that the app is fully German today and translatable later without code changes.

**Acceptance Criteria:**

**Given** the localization layer
**When** any user-facing string is displayed
**Then** it resolves through a key-based localization layer (no hard-coded user-facing strings), defaulting to `de-DE` (FR13, AR10).

**Given** a currency, date, number, or quantity value
**When** it is displayed under `de-DE`
**Then** it is formatted per locale (e.g. „1,09 €", comma decimal), and no locale-formatted value is ever persisted (values stored UTC/canonical, formatted client-side).

**Given** a domain/application error
**When** it reaches the client
**Then** it arrives as `{code, message, details}` and the client shows localized copy keyed by `code` (never the raw `message`).

### Story 1.4: Sign in with Keycloak & resolve membership identity

As a person,
I want to sign in through the household's Keycloak,
So that I'm authenticated without SGART ever holding my credentials.

**Acceptance Criteria:**

**Given** the Keycloak-backed login
**When** a person authenticates
**Then** the client obtains a JWT and SGART stores no credentials; the backend validates the JWT at `adapter.in` and takes the opaque user id only from the token `sub` — never from a request body or path (NFR4, AR10).

**Given** an authenticated request within a household context
**When** it reaches the application layer
**Then** the Identity ACL resolves `(keycloakUserId, householdId) → MemberId` before the domain is touched, and display name/email are read live from the JWT/Keycloak and never persisted (AR4, AR5, AD-6).

**Given** an authenticated user
**When** they sign out
**Then** the session/token is cleared on the device.

### Story 1.5: Command & concurrency envelope

As the developer,
I want one command/event/concurrency contract that every later command reuses,
So that live sync and the offline queue are additive layers, never a rewrite of existing handlers.

**Acceptance Criteria:**

**Given** the write model
**When** any state-changing command is defined
**Then** it flows **command → aggregate → domain event(s)** appended to KurrentDB under an **expected-version** (optimistic-concurrency) check, and PostgreSQL read models are **projection-only** — never written by command handlers (AD-1, AD-4).

**Given** a client-originated command
**When** it is issued
**Then** it carries a client **`commandId`** and a **`basedOnVersion`** (the target aggregate root's stream version), so replay is idempotent and a stale write is rejected (AD-8) — **every command-emitting story from here on reuses this envelope** (so Epic 5's offline queue is purely client-side + a conflict UI, touching no existing handler).

**Given** a process-manager-issued command
**When** it is issued
**Then** its `commandId` is **derived deterministically** from the triggering event id, so re-processing on subscription/replay never double-applies (AD-10).

**Given** the envelope
**When** the test suite runs
**Then** command→event emission and the concurrency/idempotency behavior are covered by fast unit tests (NFR6).

### Story 1.6: Create a household & first-run routing

As an authenticated person,
I want to create a household or be routed to the right place on launch,
So that I have a private container for my lists and stores.

**Acceptance Criteria:**

**Given** an authenticated person with zero households
**When** the app launches
**Then** they are shown the create-household / await-invite choice; creating one names it and makes them a Member with `HouseholdRole = Admin` (FR1); the Identity ACL mints their `MemberId` and the `Household` aggregate emits `HouseholdCreated` + `MemberJoined` carrying that same `MemberId` (AR4, AD-5).

**Given** an authenticated person
**When** the app launches with exactly one household → they go straight in; with several → they see a selection screen (FR1).

**Given** a person who already belongs to ≥ 1 household
**When** they choose to create another
**Then** creation is allowed and a second, unrelated `MemberId` is minted for them in the new household.

### Story 1.7: Switch, select & rename households

As a person in more than one household,
I want an always-visible switcher,
So that I always know and can change which household I'm acting in.

**Acceptance Criteria:**

**Given** a person in ≥ 1 household
**When** any main screen is shown
**Then** the persistent header shows the **current household name**, and tapping it opens a switcher listing their households with the active one clearly marked (UX-DR9).

**Given** the switcher
**When** they pick another household
**Then** the header and content switch to it with a brief confirmation, so it's unmistakable which household is active.

**Given** an Admin
**When** they rename the household
**Then** the name updates everywhere it is shown. *(Household deletion and role governance are covered in Epic 4.)*

### Story 1.8: Manage stores with client-side chain matching

As a member,
I want to add and remove the household's stores by name with a chain suggestion,
So that lists and trips can later be grouped by store.

**Acceptance Criteria:**

**Given** the store list
**When** a member adds a store by free-form name
**Then** the name must be unique within the household (duplicate rejected), and `Store` is created as an entity inside the `Household` aggregate (AR9).

**Given** a member typing a store name
**When** the text matches the cached `StoreChain` reference list
**Then** an advisory chain is suggested inline and can be accepted / changed / cleared (never forced or decided server-side), and the reference list works offline after its first cached load (FR3).

**Given** a store referenced by past trips/assignments
**When** a member removes it
**Then** it is **archived** (hidden from future selection) without deleting historical trips or assignments (FR3).

**Given** any store picker (item assignment, trip start, in-trip reroute)
**When** a member adds a store inline from it
**Then** it uses these same creation rules (unique name, advisory chain suggestion) and is added to the household — store creation is **not** limited to „Haushalt verwalten".

**Given** an item on an Open list assigned to a store that is later **archived** *(E6)*
**When** the list or a trip is viewed
**Then** the item **falls back to „Noch nicht zugeordnet"** (the archived store is no longer offered), so nothing points at a hidden store; historical Done trips keep their record.

### Story 1.9: Guided onboarding for a new household

As a non-expert person setting up a household,
I want a gentle step-by-step setup,
So that I can get started alone without fear of breaking anything.

**Acceptance Criteria:**

**Given** a person who chose to create a household
**When** onboarding runs
**Then** it walks create → name (with a „you can change this later" reassurance) → add stores (advisory chain suggestion, skippable) → optional invite entry, with a visible progress indicator and plain-language German (UX-DR10).

**Given** any optional step
**When** the person skips it
**Then** they still land in a fully usable app (solo works; stores/invite can be added later).

**Given** onboarding
**When** it is shown
**Then** privacy is stated up front and no account/marketing pressure is applied.

### Story 1.10: View and change my locale

As a member,
I want to view and change my language & region,
So that the app speaks my language and formats values the way I expect.

**Acceptance Criteria:**

**Given** the Profil screen
**When** a member opens „Sprache & Region"
**Then** they can view and change their own `Locale`; it is per-user, defaults from the device, and falls back to `de-DE` (FR13).

**Given** a locale change
**When** it is applied
**Then** language and currency/date/number/quantity formatting update without a reinstall.

### Story 1.11: Personal profile screen

As a member,
I want a personal profile screen,
So that I can control my own display settings and account.

**Acceptance Criteria:**

**Given** the Profil tab
**When** it is shown
**Then** it is **personal-only** (no household management) and offers „Sprache & Region", a notifications info section (fixed MVP defaults), and „Abmelden" (UX-DR14).

**Given** MVP accessibility
**When** any screen renders
**Then** the app honors the OS text-scaling / Dynamic Type setting and maintains 48px targets (NFR10). *(An in-app „Größere Darstellung" larger-display toggle is **deferred to the first fast-follow** — and may be dropped entirely — so the MVP relies on OS Dynamic Type for larger text.)*

---

## Epic 2: Shared Lists & Fast Capture

Keep several open lists at once and fill them fast — items with quantity/note, moved between
lists while planning, and autocomplete with attribute prefill so capture beats a scribbled
note. Check-off & postpone are trip-time (Epic 3). *(FR4, FR5, FR6.)*

### Story 2.1: Create and name multiple open lists

As a member,
I want to keep several open lists at once, named or auto-named,
So that parallel shops (e.g. „Wocheneinkauf" and „Getränke") don't collide.

**Acceptance Criteria:**

**Given** a household
**When** a member creates a list
**Then** it is created as **Open** with an optional name, and more than one Open list may exist at the same time (FR4), via command → `ListCreated` event (AD-4).

**Given** an unnamed Open list
**When** it is displayed
**Then** it shows „Liste {N}" by creation order among Open/In-Trip lists — **derived, never stored** (FR4).

**Given** an Open or In-Trip list
**When** a member sets or changes its name
**Then** the name updates; a **Done** list's name cannot be changed.

### Story 2.2: Listen overview with Offen/Erledigt filter

As a member,
I want an overview of my lists,
So that I can jump into the right one and glance at finished ones.

**Acceptance Criteria:**

**Given** the Listen tab
**When** it is shown
**Then** open lists appear with item counts / progress and a „+ Neue Liste" action, and a segmented **„Offen" / „Erledigt"** filter switches between open lists and the Done archive (UX-DR11).

**Given** the „Erledigt" filter
**When** it is selected
**Then** Done lists are listed as a **read-only archive** (no item commands accepted).

### Story 2.3: Add, edit, and remove items

As a member,
I want to add items with quantity and an optional note,
So that the list captures exactly what to buy.

**Acceptance Criteria:**

**Given** an Open list
**When** a member adds an item with name, Quantity, and optional note
**Then** Quantity must be **> 0** and is a `Quantity` value object (amount + Unit from the controlled vocabulary; free-text units rejected — AD-9), and the `Item` is created **inside the `ShoppingList` aggregate** (AD-10) via command → `ItemAdded`.

**Given** a list that already has an item with the same name+note
**When** a member adds an exact duplicate
**Then** it is **rejected** (items are keyed by name+note); „Milch (Bio)" and „Milch" coexist (FR5).

**Given** an existing item
**When** a member updates or removes it
**Then** the change applies via command → event; a **Done** list accepts no item commands (FR5).

### Story 2.4: Move an item to another list

As a member,
I want to move an item to another (existing or new) list while planning,
So that I can reorganize my parallel lists without any buying/trip context.

**Acceptance Criteria:**

**Given** an item on an Open list
**When** a member moves it to another **existing** Open list
**Then** it is added to the target and removed from the source, carried by a process manager issuing an **idempotent** add on the target (AD-10), and the item stays **Open** (no status change).

**Given** a member moving an item
**When** they choose a **new** list as the target
**Then** the new list is created first, then the item is moved to it.

**Given** a move whose target list already has an item with the **same name+note** *(E2)*
**When** the move is attempted
**Then** it is **rejected / deduped** (the same name+note uniqueness rule as adding, FR5) rather than creating a duplicate on the target.

**Given** the list of move targets *(E3)*
**When** a member picks one
**Then** only **Open** lists are offered — a **Done** or **In-Trip** list is never a move target.

**Given** an Open list off-trip
**When** a member views item actions
**Then** there is **no check/uncheck and no postpone** — buying (→ Done) and deferral (Postpone) happen only within a trip (CAP-9/CAP-10), so off-trip an item is always Open.

### Story 2.5: Fast item entry with autocomplete & attribute prefill

As a member,
I want the app to suggest items I've bought before and prefill their details,
So that adding a known article takes minimal taps.

**Acceptance Criteria:**

**Given** a member typing an item name
**When** the input changes
**Then** it suggests matching **previously-used item names** in the household (prefix/fuzzy), **household-scoped and private**, served lag-free from a local/cached lookup (FR6), backed by a read model over the household's own item history (**not** the Post-MVP Product catalog).

**Given** a suggestion
**When** it is selected
**Then** last-used attributes (unit, note, default Store) are prefilled, each **overridable** before adding.

**Given** a brand-new, never-seen name
**When** it is entered
**Then** it adds with no suggestion and **no extra steps**.

### Story 2.6: Assign an item to a store (with inline store creation)

As a member,
I want to assign an item to a store while planning — and add a new store on the fly,
So that the list groups by store even before a trip, without leaving to manage stores first.

**Acceptance Criteria:**

**Given** an item on an Open list
**When** a member opens its store picker
**Then** they can assign it to any existing household store; the item then shows that store, and unassigned items show „+ Geschäft".

**Given** the store picker
**When** the needed store doesn't exist yet
**Then** the member can **add a new store inline** (free-form name + advisory chain suggestion, CAP-3), created in the household under the same rules as Story 1.8 and **immediately assigned** — without leaving for „Haushalt verwalten".

**Given** an assigned item
**When** it appears in a trip's grouped view or the print layout
**Then** it is grouped under its assigned store (the assignment carries into the trip).

---

## Epic 3: Shopping Trips & Print

Shop a trip across stores — grouped by store, assign/reroute, check off, postpone — finish with
a guided leftover review; and print/share the list for analog shopping. *(FR9, FR10, FR11.)*

### Story 3.1: Start a trip across selected stores

As a member,
I want to start a trip from a list across the stores I'll visit,
So that I can shop with the list organized for this run.

**Acceptance Criteria:**

**Given** an Open list
**When** a member starts a trip and selects one or more stores
**Then** a `ShoppingTrip` aggregate is created linked to the list across those stores (requires a linked list and **≥ 1 store**), the list moves to **In-Trip**, and the trip is **Active** (FR9), via command → `TripStarted` (AD-4).

**Given** a list that already has an Active trip
**When** a member tries to start another
**Then** it is prevented — **at most one Active Trip per list** (FR9).

**Given** the store selector when starting a trip
**When** a member needs a store that doesn't exist yet
**Then** they can **add a new store inline** (free-form name + advisory client-side chain suggestion, CAP-3), created in the household and immediately selectable — so a household with **no stores yet** can still start a trip by adding one here (resolves the zero-stores case).

### Story 3.2: Store-grouped trip view with assignment & rerouting

As a member,
I want items grouped by store with an unassigned section,
So that I pick up the right things at each store.

**Acceptance Criteria:**

**Given** an active trip
**When** it is viewed
**Then** items are grouped by their assigned `Store` with a „Noch nicht zugeordnet" section for unassigned items (FR9).

**Given** an item in the trip
**When** a member assigns it to a store in the trip or **reroutes** it to a different store in the trip
**Then** its `StoreAssignment` updates and it moves under that store; Reroute is distinct from Postpone (FR9).

**Given** a member during a trip
**When** they add a store spontaneously
**Then** that store becomes part of the trip and can receive assignments.

### Story 3.3: Check off, uncheck, and postpone during a trip

As a member,
I want to check items off and postpone what I can't get, in the aisle,
So that the list reflects what actually happened.

**Acceptance Criteria:**

**Given** an item during an active trip
**When** it is checked → its status becomes **Done**; when unchecked → **Open** — and the change syncs live (FR9). *(This is the only place items reach Done — a Done item always has a Store/Trip context.)*

**Given** an item during an active trip
**When** it is postponed
**Then** it targets an **existing Open list**, a **newly created list** (created first), or is **flagged Postponed in place**; a cross-list move is carried by a process manager issuing an idempotent add on the target (AD-10), and **only Open lists** are offered as targets *(E3)*.

### Story 3.4: Complete a trip with leftover review

As a member,
I want a guided completion that handles what I didn't buy,
So that nothing is lost and the trip closes cleanly.

**Acceptance Criteria:**

**Given** an active trip
**When** a member triggers completion
**Then** a guided dialog confirms „Fertig?" and, for each remaining open item, offers **TRANSFER** (to an existing or new list) or **DISCARD**; TRANSFER places items automatically via an idempotent process manager (AD-10) (FR10).

**Given** completion is confirmed
**When** it finishes
**Then** the list moves to **Done** and becomes **immutable**, and any transferred items already sit on the target/fresh list.

**Given** a member mid-completion
**When** they choose to keep shopping
**Then** the trip stays **Active** — the system **never force-completes** a trip (FR10).

**Given** a trip with **no remaining open items** (everything bought or handled) *(E4)*
**When** a member completes it
**Then** the leftover-review step is **skipped** — completion goes straight to a simple confirm & close.

**Given** the TRANSFER target choices *(E3)*
**When** they are offered
**Then** only **Open** lists are available (plus „new list") — a Done or In-Trip list is never a transfer target.

### Story 3.5: Print and share the list

As a member,
I want to print or share the list grouped by store,
So that I (or someone else) can shop analog.

**Acceptance Criteria:**

**Given** a list
**When** a member prints it
**Then** the **native OS print dialog** shows the **grouped-by-store** layout matching the on-screen grouping, including „Noch nicht zugeordnet" (FR11).

**Given** a list
**When** a member shares it as a PDF
**Then** an **in-memory PDF** is produced (e.g. to send via WhatsApp), and **no PDF is persisted to device storage** in either the print or share path (FR11).

---

## Epic 4: Live Collaboration & Membership

Make households multi-person: invite by email, roles & governance, real-time live sync, and
content-free notifications. **🚩 Beta-ready milestone** — the shared, live differentiator lands
here (Epics 1–3 are the solo-capable substrate). *(FR2, FR7, FR12.)*

### Story 4.1: Invite a person by email

As a member,
I want to invite someone by email,
So that we can share the household's lists.

**Acceptance Criteria:**

**Given** a household
**When** any member invites a person by email
**Then** an invite is created and `MemberInvited` carries `inviteId` + `HMAC(stable per-deployment secret, normalizedEmail)` — **never the raw email** (AD-6); the raw email lives only in a mutable side-store (purged on accept, expiry, or erasure); delivery is Keycloak's (FR2).

**Given** a pending invite to an email
**When** a second invite to the **same email in the same household** is attempted
**Then** it is **rejected** (no duplicate pending invite) — the check works via the stored HMAC.

**Given** an email that already belongs to a **current member** of the household *(E5)*
**When** a member tries to invite it
**Then** it is **rejected** (already a member) — no invite is created.

### Story 4.2: Accept an invite and join

As an invited person,
I want to open my invite link and join,
So that I become a member of the household.

**Acceptance Criteria:**

**Given** a personal invite link
**When** the invitee opens it
**Then** it opens the app via **deep link**, or a **web fallback** if the app isn't installed; the invitee authenticates via Keycloak and joins as `HouseholdRole = Participant` (FR2).

**Given** a successful join
**When** membership is created
**Then** the Identity ACL **mints the invitee's `MemberId`** and `MemberJoined` carries that id (AD-5), and the invite's raw-email side-store entry is **purged**.

**Given** an **expired** invite
**When** it is opened
**Then** it **cannot be redeemed**.

**Given** an invitee who is **already a member** of that household *(E5)*
**When** they open an invite link
**Then** it is a **no-op** — they are simply taken into the household, and **no duplicate membership** is created.

### Story 4.3: Membership roles & governance

As an Admin,
I want role-gated governance with safeguards,
So that the household stays controllable and never loses its last Admin.

**Acceptance Criteria:**

**Given** the two roles
**When** day-to-day work is done
**Then** **both** Admin and Participant can do all daily work (lists, trips, invites); roles gate **only** governance actions (FR2, AR10).

**Given** an Admin
**When** they remove another member, promote a member to Admin, or delete the household
**Then** the action is allowed; a **Participant** can remove **only themselves** (leave), and cannot perform governance.

**Given** the **last Admin**
**When** they attempt to leave, be removed, or be demoted
**Then** it is **prevented** (the at-least-one-Admin invariant, FR2).

**Given** an Admin deleting the household
**When** the deletion is confirmed (a clear, hard-to-mis-trigger confirmation)
**Then** the household and its Lists, Trips, Stores, and memberships are removed for **all** members — it disappears from every member's switcher; erasure of an **individual's** personal data remains a separate right (Epic 6).

### Story 4.4: Real-time live sync

As a member,
I want everyone's changes to appear live,
So that the list is correct for all of us without refreshing.

**Acceptance Criteria:**

**Given** members connected to the same household
**When** one changes a list, item, or trip
**Then** the change appears on other connected members' devices within a few seconds, with no manual refresh, via the **per-household SSE stream** (FR7, AR10).

**Given** a dropped connection
**When** connectivity returns
**Then** the client **automatically re-establishes** the SSE connection and **reconciles** current state (FR7).

### Story 4.5: Content-free notifications

As a member,
I want to be nudged about relevant changes without my data leaving the household,
So that we stay coordinated privately.

**Acceptance Criteria:**

**Given** household activity
**When** a notification fires
**Then** the push payload carries **no item/list/receipt content** — only a **wake-and-fetch** signal that prompts the app to fetch real state from the household's own server (FR12, NFR3).

**Given** the fixed MVP default set
**When** events occur
**Then** notifications are sent for: **invitation received** (always on), **list changed** (debounced to at most one ping per list per ~5-minute interval), and **trip started / completed**.

**Given** push delivery
**When** a ping is sent
**Then** it goes through a **swappable push adapter** (MVP: FCM/APNs), so the transport can be replaced later (e.g. UnifiedPush).

### Story 4.6: Invite-acceptance web fallback

As an invited person without the app installed,
I want the invite link to work in a browser,
So that I can join even before installing SGART.

**Acceptance Criteria:**

**Given** an invite link opened where the deep link can't resolve to the app
**When** the invitee follows it
**Then** a **minimal web fallback** page handles acceptance: they authenticate via Keycloak in the browser and join as **Participant** (FR2) — the app deep link and the web path land the **same** join outcome.

**Given** the invite flow
**When** it is configured
**Then** the **deep-link**, the **web-fallback redirect**, and the **Keycloak email/redirect** settings are wired so both entry points work.

**Given** the web fallback
**When** a person tries to use it for daily work
**Then** only **invite acceptance** is supported — web is a fallback, never a daily client in v1 (NFR7).

---

## Epic 5: Offline Resilience & Conflict

Keep working with no signal — queue actions, replay idempotently, and surface genuine conflicts
as a calm, coarse keep-mine / discard choice. *(FR8.)*

### Story 5.1: Offline queue with a visible pending count

As a member,
I want my actions to work with no signal and sync later,
So that a dead spot in the store doesn't stop me.

**Acceptance Criteria:**

**Given** a member offline
**When** they add / move / (in-trip) check-off an item
**Then** the action succeeds **locally** and is captured in an **ordered offline queue**, with a visible **pending count** (e.g. „Offline · 3") in the header (FR8).

**Given** connectivity returns
**When** the queue replays
**Then** commands replay **in order**, each carrying the target aggregate root's **stream version** (`basedOnVersion`) and a client **`commandId`** (AD-8), and the pending count decrements as each applies.

### Story 5.2: Idempotent replay & silent convergence

As a member,
I want reconnection to never double-apply or nag me about harmless overlaps,
So that syncing is trustworthy and quiet.

**Acceptance Criteria:**

**Given** an ambiguous retry (e.g. a response was lost)
**When** a queued command is replayed
**Then** the client `commandId` makes it **idempotent** — it never double-applies (FR8, AD-8).

**Given** convergent / idempotent actions (the same item added twice, the same item checked)
**When** they replay
**Then** they **resolve silently** with no prompt (FR8).

### Story 5.3: Conflict detection & coarse resolution

As a member,
I want a clear, simple choice when my offline change no longer fits,
So that nothing is silently overwritten and I stay in control.

**Acceptance Criteria:**

**Given** a queued command whose target aggregate advanced meanwhile
**When** it replays
**Then** the **stale expected-version** causes it to be **rejected** — never silently overwritten, never auto-merged (FR8, AD-8).

**Given** rejected commands on reconnect
**When** the app returns online
**Then** a **non-blocking summary banner** („N Änderungen konnten nicht übernommen werden") appears and affected items show an inline „Konflikt" badge; **both open the same resolution sheet** (UX-DR12).

**Given** the resolution sheet
**When** a member reviews a conflict
**Then** it shows **your change ↔ current value** (with the other member's name resolved live, AD-6) and offers a **coarse keep-mine / discard** choice — **no field-level merge** (FR8).

---

## Epic 6: Data Protection — Export & Erasure

Honor DSGVO data-subject rights: a person can export a portable copy and fully erase themselves
by de-linking, with retention enforced and privacy guarantees explicitly tested. *(FR14.)*

### Story 6.1: Export my data

As a person,
I want a portable copy of my data,
So that I can exercise my right to data portability.

**Acceptance Criteria:**

**Given** the „Meine Daten" screen
**When** a member requests an export
**Then** a **portable** copy of the data associated with them (their profile and their lists/items/trip participation) is produced in a portable, machine-readable form (FR14).

**Given** an export
**When** it is produced
**Then** it contains only data the person is entitled to, and reflects live display data (name/email) resolved at export time rather than persisted copies (AD-6).

### Story 6.2: Erase my account and data

As a person,
I want to fully erase myself,
So that I can exercise my right to erasure.

**Acceptance Criteria:**

**Given** a member confirming erasure
**When** it runs
**Then** it **destroys the Identity-ACL mapping** rows, **scrubs** PostgreSQL read models, **purges** device/offline caches, and **deletes the Keycloak account** (FR14, AD-7); the **immutable event log is never rewritten**, leaving only **unlinkable** pseudonyms (GDPR Recital 26).

**Given** a member who is the **last Admin** of a household
**When** they attempt erasure
**Then** it is **blocked** until they hand over the Admin role or delete the household (CAP-2 invariant).

**Given** erasure completes
**When** anyone later inspects the system
**Then** **no personal data is recoverable** and the person's `MemberId`(s) are orphaned and unlinkable.

### Story 6.3: Retention & privacy-guarantee tests

As the operator,
I want retention enforced and the privacy guarantees tested,
So that personal data isn't kept indefinitely and the guarantees can't silently regress.

**Acceptance Criteria:**

**Given** personal data with a defined retention
**When** the retention condition is met
**Then** it is enforced (e.g. an invite's raw email is purged on accept/expiry; device/offline caches are bounded) — no personal data is kept indefinitely (NFR2).

**Given** the privacy guarantees
**When** the test suite runs
**Then** **erasure, export, and retention each have explicit automated tests** using **synthetic data only** (CAP-14, NFR6), and a failing privacy test blocks merge.
