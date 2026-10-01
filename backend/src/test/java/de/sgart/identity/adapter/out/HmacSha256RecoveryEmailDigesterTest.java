package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HmacSha256RecoveryEmailDigesterTest {

    private static final String PEPPER = "test-pepper-do-not-use-in-production";
    private static final String ADDRESS = "tester@example.test";

    @Test
    void digest_isStableForTheSameAddressUnderTheSamePepper() {
        HmacSha256RecoveryEmailDigester digester = new HmacSha256RecoveryEmailDigester(PEPPER);

        assertThat(digester.digest(ADDRESS)).isEqualTo(digester.digest(ADDRESS));
    }

    @Test
    void digest_differsPerPepper() {
        HmacSha256RecoveryEmailDigester firstDigester = new HmacSha256RecoveryEmailDigester(PEPPER);
        HmacSha256RecoveryEmailDigester secondDigester = new HmacSha256RecoveryEmailDigester(PEPPER + "-other");

        assertThat(firstDigester.digest(ADDRESS)).isNotEqualTo(secondDigester.digest(ADDRESS));
    }

    @Test
    void digest_neverContainsTheAddress() {
        assertThat(new HmacSha256RecoveryEmailDigester(PEPPER).digest(ADDRESS).value())
                .doesNotContain("tester")
                .doesNotContain("example");
    }

    @Test
    void constructor_rejectsABlankPepper() {
        assertThatThrownBy(() -> new HmacSha256RecoveryEmailDigester("  ")).isInstanceOf(IllegalStateException.class);
    }
}
