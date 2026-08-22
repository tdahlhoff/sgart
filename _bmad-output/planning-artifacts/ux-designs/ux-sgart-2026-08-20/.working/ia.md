# SGART Information Architecture

Top-level structure for the MVP. Validated against **SPEC.md capabilities CAP-1..14**
and the **personas** (`.working/personas.md`). Decided in Coaching mode with Timo
(2026-08-22). Feeds `EXPERIENCE.md` at Finalize.

---

## 1. Shape (decided)

- **Bottom nav = 3 tabs:** **Listen · Einkauf · Profil** (labelled icons — labels are
  non-negotiable for Rita; never icon-only). "Verlauf" is **not** a tab — Done lists live
  behind an **"Erledigt" filter inside Listen** (MVP has no prices/analytics yet; a history
  tab would be thin — it gets a real home Post-MVP when price history exists).
- **Persistent header** on the main tabs: **current household name → switcher** (left) +
  **sync/offline status** (right).
- **Household management lives in the switcher sheet** ("Haushalt verwalten"), keeping it in
  household context. **Profil stays purely personal.**

## 2. Map

```
[Auth · Keycloak]
 ├─ First-run routing (CAP-1)
 │    0 households → „Haushalt erstellen" / „Auf Einladung warten"   (gentle → Werner)
 │    1 household  → straight into the app
 │    ≥2 households → Household selection screen                     (→ Rita)
 └─ Invite acceptance — deep link → app, or web fallback (CAP-2)

[Main app — persistent chrome]
 Header:  ‹ Aktueller Haushalt ▾ ›                       ‹ Sync / Offline-Status ›
   └─ Household switcher sheet (CAP-1 multi-household — Rita's primary flow)
        · Switch household (current check-marked; name always visible)
        · „Haushalt verwalten"
             · Mitglieder / Einladungen / Rollen        (CAP-2)
             · Geschäfte                                 (CAP-3)
        · „+ Neuen Haushalt erstellen"                   (CAP-1)

 Bottom nav:
  ┌ 1 · Listen ─────────────────────────────────────────────────────────┐
  │  · Offene Listen (mehrere gleichzeitig, Auto-Name „Liste N")  CAP-4   │
  │  · Filter: „Erledigt" → Done lists (read-only archive)                │
  │  · Listendetail:                                                      │
  │       · Items: hinzufügen/ändern/löschen, abhaken, verschieben CAP-5  │
  │       · Schnell-Erfassung mit Autocomplete + Prefill          CAP-6   │
  │       · Aktion „Einkauf starten" → Trip                       CAP-9   │
  │       · Aktion „Drucken / Als PDF teilen"                     CAP-11  │
  └──────────────────────────────────────────────────────────────────────┘
  ┌ 2 · Einkauf ────────────────────────────────────────────────────────┐
  │  · Aktiver Trip: nach Geschäft gruppiert + „Noch nicht zugeordnet"    │
  │       · Zuordnen / Umleiten zwischen Geschäften              CAP-9    │
  │       · Abhaken / Aufheben / Verschieben (live)             CAP-7/9   │
  │       LIST IS THE HERO; „Einkauf abschließen" = quiet:                │
  │       non-sticky end-of-list button + ⋯ overflow (NOT sticky CTA)     │
  │  · (leer) „Einkauf starten" → Liste + Geschäfte wählen                │
  │  · „Einkauf abschließen" → Restposten-Review (TRANSFER/DISCARD) CAP-10│
  └──────────────────────────────────────────────────────────────────────┘
  ┌ 3 · Profil (rein persönlich) ───────────────────────────────────────┐
  │  · Sprache & Region (Locale, de-DE default)                  CAP-13   │
  │  · Größere Darstellung (a11y-Präferenz — MVP-Kandidat)      (Rita)    │
  │  · Benachrichtigungen (Info; MVP-Defaults fix)               CAP-12   │
  │  · Meine Daten: Export / Löschen                             CAP-14   │
  │  · Konto / Abmelden                                                   │
  └──────────────────────────────────────────────────────────────────────┘

 Cross-cutting (surfaced in-context, not a destination):
  · Live Sync (CAP-7) + Offline-Queue/Pending count + Konflikt keep/discard (CAP-8)
      → header status + inline on affected Items
  · Content-free push pings (CAP-12) → OS notifications, wake-and-fetch
```

## 3. Capability → home (coverage check — all 14 placed)

| CAP | Home |
|---|---|
| CAP-1 Households & first-run routing | Entry routing + switcher „+ Neuen Haushalt" |
| CAP-2 Invite & membership & roles | Invite acceptance (entry) + „Haushalt verwalten" |
| CAP-3 Store management | „Haushalt verwalten" → Geschäfte |
| CAP-4 Multiple lists | Listen |
| CAP-5 Item mgmt & status | Listen → Listendetail |
| CAP-6 Fast entry / autocomplete | Listen → Schnell-Erfassung |
| CAP-7 Live sync | Cross-cutting (header status + live updates) |
| CAP-8 Offline queue / conflict | Cross-cutting (pending count + inline conflict) |
| CAP-9 Trips (grouped view, routing) | Einkauf |
| CAP-10 Complete trip w/ leftovers | Einkauf → Abschließen dialog |
| CAP-11 Print & share | Listen → Listendetail action |
| CAP-12 Content-free notifications | System pings + Profil (info) |
| CAP-13 Locale / i18n | Profil → Sprache & Region |
| CAP-14 Erasure & export | Profil → Meine Daten |

## 4. Persona through-lines honored

- **Rita (multi-household):** switcher in the persistent header — one obvious tap, current
  household always shown, never settings-buried. 3 labelled tabs, no icon-only nav.
- **Werner (Admin path):** „Haushalt verwalten" (members/invites/roles/stores) is one tap
  from the header, not buried in personal Profil; first-run create flow is gentle and leads
  into store setup (onboarding detail — see key screens).
- **Ben (active trip):** dedicated Einkauf tab keeps the active trip one thumb away; list is
  the hero; quiet completion.
- **Anna (analog):** Print / Share-as-PDF is a first-class list action.
- **Jonas (solo):** identical nav; collaboration surfaces (switcher, „Haushalt verwalten")
  stay quiet — no invite-nags, solo is not a lesser mode.

## 5. Open / next

- First-run + onboarding flow detail (Werner's Admin path; store-setup guidance) → belongs
  to the **key screens & journeys** step.
- Exact home for **Größere Darstellung** confirmed under Profil.
- Next EXPERIENCE step after IA: **key screens** (active-trip w/ corrected hierarchy, list
  detail, switcher sheet, first-run) + **named-protagonist journeys**.
