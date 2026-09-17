package de.sgart.collaboration.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.adapter.out.InMemoryAccountConsentRepository;
import de.sgart.identity.application.GetConsentStatus;
import de.sgart.identity.domain.KeycloakUserId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — pure, no framework/persistence (CLAUDE.md §6). Proves the sanctioned
 * cross-context delegation (Story 7.4, design §4): {@code IdentityConsentGate} reports {@code
 * true} only once the caller has a recorded consent row, regardless of which notice version they
 * accepted (AC3 is presence-only, not version-current).
 */
class IdentityConsentGateTest {

    private static final String CALLER_ID = "anna-sub";

    private final InMemoryAccountConsentRepository accountConsentRepository = new InMemoryAccountConsentRepository();
    private final IdentityConsentGate consentGate =
            new IdentityConsentGate(new GetConsentStatus(accountConsentRepository, "2026-beta-2"));

    @Test
    void hasRecordedConsent_isFalseWhenNoConsentRowExists() {
        assertThat(consentGate.hasRecordedConsent(CALLER_ID)).isFalse();
    }

    @Test
    void hasRecordedConsent_isTrueOnceAConsentRowExists_evenOnAnOlderNoticeVersion() {
        accountConsentRepository.record(new KeycloakUserId(CALLER_ID), "2026-beta-1", Instant.now());

        assertThat(consentGate.hasRecordedConsent(CALLER_ID)).isTrue();
    }
}
