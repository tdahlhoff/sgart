---
baseline_commit: c64de8a4c263f606b0b8a51b782bfe495e21f31a
---

# Story 3.6: Two-phase transfer saga (reserve-then-remove)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a member,
I want an item I move or postpone to another list to never silently vanish when the target changes under me,
so that a cross-list transfer either lands on the target or stays where it was — never on neither.

## Context & Scope (read first)

**This is a robustness/refactor story, not a new feature — it reshapes SGART's cross-aggregate item
transfer from an eager single-phase remove into a two-phase compensating saga.** It was carved out
of the Story 3.3 code review (2026-08-29, D2) and specified in
`sprint-change-proposal-2026-08-29.md` §4.2. It is the last real story in Epic 3 (only the epic-3
retrospective remains after it).

**The defect it fixes.** Today the source list raises `ItemMovedToList` (2.4, OPEN-gated) or
`ItemPostponedToList` (3.3, IN_TRIP-gated), which **immediately folds to a removal** on the source;
`ItemMoveProcessManager` then asynchronously adds the item to the target. If the target leaves OPEN
between the handler's synchronous pre-check and the PM's async add (e.g. a concurrent `StartTrip` on
the target), the PM logs `UNRECOVERABLE_TRANSFER` and returns with the item **on neither list**. The
client already saw `200` and optimistically removed the row, so the item **silently vanishes**
(`deferred-work.md`, "code review of story-3.3 2026-08-30").

**The fix (reserve-then-remove, two-phase compensating saga).** The source **reserves** the item
(keeps it, marks it pending) instead of removing it. The PM adds to the target; on **success** it
tells the source to **confirm** (remove); on **failure** (target not OPEN / gone) it tells the
source to **cancel** (un-reserve). The item is on exactly one list at every instant — never dropped.

**Timo's locked decisions (2026-09-05):**
1. **Unify move + postpone into one `ItemTransfer*` saga vocabulary.** Introduce
   `ItemTransferInitiated` / `ItemTransferConfirmed` / `ItemTransferCancelled` (shared by both
   planning-move and in-trip-postpone) and **retire** `ItemMovedToList` + `ItemPostponedToList`. The
   OPEN-vs-IN_TRIP phase gate stays in the two aggregate methods (`moveItem` / `postponeItemToList`),
   which both raise the same `ItemTransferInitiated` carrying a `TransferOrigin` discriminator
   (`PLANNING_MOVE` / `IN_TRIP_POSTPONE`). DRY: one saga, one PM path, one read-model marker. Nothing
   is deployed (pre-beta — memory `git-workflow`), so events are reshaped freely with **no** codec
   version tolerance and **no** event-store reset.
2. **Fail-fast lock on a reserved item.** While an item is reserved, any *other* member mutation on it
   (edit / move / postpone / check-off / uncheck / discard / reroute / assign, or a *second, different*
   transfer) is rejected with a `409` (`item.transferInProgress`). A retry of the **same** transfer
   (same target) is a convergent no-op success — which also closes the lost-response 404 defect (see
   decision 4). Aligns with CLAUDE.md §1 Fail Fast.
3. **Client resolves the pending state by refresh, not polling.** After a successful initiate the
   client shows the item as „wird verschoben…" (pending) and **stops optimistically dropping it** —
   killing the false-success. The confirm-removal / cancel-restore is picked up on the next
   list/trip fetch or pull-to-refresh. No new client polling machinery (KISS/YAGNI; Epic-4 live-sync
   will push these outcomes later).
4. **Also close two adjacent items at this seam:** (a) the **lost-response 404 idempotency** defer
   (move/postpone `deferred-work.md`) — the reserve-then-confirm design makes initiate naturally
   idempotent (the item is still present-but-reserved after initiate, so a same-target retry is a
   convergent no-op); (b) **remove the interim `UNRECOVERABLE_TRANSFER` guard** — the two log-and-drop
   branches in the PM become dead once cancel compensates instead of dropping (Boy Scout).

**What this story is NOT.** No new REST endpoints (the `POST …/move` and `POST …/postpone-to-list`
endpoints keep their shape). No membership/identity work on confirm/cancel — those are **system**
compensations the PM issues directly on the source aggregate (exactly like the PM issues the target
`AddItem` today), with no `ResolveMemberIdentity` and no controller. No new client screens. Live-sync
push of confirm/cancel is Epic 4, not here.

## Acceptance Criteria

1. **(AC1 — reserve, don't remove; both phases)** **Given** an item on a source list, **when** a
   member moves it (source `OPEN`) or postpones it to a list (source `IN_TRIP`), **then** the source
   raises the single `ItemTransferInitiated` (carrying `householdId`, `sourceListId`, `itemId`,
   `targetListId`, the item payload `name`/`note`/`quantity`, and `TransferOrigin`), which folds to a
   **pending/reserved sub-state on the item — the item stays on the source, it is not removed** — and
   the command returns success once the reservation is appended.
2. **(AC2 — confirm on target success)** **Given** an `ItemTransferInitiated`, **when** the
   `ItemTransferProcessManager` reacts, **then** it adds the item to the target list exactly once
   (idempotent via a command id derived from the initiate event), and **on a successful add** (or a
   converged duplicate on the target) it issues `confirmItemTransfer` on the source →
   `ItemTransferConfirmed` → the item is **removed from the source**. Net happy-path effect is
   identical to today: the item ends up on the target only.
3. **(AC3 — cancel/compensate on target failure — the bug fix)** **Given** the target is **not
   `OPEN`** or has **no stream** when the PM tries the add (the race the interim guard used to
   log-and-drop), **then** the PM issues `cancelItemTransfer` on the source →
   `ItemTransferCancelled` (carrying a `TransferCancellationReason`) → the item **returns to its
   normal state on the source**. At no instant is the item on neither list. The former
   `UNRECOVERABLE_TRANSFER` log-and-drop branches are gone.
4. **(AC4 — fail-fast lock + lost-response idempotency)** **Given** an item currently reserved
   (pending transfer), **when** any *other* member command targets it — edit, remove, move, postpone,
   check-off, uncheck, discard, reroute, assign, or a transfer to a *different* target — **then** it
   is rejected with `409` `item.transferInProgress`. **When** the *same* transfer is retried (same
   target, e.g. a client retry after a lost `200`), **then** it is a **convergent no-op success**
   (raises nothing, append skipped) — the item stays reserved exactly once, and the client gets `200`
   instead of the old `404`.
5. **(AC5 — read-model marker + client pending affordance)** The `item_read_model` carries a
   `transfer_pending` flag (V11): `ItemTransferInitiated` sets it (row **kept**, not removed),
   `ItemTransferConfirmed` removes the row, `ItemTransferCancelled` clears the flag. List-detail
   (`ListItems`) and trip (`TripView`) reads expose it. The client renders a reserved item as
   „wird verschoben…" and **non-interactive**, and **no longer drops the row on a successful
   initiate**; confirm/cancel reconcile on the next fetch/refresh (no polling).
6. **(AC6 — unified vocabulary, no regressions, green build)** `ItemMovedToList` and
   `ItemPostponedToList` (events, codec types/payloads, projector cases, PM methods, subscription
   routing) are **retired** in favour of the single `ItemTransfer*` saga; `ItemMoveProcessManager` is
   renamed `ItemTransferProcessManager`. The 2.4 move happy path, the 2.4 client-orchestrated
   quantity-**merge** dialog, the 3.3 postpone happy path, and the 3.4 completion/leftover flow all
   still pass. Full green build stated per suite: backend `./gradlew test` (incl. ArchUnit +
   Testcontainers projector/PM suites) **and** app `flutter test` + `flutter analyze` (0 issues).

## Tasks / Subtasks

> Order matters: domain first (TDD, CLAUDE.md §6 domain-first), then application/PM, then read side,
> then client. Write the failing test before the change at each step.

- [x] **Task 1 — New transfer events + enums (domain).** (AC: 1,2,3,6)
  - [x] Create `domain/event/ItemTransferInitiated.java` — record
        `(EventId eventId, HouseholdId householdId, ShoppingListId sourceListId, ItemId itemId,
        ShoppingListId targetListId, ItemName name, ItemNote note, Quantity quantity,
        TransferOrigin origin)`. `note` intentionally nullable (mirror `ItemMovedToList`). Carries no
        personal data (AD-5/AD-6). Javadoc: folds to a **reserved** sub-state on the source (item
        stays), unlike the removed `ItemMovedToList`.
  - [x] Create `domain/event/ItemTransferConfirmed.java` — record `(EventId eventId,
        ShoppingListId listId, ItemId itemId)`. Folds to **removal** on the source (this is the old
        eager removal, now deferred to confirmation).
  - [x] Create `domain/event/ItemTransferCancelled.java` — record `(EventId eventId,
        ShoppingListId listId, ItemId itemId, TransferCancellationReason reason)`. Folds to
        **un-reserve** on the source.
  - [x] Create `domain/TransferOrigin.java` — enum `{ PLANNING_MOVE, IN_TRIP_POSTPONE }` (which
        aggregate method raised the initiate; audit/telemetry + keeps the two call sites honest).
  - [x] Create `domain/TransferCancellationReason.java` — enum `{ TARGET_NOT_OPEN, TARGET_GONE }`
        (why the PM compensated; for logs/audit and future live-sync surfacing).
  - [x] **Delete** `domain/event/ItemMovedToList.java` and `domain/event/ItemPostponedToList.java`
        after every reference is migrated (Tasks 2–6, 8).

- [x] **Task 2 — Aggregate: reserve / confirm / cancel + fail-fast lock (`ShoppingList`).** (AC: 1,2,3,4)
  - [x] Extend the private `ItemState` record with a nullable `PendingTransfer pendingTransfer`
        (a new nested record holding at least `ShoppingListId targetListId`); `null` = not reserved.
        Update every `new ItemState(...)` construction site and the `assignStore`/`setStatus` folds to
        **preserve** `pendingTransfer` (the Cl.4/7 preserve-through-fold pattern already established).
  - [x] Reshape `moveItem(itemId, targetListId, commandId)`: keep `requireOpen()` + item-exists;
        **then** — if the item is already reserved to the **same** `targetListId` → `return`
        (convergent no-op, AD-8, the lost-response retry); if reserved to a **different** target →
        throw the new `ItemTransferInProgressException`; else raise `ItemTransferInitiated(origin =
        PLANNING_MOVE)`. **No longer raise `ItemMovedToList`; no longer fold to removal here.**
  - [x] Reshape `postponeItemToList(itemId, targetListId, commandId)` identically but
        `requireInTrip()` and `origin = IN_TRIP_POSTPONE`. Add the same-target no-op + different-target
        lock.
  - [x] Add the **lock** to every other item-mutating method — `updateItem`, `removeItem`,
        `assignItemToStore`, `rerouteItem`, `checkOffItem`, `uncheckItem`, `discardItem`: after the
        item-exists check, if `pendingTransfer != null` throw `ItemTransferInProgressException`.
        (Keep each method's existing convergent-no-op checks; the lock comes first so a reserved item
        is never mutated.)
  - [x] Add `confirmItemTransfer(ItemId itemId, CommandId commandId)` — **not** phase-gated (it is a
        system saga step that must resolve regardless of the list's current `ListStatus`). If the item
        is absent (already removed on an earlier pass) → `return` (convergent no-op, replay-safe). If
        present and `pendingTransfer != null` → raise `ItemTransferConfirmed`. If present but not
        pending → `return` (defensive no-op).
  - [x] Add `cancelItemTransfer(ItemId itemId, TransferCancellationReason reason, CommandId commandId)`
        — also **not** phase-gated. If absent or not pending → `return` (convergent no-op). Else raise
        `ItemTransferCancelled(reason)`.
  - [x] `apply(...)` cases: `ItemTransferInitiated` → set `pendingTransfer` on the item (keep name/
        note/quantity/store/status); `ItemTransferConfirmed` → `itemsById.remove(itemId)`;
        `ItemTransferCancelled` → clear `pendingTransfer` (rebuild the ItemState with `pendingTransfer
        = null`). **Remove** the old `ItemMovedToList`/`ItemPostponedToList` remove-cases.
  - [x] `completeTrip` sweep: the still-`OPEN` filter must **skip reserved items**
        (`status == OPEN && pendingTransfer == null`) so a mid-flight postpone is not discarded — it
        stays pending and the saga resolves independently. **Edge to confirm in dev/review:** if a
        cancel lands after the source has since gone `DONE`, the item re-appears un-reserved as a
        preserved leftover on a Done list — accepted (data preservation > strict display-immutability;
        the window is sub-second and the trigger is a rare compensation). Document it in the completion
        notes.

- [x] **Task 3 — New exceptions (domain + application seam).** (AC: 4,6)
  - [x] `domain/exception/ItemTransferInProgressException.java` (mirror
        `ItemNotDuringTripException`).
  - [x] `application/exception/ItemTransferInProgressApplicationException.java` (mirror
        `ItemNotDuringTripApplicationException`) — translated at the handler seam so `adapter.in`
        never imports `..domain..` (CLAUDE.md §8; the `*ApplicationException` pattern).
  - [x] Map it to HTTP `409` with error code **`item.transferInProgress`** in the controller-advice
        (mirror `item.notDuringTrip`). Add it to the base error-advice contract test (Epic-1 Action 2 /
        Epic-2 Action, standing DoD).

- [x] **Task 4 — Rename + rewrite the process manager (`ItemTransferProcessManager`).** (AC: 2,3,4,6)
  - [x] Rename `application/ItemMoveProcessManager.java` → `ItemTransferProcessManager.java` (git-mv;
        update the class name, Javadoc, and log messages). Replace `onItemMovedToList` /
        `onItemPostponedToList` with a single `onItemTransferInitiated(ItemTransferInitiated initiated)`.
  - [x] Saga flow in `onItemTransferInitiated`:
        1. `CommandId derived = CommandId.deterministicFrom(initiated.eventId())`.
        2. Add to the **target** using the existing bounded load-retry loop (keep `MAX_APPEND_ATTEMPTS`):
           - target stream empty → **cancel** path, `TARGET_GONE`.
           - `target.addItem(...)` throws `ItemChangeNotPermittedException` (target not OPEN) →
             **cancel** path, `TARGET_NOT_OPEN`.
           - `target.addItem(...)` throws `DuplicateItemException` → treat as converged success →
             **confirm** path (the item is effectively already on the target).
           - append target add succeeds → **confirm** path.
           - `ConcurrencyConflictException` → retry (unchanged).
        3. **confirm**: rehydrate the **source**, call `source.confirmItemTransfer(itemId, derived)`,
           append under the loaded source version, bounded retry on `ConcurrencyConflictException`;
           convergent no-op if the source already removed the item (idempotent replay).
        4. **cancel**: rehydrate the source, call
           `source.cancelItemTransfer(itemId, reason, derived)`, append with the same bounded-retry +
           idempotency posture.
  - [x] **Command-id note (document in Javadoc):** the same `derived` id is reused for the target add
        (target stream) and the source confirm/cancel (source stream) — per-stream `(stream, commandId)`
        dedupe makes this safe and replay-idempotent for all normal cases; the only unhandled case is a
        target that flaps not-open→open across a replay (pathological; would double-place). Note it as
        an accepted edge alongside the existing checkpoint/at-least-once debts (`deferred-work.md`
        3.2) — do **not** build for it (YAGNI).
  - [x] **Delete** the `UNRECOVERABLE_TRANSFER` marker and the two log-and-drop branches (replaced by
        the cancel compensation).

- [x] **Task 5 — Subscription routing (`CollaborationProcessManagerSubscription`).** (AC: 2,3,6)
  - [x] In `react(...)`, route **only** `ItemTransferInitiated` → `itemTransferProcessManager
        .onItemTransferInitiated(...)`. Remove the `ItemMovedToList` / `ItemPostponedToList` branches.
        Keep the `TripStartedForList` / `TripCompletedForList` routing to the `TripLifecycleProcessManager`.
  - [x] Update the field/param/Javadoc names (`itemMoveProcessManager` → `itemTransferProcessManager`)
        and the resubscribe thread name string. Update the config bean wiring
        (`CollaborationApplicationConfig` / wherever the PM bean is defined) to the new type name.
  - [x] **Do not react to `ItemTransferConfirmed` / `ItemTransferCancelled`** — they are the saga's own
        output on `list-` streams; only the projector consumes them. (Verify no PM re-entry / loop.)

- [x] **Task 6 — Codec (`DomainEventJsonCodec`).** (AC: 6)
  - [x] Register `ItemTransferInitiated` / `ItemTransferConfirmed` / `ItemTransferCancelled` (new type
        constants + `toJsonBytes` cases + `fromJsonBytes` cases + payload records). Serialize
        `TransferOrigin` / `TransferCancellationReason` as their enum `name()` strings.
  - [x] **Remove** `ITEM_MOVED_TO_LIST_TYPE` / `ITEM_POSTPONED_TO_LIST_TYPE`, their `toJson`/`fromJson`
        cases, and the `ItemMovedToListPayload` / `ItemPostponedToListPayload` records. No old-type
        tolerance is required (nothing deployed — decision 1).
  - [x] Add codec round-trip tests for the three new events (mirror the existing per-event round-trip
        tests).

- [x] **Task 7 — Read model: `transfer_pending` (migration + port + projector).** (AC: 5)
  - [x] **V11** `src/main/resources/db/migration/V11__item_transfer_pending.sql`:
        `ALTER TABLE item_read_model ADD COLUMN transfer_pending BOOLEAN NOT NULL DEFAULT FALSE;`
        with a header comment (purpose: the reserved sub-state for the two-phase transfer saga, Story
        3.6; not personal data). **Do not** trip `NoPersistedPersonalDataTest` (no person columns).
  - [x] `domain/readmodel/ItemReadModel.java`: add `void setTransferPending(ItemId itemId, boolean
        pending)` (single writer for the flag). Add `transferPending` to the `ItemView` record (default
        `false` semantics via the projection).
  - [x] `adapter/out/JdbcItemReadModel.java`: implement `setTransferPending` (a one-column UPDATE) and
        add `transfer_pending` to the row mapper / `itemsOf` SELECT. Preserve the flag through
        `updateItem`/`assignStore`/`setStatus` (they only touch their own columns — verify none clobber
        it; a reserved item cannot receive those events anyway because of the aggregate lock, but keep
        the SQL column-scoped).
  - [x] Projector `project(...)`: `ItemTransferInitiated` → `itemReadModel.setTransferPending(itemId,
        true)` (**keep** the row — do not `removeItem`); `ItemTransferConfirmed` →
        `itemReadModel.removeItem(itemId)`; `ItemTransferCancelled` → `setTransferPending(itemId,
        false)`. **Remove** the old `ItemMovedToList` / `ItemPostponedToList` remove-cases. (The target
        add still arrives as `ItemAdded` on the target stream → existing case, unchanged.)

- [x] **Task 8 — Read queries thread the flag (`ListItems`, `TripView`).** (AC: 5)
  - [x] Thread `transferPending` from `ItemView` through the `ListItems` and `TripView` query result
        shapes and their controller DTOs → JSON (mirror how `status`/`storeId` are threaded). Keep the
        JSON key name aligned with the client parser (Task 10), e.g. `transferPending`.

- [x] **Task 9 — Handlers: convergent-no-op append + new 409 (`MoveItemHandler`,
      `PostponeItemToListHandler`).** (AC: 3,4)
  - [x] Keep the synchronous target-`OPEN` pre-check (`MoveTargetNotOpenException` → `409`) — it gives
        fast feedback for the common "target already not open at request time" case; the saga's cancel
        only covers the *post-check* race (AC3).
  - [x] **Skip the append when `source.uncommittedEvents()` is empty** (the convergent-no-op idiom —
        `RenameShoppingListHandler` is the template) so a same-target retry (AC4) returns `200` without
        recording a spurious command id.
  - [x] Catch the new `ItemTransferInProgressException` → `ItemTransferInProgressApplicationException`
        (`409`). Keep the existing `ItemNotFoundException` (`404`) / `ItemChangeNotPermittedException`
        (`403`, move) / `ItemNotDuringTripException` (`409`, postpone) translations.

- [x] **Task 10 — Client: pending affordance, no optimistic drop (Flutter).** (AC: 5)
  - [x] `app/lib/features/lists/data/item.dart`: add `bool transferPending` (default `false`),
        parse from JSON (fail-fast on a non-bool non-null value, matching the existing `status`
        parsing posture); include it in `copyWith`/equality if present.
  - [x] `TripCubit.postponeToList` (`app/lib/features/trips/presentation/trip_cubit.dart:229`):
        **stop removing the item optimistically.** Instead optimistically set that item's
        `transferPending = true` (keep it in the list). On error, revert the flag and surface the
        error (including the new `item.transferInProgress` 409). On `200`, keep it pending — the next
        `bootstrap`/refresh reconciles (confirm → gone, cancel → normal again).
  - [x] `ListDetailCubit.moveItem` (`.../list_detail/list_detail_cubit.dart:346`): same change —
        optimistic pending marker, not removal. **Preserve the 2.4 client-orchestrated quantity-merge
        flow** (`move_merge_dialog.dart` + `mergeRemove`) exactly — that path is a client add/update +
        remove for the same-key case and does **not** go through the saga; read it before touching
        `moveItem` and keep it working (AC6 regression).
  - [x] Trip screen (`trip_screen.dart` / `trip_item_actions_sheet.dart`) and list-detail row
        (`list_detail/list_detail_page.dart`): render a reserved item with a quiet „wird verschoben…"
        label/chip and make the row **non-interactive** (hide/disable its per-item actions — mirrors the
        server lock so the member can't fire a command that would 409).
  - [x] `error_message_resolver.dart`: map `item.transferInProgress` →
        `localizations.itemTransferInProgressError`.

- [x] **Task 11 — Localization (AC5).** (AC: 5)
  - [x] Add to `lib/l10n/app_de.arb` (German-only) with `@`-descriptions: `itemTransferPendingLabel`
        („wird verschoben…") and `itemTransferInProgressError` (a short „Dieser Artikel wird gerade
        verschoben…"-style message). Re-run gen-l10n (via build; `generate: true`).

- [x] **Task 12 — Tests (CLAUDE.md §6; test pyramid).** (AC: all)
  - [x] **Aggregate unit** (`ShoppingListTest` / a focused test): move & postpone each raise
        `ItemTransferInitiated` with the right `origin` and **keep** the item reserved (no removal);
        `confirmItemTransfer` removes; `cancelItemTransfer` un-reserves; the **lock** rejects each of
        edit/remove/assign/reroute/check-off/uncheck/discard/second-different-transfer on a reserved
        item with `ItemTransferInProgressException`; **same-target retry is a convergent no-op** (no
        event); confirm/cancel are **un-gated** (work when the list is `IN_TRIP`/`DONE`); confirm &
        cancel are convergent no-ops when the item is already gone / already un-reserved (replay);
        `completeTrip` sweep **skips** a reserved item.
  - [x] **PM unit** (`ItemTransferProcessManagerTest`, `InMemoryEventStore`): initiate → target add
        success → `ItemTransferConfirmed` on source; target not OPEN → `ItemTransferCancelled`
        (`TARGET_NOT_OPEN`); target stream gone → cancel (`TARGET_GONE`); duplicate on target →
        confirm (converged); target concurrency conflict → retries then confirms; **exactly-once on
        replay** (re-process the same initiate → derived ids dedupe, no double add / double
        confirm); confirm idempotent when the source item is already removed.
  - [x] **Handler + controller** (`MoveItemHandlerTest`, `PostponeItemToListHandlerTest`,
        `ItemControllerTest`): initiate reserves (asserts the emitted `ItemTransferInitiated`); the new
        `409 item.transferInProgress` on a locked item; **lost-response idempotency** — same
        `commandId` reused after a successful initiate returns success and does not re-reserve / does
        not `404`; the synchronous `MoveTargetNotOpenException` pre-check still fires; error-advice
        contract covers the new code.
  - [x] **Projector Testcontainers** (extend `ShoppingListReadModelProjector` test): `ItemTransferInitiated`
        sets `transfer_pending` and **keeps** the row; `ItemTransferConfirmed` removes it;
        `ItemTransferCancelled` clears the flag; two-household isolation; replay idempotency of the
        three events.
  - [x] **Codec**: round-trip for the three new events (Task 6).
  - [x] **ArchUnit**: unchanged packages → stays green (confirm; `..domain..`/`..application..`
        matchers already cover the new files).
  - [x] **Flutter**: `TripCubit.postponeToList` sets pending (does **not** remove) on success and
        reverts on error; `ListDetailCubit.moveItem` same; the 2.4 **merge** dialog flow still works
        (regression); a pending item renders „wird verschoben…" and is non-interactive; the
        `item.transferInProgress` message resolves; `Item.fromJson` parses `transferPending`.

- [x] **Task 13 — Green build (CLAUDE.md §6).** (AC: 6)
  - [x] Run **both** suites and report counts explicitly per suite: backend `./gradlew test` (incl.
        ArchUnit + both Testcontainers subscriptions) **and** `flutter test` + `flutter analyze` (0
        issues). A green build here = both suites ran. Note the baseline (backend ≈577 / Flutter 509).

## Dev Notes

### The saga at a glance (source list = S, target list = T)

```
member move/postpone
        │
        ▼
S.moveItem / S.postponeItemToList  ──►  ItemTransferInitiated (on S)   [item RESERVED, stays on S]
        │  handler returns 200                     │
        │                          ItemTransferProcessManager reacts
        │                                          ▼
        │                                  T.addItem(...) append
        │                            ┌─────────────┴─────────────┐
        │                       success/duplicate           not-OPEN / no stream
        │                            ▼                             ▼
        │              S.confirmItemTransfer(derived)   S.cancelItemTransfer(derived, reason)
        │                            ▼                             ▼
        │              ItemTransferConfirmed (on S)     ItemTransferCancelled (on S)
        │              [item REMOVED from S]            [item UN-RESERVED on S]
        ▼
  client shows „wird verschoben…" until the next fetch reconciles
```

Invariant this buys us (AC3): the item is on **exactly one** list at every instant — reserved-on-S,
then either removed-from-S-added-to-T (confirm) or un-reserved-on-S (cancel). Never on neither.

### Why unify the vocabulary (decision 1)
`ItemMovedToList` and `ItemPostponedToList` already carry identical payloads, fold identically (to a
removal), and are handled identically by the PM — the *only* real difference is the write-side phase
gate (`requireOpen` vs `requireInTrip`), which lives in the two aggregate **methods**, not the event.
The reserve/confirm/cancel lifecycle is likewise identical for both phases. So the ubiquitous-language
concept is one: **transferring an item to another list**. One `ItemTransfer*` triad, one PM path, one
read-model marker (DRY/KISS). `TransferOrigin` preserves the move-vs-postpone distinction for
audit/telemetry without duplicating the machinery.

### Existing pieces to reuse (DRY — do not reinvent)
- **PM load-retry loop + derived-command-id exactly-once:** `ItemMoveProcessManager` already has the
  bounded `MAX_APPEND_ATTEMPTS` loop, the `DuplicateItemException`-as-converged handling, and
  `CommandId.deterministicFrom(event.eventId())`. Keep all of it; add the confirm/cancel source-side
  appends using the **same** loop shape (`TripLifecycleProcessManager.onTripCompletedForList` is the
  template for a read-rehydrate-append-retry against a second stream).
- **Convergent-no-op + skip-empty-append:** `ShoppingList.rename` / `RenameShoppingListHandler` are the
  templates for "raise nothing → skip the append" (this is what makes AC4's same-target retry return
  200, not 404).
- **Preserve-through-fold:** `assignStore` / `setStatus` already rebuild `ItemState` preserving the
  other fields (Cl.4/7) — extend the same discipline to `pendingTransfer`.
- **Application-exception seam:** `ItemNotDuringTripException` → `ItemNotDuringTripApplicationException`
  → `item.notDuringTrip` (409) is the exact template for the new `ItemTransferInProgress*` → `409`.
- **Codec per-event round-trip tests** and the projector's **Testcontainers isolation + replay**
  tests already exist for the item events — copy their shape.
- **Client optimistic-with-revert + `CommandIntent`:** `TripCubit`/`ListDetailCubit` already use a
  per-intent `CommandIntent` (spent-id/re-entrancy guard) and optimistic-then-revert; only the
  *optimistic effect* changes (mark pending, not remove).

### Files being touched

**Backend — NEW**
- `domain/event/ItemTransferInitiated.java`, `ItemTransferConfirmed.java`, `ItemTransferCancelled.java`
- `domain/TransferOrigin.java`, `domain/TransferCancellationReason.java`
- `domain/exception/ItemTransferInProgressException.java`
- `application/exception/ItemTransferInProgressApplicationException.java`
- `src/main/resources/db/migration/V11__item_transfer_pending.sql`
- Test files per Task 12.

**Backend — UPDATE**
- `domain/ShoppingList.java` — `ItemState` + `PendingTransfer`; reshape `moveItem`/`postponeItemToList`;
  add `confirmItemTransfer`/`cancelItemTransfer`; add the lock to the mutating methods; `completeTrip`
  sweep filter; `apply(...)` cases.
- `application/ItemMoveProcessManager.java` → **git-mv** to `ItemTransferProcessManager.java` (rewrite).
- `adapter/out/CollaborationProcessManagerSubscription.java` — routing + names + bean wiring.
- `adapter/out/DomainEventJsonCodec.java` — register 3 new, remove 2 old.
- `adapter/out/ShoppingListReadModelProjector.java` — 3 new cases, remove 2 old.
- `domain/readmodel/ItemReadModel.java` + `ItemView.java` — `setTransferPending` + `transferPending`.
- `adapter/out/JdbcItemReadModel.java` — column + writer + row map.
- `application/query/ListItems.java` + `application/query/TripView.java` (+ their controller DTOs) —
  thread `transferPending`.
- `application/command/MoveItemHandler.java`, `PostponeItemToListHandler.java` — skip-empty append,
  new 409 translation.
- The controller-advice (error mapping) + base error-advice contract test.
- The PM bean config (`CollaborationApplicationConfig` or equivalent).

**Flutter — UPDATE**
- `features/lists/data/item.dart` — `transferPending`.
- `features/trips/presentation/trip_cubit.dart` — pending marker, no drop.
- `features/lists/presentation/list_detail/list_detail_cubit.dart` — pending marker, no drop; preserve
  merge flow.
- `features/trips/presentation/{trip_screen,trip_item_actions_sheet}.dart`,
  `features/lists/presentation/list_detail/list_detail_page.dart` — „wird verschoben…" + non-interactive.
- `shared/errors/error_message_resolver.dart`, `l10n/app_de.arb` — new code + strings.

### Regression traps (make the reviewer's job easy)
- **2.4 merge dialog** must still work — it is a *client-orchestrated* same-key merge, not a saga path.
- **`completeTrip` sweep** must not discard a reserved item (Task 2) — add a test.
- **Projector must KEEP the row on initiate** (the classic mistake: leaving the old `removeItem`).
- **Subscription must not react to Confirmed/Cancelled** (no PM loop).
- **Codec: no dangling refs** to the deleted events/payloads (compile guard + round-trip tests).
- **The synchronous target-OPEN pre-check stays** — the saga cancel is only for the *post-check* race.

### Standing DoD (applies here — this story has a full write+read+client slice)
Command endpoints exist → base error-advice contract test covers the new 409 (Epic-1 Action 2). New
read-model column → isolation + replay test in the same change (Epic-2 Action). `commandId` lifecycle
+ `isSubmitting`/re-entrancy guard on the (changed) client intents (Epic-1/2 Actions). a11y labels on
the new pending affordance; no dead strings/fields/stale comments (delete the old event refs, the
`UNRECOVERABLE_TRANSFER` marker, and any now-unused ARB/error strings); client fail-fast guards. New
PM concurrency-retry re-derives the same command id (Epic-2 Action) — inherited from the existing loop.

### Project Structure Notes
- No package moves beyond the PM git-mv (same `application` package). The hexagonal layering is
  unchanged (`adapter.in → application → domain`; `adapter.out` implements ports), so
  `HexagonalArchitectureTest` needs no changes. New events/enums/exceptions sit in the established
  `domain` / `domain.event` / `domain.exception` / `application.exception` sub-packages (§8).

### References
- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-08-29.md#4.2 D2 — reserve-then-remove] — the saga spec this story realizes.
- [Source: _bmad-output/implementation-artifacts/deferred-work.md] — "Client-side false-success on postpone-to-list transfer drop → story 3-6" (2026-08-30) and "postpone-to-list not idempotent across a lost response" (2026-08-29).
- [Source: _bmad-output/implementation-artifacts/sprint-status.yaml] — the `3-6-two-phase-transfer-saga` carve-out note (2026-08-29, D2).
- [Source: _bmad-output/planning-artifacts/epics.md#Story 3.3 / Story 3.4] — the move/postpone/leftover AC context (AD-8/AD-10 process-manager seam).
- [Source: backend/.../application/ItemMoveProcessManager.java] — current single-phase PM + the interim `UNRECOVERABLE_TRANSFER` guard to remove.
- [Source: backend/.../domain/ShoppingList.java] — `moveItem`/`postponeItemToList`/`completeTrip`, `ItemState`, the preserve-through-fold pattern, `requireOpen`/`requireInTrip`.
- [Source: backend/.../application/TripLifecycleProcessManager.java] — the read-rehydrate-append-retry-against-a-second-stream template for confirm/cancel.
- [Source: backend/.../application/command/RenameShoppingListHandler.java] — the skip-empty-append convergent-no-op template (AC4 idempotency).
- [Source: backend/.../adapter/out/{DomainEventJsonCodec,ShoppingListReadModelProjector,CollaborationProcessManagerSubscription}.java] — codec/projector/subscription seams.
- [Source: backend/.../domain/readmodel/{ItemReadModel,ItemView}.java, adapter/out/JdbcItemReadModel.java, resources/db/migration/V10__item_status.sql] — read-model column pattern for V11.
- [Source: app/lib/features/trips/presentation/trip_cubit.dart:229; app/lib/features/lists/presentation/list_detail/list_detail_cubit.dart:346] — the two optimistic paths to change.
- [Source: app/lib/features/lists/presentation/list_detail/move_merge_dialog.dart] — the 2.4 merge flow to preserve.

## Dev Agent Record

### Agent Model Used

Planning: Claude Opus 4.8. Implementation + tests + review-fix pass: Claude Sonnet 5.

### Debug Log References

- A projector correctness bug surfaced by the new Testcontainers coverage (not by pre-existing
  tests): `item_read_model.item_id` is the primary key, so the target's `ItemAdded` for a
  still-reserved item's `item_id` (same id, source row still present) collided with `ON CONFLICT
  (item_id) DO NOTHING` and silently dropped the target insert; the source's later
  `ItemTransferConfirmed` then deleted the only row by bare `item_id`, losing the item entirely.
  Fixed by (a) changing `JdbcItemReadModel.insertItem`'s conflict clause to `DO UPDATE ... WHERE
  item_read_model.list_id <> EXCLUDED.list_id` — relocating the row to the target (fresh
  `sequence_number` via `nextval(pg_get_serial_sequence(...))` so it appends at the target's tail,
  clearing `transfer_pending`) only when the incoming `ItemAdded` names a *different* list than the
  row's current one, leaving a genuine same-list replay an exact no-op; (b) scoping
  `JdbcItemReadModel.removeItem` to `(item_id, list_id)` instead of bare `item_id`, so
  `ItemTransferConfirmed` naming the item's old (source) `list_id` can never delete a row that has
  since relocated to the target. Caught by
  `ShoppingListReadModelProjectorTest#aFullTransferLeavesExactlyOneRowUnderTheTargetListWithTheSameItemId`.
- `MoveItemHandlerTest` imported but never exercised `MoveTargetNotOpenException` (a pre-existing gap
  predating this story, surfaced while verifying handler test coverage) — closed with
  `movingToATargetThatIsNotOpenIsRejectedWith409`, mirroring the sibling coverage already present in
  `PostponeItemToListHandlerTest`; added the MockMvc-level equivalent
  (`ItemControllerTest#move_returns409WhenTargetIsNotOpen`) too, since Epic 3's `startTrip` now makes
  it reachable end-to-end (the class-level Javadoc previously said this branch was unreachable until
  Epic 3 — no longer true).

### Completion Notes List

- **Domain (Task 1–3):** unified `ItemTransferInitiated`/`ItemTransferConfirmed`/
  `ItemTransferCancelled` (+ `TransferOrigin`, `TransferCancellationReason`,
  `ItemTransferInProgressException`/`ItemTransferInProgressApplicationException`) retire
  `ItemMovedToList`/`ItemPostponedToList`. `ShoppingList.moveItem`/`postponeItemToList` now reserve
  (raise `ItemTransferInitiated`, item stays) instead of removing; a same-target retry is a
  convergent no-op, a different-target retry throws the new lock exception. `confirmItemTransfer`/
  `cancelItemTransfer` are new, phase-ungated saga steps. The lock (`requireNotTransferPending`) guards
  every other item mutator (`updateItem`, `removeItem`, `assignItemToStore`, `rerouteItem`,
  `checkOffItem`, `uncheckItem`, `discardItem`). `completeTrip`'s sweep skips a reserved item.
- **Process manager (Task 4–5):** `ItemMoveProcessManager` → `ItemTransferProcessManager`
  (git-mv + rewrite): one `onItemTransferInitiated` drives target-add → confirm (success/converged
  duplicate) or cancel (`TARGET_NOT_OPEN`/`TARGET_GONE`) on the source, reusing one derived
  `CommandId` across every append it makes. The interim `UNRECOVERABLE_TRANSFER` log-and-drop
  branches are gone. `CollaborationProcessManagerSubscription` routes only
  `ItemTransferInitiated`; it does not react to `Confirmed`/`Cancelled` (verified no PM re-entry).
- **Codec/read model (Task 6–8):** codec registers the 3 new events (enum fields serialized as
  `name()`), removes the 2 old ones. V11 adds `item_read_model.transfer_pending`
  (`ItemReadModel.setTransferPending`, `ItemView.transferPending`). Projector: initiate sets the
  flag and **keeps** the row; confirm removes it; cancel clears the flag — see the Debug Log entry
  above for the relocation/scoped-delete fix this required. `ListItems`/`TripView`/
  `ItemController.ItemResponse`/`TripController` thread `transferPending` through to the client.
- **Handlers (Task 9):** `MoveItemHandler`/`PostponeItemToListHandler` keep the synchronous
  target-OPEN pre-check, skip the append when nothing was raised (closes the lost-response 404
  defect — a same-target retry now returns success), and translate the new lock exception to 409.
  The other 7 item-mutating handlers (`UpdateItem`, `RemoveItem`, `AssignItemToStore`,
  `RerouteItem`, `CheckOffItem`, `UncheckItem`, `DiscardItem`) each gained the same 409 translation.
- **Client (Task 10–11):** `Item.transferPending` (parsed, fail-fast on a non-bool). `TripCubit
  .postponeToList`/`ListDetailCubit.moveItem` optimistically mark `transferPending = true` instead
  of removing the row, reverting the flag (not re-adding a row) on failure. The trip row
  (`trip_screen.dart`) and list-detail row (`list_detail_page.dart`) render a pending item with a
  „wird verschoben…" label/subtitle, strike-through title, disabled checkbox/store-chip, and no
  trailing actions; `TripState.openItems` excludes a pending item from the completion sheet's
  leftover list (its Transfer/Verwerfen actions would otherwise 409). The 2.4 merge flow
  (`mergeIntoTarget`/`move_merge_dialog.dart`) is untouched — it never goes through the saga. Two new
  `app_de.arb` strings (`itemTransferPendingLabel`, `itemTransferInProgressError`) + `gen-l10n` rerun.
- **Tests (Task 12):** ~78 new/rewritten backend tests across aggregate unit
  (`ShoppingListItemsTest`/`ShoppingListTest`: reserve/confirm/cancel, the fail-fast lock on every
  mutator, same-target no-op, phase-ungated confirm, `completeTrip` sweep skipping a reserved item),
  PM unit (`ItemTransferProcessManagerTest`, renamed from `ItemMoveProcessManagerTest`: confirm/
  cancel-with-reason/converged-duplicate/concurrency-retry/exactly-once-on-replay), handler +
  controller (`MoveItemHandlerTest`/`PostponeItemToListHandlerTest`/`ItemControllerTest`: reserve
  asserted, the new 409, lost-response idempotency, the target-OPEN pre-check), the other 7
  item-mutating handler tests (one new 409-on-reserved-item case each), projector Testcontainers
  (initiate-keeps-row/confirm-removes/cancel-clears/cross-household isolation/replay idempotency/the
  full-transfer relocation case), and codec round-trips for the 3 new events. Flutter: `Item`
  parsing, `error_message_resolver`, `TripCubit.postponeToList`, `ListDetailCubit.moveItem`, and the
  `move_target_sheet` widget test updated for the pending-not-removed behavior.
- **Edge accepted per Dev Notes:** a cancel landing after the source has since gone `DONE`
  re-appears un-reserved as a preserved leftover on a Done list — not separately tested beyond the
  domain-level `confirmingATransferIsNotPhaseGatedAndWorksOnAnInTripList`-style coverage; accepted
  per the story's own call (data preservation over strict display-immutability).
- **Green build:** backend `./gradlew test` — **655 tests, 0 failures** (baseline 577 + regression
  fix; includes `HexagonalArchitectureTest` and both Testcontainers projector/PM-adjacent suites).
  App `flutter test` — **513 tests, 0 failures** (baseline 509); `flutter analyze` — **0 issues**.

### File List

**Backend — new**
- `src/main/java/de/sgart/collaboration/domain/event/ItemTransferInitiated.java`
- `src/main/java/de/sgart/collaboration/domain/event/ItemTransferConfirmed.java`
- `src/main/java/de/sgart/collaboration/domain/event/ItemTransferCancelled.java`
- `src/main/java/de/sgart/collaboration/domain/TransferOrigin.java`
- `src/main/java/de/sgart/collaboration/domain/TransferCancellationReason.java`
- `src/main/java/de/sgart/collaboration/domain/exception/ItemTransferInProgressException.java`
- `src/main/java/de/sgart/collaboration/application/exception/ItemTransferInProgressApplicationException.java`
- `src/main/resources/db/migration/V11__item_transfer_pending.sql`

**Backend — renamed**
- `src/main/java/de/sgart/collaboration/application/ItemMoveProcessManager.java` →
  `ItemTransferProcessManager.java` (git-mv + rewrite)
- `src/test/java/de/sgart/collaboration/application/ItemMoveProcessManagerTest.java` →
  `ItemTransferProcessManagerTest.java` (git-mv + rewrite)

**Backend — deleted**
- `src/main/java/de/sgart/collaboration/domain/event/ItemMovedToList.java`
- `src/main/java/de/sgart/collaboration/domain/event/ItemPostponedToList.java`

**Backend — updated**
- `src/main/java/de/sgart/collaboration/domain/ShoppingList.java`
- `src/main/java/de/sgart/collaboration/domain/event/ItemDiscarded.java` (Javadoc `{@link}` fix)
- `src/main/java/de/sgart/collaboration/domain/event/TripStartedForList.java` (Javadoc `{@link}` fix)
- `src/main/java/de/sgart/collaboration/domain/readmodel/ItemReadModel.java`
- `src/main/java/de/sgart/collaboration/domain/readmodel/ItemView.java`
- `src/main/java/de/sgart/collaboration/domain/readmodel/ItemSuggestionReadModel.java` (Javadoc)
- `src/main/java/de/sgart/collaboration/application/TripLifecycleProcessManager.java` (Javadoc `{@link}` fix)
- `src/main/java/de/sgart/collaboration/application/command/MoveItemHandler.java`
- `src/main/java/de/sgart/collaboration/application/command/PostponeItemToListHandler.java`
- `src/main/java/de/sgart/collaboration/application/command/UpdateItemHandler.java`
- `src/main/java/de/sgart/collaboration/application/command/RemoveItemHandler.java`
- `src/main/java/de/sgart/collaboration/application/command/AssignItemToStoreHandler.java`
- `src/main/java/de/sgart/collaboration/application/command/RerouteItemHandler.java`
- `src/main/java/de/sgart/collaboration/application/command/CheckOffItemHandler.java`
- `src/main/java/de/sgart/collaboration/application/command/UncheckItemHandler.java`
- `src/main/java/de/sgart/collaboration/application/command/DiscardItemHandler.java`
- `src/main/java/de/sgart/collaboration/application/query/ListItems.java`
- `src/main/java/de/sgart/collaboration/application/query/TripView.java`
- `src/main/java/de/sgart/collaboration/adapter/in/ItemController.java`
- `src/main/java/de/sgart/collaboration/adapter/in/TripController.java`
- `src/main/java/de/sgart/collaboration/adapter/in/WriteErrorAdvice.java`
- `src/main/java/de/sgart/collaboration/adapter/out/CollaborationApplicationConfig.java`
- `src/main/java/de/sgart/collaboration/adapter/out/CollaborationProcessManagerConfig.java`
- `src/main/java/de/sgart/collaboration/adapter/out/CollaborationProcessManagerSubscription.java`
- `src/main/java/de/sgart/collaboration/adapter/out/DomainEventJsonCodec.java`
- `src/main/java/de/sgart/collaboration/adapter/out/JdbcItemReadModel.java`
- `src/main/java/de/sgart/collaboration/adapter/out/JdbcItemSuggestionReadModel.java` (Javadoc)
- `src/main/java/de/sgart/collaboration/adapter/out/ShoppingListReadModelProjector.java`

**Backend — test files updated**
- `src/test/java/de/sgart/collaboration/domain/ShoppingListItemsTest.java`
- `src/test/java/de/sgart/collaboration/domain/ShoppingListTest.java`
- `src/test/java/de/sgart/collaboration/application/MoveItemHandlerTest.java`
- `src/test/java/de/sgart/collaboration/application/PostponeItemToListHandlerTest.java`
- `src/test/java/de/sgart/collaboration/application/UpdateItemHandlerTest.java`
- `src/test/java/de/sgart/collaboration/application/RemoveItemHandlerTest.java`
- `src/test/java/de/sgart/collaboration/application/AssignItemToStoreHandlerTest.java`
- `src/test/java/de/sgart/collaboration/application/RerouteItemHandlerTest.java`
- `src/test/java/de/sgart/collaboration/application/CheckOffItemHandlerTest.java`
- `src/test/java/de/sgart/collaboration/application/UncheckItemHandlerTest.java`
- `src/test/java/de/sgart/collaboration/application/DiscardItemHandlerTest.java`
- `src/test/java/de/sgart/collaboration/application/ListItemsTest.java`
- `src/test/java/de/sgart/collaboration/application/TripViewTest.java`
- `src/test/java/de/sgart/collaboration/adapter/in/ItemControllerTest.java`
- `src/test/java/de/sgart/collaboration/adapter/in/TripControllerTest.java`
- `src/test/java/de/sgart/collaboration/adapter/in/WriteErrorAdviceTest.java`
- `src/test/java/de/sgart/collaboration/adapter/out/DomainEventJsonCodecTest.java`
- `src/test/java/de/sgart/collaboration/adapter/out/ShoppingListReadModelProjectorTest.java`

**App — updated**
- `app/lib/features/lists/data/item.dart`
- `app/lib/features/lists/presentation/list_detail/list_detail_cubit.dart`
- `app/lib/features/lists/presentation/list_detail/list_detail_page.dart`
- `app/lib/features/trips/presentation/trip_cubit.dart`
- `app/lib/features/trips/presentation/trip_screen.dart`
- `app/lib/features/trips/presentation/trip_state.dart`
- `app/lib/shared/errors/error_message_resolver.dart`
- `app/lib/l10n/app_de.arb` (+ regenerated `app/lib/l10n/gen/*.dart` via `flutter gen-l10n`)

**App — test files updated**
- `app/test/features/lists/data/item_test.dart`
- `app/test/features/lists/presentation/list_detail/list_detail_cubit_test.dart`
- `app/test/features/lists/presentation/list_detail/move_target_sheet_test.dart`
- `app/test/features/trips/presentation/trip_cubit_test.dart`
- `app/test/shared/errors/error_message_resolver_test.dart`

**Docs — updated**
- `_bmad-output/implementation-artifacts/deferred-work.md` (closed 2 defers this story resolves;
  refreshed class-name references in a 3rd, still-open defer)

## Change Log

- 2026-09-05: Story drafted (create-story, Opus 4.8) — Epic 3's carved-out sixth story (from the 3.3
  code review D2 + sprint-change-proposal §4.2): **reshape the cross-aggregate item transfer into a
  two-phase reserve-then-remove compensating saga** so a move/postpone can never silently drop an item
  when the target leaves OPEN under it. **Timo decided (2026-09-05):** (1) **unify** move + postpone
  into one `ItemTransferInitiated`/`Confirmed`/`Cancelled` saga vocabulary (retire `ItemMovedToList` +
  `ItemPostponedToList`; `TransferOrigin` keeps the phase distinction; free reshape, nothing deployed);
  (2) **fail-fast lock** on a reserved item (`409 item.transferInProgress`), with a same-target retry
  as a convergent no-op; (3) client shows „wird verschoben…" and **stops dropping** the item on
  initiate, reconciling on refresh (no polling — live-sync is Epic 4); (4) also close the
  **lost-response 404 idempotency** defer and **remove the interim `UNRECOVERABLE_TRANSFER`** guard.
  `ItemMoveProcessManager` → `ItemTransferProcessManager` (adds confirm/cancel on the source stream);
  V11 adds `item_read_model.transfer_pending`. Baseline last-green ≈ backend 577 / Flutter 509
  (c64de8a).
- 2026-09-05: Story implemented (dev-story, Sonnet 5) — all 13 tasks complete. Domain/application/
  read-model/handler reshape into the `ItemTransferInitiated`/`Confirmed`/`Cancelled` saga per plan;
  client marks pending instead of dropping. Fixed a read-model relocation bug the new projector
  Testcontainers coverage caught (same `item_id` briefly exists reserved-on-source and
  freshly-added-on-target; `insertItem`'s conflict handling now relocates + renumbers instead of
  silently no-opping, and `removeItem` is list-scoped so a stale confirm can never delete the
  relocated row) — see Dev Agent Record → Debug Log. Also closed a pre-existing gap in
  `MoveItemHandlerTest` (imported but never asserted `MoveTargetNotOpenException`) and added the
  now-reachable MockMvc equivalent. Closed 2 `deferred-work.md` entries this story was built to
  resolve (lost-response 404 idempotency; client-side false-success on transfer drop). Green build:
  backend 655 tests / 0 failures (incl. ArchUnit + Testcontainers); Flutter 513 tests / 0 failures,
  `flutter analyze` 0 issues. Status → review.

### Review Findings

Code review 2026-09-06 (bmad-code-review, Opus 4.8 — Blind Hunter + Edge Case Hunter + Acceptance
Auditor). Result: 1 patch, 3 deferred, 5 dismissed as noise. The Acceptance Auditor found **no**
acceptance-criteria violations and no spec contradictions — all 6 ACs, all 4 locked decisions, and
all 13 tasks are substantively implemented and tested.

- [x] [Review][Patch] (fixed 2026-09-06) Read-model relocation keeps stale `store_id` and `status` — `insertItem`'s
  `ON CONFLICT (item_id) DO UPDATE` (the Story 3.6 cross-list relocation) copies name/note/quantity
  and clears `transfer_pending` but does **not** reset `store_id` or `status`. The target aggregate's
  `ItemAdded` fold sets `assignedStore=null, status=OPEN` (`ShoppingList.java:641`), so a moved
  store-assigned item — or a non-OPEN item postponed to a list (`postponeItemToList` is not
  status-gated) — lands on the target read-model row carrying the source's stale store chip / status,
  diverging from the write model and regressing the pre-3.6 delete-then-reinsert behavior. Every
  projector test uses an unassigned OPEN item, so the gap is untested. Fix: add `store_id =
  EXCLUDED.store_id, status = EXCLUDED.status` to the `DO UPDATE SET` (EXCLUDED supplies NULL/OPEN via
  the column defaults) and add a relocation regression test asserting the target row is reset.
  [backend/src/main/java/de/sgart/collaboration/adapter/out/JdbcItemReadModel.java:116]
- [x] [Review][Defer] Retry-exhaustion strands a reserved item until the next restart —
  `onItemTransferInitiated` throws `IllegalStateException` after `MAX_APPEND_ATTEMPTS` target-add
  conflicts (caught + logged-and-skipped by the subscription), and `appendSourceSagaStep` logs and
  returns after exhausting the source confirm/cancel retries. The subscription only catch-up-replays
  on drop/restart (`onCancelled → scheduleResubscribe`), not spontaneously, so an exhausted append
  leaves the item reserved-and-fail-fast-locked (every mutation 409) until the next restart — the
  class doc slightly overstates the auto-recovery. Deferred: 5 consecutive same-stream conflicts in a
  household grocery app is vanishingly unlikely, and this mirrors the accepted
  `TripLifecycleProcessManager` pattern (documented YAGNI, `deferred-work.md` 3.2).
  [backend/src/main/java/de/sgart/collaboration/application/ItemTransferProcessManager.java:129,192] — deferred, accepted pattern
- [x] [Review][Defer] Post-confirm lost-response retry returns 404, not a convergent success — once
  the sub-second saga confirms (source row removed), a client retry of the same move/postpone gets
  404 `ItemNotFound`; the cubit reverts the pending flag (row becomes interactive again) and surfaces
  a spurious `actionError`, self-healing on the next refresh (server truth: item gone from source, on
  target). AC4 idempotency is closed only during the reservation window; fully closing the
  post-completion window needs tombstones (out of scope).
  [app/lib/features/lists/presentation/list_detail/list_detail_cubit.dart:370] — deferred, self-healing
- [x] [Review][Defer] Same-target retry after the target leaves OPEN 409s instead of converging — the
  handler's `target.status() != OPEN` pre-check runs before the aggregate's same-target convergent
  no-op, so a lost-response retry of an already-reserved same-target move, after a concurrent
  `StartTrip` flipped the target out of OPEN, returns 409 `list.moveTargetNotOpen` rather than the
  intended 204. The eventual state is consistent (that transfer is being cancelled anyway), so the
  client's revert-to-interactive matches the server.
  [backend/src/main/java/de/sgart/collaboration/application/command/MoveItemHandler.java:88] — deferred, consistent eventual outcome

Dismissed (5): removed codec decode arms for the retired `ItemMovedToList`/`ItemPostponedToList`
(explicit locked decision 1 — pre-beta, event store reset, no codec version tolerance); the
converged-duplicate path leaving the moved `itemId` on neither list (documented rare-race safety net;
content survives as the pre-existing target item, interactive merge is the real path); double-place
on a target that flaps not-open→open across a replay (documented YAGNI, `deferred-work.md` 3.2);
`trip_item_actions_sheet.dart` named in Task 10 but unmodified (functionally correct — a pending
item's ⋯ trailing is unreachable); stale `ItemMovedToList` comment in the shipped `V7` migration
(correct to leave — shipped migrations are immutable).
