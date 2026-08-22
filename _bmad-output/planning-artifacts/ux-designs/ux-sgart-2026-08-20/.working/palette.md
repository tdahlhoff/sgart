# SGART Color Palette

Working color system for SGART. Anchored on Timo's supplied 5-color palette
(`imports/palette-user-supplied.md`), extended with derived warm neutrals and an amber
warning hue, and a derived dark-mode variant. Contrast ratios are WCAG 2.1; ✅ = passes AA
for normal text (≥4.5:1), ⚠️ = large-text/UI only (≥3:1), ❌ = decorative only.

Feeds `DESIGN.md` `colors` tokens at Finalize; DESIGN.md wins on conflict.

---

## 1. Brand palette (supplied)

| Token | HEX | Role |
|---|---|---|
| `carbon-black` | `#191716` | Text, dark-mode surface |
| `ghost-white` | `#f2f4ff` | Light-mode background |
| `baltic-blue` | `#456990` | **Primary** — actions, links, active nav |
| `verdigris` | `#1ea896` | **Success** — item purchased / checked off |
| `bubblegum-pink` | `#f45b69` | **Error / conflict / destructive** |

## 2. Derived warm neutrals

A warm grey ramp keyed to `carbon-black`'s hue (~20°) so greys harmonize with the brand
rather than reading cold. Used for text hierarchy, borders, dividers, disabled states.
(`ghost-white` remains the one cool-toned surface — deliberate, as the app background.)

| Token | HEX | Typical use |
|---|---|---|
| `neutral-900` | `#191716` | = carbon-black (primary text) |
| `neutral-800` | `#2b2825` | Elevated dark surface |
| `neutral-700` | `#423d39` | Strong secondary text |
| `neutral-600` | `#5c554f` | Secondary text |
| `neutral-500` | `#7a726b` | Muted text, placeholder |
| `neutral-400` | `#9c938b` | Disabled text, icons |
| `neutral-300` | `#c2b9b1` | Borders, dividers |
| `neutral-200` | `#ded7d0` | Subtle borders, input outline |
| `neutral-100` | `#eee9e4` | Hover fill, subtle surface |
| `neutral-50`  | `#f7f4f1` | Warm alt surface / card on ghost-white |

## 3. Derived warning (amber)

No amber existed in the supplied set; added so **offline / sync-pending / queued** reads
distinctly from **error** (pink). Used with dark text.

| Token | HEX | Use |
|---|---|---|
| `amber-500` | `#e0912f` | Warning / pending fill (dark text) |
| `amber-100` | `#f9ecd8` | Warning background tint |

---

## 4. Semantic tokens — Light mode

Content area: light `ghost-white` ground; chrome (header/footer/menu) is inverted — see §4a.

| Semantic token | Value | On-color text | Contrast |
|---|---|---|---|
| `color.background` | `ghost-white #f2f4ff` | `carbon-black` | 16.3:1 ✅ |
| `color.surface` | `neutral-50 #f7f4f1` | `carbon-black` | ~15:1 ✅ |
| `color.text.primary` | `carbon-black #191716` | — | — |
| `color.text.secondary` | `neutral-600 #5c554f` | on ghost-white ✅ | ~7:1 ✅ |
| `color.primary` | `baltic-blue #456990` | `on.primary` | 5.2:1 ✅ |
| `color.primary.onLight` | `baltic-blue #456990` | as text on ghost-white | 5.2:1 ✅ |
| `color.success` | `verdigris #1ea896` | `on.success` | 6.0:1 ✅ |
| `color.error` | `bubblegum-pink #f45b69` | `on.error` | 5.6:1 ✅ |
| `color.warning` | `amber-500 #e0912f` | `on.warning` | 7.0:1 ✅ |
| `color.border` | `neutral-300 #c2b9b1` | — | UI ⚠️ |
| `color.disabled` | `neutral-400 #9c938b` | — | — |

### On-colors (foreground on each fill)

Best-contrast foreground per fill, chosen from the two brand extremes. baltic-blue is the
only fill that takes light text; all others take near-black.

| Token | Fill | Value | Ratio |
|---|---|---|---|
| `color.on.primary` | baltic-blue | `ghost-white #f2f4ff` | 5.2:1 ✅ |
| `color.on.success` | verdigris | `carbon-black #191716` | 6.0:1 ✅ |
| `color.on.error` | bubblegum-pink | `carbon-black #191716` | 5.6:1 ✅ |
| `color.on.warning` | amber-500 | `carbon-black #191716` | 7.0:1 ✅ |

### 4a. Chrome (theme-following) — header / footer / bottom nav / menu

**Chrome follows the theme** (decided with Timo 2026-08-21 after seeing the direction mock):
light chrome in light mode, dark chrome in dark mode — *not* a persistent dark frame. A heavy
carbon-black bar over light content read too heavy and fought the calm feel. Separation from
content in light mode comes from a hairline (`neutral-200`), not from contrast.

| Token | Light mode | Dark mode |
|---|---|---|
| `color.chrome.background` | `#ffffff` | `carbon-black #191716` |
| `color.chrome.text` | `carbon-black #191716` (16:1 ✅) | `ghost-white #f2f4ff` (16.3:1 ✅) |
| `color.chrome.text.muted` | `neutral-500 #7a726b` | `neutral-300 #c2b9b1` |
| `color.chrome.active` | `baltic-blue #456990` (5.2:1 ✅) | `#7ba3cc` lightened primary (6.8:1 ✅) |
| `color.chrome.divider` | `neutral-200 #ded7d0` | `neutral-700 #423d39` |

> The active accent differs per theme: raw `baltic-blue` reads on white (5.2:1) but is only
> 3.1:1 on carbon-black, so dark chrome uses the lightened `#7ba3cc`.

## 5. Semantic tokens — Dark mode (derived)

Surface flips to `carbon-black`; text to `ghost-white`. Chromatic hues are lightened so they
carry contrast on a dark ground. Values are proposals to verify at Finalize.

| Semantic token | Value | Notes |
|---|---|---|
| `color.background` | `carbon-black #191716` | App ground |
| `color.surface` | `neutral-800 #2b2825` | Cards, sheets |
| `color.text.primary` | `ghost-white #f2f4ff` | |
| `color.text.secondary` | `neutral-300 #c2b9b1` | |
| `color.primary` | `#7ba3cc` (baltic +lightness) | Dark text on fill |
| `color.success` | `#33c2ad` (verdigris +lightness) | Dark text on fill |
| `color.error` | `#f6717d` (pink softened) | Dark text on fill |
| `color.warning` | `#f0a94e` (amber +lightness) | Dark text on fill |
| `color.border` | `neutral-700 #423d39` | |

---

## 6. Usage rules

- **Use the `color.on.*` tokens for text/icons on fills** (§4) — don't guess. baltic-blue
  takes `ghost-white`; verdigris/pink/amber take `carbon-black`. These are the best-contrast
  choices, all ≥5.2:1.
- **Chrome follows the theme** (§4a): light chrome (white + carbon-black text) in light mode,
  dark chrome (carbon-black + ghost-white text) in dark mode. Active accent = raw baltic-blue
  on light chrome, lightened `#7ba3cc` on dark chrome. In light mode a `neutral-200` hairline
  separates chrome from content.
- **verdigris & pink are fills, not hairlines.** As thin icons/text on `ghost-white` they sit
  at ~2.7–2.9:1 (fail). Use them as filled chips, badges, buttons with dark content.
- **Never conflate states:** verdigris = done/success, amber = pending/offline, pink = error/
  conflict. One hue, one meaning.
- **`ghost-white` is cool; neutrals are warm** — that's intentional. Keep chrome (text,
  borders) on the warm ramp; reserve ghost-white for the page background.
- Gradients from the source export are **not** adopted as tokens (flat, calm, privacy-forward
  reads better for this product); revisit only if a specific surface needs one.
