package de.sgart.identity.application;

import de.sgart.identity.domain.AccountConsent;
import de.sgart.identity.domain.AccountConsentRepository;
import de.sgart.identity.domain.KeycloakUserId;
import java.util.Objects;
import java.util.Optional;

/**
 * The consent query (Story 7.4, AC2/AC4) — side-effect-free: reads the caller's consent row (if
 * any) plus the deployment's current notice version (D-E) and reports whether a re-consent is due.
 * This is also the query the {@code collaboration} context's {@code ConsentGate} port delegates
 * to (design §4) — the one sanctioned synchronous cross-context read.
 */
public final class GetConsentStatus {

    private final AccountConsentRepository accountConsentRepository;
    private final String currentNoticeVersion;

    public GetConsentStatus(AccountConsentRepository accountConsentRepository, String currentNoticeVersion) {
        this.accountConsentRepository =
                Objects.requireNonNull(accountConsentRepository, "accountConsentRepository must not be null");
        this.currentNoticeVersion =
                Objects.requireNonNull(currentNoticeVersion, "currentNoticeVersion must not be null");
    }

    public ConsentStatus statusFor(String keycloakUserId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        Optional<AccountConsent> consent = accountConsentRepository.findFor(new KeycloakUserId(keycloakUserId));
        return new ConsentStatus(
                consent.isPresent(), consent.map(AccountConsent::noticeVersion).orElse(null), currentNoticeVersion);
    }
}
