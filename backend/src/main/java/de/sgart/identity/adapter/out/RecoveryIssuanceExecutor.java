package de.sgart.identity.adapter.out;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * The executor that runs a recovery code issuance off the request thread. A named wrapper rather
 * than a bare {@link Executor} bean, because a user-defined {@code Executor} bean would switch off
 * Spring Boot's own task executor; tests substitute a synchronous one.
 */
public record RecoveryIssuanceExecutor(Executor executor) {

    public RecoveryIssuanceExecutor {
        Objects.requireNonNull(executor, "executor must not be null");
    }
}
