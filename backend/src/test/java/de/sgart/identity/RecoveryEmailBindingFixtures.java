package de.sgart.identity;

import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryEmailBinding;
import de.sgart.identity.domain.RecoveryEmailBindingRepository;
import de.sgart.identity.domain.RecoveryEmailDigest;
import de.sgart.identity.domain.RecoveryEmailHint;
import java.time.Instant;

/** Shared test fixtures that put an account's recovery email binding into a given state. */
public final class RecoveryEmailBindingFixtures {

    private RecoveryEmailBindingFixtures() {}

    /** Saves a pending binding of {@code account} and confirms it, as a completed attach would. */
    public static void saveConfirmedBinding(
            RecoveryEmailBindingRepository repository,
            RecoveryEmailDigest digest,
            KeycloakUserId account,
            RecoveryEmailHint hint,
            Instant now) {
        repository.savePending(RecoveryEmailBinding.pending(digest, account, hint, now));
        repository.confirm(repository.findPendingFor(account).orElseThrow().confirm(now));
    }
}
