---
baseline_commit: bd8fa8e5c42bd79c45573336af7259479236d5fc
---

# Story 3.5: Print and share the list

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a member,
I want to print or share an open list grouped by store,
so that I (or someone else) can shop analog.

## Context & Scope (read first)

**This is a client-only (Flutter) story. There is NO backend work — no new event, command, aggregate,
read model, projector, migration, endpoint, `commandId`, or optimistic-state concern.** Print and
in-memory PDF are pure device capabilities. Everything the printout needs already lives in
`ListDetailState`: `items` (name, note, `amount`, `unit`, `storeId`, `status`) and `stores`
(`StoreSummary` with `name` + `chainId`). This story adds a **grouped-by-store projection** of the
list, renders it to a PDF, and hands the bytes to the OS print dialog or share sheet. Grouping is a
client render over data the cubit already holds (Story 2.6 made the assignment durable; this story is
its print consumer — confirmed in `deferred-work.md`).

Because there is no command, several standing retro/DoD items are **N/A here** (no endpoint → no
error-advice contract test; no read model → no isolation/replay test; no `commandId` → no
spent-id/re-entrancy footgun; no optimistic state → no drift). The DoD items that **do** apply are
a11y labels + 48px targets on new interactive widgets, no dead strings/fields/stale comments, and
fail-fast client guards. Call this out in the completion notes so a reviewer doesn't hunt for a
backend slice that isn't there.

**Timo's locked decisions (2026-09-05):**
1. **Availability = Open lists only.** Print/Share is a peer to „Einkauf starten" and shows under the
   same `!state.isReadOnly` gate. All items on an Open list are `OPEN`, so tick boxes are empty and
   there is no `DONE`/`DISCARDED` rendering question. Stores are already loaded for an Open list — no
   change to the read-only store-loading path.
2. **Entry = tonal button + bottom sheet.** A „Drucken / Teilen" tonal button at the list end opens
   the bottom sheet from the mockup (`screen-print-share.html`): „Drucken" (Nach Geschäft gruppiert)
   / „Als PDF teilen" (z. B. an WhatsApp senden) + the „Es wird keine Datei dauerhaft gespeichert."
   reassurance. **Do not** build the UX-DR8 app-bar ⋯ menu (it doesn't exist yet, and putting a
   primary action overflow-only violates NFR10).
3. **Group header = store name + chain label.** Each store section shows the store name with a quiet
   secondary chain label (e.g. „Edeka Schiedemann" / „Edeka"), resolving `chainId` through the
   existing `StoreChainReferenceCache`. „Noch nicht zugeordnet" has no chain label.

## Acceptance Criteria

1. **(AC1 — print, grouped)** **Given** an Open list, **when** a member taps „Drucken / Teilen" and
   chooses „Drucken", **then** the **native OS print dialog** opens showing the list **grouped by
   store** — one section per store that has items (store name + chain label), each item as a row with
   an empty **tick box**, name, and quantity (and note when present), plus a „Noch nicht zugeordnet"
   section for unassigned / archived-store items — mirroring the trip view's grouped structure
   (FR11, UX-DR19). The document header shows the list name and the current date (de-DE formatted).
2. **(AC2 — share as in-memory PDF)** **Given** an Open list, **when** a member chooses „Als PDF
   teilen", **then** the OS share sheet is offered an **in-memory PDF** (same grouped layout) so it
   can be sent e.g. to WhatsApp (FR11).
3. **(AC3 — no persisted file)** **In both paths, the app writes no PDF to persistent device
   storage.** The document is produced from in-memory bytes (`doc.save()`); our code never uses
   `path_provider`, `File`, the app-documents/gallery directory, or any save-to-disk call. *(The
   native share sheet may stage an OS-managed temporary/cache file — that is unavoidable for a native
   share and is not an app-persisted file; the guarantee we own and test is "our code only ever hands
   over bytes".)* The sheet states this explicitly: „Es wird keine Datei dauerhaft gespeichert."
4. **(AC4 — grouping matches on-screen)** The printed grouping matches how items are grouped
   on-screen: an item appears under its assigned store only when that store resolves in the
   household's active stores; an unassigned item, or one assigned to an archived/absent store, falls
   under „Noch nicht zugeordnet" (same rule as `TripState.groups`/`unassignedItems` and
   `ListDetailCubit.storeFor`). Empty stores (a household store with no items on this list) are **not**
   printed; the „Noch nicht zugeordnet" section renders only when it has items.
5. **(AC5 — availability & German UI)** „Drucken / Teilen" is offered only on an **Open** list
   (hidden on In-Trip and Done, same `!state.isReadOnly` gate as „Einkauf starten"). All new copy is
   German, resolved through the localization layer (no hard-coded user-facing strings); quantities
   reuse the existing `QuantityFormatter` so printed quantities read identically to the screen.

## Tasks / Subtasks

- [x] **Task 1 — Add dependencies (AC1, AC2; CLAUDE.md §7).** (AC: 1,2)
  - [x] Add `printing: ^5.15.0` to `app/pubspec.yaml` `dependencies` (it pulls in `pdf: ^3.13.0`);
        run `flutter pub get`. These are the current stable majors — pin them explicitly.
  - [x] No new asset needed: the bundled `assets/fonts/Inter-Variable.ttf` (already declared) is the
        PDF font.

- [x] **Task 2 — Pure grouping function (AC1, AC4).** (AC: 1,4)
  - [x] Create `app/lib/features/lists/print/list_print_grouping.dart` — a **pure**, Flutter-free
        function/model that takes the list's `List<Item>` + `List<StoreSummary>` and returns ordered
        groups: one `PrintStoreGroup { StoreSummary store; List<Item> items }` per active store that
        has items (in `stores` order), then a trailing unassigned `List<Item>` (items whose `storeId`
        is null, not in `stores`, i.e. archived/absent). Reuse the **exact resolution rule** from
        `TripState.groups`/`unassignedItems` + `ListDetailCubit.storeFor` (DRY — do not invent a
        second grouping rule). Items keep list creation order (input order).
  - [x] No `chainId → name` resolution here (keep it pure); the document builder resolves chain names
        from the reference list passed in.

- [x] **Task 3 — PDF document builder (AC1, AC3).** (AC: 1,3)
  - [x] Create `app/lib/features/lists/print/list_print_document.dart` — builds a `pw.Document` /
        `Uint8List` from: grouping (Task 2), the resolved chain names, the list title, `now`,
        `AppLocalizations`, and the Inter font bytes. Load the font via
        `rootBundle.load('assets/fonts/Inter-Variable.ttf')` → `pw.Font.ttf(byteData)` and set it as
        the document's base `pw.ThemeData` font. **Note the variable-font caveat:** the `pdf` package
        does not apply `wght` axes, so it renders Inter's default instance — use size/weight-name for
        visual hierarchy, not `FontVariation`. Faux-bold via `pw.FontWeight.bold` on the same face is
        acceptable for headers.
  - [x] Layout mirrors `screen-print-share.html` frame 2: header = list name + de-DE date (reuse
        `DateFormatter`); per group a header row (store name + quiet chain label) then item rows, each
        = empty tick-box square + name + quantity (+ „· note" when present); a „Noch nicht zugeordnet"
        group last (no chain label). Use tabular figures for quantities.
  - [x] Quantity text: reuse `QuantityFormatter().format(double.tryParse(item.amount) ?? 0,
        unitFromServerName(item.unit) ?? Unit.piece, localizations)` — identical to the on-screen row
        (`_ItemRow._formatQuantity`). Do not reformat quantities a second way.
  - [x] The builder returns bytes only (`await doc.save()`); it must not touch the filesystem.

- [x] **Task 4 — Print/share service seam (AC2, AC3; CLAUDE.md §6).** (AC: 2,3)
  - [x] Create `app/lib/features/lists/print/list_print_service.dart` — a thin injectable port over
        the `printing` plugin: `Future<void> printDocument(Uint8List bytes)` →
        `Printing.layoutPdf(onLayout: (_) => bytes)`; `Future<void> shareDocument(Uint8List bytes,
        {required String filename})` → `Printing.sharePdf(bytes: bytes, filename: filename)`. This is
        the **only** file that imports `package:printing` — the sheet and tests depend on the
        abstraction, so a fake asserts "invoked with bytes, no path" (isolate the external system at
        the boundary).
  - [x] Filename is a share hint only (e.g. sanitized `"<list title>.pdf"`), never a save path.

- [x] **Task 5 — Print/Share bottom sheet (AC1, AC2, AC3, AC5, UX-DR19).** (AC: 1,2,3,5)
  - [x] Create `app/lib/features/lists/print/print_share_sheet.dart` with
        `Future<void> showPrintShareSheet(BuildContext, {required String title, required List<Item>
        items, required List<StoreSummary> stores, required StoresApi storesApi, required
        StoreChainReferenceCache referenceCache, ListPrintService service = const ...})` following the
        `showStorePickerSheet` `showModalBottomSheet` pattern.
  - [x] Sheet content (mockup frame 1): grab handle, title = the list name, two rows — „Drucken"
        (subtitle „Nach Geschäft gruppiert") and „Als PDF teilen" (subtitle „z. B. an WhatsApp
        senden") — and the reassurance banner „Es wird keine Datei dauerhaft gespeichert.". Each row
        ≥ 48px tap target with a `Semantics(button: true, label: …)` (NFR10/UX-DR5).
  - [x] Load chain names (`referenceCache.load(storesApi)`, degrade to no-chain-labels on failure —
        mirror the cubit's degrade-and-log pattern), build grouping (Task 2) + document (Task 3), then
        call the service's print or share. A guard prevents a double build/dispatch on rapid taps.
  - [x] Genuine plugin failures surface a brief `SnackBar` (localized generic error); a user
        cancelling the OS dialog/share is **not** an error (no message).

- [x] **Task 6 — Wire the entry button into list-detail (AC5).** (AC: 5)
  - [x] In `list_detail_page.dart` `_ReadyBody`, inside the existing `if (!state.isReadOnly)` block
        (next to „Einkauf starten"), add a **tonal** `SgartButton` (key `list-detail-print-share`)
        labelled „Drucken / Teilen" that calls `showPrintShareSheet(...)` with `title`, `state.items`,
        `state.stores`, and the `StoresApi`/`StoreChainReferenceCache` already provided to this route.
  - [x] Order/spacing: keep „Einkauf starten" primary-of-the-two; „Drucken / Teilen" as the quieter
        peer. Both hidden on In-Trip/Done.

- [x] **Task 7 — Localization (AC5).** (AC: 5)
  - [x] Add German strings to `lib/l10n/app_de.arb` (German-only project — no `app_en.arb`), each with
        an `@key` description block: `printShareAction` („Drucken / Teilen"), `printOptionLabel`
        („Drucken"), `printOptionSubtitle` („Nach Geschäft gruppiert"), `shareOptionLabel` („Als PDF
        teilen"), `shareOptionSubtitle` („z. B. an WhatsApp senden"), `printShareNoFileSaved` („Es wird
        keine Datei dauerhaft gespeichert."), plus the print document's header/section copy as needed.
  - [x] **Reuse `tripUnassignedGroupLabel` ("Noch nicht zugeordnet")** for the print's unassigned
        section rather than adding a duplicate string (DRY). Re-run codegen (`flutter gen-l10n` runs
        via build; the project uses `generate: true`).

- [x] **Task 8 — Tests (CLAUDE.md §6; test pyramid).** (AC: 1,2,3,4,5)
  - [x] `test/features/lists/print/list_print_grouping_test.dart` — unit tests for the pure grouping:
        items across two stores + unassigned; item assigned to an archived/absent store → unassigned;
        empty list → no groups; all-unassigned → only the unassigned group; group + item ordering.
  - [x] `test/features/lists/print/list_print_document_test.dart` — the builder returns non-empty
        bytes for a representative list and does not throw with notes / unassigned items / an empty
        list (light: assert bytes non-empty; content-level assertions are out of scope for the pdf
        renderer).
  - [x] `test/features/lists/print/print_share_sheet_test.dart` — widget test with a **fake
        `ListPrintService`**: the sheet shows both options + the reassurance; tapping „Drucken"
        invokes `printDocument` with bytes; tapping „Als PDF teilen" invokes `shareDocument` with
        bytes and a filename; the fake asserts it was handed **bytes, never a path** (AC3). Assert the
        options carry `Semantics` button labels.
  - [x] `test/features/lists/presentation/list_detail/…` — the „Drucken / Teilen" button renders on an
        Open list and is **absent** on a read-only (In-Trip/Done) list (AC5), and opens the sheet.
  - [x] Guard AC3 structurally: the fake-service tests plus keeping `package:printing` confined to
        `list_print_service.dart` (no `path_provider`/`File` import anywhere in `features/lists/print/`)
        are the enforceable "no persisted file" evidence.

- [x] **Task 9 — Green build (CLAUDE.md §6).** (AC: all)
  - [x] Run the full app suite: `flutter test` **and** `flutter analyze` (0 issues). Report the
        counts. Backend is untouched — no `./gradlew` run is needed for this story, and say so
        explicitly in the completion notes (a green build here = the Flutter suite).

## Dev Notes

### Why client-only (and what that changes)
FR11/CAP-11 is a device capability: native OS print + in-memory PDF, no server round-trip. The list's
items and their store assignments are already in `ListDetailState` (loaded by `ListDetailCubit.bootstrap`
for an Open list). So there is **nothing** to add on the aggregate, projector, or REST side. Do not
create a "print query" or a backend PDF endpoint — that would be YAGNI and would leak a presentation
concern into the domain (Separation of Concerns).

### Existing pieces to reuse (DRY — do not reinvent)
- **Grouping rule:** `app/lib/features/trips/presentation/trip_state.dart` — `groups` /
  `unassignedItems` / `_isActiveStore` / `storeFor` express exactly the store-resolution rule the
  print needs (assigned-and-active → its store; else unassigned). Mirror it in the pure grouping
  function; consider a shared helper if it reads cleanly, but a faithful re-expression is acceptable.
- **Quantity text:** `QuantityFormatter` (`l10n/formatting/quantity_formatter.dart`) +
  `unitFromServerName` — the on-screen row uses these (`list_detail_page.dart:339`). The printout must
  produce byte-identical quantity strings.
- **Date text:** `DateFormatter` (`l10n/formatting/date_formatter.dart`, `formatDate`) for the header
  date. `intl` locale symbols are already initialized in `main.dart`.
- **Bottom-sheet shape:** `showStorePickerSheet` (`stores/presentation/store_picker_sheet.dart`) —
  `showModalBottomSheet`, grab handle, tap rows, `SgartButton`/`SgartShapes` tokens.
- **Chain-name resolution:** `StoreChainReferenceCache.load(StoresApi)` → `List<StoreChain>` (each has
  a display name); resolve `StoreSummary.chainId` against it for the group header's chain label. Degrade
  to no chain label (log, don't fail) if the reference list is unavailable — same posture as the
  cubit's store/suggestion load.
- **Button:** `SgartButton` with `SgartButtonVariant.tonal` (the „Einkauf starten" button is the
  template — `list_detail_page.dart:200`).

### Files being touched
- **UPDATE** `app/pubspec.yaml` — add `printing` (+ transitive `pdf`).
- **UPDATE** `app/lib/features/lists/presentation/list_detail/list_detail_page.dart` — add the
  „Drucken / Teilen" tonal button in the `!state.isReadOnly` block; it already reads `StoresApi` +
  `StoreChainReferenceCache` from its providers (lines 63–64) and holds `state.items`/`state.stores`.
  Preserve the existing „Einkauf starten" flow untouched.
- **UPDATE** `app/lib/l10n/app_de.arb` — new keys (Task 7).
- **NEW** `app/lib/features/lists/print/{list_print_grouping,list_print_document,list_print_service,print_share_sheet}.dart`.
- **NEW** tests under `test/features/lists/print/` and an addition to the list-detail page test.

### The "no persisted file" guarantee — be precise
Both paths call the plugin with in-memory bytes. `Printing.layoutPdf` never writes a file.
`Printing.sharePdf` hands the OS share sheet the bytes; on iOS/Android the platform may materialize a
temporary cache file so the receiving app (WhatsApp) can read it — that is OS-managed and short-lived,
**not** an app write to persistent storage. Our contract, and what the tests assert, is: our code
constructs bytes via `doc.save()` and never imports/uses `path_provider`, `File`, or a
documents/gallery directory. The reassurance copy communicates this to the member.

### Accessibility & polish (applicable DoD)
- Sheet option rows and the entry button: ≥ 48px targets, `Semantics(button: true, label: …)` (NFR10,
  UX-DR5). Labels wrap, never clip (UX-DR4 — `SgartButton` already does this).
- No dead strings/fields/stale comments; fail-fast guards on inputs (e.g. a blank list title falls
  back gracefully). No `commandId`/optimistic state exists here — do not add ceremony that has no
  state change behind it.

### Project Structure Notes
- New code lives under `app/lib/features/lists/print/` — the print concern belongs to the lists
  feature (its entry point is list-detail), and separating grouping (pure) / document (pdf) /
  service (plugin) / sheet (UI) keeps each file single-responsibility and lets the pure and
  seam-level logic be unit-tested without the plugin. This matches the feature-first, BLoC-per-screen
  layout and the "isolate external systems at the boundary" rule.
- No conflict with the unified structure; no backend package changes.

### References
- [Source: _bmad-output/planning-artifacts/epics.md#Story 3.5: Print and share the list]
- [Source: _bmad-output/planning-artifacts/epics.md#FR11 (CAP-11)] — print & share, no persisted PDF.
- [Source: _bmad-output/planning-artifacts/epics.md#UX-DR19] — print preview & share.
- [Source: ux-designs/ux-sgart-2026-08-20/.working/screen-print-share.html] — the two frames this
  story realizes (share sheet + grouped print preview with tick boxes).
- [Source: _bmad-output/implementation-artifacts/deferred-work.md] — "print layout (Story 3.5)
  consumes" the Story 2.6 store assignment; no trip/print UI built in 2.6.
- [Source: app/lib/features/trips/presentation/trip_state.dart] — grouping rule to mirror.
- [Source: app/lib/features/lists/presentation/list_detail/list_detail_page.dart] — entry point +
  „Einkauf starten" button template.
- [Source: app/lib/features/stores/presentation/store_picker_sheet.dart] — bottom-sheet pattern.
- [Source: app/lib/l10n/formatting/{quantity_formatter,date_formatter}.dart] — reuse for parity.
- [Source: https://pub.dev/packages/printing] — `printing` 5.15.0 (bundles `pdf` 3.13.0);
  `layoutPdf(onLayout:)` / `sharePdf(bytes:, filename:)`, in-memory bytes.

### Review Findings

Code review 2026-09-05 (Opus 4.8; layers: Edge Case Hunter + Acceptance Auditor — Blind Hunter skipped, thin client-only slice). 0 decision-needed, 2 patch, 1 deferred, 2 dismissed. No high/medium findings — AC1–AC5 all satisfied, AC3 no-persist contract structurally airtight.

- [x] [Review][Patch] Chain-reference load race — FIXED (2026-09-05): the sheet now stores the load future (`_chainReferenceLoad`) in `initState` and `_buildDocumentBytes` awaits it (capturing `AppLocalizations` before the await) before building, so a fast tap gets the chain labels instead of a document built against an empty reference. The future never errors (failures still swallowed in `_loadChainReference` → accepted degrade). [app/lib/features/lists/print/print_share_sheet.dart]
- [x] [Review][Patch] DRY subtitle/quantity composition — FIXED (2026-09-05): extracted `formatItemQuantity` + `formatItemSubtitle` into `app/lib/features/lists/item_display_text.dart`; both the on-screen `_ItemRow` and `ListPrintDocument._itemRow` now call the shared functions (the private `_formatQuantity` copies and the inline subtitle join are gone), giving the join rule a single representation. [app/lib/features/lists/item_display_text.dart]
- [x] [Review][Defer] Empty-string note renders a dangling " · " separator — deferred, pre-existing (identical on-screen behavior at list_detail_page.dart:287; AC5 requires print/screen parity, so this belongs to both call sites, not to this story). [app/lib/features/lists/print/list_print_document.dart:88]

Dismissed (recorded for audit): duplicate-`storeId` groups (unreachable — `storeId` is unique per household store; the mirrored `trip_state.dart` rule has no guard either); unparseable `amount` → prints `0` (parity-correct with on-screen `_formatQuantity`; `amount` is a server-guaranteed decimal string).

## Dev Agent Record

### Agent Model Used

(planning: Claude Opus 4.8; implementation: Claude Sonnet 5)

### Debug Log References

None — no HALT conditions hit; implementation proceeded task-by-task per plan with no rework.

### Completion Notes List

- **Client-only, as scoped.** No backend changes — no new event/command/aggregate/read model/
  projector/migration/endpoint/`commandId`. Confirmed no `./gradlew` run was needed; the green build
  for this story is the Flutter suite alone.
- All 9 tasks implemented as planned, no deviations. `printing ^5.15.0` (+ transitive `pdf ^3.13.0`,
  explicitly added as a direct dependency too since `list_print_document.dart` imports it directly)
  both confirmed as the current stable pub.dev majors before adding (CLAUDE.md §7).
- `ListPrintGrouping.from` mirrors `TripState.groups`/`unassignedItems`' exact resolution rule
  (assigned-and-active → its store group, else unassigned) over the list's active `stores` instead of
  a trip's store subset — one rule, two call sites, no DRY violation.
- `ListPrintDocument` resolves chain names itself from the passed `List<StoreChain>` reference list
  (mirrors `store_picker_sheet._chainNameFor`); the grouping stays pure/chain-agnostic per Task 2.
  Confirmed the variable-font caveat empirically: `pw.ThemeData.withFont` only accepts distinct
  static-font slots, so `bold` is set to the **same** Inter font as `base` (not left to default to a
  Helvetica-Bold fallback) — headers get `pw.FontWeight.bold` for semantic weight even though the
  rendered glyphs are visually identical to body text (no true bold face available). "Tabular
  figures" is approximated as a right-aligned quantity column rather than an OpenType feature flag —
  the `pdf` 3.13 API exposes no font-feature control to request true tabular figures.
- AC3 ("no persisted file") is enforced structurally, not just by test assertion: `package:printing`
  is imported **only** by `list_print_service.dart` (verified via `grep`), and no
  `path_provider`/`File`/`dart:io` import exists anywhere under `features/lists/print/` (verified via
  `grep`) — the fake-service tests assert the same contract behaviourally (bytes handed over, no path
  parameter exists to misuse).
- The print/share button is **not** gated on `state.isSubmitting` (unlike „Einkauf starten"): it
  never touches list/command state, so there is nothing for it to race or block on — adding that
  ceremony would be scope creep with no behavior behind it (Dev Notes' "no ceremony" guidance).
- A blank list title (defensive-only; list names are `nameRequired`-validated server-side, so this
  path is not reachable through the app today) falls back to a generic `printShareFilename` string in
  both the PDF header and the share filename — the fail-fast/graceful-fallback DoD item, covered by a
  dedicated document-builder test.
- Verified `rootBundle.load('assets/fonts/Inter-Variable.ttf')` resolves correctly under plain
  `flutter test` (no widget pump needed) — confirmed empirically via the document builder's own unit
  tests before writing the sheet's widget tests.
- a11y: both sheet option rows and the entry button meet the 48px minimum target and carry
  `Semantics(button: true, label: …)`; asserted via `matchesSemantics` in the widget test.

### File List

**Flutter — new files**
- `app/lib/features/lists/print/list_print_grouping.dart`
- `app/lib/features/lists/print/list_print_document.dart`
- `app/lib/features/lists/print/list_print_service.dart`
- `app/lib/features/lists/print/print_share_sheet.dart`
- `app/test/features/lists/print/list_print_grouping_test.dart`
- `app/test/features/lists/print/list_print_document_test.dart`
- `app/test/features/lists/print/print_share_sheet_test.dart`
- `app/test/support/fake_print_dependencies.dart`

**Flutter — modified files**
- `app/pubspec.yaml` — added `printing ^5.15.0` + `pdf ^3.13.0` dependencies
- `app/lib/l10n/app_de.arb` — 7 new localization strings (Task 7)
- `app/lib/features/lists/presentation/list_detail/list_detail_page.dart` — added the „Drucken /
  Teilen" tonal button in the `!state.isReadOnly` block
- `app/test/features/lists/presentation/list_detail/list_detail_page_test.dart` — 2 new tests for the
  print/share entry button (AC5)

## Change Log

- 2026-09-05: Story implemented (dev-story, Sonnet 5) — all 9 tasks complete, no deviations from
  plan. `flutter analyze`: 0 issues. `flutter test`: 509 passed, 0 failed (up from the 491 baseline —
  18 new tests: 6 grouping + 4 document + 6 sheet + 2 list-detail). Backend untouched by design
  (client-only story) — no `./gradlew` run needed.

- 2026-09-05: Story drafted (create-story, Opus 4.8) — Epic 3's fifth and final story: **print &
  share (FR11/CAP-11), a client-only Flutter slice with no backend work at all** (no event/command/
  aggregate/read model/projector/migration/endpoint/`commandId`). Adds a grouped-by-store PDF
  projection of an Open list, rendered to the native OS print dialog (`Printing.layoutPdf`) or an
  in-memory PDF share sheet (`Printing.sharePdf`), from a „Drucken / Teilen" tonal button peer to
  „Einkauf starten" opening the mockup's bottom sheet. Reuses the trip grouping rule,
  `QuantityFormatter`/`DateFormatter`, the store picker's sheet pattern, and `StoreChainReferenceCache`
  for chain labels. New deps `printing ^5.15.0` (+ `pdf ^3.13.0`). **Timo decided (2026-09-05):**
  (1) availability = **Open lists only** (peer to „Einkauf starten", same `!isReadOnly` gate — all
  items OPEN, empty tick boxes, no read-only store-loading change); (2) entry = **tonal button +
  bottom sheet** (not the not-yet-existing app-bar ⋯ menu); (3) group header = **store name + chain
  label** (chain resolved via the reference cache). "No persisted file" contract = our code only ever
  hands over `doc.save()` bytes and never touches `path_provider`/`File` (the native share sheet's
  OS-managed temp/cache file is not an app write) — enforced via a fake `ListPrintService` and by
  confining `package:printing` to that one seam. Baseline last-green ≈ Flutter 491 (backend
  untouched).
