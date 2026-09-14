package de.sgart.keycloak.authenticator;

/**
 * The outcome of {@link DeviceSignedChallengeVerifier#verify}. Deliberately carries a {@code
 * reason} only for server-side logging — the authenticator must never let the reason leak into the
 * client-facing error, so a wrong signature and an unknown user look identical to a caller (no user
 * enumeration, Story 7.1 task list).
 */
final class ChallengeVerificationResult {

    private final boolean valid;
    private final String reason;

    private ChallengeVerificationResult(boolean valid, String reason) {
        this.valid = valid;
        this.reason = reason;
    }

    static ChallengeVerificationResult accepted() {
        return new ChallengeVerificationResult(true, null);
    }

    static ChallengeVerificationResult rejected(String reason) {
        return new ChallengeVerificationResult(false, reason);
    }

    boolean isValid() {
        return valid;
    }

    String reason() {
        return reason;
    }
}
