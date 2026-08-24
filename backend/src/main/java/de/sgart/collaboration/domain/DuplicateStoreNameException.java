package de.sgart.collaboration.domain;

/**
 * Raised by {@link Household#addStore} when the requested name duplicates an <em>active</em>
 * (non-archived) store's name in the same household (AC1) — uniqueness is scoped to active stores
 * so re-adding a name after its store was archived is allowed, matching "hidden from future
 * selection".
 *
 * <p>A plain domain exception carrying no client-facing {@code code}/{@code ErrorDescriptor}: the
 * domain stays free of any outward-facing transport concern (AD-1), exactly as {@link StoreName}
 * throws a plain {@link IllegalArgumentException}. The application layer translates it into the
 * client-localizable {@code store.duplicateName} code (a {@code 409} Conflict).
 */
public final class DuplicateStoreNameException extends RuntimeException {

    public DuplicateStoreNameException(String message) {
        super(message);
    }
}
