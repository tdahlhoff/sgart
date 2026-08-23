package de.sgart.shared;

import de.sgart.shared.support.EventStoreContractTestBase;
import de.sgart.shared.support.InMemoryEventStore;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6). Proves the
 * {@link EventStore} port contract against the fast in-memory double
 * ({@link EventStoreContractTestBase}); the real KurrentDB adapter proves the identical contract in
 * {@code KurrentDbEventStoreTest} (Story 1.6, Testcontainers).
 */
class EventStoreContractTest extends EventStoreContractTestBase {

    @Override
    protected EventStore createEventStore() {
        return new InMemoryEventStore();
    }
}
