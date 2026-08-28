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
import de.sgart.collaboration.domain.event.ItemMovedToList;
import de.sgart.collaboration.domain.event.ItemRemoved;
import de.sgart.collaboration.domain.event.ItemUpdated;
import de.sgart.collaboration.domain.event.MemberJoined;
import de.sgart.collaboration.domain.event.ShoppingListCreated;
import de.sgart.collaboration.domain.event.ShoppingListRenamed;
import de.sgart.collaboration.domain.event.StoreAdded;
import de.sgart.collaboration.domain.event.StoreArchived;
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
    static final String ITEM_MOVED_TO_LIST_TYPE = "ItemMovedToList";
    static final String ITEM_ASSIGNED_TO_STORE_TYPE = "ItemAssignedToStore";

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
            case ItemMovedToList ignored -> ITEM_MOVED_TO_LIST_TYPE;
            case ItemAssignedToStore ignored -> ITEM_ASSIGNED_TO_STORE_TYPE;
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
            case ItemMovedToList moved -> jsonMapper.writeValueAsBytes(new ItemMovedToListPayload(
                    moved.eventId().value().toString(),
                    moved.householdId().value().toString(),
                    moved.sourceListId().value().toString(),
                    moved.itemId().value().toString(),
                    moved.targetListId().value().toString(),
                    moved.name().value(),
                    moved.note() == null ? null : moved.note().value(),
                    moved.quantity().amount().toPlainString(),
                    moved.quantity().unit().name()));
            case ItemAssignedToStore assigned -> jsonMapper.writeValueAsBytes(new ItemAssignedToStorePayload(
                    assigned.eventId().value().toString(),
                    assigned.householdId().value().toString(),
                    assigned.listId().value().toString(),
                    assigned.itemId().value().toString(),
                    assigned.storeId().value().toString()));
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
            case ITEM_MOVED_TO_LIST_TYPE -> {
                ItemMovedToListPayload payload = jsonMapper.readValue(json, ItemMovedToListPayload.class);
                yield new ItemMovedToList(
                        EventId.fromString(payload.eventId()),
                        HouseholdId.fromString(payload.householdId()),
                        ShoppingListId.fromString(payload.sourceListId()),
                        ItemId.fromString(payload.itemId()),
                        ShoppingListId.fromString(payload.targetListId()),
                        new ItemName(payload.name()),
                        payload.note() == null ? null : new ItemNote(payload.note()),
                        new Quantity(new BigDecimal(payload.amount()), Unit.valueOf(payload.unit())));
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

    /** {@code note} is nullable — a moved item with no note round-trips a JSON {@code null} (AC9). */
    private record ItemMovedToListPayload(
            String eventId,
            String householdId,
            String sourceListId,
            String itemId,
            String targetListId,
            String name,
            String note,
            String amount,
            String unit) {}

    private record ItemAssignedToStorePayload(
            String eventId, String householdId, String listId, String itemId, String storeId) {}
}
