package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.adapter.out.InMemoryAccountConsentRepository;
import de.sgart.identity.domain.AccountConsent;
import de.sgart.identity.domain.KeycloakUserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — in-memory {@link InMemoryAccountConsentRepository}, no framework (CLAUDE.md
 * §6). Proves Story 7.4, AC1/AC2: recording consent stores the accepted notice version and
 * timestamp, and a re-accept on a newer version overwrites the row (one current consent per
 * account).
 */
class RecordConsentTest {

    private static final Instant NOW = Instant.parse("2026-09-17T10:00:00Z");
    private static final String CALLER_ID = "anna-sub";
    private static final KeycloakUserId CALLER = new KeycloakUserId(CALLER_ID);

    private final InMemoryAccountConsentRepository accountConsentRepository = new InMemoryAccountConsentRepository();
    private final RecordConsent recordConsent =
            new RecordConsent(accountConsentRepository, Clock.fixed(NOW, ZoneOffset.UTC), "2026-beta-1");

    @Test
    void recordConsent_storesTheAcceptedNoticeVersionAndTimestamp() {
        recordConsent.record(CALLER_ID);

        assertThat(accountConsentRepository.findFor(CALLER))
                .contains(new AccountConsent(CALLER, "2026-beta-1", NOW));
    }

    @Test
    void recordConsent_reAcceptWithNewerVersion_overwritesTheRow() {
        recordConsent.record(CALLER_ID);
        Instant later = NOW.plusSeconds(3600);
        RecordConsent reConsent =
                new RecordConsent(accountConsentRepository, Clock.fixed(later, ZoneOffset.UTC), "2026-beta-2");

        reConsent.record(CALLER_ID);

        assertThat(accountConsentRepository.findFor(CALLER))
                .contains(new AccountConsent(CALLER, "2026-beta-2", later));
    }
}
