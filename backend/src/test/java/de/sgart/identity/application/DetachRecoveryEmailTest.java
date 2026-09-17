package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.adapter.out.InMemoryEmailRecoveryCodeStore;
import de.sgart.identity.application.RecoveryEmailTestSupport.RecordingSetAccountEmail;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryCodePurpose;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Fast unit test — proves Story 7.3, AC4: detach clears the email + verified flag and deletes any code rows. */
class DetachRecoveryEmailTest {

    private static final String CALLER_ID = "caller-1";
    private static final KeycloakUserId CALLER = new KeycloakUserId(CALLER_ID);

    private final RecordingSetAccountEmail setAccountEmail = new RecordingSetAccountEmail();
    private final InMemoryEmailRecoveryCodeStore emailRecoveryCodeStore = new InMemoryEmailRecoveryCodeStore();
    private final DetachRecoveryEmail detachRecoveryEmail =
            new DetachRecoveryEmail(setAccountEmail, emailRecoveryCodeStore);

    @Test
    void detachRecoveryEmail_clearsEmailAndDeletesCodeRows() {
        setAccountEmail.setEmail(CALLER, "person@example.com", true);
        emailRecoveryCodeStore.store(
                CALLER, RecoveryCodePurpose.ATTACH_CONFIRM, "hash", Instant.now().plusSeconds(60), Instant.now());
        emailRecoveryCodeStore.store(
                CALLER, RecoveryCodePurpose.RECOVER, "hash2", Instant.now().plusSeconds(60), Instant.now());

        detachRecoveryEmail.detach(CALLER_ID);

        assertThat(setAccountEmail.emailFor(CALLER)).isNull();
        assertThat(setAccountEmail.verifiedFor(CALLER)).isFalse();
        assertThat(emailRecoveryCodeStore.find(CALLER, RecoveryCodePurpose.ATTACH_CONFIRM)).isEmpty();
        assertThat(emailRecoveryCodeStore.find(CALLER, RecoveryCodePurpose.RECOVER)).isEmpty();
    }

    @Test
    void detachRecoveryEmail_whenNothingAttached_isANoOp() {
        detachRecoveryEmail.detach(CALLER_ID);

        assertThat(setAccountEmail.emailFor(CALLER)).isNull();
    }
}
