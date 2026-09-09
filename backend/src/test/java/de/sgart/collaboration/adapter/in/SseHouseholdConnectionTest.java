package de.sgart.collaboration.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.exception.LiveConnectionClosedException;
import de.sgart.shared.HouseholdId;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fast unit test — pure, no real HTTP transport (CLAUDE.md §6). Proves the SSE wire format (Story
 * 4.4, T5) and its §5 privacy guarantee (T13): the nudge frame carries only {@code householdId} +
 * the coarse {@code resource} hint, never item/list/trip/member content, and a dead emitter is
 * surfaced as {@link LiveConnectionClosedException} (T3's dead-connection tolerance depends on
 * this) rather than propagating the raw {@link IOException}.
 */
class SseHouseholdConnectionTest {

    @Test
    void changedEventJson_carriesOnlyHouseholdIdAndResource() {
        HouseholdId householdId = HouseholdId.generate();

        String json = SseHouseholdConnection.changedEventJson(householdId.toString(), "list");

        assertThat(json).isEqualTo("{\"householdId\":\"" + householdId + "\",\"resource\":\"list\"}");
    }

    @Test
    void changedEventJson_neverContainsItemListTripOrMemberContentFields() {
        String json = SseHouseholdConnection.changedEventJson(HouseholdId.generate().toString(), "members");

        assertThat(json).doesNotContain("itemId", "listId", "tripId", "memberId", "name", "note", "email");
    }

    @Test
    void sendChangedEvent_wrapsAnIOExceptionAsLiveConnectionClosedException() {
        SseEmitter failingEmitter = new SseEmitter() {
            @Override
            public void send(SseEventBuilder builder) throws IOException {
                throw new IOException("peer gone");
            }
        };
        SseHouseholdConnection connection = new SseHouseholdConnection(failingEmitter, HouseholdId.generate());

        assertThatThrownBy(() -> connection.sendChangedEvent("list"))
                .isInstanceOf(LiveConnectionClosedException.class)
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void close_isIdempotentAndSuppressesFurtherSends() {
        SseEmitter emitter = new SseEmitter();
        SseHouseholdConnection connection = new SseHouseholdConnection(emitter, HouseholdId.generate());

        connection.close();
        connection.close();

        // A send after close is a silent no-op (the connection is already gone) — never throws.
        connection.sendHeartbeat();
    }
}
