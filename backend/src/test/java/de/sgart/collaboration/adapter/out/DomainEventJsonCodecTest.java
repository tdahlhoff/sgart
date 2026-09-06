package de.sgart.collaboration.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.domain.EmailHmac;
import de.sgart.collaboration.domain.event.HouseholdCreated;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.event.HouseholdRenamed;
import de.sgart.collaboration.domain.HouseholdRole;
import de.sgart.collaboration.domain.event.InviteExpired;
import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.collaboration.domain.event.ItemAdded;
import de.sgart.collaboration.domain.event.ItemAssignedToStore;
import de.sgart.collaboration.domain.event.ItemCheckedOff;
import de.sgart.collaboration.domain.event.ItemDiscarded;
import de.sgart.collaboration.domain.event.ItemRemoved;
import de.sgart.collaboration.domain.event.ItemTransferCancelled;
import de.sgart.collaboration.domain.event.ItemTransferConfirmed;
import de.sgart.collaboration.domain.event.ItemTransferInitiated;
import de.sgart.collaboration.domain.event.ItemUnchecked;
import de.sgart.collaboration.domain.event.ItemUpdated;
import de.sgart.collaboration.domain.event.MemberInvited;
import de.sgart.collaboration.domain.event.MemberJoined;
import de.sgart.collaboration.domain.TransferCancellationReason;
import de.sgart.collaboration.domain.TransferOrigin;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.event.ShoppingListCreated;
import de.sgart.collaboration.domain.event.ShoppingListRenamed;
import de.sgart.collaboration.domain.event.StoreAdded;
import de.sgart.collaboration.domain.event.StoreArchived;
import de.sgart.collaboration.domain.event.TripStarted;
import de.sgart.collaboration.domain.event.TripStartedForList;
import de.sgart.collaboration.domain.StoreName;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import de.sgart.shared.ItemId;
import de.sgart.shared.MemberId;
import de.sgart.shared.Quantity;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreChainId;
import de.sgart.shared.StoreId;
import de.sgart.shared.TripId;
import de.sgart.shared.Unit;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

    @Test
    void itemTransferInitiatedForAPlanningMoveWithANoteRoundTripsThroughJsonUnderItsStableTypeTag() {
        ItemTransferInitiated event = new ItemTransferInitiated(
                EventId.generate(),
                householdId,
                ShoppingListId.generate(),
                ItemId.generate(),
                ShoppingListId.generate(),
                new ItemName("Milch"),
                new ItemNote("Bio"),
                Quantity.of(2, Unit.PIECE),
                TransferOrigin.PLANNING_MOVE);

        assertThat(codec.typeTagFor(event)).isEqualTo("ItemTransferInitiated");
        assertThat(roundTrip(event)).isEqualTo(event);
    }

    @Test
    void itemTransferInitiatedForAnInTripPostponeWithNoNoteRoundTripsThroughJsonPreservingTheNullNote() {
        ItemTransferInitiated event = new ItemTransferInitiated(
                EventId.generate(),
                householdId,
                ShoppingListId.generate(),
                ItemId.generate(),
                ShoppingListId.generate(),
                new ItemName("Milch"),
                null,
                Quantity.of(1, Unit.PIECE),
                TransferOrigin.IN_TRIP_POSTPONE);

        ItemTransferInitiated decoded = (ItemTransferInitiated) roundTrip(event);
        assertThat(decoded).isEqualTo(event);
        assertThat(decoded.note()).isNull();
        assertThat(decoded.origin()).isEqualTo(TransferOrigin.IN_TRIP_POSTPONE);
    }

    @Test
    void itemTransferConfirmedRoundTripsThroughJsonUnderItsStableTypeTag() {
        ItemTransferConfirmed event =
                new ItemTransferConfirmed(EventId.generate(), ShoppingListId.generate(), ItemId.generate());

        assertThat(codec.typeTagFor(event)).isEqualTo("ItemTransferConfirmed");
        assertThat(roundTrip(event)).isEqualTo(event);
    }

    @Test
    void itemTransferCancelledRoundTripsThroughJsonForEachCancellationReason() {
        ItemTransferCancelled targetNotOpen = new ItemTransferCancelled(
                EventId.generate(), ShoppingListId.generate(), ItemId.generate(), TransferCancellationReason.TARGET_NOT_OPEN);
        ItemTransferCancelled targetGone = new ItemTransferCancelled(
                EventId.generate(), ShoppingListId.generate(), ItemId.generate(), TransferCancellationReason.TARGET_GONE);

        assertThat(codec.typeTagFor(targetNotOpen)).isEqualTo("ItemTransferCancelled");
        assertThat(roundTrip(targetNotOpen)).isEqualTo(targetNotOpen);
        ItemTransferCancelled decodedGone = (ItemTransferCancelled) roundTrip(targetGone);
        assertThat(decodedGone).isEqualTo(targetGone);
        assertThat(decodedGone.reason()).isEqualTo(TransferCancellationReason.TARGET_GONE);
    }

    @Test
    void itemAssignedToStoreRoundTripsThroughJsonUnderItsStableTypeTag() {
        ItemAssignedToStore event = new ItemAssignedToStore(
                EventId.generate(), householdId, ShoppingListId.generate(), ItemId.generate(), StoreId.generate());

        assertThat(codec.typeTagFor(event)).isEqualTo("ItemAssignedToStore");
        assertThat(roundTrip(event)).isEqualTo(event);
    }

    @Test
    void tripStartedForListRoundTripsThroughJsonUnderItsStableTypeTagWithAMultiStoreList() {
        TripStartedForList event = new TripStartedForList(
                EventId.generate(),
                householdId,
                ShoppingListId.generate(),
                TripId.generate(),
                List.of(StoreId.generate(), StoreId.generate()));

        assertThat(codec.typeTagFor(event)).isEqualTo("TripStartedForList");
        assertThat(roundTrip(event)).isEqualTo(event);
    }

    @Test
    void tripStartedRoundTripsThroughJsonUnderItsStableTypeTagWithAMultiStoreList() {
        TripStarted event = new TripStarted(
                EventId.generate(),
                TripId.generate(),
                householdId,
                ShoppingListId.generate(),
                List.of(StoreId.generate(), StoreId.generate()));

        assertThat(codec.typeTagFor(event)).isEqualTo("TripStarted");
        assertThat(roundTrip(event)).isEqualTo(event);
    }

    @Test
    void itemReroutedRoundTripsThroughJsonUnderItsStableTypeTag() {
        de.sgart.collaboration.domain.event.ItemRerouted event = new de.sgart.collaboration.domain.event.ItemRerouted(
                EventId.generate(), householdId, ShoppingListId.generate(), ItemId.generate(), StoreId.generate());

        assertThat(codec.typeTagFor(event)).isEqualTo("ItemRerouted");
        assertThat(roundTrip(event)).isEqualTo(event);
    }

    @Test
    void itemCheckedOffRoundTripsThroughJsonUnderItsStableTypeTag() {
        ItemCheckedOff event = new ItemCheckedOff(EventId.generate(), householdId, ShoppingListId.generate(), ItemId.generate());

        assertThat(codec.typeTagFor(event)).isEqualTo("ItemCheckedOff");
        assertThat(roundTrip(event)).isEqualTo(event);
    }

    @Test
    void itemUncheckedRoundTripsThroughJsonUnderItsStableTypeTag() {
        ItemUnchecked event = new ItemUnchecked(EventId.generate(), householdId, ShoppingListId.generate(), ItemId.generate());

        assertThat(codec.typeTagFor(event)).isEqualTo("ItemUnchecked");
        assertThat(roundTrip(event)).isEqualTo(event);
    }

    @Test
    void itemDiscardedRoundTripsThroughJsonUnderItsStableTypeTag() {
        ItemDiscarded event = new ItemDiscarded(EventId.generate(), householdId, ShoppingListId.generate(), ItemId.generate());

        assertThat(codec.typeTagFor(event)).isEqualTo("ItemDiscarded");
        assertThat(roundTrip(event)).isEqualTo(event);
    }

    @Test
    void storeAddedToTripRoundTripsThroughJsonUnderItsStableTypeTag() {
        de.sgart.collaboration.domain.event.StoreAddedToTrip event =
                new de.sgart.collaboration.domain.event.StoreAddedToTrip(
                        EventId.generate(), TripId.generate(), householdId, StoreId.generate());

        assertThat(codec.typeTagFor(event)).isEqualTo("StoreAddedToTrip");
        assertThat(roundTrip(event)).isEqualTo(event);
    }

    @Test
    void tripCompletedForListRoundTripsThroughJsonUnderItsStableTypeTag() {
        de.sgart.collaboration.domain.event.TripCompletedForList event =
                new de.sgart.collaboration.domain.event.TripCompletedForList(
                        EventId.generate(), householdId, ShoppingListId.generate(), TripId.generate());

        assertThat(codec.typeTagFor(event)).isEqualTo("TripCompletedForList");
        assertThat(roundTrip(event)).isEqualTo(event);
    }

    @Test
    void tripCompletedRoundTripsThroughJsonUnderItsStableTypeTag() {
        de.sgart.collaboration.domain.event.TripCompleted event =
                new de.sgart.collaboration.domain.event.TripCompleted(
                        EventId.generate(), TripId.generate(), householdId, ShoppingListId.generate());

        assertThat(codec.typeTagFor(event)).isEqualTo("TripCompleted");
        assertThat(roundTrip(event)).isEqualTo(event);
    }

    @Test
    void memberInvitedRoundTripsThroughJsonUnderItsStableTypeTag() {
        MemberInvited event = new MemberInvited(
                EventId.generate(),
                householdId,
                InviteId.generate(),
                new EmailHmac("hmac-of-anna-example-com"),
                MemberId.generate(),
                HouseholdRole.PARTICIPANT,
                Instant.parse("2026-09-06T10:00:00Z"));

        assertThat(codec.typeTagFor(event)).isEqualTo("MemberInvited");
        assertThat(roundTrip(event)).isEqualTo(event);
    }

    @Test
    void memberInvitedJsonPayloadNeverCarriesTheRawEmail() {
        String hexDigest = "9f2b7a3c4e5d6a1b8c9d0e1f2a3b4c5d6e7f8091a2b3c4d5e6f7081920a1b2c3";
        MemberInvited event = new MemberInvited(
                EventId.generate(),
                householdId,
                InviteId.generate(),
                new EmailHmac(hexDigest),
                MemberId.generate(),
                HouseholdRole.PARTICIPANT,
                Instant.parse("2026-09-06T10:00:00Z"));

        String json = new String(codec.toJsonBytes(event), java.nio.charset.StandardCharsets.UTF_8);

        // Privacy round-trip guard (AD-6): the wire payload carries only the emailHmac digest —
        // never an "@"-shaped raw address, and no field named plain "email".
        assertThat(json).doesNotContain("@");
        assertThat(json).contains("emailHmac").contains(hexDigest);
        assertThat(json).doesNotContain("\"email\"");
    }

    @Test
    void inviteExpiredRoundTripsThroughJsonUnderItsStableTypeTag() {
        InviteExpired event = new InviteExpired(EventId.generate(), householdId, InviteId.generate());

        assertThat(codec.typeTagFor(event)).isEqualTo("InviteExpired");
        assertThat(roundTrip(event)).isEqualTo(event);
    }

    private DomainEvent roundTrip(DomainEvent event) {
        return codec.fromJsonBytes(codec.typeTagFor(event), codec.toJsonBytes(event));
    }
}
