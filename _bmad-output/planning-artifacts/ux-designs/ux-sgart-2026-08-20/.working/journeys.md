# SGART Named-Protagonist Journeys

End-to-end journeys tying the personas (`personas.md`) to the key screens through the
information architecture (`ia.md`), grounded in the PRD's Key User Journeys (UJ-1…UJ-5,
prd §2.3). Each step names the screen/state it uses, the SPEC capability it exercises, and
the design decision that serves it. German appears only as quoted product UI labels.

Feeds `EXPERIENCE.md` at Finalize.

Key-screen references: **Onboarding** (`screen-onboarding.html`, F1–F4), **List detail**
(`screen-list-detail.html`, A/B), **Active trip** (`screen-active-trip.html`, A/B),
**Switcher** (`screen-switcher.html`, A/B), **IA map** (`ia-map.html`).

---

## J1 — Werner sets up the elder household  · *(PRD UJ-1, creator/Admin side)*

- **Who / context:** Werner, moderate-tech, no younger organizer to lean on. Fresh install.
- **Trigger:** first launch, authenticated (Keycloak), zero households.
- **Path:**
  1. **Welcome** → chooses „Haushalt erstellen" — two clear paths, privacy stated up front,
     no account pressure. `Onboarding F1` · CAP-1
  2. **Names it** „Rita & Werner"; reassured „den Namen kannst du später jederzeit ändern".
     `Onboarding F2` · CAP-1
  3. **Adds stores**: types „Edeka Schie…", accepts the advisory suggested chain „Edeka";
     adds „Netto". Chain never forced; step skippable. `Onboarding F3` · CAP-3
  4. **Invites Rita** by email — or „Später einladen". `Onboarding F4` · CAP-2
  5. Lands in the app on an (empty) **Listen overview**, ready to add items.
- **Outcome:** a working household Werner built entirely by himself.
- **Serves:** Werner (self-serve Admin path), onboarding gentleness, plain language.
- **Edge:** skips stores + invite → still lands in a fully usable app (solo works).

## J2 — Anna and Ben build the weekly list, live  · *(PRD UJ-2)*

- **Who / context:** Anna at home planning; Ben at work. One shared „Wocheneinkauf" list.
- **Path:**
  1. Anna opens the list, **fast-adds** „Milch" — autocomplete offers it with prefilled
     „2 L · zuletzt Edeka". `List detail B` · CAP-6
  2. Adds „Butter", „Kaffee 500 g — der dunkle" (note). `List detail A` · CAP-5
  3. Ben, on his phone, adds „Joghurt (4er)"; it appears on Anna's screen within seconds,
     no refresh. *Live sync* · CAP-7
  4. Anna assigns coffee→„Netto", milk/butter→„Edeka" via store chips; yoghurt stays
     „+ Geschäft" (unassigned). `List detail A` · CAP-5/9
  5. **Duplicate guard:** Ben types „Milch" while „Milch 2 L" exists → flagged, not doubled
     (keyed by name+note). CAP-5
- **Outcome:** one shared list, correct for both, some items grouped by store.
- **Serves:** fast-entry-as-hero, live sync, coordinate-without-nagging.
- **Edge:** Ben adds while offline → item queues locally with a pending count. CAP-8

## J3 — Ben shops digitally and transfers the leftovers  · *(PRD UJ-3)*

- **Who / context:** Ben stops at Edeka on the way home.
- **Path:**
  1. From the list he taps „Einkauf starten", selects „Edeka" + „Netto". `List detail A` · CAP-9
  2. **Active trip**, store-grouped; the list fills the screen. He checks off milk, butter.
     `Active trip A` · CAP-9 (list-is-hero, one-handed 48px rows)
  3. Netto is out of his coffee → he **postpones** it; decides yoghurt can wait. CAP-5/9
  4. **Loses signal** → check-offs queue, header shows „Offline · N"; syncs on return.
     `Active trip A` (sync chip) · CAP-8
  5. Scrolls to the **list end** → the quiet, non-sticky „Einkauf abschließen".
     `Active trip B` · CAP-10
  6. **Completion dialog**: „Fertig?" → for the two open items he chooses TRANSFER → a fresh
     Open list; the trip closes. CAP-10
- **Outcome:** trip Done + archived; a new Open list already holds the carried-over items.
- **Serves:** Ben, quiet completion, offline resilience, no commerce framing.
- **Edge:** a conflict (Anna changed the same item) surfaces inline as keep/discard. CAP-8

## J4 — Anna shops analog with a printout  · *(PRD UJ-4)*

- **Who / context:** Anna prefers paper; no phone out in the aisle.
- **Path:**
  1. Opens the list, taps „Drucken / Teilen" → native OS print in the grouped-by-store
     layout. `List detail A` (peer action) · CAP-11
  2. Shops with paper.
  3. Back home, opens the trip, confirms it complete; marks the one item she couldn't find
     as postponed. *Completion* · CAP-10
- **Outcome:** household state consistent whether or not the app was used in-aisle.
- **Serves:** Anna, analog=digital parity, easy post-hoc reconciliation.
- **Edge:** instead of printing she uses „Als PDF teilen" to WhatsApp (in-memory, never
  saved to storage). CAP-11

## J5 — Rita shops for her daughter, across households  · *(NEW — multi-household)*

*Not a PRD UJ, but the real-world scenario Timo surfaced; exercises the PRD invariant
"a user may belong to more than one Household".*

- **Who / context:** Rita, low tech-comfort, member of both her own household and Anna's.
- **Path:**
  1. Rita is in „Rita & Werner"; taps the header household name → **switcher sheet**;
     the active one is unmistakable („Aktiv"). `Switcher A` · CAP-1
  2. Taps „Zuhause" (Anna's) → header **and** content change, brief „Gewechselt zu…"
     confirmation. She can see she's now shopping for her daughter. `Switcher B` · CAP-1
  3. Opens the family list; shops with the printout, or checks off with **„Größere
     Darstellung"** on (big text/targets). `Active trip` / `List detail` · a11y overlay
  4. Completes; switches back to her own household afterward.
- **Outcome:** Rita helps out without ever confusing which household she's in.
- **Serves:** Rita (switcher clarity + accessibility), validates the whole multi-household
  design and the persistent-header safeguard.
- **Edge:** if unsure, the always-visible header name prevents adding to the wrong list.

## J6 — Ben scans a receipt and learns milk is cheaper at Netto  · *(PRD UJ-5 — Horizon 2, Post-MVP)*

*Depends on post-MVP receipt + price features; not designed now, reserved for coherence.*

- At trip completion Ben scans the Edeka receipt → on-device OCR → a summary of recognized
  priced items matched to his list, one unmatched line to resolve → weeks later, milk's
  price history shows Netto has been cheaper. Post-MVP: receipts, Products, Price
  Observations, price history.
- **Design note for MVP:** nothing on the MVP active-trip or list screens assumes prices;
  the details/summary view is where prices land when they arrive (see the active-trip
  hierarchy correction — no sum in MVP).

---

## Coverage check — journeys × capabilities

| Journey | Personas | Key CAPs | Screens |
|---|---|---|---|
| J1 Setup | Werner | 1, 3, 2 | Onboarding F1–F4 |
| J2 Build list | Anna, Ben | 4, 5, 6, 7, 8 | List detail A/B |
| J3 Digital trip | Ben | 9, 10, 8 | Active trip A/B |
| J4 Analog trip | Anna | 11, 10 | List detail A, completion |
| J5 Multi-household | Rita | 1, (9/11), a11y | Switcher A/B, trip/print |
| J6 Receipts *(post-MVP)* | Ben | (post-MVP) | details/summary (future) |

Every MVP capability appears in at least one journey; CAP-12 (content-free push) is the
connective tissue *between* journeys (e.g. Ben's live add pings Anna; an invite pings Rita).
CAP-13/14 (locale, data rights) live in Profil and aren't journey-shaped.
