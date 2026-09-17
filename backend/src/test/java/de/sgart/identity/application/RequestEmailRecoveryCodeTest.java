package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.adapter.out.InMemoryEmailRecoveryCodeStore;
import de.sgart.identity.application.RecoveryEmailTestSupport.FakeFindAccountByEmail;
import de.sgart.identity.application.RecoveryEmailTestSupport.IdentityRecoveryCodeHasher;
import de.sgart.identity.application.RecoveryEmailTestSupport.RecordingSendRecoveryCodeEmail;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryCodePurpose;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — proves Story 7.3, AC2/D-H: requesting a code for a known email stores and
 * sends one; requesting for an unknown email does neither, and both cases would "succeed" toward
 * the caller equally (the controller answers {@code 202} regardless — no account/email
 * enumeration).
 */
class RequestEmailRecoveryCodeTest {

    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");
    private static final KeycloakUserId TARGET = new KeycloakUserId("target-1");

    private final FakeFindAccountByEmail findAccountByEmail = new FakeFindAccountByEmail();
    private final InMemoryEmailRecoveryCodeStore emailRecoveryCodeStore = new InMemoryEmailRecoveryCodeStore();
    private final IdentityRecoveryCodeHasher hasher = new IdentityRecoveryCodeHasher();
    private final RecordingSendRecoveryCodeEmail sendRecoveryCodeEmail = new RecordingSendRecoveryCodeEmail();
    private final RequestEmailRecoveryCode requestEmailRecoveryCode = new RequestEmailRecoveryCode(
            findAccountByEmail, emailRecoveryCodeStore, hasher, sendRecoveryCodeEmail, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void requestEmailRecoveryCode_withKnownEmail_storesAndSendsACode() {
        findAccountByEmail.registerAccount("person@example.com", TARGET);

        requestEmailRecoveryCode.request("person@example.com");

        assertThat(emailRecoveryCodeStore.find(TARGET, RecoveryCodePurpose.RECOVER)).isPresent();
        assertThat(sendRecoveryCodeEmail.sentTo).containsExactly("person@example.com");
    }

    @Test
    void requestEmailRecoveryCode_withUnknownEmail_stillReturnsAcceptedAndSendsNothing() {
        requestEmailRecoveryCode.request("nobody@example.com");

        assertThat(sendRecoveryCodeEmail.sentTo).isEmpty();
        assertThat(emailRecoveryCodeStore.find(TARGET, RecoveryCodePurpose.RECOVER)).isEmpty();
        // The service itself never throws for an unknown email — the controller layer always answers 202.
    }
}
