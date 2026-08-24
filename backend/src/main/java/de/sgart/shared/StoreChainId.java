package de.sgart.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * Opaque reference to a store chain from the Store Reference context's seeded reference list
 * (Story 1.8, AC2). Lives in {@code shared}, not {@code storereference.domain}, precisely so the
 * Collaboration {@code Store} can carry an accepted chain link without depending on the Store
 * Reference domain (AD-2): the link is an id the client decided and supplied, never a reference to
 * a {@code storereference} type, which is what keeps the chain suggestion "never decided
 * server-side" (AC2) and the context boundary intact.
 *
 * <p>UUID-backed and carrying no domain meaning. The server never validates it against the
 * reference table — it is advisory and client-decided; the reference table is seeded with fixed
 * literal UUIDs so a stored chain id stays resolvable to a display name across environments.
 */
public record StoreChainId(UUID value) {

    public StoreChainId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static StoreChainId generate() {
        return new StoreChainId(UUID.randomUUID());
    }

    public static StoreChainId fromString(String value) {
        return new StoreChainId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
