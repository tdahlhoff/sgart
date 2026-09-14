package de.sgart.keycloak.authenticator;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Fast, pure unit test (no Keycloak server) for the Story 7.1 challenge-verification crux (AC2,
 * D-B): a valid Ed25519-signed {@code username|timestamp|nonce} is accepted; a wrong signature, a
 * stale timestamp, and a replayed nonce are each rejected.
 */
class DeviceSignedChallengeVerifierTest {

    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");
    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final String USERNAME = "device-username";

    @Test
    void verify_aValidlySignedFreshChallenge_isAccepted() throws Exception {
        KeyPair keyPair = generateEd25519KeyPair();
        String timestamp = String.valueOf(NOW.getEpochSecond());
        String nonce = "nonce-1";
        String signature = sign(keyPair.getPrivate(), USERNAME, timestamp, nonce);

        ChallengeVerificationResult result = DeviceSignedChallengeVerifier.verify(
                USERNAME,
                base64Url(keyPair.getPublic().getEncoded()),
                timestamp,
                nonce,
                signature,
                NOW,
                WINDOW,
                new NonceSeenCache(WINDOW));

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void directGrantWithWrongSignature_isRejected() throws Exception {
        KeyPair signingKeyPair = generateEd25519KeyPair();
        KeyPair unrelatedKeyPair = generateEd25519KeyPair();
        String timestamp = String.valueOf(NOW.getEpochSecond());
        String nonce = "nonce-2";
        // Signed with a DIFFERENT private key than the one registered as the public key below.
        String signature = sign(signingKeyPair.getPrivate(), USERNAME, timestamp, nonce);

        ChallengeVerificationResult result = DeviceSignedChallengeVerifier.verify(
                USERNAME,
                base64Url(unrelatedKeyPair.getPublic().getEncoded()),
                timestamp,
                nonce,
                signature,
                NOW,
                WINDOW,
                new NonceSeenCache(WINDOW));

        assertThat(result.isValid()).isFalse();
    }

    @Test
    void verify_anAbsentSignature_isRejected() throws Exception {
        KeyPair keyPair = generateEd25519KeyPair();

        ChallengeVerificationResult result = DeviceSignedChallengeVerifier.verify(
                USERNAME,
                base64Url(keyPair.getPublic().getEncoded()),
                String.valueOf(NOW.getEpochSecond()),
                "nonce-3",
                null,
                NOW,
                WINDOW,
                new NonceSeenCache(WINDOW));

        assertThat(result.isValid()).isFalse();
    }

    @Test
    void directGrantWithReplayedNonce_isRejected() throws Exception {
        KeyPair keyPair = generateEd25519KeyPair();
        String timestamp = String.valueOf(NOW.getEpochSecond());
        String nonce = "nonce-4";
        String signature = sign(keyPair.getPrivate(), USERNAME, timestamp, nonce);
        String publicKey = base64Url(keyPair.getPublic().getEncoded());
        NonceSeenCache sharedCache = new NonceSeenCache(WINDOW);

        ChallengeVerificationResult first =
                DeviceSignedChallengeVerifier.verify(USERNAME, publicKey, timestamp, nonce, signature, NOW, WINDOW, sharedCache);
        ChallengeVerificationResult replay = DeviceSignedChallengeVerifier.verify(
                USERNAME, publicKey, timestamp, nonce, signature, NOW.plusSeconds(1), WINDOW, sharedCache);

        assertThat(first.isValid()).isTrue();
        assertThat(replay.isValid()).isFalse();
    }

    @Test
    void verify_aTimestampOutsideTheWindow_isRejected() throws Exception {
        KeyPair keyPair = generateEd25519KeyPair();
        Instant staleTimestamp = NOW.minus(WINDOW).minusSeconds(1);
        String timestamp = String.valueOf(staleTimestamp.getEpochSecond());
        String nonce = "nonce-5";
        String signature = sign(keyPair.getPrivate(), USERNAME, timestamp, nonce);

        ChallengeVerificationResult result = DeviceSignedChallengeVerifier.verify(
                USERNAME,
                base64Url(keyPair.getPublic().getEncoded()),
                timestamp,
                nonce,
                signature,
                NOW,
                WINDOW,
                new NonceSeenCache(WINDOW));

        assertThat(result.isValid()).isFalse();
    }

    @Test
    void verify_aSignatureOverADifferentUsername_isRejected() throws Exception {
        // The signature is valid, but over the wrong message (a signature does not transfer across
        // usernames even with the correct key) — proves the message is bound, not just the key.
        KeyPair keyPair = generateEd25519KeyPair();
        String timestamp = String.valueOf(NOW.getEpochSecond());
        String nonce = "nonce-6";
        String signature = sign(keyPair.getPrivate(), "a-different-username", timestamp, nonce);

        ChallengeVerificationResult result = DeviceSignedChallengeVerifier.verify(
                USERNAME,
                base64Url(keyPair.getPublic().getEncoded()),
                timestamp,
                nonce,
                signature,
                NOW,
                WINDOW,
                new NonceSeenCache(WINDOW));

        assertThat(result.isValid()).isFalse();
    }

    /** The JVM's raw Ed25519 key encoding is already the X.509 SubjectPublicKeyInfo DER form — the
     * verifier expects the <em>raw 32-byte</em> key instead (matching what a device actually
     * generates and sends), so tests strip the fixed 12-byte prefix back off here. */
    private static String base64Url(byte[] x509EncodedEd25519PublicKey) {
        byte[] rawPublicKey = new byte[32];
        System.arraycopy(x509EncodedEd25519PublicKey, x509EncodedEd25519PublicKey.length - 32, rawPublicKey, 0, 32);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(rawPublicKey);
    }

    private static String sign(PrivateKey privateKey, String username, String timestamp, String nonce)
            throws Exception {
        String message = username + "|" + timestamp + "|" + nonce;
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    }

    private static KeyPair generateEd25519KeyPair() throws NoSuchAlgorithmException {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }
}
