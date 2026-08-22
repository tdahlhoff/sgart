# SGART — Glossary (Ubiquitous Language)

Binding vocabulary for `SPEC.md` and all downstream work. The same concept has the same name in
domain code, events, read models, API, and UI. No abbreviations (per `CLAUDE.md` §2). Where this
glossary and the original PRD §3 differ, this file wins — it carries the architecture decisions.

- **Household** — The top-level tenant. Owns Members, Stores, Lists, Trips, and (Post-MVP) Products/Receipts. May have a single Member (solo use is first-class) or many. A person may belong to more than one Household. All data is scoped to a Household and private to it.
- **Member** — A person's *participation* in a specific Household. Identity is external (Keycloak); within SGART a Member is referenced only by a household-scoped pseudonymous **MemberId**. A person is a Member once per Household they join, with an unrelated MemberId in each.
- **MemberId** — The opaque, per-Household pseudonym used in every event and read model in place of any real identity. The Identity ACL is its sole minter and holds the only mapping back to a Keycloak account. (Never `keycloakUserId`, email, or name in the domain.)
- **HouseholdRole** — A Member's permission level: **Admin** or **Participant**. Both do all day-to-day work (lists, trips, invites); the role gates only governance actions (removing other Members, promoting to Admin, deleting the Household). *(Renamed from the PRD's overloaded "Member" role.)*
- **Store** — A concrete shopping location defined within a Household, with a free-form name (e.g. "Edeka Schiedemann"). Store names are unique within a Household. An entity inside the Household aggregate.
- **StoreChain** — Reference data identifying a retail brand (e.g. "Edeka", "Aldi"). A Store may optionally link to a StoreChain for chain-level grouping later. Shared read-only reference data, scoped per country/market. Not a Household entity.
- **Locale** — A Member's language + regional formatting (e.g. `de-DE`). Determines UI language and how currency, dates, quantities, and decimals display. Per-user, not per-Household. Distinct from the market that scopes StoreChains.
- **Shopping List** *(short: List)* — A named or auto-named collection of Items within a Household. Has a **List State**.
- **List State** — One of **Open** (being planned, no active trip), **In-Trip** (linked to an active Trip), **Done** (trip completed, archived, immutable).
- **Item** — A line on a List: name, **Quantity**, optional note, and an **Item Status**. Identified by name + note within a List (exact duplicates disallowed). An entity inside the ShoppingList aggregate.
- **Item Status** — One of **Open** (still to buy), **Done** (bought/checked off **during a Trip**), **Postponed** (deferred **during a Trip**, optionally moved to another List). Off-trip an Item is always **Open**; buying/deferring transitions require a Trip (CAP-5/CAP-9/CAP-10).
- **Quantity** — A value object: an amount + a **Unit** drawn from a controlled, extensible vocabulary (piece, gram, kilogram, millilitre, litre, pack, …). Never a free-text unit string.
- **Money** — A value object: integer minor units (cents) + ISO currency code. Never floating-point. MVP is EUR-only but currency is explicit. *(Used by Post-MVP price features.)*
- **Shopping Trip** *(short: Trip)* — An act of shopping one List across one or more selected Stores. Has a **Trip State**.
- **Trip State** — One of **Active** or **Done**. At most one Active Trip per List at a time. *(There is no Paused state — pause/resume was dropped; cross-day shopping is a new List via leftover transfer.)*
- **Store Assignment** — The linking of an Item to a Store for a Trip, so the Item appears under that Store in the grouped view. Unassigned Items appear under "Not yet assigned". A value object.
- **Move to List** — Off-trip relocation of an Item from one List to another (existing or newly created) List. Pure reorganization while planning — **not** a status change (the Item stays Open). Distinct from Postpone (a Trip-time deferral) and Reroute (a Trip-time Store change).
- **Postpone** — Deferring an Item so it is not bought now; occurs **within a Trip** (in-trip or at completion), optionally moving it to another (existing or new) List.
- **Reroute** — During a Trip, moving an Item's Store Assignment to a different Store already in (or spontaneously added to) the Trip. Distinct from Postpone.
- **Offline Queue** — Locally stored actions performed while offline, replayed to the server in order when connectivity returns; each carries the target aggregate root's version and a client command-id.
- **Live Sync** — Real-time propagation of Household changes to all connected Members' devices (via Server-Sent Events).
- **Identity ACL** — The Anti-Corruption Layer translating a Keycloak JWT into a Household-scoped MemberId; sole owner of the mapping and the single point erasure destroys.

## Post-MVP terms (reserved, not built)

- **Product** — A Household-scoped catalog entry (name + aliases) accumulating **Price Observations** and an optional **Pinning**.
- **Price Observation** — A recorded (Product, Store, price, date) fact, always Store-scoped, derived from a confirmed Receipt.
- **Receipt** — A scanned store receipt tied to a Trip and Store, processed via OCR into priced line items and confirmed; immutable after confirmation.
- **Pinning** — A durable preference binding a Product to a single Store, so future Items for that Product default to that Store.
