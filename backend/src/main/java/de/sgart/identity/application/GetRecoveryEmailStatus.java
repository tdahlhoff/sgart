package de.sgart.identity.application;

import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryEmailBinding;
import de.sgart.identity.domain.RecoveryEmailBindingRepository;
import de.sgart.identity.domain.RecoveryEmailHint;
import java.util.Objects;

/**
 * Query: which recovery email, if any, the caller has attached. Only a confirmed binding is
 * reported; a pending one grants nothing and is never listed. Side-effect free.
 */
public final class GetRecoveryEmailStatus {

    private final RecoveryEmailBindingRepository recoveryEmailBindingRepository;

    public GetRecoveryEmailStatus(RecoveryEmailBindingRepository recoveryEmailBindingRepository) {
        this.recoveryEmailBindingRepository = Objects.requireNonNull(
                recoveryEmailBindingRepository, "recoveryEmailBindingRepository must not be null");
    }

    public RecoveryEmailStatus statusFor(String keycloakUserId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        String addressHint = recoveryEmailBindingRepository
                .findConfirmedFor(new KeycloakUserId(keycloakUserId))
                .map(RecoveryEmailBinding::hint)
                .map(RecoveryEmailHint::value)
                .orElse(null);
        return new RecoveryEmailStatus(addressHint);
    }
}
