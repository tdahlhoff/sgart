package de.sgart.identity.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class EmailRecoveryCodeTest {

    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");

    @Test
    void aRecoveryCodeBelongingToAnAccountIsRejected() {
        RecoveryCodeSubject accountSubject = RecoveryCodeSubject.forAccount(new KeycloakUserId("account-1"));

        assertThatThrownBy(() -> new EmailRecoveryCode(accountSubject, RecoveryCodePurpose.RECOVER, "hash", NOW, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAttachCodeBelongingToAnAddressIsRejected() {
        RecoveryCodeSubject addressSubject = RecoveryCodeSubject.forAddress(new RecoveryEmailDigest("digest"));

        assertThatThrownBy(
                        () -> new EmailRecoveryCode(addressSubject, RecoveryCodePurpose.ATTACH_CONFIRM, "hash", NOW, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
