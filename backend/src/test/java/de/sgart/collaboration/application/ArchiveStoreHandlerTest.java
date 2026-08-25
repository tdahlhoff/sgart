package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.command.AddStoreHandler;
import de.sgart.collaboration.application.command.ArchiveStoreHandler;
import de.sgart.collaboration.application.exception.NotAHouseholdMemberApplicationException;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.StoreName;
import de.sgart.collaboration.domain.event.StoreAdded;
import de.sgart.collaboration.domain.event.StoreArchived;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.ConcurrencyConflictException;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StoreId;
import de.sgart.shared.StreamId;
import de.sgart.shared.support.InMemoryEventStore;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — in-memory {@code EventStore} + Identity ACL (CLAUDE.md §6). Proves the
 * archive-store command path (AC3): archiving an active store appends {@code StoreArchived} under
 * the loaded version, archiving an already-archived/unknown store skips the append (convergent
 * no-op, AD-8), and a non-member is rejected (403).
 */
class ArchiveStoreHandlerTest {

    private static final String ADMIN_SUB = "anna-sub";

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final AddStoreHandler addStoreHandler =
            new AddStoreHandler(eventStore, new ResolveMemberIdentity(mappingRepository));
    private final ArchiveStoreHandler handler =
            new ArchiveStoreHandler(eventStore, new ResolveMemberIdentity(mappingRepository));

    private final HouseholdId householdId = HouseholdId.generate();
    private final MemberId adminMemberId = MemberId.generate();
    private final StreamId streamId = StreamId.forHousehold(householdId);

    private void seedHouseholdWithAdmin() {
        Household household =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, CommandId.generate());
        eventStore.append(AggregateVersion.initial(streamId), household.uncommittedEvents(), CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, adminMemberId, new KeycloakUserId(ADMIN_SUB)));
    }

    private StoreId seedActiveStore() {
        StoreId storeId = StoreId.generate();
        addStoreHandler.handle(
                ADMIN_SUB, householdId.toString(), storeId.toString(), "Edeka", null, CommandId.generate().toString());
        return storeId;
    }

    @Test
    void archivingAnActiveStoreAppendsStoreArchivedUnderTheLoadedVersion() {
        seedHouseholdWithAdmin();
        StoreId storeId = seedActiveStore();

        handler.handle(ADMIN_SUB, householdId.toString(), storeId.toString(), CommandId.generate().toString());

        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events).hasSize(4); // created, joined, added, archived
        assertThat(events.get(3)).isInstanceOf(StoreArchived.class);
        assertThat(((StoreArchived) events.get(3)).storeId()).isEqualTo(storeId);
    }

    @Test
    void archivingAnAlreadyArchivedStoreSkipsTheAppend() {
        seedHouseholdWithAdmin();
        StoreId storeId = seedActiveStore();
        handler.handle(ADMIN_SUB, householdId.toString(), storeId.toString(), CommandId.generate().toString());

        handler.handle(ADMIN_SUB, householdId.toString(), storeId.toString(), CommandId.generate().toString());

        assertThat(eventStore.readStream(streamId)).hasSize(4); // no second StoreArchived
    }

    @Test
    void archivingAnUnknownStoreSkipsTheAppend() {
        seedHouseholdWithAdmin();

        handler.handle(
                ADMIN_SUB, householdId.toString(), StoreId.generate().toString(), CommandId.generate().toString());

        assertThat(eventStore.readStream(streamId)).hasSize(2); // still just the two creation events
    }

    @Test
    void rejectsAnArchiveFromANonMemberWith403() {
        seedHouseholdWithAdmin();
        StoreId storeId = seedActiveStore();

        assertThatThrownBy(() -> handler.handle(
                        "stranger-sub", householdId.toString(), storeId.toString(), CommandId.generate().toString()))
                .isInstanceOf(NotAMemberException.class);
    }

    @Test
    void translatesTheAggregateMembershipGuardIntoAnApplicationException() {
        seedHouseholdWithAdmin();
        StoreId storeId = seedActiveStore();
        // ACL/event-stream divergence: the ACL maps this caller to a member id the household's
        // stream never recorded joining, so resolve() succeeds but the aggregate's requireMember
        // guard rejects the archive. The handler must surface it as an application exception so the
        // write-side error advice in adapter.in never has to reach into the domain layer (AD-1).
        mappingRepository.save(new MemberMapping(householdId, MemberId.generate(), new KeycloakUserId("ghost-sub")));

        assertThatThrownBy(() -> handler.handle(
                        "ghost-sub", householdId.toString(), storeId.toString(), CommandId.generate().toString()))
                .isInstanceOf(NotAHouseholdMemberApplicationException.class);
    }

    @Test
    void propagatesAConcurrencyConflictWhenTheStreamAdvancesUnderTheArchive() {
        seedHouseholdWithAdmin();
        StoreId storeId = seedActiveStore();
        // A competing writer advances the stream between the archive handler's load and its append,
        // so the loaded expected version is stale — the append must fail with a
        // ConcurrencyConflictException (AD-8), never overwrite.
        ArchiveStoreHandler racingHandler =
                new ArchiveStoreHandler(racingStoreThatAdvancesOnFirstRead(), new ResolveMemberIdentity(mappingRepository));

        assertThatThrownBy(() -> racingHandler.handle(
                        ADMIN_SUB, householdId.toString(), storeId.toString(), CommandId.generate().toString()))
                .isInstanceOf(ConcurrencyConflictException.class);
    }

    /**
     * An {@link EventStore} decorator that, the first time a stream is read, appends a competing
     * {@link StoreAdded} to advance its version — simulating a concurrent writer between the
     * handler's load and its append, so the handler's expected version is stale (AD-8).
     */
    private EventStore racingStoreThatAdvancesOnFirstRead() {
        return new EventStore() {
            private boolean raced = false;

            @Override
            public List<DomainEvent> readStream(StreamId id) {
                List<DomainEvent> events = eventStore.readStream(id);
                if (!raced) {
                    raced = true;
                    eventStore.append(
                            AggregateVersion.of(id, events.size()),
                            List.of(new StoreAdded(
                                    EventId.generate(), householdId, StoreId.generate(), new StoreName("Concurrent"), null)),
                            CommandId.generate());
                }
                return events;
            }

            @Override
            public void append(AggregateVersion expectedVersion, List<DomainEvent> events, CommandId commandId) {
                eventStore.append(expectedVersion, events, commandId);
            }
        };
    }
}
