package de.sgart.identity.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * The lawful-basis record for processing household personal data (Story 7.4, design §2) — a
 * pseudonymous row mirroring {@link ProvisionedAccount} exactly: the {@link KeycloakUserId}
 * (already held in {@link MemberMapping}), the accepted {@code noticeVersion} (D-D, D-E), and the
 * audit timestamp {@code acceptedAt} (CLAUDE.md §5 auditability). No PII (AD-6) — no name, email,
 * or IP column, matching the {@code ProvisionedAccount}/{@code recovery_code} discipline.
 *
 * <p>One current consent per account: a re-accept on a newer notice version overwrites this row
 * (upsert), the same discipline {@link ProvisionedAccountRepository} and the recovery-code store
 * use. Revocation is not modeled here — it routes to the Epic 6 erasure path (design §7,
 * {@link AccountConsentRepository#deleteFor(KeycloakUserId)}).
 */
public record AccountConsent(KeycloakUserId keycloakUserId, String noticeVersion, Instant acceptedAt) {

    public AccountConsent {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        Objects.requireNonNull(noticeVersion, "noticeVersion must not be null");
        if (noticeVersion.isBlank()) {
            throw new IllegalArgumentException("noticeVersion must not be blank");
        }
        Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
    }
}
