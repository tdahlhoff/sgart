---
title: SGART — Smart Grocery & Receipt Tracker
status: draft
created: 2026-08-06
updated: 2026-08-06
---

# PRD: SGART — Smart Grocery & Receipt Tracker
*Working title — confirm.*

## 0. Document Purpose

This PRD defines *what* SGART must do and *why*, for the PM, downstream UX and architecture work, and the epic/story breakdown that follows. It is capability-first: requirements are expressed as behaviors, not implementations. The considerable technical depth already worked out in `docs/SGART.md` — the DDD/CQRS/event-sourcing design, the aggregate and event catalog, the REST + SSE API contract, the tech stack, and the milestone plan — is **not duplicated here**. It has been preserved into the companion `addendum.md` as input for the architecture phase. Where this PRD makes a scope or product decision that differs from or pressure-tests `SGART.md`, it says so inline. Vocabulary is anchored in §3 Glossary; features are grouped with globally numbered FRs nested under them; inferences are tagged `[ASSUMPTION]` inline and indexed in §9.

## 1. Vision

SGART is a privacy-friendly, collaborative household app for grocery shopping. Its near-term job is mundane but real: replace the paper list, the whiteboard, and the "add milk 🥛" WhatsApp messages with a shared list that everyone in the household edits at the same time and that stays correct while people are actually standing in the store. Lists are collaborative and live; a shopping trip can be walked digitally (check items off in the app), analog (print the list, shop, confirm afterward), or any mix of the two — the app adapts to how the household already shops rather than forcing a single behavior.

The longer-term ambition is intelligence about money. Every confirmed purchase — eventually captured by scanning the receipt with the phone camera and reading it on-device — becomes a price observation tied to a specific store. Over time SGART builds a private, household-scoped picture of what things cost where, so it can show price history per article and, later, suggest which store to route which item to. This is the payoff that separates SGART from a shared checklist: it turns routine shopping into a quiet, ongoing cost-awareness tool.

Privacy is a first-class product property, not a compliance afterthought. The system is designed to run self-hosted on a German server with no US cloud dependency; identity is delegated to a self-hosted Keycloak; push notifications carry no content (a bare "ping" that prompts the app to fetch the real data from the household's own server); and receipt OCR starts on-device. SGART is intended to grow into a public product, but the MVP deliberately launches to a small **friends-&-family cohort** for first real-world feedback before any public rollout.

## 2. Target User

SGART serves **households** — a couple, a family, a shared flat — where more than one person contributes to what gets bought and someone ends up doing the shopping. A **household of one is a first-class case**, not an edge case: a single person gets the same store-grouped lists, analog/digital/hybrid trips, printing, and (Post-MVP) receipt-driven price tracking. For a solo user the collaboration mechanics simply stay quiet — one list across their own devices instead of across people. The MVP's first users are the builder's own friends and family: real households (solo and multi-person) willing to use it for real shopping and report back.

### 2.1 Jobs To Be Done

*Jobs marked (multi) apply to multi-person households; all others hold for a household of one too.*

- **Functional — keep one shared truth.** "We keep forgetting things and buying duplicates because our list lives in three places. Give us one list [everyone can edit] / [I can trust across my own devices] and rely on." *(shared-edit aspect is (multi))*
- **Functional — shop efficiently across stores.** "We shop at Edeka *and* Aldi. Let me see what to get where, in the aisle, without re-sorting a flat list in my head."
- **Contextual — work the way we already shop.** "Sometimes I shop with my phone out, sometimes I just want a printed list. Don't make me pick one style forever."
- **Social — coordinate without nagging.** *(multi)* "Let my partner add to the list from work and let me see it update while I'm in the store — without a flood of notifications."
- **Financial (horizon 2) — know what things cost.** "I have no idea whether milk is cheaper at Netto. Quietly remember my receipts and tell me over time."
- **Emotional — trust it with our data.** "I don't want our grocery habits sitting in some US ad-tech cloud."

### 2.2 Non-Users (v1)

- **Solo power-budgeters** who want a full personal-finance / envelope-budgeting tool — SGART tracks grocery spend as a byproduct, it is not a budgeting app.
- **Store / retailer operators** — there is no merchant side; store and price data are private to each household.
- **The general public, at MVP.** MVP onboards a curated friends-&-family cohort only; open self-signup and any growth mechanics are Post-MVP.
- **Users needing a full web experience.** Web exists in v1 only as an invite-acceptance fallback, not as a client for daily use.

### 2.3 Key User Journeys

*Named-persona narratives the product enables, numbered UJ-1..UJ-5. FRs reference them inline. UJ-5 is a horizon-2 journey that depends on Post-MVP features and is included to keep the long-term shape honest.*

- **UJ-1. Anna sets up the household and pulls Ben in.**
  - **Persona + context:** Anna, one half of a two-person household, is the "organizer." She heard about SGART from the builder and agreed to try it.
  - **Entry state:** Fresh install, not authenticated, no household yet.
  - **Path:** Registers/logs in via the Keycloak-backed flow → app detects she has no household and offers *create* or *wait for invite* → she creates "Zuhause" → adds their two usual stores ("Edeka Schiedemann", "Netto"), confirming the suggested chain as she types → invites Ben by email.
  - **Climax:** Ben taps the emailed invite link, it opens the app (or web fallback), he logs in, and he appears in the household member list on Anna's screen.
  - **Resolution:** A two-member household with two stores, ready to build lists.
  - **Edge case:** Anna mistypes Ben's email and re-invites; the system rejects a second pending invite to the same address rather than sending two.

- **UJ-2. Anna and Ben build the weekly list from two places at once.**
  - **Persona + context:** Ben is at work; Anna is at home planning the week.
  - **Entry state:** Both authenticated, one shared "Open" list.
  - **Path:** Anna adds items with quantities and a note ("Milk 2L", "Butter", "Coffee 500g — the dark one") → Ben, on his phone, adds "Yoghurt (4-pack)" → each sees the other's additions appear live → Anna assigns coffee to Netto and milk/butter to Edeka; yoghurt stays unassigned.
  - **Climax:** Neither has to refresh or message the other; the list is simply correct for both.
  - **Resolution:** One "Open" list, some items grouped by store, ready for a trip.
  - **Edge case:** Ben adds "Milk" while Anna already added "Milk 2L"; because items are keyed by name+note, the duplicate is prevented/flagged rather than silently doubled.

- **UJ-3. Ben shops the trip digitally and cleans up the leftovers.**
  - **Persona + context:** Ben stops at Edeka on the way home.
  - **Entry state:** Authenticated; he starts a trip from the "Open" list, selecting Edeka and Netto.
  - **Path:** The trip view shows items grouped by store, with unassigned items in a "Not yet assigned" section → at Edeka he checks off milk and butter → Netto is out of his usual coffee, so he *postpones* the coffee → he decides yoghurt can wait too → he taps *complete trip*.
  - **Climax:** The completion dialog asks "did you get everything?"; for the two open items he chooses TRANSFER → they land on a new/next "Open" list automatically, and the trip closes.
  - **Resolution:** Trip is Done and archived; a fresh Open list already holds the carried-over items.
  - **Edge case:** Ben loses signal in the store; his check-offs queue locally and sync when signal returns, and the app shows "2 changes not yet synchronized."

- **UJ-4. Anna shops analog with a printed list.**
  - **Persona + context:** Anna prefers paper in the store and doesn't want her phone out.
  - **Entry state:** Authenticated at home; an Open list ready.
  - **Path:** She opens the print view (same grouped-by-store layout) → prints via the phone's native print dialog → shops with paper → back home, she opens the trip and confirms it complete, marking the one thing she couldn't find as postponed.
  - **Climax:** Her paper run is reflected in the app afterward with a couple of taps; nothing forced her to check items off live.
  - **Resolution:** Trip Done; household state consistent whether or not the app was used in-aisle.
  - **Edge case:** She'd rather send the list to a family member on WhatsApp than print — she uses "Share as PDF" (generated in-memory, not saved) instead.

- **UJ-5. (Horizon 2 — Post-MVP) Ben scans a receipt and later learns milk is cheaper at Netto.**
  - **Persona + context:** Ben, mildly cost-conscious, finishing a trip.
  - **Path (depends on Post-MVP receipt + price features):** At trip completion he scans the Edeka receipt with the camera → OCR runs on-device → a single summary screen shows the recognized items and prices, matched to his list, with one unmatched line to resolve → he taps OK → weeks later he opens milk's price history and sees Netto has consistently been cheaper.
  - **Resolution:** A private, growing record of what things cost where — the long-term payoff.

## 3. Glossary

*Downstream workflows and readers must use these terms exactly. FRs, UJs, and SMs use these verbatim; no synonyms elsewhere in the PRD.*

- **Household** — The top-level tenant. Owns members, stores, lists, trips, and (Post-MVP) products/receipts. A Household may have a single Member (solo use is fully supported) or many. A user may belong to more than one Household. All data is scoped to a Household and private to it.
- **Member** — A user's participation in a specific Household. Identity itself is external (delegated to Keycloak); SGART references only an opaque user id. A user is a Member once per Household they join.
- **Role** — A Member's permission level within a Household: **Admin** or **Member-role**. (When capitalized "Member" refers to the participation; "Member-role" refers specifically to the non-Admin role.)
- **Store** — A concrete shopping location defined within a Household, with a free-form name (e.g. "Edeka Schiedemann"). Store names are unique within a Household.
- **StoreChain** — Reference data identifying a retail brand (e.g. "Edeka", "Aldi" in Germany; "Albert Heijn", "Jumbo" in NL; "Carrefour" in FR). A Store may optionally be linked to a StoreChain to enable chain-level grouping later. Not household-specific; shared read-only reference data, **scoped per country/market** (the available chains depend on the household's market).
- **Locale** — The user's language + regional formatting (e.g. `de-DE`, `nl-NL`, `fr-FR`). Determines UI language and how currency, dates, quantities, and decimals are displayed. Distinct from the country/market that scopes StoreChains, though they usually align.
- **Shopping List** *(short: List)* — A named or auto-named collection of Items within a Household. Has a **List State**.
- **List State** — One of: **Open** (being planned, no active trip), **In-Trip** (linked to an active Trip), **Done** (trip completed, archived, immutable).
- **Item** — A line on a List: name, quantity, unit, optional note, and an **Item Status**. Identified by name + note within a List (duplicates of the same name + note are not allowed).
- **Item Status** — One of: **Open** (still to buy), **Done** (bought/checked off), **Postponed** (deferred, optionally moved to another List).
- **Shopping Trip** *(short: Trip)* — An act of shopping against one List across one or more selected Stores. Has a **Trip State**.
- **Trip State** — One of: **Active**, **Done**. At most one Active Trip per List at a time. (There is no Paused state — see FR-15.)
- **Store Assignment** — The linking of an Item to a Store for a Trip, so the Item appears under that Store in the grouped view. Items with no assignment appear under "Not yet assigned."
- **Postpone** — Deferring an Item so it is not bought now; optionally moving it to another (existing or new) List.
- **Reroute** — During a Trip, moving an Item's Store Assignment to a different Store that is (or is spontaneously added) part of the Trip. Distinct from Postpone.
- **Offline Queue** — Locally stored actions performed while the device is offline, replayed to the server when connectivity returns.
- **Live Sync** — The real-time propagation of Household changes to all connected Members' devices.
- **Pinning** *(Post-MVP)* — A durable preference binding a **Product** to a Store, so future Items for that Product default to that Store. A Product is pinned to at most one Store.
- **Receipt** *(Post-MVP)* — A scanned store receipt tied to a Trip and Store, processed via OCR into priced line items and then confirmed.
- **Product** *(Post-MVP)* — A household-scoped catalog entry (name + aliases) accumulating **Price Observations** and an optional Pinning.
- **Price Observation** *(Post-MVP)* — A recorded (Product, Store, price, date) fact, always store-scoped, derived from a confirmed Receipt.

## 4. Features

*MVP features (§4.1–§4.7) carry full FRs and are the delivery commitment. Post-MVP features (§4.8–§4.10) are described so downstream design stays coherent, with lighter FRs marked `[POST-MVP]`. FRs are globally numbered for stable downstream reference.*

### 4.1 Households & Membership

**Description:** A Household is the private container for everything. On first launch a user with no Household is offered two paths: create one, or wait for an invite (UJ-1). An Admin invites others by email; the invite is a personal link that opens the app (deep link) or a web fallback, where the recipient authenticates and joins. Roles are deliberately flat — Admin and Member-role can both do all day-to-day work (lists, trips, invites); the role gate exists only for destructive/governance actions (removing *other* members, promoting to Admin, deleting the Household). Identity, registration, password reset, and email delivery are delegated to Keycloak; SGART stores no credentials and references members only by an opaque id.

**Functional Requirements:**

#### FR-1: Create a Household
An authenticated user with no Household can create one with a name, becoming its first Admin. Realizes UJ-1.
**Consequences (testable):**
- After creation the creator is a Member with Role = Admin.
- A user who already belongs to ≥1 Household still sees the option to create another.
- On app open with zero Households, the user is routed to the create/await-invite choice; with exactly one, straight into it; with several, to a selection screen.

#### FR-2: Invite a Member by email
Any Member (Admin or Member-role) can invite a person by email address. Realizes UJ-1.
**Consequences (testable):**
- A second *pending* invite to the same email in the same Household is rejected.
- An invite has an expiry; an expired invite cannot be redeemed.
- Accepting a valid invite adds the person as a Member with Role = Member-role.
- The invite link opens the installed app via deep link, or a web fallback if the app is not installed.

#### FR-3: Membership lifecycle & roles
The system enforces role-based permissions and the "at least one Admin" invariant.
**Consequences (testable):**
- A Member-role user can remove only themselves; an Admin can remove any Member.
- The last remaining Admin cannot leave or be removed, and cannot be demoted, until another Admin exists.
- Only an Admin can promote a Member to Admin or delete the Household.
- A Member's identity is referenced only by opaque user id; no email/password is stored by SGART. **Decided:** member display name/email shown in the UI are read live from Keycloak/JWT claims at display time, never stored in SGART's own model.

**Feature-specific NFRs:**
- Invitation notification is global (not Household-scoped), since it must reach the recipient before membership exists.

**Notes:**
- `[NOTE FOR PM]` GDPR erasure vs. event-sourced history: a Member's "right to be forgotten" collides with an immutable event log that references their user id. Resolution strategy (pseudonymization / crypto-shredding of the id) is an Open Question (§8) and a data-governance decision for architecture — flagged, not solved here.

### 4.2 Stores & Chain Reference

**Description:** Each Household defines the Stores it shops at, with free-form names. As a user types a Store name, the app matches it against a cached list of StoreChains and suggests a brand inline; the user confirms or overrides — the chain link is never forced or decided server-side. The chain link does nothing user-visible in MVP beyond being captured; it exists to enable chain-level grouping and reporting later. Store definitions are a prerequisite for the grouped trip/print views (§4.5, §4.6).

**Functional Requirements:**

#### FR-4: Manage Stores
Any Member can add and remove Stores in their Household. Realizes UJ-1.
**Consequences (testable):**
- Store name must be unique within the Household; a duplicate is rejected.
- Adding a Store optionally captures a StoreChain link chosen by the user.
- Removing a Store archives it (**decided**): it is hidden from future selection and historical trips/assignments that referenced it are never deleted.

#### FR-5: Client-side StoreChain matching
When entering a Store name, the app suggests a StoreChain from cached reference data, editable by the user before submit.
**Consequences (testable):**
- The StoreChain reference list is available offline after first load (cached).
- The suggestion is advisory: the user can accept, change, or clear the chain link.
- The final submitted Store carries only the user-confirmed chain link (or none).

#### FR-28: `[POST-MVP — backlog]` Geolocation-assisted Store discovery
When adding a Store, a Member can pick from real nearby branches of a StoreChain (type "Edeka" → see Edeka locations near them) instead of typing a free-form name.
**Consequences (testable):**
- Suggestions derive from the device's current location plus the chosen/matched StoreChain; the Member confirms before the Store is created.
- Free-form entry (FR-4) and the client-side chain match (FR-5) remain available — geolocation is an accelerator, never the only path.
- The geodata source must satisfy the **no-foreign/US-service** privacy principle (§5 Non-Goals) — see Open Question §8.10.
**Notes:**
- `[NOTE FOR PM]` Kept as backlog, *not dropped*, precisely because it's feasible without foreign services. The obvious source (Google Places) violates the no-US-cloud stance; the privacy-friendly alternative is **OpenStreetMap** POI data via a **self-hosted Overpass API / Nominatim** — supermarket brands are tagged (`shop=supermarket`, `brand=Edeka`), the data is EU/open (ODbL), and self-hosting keeps location lookups inside SGART's own boundary. Feasibility + license + data-quality-per-market to be confirmed (Open Q8.10).

### 4.3 Shopping Lists

**Description:** A Household can have several **Open** Lists at once — real households run parallel lists ("Monday Edeka run", "big monthly shop"). A List's name is optional; when absent the UI shows an auto-generated "Liste {N}" based on creation order among non-Done lists. Items carry quantity, unit, and an optional note, and are keyed by name+note so "Milk (Bio)" and "Milk (normal)" coexist but accidental exact duplicates don't. Items move through Open → Done / Postponed. Postponing can drop an item onto another List (existing or newly created) or just flag it. Because the whole product only earns its keep if capture is faster than a scribbled note, **adding an item is optimized for speed** — autocomplete of known articles with attribute prefill (FR-27) is a core part of this feature, not a nicety.

**Functional Requirements:**

#### FR-6: Multiple Lists per Household
Any Member can create, rename, and hold multiple Open Lists simultaneously. Realizes UJ-2.
**Consequences (testable):**
- More than one List may be in state Open at once.
- A List's name is optional; unnamed Lists display as "Liste {N}", where N is position by creation time among Lists in state Open or In-Trip (Done excluded). The number is a derived display value, not stored.
- A Member can set/change a List's name at any time (except when Done).

#### FR-7: Manage Items on a List
Any Member can add, update, and remove Items. Realizes UJ-2.
**Consequences (testable):**
- Item quantity must be > 0.
- Adding an Item whose name+note exactly matches an existing Item on the same List is rejected/flagged.
- Update may change quantity, unit, and/or note.

#### FR-27: Fast item entry with autocomplete & attribute prefill
As a Member types an Item name, the app autocompletes from article names already used in the Household and prefills that article's typical attributes (unit, and where known its note and default Store), so adding a known article takes minimal taps. Realizes UJ-2.
**Consequences (testable):**
- Typing suggests matching previously-used Item names in the Household (prefix and/or fuzzy), ranked by recency/frequency.
- Selecting a suggestion prefills the last-used unit (and note / default Store Assignment where known); the Member can override any prefilled value before adding.
- Adding a brand-new, never-seen name works with no suggestion and no extra steps — autocomplete never blocks free entry.
- Suggestions are Household-scoped and private — never drawn from or leaked across other Households.
**Feature-specific NFRs:**
- **Fast capture is a primary usability goal.** Adding a known article should take on the order of a couple of taps, and suggestions must appear with no perceptible lag — i.e. served from a local/cached lookup, not a blocking round-trip.
**Notes:**
- MVP backs autocomplete with a lightweight read-model over the Household's own Item history (distinct names + last-used attributes). It does **not** require the Post-MVP Product catalog. When Products (FR-21) arrive, autocomplete can upgrade to be Product-backed — aliases, and a pinned Store becoming the prefilled default Store.
- `[NOTE FOR PM]` Deliberately distinct from Post-MVP *smart/predictive* item suggestions (recommending what you might need from shopping patterns). FR-27 is deterministic type-ahead over your own history, not prediction — cheap, and the right MVP lever for "easy & fast."

#### FR-8: Item status transitions (off-trip)
Any Member can check, uncheck, and postpone an Item on an Open List. Realizes UJ-3.
**Consequences (testable):**
- Check sets Item Status = Done; uncheck returns it to Open.
- Postpone optionally targets another List: if a target is chosen, the Item is added to that List; if a new List is requested, it is created first; if none, the Item is flagged Postponed in place.
- A Done List accepts no further Item commands.

#### FR-9: `[POST-MVP — fast-follow]` Reuse — duplicate & template
A Member can duplicate a List and archive a List as a reusable template.
**Consequences (testable):**
- An empty List cannot be duplicated or archived.
- Duplicating produces a new Open List with the same Items in Open status.
**Notes:**
- **Decided (deferred, not dropped):** FR-9 is valued functionality — recurring/weekly lists are a real household pattern — but it is not load-bearing for the core loop (UJ-2/3/4 stand without it), so it moves out of MVP to the **first fast-follow**. It stays fully specified here so the data model (list duplication, template archival) can be anticipated in architecture and not painted into a corner.

### 4.4 Real-Time Collaboration & Offline Sync

**Description:** The property that makes SGART worth more than a shared note is that everyone's view is live and correct. Changes any Member makes propagate to all connected Members' devices in near-real-time (UJ-2). When a device is offline, actions are captured locally and replayed on reconnect, with the UI honestly showing "X changes not yet synchronized" (UJ-3 edge case). MVP deliberately scopes *conflict handling* out: the offline model is best-effort replay, not multi-writer merge — see the pressure-test note.

**Functional Requirements:**

#### FR-10: Live Sync of Household changes
Changes to Lists, Items, and Trips are pushed to all connected Members of the Household in near-real-time. Realizes UJ-2, UJ-3.
**Consequences (testable):**
- A change made by one Member appears on another connected Member's device without a manual refresh, within a few seconds under normal connectivity.
- The client re-establishes the live connection automatically after a drop and reconciles current state.

#### FR-11: Offline Queue
Actions taken while offline are stored locally and replayed when connectivity returns; the UI surfaces the pending count. Realizes UJ-3 (edge case).
**Consequences (testable):**
- With no connectivity, item check/uncheck/add still succeed locally and show as pending.
- On reconnect, queued actions are sent in order; the pending indicator clears as they confirm.
- Replayed actions are idempotent — a retry after an ambiguous failure does not double-apply.
- Genuine conflicts (a queued action targeting state someone else changed meanwhile) are surfaced to the Member rather than silently overwritten — see FR-26.
**Notes:**
- **Decided (Open Q8.3):** MVP does *not* ship silent last-writer-wins, but it also does *not* ship automatic merge. It ships bounded conflict **surfacing** (FR-26) — cheap because event-sourced optimistic concurrency does the detection.

#### FR-26: Offline conflict surfacing (detect & ask — no auto-merge)
When a queued offline action targets an Item or List that another Member changed while the first Member was offline, the app surfaces the conflict and lets the Member decide, instead of silently overwriting (LWW) or attempting an automatic merge. Realizes UJ-3 (edge case).
**Consequences (testable):**
- On replay, the server rejects a queued command whose target aggregate advanced past the version the command was based on (optimistic concurrency), rather than blindly applying it.
- The client surfaces a per-item inline notice — e.g. "Milk was changed/removed while you were offline" — with a **coarse two-way choice: re-apply my change to the current state, or discard it**. No field-level reconciliation.
- **Convergent/idempotent actions do not prompt.** If both Members' actions lead to the same result (e.g. both check off the same Item), it resolves silently — only genuine divergence (item removed vs. checked; competing quantity edits) prompts.
**Out of Scope:** *(stays Post-MVP — this is where the complexity lives)*
- Automatic / field-level / three-way merge.
- Presence & heartbeat ("Anna was last seen 5 min ago").
- Delivery-status tracking per Member ("not yet received by Anna").
- Real-time "who is editing this now" indicators.
**Notes:**
- `[NOTE FOR PM]` This deliberately keeps MVP simple: the server-side cost is optimistic-concurrency on command handling (native to the event store); the client-side cost is one inline prompt with two buttons. Everything genuinely hard about collaborative conflict handling stays deferred.

### 4.5 Shopping Trips

**Description:** A Trip is the act of shopping one List across selected Stores, and it supports three equally valid modes — analog (print & shop, confirm later, UJ-4), digital-active (check off live, UJ-3), and hybrid. Starting a Trip presents the List grouped by Store, with unassigned Items in their own section. During the Trip a Member can reroute Items between Stores (including spontaneously adding a Store), check items off, or postpone. Completion is a short guided dialog: are you done? did you get everything? (transfer/discard the leftovers) — and, Post-MVP, would you like to scan receipts? Completion is always user-triggered; nothing forces it.

**Functional Requirements:**

#### FR-12: Start a Trip against a List
A Member can start a Trip from an Open List, selecting one or more Stores. Realizes UJ-3, UJ-4.
**Consequences (testable):**
- A Trip requires a linked List and at least one Store.
- Starting a Trip moves the List to state In-Trip.
- At most one Active Trip may exist per List at a time.

#### FR-13: Store-grouped Trip view with Store Assignment
The Trip presents Items grouped by Store, with a "Not yet assigned" section, and lets Members assign/reroute Items. Realizes UJ-3.
**Consequences (testable):**
- Items with no Store Assignment appear under "Not yet assigned."
- A Member can assign any Item to any Store in the Trip, before or during it.
- Reroute moves an Item to another Store already in the Trip, or to a Store the Member spontaneously adds to the Trip.
**Out of Scope:**
- Automatic routing suggestions based on price history (Post-MVP, §4.9).

#### FR-14: In-Trip item actions
During an Active Trip a Member can check, uncheck, and postpone Items. Realizes UJ-3.
**Consequences (testable):**
- Check/uncheck during a Trip updates Item Status and syncs live.
- Postponing during a Trip routes the Item to an existing Open List, or creates one if none exists, on completion review.

#### FR-15: `[DROPPED]` Pause & resume a Trip
*Cut from the product, not merely deferred.* A Trip has no pause/resume; its lifecycle is **Active → Done** only.
**Notes:**
- **Decided (dropped):** the "didn't finish shopping today, continue tomorrow" need is fully met by completing the Trip and transferring unbought Items to a new List (FR-16) — real shoppers start a fresh list next day rather than resuming a stale trip. Pause/resume is redundant, so it is removed rather than parked: there is no **Paused** state, and no "at most one Active-*or-Paused*" wording. `[Architecture: this supersedes the earlier "backlog" treatment; see ARCHITECTURE-SPINE.md.]`

#### FR-16: Complete a Trip with leftover review
Completion is a guided, user-triggered dialog that reviews open Items and closes the Trip. Realizes UJ-3, UJ-4.
**Consequences (testable):**
- The dialog asks confirmation ("finished?"), then for each remaining open Item offers TRANSFER (to which List — existing or new) or DISCARD.
- TRANSFER decisions place Items on the chosen/new List automatically; DISCARD drops them.
- On completion the List moves to Done and becomes immutable; a Trip is never force-completed by the system.
- Post-MVP: a receipt-scan step is offered per visited Store before final completion (§4.8).

### 4.6 Print & Share

**Description:** Because analog shopping is a first-class mode (UJ-4), SGART prints. The print view uses the same grouped-by-store layout as the Trip view and goes straight through the phone's native print dialog — no PDF is persisted on the device. A secondary "Share as PDF" generates a document in memory for sharing (e.g. WhatsApp) without saving it.

**Functional Requirements:**

#### FR-17: Print a List / Trip
A Member can print the current List using the native OS print dialog, in the grouped-by-store layout. Realizes UJ-4.
**Consequences (testable):**
- The printout groups Items by Store with a "Not yet assigned" section, matching the on-screen Trip layout.
- No PDF file is written to device storage as part of printing.

#### FR-18: Share as PDF (in-memory)
A Member can share the List as a PDF generated in memory. Realizes UJ-4 (edge case).
**Consequences (testable):**
- The shared PDF is passed to the OS share sheet without being persisted by the app.

### 4.7 Notifications

**Description:** Notifications keep Members coordinated without nagging (JTBD "coordinate without nagging"). Every push is a **content-free ping**: it carries no grocery data; it only prompts the app to fetch the real state from the Household's own server. "List changed" pings are debounced so a burst of edits yields at most one ping per List per interval. MVP ships a fixed, sensible default set; per-Member per-Household configuration of these settings is Post-MVP.

**Functional Requirements:**

#### FR-19: Content-free push notifications
The system sends content-free pings for the MVP default notification set and never includes Household data in the push payload.
**Consequences (testable):**
- Push payloads contain no item/list/receipt content — only enough to route the app to fetch.
- MVP defaults: invitation received (always on), list changed (debounced, ~5 min), trip started/completed. Receipt-scanned and per-setting configuration are Post-MVP.
- "List changed" is debounced to at most one ping per List per debounce interval regardless of edit volume.
**Feature-specific NFRs:**
- Pings route through platform infrastructure (FCM/APNs) via a swappable push adapter; the content-free design keeps grocery data off third-party infrastructure even so. **Decided:** routing a bare content-free ping through Google/Apple is an accepted carve-out to the no-US-cloud rule for MVP; a self-hosted path (UnifiedPush) is a public-phase option (Open Q §8.5).

### 4.8 Localization & Language

**Description:** SGART launches **German-first** (`de-DE`) but is built multilingual from day one, because the intended audience includes non-native-German speakers living in Germany and, later, users in other European markets (Netherlands, France, and beyond). "Multilingual from day one" is mostly an architecture obligation for MVP: no hard-coded UI strings, locale-aware formatting for currency (€), dates, quantities, and decimals (e.g. "1,09 €" with a comma decimal in `de`/`nl`/`fr`), and localizable Keycloak invite emails. The MVP *ships* German UI; additional UI languages (Dutch, French) and per-market StoreChain reference data are Post-MVP, but nothing in MVP may block them. The user's Locale is chosen in-app (defaulting to the device locale, falling back to German).

**Functional Requirements:**

#### FR-24: German-first, internationalization-ready UI
The MVP ships a complete German UI, and all user-facing text and formatting is locale-driven rather than hard-coded.
**Consequences (testable):**
- The MVP UI is fully German with no untranslated/placeholder strings in primary flows.
- No user-facing string is hard-coded in code paths; all resolve through a localization layer keyed by Locale.
- Currency, date, number, and quantity formatting follow the active Locale (e.g. `de-DE` renders "1,09 €", not "€1.09").
- Adding a new UI language is a resource/translation task, not a code change. `[ASSUMPTION: MVP defaults Locale from the device setting and falls back to de-DE when unsupported.]`

#### FR-25: Locale selection
A Member can view and change their Locale in-app; the choice is per user.
**Consequences (testable):**
- Changing Locale updates UI language and formatting without reinstall.
- `[ASSUMPTION: Locale is a per-user preference, not per-Household — different Members may read the same Household in different languages.]`

**Notes:**
- `[NOTE FOR PM]` Country vs. language are separable and both matter: a French-speaker in Germany wants French UI **and** German (Edeka/Aldi) StoreChains. MVP handles language (German); per-market StoreChain data and additional languages are Post-MVP (Open Question §8.8).

### 4.9 Receipts & OCR *(Post-MVP)*

**Description:** Post-MVP, a Trip's completion offers to scan each visited Store's receipt with the camera. OCR runs on-device first (with a pluggable path to a self-hosted or EU provider later), parses line items and prices, matches them against the List, and shows a single confirmation summary — matched items pre-checked, unmatched lines flagged for "assign or ignore." One confirmation, not per-item. Confirmed receipts are immutable and feed Price Observations. Duplicate receipts are detected by fingerprint and surfaced for user confirmation, never auto-rejected.

**Functional Requirements:**

#### FR-20: `[POST-MVP]` Scan, parse & confirm a Receipt
A Member can scan a Store's Receipt during/after a Trip; the app OCRs and parses it on-device and asks for a single confirmation. Realizes UJ-5.
**Consequences (testable):**
- A Receipt is always tied to a Trip and a specific Store; only one Receipt per Store per Trip.
- Confirmation is a single summary screen; matched items are bulk-checked and unmatched lines are individually resolvable.
- A confirmed Receipt is immutable.
- Suspected duplicates (by fingerprint) prompt the user rather than being silently dropped.

### 4.10 Price Intelligence & Routing *(Post-MVP)*

**Description:** Confirmed Receipts turn into Price Observations on Products. From that history SGART offers Pinning (bind a Product to a Store so future Items default there) and, further out, automatic routing suggestions during trip planning ("coffee is usually cheaper at Netto — add Netto to this trip?"). Suggestions are always confirmed by the user; the household's price data never leaves the household.

**Functional Requirements:**

#### FR-21: `[POST-MVP]` Product price history & Pinning
Confirmed Receipts record store-scoped Price Observations per Product; a Member can pin a Product to a Store. Realizes UJ-5.
**Consequences (testable):**
- Every Price Observation carries a Store; no price is recorded without one.
- A Product is pinned to at most one Store; re-pinning requires unpin-then-pin (handled atomically when done via an in-trip "always use this store" override).

#### FR-22: `[POST-MVP]` Price-based routing suggestions
During trip planning SGART may suggest a Store for an Item based on price history; the user confirms before it applies.
**Consequences (testable):**
- Suggestions are advisory and never auto-applied.

### 4.11 Dashboard & Reporting *(Post-MVP)*

**Description:** A read-only analytics surface: spend per month/quarter, spend per Store and per StoreChain, and price history per article per Store (e.g. "Milk" across all stores over time). Strictly analytical — no actions are triggered from the dashboard; routing lives in the Trip flow, not here.

#### FR-23: `[POST-MVP]` Analytics dashboard
A Member can view spend and price-history analytics for their Household. Realizes UJ-5.
**Consequences (testable):**
- All dashboard views are read-only.
- Aggregation is available at both Store and StoreChain level.

## 5. Non-Goals (Explicit)

- **Not a budgeting / personal-finance app.** SGART surfaces grocery spend as a byproduct of receipts; it does not do envelopes, income, or non-grocery expenses.
- **Not a retailer/merchant platform.** No merchant accounts, no shared/public price database — all price data is private to a Household.
- **Not a barcode inventory / pantry tracker.** SGART tracks what to buy and what was bought, not what's in your cupboard.
- **Not a meal planner or recipe app.**
- **No social feed, discovery, or public sharing of lists.**
- **No US cloud dependency, ever.** Self-hosted, German-hosted, no US hyperscaler in the data path.
- **No full web client in v1.** Web is invite-acceptance fallback only.
- **No ads and no monetization in MVP.** (Public-phase monetization is an Open Question, not a v1 goal.)
- **No public self-signup in MVP.** Friends-&-family cohort only.

## 6. MVP Scope

### 6.1 In Scope

- Household create + email invite + roles (Admin / Member-role) + membership lifecycle (FR-1–FR-3).
- Store management with client-side StoreChain matching (FR-4–FR-5).
- Multiple Open Lists, items with quantity/unit/note, check/uncheck/postpone, auto-naming (FR-6–FR-8).
- Fast item entry: history-backed autocomplete with attribute prefill (FR-27) — a core usability lever.
- Real-time Live Sync + Offline Queue with bounded conflict surfacing (FR-10–FR-11, FR-26).
- Trips: start, store-grouped view, manual assignment/reroute, in-trip actions, completion with leftover review (FR-12–FR-14, FR-16).
- Print via native dialog + in-memory Share-as-PDF (FR-17–FR-18).
- Fixed default content-free notifications (FR-19).
- German-first UI, internationalization-ready architecture, in-app Locale selection (FR-24–FR-25). Solo (household-of-one) use fully supported alongside multi-person households.
- Platforms: iOS + Android (Flutter). Web = invite-acceptance fallback only.

### 6.2 Out of Scope for MVP

- **List duplicate & template (FR-9)** — deferred to the **first fast-follow**, *not dropped*. *Reason:* valued (recurring weekly lists are a real pattern) but not load-bearing for the core loop; keeping it out trims MVP list-lifecycle surface while the FR stays specified so architecture can anticipate it.
- **Trip pause/resume (FR-15)** — **dropped** (not backlog). *Reason:* redundant with the FR-16 leftover-transfer flow (cross-day shopping = new list, not a resumed trip). Trip State is permanently Active→Done; there is no Paused state.
- **Receipts & OCR (FR-20)** — the whole scanning/parsing pipeline. *Reason:* biggest technical unknown; the shared-list value stands without it. `[NOTE FOR PM] Emotionally load-bearing — it's half the product's identity (UJ-5) and the long-term success signal. First fast-follow candidate.`
- **Price intelligence, Pinning, routing suggestions (FR-21–FR-22)** — depend on Receipts.
- **Dashboard & reporting (FR-23)** — depends on price data.
- **Configurable notification settings & receipt-scanned notifications** — MVP ships fixed defaults.
- **Automatic/field-level merge, presence, delivery-status tracking** — MVP surfaces conflicts for a coarse keep/discard decision (FR-26) but does not auto-merge or track presence/delivery (see FR-26 Out of Scope).
- **Standalone receipt scan (no trip)**, **store logos from chain data**, **cross-device presence**.
- **Smart / *predictive* item suggestions** (recommending what you might need from shopping patterns, seasonality, etc.). *Distinct from* MVP autocomplete (FR-27), which is deterministic type-ahead over your own history — that ships in MVP.
- **Additional UI languages (Dutch, French, …) and per-market StoreChain data.** *Reason:* MVP is German-first and validates with a German-speaking friends-&-family cohort; the architecture is i18n-ready (FR-24) so these are translation/data tasks, not rework. `[NOTE FOR PM] First localization fast-follow once the core loop holds.`
- **Geolocation-assisted Store discovery (FR-28)** — backlog, *not dropped*; gated on selecting a privacy-friendly geodata source (Open Q8.10). MVP adds Stores by free-form name + client-side chain match.
- **Full web client** and **public self-signup / growth mechanics**.

## 7. Success Metrics

*Dual-horizon, matching the stated success signal (habitual use now; cost insight later). Targets are calibrated to a small friends-&-family cohort, not public scale.*

**Primary**
- **SM-1 — Habitual use.** ≥ 60% of onboarded friends-&-family households complete at least one real Trip per week for 4 consecutive weeks. Validates FR-6–FR-8, FR-12–FR-16.
- **SM-2 — Collaboration actually happens.** *Among multi-person households* (solo households are excluded from this metric): in ≥ 50% of active multi-member household-weeks, ≥ 2 distinct Members edit the same List within a 24-hour window. Validates FR-10 (Live Sync is the differentiator, not a checkbox).

**Secondary**
- **SM-3 — Beta reach & feedback.** ≥ 5 friends-&-family households onboarded and each returns structured feedback at least once. Validates FR-1–FR-3 (onboarding/invite actually works for non-builders).
- **SM-5 — Capture is fast.** Adding a previously-used article takes ≤ ~2 taps/interactions and the median add-item interaction stays under a few seconds; ≥ 50% of item-adds in active households use an autocomplete suggestion. Validates FR-27 (the "easy & fast" goal is real, not aspirational).
- **SM-4 — (Horizon 2) Cost-insight adoption.** Once Receipts ship: ≥ 40% of completed Trips have a confirmed Receipt, and price history is viewed by ≥ 1 Member per household per month. Validates FR-20–FR-21, FR-23.

**Counter-metrics (do not optimize)**
- **SM-C1 — Notification restraint.** Pushes per active Member per week should stay low (target ≤ ~5). A rising number means SGART is nagging. Counterbalances any push to "increase engagement" via FR-19.
- **SM-C2 — Sync integrity over feature breadth.** Observed lost-update / duplicate-item incidents from offline replay must trend to ~0. Do not trade this for more list features. Counterbalances SM-2 and guards FR-11.
- **SM-C3 — Don't confuse setup for value.** Households created but with zero completed Trips is a vanity signal; track it as anti-signal, not success. Counterbalances SM-3.

*Given no fixed deadline, these are directional health checks for the beta, not release gates.*

## 8. Open Questions

1. **GDPR erasure vs. immutable event log.** How is a Member's right-to-be-forgotten honored when their user id is embedded across an append-only event history? (pseudonymization at ingest? crypto-shredding the id? tombstoning?) — data-governance decision for architecture. Blocks nothing in the shared-list MVP but must be answered before public rollout.
2. **Public-phase monetization.** If SGART becomes a public product, what funds the self-hosted infrastructure? (paid hosting tier, one-time app fee, donations?) Out of scope for MVP; noted so it isn't a silent gap.
3. *(Resolved.)* MVP offline conflict policy → **bounded conflict surfacing** (FR-26): detect via optimistic concurrency, prompt the user with a coarse keep/discard choice, no auto-merge and no presence/delivery tracking.
4. **OCR provider path (Post-MVP).** When on-device OCR is outgrown, which EU/self-hosted provider preserves the no-US-cloud guarantee?
5. *(Resolved.)* **Push infrastructure vs. privacy stance.** MVP accepts **content-free pings via FCM/APNs** as a carve-out to the no-US-cloud rule (the payload carries no household data — only a wake-and-fetch signal). Push sits behind a **swappable adapter**; a self-hosted path (e.g. UnifiedPush) is a public-phase option, not an MVP requirement.
6. **Web client scope for the public phase** — does "public product" eventually require a real web client, or stay mobile-only?
7. *(Resolved.)* FR-9 (duplicate/template) → deferred to first fast-follow, kept in spec. FR-15 (pause/resume) → **dropped** from the product (redundant with FR-16 leftover transfer); Trip State is permanently Active→Done.
8. **Localization roadmap — which languages and markets, in what order?** German is MVP. Which comes next: additional *languages* for users in Germany (e.g. French/Turkish/Ukrainian UI over German StoreChains), or full *market* expansion (NL/FR with their own StoreChain data)? These are separable (§4.8 note) and drive when per-market StoreChain reference data is needed.
9. **Where does StoreChain reference data come from per market?** For MVP (Germany) a static seed is fine; multi-market needs a sourcing/maintenance plan for each country's chains.
10. **Privacy-friendly geodata source for FR-28.** Confirm OpenStreetMap (self-hosted Overpass/Nominatim) as the store-location source over Google Places: verify POI/brand coverage per market, licensing (ODbL attribution), and the self-hosting/operational cost. This is the blocker that keeps FR-28 in backlog rather than MVP.

## 9. Assumptions Index

- **§4.1 / FR-3** — *(Decided.)* Member display name/email shown in the UI is read live from Keycloak/JWT claims at display time, never persisted in SGART's own model (hardened by ARCHITECTURE-SPINE AD-6).
- **§4.2 / FR-4** — *(Decided.)* Removing a Store archives it — hidden from future selection, with historical trips/assignments that referenced it never deleted.
- **§4.4 / FR-11, FR-26** — MVP offline replay uses optimistic concurrency to *detect* conflicts and surfaces a coarse keep/discard choice; no automatic merge, presence, or delivery-status tracking in MVP. `[ASSUMPTION: coarse two-way keep/discard is sufficient UX for the beta; validate with friends-&-family before investing in richer resolution.]`
- **§4.7 / FR-19** — *(Decided.)* Routing a bare content-free ping through Google/Apple (FCM/APNs), behind a swappable adapter, is an accepted carve-out to the no-US-cloud rule for MVP; self-hosted push (UnifiedPush) is a public-phase option.
- **§4.8 / FR-24** — MVP defaults Locale from the device setting and falls back to `de-DE` when the device locale is unsupported.
- **§4.8 / FR-25** — Locale is a per-user preference, not per-Household — Members may read the same Household in different languages.
