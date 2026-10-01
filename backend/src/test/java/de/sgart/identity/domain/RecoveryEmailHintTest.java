package de.sgart.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RecoveryEmailHintTest {

    @Test
    void masking_keepsTheFirstCharacterAndTheDomain() {
        assertThat(RecoveryEmailHint.masking("tester@example.test").value()).isEqualTo("t***@example.test");
    }

    @Test
    void masking_aOneCharacterLocalPart_stillMasks() {
        assertThat(RecoveryEmailHint.masking("t@example.test").value()).isEqualTo("t***@example.test");
    }

    @Test
    void masking_neverRevealsMoreOfTheLocalPart() {
        assertThat(RecoveryEmailHint.masking("tester@example.test").value()).doesNotContain("ester");
    }

    @Test
    void masking_anAddressWithoutALocalPart_isRejected() {
        assertThatThrownBy(() -> RecoveryEmailHint.masking("@example.test"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void masking_aLocalPartStartingWithASupplementaryCharacter_keepsTheWholeCharacter() {
        String supplementaryCharacter = "\uD835\uDC00";

        assertThat(RecoveryEmailHint.masking(supplementaryCharacter + "tester@example.test").value())
                .isEqualTo(supplementaryCharacter + "***@example.test");
    }

    @Test
    void toString_doesNotRevealTheMaskedAddress() {
        assertThat(RecoveryEmailHint.masking("tester@example.test").toString())
                .doesNotContain("t***")
                .doesNotContain("example.test");
    }
}
