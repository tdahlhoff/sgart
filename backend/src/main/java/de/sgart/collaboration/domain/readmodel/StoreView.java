package de.sgart.collaboration.domain.readmodel;

import de.sgart.collaboration.domain.StoreName;
import de.sgart.shared.StoreChainId;
import de.sgart.shared.StoreId;

/**
 * A store as held in the read model (AD-4) — id, display name, and the optional accepted chain
 * (Story 1.8). {@code chainId} is nullable: an unlinked store has no chain, and the client resolves
 * a present chain id to a display name from its cached reference list (single source of chain names
 * = the reference list, DRY). Read-only projection shape, distinct from the aggregate's internal
 * store state.
 */
public record StoreView(StoreId storeId, StoreName name, StoreChainId chainId) {}
