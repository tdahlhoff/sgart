package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.adapter.out.InMemoryAccountConsentRepository;
import de.sgart.identity.domain.KeycloakUserId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — in-memory {@link InMemoryAccountConsentRepository}, no framework (CLAUDE.md
 * §6). Proves Story 7.4, AC2/AC4: side-effect-free reporting of the caller's consent status
 * against the deployment's current notice version.
 */
class GetConsentStatusTest {

    private static final String CALLER_ID = "anna-sub";
    private final InMemoryAccountConsentRepository accountConsentRepository = new InMemoryAccountConsentRepository();

    @Test
    void getConsentStatus_noRow_returnsNotAccepted() {
        GetConsentStatus getConsentStatus = new GetConsentStatus(accountConsentRepository, "2026-beta-1");

        ConsentStatus status = getConsentStatus.statusFor(CALLER_ID);

        assertThat(status.accepted()).isFalse();
        assertThat(status.acceptedVersion()).isNull();
        assertThat(status.currentVersion()).isEqualTo("2026-beta-1");
    }

    @Test
    void getConsentStatus_returnsTheConfiguredCurrentVersion() {
        accountConsentRepository.record(new KeycloakUserId(CALLER_ID), "2026-beta-1", Instant.now());
        GetConsentStatus getConsentStatus = new GetConsentStatus(accountConsentRepository, "2026-beta-2");

        ConsentStatus status = getConsentStatus.statusFor(CALLER_ID);

        assertThat(status.accepted()).isTrue();
        assertThat(status.acceptedVersion()).isEqualTo("2026-beta-1");
        assertThat(status.currentVersion()).isEqualTo("2026-beta-2");
    }

    @Test
    void getConsentStatus_isSideEffectFree_repeatedCallsDoNotChangeTheStoredRow() {
        GetConsentStatus getConsentStatus = new GetConsentStatus(accountConsentRepository, "2026-beta-1");

        getConsentStatus.statusFor(CALLER_ID);
        getConsentStatus.statusFor(CALLER_ID);

        assertThat(accountConsentRepository.findFor(new KeycloakUserId(CALLER_ID)))
                .isEmpty();
    }
}
