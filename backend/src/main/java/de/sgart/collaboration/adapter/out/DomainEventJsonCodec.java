package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.HouseholdRole;
import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemNote;
import de.sgart.collaboration.domain.ShoppingListName;
import de.sgart.collaboration.domain.StoreName;
import de.sgart.collaboration.domain.event.HouseholdCreated;
import de.sgart.collaboration.domain.event.HouseholdRenamed;
import de.sgart.collaboration.domain.event.ItemAdded;
import de.sgart.collaboration.domain.event.ItemAssignedToStore;
import de.sgart.collaboration.domain.event.ItemCheckedOff;
import de.sgart.collaboration.domain.event.ItemDiscarded;
import de.sgart.collaboration.domain.event.ItemRemoved;
import de.sgart.collaboration.domain.event.ItemRerouted;
import de.sgart.collaboration.domain.event.ItemTransferCancelled;
import de.sgart.collaboration.domain.event.ItemTransferConfirmed;
import de.sgart.collaboration.domain.event.ItemTransferInitiated;
import de.sgart.collaboration.domain.event.ItemUnchecked;
import de.sgart.collaboration.domain.event.ItemUpdated;
import de.sgart.collaboration.domain.event.MemberJoined;
import de.sgart.collaboration.domain.event.ShoppingListCreated;
import de.sgart.collaboration.domain.event.ShoppingListRenamed;
import de.sgart.collaboration.domain.event.StoreAdded;
import de.sgart.collaboration.domain.event.StoreAddedToTrip;
import de.sgart.collaboration.domain.event.StoreArchived;
import de.sgart.collaboration.domain.event.TripCompleted;
import de.sgart.collaboration.domain.event.TripCompletedForList;
import de.sgart.collaboration.domain.event.TripStarted;
import de.sgart.collaboration.domain.event.TripStartedForList;
import de.sgart.collaboration.domain.TransferCancellationReason;
import de.sgart.collaboration.domain.TransferOrigin;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ItemId;
import de.sgart.shared.MemberId;
import de.sgart.shared.Quantity;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreChainId;
import de.sgart.shared.StoreId;
import de.sgart.shared.TripId;
import de.sgart.shared.Unit;
import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * Maps {@code Household} domain events to/from JSON with a stable type tag — never a Java class
 * name, so the wire format survives refactors (Story 1.6 Task 3). The domain events themselves
 * stay pure; this codec is the only place that knows about JSON, and it lives in {@code
 * adapter.out} (AD-1).
 */
final class DomainEventJsonCodec {

    static final String HOUSEHOLD_CREATED_TYPE = "HouseholdCreated";
    static final String HOUSEHOLD_RENAMED_TYPE = "HouseholdRenamed";
    static final String MEMBER_JOINED_TYPE = "MemberJoined";
    static final String STORE_ADDED_TYPE = "StoreAdded";
    static final String STORE_ARCHIVED_TYPE = "StoreArchived";
    static final String SHOPPING_LIST_CREATED_TYPE = "ShoppingListCreated";
    static final String SHOPPING_LIST_RENAMED_TYPE = "ShoppingListRenamed";
    static final String ITEM_ADDED_TYPE = "ItemAdded";
    static final String ITEM_UPDATED_TYPE = "ItemUpdated";
    static final String ITEM_REMOVED_TYPE = "ItemRemoved";
    static final String ITEM_TRANSFER_INITIATED_TYPE = "ItemTransferInitiated";
    static final String ITEM_TRANSFER_CONFIRMED_TYPE = "ItemTransferConfirmed";
    static final String ITEM_TRANSFER_CANCELLED_TYPE = "ItemTransferCancelled";
    static final String ITEM_ASSIGNED_TO_STORE_TYPE = "ItemAssignedToStore";
    static final String TRIP_STARTED_FOR_LIST_TYPE = "TripStartedForList";
    static final String TRIP_STARTED_TYPE = "TripStarted";
    static final String ITEM_REROUTED_TYPE = "ItemRerouted";
    static final String STORE_ADDED_TO_TRIP_TYPE = "StoreAddedToTrip";
    static final String ITEM_CHECKED_OFF_TYPE = "ItemCheckedOff";
    static final String ITEM_UNCHECKED_TYPE = "ItemUnchecked";
    static final String ITEM_DISCARDED_TYPE = "ItemDiscarded";
    static final String TRIP_COMPLETED_FOR_LIST_TYPE = "TripCompletedForList";
    static final String TRIP_COMPLETED_TYPE = "TripCompleted";

    private final JsonMapper jsonMapper = new JsonMapper();

    String typeTagFor(DomainEvent event) {
        return switch (event) {
            case HouseholdCreated ignored -> HOUSEHOLD_CREATED_TYPE;
            case HouseholdRenamed ignored -> HOUSEHOLD_RENAMED_TYPE;
            case MemberJoined ignored -> MEMBER_JOINED_TYPE;
            case StoreAdded ignored -> STORE_ADDED_TYPE;
            case StoreArchived ignored -> STORE_ARCHIVED_TYPE;
            case ShoppingListCreated ignored -> SHOPPING_LIST_CREATED_TYPE;
            case ShoppingListRenamed ignored -> SHOPPING_LIST_RENAMED_TYPE;
            case ItemAdded ignored -> ITEM_ADDED_TYPE;
            case ItemUpdated ignored -> ITEM_UPDATED_TYPE;
            case ItemRemoved ignored -> ITEM_REMOVED_TYPE;
            case ItemTransferInitiated ignored -> ITEM_TRANSFER_INITIATED_TYPE;
            case ItemTransferConfirmed ignored -> ITEM_TRANSFER_CONFIRMED_TYPE;
            case ItemTransferCancelled ignored -> ITEM_TRANSFER_CANCELLED_TYPE;
            case ItemAssignedToStore ignored -> ITEM_ASSIGNED_TO_STORE_TYPE;
            case TripStartedForList ignored -> TRIP_STARTED_FOR_LIST_TYPE;
            case TripStarted ignored -> TRIP_STARTED_TYPE;
            case ItemRerouted ignored -> ITEM_REROUTED_TYPE;
            case StoreAddedToTrip ignored -> STORE_ADDED_TO_TRIP_TYPE;
            case ItemCheckedOff ignored -> ITEM_CHECKED_OFF_TYPE;
            case ItemUnchecked ignored -> ITEM_UNCHECKED_TYPE;
            case ItemDiscarded ignored -> ITEM_DISCARDED_TYPE;
            case TripCompletedForList ignored -> TRIP_COMPLETED_FOR_LIST_TYPE;
            case TripCompleted ignored -> TRIP_COMPLETED_TYPE;
            default -> throw new IllegalArgumentException("No JSON mapping for event type: " + event.getClass());
        };
    }

    byte[] toJsonBytes(DomainEvent event) {
        return switch (event) {
            case HouseholdCreated created -> jsonMapper.writeValueAsBytes(new HouseholdCreatedPayload(
                    created.eventId().value().toString(),
                    created.householdId().value().toString(),
                    created.name().value()));
            case HouseholdRenamed renamed -> jsonMapper.writeValueAsBytes(new HouseholdRenamedPayload(
                    renamed.eventId().value().toString(),
                    renamed.householdId().value().toString(),
                    renamed.newName().value()));
            case MemberJoined joined -> jsonMapper.writeValueAsBytes(new MemberJoinedPayload(
                    joined.eventId().value().toString(),
                    joined.householdId().value().toString(),
                    joined.memberId().value().toString(),
                    joined.role().name()));
            case StoreAdded added -> jsonMapper.writeValueAsBytes(new StoreAddedPayload(
                    added.eventId().value().toString(),
                    added.householdId().value().toString(),
                    added.storeId().value().toString(),
                    added.name().value(),
                    added.chainId() == null ? null : added.chainId().value().toString()));
            case StoreArchived archived -> jsonMapper.writeValueAsBytes(new StoreArchivedPayload(
                    archived.eventId().value().toString(),
                    archived.householdId().value().toString(),
                    archived.storeId().value().toString()));
            case ShoppingListCreated created -> jsonMapper.writeValueAsBytes(new ShoppingListCreatedPayload(
                    created.eventId().value().toString(),
                    created.householdId().value().toString(),
                    created.listId().value().toString(),
                    created.name() == null ? null : created.name().value()));
            case ShoppingListRenamed renamed -> jsonMapper.writeValueAsBytes(new ShoppingListRenamedPayload(
                    renamed.eventId().value().toString(),
                    renamed.listId().value().toString(),
                    renamed.newName().value()));
            case ItemAdded added -> jsonMapper.writeValueAsBytes(new ItemAddedPayload(
                    added.eventId().value().toString(),
                    added.householdId().value().toString(),
                    added.listId().value().toString(),
                    added.itemId().value().toString(),
                    added.name().value(),
                    added.note() == null ? null : added.note().value(),
                    added.quantity().amount().toPlainString(),
                    added.quantity().unit().name()));
            case ItemUpdated updated -> jsonMapper.writeValueAsBytes(new ItemUpdatedPayload(
                    updated.eventId().value().toString(),
                    updated.listId().value().toString(),
                    updated.itemId().value().toString(),
                    updated.name().value(),
                    updated.note() == null ? null : updated.note().value(),
                    updated.quantity().amount().toPlainString(),
                    updated.quantity().unit().name()));
            case ItemRemoved removed -> jsonMapper.writeValueAsBytes(new ItemRemovedPayload(
                    removed.eventId().value().toString(),
                    removed.listId().value().toString(),
                    removed.itemId().value().toString()));
            case ItemTransferInitiated initiated -> jsonMapper.writeValueAsBytes(new ItemTransferInitiatedPayload(
                    initiated.eventId().value().toString(),
                    initiated.householdId().value().toString(),
                    initiated.sourceListId().value().toString(),
                    initiated.itemId().value().toString(),
                    initiated.targetListId().value().toString(),
                    initiated.name().value(),
                    initiated.note() == null ? null : initiated.note().value(),
                    initiated.quantity().amount().toPlainString(),
                    initiated.quantity().unit().name(),
                    initiated.origin().name()));
            case ItemTransferConfirmed confirmed -> jsonMapper.writeValueAsBytes(new ItemTransferConfirmedPayload(
                    confirmed.eventId().value().toString(),
                    confirmed.listId().value().toString(),
                    confirmed.itemId().value().toString()));
            case ItemTransferCancelled cancelled -> jsonMapper.writeValueAsBytes(new ItemTransferCancelledPayload(
                    cancelled.eventId().value().toString(),
                    cancelled.listId().value().toString(),
                    cancelled.itemId().value().toString(),
                    cancelled.reason().name()));
            case ItemAssignedToStore assigned -> jsonMapper.writeValueAsBytes(new ItemAssignedToStorePayload(
                    assigned.eventId().value().toString(),
                    assigned.householdId().value().toString(),
                    assigned.listId().value().toString(),
                    assigned.itemId().value().toString(),
                    assigned.storeId().value().toString()));
            case TripStartedForList started -> jsonMapper.writeValueAsBytes(new TripStartedForListPayload(
                    started.eventId().value().toString(),
                    started.householdId().value().toString(),
                    started.listId().value().toString(),
                    started.tripId().value().toString(),
                    started.storeIds().stream().map(storeId -> storeId.value().toString()).toList()));
            case TripStarted started -> jsonMapper.writeValueAsBytes(new TripStartedPayload(
                    started.eventId().value().toString(),
                    started.tripId().value().toString(),
                    started.householdId().value().toString(),
                    started.listId().value().toString(),
                    started.storeIds().stream().map(storeId -> storeId.value().toString()).toList()));
            case ItemRerouted rerouted -> jsonMapper.writeValueAsBytes(new ItemReroutedPayload(
                    rerouted.eventId().value().toString(),
                    rerouted.householdId().value().toString(),
                    rerouted.listId().value().toString(),
                    rerouted.itemId().value().toString(),
                    rerouted.storeId().value().toString()));
            case StoreAddedToTrip added -> jsonMapper.writeValueAsBytes(new StoreAddedToTripPayload(
                    added.eventId().value().toString(),
                    added.tripId().value().toString(),
                    added.householdId().value().toString(),
                    added.storeId().value().toString()));
            case ItemCheckedOff checkedOff -> jsonMapper.writeValueAsBytes(new ItemStatusEventPayload(
                    checkedOff.eventId().value().toString(),
                    checkedOff.householdId().value().toString(),
                    checkedOff.listId().value().toString(),
                    checkedOff.itemId().value().toString()));
            case ItemUnchecked unchecked -> jsonMapper.writeValueAsBytes(new ItemStatusEventPayload(
                    unchecked.eventId().value().toString(),
                    unchecked.householdId().value().toString(),
                    unchecked.listId().value().toString(),
                    unchecked.itemId().value().toString()));
            case ItemDiscarded discarded -> jsonMapper.writeValueAsBytes(new ItemStatusEventPayload(
                    discarded.eventId().value().toString(),
                    discarded.householdId().value().toString(),
                    discarded.listId().value().toString(),
                    discarded.itemId().value().toString()));
            case TripCompletedForList completed -> jsonMapper.writeValueAsBytes(new TripCompletedForListPayload(
                    completed.eventId().value().toString(),
                    completed.householdId().value().toString(),
                    completed.listId().value().toString(),
                    completed.tripId().value().toString()));
            case TripCompleted completed -> jsonMapper.writeValueAsBytes(new TripCompletedPayload(
                    completed.eventId().value().toString(),
                    completed.tripId().value().toString(),
                    completed.householdId().value().toString(),
                    completed.listId().value().toString()));
            default -> throw new IllegalArgumentException("No JSON mapping for event type: " + event.getClass());
        };
    }

    DomainEvent fromJsonBytes(String typeTag, byte[] json) {
        return switch (typeTag) {
            case HOUSEHOLD_CREATED_TYPE -> {
                HouseholdCreatedPayload payload = jsonMapper.readValue(json, HouseholdCreatedPayload.class);
                yield new HouseholdCreated(
                        EventId.fromString(payload.eventId()),
                        HouseholdId.fromString(payload.householdId()),
                        new HouseholdName(payload.name()));
            }
            case HOUSEHOLD_RENAMED_TYPE -> {
                HouseholdRenamedPayload payload = jsonMapper.readValue(json, HouseholdRenamedPayload.class);
                yield new HouseholdRenamed(
                        EventId.fromString(payload.eventId()),
                        HouseholdId.fromString(payload.householdId()),
                        new HouseholdName(payload.newName()));
            }
            case MEMBER_JOINED_TYPE -> {
                MemberJoinedPayload payload = jsonMapper.readValue(json, MemberJoinedPayload.class);
                yield new MemberJoined(
                        EventId.fromString(payload.eventId()),
                        HouseholdId.fromString(payload.householdId()),
                        MemberId.fromString(payload.memberId()),
                        HouseholdRole.valueOf(payload.role()));
            }
            case STORE_ADDED_TYPE -> {
                StoreAddedPayload payload = jsonMapper.readValue(json, StoreAddedPayload.class);
                yield new StoreAdded(
                        EventId.fromString(payload.eventId()),
                        HouseholdId.fromString(payload.householdId()),
                        StoreId.fromString(payload.storeId()),
                        new StoreName(payload.name()),
                        payload.chainId() == null ? null : StoreChainId.fromString(payload.chainId()));
            }
            case STORE_ARCHIVED_TYPE -> {
                StoreArchivedPayload payload = jsonMapper.readValue(json, StoreArchivedPayload.class);
                yield new StoreArchived(
                        EventId.fromString(payload.eventId()),
                        HouseholdId.fromString(payload.householdId()),
                        StoreId.fromString(payload.storeId()));
            }
            case SHOPPING_LIST_CREATED_TYPE -> {
                ShoppingListCreatedPayload payload = jsonMapper.readValue(json, ShoppingListCreatedPayload.class);
                yield new ShoppingListCreated(
                        EventId.fromString(payload.eventId()),
                        HouseholdId.fromString(payload.householdId()),
                        ShoppingListId.fromString(payload.listId()),
                        payload.name() == null ? null : new ShoppingListName(payload.name()));
            }
            case SHOPPING_LIST_RENAMED_TYPE -> {
                ShoppingListRenamedPayload payload = jsonMapper.readValue(json, ShoppingListRenamedPayload.class);
                yield new ShoppingListRenamed(
                        EventId.fromString(payload.eventId()),
                        ShoppingListId.fromString(payload.listId()),
                        new ShoppingListName(payload.newName()));
            }
            case ITEM_ADDED_TYPE -> {
                ItemAddedPayload payload = jsonMapper.readValue(json, ItemAddedPayload.class);
                yield new ItemAdded(
                        EventId.fromString(payload.eventId()),
                        HouseholdId.fromString(payload.householdId()),
                        ShoppingListId.fromString(payload.listId()),
                        ItemId.fromString(payload.itemId()),
                        new ItemName(payload.name()),
                        payload.note() == null ? null : new ItemNote(payload.note()),
                        new Quantity(new BigDecimal(payload.amount()), Unit.valueOf(payload.unit())));
            }
            case ITEM_UPDATED_TYPE -> {
                ItemUpdatedPayload payload = jsonMapper.readValue(json, ItemUpdatedPayload.class);
                yield new ItemUpdated(
                        EventId.fromString(payload.eventId()),
                        ShoppingListId.fromString(payload.listId()),
                        ItemId.fromString(payload.itemId()),
                        new ItemName(payload.name()),
                        payload.note() == null ? null : new ItemNote(payload.note()),
                        new Quantity(new BigDecimal(payload.amount()), Unit.valueOf(payload.unit())));
            }
            case ITEM_REMOVED_TYPE -> {
                ItemRemovedPayload payload = jsonMapper.readValue(json, ItemRemovedPayload.class);
                yield new ItemRemoved(
                        EventId.fromString(payload.eventId()),
                        ShoppingListId.fromString(payload.listId()),
                        ItemId.fromString(payload.itemId()));
            }
            case ITEM_TRANSFER_INITIATED_TYPE -> {
                ItemTransferInitiatedPayload payload = jsonMapper.readValue(json, ItemTransferInitiatedPayload.class);
                yield new ItemTransferInitiated(
                        EventId.fromString(payload.eventId()),
                        HouseholdId.fromString(payload.householdId()),
                        ShoppingListId.fromString(payload.sourceListId()),
                        ItemId.fromString(payload.itemId()),
                        ShoppingListId.fromString(payload.targetListId()),
                        new ItemName(payload.name()),
                        payload.note() == null ? null : new ItemNote(payload.note()),
                        new Quantity(new BigDecimal(payload.amount()), Unit.valueOf(payload.unit())),
                        TransferOrigin.valueOf(payload.origin()));
            }
            case ITEM_TRANSFER_CONFIRMED_TYPE -> {
                ItemTransferConfirmedPayload payload = jsonMapper.readValue(json, ItemTransferConfirmedPayload.class);
                yield new ItemTransferConfirmed(
                        EventId.fromString(payload.eventId()),
                        ShoppingListId.fromString(payload.listId()),
                        ItemId.fromString(payload.itemId()));
            }
            case ITEM_TRANSFER_CANCELLED_TYPE -> {
                ItemTransferCancelledPayload payload = jsonMapper.readValue(json, ItemTransferCancelledPayload.class);
                yield new ItemTransferCancelled(
                        EventId.fromString(payload.eventId()),
                        ShoppingListId.fromString(payload.listId()),
                        ItemId.fromString(payload.itemId()),
                        TransferCancellationReason.valueOf(payload.reason()));
            }
            case ITEM_ASSIGNED_TO_STORE_TYPE -> {
                ItemAssignedToStorePayload payload = jsonMapper.readValue(json, ItemAssignedToStorePayload.class);
                yield new ItemAssignedToStore(
                        EventId.fromString(payload.eventId()),
                        HouseholdId.fromString(payload.householdId()),
                        ShoppingListId.fromString(payload.listId()),
                        ItemId.fromString(payload.itemId()),
                        StoreId.fromString(payload.storeId()));
            }
            case TRIP_STARTED_FOR_LIST_TYPE -> {
                TripStartedForListPayload payload = jsonMapper.readValue(json, TripStartedForListPayload.class);
                yield new TripStartedForList(
                        EventId.fromString(payload.eventId()),
                        HouseholdId.fromString(payload.householdId()),
                        ShoppingListId.fromString(payload.listId()),
                        TripId.fromString(payload.tripId()),
                        payload.storeIds().stream().map(StoreId::fromString).toList());
            }
            case TRIP_STARTED_TYPE -> {
                TripStartedPayload payload = jsonMapper.readValue(json, TripStartedPayload.class);
                yield new TripStarted(
                        EventId.fromString(payload.eventId()),
                        TripId.fromString(payload.tripId()),
                        HouseholdId.fromString(payload.householdId()),
                        ShoppingListId.fromString(payload.listId()),
                        payload.storeIds().stream().map(StoreId::fromString).toList());
            }
            case ITEM_REROUTED_TYPE -> {
                ItemReroutedPayload payload = jsonMapper.readValue(json, ItemReroutedPayload.class);
                yield new ItemRerouted(
                        EventId.fromString(payload.eventId()),
                        HouseholdId.fromString(payload.householdId()),
                        ShoppingListId.fromString(payload.listId()),
                        ItemId.fromString(payload.itemId()),
                        StoreId.fromString(payload.storeId()));
            }
            case STORE_ADDED_TO_TRIP_TYPE -> {
                StoreAddedToTripPayload payload = jsonMapper.readValue(json, StoreAddedToTripPayload.class);
                yield new StoreAddedToTrip(
                        EventId.fromString(payload.eventId()),
                        TripId.fromString(payload.tripId()),
                        HouseholdId.fromString(payload.householdId()),
                        StoreId.fromString(payload.storeId()));
            }
            case ITEM_CHECKED_OFF_TYPE -> {
                ItemStatusEventPayload payload = jsonMapper.readValue(json, ItemStatusEventPayload.class);
                yield new ItemCheckedOff(
                        EventId.fromString(payload.eventId()),
                        HouseholdId.fromString(payload.householdId()),
                        ShoppingListId.fromString(payload.listId()),
                        ItemId.fromString(payload.itemId()));
            }
            case ITEM_UNCHECKED_TYPE -> {
                ItemStatusEventPayload payload = jsonMapper.readValue(json, ItemStatusEventPayload.class);
                yield new ItemUnchecked(
                        EventId.fromString(payload.eventId()),
                        HouseholdId.fromString(payload.householdId()),
                        ShoppingListId.fromString(payload.listId()),
                        ItemId.fromString(payload.itemId()));
            }
            case ITEM_DISCARDED_TYPE -> {
                ItemStatusEventPayload payload = jsonMapper.readValue(json, ItemStatusEventPayload.class);
                yield new ItemDiscarded(
                        EventId.fromString(payload.eventId()),
                        HouseholdId.fromString(payload.householdId()),
                        ShoppingListId.fromString(payload.listId()),
                        ItemId.fromString(payload.itemId()));
            }
            case TRIP_COMPLETED_FOR_LIST_TYPE -> {
                TripCompletedForListPayload payload = jsonMapper.readValue(json, TripCompletedForListPayload.class);
                yield new TripCompletedForList(
                        EventId.fromString(payload.eventId()),
                        HouseholdId.fromString(payload.householdId()),
                        ShoppingListId.fromString(payload.listId()),
                        TripId.fromString(payload.tripId()));
            }
            case TRIP_COMPLETED_TYPE -> {
                TripCompletedPayload payload = jsonMapper.readValue(json, TripCompletedPayload.class);
                yield new TripCompleted(
                        EventId.fromString(payload.eventId()),
                        TripId.fromString(payload.tripId()),
                        HouseholdId.fromString(payload.householdId()),
                        ShoppingListId.fromString(payload.listId()));
            }
            default -> throw new IllegalArgumentException("Unknown event type tag: " + typeTag);
        };
    }

    private record HouseholdCreatedPayload(String eventId, String householdId, String name) {}

    private record HouseholdRenamedPayload(String eventId, String householdId, String newName) {}

    private record MemberJoinedPayload(String eventId, String householdId, String memberId, String role) {}

    /** {@code chainId} is nullable — an unlinked store round-trips a JSON {@code null} (AC2). */
    private record StoreAddedPayload(
            String eventId, String householdId, String storeId, String name, String chainId) {}

    private record StoreArchivedPayload(String eventId, String householdId, String storeId) {}

    /** {@code name} is nullable — an unnamed list round-trips a JSON {@code null} (AC1/AC2). */
    private record ShoppingListCreatedPayload(String eventId, String householdId, String listId, String name) {}

    private record ShoppingListRenamedPayload(String eventId, String listId, String newName) {}

    /** {@code note} is nullable — an item with no note round-trips a JSON {@code null} (AC1/AC2). */
    private record ItemAddedPayload(
            String eventId,
            String householdId,
            String listId,
            String itemId,
            String name,
            String note,
            String amount,
            String unit) {}

    private record ItemUpdatedPayload(
            String eventId, String listId, String itemId, String name, String note, String amount, String unit) {}

    private record ItemRemovedPayload(String eventId, String listId, String itemId) {}

    /** {@code note} is nullable — a transferred item with no note round-trips a JSON {@code null}. */
    private record ItemTransferInitiatedPayload(
            String eventId,
            String householdId,
            String sourceListId,
            String itemId,
            String targetListId,
            String name,
            String note,
            String amount,
            String unit,
            String origin) {}

    private record ItemTransferConfirmedPayload(String eventId, String listId, String itemId) {}

    private record ItemTransferCancelledPayload(String eventId, String listId, String itemId, String reason) {}

    private record ItemAssignedToStorePayload(
            String eventId, String householdId, String listId, String itemId, String storeId) {}

    private record TripStartedForListPayload(
            String eventId, String householdId, String listId, String tripId, List<String> storeIds) {}

    private record TripStartedPayload(
            String eventId, String tripId, String householdId, String listId, List<String> storeIds) {}

    private record ItemReroutedPayload(
            String eventId, String householdId, String listId, String itemId, String storeId) {}

    private record StoreAddedToTripPayload(String eventId, String tripId, String householdId, String storeId) {}

    /** Shared payload for {@code ItemCheckedOff}, {@code ItemUnchecked}, and {@code ItemDiscarded} — all carry the same four fields. */
    private record ItemStatusEventPayload(String eventId, String householdId, String listId, String itemId) {}

    private record TripCompletedForListPayload(String eventId, String householdId, String listId, String tripId) {}

    private record TripCompletedPayload(String eventId, String tripId, String householdId, String listId) {}
}
