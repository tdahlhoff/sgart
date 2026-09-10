package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.application.ChangeNudge;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serializes a {@link ChangeNudge} to JSON — the exact wire shape a push transport sends (Story
 * 4.5, AC1). Lives in {@code adapter.out} alongside {@link DomainEventJsonCodec} (the only place
 * that knows about JSON, AD-1). {@code ChangeNudgeJsonCodecTest} is the privacy contract test:
 * asserts the output carries only {@code householdId}/{@code resource}, nothing else.
 */
final class ChangeNudgeJsonCodec {

    private final JsonMapper jsonMapper = new JsonMapper();

    byte[] toJsonBytes(ChangeNudge nudge) {
        return jsonMapper.writeValueAsBytes(
                new ChangeNudgePayload(nudge.householdId().value().toString(), nudge.resource()));
    }

    private record ChangeNudgePayload(String householdId, String resource) {}
}
