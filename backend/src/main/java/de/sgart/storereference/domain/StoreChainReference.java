package de.sgart.storereference.domain;

import de.sgart.shared.StoreChainId;

/**
 * One entry in the seeded store-chain reference list (Story 1.8, AC2) — an id and a display name.
 * Not personal data: a shop brand, not a person. The {@link StoreChainId} lives in {@code shared}
 * so the Collaboration {@code Store} can carry an accepted chain link without depending on this
 * context (AD-2).
 */
public record StoreChainReference(StoreChainId chainId, String name) {}
