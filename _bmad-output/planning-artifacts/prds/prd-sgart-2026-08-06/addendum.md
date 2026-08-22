# SGART PRD — Addendum (Technical Depth Preserved for Architecture)

This addendum preserves the implementation-level material worked out in `docs/SGART.md` that does **not** belong in a capability-first PRD but is valuable input to the architecture / solution-design phase. Nothing here is a product commitment; it is prior design thinking. The authoritative capability requirements live in `prd.md`. Where this material is later formalized, `bmad-architecture` owns it.

Source of record: `docs/SGART.md` (fuller prose and tables). This is a distilled pointer + the decisions worth carrying forward, not a re-paste.

---

## A. Tech Stack & Hosting (decided in SGART.md)

- **Frontend:** Flutter (iOS + Android), BLoC state management. Domain events arrive via SSE and are mapped to BLoC events.
- **Backend:** Java + Spring Boot, DDD + CQRS (strict command/query separation).
- **Identity/Auth:** Keycloak (self-hosted, Docker) — user management, JWT issuance, email invites, future 2FA/social login without backend changes. Backend extracts `keycloakUserId` from the JWT; no user id in request bodies/paths.
- **Write model:** EventStoreDB — immutable event streams as source of truth.
- **Read model:** PostgreSQL — projections/read models for queries, list status, dashboards.
- **Real-time:** Server-Sent Events (SSE) stream backend events into Flutter.
- **Hosting:** Docker containers on a dedicated German server (e.g. Hetzner/IONOS). Explicit non-goal: no US cloud. Full GDPR posture.
- **OCR:** Strategy Pattern — start on-device (ML Kit On-Device), swappable to self-hosted / EU provider later.
- **Push:** content-free "ping" via FCM/APNs, then app pulls encrypted data from own server.

## B. Domain Model — Aggregates, Events, Invariants

Event-sourced aggregates (stream key → responsibility):

| Aggregate | Stream key | Responsibility |
|---|---|---|
| `Household` | `household-{id}` | Membership, roles, stores, notification settings |
| `ShoppingList` | `list-{id}` | Item consistency, list status |
| `ShoppingTrip` | `trip-{id}` | Routing, live per-store status, receipt linkage |
| `Receipt` *(Post-MVP)* | `receipt-{id}` | OCR workflow, confirmation, dedup |
| `Product` *(Post-MVP)* | `product-{householdId}-{id}` | Price history, store pinning |

**MVP aggregates:** `Household`, `ShoppingList`, `ShoppingTrip`. **Deferred:** `Receipt`, `Product`.

The full event catalog and invariants per aggregate are in `docs/SGART.md §4.2–4.6`. Key events by aggregate (abbreviated):

- **Household:** `HouseholdCreated`, `MemberInvited`, `MemberJoined`, `MemberLeft`, `MemberRoleChanged`, `InviteExpired`, `StoreAdded`, `StoreRemoved`, `NotificationSettingsUpdated`.
- **ShoppingList:** `ShoppingListCreated`, `ShoppingListRenamed`, `ItemAdded`, `ItemUpdated`, `ItemRemoved`, `ItemChecked`, `ItemUnchecked`, `ItemPostponed`, `ListDuplicated`, `ListArchivedAsTemplate`.
- **ShoppingTrip:** `TripStarted`, `StoreAddedToTrip`, `ItemRoutedToStore`, `ItemStoreOverridden`, `ItemReroutedOnTrip`, `ItemCheckedOnTrip`, `ItemUncheckedOnTrip`, `ItemPostponedOnTrip`, `OpenItemsReviewed`, `ReceiptScanInitiated`, `ReceiptLinkedToTrip`, `TripCompleted`. (`TripPaused`/`TripResumed` dropped — pause/resume was cut, see PRD FR-15.)
- **Receipt (Post-MVP):** `ReceiptScanInitiated`, `OcrCompleted`, `ReceiptParsed`, `ReceiptItemMatched`, `ReceiptItemUnmatched`, `ReceiptFingerprintRegistered`, `ReceiptMarkedAsDuplicate`, `ReceiptConfirmed`.
- **Product (Post-MVP):** `ProductDiscovered`, `ProductAliasAdded`, `PriceObserved`, `ProductPinnedToStore`, `ProductUnpinnedFromStore`.

**Load-bearing invariants** (map to PRD FRs — full list in source):
- At least one Admin always; unique store name per household; no duplicate pending invite per email; expired invite not redeemable; identity delegated to Keycloak.
- List: quantity > 0; items keyed by name+note; Done lists immutable; multiple Open lists allowed; "Liste {N}" is a projection, not stored.
- Trip: ≥1 store and a linked list; at most one Active trip per list (lifecycle Active→Done, no Paused state); completion is user-triggered only.
- Receipt (Post-MVP): strict OCR→parse→confirm ordering; immutable after confirm; fingerprint dedup prompts, never auto-rejects.
- Product (Post-MVP): unique name per household; `PriceObserved` always store-scoped; pinned to ≤1 store.

## C. Process Managers (cross-aggregate workflows)

| Trigger | Action |
|---|---|
| `ReceiptConfirmed` *(Post-MVP)* | `ObservePriceCommand` per matched Product |
| `ItemStoreOverridden {updatePinning:true}` *(Post-MVP)* | atomic `UnpinFromStore` + `PinToStore` on Product |
| `ItemPostponed {targetListId}` | `AddItemFromPostponedCommand` on target list |
| `ItemPostponedOnTrip` | add to existing Open list, else create list + add |
| `OpenItemsReviewed` | per TRANSFER: add to target list (create if none) |
| `StoreAddedToTrip {reason:SUGGESTED}` *(Post-MVP)* | based on Product price history |
| `TripCompleted` | ensure open items reviewed |

## D. Reference Data — StoreChain (not an aggregate)

Static/semi-static in PostgreSQL: `StoreChain { chainId, normalizedName, logoUrl? }`. Loaded by the Flutter client at start via `GET /api/v1/store-chains`, cached 24h. Fuzzy matching (e.g. Levenshtein) runs **client-side** as the user types; server receives only the user-confirmed `{ name, chainId? }`. Enables per-store and per-chain reporting later.

## D2. Localization / i18n (implementation notes — architecture owns final form)

Product requirement lives in PRD §4.8 (FR-24/FR-25). Carry-forward for architecture/UX:
- **Flutter:** externalize all strings (e.g. `flutter_localizations` + ARB/`intl`); no literals in widgets. Locale-aware formatting via `intl` (`NumberFormat.currency`, `DateFormat`) — German uses comma decimal + trailing "€" ("1,09 €").
- **Locale resolution:** device locale → user override (persisted) → fallback `de-DE`. Per-user, not per-Household.
- **Backend:** SGART is largely language-agnostic (data, not copy), but any user-facing text it originates (error `message` is log-only, but `code`→copy mapping happens client-side — keep it that way) and **Keycloak email templates** (invites, password reset) must be localizable per recipient locale.
- **StoreChain reference data is country/market-scoped** (see §D): the seed must be partitionable by market so a German household sees Edeka/Aldi and a Dutch one sees Albert Heijn/Jumbo. MVP seeds Germany only.
- Language ≠ market: a user's UI Locale and their household's StoreChain market are independent (PRD §4.8 note / Open Q §8.8).

## D3. Geolocation Store discovery (Post-MVP — PRD FR-28)

Requirement: nearby-branch lookup when adding a Store, without foreign services. Candidate stack:
- **OpenStreetMap POI data** via **self-hosted Overpass API** (query `shop=supermarket`/`convenience` + `brand=<Chain>` within a radius of device location) and/or **Nominatim** for geocoding. Data license **ODbL** (attribution + share-alike on derived DB) — check obligations before shipping.
- Self-hosting keeps location queries inside SGART's own boundary (consistent with no-US-cloud). Alternative EU-hosted OSM providers exist if self-hosting is too heavy, but must still be non-US.
- Explicitly rejected: Google Places / other US POI APIs (violates §5 Non-Goals).
- Open risks (PRD Open Q8.10): per-market OSM brand-tag coverage/quality, ODbL attribution/share-alike obligations, Overpass/Nominatim operational cost.

## E. API Contract (transport detail — architecture owns final form)

Full contract in `docs/SGART.md §9`. Shape summary:
- Prefix `/api/v1`; JWT from Keycloak; `keycloakUserId` from token (never in body/path).
- Uniform error body `{ code, message, details }` — `code` is the client-facing machine key; `message` is log-only.
- No pagination in MVP (full result sets).
- Endpoint groups: StoreChain (1 GET), Household (create/get/invite/accept/remove-member/change-role/stores), ShoppingList (CRUD + item ops + check/uncheck/postpone + duplicate/archive), ShoppingTrip (start/get/add-store/reroute/check/uncheck/postpone/complete/receipts), SSE (`GET /households/{id}/stream`).

## F. Concretization Backlog (open technical design — "Offene Punkte")

These are unresolved *design* questions from SGART.md §10 to be settled in architecture/UX, distinct from the PRD's product Open Questions:
1. **PostgreSQL read-model schema** — tables/columns per projector, types, constraints, indices, UI-driving queries. **Includes an autocomplete read-model (PRD FR-27):** a per-Household projection over `ItemAdded`/`ItemUpdated` of distinct article names + last-used unit/note/default-Store, ranked by recency/frequency, cached client-side for lag-free type-ahead. MVP-only source is list history (no Product aggregate needed); post-Product it can be re-backed by `Product` + aliases + pinning.
2. **SSE protocol** — event JSON format, which events reach the client (raw domain vs. dedicated read-events), Flutter reconnect strategy, auth over SSE.
3. **Flutter screen map & navigation** — screen inventory, nav structure, BLoC-per-screen.
4. **Keycloak configuration** — realm/client settings, invite token/deep-link/web-fallback flow, expected JWT claims.
5. **Offline queue** — local store (SQLite/Hive?), reconnect sync order & idempotency, error/expired-token handling. **Conflict policy decided (PRD FR-26):** each queued command carries the aggregate version it was based on; on replay the write side uses EventStoreDB optimistic concurrency (expected-version) to reject stale commands; the client then surfaces a coarse keep/discard prompt. No auto-merge/presence/delivery-status in MVP. Design work remaining: how the queued command captures/refreshes expected-version, and the reject→prompt round-trip contract.
6. **Docker Compose** — full service setup, ports/volumes/depends-on, env vars, dev vs. prod config.

## G. Milestone / Phase Plan (from SGART.md §7 — sequencing input, not PRD scope)

- **Phase 1 (MVP):** Infra & Docker (Keycloak, Spring Boot, PostgreSQL, EventStoreDB), Keycloak realm.
- **Phase 2 (MVP):** Domain model & write side — `Household`, `ShoppingList`, `ShoppingTrip`; commands + EventStoreDB persistence.
- **Phase 3 (MVP):** Projections & read side — subscribe streams, build PostgreSQL read models, list-state projection + "Liste {N}", StoreChain seed data.
- **Phase 4a (MVP):** REST + SSE + offline queue. **4b (Post-MVP):** OCR abstraction, receipt parser, fingerprint dedup.
- **Phase 5 (MVP):** Flutter UI/BLoC — lists, store-grouped trip view, printing, completion dialog, offline indicator, StoreChain cache + client match. **Post-MVP:** OCR/receipt flow, dashboard, routing, notification settings.

## H. Rejected / Deferred with Rationale (product-level, cross-ref PRD)

- **Conflict handling, presence, delivery status** deferred from MVP: heavy for a friends-&-family beta; MVP uses best-effort LWW replay (PRD FR-11).
- **Standalone receipt scan** (no trip): explicitly backlog — the primary flow ties receipts to a trip+store for clean price attribution.
- **Configurable notification debounce / per-setting config:** MVP ships fixed defaults to avoid settings surface before the core loop is validated.
