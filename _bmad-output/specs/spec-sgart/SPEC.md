---
id: SPEC-sgart
companions:
  - glossary.md
  - ../../planning-artifacts/architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md
  - ../../../CLAUDE.md
sources:
  - ../../planning-artifacts/prds/prd-sgart-2026-08-06/prd.md
  - ../../planning-artifacts/prds/prd-sgart-2026-08-06/addendum.md
  - ../../../docs/SGART.md
---

> **Canonical contract.** This SPEC and the files in `companions:` are the complete, preservation-validated contract for what to build, test, and validate. Source documents listed in frontmatter are for traceability only — consult them only if you need narrative rationale or prose color this contract intentionally omits.

# SGART — Smart Grocery And Receipt Tracker

## Why

A **vision to realize**, with an **opportunity** behind it. Households keep forgetting things and buying duplicates because their shopping list lives in three places — a paper list, a whiteboard, "add milk 🥛" WhatsApp messages. SGART replaces all of that with **one shared list that everyone edits at once and that stays correct while people are actually standing in the store**, walkable digitally, on paper, or any mix. The longer-term opportunity is intelligence about money: every confirmed purchase eventually becomes a private, household-scoped record of what things cost where — turning routine shopping into a quiet cost-awareness tool. Privacy is a first-class product property, not a compliance afterthought: the system is self-hosted in Germany with no US-cloud dependency, identity is delegated to a self-hosted Keycloak, and personal data never enters the event log. The MVP launches to a curated **friends-&-family cohort**; a **household of one is a first-class case**, not an edge case.

## Capabilities

- **CAP-1 — Households & first-run routing**
  - **intent:** An authenticated person with no Household can create one (becoming its first Admin) or wait for an invite, and is routed correctly on launch by how many Households they belong to.
  - **success:** After creation the creator is a Member with role Admin; a person with zero Households sees the create/await-invite choice, with exactly one goes straight in, with several sees a selection screen.

- **CAP-2 — Invite & membership lifecycle with roles**
  - **intent:** Any Member can invite a person by email; the invitee authenticates via the link and joins; `HouseholdRole` (Admin / Participant) gates only governance actions while both roles do all daily work.
  - **success:** A second pending invite to the same email is rejected; an expired invite cannot be redeemed; accepting adds a Member with role Participant; the last Admin cannot leave, be removed, or be demoted; only an Admin can remove other Members, promote to Admin, or delete the Household.

- **CAP-3 — Store management with client-side chain matching**
  - **intent:** Any Member can add and remove the Household's Stores by free-form name, with an advisory StoreChain suggestion matched client-side as they type.
  - **success:** Store name is unique per Household (duplicate rejected); the chain link is optional and user-confirmed (accept / change / clear); the StoreChain reference list works offline after its first cached load; removing a Store archives it (hidden from future selection) without deleting historical Trips or assignments that referenced it.

- **CAP-4 — Multiple shopping lists with optional auto-naming**
  - **intent:** A Household can hold several Open Lists at once, each with an optional name.
  - **success:** More than one Open List is allowed; an unnamed List displays as "Liste {N}" by creation order among Open/In-Trip Lists (derived, never stored); a List's name can be set or changed anytime except when Done.

- **CAP-5 — Item management & off-trip list organization**
  - **intent:** Any Member can add, update, and remove Items (name, Quantity, optional note) on an Open List, and **move** an Item to another (existing or new) List. Buying transitions (check / uncheck → Done / Open) and **Postpone** happen only within a Trip (CAP-9 / CAP-10), so a **Done** status always carries a Store/Trip context.
  - **success:** Quantity > 0; an exact name+note duplicate on the same List is rejected; a Member can move an Item to another existing List or a newly created List (created first); off-trip an Item stays **Open** (no check/uncheck and no Postpone outside a Trip); a Done List accepts no Item commands.

- **CAP-6 — Fast item entry with autocomplete & attribute prefill**
  - **intent:** As a Member types an Item name, the app autocompletes from the Household's own past Item names and prefills that article's typical attributes (unit, note, default Store) so a known article takes minimal taps.
  - **success:** Suggestions are prefix/fuzzy, Household-scoped and private, served lag-free from a local/cached lookup; selecting one prefills last-used attributes (each overridable); a brand-new, never-seen name adds with no suggestion and no extra steps.

- **CAP-7 — Real-time live sync**
  - **intent:** Changes to Lists, Items, and Trips propagate to all connected Members' devices in near-real-time without a manual refresh.
  - **success:** A change made by one Member appears on another connected Member's device within a few seconds under normal connectivity; the client automatically re-establishes the live connection after a drop and reconciles current state.

- **CAP-8 — Offline queue with bounded conflict surfacing**
  - **intent:** Actions taken offline are captured locally, replayed in order on reconnect with a visible pending count, and genuine conflicts are surfaced for a coarse keep/discard choice rather than silently overwritten or auto-merged.
  - **success:** Offline check/uncheck/add succeed locally and show as pending; replay is idempotent (an ambiguous retry never double-applies); a queued command whose target aggregate advanced meanwhile is rejected and the Member gets an inline re-apply/discard choice; convergent/idempotent actions resolve silently.

- **CAP-9 — Shopping trips: start, store-grouped view, routing & in-trip actions**
  - **intent:** A Member can start a Trip from an Open List across one or more selected Stores, see Items grouped by Store (with a "Not yet assigned" section), assign or reroute Items between Stores (including a spontaneously added Store), and check / uncheck / postpone Items during the Trip.
  - **success:** A Trip requires a linked List and ≥ 1 Store and moves the List to In-Trip; at most one Active Trip exists per List; unassigned Items appear under "Not yet assigned"; a Member can assign any Item to any Store in the Trip and reroute it; in-trip check/uncheck/postpone sync live.

- **CAP-10 — Complete a trip with leftover review**
  - **intent:** Trip completion is a guided, user-triggered dialog that reviews remaining open Items and closes the Trip.
  - **success:** The dialog confirms "finished?", then per remaining open Item offers TRANSFER (to an existing or new List) or DISCARD; TRANSFER places Items automatically; on completion the List moves to Done and becomes immutable; the system never force-completes a Trip.

- **CAP-11 — Print & share the list**
  - **intent:** A Member can print the current List via the native OS print dialog in the grouped-by-store layout, and secondarily share it as an in-memory PDF.
  - **success:** The printout groups Items by Store with a "Not yet assigned" section matching the on-screen layout; no PDF is persisted to device storage in either the print or share path.

- **CAP-12 — Content-free notifications**
  - **intent:** The system keeps Members coordinated via push pings that carry no Household data and only prompt the app to fetch real state from the Household's own server, on a fixed MVP default set.
  - **success:** Push payloads contain no item/list/receipt content; defaults are invitation received (always on), list changed (debounced to at most one ping per List per ~5-minute interval), and trip started/completed; delivery goes through a swappable push adapter (MVP: FCM/APNs).

- **CAP-13 — German-first, internationalization-ready UI with locale selection**
  - **intent:** The MVP ships a complete German UI with all user-facing text and formatting locale-driven rather than hard-coded, and a Member can view and change their own Locale in-app.
  - **success:** No untranslated/placeholder strings in primary flows; no user-facing string hard-coded (all resolve through a localization layer keyed by Locale); currency/date/number/quantity formatting follows the active Locale (`de-DE` renders "1,09 €"); changing Locale updates language and formatting without reinstall; Locale is per-user, defaulting from the device and falling back to `de-DE`.

- **CAP-14 — Data-subject rights: erasure & export**
  - **intent:** A person's personal data can be located, exported, and erased on request — honoring DSGVO right-to-erasure and portability despite an immutable event log.
  - **success:** Erasure destroys the Identity-ACL mapping, scrubs read models and device/offline caches, and deletes the Keycloak account, leaving only unlinkable pseudonyms in the log with no personal data recoverable; a person's data can be exported in a portable form; erasure, export, and retention each have explicit tests.

## Constraints

- **No US-cloud dependency, ever.** Self-hosted on a dedicated German/EU server with no US hyperscaler in the data path. Bends hosting, provider, push, OCR, and geodata choices.
- **DSGVO by design.** Personal data is minimized; **events carry no personal data** — a Household-scoped pseudonymous `MemberId` only, never `keycloakUserId`, email, or name (display name/email are read live from Keycloak/JWT claims, never persisted); erasure is by de-linking without rewriting history. Bends the entire data model and event schema. *(See ARCHITECTURE-SPINE AD-5/6/7.)*
- **Content-free push through a swappable adapter.** Push payloads carry no Household data — only a wake-and-fetch signal — which is why routing them through FCM/APNs is an accepted carve-out to the no-US-cloud rule for MVP. Push is a swappable port; a self-hosted path (UnifiedPush) is a public-phase option.
- **Identity delegated to Keycloak (self-hosted).** SGART stores no credentials and references people only by an opaque id taken from the JWT — never from a request body or path.
- **Fixed architecture paradigm.** DDD + CQRS + Event Sourcing, hexagonal, in a modular monolith; state changes only via command → aggregate → event under optimistic concurrency; read models are projection-only. The binding contract is `ARCHITECTURE-SPINE.md` (adopted companion). Bends every backend design decision.
- **Clean Code + binding ubiquitous language.** No abbreviations; names reflect purpose; one term, one meaning (`glossary.md` is binding); TDD with domain-first fast unit tests and synthetic data only. Full rules in `CLAUDE.md` (adopted companion).
- **Mobile-first.** iOS + Android via Flutter/BLoC. Web exists only as an invite-acceptance fallback in v1, never as a daily client.
- **Curated cohort; solo is first-class.** MVP onboards a friends-&-family cohort only, with no public self-signup; a household of one is fully supported alongside multi-person households.

## Non-goals

- **Not a budgeting / personal-finance app** — grocery spend surfaces as a byproduct; no envelopes, income, or non-grocery expenses.
- **Not a retailer/merchant platform** — no merchant accounts and no public/shared price database; all price data is private to a Household.
- **Not a pantry / barcode inventory tracker** — SGART tracks what to buy and what was bought, not what's in the cupboard.
- **Not a meal planner or recipe app.**
- **No social feed, discovery, or public sharing of lists.**
- **No full web client in v1**; **no ads or monetization in MVP**; **no public self-signup in MVP.**
- **Out of this build (Post-MVP shape reserved for coherence, not delivered):** receipts & OCR; price intelligence, Product pinning, price-based routing; analytics dashboard; configurable notification settings and receipt-scanned notifications; offline presence, delivery-status tracking, and automatic/field-level merge; geolocation store discovery; additional UI languages and per-market StoreChain data; list duplicate/template (fast-follow).
- **Trip pause/resume is dropped** (not deferred) — the Trip lifecycle is Active → Done; cross-day shopping is a fresh List via leftover transfer.

## Success signal

Real households from the friends-&-family cohort — solo and multi-person — adopt SGART as their actual shopping tool rather than trying it once. Concretely: **≥ 60% of onboarded households complete at least one real Trip per week for four consecutive weeks**, and among multi-person households, **in ≥ 50% of active household-weeks at least two distinct Members edit the same List within a 24-hour window** — proving the live-collaboration differentiator is real, not a checkbox.
