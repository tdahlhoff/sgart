package de.sgart.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * Cross-context identifier for a pending household invite (Story 4.1). Mirrors {@link StoreId}:
 * UUID-backed, no domain meaning (AD-5), minted client-side and carried in the invite command
 * envelope so the {@code POST} response needs no body (read-your-writes without waiting on a
 * projection).
 */
public record InviteId(UUID value) {

    public InviteId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static InviteId generate() {
        return new InviteId(UUID.randomUUID());
    }

    public static InviteId fromString(String value) {
        return new InviteId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
