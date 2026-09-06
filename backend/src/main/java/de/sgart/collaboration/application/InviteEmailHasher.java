package de.sgart.collaboration.application;

import de.sgart.collaboration.domain.EmailHmac;

/**
 * Application-owned port that hashes a {@link NormalizedEmail} into the {@link EmailHmac} digest
 * the domain carries (AD-6, Story 4.1). The production implementation ({@code
 * HmacSha256InviteEmailHasher}, {@code adapter.out}) uses a <strong>stable per-deployment</strong>
 * secret — never a per-invite salt, since the same email must always hash to the same digest for
 * the AC2 duplicate-pending check to hold.
 */
public interface InviteEmailHasher {

    EmailHmac hash(NormalizedEmail normalizedEmail);
}
