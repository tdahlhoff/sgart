---
baseline_commit: de675a3
---

# Story 3.4: Complete a trip with leftover review

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a member,
I want a guided completion that handles what I didn't buy — carrying wanted items onto another
list and cleanly discarding the rest,
so that nothing is lost and the trip closes cleanly, leaving an immutable record of what happened.

## Acceptance Criteria

Derived from **epics.md § Story 3.4** (FR10 trip completion), refined against **ARCHITECTURE-SPINE.md**
(AD-3, AD-4, AD-8, AD-10, AD-11, and the "Trip lifecycle is permanently `Active → Done`" Deferred
note), **UX EXPERIENCE.md §3 "Trip lifecycle" / journeys.md J3 (UJ-3)** and the
`screen-trip-lifecycle.html` + `screen-active-trip.html` artifacts (the per-row **checkbox + ⋯** menu;
the multi-step "Fertig?" → per-open-item **Übernehmen/Verwerfen** → "Einkauf abschließen" dialog, "Doch
noch weiter einkaufen", the "Abgeschlossen" summary), the **Story 3.1 trip-start pattern this story
mirrors for completion** (`ShoppingList.startTrip` → `TripStartedForList` → `TripStartProcessManager` →
`ShoppingTrip.start` → `TripStarted`), the **Story 3.3 item-status + postpone-to-list machinery this
story extends** (`checkOffItem`/`uncheckItem`, `postponeItemToList`/`ItemPostponedToList`/
`ItemMoveProcessManager`), and **Timo's decisions (2026-09-04)** captured in the Clarifications. This is
the **fourth Epic-3 story** — it lands SGART's first **`Active → Done` completion**, the **only place a
list reaches `DONE`** and becomes immutable, replaces 3.3's in-place-postpone with a single terminal
**`ItemStatus.DISCARDED`** (a 3.3 correction, Cl. 13), consolidates every per-item trip action into one
**⋯ sheet** (Cl. 12), and finally lights up the read-only **"Erledigt" archive** (built empty since
Story 2.2). Each AC is independently testable.

1. **Trigger a guided completion from the active trip (epic AC1, FR10).** Given an **active** trip (the
   list is `IN_TRIP`), when a member triggers completion ("Einkauf abschließen" — the quiet,
   non-sticky, tonal action at the list end, UX EXPERIENCE §3 "list-is-hero"), then a guided dialog
   opens with a "Fertig?" summary ("**N von M erledigt**", "X Artikel sind noch offen"). The action
   is **absent until this story** (Story 3.3 Cl. 2 asserted its absence). The trip is **never
   force-completed** — see AC5.

2. **Discard an item while shopping (epic AC1, FR10 — Timo 2026-09-04).** Given an item during an
   **active** trip, when a member discards it (**Verwerfen** in the trip row's ⋯ „Was tun mit diesem
   Artikel?" sheet — the "I'm not buying this and not carrying it anywhere" case, Cl. 12), then its
   status becomes the terminal **`ItemStatus.DISCARDED`** via a new IN_TRIP-gated **`discardItem`**
   command → **`ItemDiscarded`** on `list-{id}` — the item **stays on the list, dimmed/"Verworfen"**, it
   is *not* removed. Already-`DISCARDED` is a convergent no-op (raises nothing, AD-8). This is the **same
   shape as 3.3's `checkOffItem`/`uncheckItem`** (`requireInTrip()`, its own past-tense event);
   `uncheckItem` returns a `DISCARDED` item to `OPEN` (3.3's symmetric-reset undo already covers "any
   non-`OPEN` → `OPEN`"), and `checkOffItem` may still take it to `DONE` ("found it after all").
   `DISCARDED` is excluded from the "remaining/open" set and from the progress **N** (bought), but counts
   in the total **M**. **`DISCARDED` is the only non-bought terminal status** — Story 3.3's in-place
   `POSTPONED` is retired as redundant with it (Cl. 13).

3. **In the completion dialog: per remaining open item, TRANSFER or DISCARD (epic AC1, FR10, AD-10).**
   Given the completion dialog, for **each item still `OPEN`** (a `DONE` or `DISCARDED` item is not a
   leftover), the member chooses **Übernehmen** (TRANSFER — carry it onto an existing **Open** list or a
   **new** list) or **Verwerfen** (DISCARD). **TRANSFER reuses Story 3.3's postpone-to-list seam
   verbatim** (`POST …/items/{itemId}/postpone-to-list` → `ItemPostponedToList` → the idempotent
   `ItemMoveProcessManager` target-add, AD-10): the item leaves the source list now and lands on the
   target — exactly the epic's "TRANSFER places items automatically via an idempotent process manager".
   **DISCARD** calls the same **`discardItem`** as AC2 (→ `DISCARDED` immediately). Only **Open** lists
   (plus "Neue Liste") are valid transfer targets — a Done or In-Trip list is never a target (epic E3,
   enforced client-side in the picker and server-side by the reused `MoveTargetNotOpenException` → 409).

4. **Confirming completion sweeps any *still*-open item to DISCARDED, then moves the list to Done &
   immutable (epic AC2, Timo 2026-09-04 — the crux).** Given the member confirms completion, when it
   finishes, then **`ShoppingList.completeTrip` sweeps** — raising one **`ItemDiscarded`** for **any item
   that is still `OPEN`** (the same event as AC2/AC3; a quality-of-life safety net catching anything
   untouched or reached via the E4 straight-complete) — **and then** raises **`TripCompletedForList`**,
   folding the list `IN_TRIP → DONE`, all in one command / one append. The list becomes **immutable**
   (every planning command already `requireOpen()`, every in-trip command `requireInTrip()`, and `rename`
   rejects a `DONE` list — so a `DONE` list rejects all mutation, now reachable for the first time). A
   **process manager** (mirroring trip-start, AD-10) reacts to `TripCompletedForList` and completes the
   trip aggregate (`ShoppingTrip.complete` → **`TripCompleted`**, `ACTIVE → DONE`). Any **TRANSFER**red
   items already sit on their target/fresh list; a `DISCARDED` item is a visible historical record ("nicht
   gekauft"), never carried anywhere.

5. **Never force-complete; keep-shopping keeps the trip Active (epic AC3, FR10).** Given a member
   mid-completion, when they choose "Doch noch weiter einkaufen" (cancel), then the dialog closes, the
   trip stays **Active** (`IN_TRIP`), and **no** `ItemDiscarded` / `TripCompletedForList` is raised by the
   completion path — the system never force-completes. Only the explicit "Einkauf abschließen"
   confirmation triggers AC4. (A discard the member made in AC2/AC3 is a deliberate, already-committed
   choice and legitimately stands.)

6. **No remaining open items → skip the leftover step (epic AC4/E4).** Given a trip with **no `OPEN`
   items** (everything is `DONE` or already `DISCARDED`), when a member completes it, then the
   leftover-review step is **skipped** — completion goes straight to a simple confirm & close.
   `completeTrip` still runs (it finds nothing to sweep, raises only `TripCompletedForList`); the list
   moves to Done as in AC4.

7. **The list appears in the read-only "Erledigt" archive; the cached archive invalidates (epic AC2,
   retro Action 11).** Given completion finishes, when the member returns to the Listen overview, then the
   completed list has left the Open set (its `active_trip_id` is cleared) and appears in the **"Erledigt"**
   archive (`ListDoneLists`, built empty since Story 2.2 — now reachable for the first time). The client's
   **lazily-cached Done archive is invalidated** so the newly-completed list appears **without a full app
   reload** (the deferred Story 2.2 gap, Epic-2 retro Action 11). Opening a Done list shows its items
   **read-only** with their terminal treatments (`DONE` / `DISCARDED`).

8. **Membership, isolation, eventual consistency & no personal data (epic AC5).** The discard + completion
   commands are **membership-gated** (non-member → **403**); a malformed id is **400**; an
   unknown/other-household list is **404**; discarding on / completing a **not-`IN_TRIP`** list is **409**
   (a state conflict — the trip may have completed concurrently, mirroring 3.2/3.3's in-trip 409; discard
   reuses 3.3's `ItemNotDuringTripException` → 409, completion uses the new `TripNotCompletableException`
   → 409, Cl. 8). `ItemDiscarded` / `TripCompletedForList` / `TripCompleted` carry **no personal data and
   no *who*** — household/list/item/trip ids only (AD-5/AD-6, mirrors `ItemCheckedOff` /
   `TripStartedForList` / `TripStarted`); `MemberId` is used only at the handler seam. Read models are
   queried **by `household_id`** so one household's lists/items never leak. Tests use synthetic,
   clearly-fake German data only.

## Clarifications (LOCKED)

Taken from the epic ACs, the ARCHITECTURE-SPINE, the Story 3.1 trip-start and 3.3 status/postpone
patterns, `deferred-work.md`, the Epic-2 retro action items, and **Timo's decisions (2026-09-04)**.
**If any is wrong, correct it before `dev-story`.**

1. **`ItemStatus` becomes `{ OPEN, DONE, DISCARDED }` — add `DISCARDED`, remove `POSTPONED` (Timo,
   2026-09-04; see Cl. 13).** `DISCARDED` is the single terminal "not bought, stays on the list" status,
   written by a new `ItemDiscarded` event on `list-{id}` that folds `status → DISCARDED` via the existing
   `setStatus(...)` helper — **it does not remove the item** (distinct from `ItemRemoved` /
   `ItemPostponedToList`, which fold to a removal). A discarded item is a visible "not bought, thrown
   away" historical record on the (soon-to-be-`DONE`) list, rendered dimmed with a "Verworfen" treatment.
   `DISCARDED` is excluded from the "remaining/open" set and the progress **N**, counted in **M**; and
   `uncheckItem` returns a `DISCARDED` item to `OPEN` (the 3.3 symmetric-reset undo already covers "any
   non-`OPEN` → `OPEN`"). **`ItemDiscarded` has two writers** (Cl. 2/12): the explicit `discardItem`
   command and the completion sweep. [Source: 3.3 `ItemStatus`/`setStatus`/`uncheckItem`;
   ARCHITECTURE-SPINE.md #AD-11; epics.md Story 3.4 "TRANSFER … or DISCARD".]

2. **Completion is an atomic "sweep-then-complete" command on the `ShoppingList`, with the sweep as a
   quality-of-life safety net over the first-class discard (Timo, 2026-09-04).** `ShoppingList.completeTrip(commandId)`
   — `requireInTrip()` — iterates its items and **raises one `ItemDiscarded` for every item still `OPEN`**
   (the same event `discardItem` raises, Cl. 12), then raises **`TripCompletedForList`** (folds `IN_TRIP →
   DONE`). All in **one command → one append → one aggregate**, so nothing `OPEN` can survive completion
   even if the member never touched an item. The sweep runs **in-aggregate while the list is still
   `IN_TRIP`** (so the discards are valid before immutability lands in the same append). **Rejected:** a
   process manager that discards *after* `DONE` (it would have to mutate an immutable list — the exact
   race that argues for the in-aggregate sweep); and a domain guard that *rejects* completion while `OPEN`
   items remain (the epic forbids force-completion and the sweep makes the "nothing lost" guarantee
   without a precondition). [Source: `ShoppingList.startTrip`/`requireInTrip`; `apply(TripStartedForList)`
   (`IN_TRIP` fold); ARCHITECTURE-SPINE.md #AD-4/#AD-10.]

3. **Completion mirrors trip-start's list→PM→trip shape (the symmetric parallel).** Trip *start* (Story
   3.1): `startTrip` on the list → `TripStartedForList` (list-{id}) → `TripStartProcessManager` →
   `ShoppingTrip.start` → `TripStarted` (trip-{id}). Trip *completion* is the mirror image:
   `completeTrip` on the list → `TripCompletedForList` (list-{id}) → the **same** trip process manager,
   extended → `ShoppingTrip.complete` → `TripCompleted` (trip-{id}), `ACTIVE → DONE`. Extend
   `TripStartProcessManager` with `onTripCompletedForList(...)` and **rename it
   `TripLifecycleProcessManager`** (Boy Scout — it now spans start *and* completion; keeping the "Start"
   name is the churn-minimizing fallback). `ShoppingTrip.complete(commandId)`: `if (status ==
   TripStatus.DONE) return;` convergent no-op, else raise `TripCompleted` (mirror `addStore`'s ACTIVE
   guard / no-op idiom). The PM's target command id is **derived deterministically from the
   `TripCompletedForList` event id** and it inherits the **bounded concurrency-retry** the trip PM already
   uses (retro Action 12 satisfied by reuse). Route `TripCompletedForList` in
   `CollaborationProcessManagerSubscription.react` (same `list-` prefix it already watches). [Source:
   `TripStartProcessManager` (deterministic id + converge-on-conflict); `ShoppingTrip.start`/`addStore`;
   `CollaborationProcessManagerSubscription.react`; 3.1 story Cl. 1.]

4. **TRANSFER = the Story 3.3 postpone-to-list, unchanged.** The completion dialog's (and the ⋯ sheet's)
   "auf andere/neue Liste" calls the **existing** `POST …/items/{itemId}/postpone-to-list`
   (`ItemPostponedToList` → `ItemMoveProcessManager`, IN_TRIP-gated, Open-only target, self-target 400,
   the "Neue Liste" client two-step) — 3.4 adds **no** new transfer event/command/endpoint. The item
   leaves the source list the moment it's confirmed (while still `IN_TRIP`), so by the time `completeTrip`
   runs, the only `OPEN` items left are untouched ones — which the sweep discards. 3.4 adds exactly **two**
   new endpoints: the item **discard** (Cl. 12, on `ItemController`) and the trip **complete** (Cl. 7, on
   `TripController`). [Source: 3.3 Tasks 8/14 (`postponeItemToList`, `PostponeItemToListHandler`, `POST
   …/postpone-to-list`); `postpone_target_sheet.dart`.]

5. **The list's read side: `TripCompletedForList` → status `DONE` + clear `active_trip_id`; no new
   migration.** `ItemStatus.DISCARDED` reuses the existing `item_read_model.status VARCHAR(16)` column
   (V10, Story 3.3) — 'DISCARDED' fits; **no V11**. The list projector gains `case ItemDiscarded →
   setStatus(item, DISCARDED)` and `case TripCompletedForList → readModel.markDone(listId)` where a new
   `ShoppingListReadModel.markDone(listId)` (mirror `markInTrip`) sets `status = 'DONE'` **and**
   `active_trip_id = NULL` (the nav key clears, so the row leaves the "Im Einkauf"/Open set and the
   `ListDoneLists` archive picks it up). The projector's `case ItemPostponed` is **removed** (Cl. 13).
   `ItemView`/`ListItems.ItemSummary`/`TripView` already carry `status` (3.3) — `DISCARDED` rides that
   field with **no query change**. [Source: `ShoppingListReadModelProjector` (`TripStartedForList →
   markInTrip`, the 3.3 status cases); `JdbcShoppingListReadModel` (`active_trip_id`);
   `ShoppingListReadModel.markInTrip`; `V10__item_status.sql`.]

6. **The trip's read side: `TripCompleted` deletes the trip's `trip_store_read_model` rows (hygiene);
   no trip-status column.** The trip read side (Story 3.2) is only the `trip_store_read_model` feeding the
   **active** trip view; once `active_trip_id` is cleared, `GET …/trips/active` no longer reaches this
   trip, so its store rows are dead. `ShoppingTripReadModelProjector` gains `case TripCompleted → delete
   the trip's store rows` (the switch must handle the event — a silent no-op is the low-risk fallback;
   deletion is the lean choice, and store ids carry no PII). No trip header/status read model is added
   (YAGNI — the list's `DONE` + archive is the durable record). [Source: `ShoppingTripReadModelProjector`
   (the `trip-` prefix projector, its switch); 3.2 story Cl. 2/4 (`trip_store_read_model`, no
   `trip_read_model` header).]

7. **The completion command + endpoint mirror `StartTrip` / `AddStoreToTrip`.** `CompleteTrip(ShoppingListId
   listId, TripId tripId, CommandId commandId, AggregateVersion basedOnVersion)` + `CompleteTripHandler`
   (mirror `StartTripHandler`: translate ids 400 → resolve identity 403 → `loadListOwnedBy` 404 empty /
   404 cross-household → loaded version AD-8 → `list.completeTrip(commandId)` translating
   **`TripNotCompletableException` → `TripNotCompletableApplicationException` (409)** at the seam → append
   if `!uncommittedEvents().isEmpty()`). `tripId` is validated for envelope completeness and REST clarity
   but the handler resolves via the **list** (`completeTrip` is a `ShoppingList` command — `tripId` is
   informational, mirroring add-store's loose `{tripId}`). Endpoint: **`POST
   /api/v1/households/{householdId}/lists/{listId}/trips/{tripId}/complete`** on the existing
   `TripController` (mirror `@PostMapping("/{tripId}/stores")`), returning **200**. Plain-`String` request
   DTO (`record CompleteTripRequest(String commandId)`, no `..domain..`, ArchUnit). [Source:
   `TripController` (`@PostMapping`, `/{tripId}/stores`, `StartTripRequest`/`AddStoreToTripRequest`);
   `StartTrip`/`StartTripHandler`; 3.3 story Cl. 8.]

8. **New completion exception mirrors `TripNotStartableException` (DRY, one gate one 409).** `completeTrip`
   guards `status != ListStatus.IN_TRIP` with a new **`TripNotCompletableException`** (domain, message "A
   trip may only be completed from an In-Trip list, list is " + status) +
   **`TripNotCompletableApplicationException`** (application, → **409**, stable `code`), mirroring the
   `TripNotStartable*` pair Story 3.1 established for the inverse transition. Do **not** overload the
   item-scoped `ItemNotDuringTripException` (3.3) for completion — that message ("Items may only be
   changed during a trip") is wrong for a list-level completion. (`discardItem`, being an item mutation,
   *does* reuse `ItemNotDuringTripException` → 409, Cl. 12.) Update `WriteErrorAdvice` + its error-advice
   contract test (retro Action 2). [Source: `TripNotStartableException` /
   `TripNotStartableApplicationException` (Story 3.1); `ItemNotDuringTripException` (3.3); `WriteErrorAdvice`.]

9. **The read-only Done archive + the reachable `DONE`-invariant tests finally land (deferred since
   Stories 2.1/2.2).** With a real `DONE` transition now reachable, add the coverage those stories
   deferred: **(a)** an integration/projector test driving create → start → complete and asserting the
   list lands in `ListDoneLists` with `status = DONE` and `active_trip_id = NULL` (and leaves
   `ListOpenLists`); **(b)** the reachable **`rename`-on-`DONE`** aggregate test — rehydrate
   `[ShoppingListCreated, TripStartedForList, TripCompletedForList]`, assert `rename` throws
   `ListNameChangeNotPermittedException` (Story 2.1 Cl. 1); **(c)** end-to-end **read-only-archive**
   coverage with a genuinely-completed list (Story 2.2 AC2, previously proven only on synthetic `DONE`
   rows). [Source: `deferred-work.md` "`DONE`-rejects-rename / read-only-archive e2e … Epic 3", "Cached
   Done archive … Epic 3"; `ShoppingList.rename` guard; `ListDoneLists`; `ListOpenLists`.]

10. **Carry the still-open Epic-2 retro action items into this story (DoD, not review-catch)** — per the
    3.2/3.3 Cl. 9 precedent: **(a)** optimistic-state — discard + completion reflect their server-visible
    effect (item dims to DISCARDED; list leaves the trip / lands in the archive; transferred items already
    gone), inline-created transfer targets behave (Action 7); **(b)** an **error-advice contract test** for
    the discard + complete endpoints (bad input / each reachable domain exception → the right 4xx) + the
    extended DoD (a11y labels on the ⋯/undo affordances + the completion action + dialog controls ≥48px
    UX-DR5, no dead code/strings, fail-fast guards) (Actions 2/3/8); **(c)** the `isSubmitting`/re-entrancy
    + spent-`CommandIntent` guards on the new discard + complete client paths from the first pass (Action
    9); **(d)** the completion + discard touch the item read model's `status` — a **two-household isolation
    + replay-idempotency** test lands in the same change (Action 10); **(e)** the completion PM reuses the
    deterministic-id + bounded-retry pattern (Action 12). **Action 11 (cached Done-archive invalidation) is
    realised here** (AC7). [Source: `epic-2-retro-2026-08-28.md` §6; sprint-status open action items.]

11. **Deliberately NOT in 3.4 — the two-phase transfer saga stays in Story 3-6.** 3.4 reuses 3.3's
    postpone-to-list seam with its **interim** `UNRECOVERABLE_TRANSFER` log-and-surface guard; it does
    **not** build the reserve-then-remove two-phase compensating saga, and the client false-success UX on a
    transfer-drop (the "wird verschoben…" / reconcile affordance) **remains deferred to Story
    `3-6-two-phase-transfer-saga`** (Timo, 2026-08-30). Completion's leftover TRANSFER therefore inherits
    exactly the 3.3 behaviour — do not expand or regress it. Also unchanged: the postpone-to-list
    "404-on-retry after a lost response" defer. [Source: sprint-status `3-6` note; `deferred-work.md`
    "Client-side false-success … → story 3-6", "postpone-to-list not idempotent across a lost response".]

12. **Every per-item trip-row action consolidates into ONE ⋯ sheet; discard is first-class (Timo,
    2026-09-04).** *Backend:* `ShoppingList.discardItem(ItemId, CommandId)` — a first-class IN_TRIP-gated
    status command **mirroring 3.3's `checkOffItem`** (`requireNonNull` + `requireInTrip()` + unknown item
    → `ItemNotFoundException` + already-`DISCARDED` convergent no-op + else `raise(new ItemDiscarded(…))`),
    reusing 3.3's `ItemNotDuringTripException` (→ 409, item-scoped message correct). A `DiscardItem`
    command + `DiscardItemHandler` (mirror `CheckOffItem`/`CheckOffItemHandler`) and a `POST
    …/items/{itemId}/discard` endpoint on `ItemController` (mirror `/{itemId}/check-off`, 200). *Flutter
    (the consolidation):* the trip row today scatters separate **reroute** (`_openReroutePicker` →
    `store_picker_sheet`, Story 3.2) and **postpone** (`_openPostponeSheet` → `postpone_target_sheet`,
    Story 3.3) trailing actions. **Replace both with a single per-row „⋯" action**
    (`trip-item-actions-{itemId}`) opening ONE **„Was tun mit diesem Artikel?"** sheet offering: **Anderes
    Geschäft** (reroute → the trip's stores, reusing `store_picker_sheet`), **auf andere Liste** (Open
    lists) + **＋ Neue Liste** (→ `postponeItemToList`), and **Verwerfen** (→ `discardItem`). Rationale
    (Timo): the trip view is grouped by store, so a per-item store affordance is redundant with the group
    header; this matches `screen-active-trip.html`'s envisioned per-row **checkbox + ⋯ menu**. **Backend-
    unchanged apart from `discardItem`** — reroute / postpone-to-list already exist (3.2/3.3); the sheet
    just composes the two existing pickers + a discard row. There is **no "Hier vormerken" option** (Cl.
    13 retires in-place postpone). Status-dependent rows: an **`OPEN` row** = checkbox (→ DONE) + ⋯; a
    **`DONE` row** = checkbox (→ OPEN) only; a **`DISCARDED` row** = checkbox (→ DONE "found it") + UNDO (→
    OPEN), no ⋯. Does **not** touch the list-detail store chip (2.6, the off-trip planning assignment — a
    different screen). [Source: `trip_screen.dart`
    (`_openReroutePicker`/`_openPostponeSheet`/`_TripItemRow._buildTrailing`), `store_picker_sheet.dart`,
    `postpone_target_sheet.dart`; 3.3 `checkOffItem`/`CheckOffItem`/`ItemCheckedOff`/`POST …/check-off`,
    `ItemController`, `ItemNotDuringTripException`; ux `screen-active-trip.html` (the ⋯ menu).]

13. **Retire Story 3.3's in-place `POSTPONED` — it is redundant with `DISCARDED` (Timo, 2026-09-04; a 3.3
    correction folded into 3.4).** In-place postpone and discard did the same thing (item stays on the
    list, dimmed, not bought, reversible via `uncheck`); the 3.3 correct-course kept in-place postpone
    only because discard did not yet exist. With `DISCARDED` now the single "not bought, stays here"
    status, **remove** the in-place variant end-to-end: `ItemPostponed` event, `ShoppingList.postponeItemInPlace`
    + its `apply(ItemPostponed)` case, `PostponeItem`/`PostponeItemHandler` + bean, the `POST
    …/items/{itemId}/postpone` endpoint, the `DomainEventJsonCodec` `ItemPostponed` registration, the
    projector `case ItemPostponed`, the `ItemStatus.POSTPONED` value, and on the client the
    `ItemStatus.postponed` value, `TripCubit.postponeInPlace`, the „Hier vormerken"/„Zurückstellen" sheet
    row + its ARB strings, the `POSTPONED` rendering, and all their tests. **KEEP** `ItemPostponedToList`
    (move-to-another-list) and its whole seam — that is a distinct, useful carry-over action.
    **Event store:** nothing from this project has ever been deployed or run against a persistent store
    (Timo, 2026-09-04), so no `ItemPostponed` events exist anywhere — this is a **clean code removal** with
    no replay/migration concern (no reset, no codec-legacy-tolerance needed). The Testcontainers suites
    spin up a fresh store per run regardless. [Source: 3.3 story Cl. 1/2 + Tasks 2/5/7/12/14 (the in-place
    postpone surface); 3.3 correct-course 2026-08-29 (kept in-place postpone); memory git-workflow
    (commit-to-main pre-beta).]

## Tasks / Subtasks

> Follow TDD (CLAUDE.md §6): failing test first at the lowest level that proves the behaviour, then the
> simplest code to pass. Keep the domain pure (AD-1). **Mirror the cited existing file for every new
> class** — the event, aggregate method, command→handler, projection, controller endpoint, process-
> manager reaction, and Flutter patterns are all established (Stories 3.1 start, 3.3 status/postpone); do
> not invent new ones. This story lands the **`Active → Done` completion**, the terminal
> **`ItemStatus.DISCARDED`** (a first-class in-trip `discardItem` + the completion sweep), **retires 3.3's
> in-place `POSTPONED`** (Cl. 13), consolidates the trip-row actions into one ⋯ sheet, and lights up the
> reachable **Done archive**. Baseline: the full-suite counts you observe at branch start (last green ≈
> backend 577 / Flutter 491 at `de675a3`; note that retiring in-place postpone *removes* some 3.3 tests,
> so the count delta is not purely additive — report it honestly per suite).

### Backend — retire in-place postpone (3.3 correction, Cl. 13) — do this first

- [x] **Task 0: remove the in-place-postpone surface** — delete `ItemPostponed` (event),
      `ShoppingList.postponeItemInPlace` + its `apply(ItemPostponed)` case, `PostponeItem` +
      `PostponeItemHandler` + its bean in `CollaborationApplicationConfig`, the `POST …/items/{itemId}/postpone`
      mapping in `ItemController`, the `ItemPostponed` registration in `DomainEventJsonCodec`, the projector
      `case ItemPostponed`, and the `ItemStatus.POSTPONED` value. Remove the now-dead tests
      (`PostponeItemHandlerTest`, the aggregate/projector/controller/codec POSTPONED cases). **Keep**
      `ItemPostponedToList` + `PostponeItemToList`/`PostponeItemToListHandler` + `POST …/postpone-to-list`
      untouched (the move-to-list seam). Confirm nothing else references `POSTPONED`/`ItemPostponed`.
  - [ ] **Event store:** clean removal — no persisted `ItemPostponed` events exist (nothing deployed or
        run, Timo 2026-09-04), so no reset / migration / codec-legacy-tolerance is needed.

### Backend — domain: DISCARDED status + the three completion events + the aggregate transitions (AC2–AC6, AC8, Cl. 1/2/3/8/12)

- [x] **Task 1: `ItemStatus.DISCARDED`** — `collaboration.domain` (add one value; `POSTPONED` already
      removed in Task 0 → enum is now `{ OPEN, DONE, DISCARDED }`)
  - [ ] Javadoc: the terminal "not bought, thrown away" status (Story 3.4), written only by
        `ItemDiscarded` (the explicit `discardItem` **or** the completion sweep); the item stays on the
        list, dimmed — distinct from a removal (`ItemRemoved`/`ItemPostponedToList`).
- [x] **Task 2: `ItemDiscarded` event** — `collaboration.domain.event` (mirror `ItemCheckedOff`)
  - [ ] `record ItemDiscarded(EventId eventId, HouseholdId householdId, ShoppingListId listId, ItemId
        itemId)` — `requireNonNull` all. Javadoc: raised on `list-{id}` while the list is `IN_TRIP` by
        **either** the explicit `discardItem` (Cl. 12) **or** the `completeTrip` sweep (Cl. 2); folds
        `status → DISCARDED` (a status change, **not** a removal); no personal data / no *who*
        (AD-5/AD-6, mirrors `ItemCheckedOff`).
- [x] **Task 3: `TripCompletedForList` + `TripCompleted` events** (mirror `TripStartedForList` / `TripStarted`)
  - [ ] `record TripCompletedForList(EventId eventId, HouseholdId householdId, ShoppingListId listId,
        TripId tripId)` on `list-{id}`; folds the list `IN_TRIP → DONE`. Javadoc: the completion
        counterpart of `TripStartedForList`; carries `tripId` so the PM can complete the matching trip.
  - [ ] `record TripCompleted(EventId eventId, TripId tripId, HouseholdId householdId, ShoppingListId
        listId)` on `trip-{id}`; folds the trip `ACTIVE → DONE`. Raised by the PM, never a handler (AD-10).
- [x] **Task 4: completion exception** (Cl. 8) — `collaboration.domain.exception` (mirror `TripNotStartableException`)
  - [ ] `TripNotCompletableException` (message "A trip may only be completed from an In-Trip list, list
        is " + status), thrown by `ShoppingList.completeTrip` when `status != IN_TRIP`.
- [x] **Task 5: `ShoppingList.discardItem` + `completeTrip` + folds** (AC2, AC4, AC6, Cl. 1/2/12)
  - [ ] `public void discardItem(ItemId itemId, CommandId commandId)` — **mirror `checkOffItem`** (Cl. 12):
        `requireNonNull` all; `requireInTrip()`; unknown item → `ItemNotFoundException`; already-`DISCARDED`
        convergent no-op; else `raise(new ItemDiscarded(EventId.generate(), householdId, listId, itemId))`.
  - [ ] `public void completeTrip(CommandId commandId)`: `requireNonNull(commandId)`; guard `status !=
        ListStatus.IN_TRIP → TripNotCompletableException`; **sweep** — for each entry in `itemsById` whose
        `status() == ItemStatus.OPEN`, `raise(new ItemDiscarded(EventId.generate(), householdId, listId,
        itemId))` (deterministic iteration order; a `DONE`/`DISCARDED` item is untouched); **then**
        `raise(new TripCompletedForList(EventId.generate(), householdId, listId, activeTripId))`. Javadoc:
        the sweep-then-complete (Cl. 2) — the sweep is a QoL safety net over the explicit `discardItem`;
        the only place a list reaches `DONE`.
  - [ ] The aggregate must know its `tripId` to emit `TripCompletedForList`. Extend `apply(TripStartedForList)`
        to capture `this.activeTripId = started.tripId()` (add the field). Confirm no consumer relies on
        its absence.
  - [ ] `apply(ItemDiscarded)` → `setStatus(itemId, ItemStatus.DISCARDED)` (existing helper, preserving
        other fields). `apply(TripCompletedForList)` → `this.status = ListStatus.DONE`. Register both in
        the `apply(...)` switch.
  - [ ] Extend `ShoppingListTest`: `discardItem_onAnInTripList_raisesItemDiscarded_andFoldsToDiscarded`;
        `discardItem_whenAlreadyDiscarded_isAConvergentNoOp`; `discardItem_onAnOpenList_throwsItemNotDuringTrip`;
        `discardItem_forAnUnknownItem_throwsItemNotFound`; `uncheckItem_returnsADiscardedItemToOpen`;
        `completeTrip_onAnInTripList_discardsOpenItems_andFoldsToDone` (OPEN + DONE + DISCARDED + a
        moved-away item → each remaining OPEN becomes DISCARDED, DONE/DISCARDED untouched, list `DONE`,
        `TripCompletedForList` last); `completeTrip_withNoOpenItems_raisesOnlyTripCompletedForList` (E4);
        `completeTrip_onAnOpenList_throwsTripNotCompletable`; `completeTrip_onADoneList_throwsTripNotCompletable`;
        **`renameADoneList_throwsListNameChangeNotPermitted`** (Cl. 9(b) — rehydrate `[Created,
        TripStartedForList, TripCompletedForList]`, rename → throws).
- [x] **Task 6: `ShoppingTrip.complete` + `apply(TripCompleted)`** (AC4, Cl. 3)
  - [ ] `public void complete(CommandId commandId)`: `requireNonNull(commandId)`; `if (status ==
        TripStatus.DONE) return;` convergent no-op (mirror `addStore`); else `raise(new
        TripCompleted(EventId.generate(), tripId, householdId, listId))`. `apply(TripCompleted)` →
        `this.status = TripStatus.DONE`; register in the switch.
  - [ ] Extend `ShoppingTripTest`: `complete_onAnActiveTrip_raisesTripCompleted_andFoldsToDone`;
        `complete_onADoneTrip_isAConvergentNoOp`.

### Backend — application: the discard + completion commands + handlers + PM reaction (AC1–AC6, AC8, Cl. 3/7/8/10/12)

- [x] **Task 7a: `DiscardItem` + `DiscardItemHandler`** — `application.command` (mirror `CheckOffItem`/`CheckOffItemHandler`; DTO **beside** its handler)
  - [ ] `record DiscardItem(ShoppingListId listId, ItemId itemId, CommandId commandId, AggregateVersion
        basedOnVersion)` — `requireNonNull` all. Handler mirrors `CheckOffItemHandler`: translate ids (400)
        → resolve identity (403) → `loadListOwnedBy` (404 empty / 404 cross-household) → loaded version
        (AD-8) → `list.discardItem(...)` translating `ItemNotFoundException` → 404 and
        **`ItemNotDuringTripException` → `ItemNotDuringTripApplicationException` (409, reused from 3.3)** at
        the seam → append only if `!uncommittedEvents().isEmpty()` (already-DISCARDED no-op skips). Wire the
        bean (mirror `checkOffItemHandler`).
  - [ ] `DiscardItemHandlerTest` (in-memory `EventStore` + fake `ResolveMemberIdentity`, mirror
        `CheckOffItemHandlerTest`): appends `ItemDiscarded` on `list-{id}`; 403 / 400 / 404 / 404-cross-hh
        / **409 not-In-Trip** / unknown item 404 / already-DISCARDED no-append.
- [x] **Task 7b: `CompleteTrip` + `CompleteTripHandler`** — `application.command` (mirror `StartTrip`/`StartTripHandler`; DTO **beside** its handler)
  - [ ] `record CompleteTrip(ShoppingListId listId, TripId tripId, CommandId commandId, AggregateVersion
        basedOnVersion)` — `requireNonNull` all. Handler mirrors `StartTripHandler`: translate ids (400) →
        resolve identity (403) → `loadListOwnedBy` (404 empty / 404 cross-household) → loaded version
        (AD-8) → `list.completeTrip(commandId)` translating **`TripNotCompletableException` →
        `TripNotCompletableApplicationException` (409)** at the seam → append if `!uncommittedEvents().isEmpty()`.
        Reuse `CommandFieldTranslations` (`toShoppingListId`/`toTripId` — confirm, no new translator).
  - [ ] `TripNotCompletableApplicationException` (`application.exception`, → 409, stable `code`); confirm
        `WriteErrorAdvice` maps it (update the mapping + its **error-advice contract test**, Action 2).
        Wire the bean (mirror `startTripHandler`).
  - [ ] `CompleteTripHandlerTest` (mirror `StartTripHandlerTest`): appends `ItemDiscarded`×open +
        `TripCompletedForList` on `list-{id}`; 403 / 400 / 404 / 404-cross-hh / **409 not-In-Trip**; a
        re-delivered same-`commandId` completion is deduped (no double-append).
- [x] **Task 8: extend the trip PM to complete the trip** (AC4, Cl. 3, Action 12) — `application`
  - [ ] Add `onTripCompletedForList(TripCompletedForList event)` to `TripStartProcessManager`, mirroring
        `onTripStartedForList`: load the `ShoppingTrip` by `event.tripId()`, `trip.complete(commandId)`
        with a **command id derived deterministically from `event.eventId()`**, append with the inherited
        **bounded concurrency-retry**. **Rename the class `TripLifecycleProcessManager`** (Boy Scout — now
        spans start + completion; update Javadoc, the bean method, references; keeping "Start" is the
        churn-minimizing fallback, note if taken).
  - [ ] `CollaborationProcessManagerSubscription.react`: route `TripCompletedForList` →
        `tripLifecycleProcessManager.onTripCompletedForList(...)` (same `list-` prefix, already subscribed).
        Update the class Javadoc's "reacts to …" list.
  - [ ] Extend the PM test: a `TripCompletedForList` completes the trip once (`TripCompleted` on
        `trip-{id}`); a re-delivery (deterministic id) is a silent no-op; a lost concurrency race converges.

### Backend — read side: projector cases + `markDone` + the trip-store cleanup (AC2, AC7, AC8, Cl. 5/6/9/10/13)

- [x] **Task 9: `ShoppingListReadModel.markDone` + `JdbcShoppingListReadModel`** (Cl. 5)
  - [ ] Port: `default void markDone(ShoppingListId listId)` (mirror `markInTrip`). `JdbcShoppingListReadModel`:
        `UPDATE shopping_list_read_model SET status = 'DONE', active_trip_id = NULL WHERE list_id = :listId`.
        Confirm `setStatus` (V10) accepts `'DISCARDED'` (VARCHAR(16), no change).
- [x] **Task 10: list projector cases** (Cl. 1/5/10/13) — `ShoppingListReadModelProjector`
  - [ ] Add `case ItemDiscarded d -> itemReadModel.setStatus(d.itemId(), ItemStatus.DISCARDED)` and
        `case TripCompletedForList c -> readModel.markDone(c.listId())`. **Remove** the `case ItemPostponed`
        (Task 0). Leave `ItemUpdated`/`ItemRerouted`/`ItemCheckedOff`/`ItemUnchecked` cases unchanged.
  - [ ] Extend `ShoppingListReadModelProjectorTest` (Testcontainers): `ItemDiscarded` → `status = DISCARDED`;
        `TripCompletedForList` → list `status = DONE` **and** `active_trip_id` NULL; **two-household
        isolation** + **replay idempotency** for the completion sequence (Action 10); the completed list
        lands in `ListDoneLists`, leaves `ListOpenLists` (Cl. 9(a)).
- [x] **Task 11a: trip projector `TripCompleted` cleanup** (Cl. 6) — `ShoppingTripReadModelProjector`
  - [ ] `case TripCompleted c -> tripStoreReadModel.deleteForTrip(c.tripId())` (new port/JDBC method:
        `DELETE FROM trip_store_read_model WHERE trip_id = :tripId`); Javadoc why. Silent no-op is the
        low-risk fallback, but the switch **must** handle `TripCompleted`.
  - [ ] Extend `ShoppingTripReadModelProjectorTest`: `TripCompleted` removes the trip's store rows; a
        `TripView` query for that list returns empty after.

### Backend — adapter.in + codec + ArchUnit (AC1–AC3, AC8, Cl. 7/8/12/13)

- [x] **Task 11b: extend `ItemController` with discard** (Cl. 12) — mirror `@PostMapping("/{itemId}/check-off")` (200)
  - [ ] `@PostMapping("/{itemId}/discard")` → `discardItemHandler.handle(...)`; `record
        DiscardRequest(String commandId)`; **200**. Identity from JWT `sub` via `AuthenticatedCaller`;
        plain-`String` DTO. (The `/{itemId}/postpone` mapping is already removed in Task 0.)
  - [ ] Extend `ItemControllerTest`: discard → 200 / 400 malformed / 403 non-member / 404 unknown
        list/item / **409 not-In-Trip** (the discard error-advice contract).
- [x] **Task 12: extend `TripController` with completion** (Cl. 7) — mirror `@PostMapping("/{tripId}/stores")`
  - [ ] `@PostMapping("/{tripId}/complete")` → `completeTripHandler.handle(...)`; `record
        CompleteTripRequest(String commandId)`; **200**. Identity from JWT `sub`; `basedOnVersion` from the
        request envelope like the sibling endpoints. Plain-`String` DTO.
  - [ ] `TripControllerTest`: complete → 200 / 400 / 403 / 404 / **409 not-In-Trip** (the Action-2
        error-advice contract for the new endpoint).
- [x] **Task 13: `DomainEventJsonCodec`** — register the three new events (`ITEM_DISCARDED_TYPE`,
      `TRIP_COMPLETED_FOR_LIST_TYPE`, `TRIP_COMPLETED_TYPE`); **remove** the `ItemPostponed` registration
      (Task 0). `typeTagFor` + `toJsonBytes` + `fromJsonBytes` per event. Extend `DomainEventJsonCodecTest`:
      the three round-trip with a **stable type tag**; the `ItemPostponed` round-trip test is removed (or,
      if the legacy-tolerance fallback of Cl. 13 is taken, an `ItemPostponed`-decodes-as-legacy test).
- [x] **Task 14: ArchUnit** — run `HexagonalArchitectureTest`; confirm the three events + the new
      exception (`..domain..`), the two commands/handlers + app-exception (`..application..`), the
      projector/read-model changes (`..adapter.out..`), and the extended controllers (`..adapter.in..`, no
      `..domain..` import) need **no** rule change.

### Flutter — retire in-place postpone (3.3 correction, Cl. 13)

- [x] **Task 15a: remove the in-place-postpone client surface** — delete `ItemStatus.postponed`,
      `TripCubit.postponeInPlace`, the „Hier vormerken"/„Zurückstellen" in-place row in
      `postpone_target_sheet.dart` (keep its Open-lists + „Neue Liste" target selection), the `POSTPONED`
      rendering (`tripPostponedLabel` etc.) and the in-place ARB strings (`tripPostponeInPlace`,
      `tripItemPostponeAction`, `tripItemUndoPostponeAction` if unused after), and the corresponding tests.
      Confirm `flutter analyze` finds no dangling references.

### Flutter — data layer (AC1–AC3, AC8)

- [x] **Task 15b: `ItemStatus.discarded` + `ItemsApi.discardItem` + `TripsApi.completeTrip`** — `features/lists/data/` + `features/trips/data/`
  - [ ] Add `discarded` to the Dart `ItemStatus` enum with `fromServerName`/`serverName` mapping
        `'DISCARDED'` (mirror the existing OPEN/DONE entries; enum is now `{ open, done, discarded }` after
        Task 15a; `Item.fromJson`'s fail-fast validation accepts exactly these).
  - [ ] `ItemsApi.discardItem(householdId, listId, itemId, {required commandId})` (`POST
        …/items/{itemId}/discard`, mirror the 3.3 `checkOffItem` method). Extend `FakeItemsApi`.
        Request-shape test.
  - [ ] `TripsApi.completeTrip(householdId, listId, tripId, {required commandId})` (`POST
        …/lists/{listId}/trips/{tripId}/complete`, mirror `startTrip`/`addStoreToTrip`). Extend
        `FakeTripsApi`. Request-shape test; a server error surfaces as the shared `AppException`.

### Flutter — the ⋯ item-actions sheet + the completion flow (AC1–AC7, Cl. 4/10/12)

- [x] **Task 16: `TripCubit` + `TripState`** — `features/trips/presentation/`
  - [ ] `TripState`: computed `remainingOpenCount` / `openItems` (items with `status == ItemStatus.open`,
        excluding DONE/DISCARDED — the leftover set); a terminal "completed" outcome for the widget (a
        `completed` flag/state or a stream the page listens to for navigation).
  - [ ] `TripCubit.discard(itemId)` — a dedicated `CommandIntent` (keyed on the item, freshened on change +
        after success), guarded `ready && !isSubmitting`, **optimistic** (`Item.copyWith(status: discarded)`),
        calls `ItemsApi.discardItem`, revert + `actionError` on failure (Actions 7/9) — mirror 3.3's
        `checkOff`. (`reroute` / `postponeToList` already exist from 3.2/3.3 — the ⋯ sheet routes to them
        unchanged; `postponeInPlace` is removed in Task 15a.)
  - [ ] `TripCubit.completeTrip()` — a dedicated `CommandIntent`, guarded `ready && !isSubmitting`, calls
        `TripsApi.completeTrip`, on success emits the completed outcome (the trip screen pops), revert +
        `actionError` on failure (Actions 7/9). Transfers reuse the existing `postponeToList`.
  - [ ] `trip_cubit_test.dart`: `discard` optimistically → DISCARDED + reverts + freshens the intent +
        `isSubmitting` guard; `completeTrip` calls the api + emits completed + reverts + `actionError` + the
        guard drops a second tap; `remainingOpenCount` excludes DONE/DISCARDED.
- [x] **Task 17: the consolidated ⋯ „Was tun mit diesem Artikel?" sheet + trip-row rework** (Cl. 12)
  - [ ] **Sheet** (`trip_item_actions_sheet.dart`, or extend `postpone_target_sheet`): rows for **Anderes
        Geschäft** (reroute → `showStorePickerSheet` over the **trip's** stores, then `cubit.reroute`),
        **auf andere Liste** (Open lists) + **＋ Neue Liste** (→ `cubit.postponeToList`, the 2.4/3.3
        create-then-postpone two-step), and **Verwerfen** (→ `cubit.discard`). Compose the **existing**
        `store_picker_sheet` (3.2) + `postpone_target_sheet` target selection (3.3, minus „Hier vormerken").
        **No „Hier vormerken"** (Cl. 13).
  - [ ] **Rework `_TripItemRow._buildTrailing` (`trip_screen.dart`)**: replace the scattered
        `_openReroutePicker`/`_openPostponeSheet` trailing actions with a **single ⋯ action**
        (`trip-item-actions-{itemId}`) opening the sheet. Status-dependent rows: **`OPEN`** = checkbox (→
        DONE) + ⋯; **`DONE`** = checkbox (→ OPEN) only; **`DISCARDED`** = checkbox (→ DONE "found it") +
        UNDO (→ OPEN), no ⋯. a11y labels on the ⋯ + UNDO controls (≥48px, UX-DR5).
  - [ ] Render a `DISCARDED` item dimmed with a "Verworfen" treatment in the trip screen and in the
        **read-only Done-list detail** (opening an "Erledigt" list shows items read-only with `DONE` /
        `DISCARDED` treatments — confirm the Story 2.2 read-only archive path renders item statuses). Keep
        3.3's DONE row treatment intact.
  - [ ] `trip_screen_test.dart` / sheet widget test: OPEN row = checkbox + ⋯; ⋯ opens the sheet listing
        Anderes Geschäft / auf andere Liste / Neue Liste / Verwerfen; each routes to the right cubit call
        (reroute via the store picker, postpone-to-list, new-list two-step, discard); a DISCARDED row shows
        checkbox + UNDO and no ⋯; UNDO → OPEN; the **3.2 reroute** and **3.3 postpone-to-list** paths still
        work through the new sheet (regression).
- [x] **Task 18: completion dialog widget** — `features/trips/presentation/` (mirror `screen-trip-lifecycle.html` step 2/3)
  - [ ] A guided, multi-step dialog: **"Fertig?"** summary ("N von M erledigt", "X Artikel sind noch
        offen"); **per remaining OPEN item** an **Übernehmen** (→ transfer target: Open lists + "Neue Liste",
        reusing `postpone_target_sheet`) / **Verwerfen** (→ `cubit.discard`) choice; a tonal **"Einkauf
        abschließen"** confirm + a **"Doch noch weiter einkaufen"** cancel (closes, trip stays Active — AC5,
        assert nothing is raised). **E4 (AC6):** when `remainingOpenCount == 0`, skip the per-item step —
        straight to a simple confirm & close. Post-completion **"Abgeschlossen"** summary ("„{name}" ist
        erledigt und archiviert") with "Zur neuen Liste" (if a transfer targeted/created a list) / "Fertig".
        a11y labels on the action + dialog controls (≥48px, UX-DR5).
  - [ ] Widget/`trip_screen_test.dart`: the "Einkauf abschließen" action now **exists** (reverses 3.3's
        absence assertion); opening it with open items shows the leftover step with Übernehmen/Verwerfen;
        "Übernehmen"→ a target transfers (reuses postpone-to-list); "Verwerfen"→ discard; confirm calls
        `completeTrip`; "Doch noch weiter einkaufen" cancels with no command; the E4 no-open-items path
        skips straight to confirm.
- [x] **Task 19: navigation + Done-archive cache invalidation (Action 11, AC7)** — `features/lists/presentation/list_overview/shopping_lists_cubit.dart`
  - [ ] On completion, pop the trip screen back to the overview. Invalidate the lazily-cached Done archive:
        reset `archiveStatus` to `idle` on the overview's bootstrap/refresh (so any Done transition that
        happened while away is reflected on the next "Erledigt" selection) — the minimal robust fix for the
        deferred Story 2.2 gap. Confirm the completed list also leaves the Open set (`active_trip_id`
        cleared server-side).
  - [ ] `shopping_lists_cubit_test.dart`: after a refresh, selecting "Erledigt" refetches (a stale cached
        archive no longer hides a newly-Done list); the Open set excludes the completed list.

### Flutter — localization (Cl. 10/12/13)

- [x] **Task 20: localization** — `l10n` (`app_de.arb` + `flutter gen-l10n`)
  - [ ] Add: `tripCompleteAction` ("Einkauf abschließen"), `tripCompleteDialogTitle` ("Fertig?"),
        `tripCompleteSummary` ("{done} von {total} erledigt" — reuse `tripProgressLabel` if identical),
        `tripCompleteLeftoverPrompt` ("Was möchtest du mit den offenen Artikeln tun?"),
        `tripLeftoverTransferAction` ("Übernehmen"), `tripKeepShoppingAction` ("Doch noch weiter
        einkaufen"), `tripCompletedSummary` ("„{name}" ist erledigt und archiviert"),
        `tripCompletedGoToNewList` ("Zur neuen Liste") / `commonDone` ("Fertig"),
        `tripItemActionsSheetTitle` ("Was tun mit diesem Artikel?"), `tripItemRerouteInSheet` ("Anderes
        Geschäft" — or reuse `tripItemRerouteAction`), `tripItemDiscardAction` ("Verwerfen"),
        `tripItemUndoDiscardAction` (DISCARDED-row UNDO), `itemDiscardedLabel` ("Verworfen"). **Reuse** the
        3.3/2.4 "Neue Liste" / transfer-target / Open-list strings. **Remove** the in-place-postpone strings
        (Task 15a). No dead strings, no hard-coded user-facing strings (Action 2 DoD).

### Tests & green build (CLAUDE.md §6)

- [x] **Task 21: extended-DoD sweep (retro Actions 2/3/7/8/9/10/11/12)** — before review: discard +
      completion reflect their server-visible effect optimistically (item dims to DISCARDED; list leaves
      the trip / lands in the archive); a11y labels on the ⋯/UNDO affordances + the completion action +
      dialog controls; no dead code / no hard-coded / no orphaned POSTPONED strings; fail-fast +
      `isSubmitting`/spent-intent guards on **both** new command paths (discard + complete); the
      error-advice mapping tests for the discard + complete endpoints exist; the completion sequence has an
      isolation + replay test; **the Done-archive cache invalidation (Action 11) is implemented + tested**;
      the completion PM reuses the deterministic-id + bounded-retry pattern.
- [x] **Task 22: full-suite green** — backend `./gradlew test` (incl. `HexagonalArchitectureTest` ArchUnit
      **and** the Testcontainers projector tests) **and** Flutter `flutter analyze` + `flutter test`, both
      **in full** (not per-file), per CLAUDE.md §6. State which suite ran and the counts (report the delta
      from the branch-start baseline; note that Task 0/15a *remove* some 3.3 POSTPONED tests, so the delta
      is not purely additive).

### Review Findings

Code review 2026-09-05 (bmad-code-review, Opus 4.8, three layers: Blind Hunter · Edge Case Hunter ·
Acceptance Auditor). 2 decision-needed, 9 patch, 0 defer, 3 dismissed. Severity in brackets.

**Decision-needed** (RESOLVED 2026-09-05 by Timo → now patches):

- [x] [Review][Patch] [MEDIUM] Make `completeTrip` converge on an already-`DONE` list. **Decision:
      converge if already DONE.** Add `if (status == ListStatus.DONE) return;` (convergent no-op, AD-8)
      before the `status != IN_TRIP` guard, so a lost-ack re-delivery of the same completion pops
      cleanly; an `OPEN` (never-in-trip) list still throws `TripNotCompletableException` → 409 (AC8
      preserved for a genuine conflict). Add the matching test. [`ShoppingList.java:410`]
- [x] [Review][Patch] [MEDIUM] Give the trip-completion PM a bounded concurrency-retry. **Decision: add
      bounded retry.** Replace the single-shot converge-on-conflict in `onTripCompletedForList` with a
      bounded reload→re-apply→append retry loop so a genuine concurrent trip-stream append is retried
      rather than swallowed (list `DONE` but trip stuck `ACTIVE`); retrofit `onTripStartedForList` to the
      same loop for consistency, delivering the Cl.3/AD-8 "bounded concurrency-retry". Add the
      "lost-race converges" test. [`TripLifecycleProcessManager.java:73`]

**Patch** (unambiguous fixes):

- [x] [Review][Patch] [HIGH] Completing a trip from the "Einkauf" tab crashes — `ShoppingListsCubit` is
      not an ancestor there. `active_trips_view.dart:81` calls `context.read<ShoppingListsCubit>()`, but
      `HouseholdShell` provides `ShoppingListsCubit` only inside the *Listen* tab subtree and
      `ActiveTripsCubit` only inside the *Einkauf* tab subtree — siblings in the `IndexedStack`, so the
      lists cubit is out of scope. Completing a trip from the Einkauf tab throws
      `ProviderNotFoundException` in the async `onTap` after the trip already completed; AC7 archive
      invalidation never runs from this path. Untested (`active_trips_view_test` only asserts navigation).
      Fix: hoist `ShoppingListsCubit` to a `MultiBlocProvider` above the `IndexedStack`, or stop reaching
      across tabs. [`active_trips_view.dart:81`, `household_shell.dart:61`]
- [x] [Review][Patch] [MEDIUM] Read-only Done-list detail renders no `DONE`/`DISCARDED` terminal
      treatments (AC7 / Task 17). `_ItemRow` renders `title: Text(item.name)` with no status-dependent
      style, dim, or "Verworfen" label — a completed list opened from "Erledigt" shows plain rows. AC7
      requires terminal treatments in the read-only archive detail; the styling exists only in the trip
      screen's `_TripItemRow`. [`list_detail_page.dart:275`]
- [x] [Review][Patch] [MEDIUM] Completion sheet is a frozen `cubit.state` snapshot — in-sheet "Verwerfen"
      gives no feedback and confirm can silently no-op. `_CompletionSheetBody`/`_LeftoverItemRow` are
      `StatelessWidget`s reading `cubit.state` once (no `BlocBuilder`). Tapping "Verwerfen" on a leftover
      neither pops nor rebuilds — the row stays with live buttons, "N von M" never updates, re-tap fires
      redundant commands. Separately, tapping "Einkauf abschließen" while a leftover discard/transfer is
      still in flight silently no-ops (`completeTrip` early-returns on `isSubmitting`) after the sheet
      already popped — the member believes it completed. Fix: wrap the sheet in
      `BlocBuilder<TripCubit, TripState>`; disable/guard confirm while `isSubmitting`.
      [`trip_screen.dart:408`]
- [x] [Review][Patch] [MEDIUM] Specified tests for tasks marked `[x]` are absent (CLAUDE.md §6 TDD;
      the story claims the deferred invariant tests "finally land"). Missing: `renameADoneList_throws…`
      (Cl.9b/Task5); `uncheckItem_returnsADiscardedItemToOpen` (Cl.1/Task5);
      `completeTrip_onADoneList_throwsTripNotCompletable` (Task5); `discardItem_forAnUnknownItem_throwsItemNotFound`
      (Task5); projector `ListDoneLists`/`ListOpenLists` membership + two-household isolation/replay for
      the completion sequence (Cl.9a/Action10/Task10); read-only-archive e2e with a genuinely-completed
      list (`ListDoneListsTest` untouched, Cl.9c); `CompleteTripHandlerTest` cross-household-404 +
      re-delivery dedupe + the fixture seeds no items so the sweep (`ItemDiscarded`×open) is never
      asserted (Task7b); `TripLifecycleProcessManagerTest` "lost-race converges" (Task8);
      `ItemControllerTest` discard 400/403/409-not-In-Trip contract (Task11b). Re-verify Task 22's
      full-suite-green claim and its counts once these land.
- [x] [Review][Patch] [LOW] Stale/incomplete javadoc references to the retired `POSTPONED`/`ItemPostponed`
      (Cl.13 Boy-Scout). `ItemUnchecked.java:16` ("undo affordance for both `DONE` and `POSTPONED`" →
      `DISCARDED`); `DomainEvent.java:9` uses `{@code ItemPostponed}` as its example event (now deleted);
      `ItemNotDuringTripException.java:11` lists "checked off, unchecked, postponed, or rerouted" but omits
      the new `discarded` in-trip mutation.
- [x] [Review][Patch] [LOW] `TripState.remainingOpenCount` is dead code — defined + mentioned in the
      class doc, never read (the sheet uses `openItems.isNotEmpty`); Task 16 also specified a
      `remainingOpenCount` cubit test that does not exist. Remove it, or wire + test it. [`trip_state.dart:115`]
- [x] [Review][Patch] [LOW] `TripCubit.completeTrip` doc claims it "reverts on failure" but there is no
      optimistic state to revert (only `isSubmitting`/`actionError` are set). Reword. [`trip_cubit.dart:182`]
- [x] [Review][Patch] [LOW] `completeTrip` sweep iterates `itemsById.entrySet()` while
      `raise → apply(ItemDiscarded) → setStatus → put` mutates the map. Safe today only because
      `LinkedHashMap.put` on an existing key is non-structural; fragile if the fold ever adds/removes a
      key. Snapshot the still-`OPEN` item ids before raising. [`ShoppingList.java:418`]
- [x] [Review][Patch] [LOW] `onTripCompletedForList` NPEs on an empty/absent trip stream. If the trip's
      `TripStarted` was never materialized (start reaction log-and-skipped), `rehydrate([])` yields null
      fields, `complete()` proceeds (null ≠ `DONE`) and raises `TripCompleted` whose `requireNonNull`
      guards throw — escaping the try (which wraps only the append); the trip never completes and its
      store rows are never cleaned. Guard for empty history before rehydrating. [`TripLifecycleProcessManager.java:73`]

**Dismissed** (evaluated, not defects):

- Deleting the persisted `ItemPostponed` event type / `POSTPONED` rows breaks replay/migration (raised by
  Blind Hunter + Edge Case Hunter, who lacked the spec) — dismissed per **Cl.13 (LOCKED)**: nothing has
  ever been deployed or run against a persistent store, so no such events/rows exist anywhere; clean
  removal, no migration, Testcontainers use a fresh store per run.
- `tripId` path param not validated against the list's active trip — dismissed per **Cl.7 (LOCKED)**:
  `tripId` is informational, the handler resolves via the list, and a list has one active trip, so
  completion always targets *its* active trip regardless of the URL id ("wrong trip" is unreachable).
- E4 "skip straight to confirm" allegedly unimplemented — dismissed: AC6 means skip the *per-item
  leftover step* (which the code does when `openItems.isEmpty`) and show a simple confirm; behavior
  matches AC6.

## Dev Notes

### What is (and isn't) in this story — read first

3.4 is the **payoff of the Epic-3 groundwork**: SGART's first **`Active → Done` trip completion**, the
first time a **list becomes `DONE` and immutable**, and the read-only **"Erledigt" archive** (empty since
Story 2.2) holding a real list. It also does two consolidations Timo settled on 2026-09-04: **discard
replaces in-place postpone** as the single "not bought, stays here" status (Cl. 13), and **every per-item
trip-row action folds into one ⋯ sheet** (Cl. 12). Thanks to 3.1 (list→PM→trip start) and 3.3 (item
status + postpone-to-list), the genuinely *new* domain surface is small.

The crux ideas:

- **`ItemStatus { OPEN, DONE, DISCARDED }` — POSTPONED retired (Cl. 1/13).** In-place postpone and discard
  did the same thing; `DISCARDED` is the one terminal "not bought, stays dimmed on the list" status.
  Removing the shipped in-place-postpone surface is Task 0/15a. `ItemPostponedToList` (carry to another
  list) stays — a distinct action.
- **DISCARD is a first-class in-trip action *and* a completion safety net (Cl. 2/12).** `discardItem`
  (mirrors 3.3 `checkOffItem`) sets `DISCARDED` while shopping; `completeTrip` sweeps any *still*-`OPEN`
  item to `DISCARDED` (same event) then raises `TripCompletedForList` — one atomic append, in-aggregate
  while still `IN_TRIP`.
- **Completion mirrors trip start (Cl. 3).** `completeTrip` → `TripCompletedForList` (list) → the trip PM
  (extended, renamed `TripLifecycleProcessManager`) → `ShoppingTrip.complete` → `TripCompleted` (trip,
  `ACTIVE → DONE`).
- **One ⋯ sheet per trip row (Cl. 12).** Row = **checkbox + ⋯**; ⋯ opens „Was tun mit diesem Artikel?" =
  **Anderes Geschäft** (reroute, 3.2) · **auf andere / Neue Liste** (postpone-to-list, 3.3) · **Verwerfen**
  (discard, new). The trip view is grouped by store, so a per-item store affordance is redundant; matches
  `screen-active-trip.html`'s ⋯ menu. Backend-unchanged apart from `discardItem`.
- **The first real `DONE` unlocks the deferred invariants (Cl. 9, Action 11).** Reachable `rename`-on-`DONE`
  test, the end-to-end read-only archive, and the cached Done-archive invalidation all land now.

It **deliberately does not** build (Cl. 11): the **two-phase transfer saga** or the client false-success
transfer-drop UX (Story `3-6`); **print/share** (Story 3.5); **cross-device live-sync** (Epic 4 SSE).

Flow (a mix of reroute + discard + transfer, then complete):

```
member on the trip screen (Story 3.2/3.3) — each row: [checkbox] Name  [⋯]
  tap ⋯ → „Was tun mit diesem Artikel?"
     ├─ Anderes Geschäft   → showStorePickerSheet (trip stores) → cubit.reroute → ItemRerouted (store change)
     ├─ auf andere Liste / Neue Liste → POST …/items/{itemId}/postpone-to-list {targetListId, commandId}
     │      → ItemPostponedToList → ItemMoveProcessManager → AddItem on the target   (item leaves NOW)
     └─ Verwerfen          → POST …/items/{itemId}/discard {commandId}
            → ShoppingList.discardItem → ItemDiscarded (→ DISCARDED, dimmed, stays)
  scrolls to the list end → tap „Einkauf abschließen"
     completion dialog „Fertig?" (N von M erledigt; X offen); per open item Übernehmen (postpone-to-list) / Verwerfen (discard)
  confirm „Einkauf abschließen"
     → POST …/lists/{listId}/trips/{tripId}/complete {commandId}
       → CompleteTripHandler: load list-{id}, requireInTrip()
          → ShoppingList.completeTrip:  raise ItemDiscarded ×(each still-OPEN item)   (→ DISCARDED)
                                        raise TripCompletedForList                    (→ list DONE)
       KurrentDB $all ─filter list-*─▶ ShoppingListReadModelProjector
          → setStatus(item, DISCARDED) …            (item_read_model.status)
          → markDone(listId): status='DONE', active_trip_id=NULL   (leaves Open set → ListDoneLists)
       KurrentDB $all ─filter list-*─▶ TripLifecycleProcessManager.onTripCompletedForList
          → ShoppingTrip.complete → TripCompleted (trip-{id}, ACTIVE→DONE)
       KurrentDB $all ─filter trip-*─▶ ShoppingTripReadModelProjector.project(TripCompleted)
          → delete trip_store_read_model rows for the trip
  back on the overview → archive cache invalidated → „Erledigt" shows the completed list (read-only)
```

### Architecture patterns & constraints

- **AD-4 CQRS, projection-only.** `markDone` (status + `active_trip_id`) and the `DISCARDED` status are
  written only by the projector; handlers never write read models. Eventually consistent — the client
  completes optimistically (the trip screen pops; the archive refetches). [#AD-4]
- **AD-8 online load-then-append + idempotency.** Each handler reads the list stream, uses the loaded
  version as the expected version, and appends; the completion PM derives its command id deterministically
  from the `TripCompletedForList` event id and retries a lost race a bounded number of times. A re-delivered
  completion is a convergent no-op (EventStore dedupe + `trip.complete`'s `DONE` no-op); already-`DISCARDED`
  / same-status are no-ops that skip the append. [#AD-8]
- **AD-10 aggregate boundaries + cross-aggregate via a PM.** The sweep + list→`DONE` mutate only the
  `ShoppingList`; the trip→`DONE` is a separate append on the `ShoppingTrip`, driven by the PM (single
  writer per append). TRANSFER's target-add is the existing PM's job. [#AD-10]
- **AD-5/AD-6 no PII, no audit.** `ItemDiscarded` / `TripCompletedForList` / `TripCompleted` carry ids only
  — no `MemberId`, no *who* (mirrors `ItemCheckedOff` / `TripStartedForList` / `TripStarted`). Read models
  queried by `household_id`. [#AD-5/#AD-6]
- **Spine — "Trip lifecycle is permanently `Active → Done`".** No `Paused`. Both `TripStatus.DONE` and
  `ListStatus.DONE` already exist in the enums (reserved since 3.1/2.1); 3.4 makes them reachable.
- **AD-11 ubiquitous language.** `completeTrip`/`TripCompletedForList`/`TripCompleted`;
  `discardItem`/`ItemDiscarded`/`DISCARDED`; „Einkauf abschließen" / „Verwerfen" / „Verworfen" / „Doch noch
  weiter einkaufen" / „Anderes Geschäft" (reroute) / „auf andere Liste" (carry-over). Discard (not bought,
  stays) ≠ reroute (buy elsewhere this trip) ≠ move-to-list (carry over). No in-place-postpone concept
  remains (Cl. 13). No abbreviations. [#AD-11]

### Source tree — mirror / touch these exact files

| New/changed | Mirror | Path |
| --- | --- | --- |
| **remove** `ItemPostponed`, `postponeItemInPlace`+`apply(ItemPostponed)`, `PostponeItem`/`PostponeItemHandler`, `POST …/postpone`, codec `ItemPostponed`, projector `case ItemPostponed`, `ItemStatus.POSTPONED` (Cl. 13) | — | `collaboration/**` |
| `ItemStatus.DISCARDED` (add; `POSTPONED` removed) | (extend) `ItemStatus` | `collaboration/domain/` |
| `ShoppingList.discardItem` + `completeTrip` + `activeTripId` fold + `apply(ItemDiscarded)`/`apply(TripCompletedForList)` | (extend) `ShoppingList` (`checkOffItem`/`startTrip`/`requireInTrip`/`setStatus`) | `collaboration/domain/` |
| `ShoppingTrip.complete` + `apply(TripCompleted)` | (extend) `ShoppingTrip` (`addStore`) | `collaboration/domain/` |
| `ItemDiscarded` (event) | `ItemCheckedOff` | `collaboration/domain/event/` |
| `TripCompletedForList` / `TripCompleted` (events) | `TripStartedForList` / `TripStarted` | `collaboration/domain/event/` |
| `TripNotCompletableException` | `TripNotStartableException` | `collaboration/domain/exception/` |
| `DiscardItem` + `DiscardItemHandler` | `CheckOffItem` + `CheckOffItemHandler` | `collaboration/application/command/` |
| `CompleteTrip` + `CompleteTripHandler` | `StartTrip` + `StartTripHandler` | `collaboration/application/command/` |
| `TripNotCompletableApplicationException` | `TripNotStartableApplicationException` | `collaboration/application/exception/` |
| (discard reuses 3.3's `ItemNotDuringTripApplicationException`) | — | `collaboration/application/exception/` |
| `TripStartProcessManager.onTripCompletedForList` (+ rename `TripLifecycleProcessManager`) | (extend) `onTripStartedForList` | `collaboration/application/` |
| `CollaborationProcessManagerSubscription` route `TripCompletedForList` | (extend) | `collaboration/adapter/out/` |
| `ShoppingListReadModel.markDone` / `JdbcShoppingListReadModel` | (extend) `markInTrip` | `collaboration/domain/readmodel/`, `adapter/out/` |
| list projector `ItemDiscarded`/`TripCompletedForList` cases (remove `ItemPostponed`) | (extend) `ShoppingListReadModelProjector` | `collaboration/adapter/out/` |
| trip projector `TripCompleted` cleanup + `trip_store_read_model` `deleteForTrip` | (extend) `ShoppingTripReadModelProjector` | `collaboration/adapter/out/` |
| `ItemController` `POST …/{itemId}/discard` (200); remove `/{itemId}/postpone` | (extend, mirror `/{itemId}/check-off`) | `collaboration/adapter/in/` |
| `TripController` `POST …/{tripId}/complete` (200) | (extend, mirror `/{tripId}/stores`) | `collaboration/adapter/in/` |
| `DomainEventJsonCodec` (add 3 events; remove `ItemPostponed`) | (extend) | `collaboration/adapter/out/` |
| `ItemStatus.discarded` + `ItemsApi.discardItem` + `TripsApi.completeTrip` (remove `ItemStatus.postponed`) | (extend) `item.dart`, `items_api.dart`, `trips_api.dart` | `app/lib/features/lists/data/`, `app/lib/features/trips/data/` |
| `TripCubit.discard` + `completeTrip` + `TripState` leftover getters (remove `postponeInPlace`) | (extend) | `app/lib/features/trips/presentation/` |
| consolidated ⋯ item-actions sheet (reroute + postpone-to-list + discard) replacing the scattered trailing actions; retire „Hier vormerken" | (new) `trip_item_actions_sheet.dart`, composing `store_picker_sheet.dart` + `postpone_target_sheet.dart`; rework `_TripItemRow._buildTrailing` | `app/lib/features/trips/presentation/` |
| completion dialog + DISCARDED rendering | (new/extend) `trip_screen.dart`; reuse `postpone_target_sheet.dart` | `app/lib/features/trips/presentation/` |
| Done-archive cache invalidation (Action 11) | (extend) `shopping_lists_cubit.dart` | `app/lib/features/lists/presentation/list_overview/` |

### Package structure (CLAUDE.md §8)

New backend classes drop into the **existing** intent subpackages (`domain`, `domain.event`,
`domain.exception`, `application` (the PM), `application.command` with the DTO beside its handler,
`application.exception`, `adapter.out`, `adapter.in`). **No new controller** (discard extends
`ItemController`; complete extends `TripController`), **no new migration** (DISCARDED reuses V10's status
column), **no new Flutter feature package** (completion + the ⋯ sheet extend `features/trips/`; the
transfer target reuses `features/lists/`). No ArchUnit rule change.

### Testing standards

- **Domain first (pure):** `ShoppingListTest` — `discardItem` raises `ItemDiscarded` → DISCARDED,
  IN_TRIP-gated, already-DISCARDED no-op, unknown item; `completeTrip` sweeps still-OPEN + folds DONE; E4
  no-open-items; IN_TRIP-gated (Open/Done → `TripNotCompletableException`); reachable `rename`-on-`DONE`
  (Cl. 9(b)); `uncheck` returns DISCARDED→OPEN. `ShoppingTripTest` — `complete` raises `TripCompleted` +
  fold; already-DONE no-op. **Removed:** the in-place-postpone aggregate tests (Task 0).
- **Handler:** `DiscardItemHandlerTest` (mirror `CheckOffItemHandlerTest`) — 200-append / 403 / 400 /
  404×2 / 409-not-In-Trip / unknown-item / no-op. `CompleteTripHandlerTest` (mirror `StartTripHandlerTest`)
  — sweep + completion; 403/400/404×2/409; re-delivery dedupe. **Removed:** `PostponeItemHandlerTest`.
- **Process manager:** the trip PM completes the trip once on `TripCompletedForList`; deterministic-id
  re-delivery no-ops; concurrency race converges.
- **Projector/read model (Testcontainers):** `ShoppingListReadModelProjectorTest` — `ItemDiscarded` →
  DISCARDED; `TripCompletedForList` → DONE + `active_trip_id` NULL; isolation + replay idempotency (Action
  10); completed list in `ListDoneLists`, gone from `ListOpenLists` (Cl. 9(a)); **removed** the
  `ItemPostponed` case test. `ShoppingTripReadModelProjectorTest` — `TripCompleted` deletes the trip's
  store rows.
- **Controller (MockMvc):** `ItemControllerTest` — discard → 200/400/403/404/409 (removed the postpone
  case). `TripControllerTest` — complete → 200/400/403/404/409 (Action-2 contract). `DomainEventJsonCodecTest`
  — three new events round-trip (removed the `ItemPostponed` round-trip, unless the legacy-tolerance
  fallback is taken).
- **Flutter (fakes only):** `items_api` discard + `trips_api` complete request shapes; `TripCubit` (`discard`
  optimistic → DISCARDED + revert; `completeTrip` optimistic completed-outcome + revert + intent freshen +
  `isSubmitting`; `remainingOpenCount`); the **⋯ sheet** (OPEN row = checkbox + ⋯; routes Anderes
  Geschäft/auf andere Liste/Neue Liste/Verwerfen; DONE = checkbox; DISCARDED = checkbox + UNDO; **3.2
  reroute + 3.3 postpone-to-list regress through the sheet**); the completion dialog (action now present;
  leftover step; Übernehmen reuses postpone-to-list; Verwerfen → discard; keep-shopping cancels; E4 skip);
  DISCARDED dimmed rendering; `shopping_lists_cubit` archive-invalidation (Action 11). **Removed:** the
  in-place-postpone / POSTPONED tests (Task 15a).
- **DSGVO:** synthetic German data only; explicit no-PII stance on the three events (AC8).
- **Green build = full suite** for both modules; state which ran and the counts (report the delta — not
  purely additive, since Task 0/15a remove 3.3 POSTPONED tests).

### Deferred / do-not-build (premature-value discipline)

- **Two-phase reserve-then-remove transfer saga + the client false-success transfer-drop UX** → **Story
  `3-6-two-phase-transfer-saga`** (Cl. 11). 3.4's leftover TRANSFER inherits 3.3's interim
  `UNRECOVERABLE_TRANSFER` guard as-is.
- **Print / share the grouped list** → **Story 3.5**.
- **Cross-device live-sync of completion** → **Epic 4** (SSE). MVP: the actor completes optimistically;
  peers refetch on open.
- **A trip-status read model / trip history header** — YAGNI (Cl. 6).
- **`postpone-to-list` 404-on-lost-response idempotency** — a cross-handler fix, still deferred.
- **Optional rename `ItemPostponedToList` → a `…Transfer…` name** now that in-place postpone is gone and
  "postpone" only means carry-to-a-list — deferred Boy-Scout (touches shipped events/codec); note only.

### Project Structure Notes

- Two new endpoints: `POST …/items/{itemId}/discard` (ItemController) and `POST
  …/lists/{listId}/trips/{tripId}/complete` (TripController), both 200 (the item/trip family conventions).
  `completeTrip` is a `ShoppingList` command — the `{tripId}` is informational.
- **No migration** (V10's `status VARCHAR(16)` holds 'DISCARDED'; `active_trip_id` already nullable).
- Retiring the shipped `ItemPostponed` event is a clean removal — nothing has been deployed or run, so no
  persisted events exist and no reset/migration is needed (Cl. 13, Task 0).

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story-3.4] — user story + BDD ACs (guided „Fertig?"; per open item TRANSFER to existing/new or DISCARD; PM-placed transfers; list → Done + immutable; never force-complete; E4 skip; E3 Open-only targets).
- [Source: ARCHITECTURE-SPINE.md #AD-4/#AD-8/#AD-10/#AD-11 + "Deferred: Trip lifecycle is permanently Active → Done"].
- [Source: ux-designs/ux-sgart-2026-08-20/EXPERIENCE.md §3 „Trip lifecycle"; journeys.md J3 (UJ-3); .working/screen-trip-lifecycle.html; .working/screen-active-trip.html (per-row checkbox + ⋯ menu)] — the quiet „Einkauf abschließen"; the multi-step „Fertig?" → Übernehmen/Verwerfen dialog; done = list Done+immutable, transferred items on a fresh list.
- [Source: backend `ShoppingList.java` (`startTrip`/`requireInTrip`/`apply(TripStartedForList)`/`checkOffItem`/`uncheckItem`/`setStatus`/`rename` DONE guard/`ItemState`), `ShoppingTrip.java` (`start`/`addStore`/`apply`), `TripStarted.java`/`TripStartedForList.java`, `ItemCheckedOff.java`, `CheckOffItem.java`/`CheckOffItemHandler.java`, `StartTrip.java`/`StartTripHandler.java`, `TripStartProcessManager.java`, `CollaborationProcessManagerSubscription.java`, `TripController.java` (`@PostMapping`, `/{tripId}/stores`), `ItemController.java` (`/{itemId}/check-off`), `ShoppingListReadModelProjector.java`, `ShoppingTripReadModelProjector.java`, `JdbcShoppingListReadModel.java` (`active_trip_id`, `markInTrip`), `ShoppingListReadModel.java`, `TripStoreReadModel.java`/`JdbcTripStoreReadModel.java`, `ListDoneLists.java`/`ListOpenLists.java`, `DomainEventJsonCodec.java`, `TripNotStartableException.java`, `WriteErrorAdvice.java`, `V10__item_status.sql`] — patterns to mirror; and the in-place-postpone surface to remove (`ItemPostponed.java`, `PostponeItem*.java`, `postponeItemInPlace`).
- [Source: app `features/lists/data/item.dart` (`ItemStatus` enum), `features/lists/data/items_api.dart` (`checkOffItem`), `features/trips/data/trips_api.dart`, `features/trips/presentation/trip_cubit.dart`/`trip_state.dart`/`trip_screen.dart` (`_openReroutePicker`/`_openPostponeSheet`/`_TripItemRow`), `features/stores/presentation/store_picker_sheet.dart`, `features/lists/presentation/list_detail/postpone_target_sheet.dart` (retire „Hier vormerken", keep target selection), `features/lists/presentation/list_overview/shopping_lists_cubit.dart` (`archiveStatus` cache, Action 11), `shared/commands/command_intent.dart`, `theme/tokens/sgart_colors.dart`] — client patterns to mirror; in-place-postpone client surface to remove.
- [Source: _bmad-output/implementation-artifacts/3-1-…md] — the list→PM→trip start flow mirrored for completion.
- [Source: _bmad-output/implementation-artifacts/3-3-…md] — item status + `setStatus`/`uncheckItem` symmetric reset; check-off pattern (the `discardItem` mirror); the postpone-to-list transfer seam reused for TRANSFER; the in-trip 409; the in-place-postpone surface retired here (Cl. 13).
- [Source: _bmad-output/implementation-artifacts/deferred-work.md] — „Cached Done archive … Epic 3" (Action 11); „DONE-rejects-rename / read-only-archive e2e … Epic 3" (Cl. 9); the 3-6 transfer-drop defer (Cl. 11).
- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-08-29.md; sprint-status `3-6` note] — the two-phase saga carved to Story 3-6.
- [Source: _bmad-output/implementation-artifacts/epic-2-retro-2026-08-28.md §6 + sprint-status open action items] — carried actions 2/3/7/8/9/10/12 as DoD (Cl. 10); Action 11 realised here.
- [Source: CLAUDE.md §1–§8; memory git-workflow (commit-to-main pre-beta; nothing deployed/run, so Cl. 13's `ItemPostponed` removal is clean — no event-store reset needed)].

## Dev Agent Record

### Agent Model Used

(planning: Claude Opus 4.8)

### Debug Log References

### Completion Notes List

### File List

## Change Log

- 2026-09-04: Story drafted (create-story, Opus 4.8) — Epic 3's fourth story: SGART's first `Active →
  Done` trip completion. **Timo decided (2026-09-04):** (1) DISCARD = a new `ItemDiscarded` event → a
  terminal `ItemStatus.DISCARDED`, the item stays on the list dimmed; (2) discard is BOTH a first-class
  in-trip action (`discardItem`, mirrors 3.3 `checkOffItem`; `POST …/items/{itemId}/discard`) AND an
  auto-discard sweep in `completeTrip` (QoL safety net → `TripCompletedForList` `IN_TRIP → DONE`); (3)
  every per-item trip-row action consolidates into ONE ⋯ „Was tun mit diesem Artikel?" sheet (Anderes
  Geschäft/reroute + auf andere/neue Liste + Verwerfen); (4) **retire Story 3.3's in-place `POSTPONED`**
  as redundant with `DISCARDED` — `ItemStatus` becomes `{ OPEN, DONE, DISCARDED }`, removing the shipped
  `ItemPostponed`/`postponeItemInPlace`/`PostponeItem*`/`POST …/postpone`/„Hier vormerken" surface end-to-
  end (Cl. 13; keep `ItemPostponedToList` move-to-list; clean removal — nothing deployed/run, no
  event-store reset needed). Completion
  mirrors 3.1 trip-start via the trip PM (renamed `TripLifecycleProcessManager`) → `ShoppingTrip.complete`
  → `TripCompleted`. TRANSFER reuses 3.3's postpone-to-list verbatim; two new endpoints (discard +
  complete). First real `DONE` lights up the read-only „Erledigt" archive + lands the deferred reachable
  `rename`-on-`DONE` test, the read-only-archive e2e, and the cached-Done-archive invalidation (Action
  11). 13 LOCKED clarifications. The two-phase transfer saga + the client false-success transfer-drop UX
  stay in Story `3-6` (Cl. 11). No new migration (DISCARDED reuses the V10 status column). Baseline (last
  green ≈ backend 577 / Flutter 491 at `de675a3`; delta not purely additive — POSTPONED tests removed).
