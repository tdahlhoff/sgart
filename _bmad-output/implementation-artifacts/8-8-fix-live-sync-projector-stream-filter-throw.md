---
title: 'Story 8.8: Prove the list/trip projectors'' live subscriptions deliver events'
type: 'chore'
created: '2026-09-28'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
context: []
baseline_commit: 'adcb2b42b34a65226d9d1363d2b599806dd03821'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The stream-filter throw this story was written for is already fixed. Commit `b4218f7` (2026-09-12) replaced the chained `addStreamNamePrefix` calls with a single `withStreamNameRegularExpression` in both `ShoppingListReadModelProjector` and `ShoppingTripReadModelProjector`, and `MultiPrefixKurrentDbSubscriptionRegressionTest` proves `start()` no longer throws. That test only proves the subscription *opens*, though. A wrong regex (a typo in a prefix, a missing `household` branch) would still open fine and silently drop events, and nothing would catch it: the projector tests drive `project(...)` directly.

**Approach:** Extend the existing regression test so both projectors, subscribed live against a real KurrentDB, demonstrably receive an event from **each** of their two stream prefixes. The proof runs through the real read model on a PostgreSQL Testcontainer, following `HouseholdLiveSyncFanoutIntegrationTest`'s harness.

**Decisions (Timo, 2026-09-28):**
- **D1 Scope:** ACs 1 (no throw) and 2 (real subscription test) are already met by `b4218f7`. This story adds only the event-delivery proof. No production code changes unless the new test exposes a real defect, in which case fix it (the regression test comes first).

## Boundaries & Constraints

**Always:** Append events through the real `KurrentDbEventStore`, never by calling `project(...)`, so the whole path (subscription filter → decode → `project`) is exercised. Wait for asynchronous delivery with a bounded poll (mirror the fanout test's await helpers), never a fixed sleep. Each test uses fresh random ids, so tests are independent of `fromStart` replay of other tests' events. Only synthetic data.

**Never:** No change to the filter, `project(...)`, or the subscription options while the test passes. No new test dependency (e.g. Awaitility) if the existing await pattern suffices. No separate Spring context.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| List projector, `list-` prefix | append a list-creating event for household H to `list-{id}` | the list row for H appears in `shopping_list_read_model` | poll times out → test fails |
| List projector, `household-` prefix | then append `HouseholdDeleted(H)` to `household-{H}` | H's list row is purged | as above |
| Trip projector, `trip-` prefix | append `TripStarted` (H, ≥1 store) to `trip-{id}` | trip-store rows appear | as above |
| Trip projector, `household-` prefix | then append `HouseholdDeleted(H)` | H's trip-store rows are purged | as above |

</frozen-after-approval>

## Code Map

- `backend/src/test/java/de/sgart/collaboration/adapter/out/MultiPrefixKurrentDbSubscriptionRegressionTest.java` -- today: KurrentDB container + a deliberately *unreachable* datasource, two `start()`-only tests. Extend it here: add the Postgres container + Flyway and keep the two existing tests.
- `backend/src/test/java/de/sgart/collaboration/adapter/out/HouseholdLiveSyncFanoutIntegrationTest.java:70-110` -- the harness to mirror: `@Container` Postgres 18.6, `DriverManagerDataSource` + `Flyway.migrate()`, `KurrentDbEventStore(client)`, the `awaitTrue`/`awaitBroadcast` bounded-poll helpers.
- `backend/src/main/java/de/sgart/collaboration/adapter/out/ShoppingListReadModelProjector.java:97-165,208-245` -- `project` (the list-creation case, `HouseholdDeleted` → `purgeHousehold` on 3 read models) and the `^(list|household)-.*` filter, `fromStart`.
- `backend/src/main/java/de/sgart/collaboration/adapter/out/ShoppingTripReadModelProjector.java:67-80,122-156` -- `TripStarted` → store rows, `HouseholdDeleted` → `purgeHousehold`, the `^(trip|household)-.*` filter.
- `backend/src/main/java/de/sgart/collaboration/adapter/out/KurrentDbEventStore.java:41` -- `append(expectedVersion, events, commandId)`; the stream is derived from the event.
- `ShoppingListReadModelProjectorTest.java:~1061` -- a direct-`project` `HouseholdDeleted` fixture; reuse its event construction.
- `_bmad-output/implementation-artifacts/deferred-work.md:146-153` -- the stale 4.4 entry that spawned this story.

## Tasks & Acceptance

**Execution:**
- [x] `MultiPrefixKurrentDbSubscriptionRegressionTest.java` -- add Postgres + Flyway; add `shoppingListProjector_liveSubscription_deliversBothListAndHouseholdStreamEvents` and `shoppingTripProjector_liveSubscription_deliversBothTripAndHouseholdStreamEvents` per the matrix; update the class Javadoc (it now proves delivery, not just start-up). Keep the two existing `start()` tests.
- [x] `_bmad-output/implementation-artifacts/deferred-work.md` -- mark the 4.4 "chain `addStreamNamePrefix` twice" entry **resolved** (fixed `b4218f7`; delivery proven by Story 8.8). Change nothing else.

**Acceptance Criteria:**
- Given both projectors started live against a real KurrentDB, when an event is appended to each of their two stream prefixes, then each is projected into the real read model within a bounded wait.
- Given the full backend suite, when it runs, then it is green, including ArchUnit and the new Testcontainers tests.

## Implementation Notes

- Implemented by Sonnet subagent 2026-09-28. Extended `MultiPrefixKurrentDbSubscriptionRegressionTest` per the harness in `HouseholdLiveSyncFanoutIntegrationTest`: added the `@Container Postgres 18.6` + `Flyway.migrate()` + shared `JdbcClient`/`KurrentDbEventStore`, kept the two existing `start()`-only tests unchanged (they now build their projectors against a dedicated unreachable-datasource `JdbcClient` per test, since the class-level `jdbcClient` now points at the real Postgres container), and added the two delivery tests from the I/O matrix, each appending through the real `KurrentDbEventStore` and polling the real read model (`shoppingListReadModel.listsOf(...)`, `tripStoreReadModel.storesOf(...)`) with the same bounded `awaitTrue` helper used in the fanout test (80 × 250 ms).
- No production code changed — the delivery proof did not expose a defect; both projectors' single-regex filters already deliver both prefixes correctly (per D1, this was expected since `b4218f7`).
- Updated the class Javadoc to describe both the original start()-only guard and the new delivery proof.
- Marked the Story 4.4 code-review deferred-work entry for the chained `addStreamNamePrefix` throw as **RESOLVED**, citing `b4218f7` and this story's delivery proof.

## Spec Change Log

## Review Triage Log

Review 2026-09-28 (Opus; Edge Case Hunter + Verification Gap; Blind Hunter skipped for the thin, test-only slice). VG: no gaps. It mutation-checked all four prefix branches, and each regression fails the new tests.

| # | Finding | Verdict | Evidence / route |
|---|---------|---------|------------------|
| 1 | (EC) The start()-only tests' "never queried" comment is now false: those projectors receive other tests' events and hit the unreachable datasource | low | Real: `stop()` doesn't cancel the subscription (see #2), and `fromStart` replays → patch (reword the comment/Javadoc). |
| 2 | (EC + VG other) `ShoppingList/TripReadModelProjector.stop()` only clears `running` and shuts down the scheduler; the open `$all` subscription is never cancelled | medium | Verified at `ShoppingListReadModelProjector.java:191` / `ShoppingTripReadModelProjector.java:103`; `HouseholdLiveSyncFanout`/`HouseholdNotificationFanout` already retain and stop theirs. Pre-existing production code, not caused by this test-only story → defer. |
| 3 | (EC claim) "Tests independent of fromStart replay" holds only for the delivery tests | low | Same root as #1/#2; the delivery tests are isolated by fresh ids; handled by the #1 rewording. |
| 4 | (VG other) `awaitTrue` duplicated line for line from `HouseholdLiveSyncFanoutIntegrationTest` | low | Real, violates the DRY rule (CLAUDE.md §1); a small helper extraction → patch. |

## Verification

**Commands:**
- `cd backend && ./gradlew :test --tests '*MultiPrefixKurrentDbSubscriptionRegressionTest'` -- ran: BUILD SUCCESSFUL, 4 tests pass.
- `cd backend && ./gradlew test` -- ran: BUILD SUCCESSFUL (full suite incl. ArchUnit and all Testcontainers tests).
- App suite not run: backend-test-only story, no `app/` change.
