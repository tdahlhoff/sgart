package de.sgart.identity.adapter.out;

import de.sgart.identity.domain.AccountConsent;
import de.sgart.identity.domain.AccountConsentRepository;
import de.sgart.identity.domain.KeycloakUserId;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link AccountConsentRepository} — the fast unit-test double (CLAUDE.md §6), mirroring
 * {@link InMemoryProvisionedAccountRepository}. The durable production adapter is {@link
 * JdbcAccountConsentRepository}.
 */
public final class InMemoryAccountConsentRepository implements AccountConsentRepository {

    private final Map<KeycloakUserId, AccountConsent> consentByUser = new HashMap<>();

    @Override
    public void record(KeycloakUserId keycloakUserId, String noticeVersion, Instant acceptedAt) {
        consentByUser.put(keycloakUserId, new AccountConsent(keycloakUserId, noticeVersion, acceptedAt));
    }

    @Override
    public Optional<AccountConsent> findFor(KeycloakUserId keycloakUserId) {
        return Optional.ofNullable(consentByUser.get(keycloakUserId));
    }

    @Override
    public void deleteFor(KeycloakUserId keycloakUserId) {
        consentByUser.remove(keycloakUserId);
    }

    /** Test helper — resets this double between test methods that share one Spring context. */
    public void clear() {
        consentByUser.clear();
    }
}
