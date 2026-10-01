package de.sgart.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RecoveryEmailDigestTest {

    @Test
    void digest_rejectsBlankInput() {
        assertThatThrownBy(() -> new RecoveryEmailDigest("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void digest_withTheSameValue_isEqual() {
        assertThat(new RecoveryEmailDigest("abc")).isEqualTo(new RecoveryEmailDigest("abc"));
    }

    @Test
    void toString_doesNotRevealTheDigestValue() {
        assertThat(new RecoveryEmailDigest("secret-digest-value").toString()).doesNotContain("secret-digest-value");
    }
}
