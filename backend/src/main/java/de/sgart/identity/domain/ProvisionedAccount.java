package de.sgart.identity.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * The account-lifecycle shell created before any household exists (Story 7.1) — a silent,
 * zero-input Keycloak account bound to a device's public key. Carries the data-minimization set
 * only: the pseudonymous {@link KeycloakUserId} (already held in {@link MemberMapping}) and
 * {@code provisionedAt}, the retention sweep's clock anchor (AD-6, no PII).
 *
 * <p>"Activated" is deliberately <strong>not</strong> a field here — it is derived by checking
 * whether a {@link MemberMapping} exists for this account's {@link KeycloakUserId} (and, from a
 * later story, whether an email is attached). Storing an {@code activatedAt} column would be a
 * second, easily-stale source of truth for the same fact (DRY/YAGNI).
 */
public record ProvisionedAccount(KeycloakUserId keycloakUserId, Instant provisionedAt) {

    public ProvisionedAccount {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        Objects.requireNonNull(provisionedAt, "provisionedAt must not be null");
    }
}
