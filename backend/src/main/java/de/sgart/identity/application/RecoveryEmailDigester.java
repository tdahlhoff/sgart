package de.sgart.identity.application;

import de.sgart.identity.domain.RecoveryEmailDigest;

/** Application-owned port that turns a normalized recovery address into its keyed one-way digest. */
public interface RecoveryEmailDigester {

    RecoveryEmailDigest digest(String normalizedAddress);
}
