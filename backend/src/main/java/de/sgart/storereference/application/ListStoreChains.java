package de.sgart.storereference.application;

import de.sgart.storereference.domain.StoreChainReference;
import de.sgart.storereference.domain.StoreChainReferenceReadModel;
import java.util.List;
import java.util.Objects;

/**
 * The read side of the store-chain reference list (Story 1.8, AC2): the payload the client caches
 * for 100% client-side, offline-after-first-load chain matching. A pure query — no side effects
 * (CLAUDE.md §6 CQRS coverage) — reading through the domain-owned {@link
 * StoreChainReferenceReadModel} port (AD-4).
 */
public final class ListStoreChains {

    private final StoreChainReferenceReadModel storeChainReferenceReadModel;

    public ListStoreChains(StoreChainReferenceReadModel storeChainReferenceReadModel) {
        this.storeChainReferenceReadModel =
                Objects.requireNonNull(storeChainReferenceReadModel, "storeChainReferenceReadModel must not be null");
    }

    public List<ChainReference> listAll() {
        return storeChainReferenceReadModel.listAll().stream().map(ListStoreChains::toReference).toList();
    }

    private static ChainReference toReference(StoreChainReference reference) {
        return new ChainReference(reference.chainId().toString(), reference.name());
    }

    /**
     * A chain reference as seen by the caller: id + name. Plain {@code String}s, not domain types,
     * so {@code adapter.in} can consume this record without reaching into {@code
     * storereference.domain}.
     */
    public record ChainReference(String chainId, String name) {}
}
