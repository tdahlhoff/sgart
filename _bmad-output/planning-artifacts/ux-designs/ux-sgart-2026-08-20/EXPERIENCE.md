---
status: final
created: 2026-08-20
updated: 2026-08-22
sources:
  - ../../../specs/spec-sgart/SPEC.md
  - ../../prds/prd-sgart-2026-08-06/prd.md
  - .working/personas.md
  - .working/ia.md
  - .working/journeys.md
---

# SGART Experience Spine

The canonical experience design for SGART MVP: who it's for, how it's structured, the key
screens, and the end-to-end journeys. A lean spine; `.working/*` holds full detail and the
published mocks show the screens. German appears only as quoted product UI labels.

**Product posture:** a private, collaborative **coordination & tracking** tool — *not*
commerce. Nothing is bought/ordered in-app; screens optimize for capturing and checking off,
not conversion. **No prices/sums in the MVP** (they arrive post-MVP with receipt scanning).

---

## 1. Personas

Grounded in the PRD (Anna, Ben are canonical). One interlinked family/social graph.

| Persona | Role / household | Mode | Primary design driver |
|---|---|---|---|
| **Anna** ⭐ | Admin, 2-person hh „Zuhause" | analog / hybrid | print & post-hoc reconcile as first-class; safe setup |
| **Ben** ⭐ | Member-role | digital in-aisle | **owns the active-trip screen**; fast add; offline/sync |
| **Rita** ⭐ | Anna's mother; **member of TWO hh** (her own + Anna's) | analog / simple digital | **accessibility + multi-household** driver |
| **Werner** ⭐ | Rita's husband; Admin of an all-elder hh | hybrid | **self-serve Admin/creator path** (no power user present) |
| **Jonas** ○ | solo household | hybrid | solo is first-class → collaboration **degrades to quiet** |

**Cohort:** builder's friends & family, **genuinely mixed** in age/tech-comfort → the
accessibility overlay (DESIGN §5) is load-bearing, not optional. Rita belongs to two
households (PRD invariant "a user may belong to >1 Household") → **multi-household switching
is a primary flow**, and the persona who switches most is the least tech-comfortable.

## 2. Information Architecture

**Three bottom-nav tabs — „Listen" · „Einkauf" · „Profil"** (labelled icons, never
icon-only). No „Verlauf" tab: Done lists live behind an „Erledigt" filter in „Listen" (MVP
has no price history yet).

**Persistent header** on the main tabs: **current household name → switcher** (left) +
**sync/offline status** (right). The switcher sheet also hosts **„Haushalt verwalten"**
(members/invites/roles + stores) — household management stays in household context; **Profil
is purely personal**.

**Entry / first-run:** auth (Keycloak) → CAP-1 routing (0 → create / await-invite · 1 →
straight in · ≥2 → selection) + invite acceptance (deep link, web fallback).

### Capability → home (all 14 placed)
| CAP | Home |
|---|---|
| 1 households/routing | entry routing + switcher „+ Neuen Haushalt" |
| 2 invite/roles | invite acceptance + „Haushalt verwalten" |
| 3 stores | „Haushalt verwalten" → Geschäfte |
| 4 lists · 5 items · 6 fast add | Listen (+ list detail) |
| 7 live sync · 8 offline/conflict | cross-cutting (header status + inline) |
| 9 trips · 10 completion | Einkauf |
| 11 print/share | Listen → list detail action |
| 12 push | system pings + Profil (info) |
| 13 locale · 14 data rights | Profil |

Cross-cutting states (sync/offline/pending, push) surface **in context** — header status +
inline on items — never as destinations. **Conflict** is the exception: it surfaces in
context (a non-blocking reconnect banner + inline „Konflikt" badge) *and* has a dedicated
resolution sheet (see key screens).

## 3. Key screens

Each applies the DESIGN spine (Inter, System 3, 48px, text-only buttons, locked palette).

- **Active trip (Ben)** — `screen-active-trip.html` · artifact `9423bc7b-…`
  **List is the hero.** No sticky bottom bar, no sum. „Einkauf abschließen" is a quiet,
  non-sticky, tonal action at the **list end**, mirrored in ⋯. Store-grouped with a „Noch
  nicht zugeordnet" section; live check-off; offline chip. (CAP-9/10, 7/8)
- **List detail (Anna)** — `screen-list-detail.html` · artifact `994ccd54-…`
  **Fast entry is the hero** — persistent add field with autocomplete + attribute prefill
  and an "add as new" path (CAP-6). „Einkauf starten" (CAP-9) and „Drucken / Teilen"
  (CAP-11) are **peer** actions (analog = digital). Items carry name/qty/note + store chip;
  check off outside a trip (CAP-5). ⋯: rename / print+share / delete.
- **Household switcher (Rita)** — `screen-switcher.html` · artifact `47eb13e6-…`
  Bottom sheet from the header chip. Active household **unmistakable** („Aktiv"); one tap to
  switch; after switching, header **and** content change with a brief confirmation.
  Safeguard: household name is **always** in the header, never settings-buried. Hosts
  „Haushalt verwalten" + „Neuen Haushalt erstellen". (CAP-1, entry to 2/3)
- **First-run / onboarding (Werner)** — `screen-onboarding.html` · artifact `3bf936d6-…`
  Gentle **one-step-at-a-time wizard** a non-expert completes alone: create → name (with
  "change later" reassurance) → add stores (advisory client-side chain suggestion,
  confirm/change/clear, skippable) → invite (optional; „Später einladen" → into the app,
  solo first-class). Progress bar, plain German, privacy stated up front. (CAP-1/3/2)

- **Conflict resolution (offline replay)** — `screen-conflict.html` · artifact `7884ad17-…`
  A conflict = a **queued offline command rejected** because the target advanced meanwhile
  (CAP-8 / AD-8). Entry points: a **non-blocking reconnect banner** („N Änderungen konnten
  nicht übernommen werden") + inline „Konflikt" badge — both open the **same resolution
  sheet**. Coarse, **per-command keep mine / discard — no field-level merge**; each conflict
  shows *your change ↔ current (by whom)* with names resolved live (AD-6). Convergent/
  idempotent actions (same add, same check-off, simultaneous *online* edits) resolve
  **silently** — never surface here. Pink = conflict (distinct from amber offline/pending).

**Supporting screens** (mocked 2026-08-22 for full MVP coverage; same design system):

- **Listen overview** (CAP-4) — `screen-lists-invite.html` · artifact `009d4dd9-…`: several
  Open lists with progress + auto-name „Liste N"; a segmented **Offen / Erledigt** filter (Done
  lists = read-only archive, no history tab); „+ Neue Liste".
- **Invite acceptance** (CAP-2) — same artifact: recipient path — who invited you + which
  household, join as „Mitglied", privacy stated, deep-link/web-fallback.
- **Profil + Meine Daten** (CAP-13/12/14) — `screen-profile.html` · artifact `8a02da6e-…`:
  personal-only (Sprache & Region, notifications-info; the **Größere Darstellung** toggle is
  **deferred to fast-follow**, so MVP larger-text rests on OS Dynamic Type); the
  GDPR **export/erasure** flow — plain, calm, clearly irreversible, with the **last-Admin
  guard** blocking erasure until the role is handed over; copy reflects AD-7 de-linking.
- **Haushalt verwalten** (CAP-2/3) — `screen-household-manage.html` · artifact `25b442ae-…`:
  members with role labels + **last-Admin** rule, invites („eine Einladung pro E-Mail"), and
  store management where **removal archives** (history preserved) with advisory chain
  suggestion. Uses the list-row status label (DESIGN §4b).
- **Trip lifecycle** (CAP-9/10) — `screen-trip-lifecycle.html` · artifact `cf10bb7d-…`: start
  = multi-select stores (≥1); completion = guided multi-step **Übernehmen/Verwerfen** per open
  item with a transfer target, never force-completed; done = list Done+immutable, leftovers on
  a fresh list.
- **Print & Share** (CAP-11) — `screen-print-share.html` · artifact `98cfa9c8-…`: native print
  in the grouped-by-store layout + share-as-in-memory-PDF; explicit **no-file-saved** guarantee.

## 4. Journeys

End-to-end, grounded in PRD UJ-1…5. Full text: `.working/journeys.md`; map artifact
`e8372639-…`.

| # | Journey | Personas | CAPs | Screens |
|---|---|---|---|---|
| J1 | Werner sets up the elder household (UJ-1, creator side) | Werner | 1,3,2 | Onboarding |
| J2 | Anna & Ben build the list, live (UJ-2) | Anna, Ben | 4,5,6,7,8 | List detail |
| J3 | Ben's digital trip + transfer leftovers (UJ-3) | Ben | 9,10,8 | Active trip |
| J4 | Anna's analog printed trip (UJ-4) | Anna | 11,10 | List detail + completion |
| J5 | Rita shops across households (new, multi-hh) | Rita | 1, a11y | Switcher + trip/print |
| J6 | Receipts / price history (UJ-5) — **Post-MVP** | Ben | — | details/summary (future) |

Every MVP capability appears in ≥1 journey. CAP-12 (content-free push) is the glue *between*
journeys (a live add pings Anna; an invite pings Rita). CAP-13/14 live in Profil and aren't
journey-shaped.

## 5. Downstream note

For the requirement inventory, **SPEC.md is canonical** (CAP-1…14), not PRD FR numbering.
This spine + DESIGN.md are the UX contract for `bmad-create-epics-and-stories` and
implementation.
