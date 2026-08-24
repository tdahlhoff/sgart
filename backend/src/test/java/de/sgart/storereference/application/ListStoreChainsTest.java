package de.sgart.storereference.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.shared.StoreChainId;
import de.sgart.storereference.application.ListStoreChains.ChainReference;
import de.sgart.storereference.domain.StoreChainReference;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — pure, no framework or persistence (CLAUDE.md §6). Proves the reference query
 * (AC2) maps domain reference entries to plain-string DTOs and is side-effect free.
 */
class ListStoreChainsTest {

    @Test
    void listAll_returnsTheReferenceEntriesAsPlainStringDtos() {
        StoreChainId edekaId = StoreChainId.generate();
        StoreChainId reweId = StoreChainId.generate();
        ListStoreChains listStoreChains = new ListStoreChains(
                () -> List.of(new StoreChainReference(edekaId, "Edeka"), new StoreChainReference(reweId, "Rewe")));

        List<ChainReference> references = listStoreChains.listAll();

        assertThat(references)
                .containsExactly(
                        new ChainReference(edekaId.toString(), "Edeka"),
                        new ChainReference(reweId.toString(), "Rewe"));
    }

    @Test
    void listAll_returnsEmptyWhenTheReferenceListIsEmpty() {
        ListStoreChains listStoreChains = new ListStoreChains(List::of);

        assertThat(listStoreChains.listAll()).isEmpty();
    }
}
