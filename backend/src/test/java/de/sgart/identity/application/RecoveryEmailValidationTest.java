package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RecoveryEmailValidationTest {

    @Test
    void validated_lowercasesAndTrimsTheAddress() {
        assertThat(RecoveryEmailValidation.validated("  Tester.Name+Tag@Example.TEST \n"))
                .isEqualTo("tester.name+tag@example.test");
    }

    @Test
    void validated_doesNotFoldProviderSpecificParts() {
        assertThat(RecoveryEmailValidation.validated("a.b+c@example.test")).isEqualTo("a.b+c@example.test");
    }

    @Test
    void validated_aMalformedAddress_isRejected() {
        assertThatThrownBy(() -> RecoveryEmailValidation.validated("not-an-email"))
                .isInstanceOf(InvalidRecoveryEmailException.class);
    }

    @Test
    void validated_aMissingAddress_isRejected() {
        assertThatThrownBy(() -> RecoveryEmailValidation.validated(null))
                .isInstanceOf(InvalidRecoveryEmailException.class);
    }

    @Test
    void validated_anAddressLongerThanTheMaximumLength_isRejected() {
        String tooLongAddress = "a".repeat(250) + "@example.test";

        assertThatThrownBy(() -> RecoveryEmailValidation.validated(tooLongAddress))
                .isInstanceOf(InvalidRecoveryEmailException.class);
    }

    @Test
    void validated_anAddressOfExactlyTheMaximumLength_isAccepted() {
        String longestAddress = "a".repeat(254 - "@example.test".length()) + "@example.test";

        assertThat(RecoveryEmailValidation.validated(longestAddress)).isEqualTo(longestAddress);
    }
}
