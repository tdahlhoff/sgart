package de.sgart.collaboration.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.ConcurrencyConflictException;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StreamId;
import io.kurrent.dbclient.KurrentDBClient;
import io.kurrent.dbclient.KurrentDBConnectionString;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Testcontainers integration test against real KurrentDB {@code 25.1.4} (the compose image,
 * Clarification 1) — re-proves, against real infrastructure and the production {@code Household}
 * event vocabulary, the same {@code EventStore} port guarantees {@code InMemoryEventStore} already
 * satisfies with the synthetic {@code CounterAggregate} fixture ({@code EventStoreContractTest}):
 * expected-version optimistic concurrency, {@code commandId} idempotent replay, and multi-event
 * atomicity. Deliberately does <em>not</em> reuse {@code EventStoreContractTestBase} — {@link
 * DomainEventJsonCodec} only knows the real event vocabulary (by design, so a wire-format
 * regression in production events is never masked by a test fixture), and the task's own brief
 * asks for these guarantees proven against a real {@code Household}, not the synthetic counter.
 * Owns its own container lifecycle; never points at the dev compose KurrentDB (Story 1.4's
 * Keycloak precedent).
 */
@Testcontainers
class KurrentDbEventStoreTest {

    @Container
    static final GenericContainer<?> KURRENTDB = new GenericContainer<>("docker.kurrent.io/kurrent-latest/kurrentdb:25.1.4")
            .withExposedPorts(2113)
            .withEnv("KURRENTDB_CLUSTER_SIZE", "1")
            .withEnv("KURRENTDB_RUN_PROJECTIONS", "All")
            .withEnv("KURRENTDB_START_STANDARD_PROJECTIONS", "true")
            .withEnv("KURRENTDB_INSECURE", "true")
            .withEnv("KURRENTDB_ENABLE_ATOM_PUB_OVER_HTTP", "true")
            .waitingFor(Wait.forHttp("/health/live")
                    .forPort(2113)
                    // The endpoint replies 204 No Content when healthy, not 200 (verified against
                    // the real image) — the default matcher only accepts 200.
                    .forStatusCode(204)
                    .withStartupTimeout(Duration.ofMinutes(2)));

    private static KurrentDBClient client;

    private KurrentDbEventStore eventStore;
    private StreamId streamId;

    @BeforeAll
    static void createClient() {
        String connectionString =
                "esdb://" + KURRENTDB.getHost() + ":" + KURRENTDB.getMappedPort(2113) + "?tls=false";
        client = KurrentDBClient.create(KurrentDBConnectionString.parseOrThrow(connectionString));
    }

    @AfterAll
    static void closeClient() {
        client.shutdown().join();
    }

    @BeforeEach
    void setUp() {
        eventStore = new KurrentDbEventStore(client);
        streamId = StreamId.forHousehold(HouseholdId.generate());
    }

    @Test
    void appendingAHouseholdsCreationEventsAdvancesTheStreamByTwo() {
        Household household = newHousehold();

        eventStore.append(AggregateVersion.initial(streamId), household.uncommittedEvents(), CommandId.generate());

        assertThat(eventStore.readStream(streamId)).hasSize(2);
    }

    @Test
    void readStreamOnANeverAppendedStreamReturnsAnEmptyList() {
        assertThat(eventStore.readStream(streamId)).isEmpty();
    }

    @Test
    void rejectsAnAppendWhoseBasedOnVersionIsBehindTheStreamAndWritesNothing() {
        eventStore.append(AggregateVersion.initial(streamId), newHousehold().uncommittedEvents(), CommandId.generate());

        assertThatThrownBy(() -> eventStore.append(
                        AggregateVersion.initial(streamId), // behind: stream is already at version 2
                        newHousehold().uncommittedEvents(),
                        CommandId.generate()))
                .isInstanceOf(ConcurrencyConflictException.class)
                .satisfies(thrown -> assertThat(((ConcurrencyConflictException) thrown).errorDescriptor().code())
                        .isEqualTo(ConcurrencyConflictException.ERROR_CODE));

        assertThat(eventStore.readStream(streamId)).hasSize(2); // nothing further was appended
    }

    @Test
    void aRejectedMultiEventAppendLeavesTheStreamUntouchedAtomically() {
        // A rejected append writes NEITHER of the two events (HouseholdCreated, MemberJoined) —
        // proving atomicity, not just that the version check itself works.
        eventStore.append(AggregateVersion.initial(streamId), newHousehold().uncommittedEvents(), CommandId.generate());
        List<DomainEvent> afterFirstAppend = eventStore.readStream(streamId);

        assertThatThrownBy(() -> eventStore.append(
                        AggregateVersion.initial(streamId),
                        newHousehold().uncommittedEvents(),
                        CommandId.generate()))
                .isInstanceOf(ConcurrencyConflictException.class);

        assertThat(eventStore.readStream(streamId)).isEqualTo(afterFirstAppend);
    }

    @Test
    void appliesTheSameCommandIdOnlyOnceEvenAfterTheStreamAdvanced() {
        CommandId retriedCommand = CommandId.generate();
        eventStore.append(AggregateVersion.initial(streamId), newHousehold().uncommittedEvents(), retriedCommand);

        // Re-delivering the same commandId, even against its now-stale basedOnVersion, is a silent
        // no-op — never a conflict, and never applied twice (AD-8, "survives restart").
        eventStore.append(AggregateVersion.initial(streamId), newHousehold().uncommittedEvents(), retriedCommand);

        assertThat(eventStore.readStream(streamId)).hasSize(2);
    }

    @Test
    void aRoundTripAppendThenReadStreamRebuildsTheSameHousehold() {
        HouseholdId householdId = HouseholdId.generate();
        MemberId adminMemberId = MemberId.generate();
        StreamId householdStreamId = StreamId.forHousehold(householdId);
        Household original =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, CommandId.generate());

        eventStore.append(AggregateVersion.initial(householdStreamId), original.uncommittedEvents(), CommandId.generate());

        List<DomainEvent> history = eventStore.readStream(householdStreamId);
        Household rehydrated = Household.rehydrate(householdStreamId, history);

        assertThat(rehydrated.householdId()).isEqualTo(original.householdId());
        assertThat(rehydrated.name()).isEqualTo(original.name());
        assertThat(rehydrated.version()).isEqualTo(original.version());
    }

    private Household newHousehold() {
        return Household.create(
                new HouseholdId(streamId.id()), new HouseholdName("Familie Muster"), MemberId.generate(), CommandId.generate());
    }
}
