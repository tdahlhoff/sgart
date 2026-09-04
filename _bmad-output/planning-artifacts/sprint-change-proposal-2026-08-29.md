# Sprint Change Proposal — Story 3.3 (2026-08-29)

**Author:** Timo (via correct-course) · **Story:** `3-3-check-off-uncheck-and-postpone-during-a-trip` · **Scope:** Moderate

## 1. Issue Summary

The Story 3.3 code review (2026-08-29, commit `db89c71`) surfaced two decision-level findings and a
body of defects. The lead decision — whether the **in-place postpone** variant should exist — was
first routed toward *removal* ("no value flagging POSTPONED on a soon-to-be-DONE list"). On
reflection the purpose became clear: in-place postpone is the terminal state for an item that is
**not bought and not being moved to another list**, so a member can close a trip cleanly without
carrying an unwanted item somewhere it doesn't belong. The overlap with Story 3.4's completion-time
DISCARD is a *when* difference (mid-trip, per item) not a redundancy.

**Outcome: keep the feature; fix its defects.** No product-spec change.

## 2. Impact Analysis

- **PRD / Epics / Architecture / UX:** **No change.** `ItemStatus {OPEN, DONE, POSTPONED}`, all three
  postpone variants (in-place · to-existing · to-new), and the AD-10 `ItemPostponedToList` → process-
  manager seam (reused by Story 3.4) all remain as specified.
- **Story 3.3:** stays `in-progress`; Review Findings updated to reflect "keep + fix".
- **Story 3.4:** unaffected — leftover review still operates on remaining **open** items via the
  idempotent PM.
- **Technical:** D2 reshapes the shared move/postpone process manager (also touches Story 2.4 move).

## 3. Recommended Approach

**Direct adjustment** — fix within the existing plan; no rollback, no MVP change. Re-dev Story 3.3
to clear the review findings, then return it to `review`.

## 4. Detailed Change Proposals (dev tasks)

### 4.1 Affordance fix — non-OPEN trip rows (`trip_screen.dart`, UI-only)
Domain already supports every transition (`checkOff` / `uncheck`); this is purely which controls each row shows.

| Row status | Checkbox | Other affordances |
|---|---|---|
| OPEN | ✔ → DONE | postpone (in-place / to-list) · reroute/assign — **unchanged** |
| DONE | → OPEN (uncheck) | **hide postpone + reroute** (removes the silent DONE→POSTPONED un-check) |
| POSTPONED | → DONE ("found it after all") | **UNDO → OPEN** (uncheck) · **hide reroute** |

### 4.2 D2 — reserve-then-remove (two-phase) in `ItemMoveProcessManager`
Invert the flow into a compensating saga so a not-Open target never loses the item:
1. Source flags/reserves the postpone-to-list (does **not** remove yet).
2. PM adds to target (idempotent, AD-10).
3. On target-add **success** → PM confirms removal on source.
4. On target-not-Open / failure → PM cancels; item stays on source (unlocked).

Applies to **both** `ItemPostponedToList` (3.3) and `ItemMovedToList` (2.4 move). Preserves AD-8/AD-10
idempotency. **Exact event/command shape to be pinned with the architect during dev.**

### 4.3 Missing tests (the story's own DoD, Tasks 7/8/9/12/13/14/22)
- `UncheckItemHandlerTest`, `PostponeItemHandlerTest`, `PostponeItemToListHandlerTest`
- `ItemMoveProcessManagerTest` extended for `ItemPostponedToList` (incl. the two-phase target-not-Open path)
- `ItemControllerTest` — the 4 new endpoints + error-advice contract
- Projector Cl.4 regression (status preserved on reroute/update after check) + status-column replay-idempotency
- Aggregate `postponeItemToList_forAnUnknownItem_throwsItemNotFound`; `TripViewTest` DONE-in-trip assertion

### 4.4 Patch findings
- Map `item.notDuringTrip` (409) in `error_message_resolver.dart` + add ARB string
- Surface `createList` failure in the postpone flow instead of the silent `return`
- Fix stale `item.notReroutable` doc (`items_api.dart:66`) and test (`trip_cubit_test.dart:104,111`)
- Fix or delete the hollow `movingViaThePlanningMoveSheetStillWorks` regression test
- Correct the over-promising "reused idempotency key" doc on postpone-to-list

## 5. Implementation Handoff

**Scope: Moderate → Developer (dev-story), with architect touch on 4.2.**
Re-dev Story 3.3 against §4. Success = all Review Findings cleared, full green build stated per suite
(backend `./gradlew test` incl. ArchUnit **and** `flutter test` / `flutter analyze`), story back to `review`.

**Deferred (unchanged):** postpone-to-list idempotency-on-lost-response (404 on retry) — pre-existing
2.4 pattern; logged in `deferred-work.md`. Note: §4.2's two-phase saga may partly subsume this; revisit
during dev.
