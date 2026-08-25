package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.HouseholdRole;
import de.sgart.collaboration.domain.StoreName;
import de.sgart.collaboration.domain.event.HouseholdCreated;
import de.sgart.collaboration.domain.event.HouseholdRenamed;
import de.sgart.collaboration.domain.event.MemberJoined;
import de.sgart.collaboration.domain.event.StoreAdded;
import de.sgart.collaboration.domain.event.StoreArchived;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StoreChainId;
import de.sgart.shared.StoreId;
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

    private final JsonMapper jsonMapper = new JsonMapper();

    String typeTagFor(DomainEvent event) {
        return switch (event) {
            case HouseholdCreated ignored -> HOUSEHOLD_CREATED_TYPE;
            case HouseholdRenamed ignored -> HOUSEHOLD_RENAMED_TYPE;
            case MemberJoined ignored -> MEMBER_JOINED_TYPE;
            case StoreAdded ignored -> STORE_ADDED_TYPE;
            case StoreArchived ignored -> STORE_ARCHIVED_TYPE;
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
}
