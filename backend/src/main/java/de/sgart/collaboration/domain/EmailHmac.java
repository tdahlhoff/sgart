package de.sgart.collaboration.domain;

/**
 * The HMAC digest of a normalized invitee email (AD-6, Story 4.1). Holds only the hex digest
 * string — never the raw email nor any means to derive it; the raw address exists only in the
 * mutable {@code invite_email_side_store} outside the domain. Equality is by digest, so the AC2
 * duplicate-pending check ({@code Household.invitePerson}) can compare invites by their {@code
 * EmailHmac} alone. The digest is computed by the application-owned {@code InviteEmailHasher} port
 * with a stable per-deployment secret (never a per-invite salt — HMAC stability is load-bearing for
 * the duplicate check).
 */
public record EmailHmac(String digest) {

    public EmailHmac {
        if (digest == null || digest.isBlank()) {
            throw new IllegalArgumentException("digest must not be null or blank");
        }
    }
}
