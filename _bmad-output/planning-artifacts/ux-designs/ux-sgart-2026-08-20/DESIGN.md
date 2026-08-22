---
status: final
created: 2026-08-20
updated: 2026-08-22
sources:
  - ../../../specs/spec-sgart/SPEC.md
  - .working/palette.md
  - .working/typography.md
  - .working/shape.md
---

# SGART Design Spine

The canonical visual system for SGART. A lean spine of invariants; the `.working/*` files
hold the derivation and the published mocks show it in context. On conflict, this file wins
for tokens and rules. German appears only as quoted product UI labels (product is
German-first, CAP-13); all else is English.

Mobile-first (Flutter iOS + Android). Tone: **calm, private, domestic, trustworthy** —
a coordination tool, not commerce. Flat-forward, unhurried, legible.

---

## 1. Color

Anchored on a supplied 5-color palette, extended with a warm-neutral ramp, an amber
warning, and a derived dark mode. **One hue, one meaning.** Contrast ≥ AA for text.

### Brand + semantic roles
| Role | Light | Dark | On-color (text/icon on fill) |
|---|---|---|---|
| `primary` (actions, links, active nav) | `baltic #456990` | `#7ba3cc` | light: `ghost-white #f2f4ff` (5.2:1) · dark: `carbon-black` (6.8:1) |
| `success` (Done / purchased) | `verdigris #1ea896` | `#33c2ad` | `carbon-black #191716` |
| `warning` (offline / sync-pending) | `amber #e0912f` | `#f0a94e` | `carbon-black` |
| `error` (conflict / destructive) | `pink #f45b69` | `#f6717d` | `carbon-black` |
| `background` | `ghost-white #f2f4ff` | `carbon-black #191716` | — |
| `surface` | `#ffffff` / `neutral-50 #f7f4f1` | `neutral-800 #2b2825` | — |
| `text.primary` | `carbon-black` | `ghost-white` | — |
| `text.secondary` | `neutral-600 #5c554f` | `neutral-300 #c2b9b1` | — |
| `border` | `neutral-200/300` | `neutral-700 #423d39` | — |

**Warm neutral ramp** (keyed to carbon-black's ~20° hue, so greys harmonize):
`900 #191716 · 700 #423d39 · 600 #5c554f · 500 #7a726b · 400 #9c938b · 300 #c2b9b1 ·
200 #ded7d0 · 100 #eee9e4 · 50 #f7f4f1`. `ghost-white` is the one cool surface (deliberate).

### Chrome — follows the theme
Header / footer / bottom nav / menus are **light in light mode, dark in dark mode** (not a
persistent dark frame). In light mode a `neutral-200` hairline separates chrome from content.
Active accent = raw `baltic` on light chrome, lightened `#7ba3cc` on dark chrome (raw baltic
is only 3.1:1 on carbon-black).

### Rules
- Use the `on.*` colors for text/icons on fills — `baltic` takes light text; `verdigris /
  amber / pink` take near-black. All ≥ 5.2:1.
- **`primary`'s on-color splits by mode.** Ghost-white on the lightened dark accent `#7ba3cc`
  is only 2.4:1 and fails AA, so dark mode takes `carbon-black` (6.8:1) instead.
  *(Amended 2026-08-22 after the Story 1.2 code review.)*
- `verdigris` & `pink` are **fills, not hairlines** (thin strokes fail contrast) — use as
  filled chips/badges/buttons with dark content.
- Never conflate states: `verdigris` = done, `amber` = pending/offline, `pink` = error/conflict.
- **No gradients** (flat reads calmer, privacy-forward).

### Text on a semantic tint (`on…Tint`)
Tinted tags and tonal fills (§4, §4b) use the semantic hue at **14 % alpha** as background.
The saturated hue is *not* legible as text on its own tint — amber reaches only 2.1:1 and
verdigris 2.4:1 — so each hue carries a separate text token, darkened (light mode) or
lightened (dark mode) exactly as far as AA requires:

| Role | Tint text · light | Tint text · dark |
|---|---|---|
| `primary` | `#3f5f83` (5.0:1) | `#8aaed2` (5.0:1) |
| `success` | `#136c60` (5.0:1) | `#33c2ad` (5.1:1) |
| `warning` | `#8a5515` (5.0:1) | `#f0a94e` (5.5:1) |
| neutral | `neutral-600` (5.5:1) | `neutral-300` (5.7:1) |

*(Added 2026-08-22 after the Story 1.2 code review, which measured the original
hue-on-its-own-tint pairings at 2.1–4.4:1.)*

## 2. Typography

**Inter throughout**, weight-differentiated (chosen over a warm-humanist and an editorial
pairing — clarity over character). Complete Latin-Extended for German (`ä ö ü ß`) and future
i18n. Flutter: `google_fonts` or bundle the variable asset (prefer bundling for offline /
no-network-fetch).

| Role | Weight | Notes |
|---|---|---|
| Display / screen title | 600 | slight negative tracking (~-.01em) |
| Section / group heading | 600–700 | |
| Body, item names | 400 | line-height ~1.5 (dense rows ~1.4) |
| Emphasis (counts, values) | 600 | |
| Buttons | 600 | |
| Captions / kickers | 500 | uppercase labels: letter-spacing ~.12em |

**Tabular figures** (`FontFeature.tabularFigures()`) wherever digits align or update live —
quantities, counts (`7 von 12`), later prices. de-DE formatting via `intl`.
> Prices/sums are **post-MVP** (arrive with receipt scanning); MVP tabular use = quantities
> + counts.

Type scale (rem, may fine-tune): display ~1.7 · title ~1.32 · heading ~1.15 · body ~0.92 ·
meta ~0.8 · caption ~0.75 · kicker ~0.66.

## 3. Shape · elevation · density — “System 3 · Ausgewogen”

Medium radius, hairline + one faint lift, comfortable-compact. Base spacing unit **4px**.

| Token | Value |
|---|---|
| `radius.card` | 14px |
| `radius.button` | 12px |
| `radius.control` (checkbox…) | 6px |
| `radius.pill` (badge/chip/track) | 999px |
| `space.card-padding` | 15px |
| `space.row-padding-y` | 12px |
| `space.heading-gap` | 8px |

**Elevation is flat-forward:** content surfaces = hairline border, no shadow; elevated
cards/sheets = hairline + one faint tier (`0 10px 26px -18px rgba(25,23,22,.4)`); modals a
touch stronger. No multi-layer Material ladder.

## 4. Buttons (rule)

- **Action buttons are text-only — no icons.** (German labels are long; icons stole room and
  read busier.) Icons stay for icon-buttons (⋯, back) and the bottom nav.
- **Labels never clip and never shrink the font.** They wrap as far as they need to — two
  lines covers most German labels, but the button grows rather than truncating at large text
  sizes. *(Clarified 2026-08-22: "two lines" is the typical case, not a cap.)*
- Primary = filled `baltic` (on = ghost-white). Secondary = outlined baltic on transparent.
  Terminal/quiet actions (e.g. „Einkauf abschließen") = **tonal** (soft baltic tint),
  **non-sticky**.
- Menu/list rows keep their icons — the rule is about *buttons*.

## 4b. List-row status label (convention)

A row's status/attribute (member role, store chain, and similar) sits as a **dense label on
its own line under the name** — never as a trailing pill competing for horizontal width
(names always keep full width). The label is a small **tinted, uppercase** tag: subtle
semantic tint background (the hue at 14 %) + that hue's `on…Tint` text color, tight padding,
minimal added row height. Uppercasing is visual only — assistive technology gets the original
string.
Semantic tints: role Admin = baltic tint, Mitglied = neutral, Ausstehend = amber tint;
store chain = verdigris tint. Do not label the current user with a „Du" marker. Any trailing
control (⋯) gets a fixed-width slot so rows align. *(Decided with Timo 2026-08-22.)*

## 5. Accessibility overlay (non-negotiable, on top of System 3)

MVP cohort is **genuinely mixed** in age/tech-comfort (persona Rita is a primary,
low-tech user). So:
- **48px minimum interactive target** on every tappable row/control; small glyphs get
  padded hit areas.
- **Honor OS Dynamic Type / text scaling** (`MediaQuery.textScaler`); layouts reflow.
- **“Größere Darstellung”** in-app larger-display preference — **deferred to the first
  fast-follow** (Timo, 2026-08-22; may be dropped entirely). The MVP relies on OS Dynamic Type
  for larger text; Rita's accessibility in MVP rests on Dynamic Type + 48px + calm/plain/forgiving.
- Primary actions never live *only* in overflow. High-contrast, calm screens; forgiving,
  reversible flows; plain-language German copy.

## 6. Published references (design)
- Color-in-context direction mock · artifact `9eda126a-…`
- Typography specimen (A/B/C) · artifact `6a9e22b1-…`
- Shape/density specimen (1/2/3) · artifact `9d70d8ba-…`
