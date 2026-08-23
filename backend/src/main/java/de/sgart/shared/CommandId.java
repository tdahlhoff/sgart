package de.sgart.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/**
 * Opaque, client-supplied identity of a command. UUID-backed and carrying no domain meaning.
 *
 * <p>The command id is what makes replay idempotent: an {@link EventStore} that has already applied
 * a {@code CommandId} to a stream collapses a second append of the same id to a silent no-op (AD-8).
 * A client-originated command carries a freshly {@link #generate() generated} id; a
 * process-manager-issued command derives its id {@link #deterministicFrom(EventId) deterministically}
 * from the triggering event, so re-processing that event never double-applies (AD-10).
 */
public record CommandId(UUID value) {

    /**
     * Fixed SGART namespace for name-based (version 5) command-id derivation. Never change it: the
     * derivation must be stable across releases, or a redelivered event would derive a different id
     * and the exactly-once guarantee would break.
     */
    private static final UUID DETERMINISTIC_NAMESPACE =
            UUID.fromString("6b1f4d2e-0c3a-5e77-9b8a-2d4c6f8a1e00");

    public CommandId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static CommandId generate() {
        return new CommandId(UUID.randomUUID());
    }

    public static CommandId fromString(String value) {
        return new CommandId(UUID.fromString(value));
    }

    /**
     * Derives a stable command id from a triggering event id: same event id always yields the same
     * command id (a pure function, no state). Implemented as a name-based UUID (version 5, SHA-1)
     * over the event id under {@link #DETERMINISTIC_NAMESPACE}. This is the exactly-once mechanism
     * for process managers (AD-10): a redelivered event derives an already-applied command id, which
     * the store's idempotency rule turns into a no-op.
     */
    public static CommandId deterministicFrom(EventId eventId) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        return new CommandId(nameBasedUuid(DETERMINISTIC_NAMESPACE, eventId.toString()));
    }

    @Override
    public String toString() {
        return value.toString();
    }

    private static UUID nameBasedUuid(UUID namespace, String name) {
        MessageDigest sha1 = sha1();
        sha1.update(toBytes(namespace));
        sha1.update(name.getBytes(StandardCharsets.UTF_8));
        byte[] hash = sha1.digest();

        byte[] uuidBytes = new byte[16];
        System.arraycopy(hash, 0, uuidBytes, 0, 16);
        uuidBytes[6] = (byte) ((uuidBytes[6] & 0x0F) | 0x50); // version 5
        uuidBytes[8] = (byte) ((uuidBytes[8] & 0x3F) | 0x80); // IETF variant

        long mostSignificant = 0;
        long leastSignificant = 0;
        for (int i = 0; i < 8; i++) {
            mostSignificant = (mostSignificant << 8) | (uuidBytes[i] & 0xFF);
        }
        for (int i = 8; i < 16; i++) {
            leastSignificant = (leastSignificant << 8) | (uuidBytes[i] & 0xFF);
        }
        return new UUID(mostSignificant, leastSignificant);
    }

    private static byte[] toBytes(UUID uuid) {
        byte[] bytes = new byte[16];
        long mostSignificant = uuid.getMostSignificantBits();
        long leastSignificant = uuid.getLeastSignificantBits();
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (mostSignificant & 0xFF);
            mostSignificant >>>= 8;
        }
        for (int i = 15; i >= 8; i--) {
            bytes[i] = (byte) (leastSignificant & 0xFF);
            leastSignificant >>>= 8;
        }
        return bytes;
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException cause) {
            throw new IllegalStateException("SHA-1 is required for name-based command ids", cause);
        }
    }
}
