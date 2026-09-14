package de.sgart.keycloak.authenticator;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * The Story 7.1 (D-B) challenge-verification crux, deliberately free of any {@code org.keycloak}
 * type so it is unit-testable with plain JUnit — no Keycloak server, no {@code
 * AuthenticationFlowContext} (CLAUDE.md §6, "domain first" / "isolate external systems" applied
 * here to a Keycloak SPI rather than a Spring adapter, since this module has no domain/application
 * split of its own).
 *
 * <p>Verifies that {@code signature} is a valid Ed25519 signature — by {@code publicKey} — over the
 * exact message {@code username|timestampEpochSeconds|nonce} (the crux: the nonce and timestamp
 * ride <em>inside</em> the signed payload, not a separate round-trip), that {@code
 * timestampEpochSeconds} falls within {@code allowedClockSkew} of {@code now}, and that {@code
 * (username, nonce)} has not been seen before (via {@link NonceSeenCache}).
 *
 * <p>The Ed25519 public key travels as raw 32 bytes (RFC 8032), base64url-encoded, exactly as
 * generated on the device and registered as the Keycloak user's {@code publicKey} attribute (D-D).
 * The JVM's {@code KeyFactory}/{@code Signature} for {@code "Ed25519"} (native since JDK 15, no
 * third-party crypto lib needed) require an X.509 {@code SubjectPublicKeyInfo} encoding, so the raw
 * key is wrapped in the fixed 12-byte RFC 8410 DER prefix before use.
 */
final class DeviceSignedChallengeVerifier {

    private static final int ED25519_PUBLIC_KEY_LENGTH_BYTES = 32;

    /** RFC 8410 {@code SubjectPublicKeyInfo} prefix for a raw Ed25519 public key (OID 1.3.101.112):
     * {@code SEQUENCE { SEQUENCE { OID id-Ed25519 }, BIT STRING { 0x00, <32 raw key bytes> } }}. */
    private static final byte[] X509_ED25519_PREFIX = {
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    private DeviceSignedChallengeVerifier() {}

    static ChallengeVerificationResult verify(
            String username,
            String publicKeyBase64Url,
            String timestampEpochSeconds,
            String nonce,
            String signatureBase64Url,
            Instant now,
            Duration allowedClockSkew,
            NonceSeenCache nonceSeenCache) {

        if (isBlank(username)
                || isBlank(publicKeyBase64Url)
                || isBlank(timestampEpochSeconds)
                || isBlank(nonce)
                || isBlank(signatureBase64Url)) {
            return ChallengeVerificationResult.rejected("missing required challenge field");
        }

        Instant timestamp;
        try {
            timestamp = Instant.ofEpochSecond(Long.parseLong(timestampEpochSeconds));
        } catch (NumberFormatException notANumber) {
            return ChallengeVerificationResult.rejected("timestamp is not a number");
        }
        if (Duration.between(timestamp, now).abs().compareTo(allowedClockSkew) > 0) {
            return ChallengeVerificationResult.rejected("timestamp outside the allowed window");
        }

        byte[] publicKeyBytes;
        byte[] signatureBytes;
        try {
            publicKeyBytes = Base64.getUrlDecoder().decode(publicKeyBase64Url);
            signatureBytes = Base64.getUrlDecoder().decode(signatureBase64Url);
        } catch (IllegalArgumentException notBase64Url) {
            return ChallengeVerificationResult.rejected("public key or signature is not valid base64url");
        }
        if (publicKeyBytes.length != ED25519_PUBLIC_KEY_LENGTH_BYTES) {
            return ChallengeVerificationResult.rejected("public key is not a 32-byte Ed25519 key");
        }

        String message = username + "|" + timestampEpochSeconds + "|" + nonce;
        if (!verifyEd25519Signature(publicKeyBytes, message.getBytes(StandardCharsets.UTF_8), signatureBytes)) {
            return ChallengeVerificationResult.rejected("signature does not match");
        }

        // Only a verified-valid challenge consumes the nonce: recording it earlier (e.g. right
        // after the timestamp check) would let a bad-signature attempt on an observed
        // (username, nonce) permanently burn that nonce and block the legitimate device's real
        // login attempt using it.
        if (!nonceSeenCache.recordIfUnseen(username, nonce, now)) {
            return ChallengeVerificationResult.rejected("nonce already used (replay)");
        }

        return ChallengeVerificationResult.accepted();
    }

    private static boolean verifyEd25519Signature(byte[] rawPublicKeyBytes, byte[] message, byte[] signature) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
            PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(toX509(rawPublicKeyBytes)));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(message);
            return verifier.verify(signature);
        } catch (GeneralSecurityException | IllegalArgumentException invalidKeyOrSignature) {
            // A malformed key/signature is a rejection, not a server error (fail fast, never 500 —
            // mirrored here as "never an authenticator crash").
            return false;
        }
    }

    private static byte[] toX509(byte[] rawEd25519PublicKeyBytes) {
        byte[] encoded = new byte[X509_ED25519_PREFIX.length + rawEd25519PublicKeyBytes.length];
        System.arraycopy(X509_ED25519_PREFIX, 0, encoded, 0, X509_ED25519_PREFIX.length);
        System.arraycopy(
                rawEd25519PublicKeyBytes, 0, encoded, X509_ED25519_PREFIX.length, rawEd25519PublicKeyBytes.length);
        return encoded;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
