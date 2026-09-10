package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.adapter.out.InMemoryDeviceTokenRepository;
import de.sgart.identity.domain.DevicePlatform;
import de.sgart.identity.domain.DeviceToken;
import de.sgart.identity.domain.KeycloakUserId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test (CLAUDE.md §6) for the system-driven stale-token prune (Story 4.5, AC5) — the
 * notification fan-out's counterpart to the user-driven {@link UnregisterDeviceToken}.
 */
class PruneDeviceTokenTest {

    private final InMemoryDeviceTokenRepository repository = new InMemoryDeviceTokenRepository();
    private final PruneDeviceToken pruneDeviceToken = new PruneDeviceToken(repository);

    @Test
    void prune_removesTheTokenRegardlessOfOwner() {
        repository.upsert(new DeviceToken(
                new KeycloakUserId("anna-sub"), "dead-token", DevicePlatform.ANDROID, Instant.parse("2026-09-10T10:00:00Z")));

        pruneDeviceToken.prune("dead-token");

        assertThat(repository.findByToken("dead-token")).isEmpty();
    }

    @Test
    void prune_isIdempotentForAnUnknownToken() {
        pruneDeviceToken.prune("never-registered");
    }

    @Test
    void prune_isANoOpForABlankToken() {
        pruneDeviceToken.prune(" ");
        pruneDeviceToken.prune(null);
    }
}
