package de.sgart.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RecoveryEmailBindingTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-01T10:05:00Z");

    private final RecoveryEmailBinding pendingBinding = RecoveryEmailBinding.pending(
            new RecoveryEmailDigest("digest"),
            new KeycloakUserId("account"),
            RecoveryEmailHint.masking("tester@example.test"),
            CREATED_AT);

    @Test
    void pending_isNotConfirmed() {
        assertThat(pendingBinding.isConfirmed()).isFalse();
        assertThat(pendingBinding.confirmedAt()).isNull();
    }

    @Test
    void confirm_setsTheConfirmationTimeAndKeepsTheRest() {
        RecoveryEmailBinding confirmedBinding = pendingBinding.confirm(CONFIRMED_AT);

        assertThat(confirmedBinding.isConfirmed()).isTrue();
        assertThat(confirmedBinding.confirmedAt()).isEqualTo(CONFIRMED_AT);
        assertThat(confirmedBinding.digest()).isEqualTo(pendingBinding.digest());
        assertThat(confirmedBinding.createdAt()).isEqualTo(CREATED_AT);
    }
}
