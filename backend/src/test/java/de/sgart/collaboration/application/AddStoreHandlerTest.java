package de.sgart.collaboration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.StoreAdded;
import de.sgart.collaboration.domain.StoreName;
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
import de.sgart.shared.StoreChainId;
import de.sgart.shared.StoreId;
import de.sgart.shared.StreamId;
import de.sgart.shared.support.InMemoryEventStore;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — in-memory {@code EventStore} + in-memory Identity ACL, no framework or
 * persistence (CLAUDE.md §6). Proves the add-store command path (AC1, AC4): a member's add appends
 * {@code StoreAdded} under the loaded expected version, a non-member is rejected (403), a duplicate
 * active name maps to the {@code store.duplicateName} conflict, and malformed fields map to their
 * localizable codes.
 */
class AddStoreHandlerTest {

    private static final String ADMIN_SUB = "anna-sub";

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemoryMemberMappingRepository mappingRepository = new InMemoryMemberMappingRepository();
    private final AddStoreHandler handler =
            new AddStoreHandler(eventStore, new ResolveMemberIdentity(mappingRepository));

    private final HouseholdId householdId = HouseholdId.generate();
    private final MemberId adminMemberId = MemberId.generate();
    private final StreamId streamId = StreamId.forHousehold(householdId);

    private void seedHouseholdWithAdmin() {
        Household household =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, CommandId.generate());
        eventStore.append(AggregateVersion.initial(streamId), household.uncommittedEvents(), CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, adminMemberId, new KeycloakUserId(ADMIN_SUB)));
    }

    @Test
    void addingAStoreAppendsStoreAddedUnderTheLoadedExpectedVersion() {
        seedHouseholdWithAdmin();
        StoreId storeId = StoreId.generate();
        StoreChainId chainId = StoreChainId.generate();

        handler.handle(
                ADMIN_SUB,
                householdId.toString(),
                storeId.toString(),
                "Edeka Schiedemann",
                chainId.toString(),
                CommandId.generate().toString());

        List<DomainEvent> events = eventStore.readStream(streamId);
        assertThat(events).hasSize(3);
        assertThat(events.get(2)).isInstanceOf(StoreAdded.class);
        StoreAdded added = (StoreAdded) events.get(2);
        assertThat(added.storeId()).isEqualTo(storeId);
        assertThat(added.chainId()).isEqualTo(chainId);
    }

    @Test
    void addingAStoreWithNoChainAppendsAnUnlinkedStore() {
        seedHouseholdWithAdmin();

        handler.handle(
                ADMIN_SUB,
                householdId.toString(),
                StoreId.generate().toString(),
                "Wochenmarkt",
                null,
                CommandId.generate().toString());

        StoreAdded added = (StoreAdded) eventStore.readStream(streamId).get(2);
        assertThat(added.chainId()).isNull();
    }

    @Test
    void rejectsAnAddFromANonMemberWith403() {
        seedHouseholdWithAdmin();

        assertThatThrownBy(() -> handler.handle(
                        "stranger-sub",
                        householdId.toString(),
                        StoreId.generate().toString(),
                        "Edeka",
                        null,
                        CommandId.generate().toString()))
                .isInstanceOf(NotAMemberException.class);
        assertThat(eventStore.readStream(streamId)).hasSize(2); // no StoreAdded appended
    }

    @Test
    void mapsADuplicateActiveNameToTheConflictCode() {
        seedHouseholdWithAdmin();
        handler.handle(
                ADMIN_SUB,
                householdId.toString(),
                StoreId.generate().toString(),
                "Edeka",
                null,
                CommandId.generate().toString());

        assertThatThrownBy(() -> handler.handle(
                        ADMIN_SUB,
                        householdId.toString(),
                        StoreId.generate().toString(),
                        "  edeka  ",
                        null,
                        CommandId.generate().toString()))
                .isInstanceOf(DuplicateStoreNameApplicationException.class)
                .satisfies(thrown -> assertThat(
                                ((DuplicateStoreNameApplicationException) thrown).errorDescriptor().code())
                        .isEqualTo("store.duplicateName"));
        assertThat(eventStore.readStream(streamId)).hasSize(3); // only the first StoreAdded
    }

    @Test
    void mapsABlankNameToNameRequired() {
        seedHouseholdWithAdmin();

        assertThatThrownBy(() -> handler.handle(
                        ADMIN_SUB,
                        householdId.toString(),
                        StoreId.generate().toString(),
                        "   ",
                        null,
                        CommandId.generate().toString()))
                .isInstanceOf(InvalidStoreNameException.class)
                .satisfies(thrown -> assertThat(((InvalidStoreNameException) thrown).errorDescriptor().code())
                        .isEqualTo("store.nameRequired"));
    }

    @Test
    void propagatesAConcurrencyConflictWhenTheStreamAdvancesUnderTheAdd() {
        seedHouseholdWithAdmin();
        // A competing writer lands an event on the stream between this handler's load and its append,
        // so the loaded expected version is stale — the append must fail with a
        // ConcurrencyConflictException (AD-8), never overwrite.
        AddStoreHandler racingHandler =
                new AddStoreHandler(racingStoreThatAdvancesOnFirstRead(), new ResolveMemberIdentity(mappingRepository));

        assertThatThrownBy(() -> racingHandler.handle(
                        ADMIN_SUB,
                        householdId.toString(),
                        StoreId.generate().toString(),
                        "Rewe",
                        null,
                        CommandId.generate().toString()))
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

    @Test
    void mapsAMalformedStoreIdToStoreIdInvalid() {
        seedHouseholdWithAdmin();

        assertThatThrownBy(() -> handler.handle(
                        ADMIN_SUB,
                        householdId.toString(),
                        "not-a-uuid",
                        "Edeka",
                        null,
                        CommandId.generate().toString()))
                .isInstanceOf(InvalidCommandEnvelopeException.class)
                .satisfies(thrown -> assertThat(((InvalidCommandEnvelopeException) thrown).errorDescriptor().code())
                        .isEqualTo("command.storeIdInvalid"));
    }
}
