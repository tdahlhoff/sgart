package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.domain.HouseholdCreated;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.HouseholdRenamed;
import de.sgart.collaboration.domain.HouseholdRole;
import de.sgart.collaboration.domain.MemberJoined;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
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

    private final JsonMapper jsonMapper = new JsonMapper();

    String typeTagFor(DomainEvent event) {
        return switch (event) {
            case HouseholdCreated ignored -> HOUSEHOLD_CREATED_TYPE;
            case HouseholdRenamed ignored -> HOUSEHOLD_RENAMED_TYPE;
            case MemberJoined ignored -> MEMBER_JOINED_TYPE;
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
            default -> throw new IllegalArgumentException("Unknown event type tag: " + typeTag);
        };
    }

    private record HouseholdCreatedPayload(String eventId, String householdId, String name) {}

    private record HouseholdRenamedPayload(String eventId, String householdId, String newName) {}

    private record MemberJoinedPayload(String eventId, String householdId, String memberId, String role) {}
}
