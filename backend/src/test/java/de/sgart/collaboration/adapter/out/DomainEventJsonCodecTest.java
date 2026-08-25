package de.sgart.collaboration.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.domain.event.HouseholdCreated;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.event.HouseholdRenamed;
import de.sgart.collaboration.domain.HouseholdRole;
import de.sgart.collaboration.domain.event.MemberJoined;
import de.sgart.collaboration.domain.event.StoreAdded;
import de.sgart.collaboration.domain.event.StoreArchived;
import de.sgart.collaboration.domain.StoreName;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StoreChainId;
import de.sgart.shared.StoreId;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test of the JSON wire codec — no framework or persistence (CLAUDE.md §6). Encoding then
 * decoding an event must reconstruct an equal event, and the type tag must be the stable wire tag
 * (not the Java class name) so the format survives refactors (Story 1.6 Task 3; Story 1.7 adds
 * {@link HouseholdRenamed}).
 */
class DomainEventJsonCodecTest {

    private final DomainEventJsonCodec codec = new DomainEventJsonCodec();
    private final HouseholdId householdId = HouseholdId.generate();

    @Test
    void householdRenamedRoundTripsThroughJsonUnderItsStableTypeTag() {
        HouseholdRenamed event =
                new HouseholdRenamed(EventId.generate(), householdId, new HouseholdName("Familie Beispiel"));

        assertThat(codec.typeTagFor(event)).isEqualTo("HouseholdRenamed");
        assertThat(roundTrip(event)).isEqualTo(event);
    }

    @Test
    void householdCreatedRoundTripsThroughJson() {
        HouseholdCreated event =
                new HouseholdCreated(EventId.generate(), householdId, new HouseholdName("Familie Muster"));

        assertThat(roundTrip(event)).isEqualTo(event);
    }

    @Test
    void memberJoinedRoundTripsThroughJson() {
        MemberJoined event =
                new MemberJoined(EventId.generate(), householdId, MemberId.generate(), HouseholdRole.ADMIN);

        assertThat(roundTrip(event)).isEqualTo(event);
    }

    @Test
    void storeAddedWithAChainRoundTripsThroughJsonUnderItsStableTypeTag() {
        StoreAdded event = new StoreAdded(
                EventId.generate(), householdId, StoreId.generate(), new StoreName("Edeka"), StoreChainId.generate());

        assertThat(codec.typeTagFor(event)).isEqualTo("StoreAdded");
        assertThat(roundTrip(event)).isEqualTo(event);
    }

    @Test
    void storeAddedWithNoChainRoundTripsThroughJsonPreservingTheNullChain() {
        StoreAdded event =
                new StoreAdded(EventId.generate(), householdId, StoreId.generate(), new StoreName("Wochenmarkt"), null);

        StoreAdded decoded = (StoreAdded) roundTrip(event);
        assertThat(decoded).isEqualTo(event);
        assertThat(decoded.chainId()).isNull();
    }

    @Test
    void storeArchivedRoundTripsThroughJsonUnderItsStableTypeTag() {
        StoreArchived event = new StoreArchived(EventId.generate(), householdId, StoreId.generate());

        assertThat(codec.typeTagFor(event)).isEqualTo("StoreArchived");
        assertThat(roundTrip(event)).isEqualTo(event);
    }

    private DomainEvent roundTrip(DomainEvent event) {
        return codec.fromJsonBytes(codec.typeTagFor(event), codec.toJsonBytes(event));
    }
}
