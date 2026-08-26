---
baseline_commit: 82ebedc68bf85a3978f2c330c57cca8ccc51db10
---

# Story 2.2: Listen overview with Offen/Erledigt filter

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a member,
I want an overview of my lists with a segmented Offen/Erledigt filter,
so that I can jump into the right open list and glance at finished ones in a read-only archive.

## Acceptance Criteria

1. **The Listen tab shows the household's Open lists with a „+ Neue Liste" action, and a segmented „Offen" / „Erledigt" filter switches between the Open lists and the Done archive.** (FR4, UX-DR11, AD-4)
   - The Listen tab renders a segmented **„Offen" / „Erledigt"** control (`SegmentedButton`) at the top; **„Offen"** is the default selection. Selecting a segment switches the body between the **Open lists** (the Story 2.1 surface: each list's name or derived „Liste N", per-row rename, „+ Neue Liste") and the **Done archive** (AC2).
   - **The „+ Neue Liste" create action and per-row rename affordances belong to the „Offen" view only** — they are **absent** from the „Erledigt" archive (AC2 read-only).
   - Each Open row carries its **status label** („Offen") on its own line under the name via the shared `StatusLabel` (DESIGN §4b, UX-DR21) — the overview is where the row-status convention lands.
   - **Item counts / progress are out of scope for 2.2 (LOCKED Clarification 1).** Items are Story 2.3 (they light up the per-row count in the row this story builds); check-off *progress* is Epic 3. **Do not** add an `item_count` column, a progress bar, or any „N Artikel" copy here — no capability produces items yet (YAGNI, retro premature-value rule).

2. **The „Erledigt" filter lists Done lists as a read-only archive — no create, no rename, no item commands.** (FR4, UX-DR11)
   - Selecting **„Erledigt"** shows the household's **Done** lists as a **read-only archive**: rows render the list's own name (or a neutral fallback label for an unnamed archived list — see Dev Notes), with a **`DONE` status label**, and **no** „+ Neue Liste", **no** rename affordance, and **no** ⋯/item actions. The archive is a **pure query** — it issues no commands.
   - The read path is a new query **`ListDoneLists`** (mirror `ListOpenLists`) reading through the **existing** `ShoppingListReadModel.listsOf` port (which already returns every status), filtering to `DONE`. Exposed on the existing controller via a **`?filter=open|done`** query parameter (default `open`, back-compatible with 2.1's client and tests).
   - **Empty until Epic 3, by construction.** A list becomes `DONE` only at **trip completion (Epic 3)** — no Epic-2 command drives a list out of `OPEN`. So in Epic 2 the archive is **always empty** and shows a calm archive empty-state. The filter, the query, and the read-only rendering are **built and tested now** (with synthetically-seeded `DONE` read-model rows); the archive fills for real when Epic 3 adds the trip-completion event. **Do not** fabricate an Epic-3 completion/trip event to force a `DONE` list (premature-value rule, retro action item #4).
   - **Server-side read-only invariant already stands:** the `DONE`-rejects-rename guard coded in 2.1 remains; item commands do not exist until Story 2.3. AC2's "no item commands accepted on a Done list" is therefore satisfied structurally in Epic 2 and gains its end-to-end coverage in Epic 3 alongside the events that produce `DONE` (already logged in `deferred-work.md`).

## Tasks / Subtasks

- [x] **Task 1 — `ListDoneLists` query (read side; Done archive)** (AC: #2)
  - [x] Add `ListDoneLists` in `collaboration.application.query`, a **near-exact mirror of `ListOpenLists`**: compose `ResolveMemberIdentity` (membership check → `403` for a non-member) with `ShoppingListReadModel`, read `listsOf(householdId)` (the port already returns every status — no port change), **filter `status == ListStatus.DONE`**, map to the existing `ListOpenLists.ShoppingListSummary` record (reuse it — DRY; do **not** duplicate the summary type), returning Done lists in **creation order** (the port's order; there is no completion timestamp in the read model yet — note this in the Javadoc for Epic 3). No side effects (CLAUDE.md §6 CQRS coverage). Never accept a `MemberId` from the client.
  - [x] Unit test `ListDoneListsTest` (in-memory `ShoppingListReadModel` double seeded with **mixed** OPEN/DONE `ShoppingListView`s — legitimate: the test seeds a read-model *shape*, it does **not** fabricate a domain transition): returns **only** the `DONE` lists in creation order; excludes OPEN lists; a **non-member is rejected** (`403`); the query is **side-effect free** (a second call returns the same rows); an empty household archive returns an empty list. Full-sentence behavior names (e.g. `doneListsAreReturnedAsAReadOnlyArchiveInCreationOrder`, `openListsAreExcludedFromTheArchive`, `aNonMemberCannotReadTheArchive`).

- [x] **Task 2 — REST: `?filter=open|done` on the list GET** (AC: #1, #2)
  - [x] Extend `ShoppingListController`'s `GET /api/v1/households/{householdId}/lists` with an **optional `@RequestParam` `filter`** defaulting to `"open"`: `open` → `listOpenLists.forHousehold(...)` (unchanged path), `done` → the new `listDoneLists.forHousehold(...)`. Both return the same `[{listId, name, status}]` shape (`ShoppingListSummaryResponse`). Inject `ListDoneLists` alongside `ListOpenLists`.
  - [x] **Fail fast on a bad filter:** an unrecognized `filter` value is a **`400`** with a stable `code` (add `command.listFilterInvalid` to `CommandFieldTranslations` and translate there — DRY, mirroring the other `command.*Invalid` codes; do **not** hand-roll the check in the controller). Map it in `WriteErrorAdvice` if it surfaces a new exception type, else reuse `InvalidCommandEnvelopeException` (`400`) as the other malformed-input translators do.
  - [x] Wire the `ListDoneLists` bean in the existing collaboration config (the same class that wires `ListOpenLists` — `HouseholdApplicationConfig`; the §8 rename of these `Household*Config` classes to collaboration-context names is a **recorded defer**, not this story's job).
  - [x] MockMvc slice tests (extend `ShoppingListControllerTest`, `jwt()` — no live Keycloak): `GET` (no param) still returns the **Open** lists (2.1 back-compat); `GET ?filter=open` returns Open; `GET ?filter=done` returns the **caller's Done** lists (seed the in-memory read-model double with DONE rows and **honor `householdId`** — the 2.1 review fixed the double to respect its argument; keep that); `GET ?filter=bogus` → `400` `command.listFilterInvalid`; `401` unauthenticated; a **non-member** `403`; caller identity from the JWT `sub` only. **Per-household isolation:** the `?filter=done` slice must assert a second household's Done lists are excluded (mirror the 2.1 isolation test the review added).

- [x] **Task 3 — Flutter: Offen/Erledigt segmented filter + read-only archive** (AC: #1, #2)
  - [x] **Data:** add `listDoneLists(householdId)` to `ShoppingListsApi` (GET with `?filter=done` over `AuthenticatedHttpClient.getJsonList`, mirroring `listOpenLists`; keep `listOpenLists` calling the default/`?filter=open` endpoint). Reuse the existing `ShoppingListSummary` model unchanged.
  - [x] **State:** extend `ShoppingListsState` with a **`ListFilter { offen, erledigt }`** (default `offen`) and the archive: a `List<ShoppingListSummary> doneLists` plus a small archive load sub-status (`idle`/`loading`/`ready`/`failure`) so a failed archive load does **not** tear down the Open view (mirror how `actionError` is kept separate from `loadError`). **Keep the existing `lists` field as the Open lists** (do not rename it — avoids churn on 2.1's tested surface; add, don't reshape). Keep `==`/`hashCode`/`copyWith` complete and correct (the state is value-compared in tests).
  - [x] **Cubit:** add `selectFilter(ListFilter)` to `ShoppingListsCubit`. Switching to `erledigt` **lazily loads the archive on first selection** (idempotent — cache the result; Erledigt is empty/rare in Epic 2, so don't fetch it in `bootstrap`), with every `emit` **guarded by `isClosed`** and `AppException`→`AppError` mapping (the established cubit contract). Switching back to `offen` needs no refetch. The archive is **read-only** — the cubit exposes **no** create/rename path from the Erledigt view.
  - [x] **View:** add a `SegmentedButton<ListFilter>` at the top of `ListsView` (segments „Offen"/„Erledigt", localized; stable `Key`s; a11y — `SegmentedButton` labels are the localized copy, and the control honors the 48px target / Dynamic Type per DESIGN §5). Render:
    - **Offen** → the existing open-lists body **plus** an „Offen" `StatusLabel` (`StatusLabelVariant.neutral`) under each row name (DESIGN §4b); keep rename + „+ Neue Liste".
    - **Erledigt** → a **read-only archive**: rows show the list name (or the unnamed-archived fallback, Dev Notes) with a `DONE` `StatusLabel` (`StatusLabelVariant.neutral` — a dedicated Done tint is an Epic-3 polish), **no** rename, **no** „+ Neue Liste", **no** ⋯; a calm archive empty-state (`listsArchiveEmptyState`) while empty (always, in Epic 2); a loading indicator and a mapped failure message for the archive load.
  - [x] **l10n** (`app/lib/l10n/app_de.arb`, English keys / German values; regenerate `app_localizations*`): `listsFilterOpen` („Offen"), `listsFilterDone` („Erledigt"), `listsArchiveEmptyState` (calm plain-German, e.g. „Noch keine erledigten Listen."), `listStatusOpen` („Offen") and `listStatusDone` („Erledigt") for the row `StatusLabel`s, plus `listsArchiveUnnamedFallback` („Liste") for an unnamed archived row. Map the new `command.listFilterInvalid` code in `error_message_resolver.dart` (defensive — the client never sends a bad filter, but the resolver must cover every server code, per the retro DoD). **No** count/progress strings (deferred).
  - [x] **Flutter widget/cubit tests** (stub the HTTP boundary; reuse `test/support/widget_test_harness.dart` and `fake_shopping_lists_dependencies.dart`): default filter is **Offen** and shows the create action; switching to **Erledigt** loads and renders the archive **read-only** — assert the „+ Neue Liste" button and rename affordances are **absent** (seed the fake api's `listDoneLists` with synthetic DONE rows to prove read-only rendering); an **empty archive** shows `listsArchiveEmptyState`; switching **back to Offen** restores create + rename; an archive **load failure** maps to localized copy without tearing down the Open view; the segmented control exposes localized a11y labels. Added cubit coverage for `selectFilter` (Offen→Erledigt lazy-loads once and caches; Erledigt→Offen→Erledigt does not refetch) matching this file's existing plain-`test`/`FakeShoppingListsApi` convention rather than introducing `bloc_test` (the repo's cubit tests do not use it — consistency over ceremony).

- [x] **Task 4 — Guardrails, deferred-work, and green suites** (AC: #1, #2)
  - [x] **No new backend or Flutter deps.** No new events, **no new read-model column, no Flyway migration** (the archive reads existing `shopping_list_read_model` rows; Done rows simply don't exist yet in Epic 2). `SegmentedButton`/`StatusLabel` are Material/existing-shared — nothing to add. If touching any dependency, honor CLAUDE.md §7 — but this story adds none.
  - [x] Keep **all ArchUnit rules green** (`HexagonalArchitectureTest`): `ListDoneLists` lives in `application.query` and composes the domain read-model **port** + the Identity **application** port only; the controller/advice stay in `adapter.in` and never import `..domain..` (DTOs cross as plain `String`s — the established §8 seam). No new persisted personal data (no schema change) — `NoPersistedPersonalDataTest` stays green unchanged.
  - [x] `package-info.java` stays accurate for each touched layer (§8). No new domain types, events, or codec entries (no wire-format change).
  - [x] **Append to `deferred-work.md`** (do not fabricate these now): (a) **item counts** on overview rows land in **Story 2.3**; **progress** (checked/total) lands in **Epic 3** with check-off; (b) the **archived-unnamed-list label** and a dedicated **`DONE` status tint** are finalized in **Epic 3** when Done lists become reachable; (c) reaffirm the 2.1 defers now made concrete by this story — the **`DONE`-rejects-rename / archive read-only e2e** is exercised only with synthetic read-model rows until Epic 3 produces `DONE`, and `ListOpenLists` (and now the ordinal source) must include `IN_TRIP` in Epic 3.
  - [x] Run **both suites locally for real** before review (memory `backend-test-hygiene`, `flutter-test-local`). Backend: `cd backend && ./gradlew test` (unit + ArchUnit + Testcontainers — needs Docker; the context-loads-with-infra-down tests still pass with containers stopped). Client: `export PATH="$PATH:/home/timo/tools/flutter/bin"; cd app && flutter analyze && flutter test`. Zero new analyzer/compiler warnings. **Baseline green counts to keep and grow: backend 260, Flutter 266** (Story 2.1 close). Report which suite(s) ran (a green build means the **full** suite ran).

## Dev Notes

### Scope & intent
**This is a thin, right-sized overview story — the second story of Epic 2 — and it is deliberately narrow because most of what UX-DR11 pictures depends on later capabilities.** 2.1 already shipped the aggregate, the read model, the `ListOpenLists` query, and a minimal open-lists surface. 2.2 adds exactly two things on top: **(1) a segmented Offen/Erledigt filter** and **(2) a read-only Done archive** (a new sibling query + a `?filter` param + read-only rendering). It writes **no new events, no migration, no domain types** — it is a read-side + presentation slice reusing 2.1's contracts.

**Two capabilities in AC1/UX-DR11 are explicitly OUT of 2.2 (both LOCKED with Timo — see Clarifications):**
- **Item counts / progress** — items are Story **2.3**; check-off *progress* is **Epic 3**. No list has any items yet, so counts would be a noisy „0" on every row and progress has no source. **Deferred**: 2.3 lights up the per-row count in the row this story builds; the progress bar lands in Epic 3. **Do not add an `item_count` column or any count/progress UI here.**
- **A populated Done archive** — a list reaches `DONE` only at **trip completion (Epic 3)**. So the „Erledigt" archive is **structurally complete but always empty in Epic 2**. Build the filter, the `ListDoneLists` query, and the read-only rendering now (tested with synthetic `DONE` read-model rows); it fills for real in Epic 3. **Do not** fabricate an Epic-3 completion event to force a `DONE` list.

This is a direct application of the Epic-1 retro's **premature-value rule** (action item #4, owner PM): don't author a surface whose value depends on a later-epic capability — ship the structural scaffold and the empty-state, and let the later story light it up.

### Source of truth: epics + ARCHITECTURE-SPINE + UX (binding)
[Source: epics.md §"Story 2.2" (lines 491–505); architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md; ux-designs/ux-sgart-2026-08-20/EXPERIENCE.md, DESIGN.md]
- **UX-DR11 (EXPERIENCE.md 109–111):** the Listen overview = several Open lists with progress + auto-name „Liste N"; a segmented **Offen / Erledigt** filter; **Done lists = read-only archive, no separate history tab**; „+ Neue Liste". (The „progress" part is the deferred piece — Clarification 1.)
- **No „Verlauf" tab (EXPERIENCE.md 44–45):** Done lists live **behind the „Erledigt" filter** in „Listen", not a separate tab — so the archive is a *filter state* of the same screen, exactly as this story builds it.
- **AD-4 / AR3 (spine 82–86):** state changes only by appending events; **read models are projection-only and never written by a command handler**; projections are eventually consistent. → 2.2 is read-only: a new **query** over the existing read model, zero new write path.
- **AD-5 / AD-6 (spine 88–98):** read models reference a person only by `MemberId`, and a list carries **no creator attribution** (list/household names are not personal data). → the archive rows carry `listId`, optional `name`, `status` only — **no PII**, no schema change, `NoPersistedPersonalDataTest` untouched.
- **AR10 / conventions (spine 132–146):** REST under `/api/v1`; error shape `{code, message, details}`; **identity from the JWT `sub`** (never body/path); no-abbreviation ubiquitous language. → the `?filter` param is validated fail-fast to a stable `code`; the caller is resolved from `sub`.
- **DESIGN §4b (133–143) / UX-DR21:** the row status label is a dense, tinted, **uppercase** tag on **its own line under the name** — never a trailing pill. The shared `StatusLabel` widget already implements this; the overview is where it earns its place (an „Offen" label now, `IN_TRIP`/`DONE` variants in Epic 3).
- **DESIGN §5 (145–156):** 48px min target, honor Dynamic Type / `MediaQuery.textScaler`, plain-language calm German, primary actions never only in overflow. The `SegmentedButton` and archive rows must honor these.

### The patterns already in the repo — read before writing (this is most of the job)
2.2 is a near-mechanical extension of 2.1's own slice. Read and mirror these:
- **Query template:** `backend/.../collaboration/application/query/ListOpenLists.java` — copy it for `ListDoneLists`, changing only the `status` filter (`DONE`) and the Javadoc. **Reuse `ListOpenLists.ShoppingListSummary`** (do not duplicate the record). The read-model **port** `collaboration/domain/readmodel/ShoppingListReadModel.java` already returns **every** status (`listsOf`) — no port change.
- **Controller:** `collaboration/adapter/in/ShoppingListController.java` — the `GET` handler gains a `@RequestParam(defaultValue = "open") String filter` and dispatches to `ListOpenLists`/`ListDoneLists`; the `ShoppingListSummaryResponse` shape is reused as-is. Follow the existing `AuthenticatedCaller.fromJwt(jwt)` identity path.
- **Translations / error codes:** `collaboration/application/CommandFieldTranslations.java` — add the `filter` translator + `command.listFilterInvalid` here (DRY), mirroring `toShoppingListId`'s `command.listIdInvalid` shape. Reuse `InvalidCommandEnvelopeException` (`400`) for the malformed value unless a clearer type reads better; if new, map it in `WriteErrorAdvice`.
- **Config wiring:** `collaboration/adapter/out/HouseholdApplicationConfig.java` wires `ListOpenLists` — add the `ListDoneLists` bean beside it. (The §8 rename of `Household*Config` → collaboration-context names is a **recorded 2.1 defer**, not this story.)
- **Controller test:** `backend/src/test/java/.../adapter/in/ShoppingListControllerTest.java` — its in-memory `ShoppingListReadModel` double **honors `householdId`** (2.1 review fix) and can be seeded with `DONE` rows; extend it for the `?filter` cases + the per-household archive isolation assertion.
- **Client feature:** `app/lib/features/lists/` — `data/shopping_lists_api.dart` (add `listDoneLists`), `presentation/shopping_lists_state.dart` (add `ListFilter` + `doneLists` + archive sub-status), `presentation/shopping_lists_cubit.dart` (add `selectFilter` with lazy archive load, `isClosed`-guarded emits), `presentation/lists_view.dart` (add the `SegmentedButton` + read-only archive body). Reuse `ShoppingListSummary` and the `showCreateListSheet`/`showRenameListSheet` flows — **only in the Offen body**.
- **Shared widgets:** `app/lib/shared/widgets/status_label.dart` (`StatusLabel` + `StatusLabelVariant.neutral`) for the row status label; `app/lib/shared/widgets/sgart_button.dart` for the create action; the stores view `app/lib/features/stores/presentation/stores_management_view.dart` for the empty-state + `StatusLabel` usage pattern.
- **Test harness:** `app/test/support/widget_test_harness.dart` and `app/test/support/fake_shopping_lists_dependencies.dart` — extend the fake api with a seedable `listDoneLists`; follow `app/test/features/lists/presentation/{shopping_lists_cubit_test,lists_view_test}.dart`.

### The Done archive is real but empty until Epic 3 (read carefully — same shape as 2.1's DONE-guard deferral)
AC2 requires the „Erledigt" filter to list Done lists read-only. In Epic 2 **a list is only ever `OPEN`** — the sole state-producing event is `ShoppingListCreated` (→ `OPEN`); nothing transitions a list to `DONE` until Epic 3 (trip completion). So:
- **Do** build the filter, the `ListDoneLists` query, the `?filter=done` endpoint, and the read-only archive rendering + empty-state. All of it is **correct-by-construction** and testable **now** by seeding synthetic `DONE` rows into the read-model **shape** (the query filters a projection; seeding a `DONE` `ShoppingListView` in a test double is *not* fabricating a domain transition — it exercises the query's filter, which is exactly what a unit test should isolate).
- **Do not** invent an Epic-3 completion/trip-done event to produce a real `DONE` list through the write path (the premature-value trap, retro action item #4). The end-to-end "a completed trip makes its list appear in the read-only archive" coverage is **Epic 3's**, alongside the event that produces `DONE`.
- **Unnamed archived list label:** 2.1's „Liste N" ordinal counts **Open (and later In-Trip)** lists and **excludes Done** (AC2 of 2.1). So a Done list has no ordinal in that sequence. For 2.2 the archive is empty, so pick the **simplest correct fallback** for an unnamed archived row — a plain localized „Liste" (no number) or the list's own name when present — and **record in `deferred-work.md`** that Epic 3 finalizes archived-unnamed labeling when Done lists become reachable. Do not invent a Done-ordinal scheme now (YAGNI).

### Read-only means read-only (AC2 — the invariant that must hold)
- The Erledigt view issues **no commands**: no create, no rename, no item actions (items don't exist yet anyway — Story 2.3). The cubit must not expose a write path from the archive; the view must not render „+ Neue Liste", a rename icon, or a ⋯ menu in the archive body. **Assert the absence** of those affordances in the widget test — that is the testable heart of AC2 in Epic 2.
- Server-side, the `DONE`-rejects-rename guard from 2.1 stands; item commands are unimplemented. So there is no reachable way to mutate a Done list in Epic 2 — AC2 holds structurally, and its e2e proof (with a *real* Done list) is Epic 3's (already in `deferred-work.md`).

### Previous-story intelligence (Story 2.1 done; code review 2026-08-26)
[Source: implementation-artifacts/2-1-create-and-name-multiple-open-lists.md, deferred-work.md]
- **2.1 shipped the whole read/write slice** for create + rename + list-open, proven against real KurrentDB + PostgreSQL (Testcontainers) **and a real end-to-end stack run** (which uncovered and fixed the missing `spring-boot-flyway` dep — Boot 4 modularization). 2.2 needs **no** infra work; it rides 2.1's proven contracts.
- **2.1 review fixes that this story must not regress:** the read-model re-projection uses `ON CONFLICT … DO NOTHING` for the created upsert (rename owns name updates); the controller test double **honors `householdId`** (don't reintroduce the leak-hiding bug — reuse and extend it, including a Done-archive isolation assertion); client sheets **pop only on success** and gate submit on `isSubmitting` (the archive has no sheet, but keep the Offen create/rename flow intact); the error-code family is **`list.*`** (`list.notFound`, not `shoppingList.*`) — keep the new `command.listFilterInvalid` in the existing `command.*` envelope family (a request-envelope error, like `command.listIdInvalid`, not a `list.*` domain error).
- **2.1 recorded defers this story touches/reaffirms:** `ListOpenLists`'s OPEN-only filter must include `IN_TRIP` in Epic 3 (the ordinal source) — 2.2 adds a sibling `DONE` filter but does **not** change 2.1's OPEN filter; reaffirm the Epic-3 note. The projector partial-failure robustness defer is unrelated to 2.2 (no projector change).
- **Retro DoD extension (open action item) — honor all:** `commandId` lifecycle (n/a here — the archive and filter issue no commands; do **not** add one), **error advice mapped + tested** (the `?filter` `400` path is covered in the MockMvc slice — a genuine 4xx, never a 500), **a11y labels** on the new `SegmentedButton` and archive rows, **no dead strings/fields/stale comments** (remove nothing live; every new l10n key is used), client fail-fast (n/a — no text input added).
- **Local test reality:** Flutter SDK at `/home/timo/tools/flutter/bin` (not on PATH). Run **both** suites for real (memory `backend-test-hygiene`: the backend suite was silently red for a stretch of Epic 1 because only `flutter test` was run). A green build means the **full** suite ran — say which.
- **Git:** solo, **direct-to-`main`** pre-beta (branches start at Epic 4/beta, memory `git-workflow`). Baseline = `82ebedc`.

### Latest tech notes
- **No new dependencies.** `SegmentedButton` is Material 3 (already in the Flutter SDK; the app uses Material 3 theming). `StatusLabel` is an existing shared widget. Backend adds one query class + one `@RequestParam` — no library, no event, no migration, no codec entry. If any dependency is touched, honor CLAUDE.md §7 (newest *supported* version) — but this story adds none, so no bump is expected.
- **`SegmentedButton<T>`**: use a typed `ListFilter` value, `selected: {state.filter}`, `onSelectionChanged` calling `cubit.selectFilter(...)`; give the segments localized labels and stable `Key`s; it honors `MediaQuery.textScaler` and the 48px target out of the box (DESIGN §5), but verify the labels don't clip under large text scaling in the widget test.

### Project Structure Notes
```text
backend/src/main/java/de/sgart/
  collaboration/
    application/
      query/ListDoneLists.java                 # NEW — mirror ListOpenLists, filter DONE, reuse ShoppingListSummary
      CommandFieldTranslations.java            # MODIFIED — + filter translator + command.listFilterInvalid
    adapter/in/
      ShoppingListController.java              # MODIFIED — GET gains ?filter=open|done, dispatches to the two queries
      WriteErrorAdvice.java                    # MODIFIED only if a new exception type is introduced (else reuse InvalidCommandEnvelopeException)
    adapter/out/
      HouseholdApplicationConfig.java          # MODIFIED — + ListDoneLists bean beside ListOpenLists
backend/src/test/java/de/sgart/
  collaboration/application/ListDoneListsTest.java              # NEW — archive filter, non-member 403, side-effect free
  collaboration/adapter/in/ShoppingListControllerTest.java      # MODIFIED — ?filter cases, bad-filter 400, archive per-household isolation
app/lib/features/lists/
  data/shopping_lists_api.dart                 # MODIFIED — + listDoneLists (GET ?filter=done)
  presentation/shopping_lists_state.dart       # MODIFIED — + ListFilter, doneLists, archive sub-status (keep `lists` = open)
  presentation/shopping_lists_cubit.dart       # MODIFIED — + selectFilter (lazy archive load, isClosed-guarded)
  presentation/lists_view.dart                 # MODIFIED — + SegmentedButton + read-only archive body + Offen StatusLabel
app/lib/l10n/app_de.arb (+ generated)          # MODIFIED — + filter/archive/status copy + command.listFilterInvalid
app/lib/shared/errors/error_message_resolver.dart               # MODIFIED — + command.listFilterInvalid mapping
app/test/support/fake_shopping_lists_dependencies.dart          # MODIFIED — + seedable listDoneLists
app/test/features/lists/presentation/{shopping_lists_cubit_test,lists_view_test}.dart   # MODIFIED — filter + archive tests
```
- **No new `collaboration.domain` types, no events, no read-model column, no Flyway migration, no codec change.** The ArchUnit layer rules apply unchanged (`ListDoneLists` in `application.query`; the controller never imports `..domain..`). One class per concern (SRP); no abbreviations; reuse the `shared` ids, the identity seam, and 2.1's `ShoppingListSummary`/read-model port — **do not duplicate** them.

### Testing standards
[Source: CLAUDE.md §6; ARCHITECTURE-SPINE Testing convention (line 146); NFR6]
- **Pyramid, base fast & infra-free.** `ListDoneLists` is proven with a **pure unit test** using an in-memory `ShoppingListReadModel` double + in-memory ACL — no infra. REST uses **MockMvc + `jwt()`**. No Testcontainers change needed (no schema/projector change); the existing projector/read-model integration tests stay green untouched.
- **CQRS coverage:** the **query** `ListDoneLists` is tested for the read model it returns (only `DONE`, creation order), membership gating (`403`), and that it is **side-effect free** (second call = same rows). No command is added, so no new command/event coverage.
- **Behavior, not structure** — full-sentence names, e.g. `doneListsAreReturnedAsAReadOnlyArchiveInCreationOrder`, `openListsAreExcludedFromTheArchive`, `aNonMemberCannotReadTheArchive`, `getWithoutFilterStillReturnsOpenLists`, `getWithDoneFilterReturnsTheArchive`, `anUnknownFilterIsRejectedWithFourHundred`, `anotherHouseholdsDoneListsAreExcluded`.
- **Read-only archive is the AC2 heart (client):** the widget test must assert that in the Erledigt view the „+ Neue Liste" button and the per-row rename affordance are **absent**, and that an empty archive shows `listsArchiveEmptyState`. This is the testable proof of "read-only" in Epic 2 (with synthetic Done rows, since no real Done list exists yet).
- **DSGVO:** no schema change and no new persisted data — `NoPersistedPersonalDataTest` stays green unchanged. **Synthetic data only** in every new test — fake household ids / list names (e.g. „Wocheneinkauf", „Getränke", „Alte Liste"), never real personal data.
- **Keep green & grow:** all Story-2.1 suites stay passing (**backend 260, Flutter 266** at 2.1 close). A red build blocks merge (NFR6). Update the existing lists-view/cubit tests if the added filter changes their default-render assumptions (default is **Offen**, which renders the same open-lists body 2.1 tested).

### References
- [Source: epics.md#Story 2.2: Listen overview with Offen/Erledigt filter] (lines 491–505) — user story + the two ACs
- [Source: epics.md] Epic 2 summary (lines 187–194) — "Delivers the Listen overview (with the „Erledigt" filter)"; FR4 (line 44); UX-DR8/DR11/DR21 tags (line 194)
- [Source: ux-designs/ux-sgart-2026-08-20/EXPERIENCE.md] Listen overview UX-DR11 (109–111); no-„Verlauf"-tab decision (44–45)
- [Source: ux-designs/ux-sgart-2026-08-20/DESIGN.md] §4b list-row status label / UX-DR21 (133–143); §5 accessibility overlay (145–156)
- [Source: architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md] AD-4/AR3 read-model projection-only (82–86); AD-5/AD-6 no PII (88–98); AR10 conventions (132–146)
- [Source: implementation-artifacts/2-1-create-and-name-multiple-open-lists.md] — the `ListOpenLists` query, `ShoppingListReadModel` port, `ShoppingListController`, `ShoppingListsApi`/`Cubit`/`State`/`ListsView`, and the 2.1 review fixes this story must not regress; the DONE-guard/premature-value deferral pattern this story mirrors
- [Source: backend/src/main/java/de/sgart/collaboration/**] — `application/query/ListOpenLists.java`, `domain/readmodel/ShoppingListReadModel.java` + `ShoppingListView.java`, `adapter/in/ShoppingListController.java`, `application/CommandFieldTranslations.java`, `adapter/out/HouseholdApplicationConfig.java` — the exact code to mirror/extend
- [Source: app/lib/features/lists/**, app/lib/shared/widgets/status_label.dart, app/lib/shared/widgets/sgart_button.dart, app/lib/shared/errors/error_message_resolver.dart] — the client feature, the status-label + button shared widgets, and the error-mapping template
- [Source: app/lib/features/stores/presentation/stores_management_view.dart] — an existing empty-state + `StatusLabel` usage pattern to follow
- [Source: CLAUDE.md] — Clean Code (§1), no-abbreviations naming (§2), DDD/CQRS layering (§3–§4), DSGVO (§5), TDD/full-suite testing (§6), dependency currency (§7), package structure (§8)
- [Source: memory `bmad-flow-state`, `backend-test-hygiene`, `flutter-test-local`, `git-workflow`, `language-policy`, `model-preferences`] — resume point (Epic 2, Story 2.2); run both suites for real; English keys/German values; direct-to-`main` pre-beta

## Clarifications (LOCKED by Timo 2026-08-26 — both confirmed via create-story)

1. **Item counts / progress in AC1 — how to handle when items (2.3) and check-off/progress (Epic 3) don't exist yet?** — ✅ **LOCKED: defer to 2.3 (counts) / Epic 3 (progress).** 2.2 ships the filter + archive shell only. When Story 2.3 adds items it lights up the per-row count in the row 2.2 builds; the progress bar lands in Epic 3 with check-off. **No `item_count` column and no count/progress UI in 2.2** — no capability produces items yet (YAGNI, the retro's premature-value rule, action item #4). *Alternatives (rejected):* show a placeholder „0 Artikel" now (adds a column before items exist; noise on every row) or reorder 2.2 after 2.3 (a sprint-sequence change; unnecessary once counts are cleanly deferred to 2.3).

2. **The „Erledigt" / Done archive — Done lists are unreachable until Epic 3 (trip completion). What does 2.2 build?** — ✅ **LOCKED: build the real filter + read-only archive now (empty until Epic 3).** Add the segmented Offen/Erledigt control; Offen shows the open lists (from 2.1), Erledigt renders a **read-only** archive backed by a new `ListDoneLists` read query — structurally complete and correct-by-construction, simply **empty until Epic 3** fills it. AC2's read-only semantics (no create/rename/item affordances in the archive) are **built and tested now** with synthetic `DONE` read-model rows, plus a calm archive empty-state. **Do not** fabricate an Epic-3 trip-completion event to force a real `DONE` list. *Alternative (rejected):* Offen view only + a disabled/placeholder Erledigt tab — leaves AC2's read-only archive semantics unbuilt and untested until Epic 3.

### Authoring decisions the dev may treat as settled (not open questions)
- **Query name:** `ListDoneLists` (mirrors `ListOpenLists`; reuse its `ShoppingListSummary` record — do not duplicate).
- **REST shape:** one endpoint, a `?filter=open|done` query param on the existing `GET /api/v1/households/{householdId}/lists`, default `open` (2.1 back-compat). A bad filter is a `400 command.listFilterInvalid` (request-envelope family, translated in `CommandFieldTranslations`).
- **Client filter model:** a `ListFilter { offen, erledigt }` in state (default `offen`); archive **lazily loaded** on first Erledigt selection and cached; the existing `lists` field stays the Open lists (add, don't reshape).
- **Row status label:** the shared `StatusLabel` (`StatusLabelVariant.neutral`) under the name — „Offen" on open rows, „Erledigt"/`DONE` on archive rows (a dedicated Done tint is an Epic-3 polish).
- **No new events / migration / read-model column / codec entry / process manager** in 2.2 — it is a read-side + presentation slice.

## Dev Agent Record

### Agent Model Used

Sonnet 5 (dev-story)

### Debug Log References

None — no failures requiring a debug log; both suites (backend `./gradlew test`, Flutter `flutter analyze && flutter test`) ran green on the first full run after implementation.

### Completion Notes List

- Backend: added `ListDoneLists` (mirrors `ListOpenLists`, filters `DONE`, reuses `ShoppingListSummary`) and wired it via `HouseholdApplicationConfig`. Extended `ShoppingListController`'s `GET` with `?filter=open|done` (default `open`, 2.1 back-compat), dispatching to `listOpenLists`/`listDoneLists`. Added `CommandFieldTranslations.toValidatedListFilter` (fail-fast `400 command.listFilterInvalid`), reusing the existing `InvalidCommandEnvelopeException`/`WriteErrorAdvice` mapping — no new exception type needed.
- Backend tests: `ListDoneListsTest` (5 tests — archive filter/order, OPEN exclusion, empty archive, non-member 403, side-effect-free) and 7 new `ShoppingListControllerTest` cases (no-param/`?filter=open` back-compat, `?filter=done` archive with per-household isolation, `?filter=bogus` 400, non-member 403 on the archive). Backend suite grew 260 → 271, all green (`./gradlew test`, full suite incl. ArchUnit + Testcontainers).
- Flutter: added `ShoppingListsApi.listDoneLists`, extended `ShoppingListsState` with `ListFilter`/`doneLists`/`ArchiveStatus`/`archiveError` (kept `lists` = Open, unchanged shape), added `ShoppingListsCubit.selectFilter` (lazy-load + cache the archive on first `erledigt` selection, `isClosed`-guarded), and rebuilt `ListsView` with a `SegmentedButton<ListFilter>`, an „Offen" `StatusLabel` on each Open row, and a read-only archive body (no create/rename/⋯, calm empty-state, loading + mapped failure states).
- l10n: added `listsFilterOpen`, `listsFilterDone`, `listsArchiveEmptyState`, `listStatusOpen`, `listStatusDone`, `listsArchiveUnnamedFallback`; mapped `command.listFilterInvalid` in `error_message_resolver.dart` (falls through to the generic fallback — defensive coverage, the client never sends a bad filter).
- Test-support: extended `FakeShoppingListsApi` with `doneListsToReturn`/`doneListError`/`listDoneListsCallCount`. Added cubit tests for `selectFilter` (default Offen, lazy-load-once, cache across Erledigt→Offen→Erledigt, archive-failure isolation) and widget tests for the read-only archive, empty state, Offen↔Erledigt round-trip, and the segmented control's localized labels — following this file's existing plain-`test`/`FakeShoppingListsApi` convention (the repo's cubit tests do not use `bloc_test` despite it being a dev dependency; consistency over ceremony). Flutter suite grew 266 → 278, all green (`flutter analyze` clean, `flutter test` full suite).
- Appended a `deferred-work.md` entry for this story's own defers (item counts/progress scope, archived-unnamed label + Done tint finalization, e2e coverage with a real Done list, reaffirming the 2.1 `IN_TRIP`-ordinal defer).
- No new events, migration, read-model column, or codec entry — a pure read-side + presentation slice as scoped.

### File List

- `backend/src/main/java/de/sgart/collaboration/application/query/ListDoneLists.java` (new)
- `backend/src/test/java/de/sgart/collaboration/application/ListDoneListsTest.java` (new)
- `backend/src/main/java/de/sgart/collaboration/application/CommandFieldTranslations.java` (modified)
- `backend/src/main/java/de/sgart/collaboration/adapter/in/ShoppingListController.java` (modified)
- `backend/src/test/java/de/sgart/collaboration/adapter/in/ShoppingListControllerTest.java` (modified)
- `backend/src/main/java/de/sgart/collaboration/adapter/out/HouseholdApplicationConfig.java` (modified)
- `app/lib/features/lists/data/shopping_lists_api.dart` (modified)
- `app/lib/features/lists/presentation/shopping_lists_state.dart` (modified)
- `app/lib/features/lists/presentation/shopping_lists_cubit.dart` (modified)
- `app/lib/features/lists/presentation/lists_view.dart` (modified)
- `app/lib/l10n/app_de.arb` (modified — `lib/l10n/gen/*.dart` is gitignored and regenerated via `flutter gen-l10n`, not tracked)
- `app/lib/shared/errors/error_message_resolver.dart` (modified)
- `app/test/support/fake_shopping_lists_dependencies.dart` (modified)
- `app/test/features/lists/presentation/shopping_lists_cubit_test.dart` (modified)
- `app/test/features/lists/presentation/lists_view_test.dart` (modified)
- `_bmad-output/implementation-artifacts/deferred-work.md` (modified)

## Change Log

| Date | Version | Description | Author |
|------|---------|-------------|--------|
| 2026-08-26 | 0.1 | Initial story draft — create-story context engine (Opus 4.8). Two LOCKED clarifications: item counts/progress deferred to 2.3/Epic 3; Done archive built read-only now (empty until Epic 3). | Timo |
| 2026-08-26 | 1.0 | Implemented — `ListDoneLists` query + `?filter=open|done` REST + Flutter Offen/Erledigt segmented filter and read-only archive. Backend 260→271, Flutter 266→278, both suites green. | Sonnet 5 (dev-story) |

### Review Findings

Code review 2026-08-26 (Opus 4.8; Blind Hunter + Edge Case Hunter + Acceptance Auditor, all Opus 4.8). Acceptance Auditor: **all ACs met**. Suites re-run green independently: backend `./gradlew test` BUILD SUCCESSFUL; Flutter `flutter analyze` clean + `flutter test` 278 passed.

- [x] [Review][Decision] Client filter enum is German (`ListFilter { offen, erledigt }`) while the wire protocol and the rest of the code are English (`?filter=open\|done`, `ListStatus.OPEN/DONE`, `ShoppingListsStatus.ready`, `ArchiveStatus.idle`) — the same concept carries two names across the boundary, against CLAUDE.md §2 (consistent ubiquitous language) and the `language-policy` memory (German confined to product-UI strings; the UI text already lives correctly in the `.arb` values). **Resolved: renamed to `ListFilter { open, done }`** (Timo's call); German now stays only in `listsFilterOpen`/`listsFilterDone`. Enum + all references (state, cubit, view) and the cubit test method names updated. [app/lib/features/lists/presentation/shopping_lists_state.dart:10]
- [x] [Review][Patch] Archive-load failure is an unrecoverable dead-end — after `listDoneLists` fails once, `archiveStatus` is `failure`, and the lazy-load guard (`archiveStatus != idle`) skipped every subsequent Erledigt reselect while the failure body rendered only static text with no retry. **Fixed:** the load now (re)runs when `archiveStatus` is `idle` *or* `failure`, so reselecting Erledigt retries; added a `lists-archive-retry-button` in the failure branch (reusing `householdsRetryButtonLabel`) wired to a new `ShoppingListsCubit.retryArchive()`. The archive load is refactored into `_loadArchive()`, whose post-`await` emits are now guarded on `state.status == ready` so a concurrent full reload can't be clobbered. Covered by 3 new cubit tests + 1 new widget test. [app/lib/features/lists/presentation/shopping_lists_cubit.dart; app/lib/features/lists/presentation/lists_view.dart]
- [x] [Review][Defer] Cached archive has no invalidation hook — `selectFilter` loads the archive once and caches it; nothing resets `archiveStatus` to `idle` when a list becomes Done. Latent in Epic 2 (archive always empty); when Epic 3 makes a real Done transition reachable, a newly-completed list won't appear until a full screen reload. [app/lib/features/lists/presentation/shopping_lists_cubit.dart:45] — deferred to Epic 3

Dismissed as noise (4): `ListDoneLists` duplicating `ListOpenLists` (spec-directed separate CQRS read models that diverge in Epic 3; the record is reused, not duplicated); the explicit `command.listFilterInvalid` resolver arm resolving to the same fallback (retro-DoD asked for the explicit coverage); `IN_TRIP` invisible in both views (already recorded in deferred-work as a 2.1→Epic 3 defer); no SegmentedButton-specific text-scale test (a generic button + status-label reflow-at-2× test already exists in `text_scaling_test.dart`).
