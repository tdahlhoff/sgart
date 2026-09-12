# ADR-0003 — Product name: "Einkaufsfuchs" replaces "SGART" as the public-facing name

- **Status:** Proposed, pending naming-conflict resolution — "Einkaufsfuchs" remains the preferred
  candidate and this decision's reasoning stands, but a same-category name collision was found
  (see Update below) before the Docs/UI rollout was made permanent. Technical rename was never in
  scope (see Follow-ups) and still isn't.
- **Date:** 2026-09-11 (decided); updated 2026-09-11
- **Deciders:** Timo Dahlhoff

## Context

"SGART" (Smart Grocery And Receipt Tracker) was chosen as a working name, but the project targets
the German market first (see `docs/PITCH.md`), and "SGART" is neither a German expression nor
particularly warm/approachable — it reads as an acronym, not a product people would recommend to
family and friends.

A naming brainstorm was run (`bmad-brainstorming`, session log at
`_bmad-output/brainstorming/brainstorm-german-name-for-sgart-2026-09-11/.memlog.md` and rendered
keepsake `brainstorm.html` in the same folder) across seven divergent techniques plus a focused
follow-up round, generating 100+ candidate names. Two finalists emerged from the animal-name
direction: **Bonfuchs** (Bon = receipt + Fuchs = fox/clever) and **Einkaufsfuchs** (Einkauf =
shopping/grocery + Fuchs).

**Why Einkaufsfuchs over Bonfuchs.** The app's core function today, and for the foreseeable
roadmap, is shared shopping-list management and store-grouped shopping planning (see
`docs/SGART.md` §1). Receipt scanning, price history, and store-preference-by-item are a
later-arriving enhancement layered on top of that core, not the anchor — and a cost breakdown is a
further downstream by-product of that enhancement, not a near-term feature. A name built around
"Bon" (receipt) would brand the app after a future capability instead of what it actually does for
users now. "Einkaufsfuchs" leads with the list/planning function and still comfortably extends to
cover price comparison and cost overview once those ship, since all of it still falls under
"Einkauf" (shopping).

**The German echo of "Smart Grocery."** A side effect noticed only after the pick: "Einkaufsfuchs"
quietly reconstructs the original SGART wordplay in German — *Einkauf* = Grocery, *Fuchs* ("schlau
wie ein Fuchs" = clever/smart as a fox) = Smart. It is the closest natural German equivalent the
session produced to the English name it replaces.

## Decision

**Adopt "Einkaufsfuchs" as the product's public-facing name.**

- **Full brand name / wordmark:** `Einkaufsfuchs` (store listing, website, marketing). Logo
  treatment may use `EinkaufsFuchs` (CamelCase) or `Einkaufs-Fuchs` (hyphenated) for legibility at
  small sizes.
- **Icon / home-screen short label:** `Kauffux` or `Fux` — "Einkaufsfuchs" is long enough that
  iOS/Android home-screen labels under the app icon would likely truncate it, so a shorter
  operational form is used in that specific slot only. The full name stays the brand of record
  everywhere else.
- **Current claim/tagline:** *"Die Einkaufsliste, die mitdenkt."* — matches today's core function
  (the smart shared list), not the not-yet-shipped price feature.
- **Follow-up claim**, to switch to once receipt scanning / price history ships: *"Weiß, wo's
  günstig ist."*

## Consequences

**Positive**

- The name now matches the target market's language and the product's actual current
  functionality, not a future one.
- "Fuchs" carries "clever/smart" connotation in German the same way "Smart" did in the English
  acronym — the rebrand doesn't lose that meaning, it translates it.
- A short icon-label variant (`Fux`/`Kauffux`) is already chosen, avoiding a later scramble when
  someone notices the full name doesn't fit under the app icon.

**Negative / constraints**

- Two names now coexist during the transition: "SGART" remains the technical/internal name
  (Java package root `de.sgart.*` per `CLAUDE.md` §8, repo name, deep-link scheme
  `de.sgart.app://invite` per ADR-0002, `docs/SGART.md`, `CLAUDE.md` itself) while "Einkaufsfuchs"
  becomes the product-facing name. This is a common and low-risk pattern (public product name vs.
  internal codename) but must be applied consistently — don't let half-renamed material leak to
  users.
- No check yet on practical availability: app-store listing name collisions, `.de` domain
  availability, or German trademark conflicts for "Einkaufsfuchs" have **not** been verified.

## Update 2026-09-11 — naming conflict found, Docs/UI rollout reverted

Timo found that **"Einkaufsfuchs" is already a market product name**: a barcode-scanning shopping
aid with voice output for blind/severely visually impaired users, sold by Deutscher
Hilfsmittelvertrieb (Hannover, operating under BVN — Blinden- und Sehbehindertenverband
Niedersachsen e.V.) at
`https://deutscherhilfsmittelvertrieb.de/shop/elektronische-hilfsmittel/einkaufshilfe/einkaufsfuchs`
— €3,790, reimbursable via German statutory health insurance with a prescription. No ®/™ is shown
on the product page.

**Risk assessment (not a full legal opinion):** the products are functionally different (a
physical assistive-tech device vs. a household shopping-list app) and likely sit in different Nice
trademark classes (medical apparatus vs. software), which would not automatically block a
registration for our use. But both are marketed under "Einkaufshilfe" — close enough in category,
from an established provider with existing customers, that this is a real conflict risk, not a
theoretical one. This is exactly the check Follow-up 2 (below, from the original decision) already
flagged as outstanding — it was outstanding for a reason.

**Action taken:** the Docs/UI rollout that had been applied (app bar titles, MaterialApp title,
`app_de.arb` welcome heading + regenerated l10n, Android `android:label`, iOS
`CFBundleDisplayName`, and `docs/PITCH.md`) was **reverted** back to "SGART" pending resolution.
"Einkaufsfuchs" stays the preferred candidate and every finding above (the Bonfuchs comparison, the
Smart-Grocery wordplay, the icon-label sizing decision) still holds — nothing here invalidates the
reasoning, it just means the name isn't cleared to ship yet. The full session record (including the
mini-round that produced `Kauffux`/`Fux` and the taglines) is untouched at
`_bmad-output/brainstorming/brainstorm-german-name-for-sgart-2026-09-11/`.

## Update 2026-09-11 (later same session) — further candidates explored after the conflict

After the trademark conflict above, the same brainstorming session kept going and produced two more
directions not yet reflected in the Decision section or in `brainstorm.html` (that keepsake was
rendered before these rounds; the raw session log,
`_bmad-output/brainstorming/brainstorm-german-name-for-sgart-2026-09-11/.memlog.md`, has them).
Neither has superseded "Einkaufsfuchs" as the recorded decision above — both are additional
candidates awaiting the same trademark/availability check as Einkaufsfuchs (Follow-up 2) before
any of the three can be chosen.

**Zwergi** (and the `Einkaufszwerg`/`Kaufzwerg`/`Listenzwerg`/`Sparzwerg` family it came from): a
household-dwarf metaphor requested specifically after the Einkaufsfuchs conflict, on the theory that
dwarfs (Schneewittchens 7 Zwerge — industrious, work as a team, quietly hoard treasure underground)
fit the shared-household angle and the background price-history feature at least as well as the
animal names did. A quick web sanity check found no same-category collision (nearest hit was an
unrelated chatbot SaaS, "Zwergi Chat"), but that is not a DPMA/trademark check. `Zwergi` is short
enough to need no separate icon-label contraction (unlike `Einkaufsfuchs` → `Fux`/`Kauffux`).
Two claims were kept side by side rather than picking one: *"Der schlaue Einkaufs-Begleiter"*
(neutral, store listing/header) and *"Euer schlauer Einkaufs-Begleiter"* (warmer, in-app/marketing;
plural "Euer" carries the shared-household angle the way "Dein" would not).

**SEKT** (`Schlauer Einkaufs- und Kassenbon-Tracker`): a German-acronym direction, chosen over a
"KLUG" alternative because every letter is a genuine word-initial (mirroring how "SGART" itself is
built), where KLUG's "U" for "und" was a forced non-word-initial. The wordplay hook is "Sekt"
(sparkling wine, anstoßen/feiern) playing the same role "Fuchs = schlau" played for Einkaufsfuchs.
Claim candidates logged: *"Der Einkauf, der prickelt."* / *"Stoßt an auf schlaues Einkaufen."* This
was the last entry in the session log — no follow-up mini-round (spelling/claim/icon-label
variants, the treatment Einkaufsfuchs and Zwergi both got) has been run for SEKT yet.

## Follow-ups

1. **Scope the technical rename, explicitly, before doing it.** This ADR records the naming
   decision only. Renaming the Java package root (`de.sgart.*`), the git repository, `CLAUDE.md`'s
   title, `docs/SGART.md`, the deep-link scheme, and any Keycloak realm/client naming is a
   separate, wide-blast-radius change (touches code, CI, mobile deep links, and a running beta) and
   needs its own explicit go-ahead — not assumed by this ADR.
2. **Domain/app-store/trademark check** for "Einkaufsfuchs" before any public listing — now
   confirmed necessary, not just precautionary (see Update above). Check DPMAregister
   (register.dpma.de) at minimum; consider a short consult with a trademark attorney given the
   confirmed same-category collision, before re-applying the Docs/UI rollout or committing further.
   The same check is outstanding for "Zwergi" and "SEKT" (see the later-session Update above) if
   either is picked instead.
3. Re-apply `Einkaufsfuchs` / `Kauffux` / `Fux` / the two claims to UI mockups, store listing copy,
   and `docs/PITCH.md` only after Follow-up 2 clears — or after a different name is chosen instead.
