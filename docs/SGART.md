# Project Plan: Smart Grocery & Receipt Tracker (SGART)

## 1. Project Story (Vision)

Development of a privacy-friendly, collaborative household app for intelligent management of shopping lists and receipt analysis. The app enables users to process purchases in real time, scan receipts via smartphone camera, and analyze price trends and expenses per store from the extracted data (OCR). An intelligent algorithm will help in the future to split items based on historical data across preferred stores and planned shopping trips to optimize costs.

---

## 2. Architecture & Tech Stack

- **Frontend (Mobile App):** Flutter (Cross-Platform for iOS & Android)
- **Backend:** Custom Java application with Spring Boot
- **Identity & Auth:** Keycloak (Self-Hosted, Docker) — handles user management, JWT issuance, email invitations, and future 2FA/Social Login without backend changes
- **Databases:**
  - **EventStoreDB:** Immutable source of truth for all system events (Write Model)
  - **PostgreSQL:** For relational queries, dashboards, and current list status (Read Models / Projections)
- **Hosting:** Docker containers (Keycloak, EventStoreDB, PostgreSQL, Spring Boot Backend) on a dedicated server in Germany (e.g. Hetzner, IONOS) for 100% GDPR compliance without US cloud services
- **Real-time Communication:** Server-Sent Events (SSE) stream backend events directly into the Flutter app

---

## 3. Core Concepts & Design Patterns

- **Domain-Driven Design (DDD) & CQRS:** Strict separation of write operations (Commands) and read operations (Queries) in the Spring Boot backend
- **Event Sourcing:** The state of shopping lists and receipts is not overwritten but stored as a chain of immutable events (e.g. `ItemAdded`, `ItemChecked`) in EventStoreDB
- **State Management (Flutter):** BLoC pattern. Domain events received via SSE are converted into BLoC events and reactively update the UI
- **Strategy Pattern (OCR):** Strict abstraction of text recognition in Flutter. Start with local, offline-capable on-device models (e.g. ML Kit On-Device), with the flexibility to switch to self-hosted server solutions or EU service providers later
- **Privacy-Friendly Push Notifications:** Pure, content-free "ping" messages via Google/Apple infrastructure, after which the app pulls the real data encrypted from the own server in the background
- **Process Managers:** Coordinate cross-aggregate workflows (e.g. `ReceiptConfirmed` → `PriceObserved` on `Product` aggregates)

---

## 4. Domain Model

### 4.1 Aggregates Overview

| Aggregate | Stream Key | Primary Responsibility |
|---|---|---|
| `Household` | `household-{id}` | Membership, roles, store definitions |
| `ShoppingList` | `list-{id}` | Item consistency, status |
| `ShoppingTrip` | `trip-{id}` | Routing, live status per store, receipt linkage |
| `Receipt` | `receipt-{id}` | OCR workflow, confirmation, deduplication |
| `Product` | `product-{householdId}-{id}` | Price history, store pinning |

---

### 4.2 Household

**Events:**
```
HouseholdCreated        { householdId, name, createdBy }
MemberInvited           { inviteId, email, role: ADMIN | MEMBER, expiresAt }
MemberJoined            { keycloakUserId }
MemberLeft              { keycloakUserId }
MemberRoleChanged       { keycloakUserId, newRole }
InviteExpired           { inviteId }
StoreAdded              { storeId, name, chainId? }
StoreRemoved            { storeId }
```

**Invariants:**
- At least one `ADMIN` must remain at all times
- A pending invite for the same email address cannot exist twice
- An expired invite cannot be redeemed (`InviteExpired`)
- A store name must be unique within a household
- User management is fully delegated to Keycloak; only `keycloakUserId` is referenced in events

**Notes:**
- Invitation flow: Admin enters email → system sends email with personal invite link via Keycloak → link opens app (Deep Link if installed) or web version → recipient registers or logs in → `MemberJoined`
- Store names are household-scoped and free-form (e.g. "Edeka Schiedemann", "E Center Stroetmann")
- Stores are linked to a `StoreChain` reference (see Section 4.6) for aggregated reporting

---

### 4.3 ShoppingList

**Events:**
```
ShoppingListCreated         { listId, householdId, name, createdBy }
ItemAdded                   { itemId, name, quantity, unit, note?, addedBy }
ItemUpdated                 { itemId, quantity?, unit?, note? }
ItemRemoved                 { itemId, removedBy }
ItemChecked                 { itemId, checkedBy }
ItemUnchecked               { itemId }
ItemPostponed               { itemId, targetListId? }
ListDuplicated              { newListId, sourceListId }
ListArchivedAsTemplate      { templateName }
```

**Invariants:**
- List name must not be empty
- Item quantity must be > 0
- Items are identified by name + note combination; identical name + note is not allowed (prevents unintended duplicates while allowing e.g. "Milk (Bio)" and "Milk (normal)")
- An empty list cannot be archived or duplicated
- An archived list accepts no further commands

**Notes:**
- Default list name: `"Shopping Week {KW} / {YYYY}"` — generated server-side, overridable by user
- `ItemPostponed`: `targetListId` is optional. If set, a Process Manager fires `ItemAddedFromPostponed` on the target list. If not set, item is flagged as postponed without assignment. UI prompts: "Where should this item go?" → select existing list / create new list / keep as postponed

---

### 4.4 ShoppingTrip

**Events:**
```
TripStarted                 { tripId, householdId, shoppingListId, selectedStoreIds }
ItemRoutedToStore           { itemId, storeId, reason: PINNED | CHEAPEST | MANUAL }
ItemStoreOverridden         { itemId, originalStoreId, overrideStoreId, updatePinning: bool }
ItemCheckedOnTrip           { itemId, storeId }
ItemUncheckedOnTrip         { itemId }
ItemPostponedOnTrip         { itemId, targetStoreId?, nextTrip: bool }
ReceiptScanInitiated        { receiptId, storeId }
ReceiptLinkedToTrip         { receiptId, storeId }
TripPaused                  { reason? }
TripResumed                 {}
TripCompleted               { completedBy }
```

**Invariants:**
- A trip requires at least one store
- A trip requires a linked `ShoppingList`
- `ReceiptScanInitiated` is rejected if a receipt for the given `storeId` is already linked
- `ReceiptScanInitiated` is rejected if trip is `PAUSED`, `DONE`
- Only one trip per shopping list may be in state `ACTIVE` or `PAUSED` at any time
- `TripCompleted` is user-triggered — no forced completion based on receipt status

**Trip States:**
```
ACTIVE  → Trip is running
PAUSED  → Trip is deferred (e.g. postponed to next day); list and open items remain unchanged
DONE    → User declares trip finished; missing receipts remain visible but are not a blocker
```

**Notes:**
- Receipt scan is always initiated from within a trip for a specific store — no free-standing scan in the primary flow
- `ItemStoreOverridden { updatePinning: true }` triggers a Process Manager to fire `ProductPinnedToStore` on the `Product` aggregate — the permanent pinning is updated transparently
- UI shows receipt status per store: ✅ scanned / ⏳ pending

---

### 4.5 Receipt

**Events:**
```
ReceiptScanInitiated        { receiptId, tripId, storeId, initiatedBy }
OcrCompleted                { rawText, ocrProvider }
ReceiptParsed               { items: [{ name, qty, unitPrice, totalPrice }], total, date }
ReceiptItemMatched          { receiptItemIndex, productId }
ReceiptItemUnmatched        { receiptItemIndex, suggestedName }
ReceiptFingerprintRegistered { fingerprint }
ReceiptMarkedAsDuplicate    { originalReceiptId, detectedBy: USER | SYSTEM }
ReceiptConfirmed            { confirmedBy }
```

**Invariants:**
- `OcrCompleted` only after `ReceiptScanInitiated`
- `ReceiptParsed` only after `OcrCompleted`
- `ReceiptConfirmed` only after `ReceiptParsed`
- At least one item must exist after parsing
- After `ReceiptConfirmed`, the receipt is immutable — no further changes
- Duplicate detection via fingerprint (`hash(storeId + date + total + sortedItemNames)`) is evaluated after `ReceiptParsed`; duplicate prompts user confirmation, not automatic rejection

**Notes:**
- `ReceiptConfirmed` triggers a Process Manager: for each matched item, `ObservePriceCommand` is issued on the corresponding `Product` aggregate (eventual consistency, idempotent)
- OCR abstraction via Strategy Pattern: starts with ML Kit On-Device, switchable to self-hosted or EU provider

---

### 4.6 Product

**Events:**
```
ProductDiscovered           { productId, householdId, name }
ProductAliasAdded           { alias }
PriceObserved               { storeId, price, observedAt, receiptId }
ProductPinnedToStore        { storeId }
ProductUnpinnedFromStore    { storeId }
```

**Invariants:**
- Product name must be unique within a household
- `PriceObserved` always requires a `storeId` — no price without store context
- A product may be pinned to at most one store at a time
- `ProductPinnedToStore` is rejected if the product is already pinned to a different store — requires explicit `ProductUnpinnedFromStore` first (unless triggered via `ItemStoreOverridden { updatePinning: true }`, which handles unpin + repin atomically via Process Manager)

---

### 4.7 Reference Data: StoreChain

Not an aggregate — managed as static/semi-static reference data in PostgreSQL.

```
StoreChain { chainId, normalizedName, logoUrl? }
// Examples: { "edeka", "Edeka" }, { "aldi", "Aldi" }, { "rewe", "Rewe" }
```

- On `StoreAdded`, the backend suggests a `chainId` via fuzzy matching (Levenshtein distance) against known chains
- User confirms or overrides — final decision always with the user
- Enables dual-level reporting: per local store ("Edeka Schiedemann") and per chain ("All Edeka branches")

---

### 4.8 Process Managers

| Trigger Event | Action |
|---|---|
| `ReceiptConfirmed` | Issue `ObservePriceCommand` for each matched `Product` |
| `ItemStoreOverridden { updatePinning: true }` | Issue `UnpinFromStoreCommand` + `PinToStoreCommand` on `Product` |
| `ItemPostponed { targetListId }` | Issue `AddItemFromPostponedCommand` on target `ShoppingList` |

---

## 5. Feature Scope

### Shopping Lists & Live Mode
- Collaborative lists with multi-tenancy (households & user roles)
- Quantity input with flexible units (pieces, kg, litres)
- Free-text notes (e.g. for preferred brands)
- Reusability (duplicate lists / templates)
- Print function (PDF export for offline users)
- Granular item status in the supermarket: *Open*, *Done*, *Postponed* (to another store or next trip)
- Default list name: `"Shopping Week {KW} / {YYYY}"`

### Smart Features & Routing
- **Shopping Trips:** User selects stores for current trip (e.g. only Edeka and Aldi)
- **Store Pinning:** Lock items to specific stores (e.g. coffee always at Netto)
- **Per-trip Override:** User can manually reroute a pinned item for the current trip only, with optional "Always use this store from now on" checkbox
- Automatic routing of remaining items based on cheapest historical price

### Dashboard & Reporting
- Expenses per month (bar/pie charts)
- Expenses per store (local and chain-level aggregation)
- Price history of individual items per store (line charts)

---

## 6. Milestone Plan

### Phase 1: Infrastructure & Docker Setup
- Set up `docker-compose.yml` (Keycloak, Spring Boot, PostgreSQL, EventStoreDB)
- Network configuration and persistent volumes
- Keycloak realm configuration (SGART realm, client, roles)

### Phase 2: Domain Model & Write Side (Backend)
- Define aggregates (`ShoppingList`, `ShoppingTrip`, `Receipt`, `Household`, `Product`) and domain events in Java
- Implement command handling and persistence in EventStoreDB

### Phase 3: Projections & Read Side (Backend)
- Subscribe to EventStoreDB streams via Spring Boot
- Develop projectors that build relational PostgreSQL tables from events for UI queries
- StoreChain reference data setup and fuzzy matching logic

### Phase 4: API, SSE & OCR Integration
- Expose REST endpoints (Queries & Commands)
- Build SSE broadcaster for real-time updates
- Implement OCR abstraction (Strategy Pattern) and receipt parser
- Implement receipt fingerprint deduplication

### Phase 5: Flutter Frontend & BLoC
- UI/UX development (lists, live mode, dashboards)
- Integrate SSE client and map server events to BLoC state management
- Finalize offline sync logic and run end-to-end tests

---

## 7. Backlog (Low Priority)

- **Standalone Receipt Scan:** Scan a receipt without a trip context, purely for price tracking
  ```
  StandaloneReceiptScanCommand { householdId, storeId }
  ```
- **Store Logos:** Automatically enrich store entries with chain logos from reference data
- **Smart Item Suggestions:** Suggest items based on historical shopping patterns
- **Cross-device sync:** Real-time sync across multiple devices per household member