package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.identity.adapter.out.InMemoryEmailRecoveryCodeStore;
import de.sgart.identity.adapter.out.InMemoryRecoveryEmailBindingRepository;
import de.sgart.identity.adapter.out.InMemoryRecoveryEmailThrottles;
import de.sgart.identity.application.RecoveryEmailTestSupport.IdentityRecoveryCodeHasher;
import de.sgart.identity.application.RecoveryEmailTestSupport.Sha256RecoveryEmailDigester;
import de.sgart.identity.application.RecoveryEmailTestSupport.RecordingSendRecoveryCodeEmail;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryCodePurpose;
import de.sgart.identity.domain.RecoveryCodeSubject;
import de.sgart.identity.domain.RecoveryEmailBinding;
import de.sgart.identity.domain.RecoveryEmailDigest;
import de.sgart.identity.domain.RecoveryEmailHint;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Fast unit test — detach removes every binding and code row of the caller, and only the caller's. */
class DetachRecoveryEmailTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final String CALLER_ID = "caller-1";
    private static final KeycloakUserId CALLER = new KeycloakUserId(CALLER_ID);
    private static final KeycloakUserId OTHER_ACCOUNT = new KeycloakUserId("other-account");
    private static final RecoveryEmailDigest DIGEST = new RecoveryEmailDigest("digest");
    private static final RecoveryEmailHint HINT = RecoveryEmailHint.masking("person@example.test");

    private final InMemoryRecoveryEmailBindingRepository bindings = new InMemoryRecoveryEmailBindingRepository();
    private final InMemoryEmailRecoveryCodeStore codeStore = new InMemoryEmailRecoveryCodeStore();
    private final DetachRecoveryEmail detachRecoveryEmail = new DetachRecoveryEmail(bindings, codeStore);

    @Test
    void detach_removesPendingAndConfirmedBindingsAndCodes() {
        bindings.savePending(RecoveryEmailBinding.pending(DIGEST, CALLER, HINT, NOW));
        bindings.confirm(bindings.findPendingFor(CALLER).orElseThrow().confirm(NOW));
        bindings.savePending(RecoveryEmailBinding.pending(new RecoveryEmailDigest("other"), CALLER, HINT, NOW));
        bindings.savePending(RecoveryEmailBinding.pending(DIGEST, OTHER_ACCOUNT, HINT, NOW));
        codeStore.store(RecoveryCodeSubject.forAccount(CALLER), RecoveryCodePurpose.ATTACH_CONFIRM, "hash", NOW.plusSeconds(60), NOW);

        detachRecoveryEmail.detach(CALLER_ID);

        assertThat(bindings.findAllFor(CALLER)).isEmpty();
        assertThat(bindings.findPendingFor(OTHER_ACCOUNT)).isPresent();
        assertThat(codeStore.find(RecoveryCodeSubject.forAccount(CALLER), RecoveryCodePurpose.ATTACH_CONFIRM)).isEmpty();
    }

    @Test
    void detach_whenNothingIsAttached_isANoOp() {
        detachRecoveryEmail.detach(CALLER_ID);

        assertThat(bindings.all()).isEmpty();
    }

    @Test
    void detach_doesNotResetAnyThrottle() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InMemoryRecoveryEmailThrottles throttles = new InMemoryRecoveryEmailThrottles(clock);
        AttachRecoveryEmail attachRecoveryEmail = new AttachRecoveryEmail(
                bindings,
                new Sha256RecoveryEmailDigester(),
                codeStore,
                new IdentityRecoveryCodeHasher(),
                new RecordingSendRecoveryCodeEmail(),
                throttles,
                throttles,
                clock);
        attachRecoveryEmail.attach(CALLER_ID, "person@example.test");

        detachRecoveryEmail.detach(CALLER_ID);

        assertThatThrownBy(() -> attachRecoveryEmail.attach(CALLER_ID, "person@example.test"))
                .isInstanceOf(RecoveryCodeRateLimitedException.class);
    }
}
