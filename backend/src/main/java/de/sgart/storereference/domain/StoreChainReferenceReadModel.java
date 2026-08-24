package de.sgart.storereference.domain;

import java.util.List;

/**
 * Domain-owned port over the read-only, seeded store-chain reference data (Story 1.8, AC2). The
 * {@code JdbcStoreChainReferenceReadModel} adapter implements it; {@code ListStoreChains}
 * (application layer) reads through it. Reference data is global (household-less) and never written
 * at runtime — it is seeded by a Flyway migration (AD-4: read side only).
 */
public interface StoreChainReferenceReadModel {

    /** @return the full seeded chain reference list (no pagination — MVP convention). */
    List<StoreChainReference> listAll();
}
