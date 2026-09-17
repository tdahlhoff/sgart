package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Fast unit test — no framework or persistence (CLAUDE.md §6). Proves Story 7.3's AD-6-style
 * stability guarantee, and (AC1 HMAC / {@code emailRecoveryCode_persistsHmacNotPlaintext}) that
 * the digest is never the plaintext code itself.
 */
class HmacSha256RecoveryCodeHasherTest {

    private static final String SECRET = "test-secret-do-not-use-in-production";

    @Test
    void hash_isStableForTheSameCodeUnderTheSameSecret() {
        HmacSha256RecoveryCodeHasher hasher = new HmacSha256RecoveryCodeHasher(SECRET);

        assertThat(hasher.hash("042817")).isEqualTo(hasher.hash("042817"));
    }

    @Test
    void hash_producesDifferentDigestsForDifferentCodes() {
        HmacSha256RecoveryCodeHasher hasher = new HmacSha256RecoveryCodeHasher(SECRET);

        assertThat(hasher.hash("042817")).isNotEqualTo(hasher.hash("999999"));
    }

    @Test
    void emailRecoveryCode_persistsHmacNotPlaintext() {
        HmacSha256RecoveryCodeHasher hasher = new HmacSha256RecoveryCodeHasher(SECRET);

        assertThat(hasher.hash("042817")).isNotEqualTo("042817").doesNotContain("042817");
    }

    @Test
    void constructor_rejectsABlankSecret() {
        assertThatThrownBy(() -> new HmacSha256RecoveryCodeHasher("   ")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructor_rejectsANullSecret() {
        assertThatThrownBy(() -> new HmacSha256RecoveryCodeHasher(null)).isInstanceOf(IllegalStateException.class);
    }
}
