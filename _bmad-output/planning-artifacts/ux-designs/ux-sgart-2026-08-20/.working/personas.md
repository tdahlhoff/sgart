# SGART Personas

Design personas for the EXPERIENCE side. **Grounded in the PRD** (§2 Target User, §2.3
Key User Journeys) — Anna and Ben are canonical PRD personas and keep their names/roles
(ubiquitous language). Rita is added to represent the **genuinely-mixed** friends-&-family
cohort (Timo, 2026-08-22); Jonas represents the PRD's first-class **solo** household.

Feeds `EXPERIENCE.md` at Finalize. Names are proposals — rename freely.

---

## Cohort framing

MVP users = the builder's real friends & family: real households (solo and multi-person),
**genuinely mixed** in age and tech-comfort. Design must serve the tech-comfortable
majority *and* older / less-techy members without a separate app — via an opt-in
**"Größere Darstellung"**, Dynamic Type, a 48px target floor, and gentle onboarding.

## Household map (personas are one interlinked family/social graph)

```
  Household "Zuhause"            Household "Rita & Werner"        (solo)
  ├─ Anna   — Admin              ├─ Werner — Admin               Jonas — Admin
  ├─ Ben    — Member-role        └─ Rita   — Member-role         (household of one)
  └─ Rita   — Member-role  ◄──── same person, TWO households
        (shops for her daughter)
```

**Rita belongs to two Households** — her own (with Werner) and her daughter Anna's
(she sometimes shops for them). She is the living case of the PRD invariant *"a user may
belong to more than one Household"* — and, tellingly, the persona who **switches
households most is also the least tech-comfortable.** → Multi-household switching is a
**primary, real-world** flow, not an edge case, and must be obvious enough for Rita.

---

## 1. Anna — the Organizer  ⭐ primary

- **Role / household:** Admin of a two-person household ("Zuhause"). Sets it up, adds
  stores, invites others. (PRD UJ-1, UJ-4.)
- **Tech comfort:** comfortable; smartphone is second nature.
- **Shopping mode:** **analog / hybrid** — prefers a *printed* list, doesn't want her
  phone out in the aisle; reconciles the trip in the app afterward.
- **Goals:** one shared source of truth; plan the week calmly; trust the app with the
  household's data (privacy).
- **Frictions SGART removes:** list scattered across notes/WhatsApp/whiteboard;
  duplicates; re-sorting a flat list by store in her head.
- **Maps to:** UJ-1 (setup + invite), UJ-4 (analog printed trip). Jobs: shared truth,
  work-the-way-we-shop, trust.
- **Design implications:** print / "Share as PDF" is first-class, not an afterthought;
  post-hoc trip reconciliation must be as easy as live check-off; setup/invite flow is
  her first impression and must feel safe.

## 2. Ben — the Participant  ⭐ primary

- **Role / household:** Member-role in Anna's household. Adds items on the go; does the
  in-store shopping. (PRD UJ-2, UJ-3, UJ-5.)
- **Tech comfort:** comfortable; lives on his phone.
- **Shopping mode:** **digital** — checks items off in-aisle, one-handed, on the way home.
- **Goals:** grab the right things at the right store fast; not get nagged; (horizon 2)
  quietly learn what's cheaper where.
- **Frictions SGART removes:** not knowing what to get where in the aisle; signal drops
  mid-shop; coordinating without a flood of messages.
- **Maps to:** UJ-2 (build list live from two places), UJ-3 (digital trip + postpone +
  transfer leftovers), UJ-5 (post-MVP receipt/price). Jobs: shop-across-stores,
  coordinate-without-nagging, cost-awareness.
- **Design implications:** the **active-trip screen is his** — list is the hero, fast
  one-handed check-off, store grouping, clear offline/sync/conflict states, quiet
  completion (non-sticky end-of-list button + ⋯). Add-item speed (autocomplete) is core.

## 3. Rita — the Pulled-In Elder  ⭐ primary (accessibility + multi-household driver)

- **Role / household:** belongs to **two Households** — **Member-role in her daughter
  Anna's** household ("Zuhause", she shops for the family sometimes) **and Member-role in
  her own** elder household with her husband Werner. Uses SGART because *her family*
  adopted it — not a self-motivated adopter.
- **Tech comfort:** **low–moderate.** Smartphone yes, but small text is hard, deep menus
  get lost, unexpected states cause anxiety. Reading glasses.
- **Shopping mode:** **analog or simple digital** — a printed list, or basic check-off if
  the buttons are big and the screen is calm.
- **Goals:** genuinely help without fear of "breaking something"; see clearly; do the
  common thing in as few, obvious steps as possible.
- **Frictions SGART must handle:** tiny tap targets; jargon; irreversible-feeling actions;
  needing to discover features hidden in overflow menus; getting stranded in a flow.
- **Maps to:** accepts an invite (UJ-1 recipient side), analog trip (UJ-4), simple
  check-off (a gentler UJ-3). Jobs: shared truth, work-the-way-we-shop.
- **Design implications (drive the accessibility overlay):** **"Größere Darstellung"**
  preference; honor Dynamic Type; 48px+ targets; high-contrast, calm screens; primary
  actions never *only* in overflow; forgiving, reversible flows (undo, confirmations that
  reassure not scold); plain-language German copy; short obvious happy paths.
- **Design implications (multi-household):** the **household switcher must be obvious and
  low-effort** — clear which household she's acting in *right now* (avoid adding to the
  wrong household's list), easy to switch, no jargon. She's the reason switching can't hide
  in a settings menu.

## 4. Werner — the Reluctant Elder Admin  ⭐ primary (setup/Admin-path driver)

- **Role / household:** Rita's husband; **Admin of their all-elder household** — the only
  household here with **no younger tech-savvy organizer** to lean on.
- **Tech comfort:** **moderate** — more capable than Rita, but not a power user; figures
  things out slowly and dislikes surprises.
- **Shopping mode:** hybrid — a printed list, or careful digital check-off.
- **Goals:** get the two of them set up and shopping without help; not fear breaking
  anything; keep it simple.
- **Frictions SGART must handle:** he has to **self-serve the hard tasks** Anna shields
  Rita from — create the household, add stores, send/manage invites, be the "at least one
  Admin." No power user in the room to rescue a stuck flow.
- **Maps to:** UJ-1 **as the creator/Admin** (not just the invitee side); analog trips
  (UJ-4). Jobs: shared truth, work-the-way-we-shop.
- **Design implications:** the **setup / household-creation / store-management / invite /
  role-management flows must be completable by a non-expert older user**, not just by an
  Anna. Onboarding hand-holding extends to the **Admin/creator path**, not only the invitee
  path. Governance actions (remove member, delete household) must be clearly labeled and
  hard to trigger by accident. (Accessibility floor is already owned by Rita — Werner rides
  on it; his distinct contribution is the *Admin* burden landing on a non-expert.)

## 5. Jonas — the Solo Household  ○ secondary (keeps solo honest)

- **Role / household:** **Household of one** (first-class per PRD §2). One-person flat;
  runs one list across his own devices (phone + maybe tablet).
- **Tech comfort:** comfortable.
- **Shopping mode:** hybrid — digital in-aisle, occasional printout.
- **Goals:** stop forgetting things; keep his own list consistent across devices; later,
  see what he spends on groceries.
- **Design implications:** collaboration UI must **degrade gracefully to quiet** — no
  empty "invite people" nags, no member-centric clutter, no implication that solo is a
  lesser mode. Everything (store grouping, trips, print, post-MVP prices) works for one.

---

## Persona → design through-lines

| Concern | Driven by | Decision |
|---|---|---|
| Active-trip hierarchy (list is hero, quiet completion) | Ben | already decided; render in key screens |
| Print / Share-as-PDF as first-class | Anna | key-screen requirement |
| Post-hoc (analog) trip reconciliation ≈ as easy as live | Anna, Rita | key-screen requirement |
| Larger-display preference + Dynamic Type + 48px + calm | Rita | shape.md accessibility overlay (MVP candidate) |
| Gentle onboarding — **invitee AND Admin/creator paths** | Rita, Werner | onboarding flow requirement |
| Setup / store / invite / role flows self-serve-able by a non-expert | Werner | IA + key-screen requirement |
| **Obvious household switcher** (which household am I in *now*?) | Rita | IA rule — switching is primary, not settings-buried |
| Governance actions clearly labeled, hard to mis-trigger | Werner | key-screen requirement |
| Solo degrades to quiet (no collaboration nags) | Jonas | applies across all screens |
| Primary actions never overflow-only | Rita, Anna | navigation/IA rule |

## Open / to confirm with Timo

- Names: Anna, Ben are PRD-canonical; **Rita, Werner, Jonas** are proposals — rename if
  desired. (Rita, Werner, Anna confirmed as an interlinked family by Timo 2026-08-22.)
- **"Größere Darstellung"** — RESOLVED (Timo, 2026-08-22): **deferred to the first fast-follow**
  (may be dropped). MVP larger-text rests on OS Dynamic Type + the 48px floor; Rita's MVP
  accessibility rests on Dynamic Type + 48px + calm/plain/forgiving copy.
- Whether Jonas stays a named persona or folds into a "solo mode" note.
- **Multi-household switching** is now confirmed a primary flow (Rita) — carry into IA as a
  first-class navigation concern, not a settings detail.
