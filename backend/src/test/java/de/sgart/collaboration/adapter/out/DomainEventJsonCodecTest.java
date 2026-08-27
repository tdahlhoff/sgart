package de.sgart.collaboration.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.domain.event.HouseholdCreated;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.event.HouseholdRenamed;
import de.sgart.collaboration.domain.HouseholdRole;
import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.collaboration.domain.event.ItemAdded;
import de.sgart.collaboration.domain.event.ItemRemoved;
import de.sgart.collaboration.domain.event.ItemUpdated;
import de.sgart.collaboration.domain.event.MemberJoined;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.event.ShoppingListCreated;
import de.sgart.collaboration.domain.event.ShoppingListRenamed;
import de.sgart.collaboration.domain.event.StoreAdded;
import de.sgart.collaboration.domain.event.StoreArchived;
import de.sgart.collaboration.domain.StoreName;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ItemId;
import de.sgart.shared.MemberId;
import de.sgart.shared.Quantity;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreChainId;
import de.sgart.shared.StoreId;
import de.sgart.shared.Unit;
import java.math.BigDecimal;
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

    @Test
    void shoppingListCreatedWithANameRoundTripsThroughJsonUnderItsStableTypeTag() {
        ShoppingListCreated event = new ShoppingListCreated(
                EventId.generate(), householdId, ShoppingListId.generate(), new ShoppingListName("Wocheneinkauf"));

        assertThat(codec.typeTagFor(event)).isEqualTo("ShoppingListCreated");
        assertThat(roundTrip(event)).isEqualTo(event);
    }

    @Test
    void shoppingListCreatedWithNoNameRoundTripsThroughJsonPreservingTheNullName() {
        ShoppingListCreated event =
                new ShoppingListCreated(EventId.generate(), householdId, ShoppingListId.generate(), null);

        ShoppingListCreated decoded = (ShoppingListCreated) roundTrip(event);
        assertThat(decoded).isEqualTo(event);
        assertThat(decoded.name()).isNull();
    }

    @Test
    void shoppingListRenamedRoundTripsThroughJsonUnderItsStableTypeTag() {
        ShoppingListRenamed event =
                new ShoppingListRenamed(EventId.generate(), ShoppingListId.generate(), new ShoppingListName("Getränke"));

        assertThat(codec.typeTagFor(event)).isEqualTo("ShoppingListRenamed");
        assertThat(roundTrip(event)).isEqualTo(event);
    }

    @Test
    void itemAddedWithANoteRoundTripsThroughJsonUnderItsStableTypeTag() {
        ItemAdded event = new ItemAdded(
                EventId.generate(),
                householdId,
                ShoppingListId.generate(),
                ItemId.generate(),
                new ItemName("Milch"),
                new ItemNote("Bio"),
                Quantity.of(1, Unit.PIECE));

        assertThat(codec.typeTagFor(event)).isEqualTo("ItemAdded");
        assertThat(roundTrip(event)).isEqualTo(event);
    }

    @Test
    void itemAddedWithNoNoteRoundTripsThroughJsonPreservingTheNullNote() {
        ItemAdded event = new ItemAdded(
                EventId.generate(),
                householdId,
                ShoppingListId.generate(),
                ItemId.generate(),
                new ItemName("Milch"),
                null,
                Quantity.of(1, Unit.PIECE));

        ItemAdded decoded = (ItemAdded) roundTrip(event);
        assertThat(decoded).isEqualTo(event);
        assertThat(decoded.note()).isNull();
    }

    @Test
    void itemAddedPreservesAFractionalAmount() {
        ItemAdded event = new ItemAdded(
                EventId.generate(),
                householdId,
                ShoppingListId.generate(),
                ItemId.generate(),
                new ItemName("Hackfleisch"),
                null,
                new Quantity(new BigDecimal("0.5"), Unit.KILOGRAM));

        assertThat(roundTrip(event)).isEqualTo(event);
    }

    @Test
    void itemUpdatedRoundTripsThroughJsonUnderItsStableTypeTag() {
        ItemUpdated event = new ItemUpdated(
                EventId.generate(),
                ShoppingListId.generate(),
                ItemId.generate(),
                new ItemName("Milch"),
                new ItemNote("Bio 1,5%"),
                Quantity.of(2, Unit.PIECE));

        assertThat(codec.typeTagFor(event)).isEqualTo("ItemUpdated");
        assertThat(roundTrip(event)).isEqualTo(event);
    }

    @Test
    void itemRemovedRoundTripsThroughJsonUnderItsStableTypeTag() {
        ItemRemoved event = new ItemRemoved(EventId.generate(), ShoppingListId.generate(), ItemId.generate());

        assertThat(codec.typeTagFor(event)).isEqualTo("ItemRemoved");
        assertThat(roundTrip(event)).isEqualTo(event);
    }

    private DomainEvent roundTrip(DomainEvent event) {
        return codec.fromJsonBytes(codec.typeTagFor(event), codec.toJsonBytes(event));
    }
}
