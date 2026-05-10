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
| `Household` | `household-{id}` | Membership, roles, store definitions, notification settings |
| `ShoppingList` | `list-{id}` | Item consistency, status |
| `ShoppingTrip` | `trip-{id}` | Routing, live status per store, receipt linkage |
| `Receipt` | `receipt-{id}` | OCR workflow, confirmation, deduplication |
| `Product` | `product-{householdId}-{id}` | Price history, store pinning |

---

### 4.2 Household

**Events:**
```
HouseholdCreated            { householdId, name, createdBy }
MemberInvited               { inviteId, email, role: ADMIN | MEMBER, expiresAt }
MemberJoined                { keycloakUserId }
MemberLeft                  { keycloakUserId }
MemberRoleChanged           { keycloakUserId, newRole }
InviteExpired               { inviteId }
StoreAdded                  { storeId, name, chainId? }
StoreRemoved                { storeId }
NotificationSettingsUpdated { keycloakUserId, settings: {
                                householdChanges: bool,
                                listChanged: bool,
                                listChangedDebounceMinutes: int,  // default: 5
                                tripStatus: bool,
                                receiptScanned: bool
                              }}
```

**Invariants:**
- At least one `ADMIN` must remain at all times
- A pending invite for the same email address cannot exist twice
- An expired invite cannot be redeemed (`InviteExpired`)
- A store name must be unique within a household
- User management is fully delegated to Keycloak; only `keycloakUserId` is referenced in events
- An ADMIN can only leave the household if at least one other ADMIN exists
- An ADMIN cannot be removed by others if they are the only ADMIN
- Only an ADMIN can remove other members; a MEMBER can only remove themselves

**Roles & Permissions:**

| Action | MEMBER | ADMIN |
|---|---|---|
| Create / edit / delete lists | ✅ | ✅ |
| Start / complete a trip | ✅ | ✅ |
| Invite members | ✅ | ✅ |
| Remove other members | ❌ | ✅ |
| Remove themselves | ✅ | ⚠️ only if at least 1 other ADMIN exists |
| Promote member to ADMIN | ❌ | ✅ |
| Delete household | ❌ | ✅ |

**Notes:**
- Invitation flow: Admin enters email → system sends email with personal invite link via Keycloak → link opens app (Deep Link if installed) or web version → recipient registers or logs in → `MemberJoined`
- Store names are household-scoped and free-form (e.g. "Edeka Schiedemann", "E Center Stroetmann")
- Stores are linked to a `StoreChain` reference (see Section 4.7) for aggregated reporting
- Notification settings are per household per member — a user can have different settings for each household they belong to
- Invitation notifications are global (not household-scoped) since they arrive before membership

---

### 4.3 ShoppingList

**Events:**
```
ShoppingListCreated         { listId, householdId, createdBy, name? }
ShoppingListRenamed         { listId, newName }
ItemAdded                   { itemId, name, quantity, unit, note?, addedBy }
ItemUpdated                 { itemId, quantity?, unit?, note? }
ItemRemoved                 { itemId, removedBy }
ItemChecked                 { itemId, checkedBy }
ItemUnchecked               { itemId }
ItemPostponed               { itemId, targetListId? }
ListDuplicated              { newListId, sourceListId }
ListArchivedAsTemplate      { templateName }
```

**List States:**
```
OPEN      → Being planned, no active trip started
IN_TRIP   → Currently linked to an active trip
DONE      → Trip completed, list archived
```

**Invariants:**
- `name` is optional — if not set, a display name is generated automatically (see Notes)
- Item quantity must be > 0
- Items are identified by name + note combination; identical name + note is not allowed (prevents unintended duplicates while allowing e.g. "Milk (Bio)" and "Milk (normal)")
- An empty list cannot be archived or duplicated
- An archived list (`DONE`) accepts no further commands
- Multiple `OPEN` lists are allowed per household

**Notes:**
- `name` is optional. When no name is set, the UI generates a display name `"Liste {N}"` where N is the position of this list sorted by `createdAt` among all lists in state `OPEN` or `IN_TRIP`. Lists in state `DONE` are excluded from this numbering. The numbering is a projection, not a stored value.
- The user can set or change the name at any time via `ShoppingListRenamed` (e.g. "Monday Edeka Route")
- `ItemPostponed`: `targetListId` is optional. If set, a Process Manager fires `ItemAddedFromPostponed` on the target list. If not set, item is flagged as postponed without assignment. UI prompts: "Where should this item go?" → select existing list / create new list / keep as postponed

---

### 4.4 ShoppingTrip

**Events:**
```
TripStarted                 { tripId, householdId, shoppingListId, selectedStoreIds }
StoreAddedToTrip            { storeId, reason: MANUAL | SUGGESTED }
ItemRoutedToStore           { itemId, storeId, reason: PINNED | CHEAPEST | MANUAL }
ItemStoreOverridden         { itemId, originalStoreId, overrideStoreId, updatePinning: bool }
ItemReroutedOnTrip          { itemId, storeId, reason: MANUAL | SUGGESTED }
ItemCheckedOnTrip           { itemId, storeId }
ItemUncheckedOnTrip         { itemId }
ItemPostponedOnTrip         { itemId }
OpenItemsReviewed           { decisions: [{ itemId, action: TRANSFER | DISCARD, targetListId? }] }
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
- `ReceiptScanInitiated` is rejected if trip is `PAUSED` or `DONE`
- Only one trip per shopping list may be in state `ACTIVE` or `PAUSED` at any time
- `TripCompleted` is user-triggered — no forced completion based on receipt status

**Trip States:**
```
ACTIVE  → Trip is running
PAUSED  → Trip is deferred (e.g. postponed to next day); list and open items remain unchanged
DONE    → User declares trip finished; missing receipts remain visible but are not a blocker
```

**Usage Scenarios:**
The app supports three equally valid usage modes:
- **Analog:** Print the list, shop without the app, confirm trip completion afterwards, optionally scan receipts later
- **Digital-Active:** Check off items directly in the app during shopping
- **Hybrid:** Mix of both — e.g. shop analog but scan receipts afterwards for price tracking

**Trip View — Grouped by Store:**
When a trip is started, the shopping list is presented grouped by store. Items not assigned to any store are listed separately at the bottom. This applies to both the in-app view and the print view.

```
📍 Edeka Schiedemann
  ☐ Milk (2L)
  ☐ Butter

📍 Netto
  ☐ Coffee (500g)

──────────────────────
Not yet assigned
──────────────────────
  ☐ Muesli
  ☐ Yoghurt (4-pack)
```

**Store Assignment:**
- The user can manually assign any item to any store before or during the trip
- Items with no store assignment appear in the "Not yet assigned" section
- Post-MVP: automatic routing suggestions based on price history

**Rerouting vs. Postpone during a Trip:**

| Action | Meaning | Event |
|---|---|---|
| **Reroute (A1)** | Item goes to another store already in the trip | `ItemReroutedOnTrip { reason: MANUAL }` |
| **Reroute (A2)** | User adds a new store spontaneously to the trip | `StoreAddedToTrip { reason: MANUAL }` + `ItemReroutedOnTrip` |
| **Reroute (A3)** | App suggests a store based on price history; user confirms | `StoreAddedToTrip { reason: SUGGESTED }` + `ItemReroutedOnTrip { reason: SUGGESTED }` |
| **Postpone** | Item is moved to the next shopping list | `ItemPostponedOnTrip` → Process Manager |

**Trip Completion Flow (multi-step dialog):**
```
1. "Have you finished your shopping trip?"
   → Yes / No (trip remains ACTIVE)

2. "Did you get everything?"
   → Yes, everything → proceed to step 3
   → No → per open item: TRANSFER (to which list?) | DISCARD
   → OpenItemsReviewed event emitted

3. "Would you like to scan receipts?"
   → Per visited store: Scan receipt | Skip
   → Receipts can also be scanned later

4. TripCompleted
```

**Notes:**
- Receipt scan is always initiated from within a trip for a specific store — no free-standing scan in the primary flow (see Backlog for standalone scan)
- `ItemStoreOverridden { updatePinning: true }` triggers a Process Manager to fire `ProductPinnedToStore` on the `Product` aggregate — the permanent pinning is updated transparently
- UI shows receipt status per store: ✅ scanned / ⏳ pending
- The app may proactively prompt "Have you finished your shopping?" based on inactivity or time elapsed

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

**Receipt Confirmation Flow:**
After OCR and parsing, the user sees a single summary screen:
```
"These items were recognised and marked as purchased:"
  ✅ Milk 2L — 1,09€
  ✅ Butter — 1,79€
  ⚠️ "Bio Joghurt 4x125g" → not matched → "Assign to product or ignore?"

→ User taps OK → ItemChecked (bulk) + PriceObserved (bulk) emitted
```
No per-item confirmation required for matched items — one overview, one confirmation.

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

- The full list of StoreChains is loaded by the Flutter client at app start via `GET /api/v1/store-chains` and cached locally (TTL: 24h)
- Fuzzy matching (e.g. Levenshtein distance) runs client-side as the user types the store name
- The matching suggestion is shown inline in the form; the user confirms or overrides before submitting
- `POST /api/v1/households/{id}/stores` receives the final `{ name, chainId? }` — no server-side suggestion needed
- The final decision on chain assignment always rests with the user
- Enables dual-level reporting: per local store ("Edeka Schiedemann") and per chain ("All Edeka branches")

---

### 4.8 Process Managers

| Trigger Event | Action |
|---|---|
| `ReceiptConfirmed` | Issue `ObservePriceCommand` for each matched `Product` |
| `ItemStoreOverridden { updatePinning: true }` | Issue `UnpinFromStoreCommand` + `PinToStoreCommand` on `Product` |
| `ItemPostponed { targetListId }` | Issue `AddItemFromPostponedCommand` on target `ShoppingList` |
| `ItemPostponedOnTrip` | Check if OPEN list exists → if yes: `AddItemCommand` on selected list; if no: `CreateShoppingListCommand` + `AddItemCommand` |
| `OpenItemsReviewed` | For each `TRANSFER` decision: `AddItemCommand` on target list (create list first if none exists) |
| `StoreAddedToTrip { reason: SUGGESTED }` | Based on `PriceObserved` history of the `Product` aggregate |
| `TripCompleted` | Check for unreviewed open items → if any: trigger OpenItemsReviewed dialog |

---

## 5. Feature Scope

### Shopping Lists & Live Mode
- Collaborative lists with multi-tenancy (households & user roles)
- Multiple OPEN lists allowed per household simultaneously (e.g. "Monday Edeka route", "Thursday Aldi run")
- Optional list name — auto-generated display name `"Liste {N}"` based on creation order among OPEN + IN_TRIP lists
- Quantity input with flexible units (pieces, kg, litres)
- Free-text notes (e.g. for preferred brands)
- Reusability (duplicate lists / templates)
- **Print / Druckansicht:** Direct printing via native OS print dialog (Flutter `printing` package) — no PDF is saved on device. The print view uses the same grouped-by-store layout as the trip view. Secondary action: "Share as PDF" (generated in-memory, not saved) for sharing the list e.g. via WhatsApp.
- Granular item status in the supermarket: *Open*, *Done*, *Postponed*

### Shopping Trip — Usage Modes
- **Analog:** Print list → shop → confirm trip done → optionally scan receipts
- **Digital-Active:** Check off items in app during shopping
- **Hybrid:** Any combination of the above

### Shopping Trip — Routing & Store Assignment
- Trip view grouped by store; unassigned items listed separately at bottom
- Manual store assignment per item (always available)
- **Shopping Trips:** User selects stores for current trip
- **Store Pinning:** Lock items to specific stores (e.g. coffee always at Netto)
- **Per-trip Override:** User can manually reroute a pinned item for the current trip only, with optional "Always use this store from now on" checkbox
- Spontaneous store addition during trip (user knows they can get the item there)
- Post-MVP: Automatic routing suggestions based on cheapest historical price

### Notifications (per household, per member)
Configurable per household — a user may have different settings for each household:

| Notification | Default | Configurable |
|---|---|---|
| Household changes (member joined/left) | ✅ On | ✅ |
| List changed (debounced, default 5 min) | ✅ On | ✅ |
| Trip started / completed | ✅ On | ✅ |
| Receipt scanned | ❌ Off | ✅ |
| Invitation received | ✅ On | ❌ (always on) |

- "List changed" notifications are debounced — at most one notification per list per debounce interval, even if many items changed
- Notification content is always a content-free ping; the app fetches real data from own server

### Offline Behaviour
- **Offline Queue (MVP):** Actions performed while offline are stored locally and synced when connection is restored. UI shows "X changes not yet synchronised"
- **Post-MVP — Presence:** Heartbeat mechanism detects when a member is offline; other members see "⚠️ Anna was last seen 5 min ago — her changes may be missing"
- **Post-MVP — Delivery Status:** Server tracks which members have received each event via SSE; UI shows "⚠️ Not yet received by Anna"
- **Post-MVP — Conflict Handling:** When offline actions conflict with online changes, the affected member receives an inline notification (e.g. "You checked off Milk, but it was removed from the list by Peter")

### Dashboard & Reporting (Post-MVP)
- Expenses per month / quarter (bar / pie charts)
- Expenses per store (local store and chain-level aggregation)
- Price history per article per store (line chart) — e.g. select "Milk" → see all purchases, all stores, price over time
- All dashboard views are analytical/read-only — no actions triggered from dashboard
- Routing suggestions during trip planning are based on the same price data but are part of the Trip flow, not the Dashboard

---

## 6. MVP Scope

The MVP focuses on the core collaborative shopping list experience. All intelligence features (OCR, routing, price history, dashboard) are Post-MVP.

**MVP User Journey:**
```
Create household → Invite members →
Create list → Add items (collaboratively, real-time) →
Assign items to stores (manually) →
Print list (native print dialog) OR use app during shopping →
Start trip → Check off items →
Complete trip → Review open items (transfer or discard) →
Create next list
```

**MVP Includes:**
- Household creation & member invitation (Keycloak)
- First app start without household: prompt to create a new household or wait for an invitation
- Multiple collaborative shopping lists per household
- Real-time sync via SSE (BLoC state management in Flutter)
- Manual store assignment per item
- Trip view grouped by store with "Not yet assigned" section
- Print via native OS print dialog; secondary "Share as PDF" action (in-memory)
- Trip completion dialog (open items review)
- Offline queue (local pending actions)
- Basic notifications (invitation, list changed, trip status)
- Roles: ADMIN / MEMBER (all permissions equal except household deletion and member removal)

**Post-MVP (planned, not in first release):**
- OCR & receipt scanning
- Automatic item-to-store routing (price-based)
- Store pinning & permanent routing preferences
- Price history & product tracking
- Dashboard & reporting
- Configurable notification settings per household
- Offline presence detection & delivery status
- Conflict handling for offline edits
- Standalone receipt scan (without trip context)
- Store logos from chain reference data
- Smart item suggestions based on history
- Debounce configuration for notifications

---

## 7. Milestone Plan

### Phase 1: Infrastructure & Docker Setup ✅ MVP
- Set up `docker-compose.yml` (Keycloak, Spring Boot, PostgreSQL, EventStoreDB)
- Network configuration and persistent volumes
- Keycloak realm configuration (SGART realm, client, roles)

### Phase 2: Domain Model & Write Side (Backend) ✅ MVP
- Define aggregates (`ShoppingList`, `ShoppingTrip`, `Household`) and domain events in Java
- Implement command handling and persistence in EventStoreDB
- MVP aggregates only: `Household`, `ShoppingList`, `ShoppingTrip`
- Post-MVP aggregates deferred: `Receipt`, `Product`

### Phase 3: Projections & Read Side (Backend) ✅ MVP
- Subscribe to EventStoreDB streams via Spring Boot
- Develop projectors that build relational PostgreSQL tables from events for UI queries
- List state projection (OPEN / IN_TRIP / DONE) and display name generation (`Liste {N}`)
- StoreChain reference data setup (static seed data in PostgreSQL; fuzzy matching is client-side)

### Phase 4: API, SSE & OCR Integration
- **Phase 4a (MVP):** Expose REST endpoints (Queries & Commands) per Section 9, build SSE broadcaster for real-time updates, offline queue support
- **Phase 4b (Post-MVP):** Implement OCR abstraction (Strategy Pattern) and receipt parser, implement receipt fingerprint deduplication

### Phase 5: Flutter Frontend & BLoC ✅ MVP
- UI/UX development (lists, trip view grouped by store, print via `printing` package)
- Integrate SSE client and map server events to BLoC state management
- Trip completion dialog (open items review)
- Offline queue indicator in UI
- StoreChain cache + client-side fuzzy matching for store name input
- Post-MVP: OCR/receipt flow, dashboard, routing suggestions, notification settings

---

## 8. Backlog (Low Priority / Post-MVP)

- **Standalone Receipt Scan:** Scan a receipt without a trip context, purely for price tracking
  ```
  StandaloneReceiptScanCommand { householdId, storeId }
  ```
- **Store Logos:** Automatically enrich store entries with chain logos from reference data
- **Smart Item Suggestions:** Suggest items based on historical shopping patterns
- **Cross-device sync:** Real-time sync across multiple devices per household member
- **Offline Presence:** Heartbeat-based detection of offline members with UI indicator
- **Delivery Status:** Track which household members have received each SSE event
- **Conflict Handling:** Inline notifications when offline edits conflict with concurrent changes
- **Configurable Notification Debounce:** Let users choose "immediately / every 5 min / every 30 min"
- **Routing Suggestions:** Automatic item-to-store routing based on cheapest historical price
- **Dashboard & Reporting:** Full analytics suite (expenses by month/store, price history per article)

---

## 9. API Kontrakt

All endpoints are prefixed with `/api/v1`.
Authentication: JWT issued by Keycloak, validated by the Spring Boot backend. The `keycloakUserId` is extracted from the token — no user ID in request body or path needed.

### Error Format
All error responses use a uniform JSON structure:
```json
{
  "code": "INVITE_ALREADY_EXISTS",
  "message": "A pending invite for this email already exists.",
  "details": {}
}
```
- `code` — machine-readable error identifier, evaluated by the Flutter client for user-facing messages
- `message` — for logging/debugging only, not shown to the end user
- `details` — optional additional context (e.g. which field failed validation)

### Pagination
No pagination in MVP. All list endpoints return the full result set.

---

### StoreChain

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/store-chains` | Returns all known store chains. Loaded at app start, cached client-side (TTL 24h). Used for client-side fuzzy matching when adding a store. |

Response:
```json
[
  { "chainId": "edeka", "normalizedName": "Edeka", "logoUrl": null },
  { "chainId": "aldi", "normalizedName": "Aldi", "logoUrl": null }
]
```

---

### Household

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/households` | All households of the authenticated user. If empty → first-start flow (create or await invite). If 1 → load directly. If n → show selection screen. |
| `POST` | `/api/v1/households` | Create a new household |
| `GET` | `/api/v1/households/{id}` | Load household details |
| `POST` | `/api/v1/households/{id}/invites` | Invite a member by email |
| `POST` | `/api/v1/invites/{inviteId}/accept` | Accept an invite (called after Deep Link / web redirect) |
| `DELETE` | `/api/v1/households/{id}/members/{userId}` | Remove a member (ADMIN only, or self-removal) |
| `PATCH` | `/api/v1/households/{id}/members/{userId}/role` | Change member role (ADMIN only) |
| `POST` | `/api/v1/households/{id}/stores` | Add a store. Body: `{ name, chainId? }`. The `chainId` is determined client-side via fuzzy matching and confirmed by the user before submission. |
| `DELETE` | `/api/v1/households/{id}/stores/{storeId}` | Remove a store |

---

### ShoppingList

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/households/{id}/lists` | All lists for a household |
| `POST` | `/api/v1/households/{id}/lists` | Create a new list |
| `GET` | `/api/v1/lists/{id}` | Load a single list with items |
| `PATCH` | `/api/v1/lists/{id}` | Rename a list |
| `POST` | `/api/v1/lists/{id}/items` | Add an item |
| `PATCH` | `/api/v1/lists/{id}/items/{itemId}` | Update an item (quantity, unit, note) |
| `DELETE` | `/api/v1/lists/{id}/items/{itemId}` | Remove an item |
| `POST` | `/api/v1/lists/{id}/items/{itemId}/check` | Check an item |
| `POST` | `/api/v1/lists/{id}/items/{itemId}/uncheck` | Uncheck an item |
| `POST` | `/api/v1/lists/{id}/items/{itemId}/postpone` | Postpone an item |
| `POST` | `/api/v1/lists/{id}/duplicate` | Duplicate a list |
| `POST` | `/api/v1/lists/{id}/archive` | Archive a list as template |

---

### ShoppingTrip

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/households/{id}/trips` | Start a trip |
| `GET` | `/api/v1/trips/{id}` | Load trip details |
| `POST` | `/api/v1/trips/{id}/stores` | Add a store to the trip spontaneously |
| `PATCH` | `/api/v1/trips/{id}/items/{itemId}/store` | Reroute an item to another store |
| `POST` | `/api/v1/trips/{id}/items/{itemId}/check` | Check off an item during the trip |
| `POST` | `/api/v1/trips/{id}/items/{itemId}/uncheck` | Uncheck an item during the trip |
| `POST` | `/api/v1/trips/{id}/items/{itemId}/postpone` | Postpone an item during the trip |
| `POST` | `/api/v1/trips/{id}/pause` | Pause the trip |
| `POST` | `/api/v1/trips/{id}/resume` | Resume a paused trip |
| `POST` | `/api/v1/trips/{id}/complete` | Complete the trip (includes OpenItemsReviewed payload) |
| `POST` | `/api/v1/trips/{id}/receipts` | Initiate a receipt scan for a specific store |

---

### SSE

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/households/{id}/stream` | Subscribe to the SSE event stream for a household |

---

## 10. Offene Punkte (Concretization Backlog)

### 10.1 PostgreSQL Read-Model-Schema ⬜
- Tabellen & Spalten pro Projektor
- Datentypen, Constraints, Indizes
- Welche Queries treiben die UI?

### 10.2 SSE-Protokoll ⬜
- Event-Format (JSON-Struktur, Event-Typen)
- Welche Events werden an den Client gesendet? (alle Domain-Events? oder dedizierte Read-Events?)
- Client-Subscription-Logik in Flutter (Reconnect-Strategie, Auth-Header via SSE)

### 10.3 Flutter Screen-Map & Navigation ⬜
- Übersicht aller Screens
- Navigationsstruktur (Bottom Nav, Stack, Routing)
- Welcher BLoC gehört zu welchem Screen?

### 10.4 Keycloak-Konfiguration ⬜
- Realm- & Client-Settings
- Invite-Flow technisch (Token-Link, Deep Link, Fallback Web)
- JWT-Claims die das Backend erwartet (z.B. `sub`, `email`, custom claims?)

### 10.5 Offline-Queue ⬜
- Lokaler Speicher (SQLite / Hive?)
- Sync-Strategie beim Reconnect (Reihenfolge, Idempotenz)
- Fehlerbehandlung (Konflikt, abgelaufener Token, Server-seitige Ablehnung)

### 10.6 Docker Compose ⬜
- Vollständiges Service-Setup (Ports, Volumes, Depends-on)
- Environment-Variablen pro Service
- Lokale Dev- vs. Prod-Konfiguration