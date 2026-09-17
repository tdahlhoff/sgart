package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.identity.adapter.out.InMemoryEmailRecoveryCodeStore;
import de.sgart.identity.application.RecoveryEmailTestSupport.IdentityRecoveryCodeHasher;
import de.sgart.identity.application.RecoveryEmailTestSupport.RecordingSendRecoveryCodeEmail;
import de.sgart.identity.application.RecoveryEmailTestSupport.RecordingSetAccountEmail;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryCodePurpose;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — pure, in-memory doubles, no framework (CLAUDE.md §6). Proves Story 7.3, AC1:
 * attaching an email sets it unverified and issues a hashed one-time code, and a malformed email
 * is rejected fast.
 */
class AttachRecoveryEmailTest {

    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");
    private static final String CALLER_ID = "caller-1";
    private static final KeycloakUserId CALLER = new KeycloakUserId(CALLER_ID);

    private final RecordingSetAccountEmail setAccountEmail = new RecordingSetAccountEmail();
    private final InMemoryEmailRecoveryCodeStore emailRecoveryCodeStore = new InMemoryEmailRecoveryCodeStore();
    private final IdentityRecoveryCodeHasher hasher = new IdentityRecoveryCodeHasher();
    private final RecordingSendRecoveryCodeEmail sendRecoveryCodeEmail = new RecordingSendRecoveryCodeEmail();
    private final AttachRecoveryEmail attachRecoveryEmail = new AttachRecoveryEmail(
            setAccountEmail, emailRecoveryCodeStore, hasher, sendRecoveryCodeEmail, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void attachRecoveryEmail_setsUnverifiedEmailAndIssuesCode() {
        attachRecoveryEmail.attach(CALLER_ID, "person@example.com");

        assertThat(setAccountEmail.emailFor(CALLER)).isEqualTo("person@example.com");
        assertThat(setAccountEmail.verifiedFor(CALLER)).isFalse();
        assertThat(emailRecoveryCodeStore.find(CALLER, RecoveryCodePurpose.ATTACH_CONFIRM)).isPresent();
        assertThat(sendRecoveryCodeEmail.sentTo).containsExactly("person@example.com");
        assertThat(emailRecoveryCodeStore.find(CALLER, RecoveryCodePurpose.ATTACH_CONFIRM).orElseThrow().codeHash())
                .isEqualTo(hasher.hash(sendRecoveryCodeEmail.sentCodes.get(0)));
    }

    @Test
    void attachRecoveryEmail_withMalformedEmail_returnsBadRequestNotServerError() {
        assertThatThrownBy(() -> attachRecoveryEmail.attach(CALLER_ID, "not-an-email"))
                .isInstanceOf(InvalidRecoveryEmailException.class);

        assertThat(setAccountEmail.emailFor(CALLER)).isNull();
        assertThat(emailRecoveryCodeStore.find(CALLER, RecoveryCodePurpose.ATTACH_CONFIRM)).isEmpty();
    }
}
