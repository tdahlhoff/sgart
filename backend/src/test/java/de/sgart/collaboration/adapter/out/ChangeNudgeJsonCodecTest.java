package de.sgart.collaboration.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.application.ChangeNudge;
import de.sgart.shared.HouseholdId;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The privacy contract test (Story 4.5, AC1): a content-free push payload carries <strong>only</strong>
 * {@code householdId} and {@code resource} — never item/list/receipt/store content, never PII.
 * Asserts the exact key set of the serialized JSON, not merely the absence of a few forbidden
 * substrings (a substring check would be unsound here: {@code resource}'s own legitimate value can
 * literally be {@code "list"}).
 */
class ChangeNudgeJsonCodecTest {

    private final ChangeNudgeJsonCodec codec = new ChangeNudgeJsonCodec();
    private final JsonMapper jsonMapper = new JsonMapper();

    @Test
    void toJsonBytes_serializesOnlyHouseholdIdAndResource() {
        HouseholdId householdId = HouseholdId.generate();
        ChangeNudge nudge = new ChangeNudge(householdId, "list");

        byte[] json = codec.toJsonBytes(nudge);

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = jsonMapper.readValue(json, Map.class);
        assertThat(parsed.keySet()).containsExactlyInAnyOrder("householdId", "resource");
        assertThat(parsed.get("householdId")).isEqualTo(householdId.value().toString());
        assertThat(parsed.get("resource")).isEqualTo("list");
    }

    @Test
    void toJsonBytes_roundTripsEveryResourceValue() {
        for (String resource : new String[] {"list", "trip", "members", "household"}) {
            HouseholdId householdId = HouseholdId.generate();
            byte[] json = codec.toJsonBytes(new ChangeNudge(householdId, resource));

            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = jsonMapper.readValue(json, Map.class);
            assertThat(parsed).containsExactlyInAnyOrderEntriesOf(
                    Map.of("householdId", householdId.value().toString(), "resource", resource));
        }
    }

    @Test
    void toJsonBytes_isUtf8AndHumanReadableForLogging() {
        ChangeNudge nudge = new ChangeNudge(HouseholdId.generate(), "trip");

        String json = new String(codec.toJsonBytes(nudge), StandardCharsets.UTF_8);

        assertThat(json).contains("\"resource\":\"trip\"");
    }
}
