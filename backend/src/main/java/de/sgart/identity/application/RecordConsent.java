package de.sgart.identity.application;

import de.sgart.identity.domain.AccountConsentRepository;
import de.sgart.identity.domain.KeycloakUserId;
import java.time.Clock;
import java.util.Objects;

/**
 * Records the caller's acceptance of the current privacy notice/terms (Story 7.4, AC1/AC2) — the
 * consent basis for the first processing of household personal data (CLAUDE.md §5). A CQRS
 * command: upserts one pseudonymous {@code account_consent} row and returns nothing beyond
 * success. A re-accept on a newer notice version overwrites the previous row (AC2, AC4).
 *
 * <p>Stamps the deployment's own {@code currentNoticeVersion} rather than trusting a
 * client-supplied version: a client-controlled value would let a caller record consent for a
 * fabricated/never-deployed version, weakening the lawful-basis proof (Story 7.4 review).
 */
public final class RecordConsent {

    private final AccountConsentRepository accountConsentRepository;
    private final Clock clock;
    private final String currentNoticeVersion;

    public RecordConsent(AccountConsentRepository accountConsentRepository, Clock clock, String currentNoticeVersion) {
        this.accountConsentRepository =
                Objects.requireNonNull(accountConsentRepository, "accountConsentRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.currentNoticeVersion =
                Objects.requireNonNull(currentNoticeVersion, "currentNoticeVersion must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5) — never accepted from the request body.
     */
    public void record(String keycloakUserId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        accountConsentRepository.record(new KeycloakUserId(keycloakUserId), currentNoticeVersion, clock.instant());
    }
}
