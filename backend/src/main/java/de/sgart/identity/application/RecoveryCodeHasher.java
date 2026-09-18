package de.sgart.identity.application;

/**
 * Application-owned port that hashes a plaintext one-time recovery code (Story 7.3, design §4),
 * kept identity-local rather than a cross-context dependency (Separation of Concerns). The
 * production implementation ({@code HmacSha256RecoveryCodeHasher}, {@code adapter.out}) uses a
 * <strong>stable per-deployment</strong> HMAC-SHA256 secret — never a bare SHA-256, which would be
 * offline-trivial to brute-force for a 6-digit code.
 */
public interface RecoveryCodeHasher {

    String hash(String code);
}
