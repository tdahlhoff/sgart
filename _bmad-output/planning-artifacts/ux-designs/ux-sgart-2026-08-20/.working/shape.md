# SGART Shape, Elevation & Density

Working system for physical feel. Decided in the Coaching-mode UX flow after the
3-system specimen (`.working/direction-shape.html`); Timo chose **3 · Ausgewogen** with
an accessibility overlay. Feeds `DESIGN.md` at Finalize; DESIGN.md wins on conflict.

---

## 1. Decision — System 3 · Ausgewogen (Balanced)

Medium radius, hairline borders plus a faint lift, comfortable-compact density. Reads
calm and aisle-friendly while still fitting a long grocery list. Rejected: 1·Ruhig (too
roomy as a default for all users), 2·Kompakt (tap targets ~41px, below the floor).

## 2. Corner radius

| Token | Value | Applies to |
|---|---|---|
| `radius.card` | 14px | Cards, sheets, grouped list containers |
| `radius.button` | 12px | Buttons / CTAs |
| `radius.control` | 6px | Checkboxes, small controls |
| `radius.pill` | 999px | Badges, chips, progress track |

## 3. Elevation

Flat-forward, consistent with the calm/privacy tone (gradients rejected in palette.md):

- **Content surfaces:** hairline border (`neutral-200` light / `neutral-700` dark),
  no shadow.
- **Elevated cards / raised sheets:** hairline + one faint soft shadow tier
  (`0 10px 26px -18px rgba(25,23,22,.4)`), just enough lift to read as raised.
- **Modals / bottom sheets:** a slightly stronger single shadow tier (define at build).
- No multi-layer Material elevation ladder. One faint tier is the whole system.

## 4. Density & spacing

Comfortable-compact. Base spacing unit **4px**; common steps 4/8/12/16/24.

| Token | Value |
|---|---|
| `space.card-padding` | 15px (≈16) |
| `space.row-padding-y` | 12px |
| `space.heading-gap` | 8px |

## 4a. Buttons (design rule)

- **Action buttons are text-only** — no icons. Decided with Timo 2026-08-22 (German
  labels are long; icons stole the room and read busier). Icons stay reserved for
  icon-buttons (⋯ overflow, back chevron) and the bottom nav.
- **Labels wrap, never clip.** Buttons allow their label to break to two lines at large
  text sizes / "Größere Darstellung" instead of truncating or shrinking the font.
- Primary = filled baltic (`color.on.primary` = ghost-white). Secondary = outlined baltic
  on transparent. Terminal/quiet actions (e.g. "Einkauf abschließen") = tonal (soft baltic
  tint), non-sticky.
- Applied consistently: the trip-screen "Einkauf abschließen" button is also text-only
  (checkmark icon removed, Timo 2026-08-22). Menu items (in ⋯ sheets) keep their icons —
  the rule is about *buttons*, not list/menu rows.

## 5. Accessibility overlay (applies on top of System 3 — non-negotiable)

- **Interactive-target floor:** every tappable row/control has **`min-height: 48px`**
  (Material 48dp; clears the 44px comfortable floor). System 3 list rows already land
  ~47–48px; the floor makes it a guarantee, not a coincidence.
- **Expanded hit areas:** small glyphs (21px checkbox) get padding so the *touch* area
  reaches 48px even though the drawn control is smaller.
- **Honor OS Dynamic Type / text scaling** (Flutter `MediaQuery.textScaler`): layouts
  reflow gracefully as the user's system text size grows — the primary accessibility
  lever for older / low-vision users, independent of the base density.
- This decision confirms the provisional type scale in `typography.md`.

## 6. Older-user question — RESOLVED (2026-08-22)

Persona work settled the parked target-group dependency. Timo's real friends-&-family
cohort is **genuinely mixed** (includes older / less-tech-comfortable members who will be
regular users, e.g. the new persona Rita). Resolution:

- **Base stays System 3** — do NOT switch to Ruhig. A globally roomier default would tax
  the tech-comfortable majority; the accommodation is made **opt-in** instead.
- **"Größere Darstellung" (larger-display) in-app preference → deferred to first fast-follow**
  (Timo, 2026-08-22; may be dropped). MVP accessibility for larger text rests on OS Dynamic
  Type + the 48px floor; the opt-in in-app toggle is post-MVP.
- **Gentler onboarding** for less-techy members becomes a design requirement (see personas).
