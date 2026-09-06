package de.sgart.collaboration.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.application.NormalizedEmail;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — no framework or persistence (CLAUDE.md §6). Proves the AD-6 stability guarantee
 * (Story 4.1, T9): the same normalized email always hashes to the same digest under a fixed secret
 * (what makes the AC2 duplicate-pending check work), a different email hashes differently, and a
 * different secret changes the digest — the secret is stable per-deployment, never per-invite.
 */
class HmacSha256InviteEmailHasherTest {

    private static final String SECRET = "test-secret-do-not-use-in-production";

    @Test
    void hash_isStableForTheSameNormalizedEmailUnderTheSameSecret() {
        HmacSha256InviteEmailHasher hasher = new HmacSha256InviteEmailHasher(SECRET);
        NormalizedEmail email = NormalizedEmail.fromRaw("anna@example.com");

        assertThat(hasher.hash(email)).isEqualTo(hasher.hash(email));
    }

    @Test
    void hash_producesDifferentDigestsForDifferentEmails() {
        HmacSha256InviteEmailHasher hasher = new HmacSha256InviteEmailHasher(SECRET);

        assertThat(hasher.hash(NormalizedEmail.fromRaw("anna@example.com")))
                .isNotEqualTo(hasher.hash(NormalizedEmail.fromRaw("berta@example.com")));
    }

    @Test
    void hash_producesADifferentDigestUnderADifferentSecret() {
        HmacSha256InviteEmailHasher first = new HmacSha256InviteEmailHasher(SECRET);
        HmacSha256InviteEmailHasher second = new HmacSha256InviteEmailHasher("a-completely-different-secret");
        NormalizedEmail email = NormalizedEmail.fromRaw("anna@example.com");

        assertThat(first.hash(email)).isNotEqualTo(second.hash(email));
    }

    @Test
    void constructor_rejectsABlankSecret() {
        assertThatThrownBy(() -> new HmacSha256InviteEmailHasher("   ")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructor_rejectsANullSecret() {
        assertThatThrownBy(() -> new HmacSha256InviteEmailHasher(null)).isInstanceOf(IllegalStateException.class);
    }
}
