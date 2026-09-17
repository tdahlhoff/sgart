package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.identity.adapter.out.InMemoryEmailRecoveryCodeStore;
import de.sgart.identity.application.RecoveryEmailTestSupport.IdentityRecoveryCodeHasher;
import de.sgart.identity.application.RecoveryEmailTestSupport.RecordingSetAccountEmail;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryCodePurpose;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — proves Story 7.3, AC1: a correct code marks the email verified and consumes
 * the code (single-use); a wrong/expired/exhausted code is rejected fast and changes nothing.
 */
class ConfirmRecoveryEmailTest {

    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");
    private static final String CALLER_ID = "caller-1";
    private static final KeycloakUserId CALLER = new KeycloakUserId(CALLER_ID);

    private final RecordingSetAccountEmail setAccountEmail = new RecordingSetAccountEmail();
    private final InMemoryEmailRecoveryCodeStore emailRecoveryCodeStore = new InMemoryEmailRecoveryCodeStore();
    private final IdentityRecoveryCodeHasher hasher = new IdentityRecoveryCodeHasher();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ConfirmRecoveryEmail confirmRecoveryEmail =
            new ConfirmRecoveryEmail(setAccountEmail, emailRecoveryCodeStore, hasher, clock);

    @Test
    void confirmRecoveryEmail_withCorrectCode_marksEmailVerifiedAndConsumesCode() {
        storeCode("042817", NOW.plusSeconds(60), 0);

        confirmRecoveryEmail.confirm(CALLER_ID, "042817");

        assertThat(setAccountEmail.verifiedFor(CALLER)).isTrue();
        assertThat(emailRecoveryCodeStore.find(CALLER, RecoveryCodePurpose.ATTACH_CONFIRM)).isEmpty();
    }

    @Test
    void confirmRecoveryEmail_withWrongOrExpiredCode_isRejectedFastAndChangesNothing() {
        storeCode("042817", NOW.plusSeconds(60), 0);

        assertThatThrownBy(() -> confirmRecoveryEmail.confirm(CALLER_ID, "000000"))
                .isInstanceOf(RecoveryCodeRejectedException.class);
        assertThat(setAccountEmail.verifiedFor(CALLER)).isNotEqualTo(Boolean.TRUE);

        emailRecoveryCodeStore.delete(CALLER, RecoveryCodePurpose.ATTACH_CONFIRM);
        storeCode("042817", NOW.minusSeconds(1), 0);
        assertThatThrownBy(() -> confirmRecoveryEmail.confirm(CALLER_ID, "042817"))
                .isInstanceOf(RecoveryCodeRejectedException.class);
    }

    @Test
    void confirmRecoveryEmail_exhaustedAttempts_isRejected() {
        storeCode("042817", NOW.plusSeconds(60), RecoveryCode.MAX_ATTEMPTS);

        assertThatThrownBy(() -> confirmRecoveryEmail.confirm(CALLER_ID, "042817"))
                .isInstanceOf(RecoveryCodeRejectedException.class);
    }

    private void storeCode(String code, Instant expiresAt, int attempts) {
        emailRecoveryCodeStore.store(CALLER, RecoveryCodePurpose.ATTACH_CONFIRM, hasher.hash(code), expiresAt, NOW);
        for (int i = 0; i < attempts; i++) {
            emailRecoveryCodeStore.incrementAttempts(CALLER, RecoveryCodePurpose.ATTACH_CONFIRM);
        }
    }
}
