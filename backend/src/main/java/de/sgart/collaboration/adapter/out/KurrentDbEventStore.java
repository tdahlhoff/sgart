package de.sgart.collaboration.adapter.out;

import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.ConcurrencyConflictException;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventStore;
import de.sgart.shared.StreamId;
import io.kurrent.dbclient.AppendToStreamOptions;
import io.kurrent.dbclient.EventData;
import io.kurrent.dbclient.KurrentDBClient;
import io.kurrent.dbclient.ReadResult;
import io.kurrent.dbclient.ReadStreamOptions;
import io.kurrent.dbclient.RecordedEvent;
import io.kurrent.dbclient.ResolvedEvent;
import io.kurrent.dbclient.StreamNotFoundException;
import io.kurrent.dbclient.StreamState;
import io.kurrent.dbclient.WrongExpectedVersionException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

/**
 * The real KurrentDB adapter for the Story 1.5 {@link EventStore} port (deferred to Story 1.6) —
 * event sourcing's write side (AD-4) finally wired to durable storage. Maps the port's
 * expected-version semantics onto KurrentDB's optimistic-concurrency append ({@link StreamState})
 * and its {@code commandId} idempotency onto event metadata, proving the identical contract
 * {@code InMemoryEventStore} already satisfied ({@code EventStoreContractTestBase}).
 */
public final class KurrentDbEventStore implements EventStore {

    private final KurrentDBClient client;
    private final DomainEventJsonCodec codec = new DomainEventJsonCodec();

    public KurrentDbEventStore(KurrentDBClient client) {
        this.client = Objects.requireNonNull(client, "client must not be null");
    }

    @Override
    public void append(AggregateVersion expectedVersion, List<DomainEvent> events, CommandId commandId) {
        Objects.requireNonNull(expectedVersion, "expectedVersion must not be null");
        Objects.requireNonNull(events, "events must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        String streamName = expectedVersion.streamId().key();

        if (commandIdAlreadyAppliedTo(streamName, commandId)) {
            return; // idempotent replay (AD-8): already applied, silent no-op — survives restart
        }

        EventData[] eventData = events.stream().map(event -> toEventData(event, commandId)).toArray(EventData[]::new);
        StreamState expectedState = toStreamState(expectedVersion);

        try {
            client.appendToStream(streamName, AppendToStreamOptions.get().streamState(expectedState), eventData)
                    .get();
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof WrongExpectedVersionException wrongVersion) {
                throw new ConcurrencyConflictException(
                        expectedVersion, actualVersionFrom(wrongVersion, expectedVersion.streamId()));
            }
            throw new KurrentDbAccessException("Failed to append to stream " + streamName, exception.getCause());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new KurrentDbAccessException("Interrupted while appending to stream " + streamName, exception);
        }
    }

    @Override
    public List<DomainEvent> readStream(StreamId streamId) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        return readRecordedEvents(streamId.key()).stream()
                .map(recordedEvent -> codec.fromJsonBytes(recordedEvent.getEventType(), recordedEvent.getEventData()))
                .toList();
    }

    private boolean commandIdAlreadyAppliedTo(String streamName, CommandId commandId) {
        String expectedMetadata = commandId.toString();
        return readRecordedEvents(streamName).stream()
                .anyMatch(recordedEvent -> expectedMetadata.equals(commandIdMetadataOf(recordedEvent)));
    }

    /** The applied {@code commandId} recorded as event metadata, or {@code null} if the event carries none. */
    private static String commandIdMetadataOf(RecordedEvent recordedEvent) {
        byte[] metadata = recordedEvent.getUserMetadata();
        return metadata == null ? null : new String(metadata, StandardCharsets.UTF_8);
    }

    private List<RecordedEvent> readRecordedEvents(String streamName) {
        try {
            ReadResult result =
                    client.readStream(streamName, ReadStreamOptions.get().forwards().fromStart()).get();
            return result.getEvents().stream().map(ResolvedEvent::getOriginalEvent).toList();
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof StreamNotFoundException) {
                return List.of(); // never appended (port contract): empty, not an error
            }
            throw new KurrentDbAccessException("Failed to read stream " + streamName, exception.getCause());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new KurrentDbAccessException("Interrupted while reading stream " + streamName, exception);
        }
    }

    private EventData toEventData(DomainEvent event, CommandId commandId) {
        return EventData.builderAsJson(event.eventId().value(), codec.typeTagFor(event), codec.toJsonBytes(event))
                .metadataAsBytes(commandId.toString().getBytes(StandardCharsets.UTF_8))
                .build();
    }

    private static StreamState toStreamState(AggregateVersion expectedVersion) {
        return expectedVersion.isInitial()
                ? StreamState.noStream()
                : StreamState.streamRevision(expectedVersion.value() - 1);
    }

    /** The actual current version, derived from the rejection KurrentDB itself reported — no extra read. */
    private static AggregateVersion actualVersionFrom(WrongExpectedVersionException wrongVersion, StreamId streamId) {
        long actualRaw = wrongVersion.getActualState().toRawLong();
        long actualEventCount = actualRaw < 0 ? 0 : actualRaw + 1;
        return AggregateVersion.of(streamId, actualEventCount);
    }
}
