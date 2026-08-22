# SGART Typography

Working type system for SGART. Decided in the Coaching-mode UX flow after the
3-direction specimen (`.working/direction-typography.html`); Timo chose **A · Klar**.
Feeds `DESIGN.md` `typography` tokens at Finalize; DESIGN.md wins on conflict.

---

## 1. Typeface — decided

**Inter, used throughout** (headings, body, UI, data). One family, weight-differentiated.

- **Why:** ruthless small-size legibility for aisle use, a purpose-built UI face,
  complete Latin-Extended coverage for German (`ä ö ü ß Ä Ö Ü`) and future i18n, and
  first-class **tabular figures** for quantities and prices. Neutral by design — it
  carries the color system and the content, and stays out of the way.
- **Rejected:** B (Figtree-only, warmer) and C (Fraunces + Figtree, editorial). Timo
  chose the clear, neutral direction over added character.
- **Flutter:** ships via the `google_fonts` package (`GoogleFonts.inter`), or bundle the
  Inter variable font as an asset for offline-first/no-network-fetch (preferred for a
  privacy-forward, offline-capable app — avoids a runtime font download).

## 2. Weights

| Role | Weight |
|---|---|
| Display / screen title | 600 (SemiBold) |
| Section / group heading | 600–700 |
| Body, item names | 400 (Regular) |
| Emphasis in body (counts, values) | 600 |
| Buttons / CTAs | 600 |
| Captions, kickers, meta | 500 |

Kickers / uppercase labels: letter-spacing ~`.12em`. Display/titles: slight negative
tracking (~`-.01em`). Body line-height ~1.5; dense UI rows ~1.4.

## 3. Numerals — tabular by default for aligned data

**Rule:** wherever digits line up or update live — quantities (`1,5 kg`, `250 g`),
progress counts (`7 von 12`), and later prices/sums — use **tabular figures**
(`font-feature-settings: 'tnum' 1`, Flutter `FontFeature.tabularFigures()`). Prevents
column jitter as values change. Proportional figures are fine in flowing prose.

> Note: **prices/sums are post-MVP** (they arrive with receipt scanning). In the MVP the
> tabular-figures rule is justified by quantities and counts alone.

de-DE formatting is a separate concern (decimal comma, `1.234,56 €`, thin-space
grouping) handled by `intl`/locale — not the typeface — but Inter's tnum makes the
result align.

## 4. Type scale (PROVISIONAL — finalize with the density step)

Starting scale from the specimen (rem, revisit alongside shape/density):

| Token | Size | Use |
|---|---|---|
| `text.display` | ~1.7rem | Empty-state / large screen title |
| `text.title` | ~1.32rem | Active screen title (e.g. Wocheneinkauf) |
| `text.heading` | ~1.15rem | Section headings |
| `text.body` | ~0.92rem | Item names, body |
| `text.meta` | ~0.8rem | Quantities, secondary meta |
| `text.caption` | ~0.75rem | Timestamps, captions |
| `text.kicker` | ~0.66rem | Uppercase labels/kickers |

> Sizes are provisional until the density decision (comfortable vs. compact rows) is
> made; the ratios and roles hold, the absolute values may shift.

## 5. Not yet decided (next steps)

Corner radius, elevation, spacing scale, and row density are still placeholders in the
mocks — decided next, then this scale is confirmed.
