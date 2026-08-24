package de.sgart.storereference.adapter.in;

import de.sgart.storereference.application.ListStoreChains;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The store-chain reference endpoint (Story 1.8, AC2): {@code GET /api/v1/store-chains} returns the
 * full seeded chain list the client caches for 100% client-side, offline-after-first-load chain
 * matching. Authenticated but <strong>household-less</strong> — reference data is global, like
 * {@code IdentityController}'s {@code /me} (both need no {@code MemberId} and touch no household).
 * {@code SecurityConfig} already secures {@code /api/v1/**}. No pagination (MVP convention).
 */
@RestController
@RequestMapping("/api/v1/store-chains")
class StoreChainController {

    private final ListStoreChains listStoreChains;

    StoreChainController(ListStoreChains listStoreChains) {
        this.listStoreChains = listStoreChains;
    }

    @GetMapping
    List<StoreChainResponse> list() {
        return listStoreChains.listAll().stream()
                .map(reference -> new StoreChainResponse(reference.chainId(), reference.name()))
                .toList();
    }

    /** Transport DTO — the exact shape the client's chain-reference cache expects. */
    record StoreChainResponse(String chainId, String name) {}
}
