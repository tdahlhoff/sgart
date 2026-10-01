package de.sgart.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RecoveryEmailHintTest {

    @Test
    void masking_keepsTheFirstCharacterOfTheLocalPartAndOfTheDomainNameAndTheTopLevelDomain() {
        assertThat(RecoveryEmailHint.masking("tester@example.test").value()).isEqualTo("t***@e***.test");
    }

    @Test
    void masking_aCommonProvider_keepsOnlyTheFirstCharacterOfTheProviderName() {
        assertThat(RecoveryEmailHint.masking("tester@gmail.com").value()).isEqualTo("t***@g***.com");
        assertThat(RecoveryEmailHint.masking("tester@lastname.de").value()).isEqualTo("t***@l***.de");
    }

    @Test
    void masking_aDomainWithSubdomains_keepsOnlyTheTextAfterTheLastDotAsTopLevelDomain() {
        assertThat(RecoveryEmailHint.masking("tester@mail.example.test").value()).isEqualTo("t***@m***.test");
    }

    @Test
    void masking_aDomainWithoutADot_masksEverythingAfterItsFirstCharacter() {
        assertThat(RecoveryEmailHint.masking("tester@localhost").value()).isEqualTo("t***@l***");
    }

    @Test
    void masking_aSupplementaryCharacterStartingTheDomainName_keepsTheWholeCharacter() {
        String supplementaryCharacter = "\uD835\uDC00";

        assertThat(RecoveryEmailHint.masking("tester@" + supplementaryCharacter + "domain.test").value())
                .isEqualTo("t***@" + supplementaryCharacter + "***.test");
    }

    @Test
    void masking_aOneCharacterLocalPart_stillMasks() {
        assertThat(RecoveryEmailHint.masking("t@example.test").value()).isEqualTo("t***@e***.test");
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
                .isEqualTo(supplementaryCharacter + "***@e***.test");
    }

    @Test
    void toString_doesNotRevealTheMaskedAddress() {
        assertThat(RecoveryEmailHint.masking("tester@example.test").toString())
                .doesNotContain("t***")
                .doesNotContain("example.test");
    }
}
