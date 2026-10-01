package de.sgart.identity.application;

import static de.sgart.identity.RecoveryEmailBindingFixtures.saveConfirmedBinding;
import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.adapter.out.InMemoryEmailRecoveryCodeStore;
import de.sgart.identity.adapter.out.InMemoryRecoveryEmailBindingRepository;
import de.sgart.identity.adapter.out.InMemoryRecoveryEmailThrottles;
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

class GetRecoveryEmailStatusTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final String CALLER_ID = "caller-1";
    private static final KeycloakUserId CALLER = new KeycloakUserId(CALLER_ID);
    private static final RecoveryEmailHint HINT = RecoveryEmailHint.masking("person@example.test");

    private final InMemoryRecoveryEmailBindingRepository bindings = new InMemoryRecoveryEmailBindingRepository();
    private final InMemoryEmailRecoveryCodeStore codeStore = new InMemoryEmailRecoveryCodeStore();
    private final InMemoryRecoveryEmailThrottles throttles =
            new InMemoryRecoveryEmailThrottles(Clock.fixed(NOW, ZoneOffset.UTC));
    private final GetRecoveryEmailStatus getRecoveryEmailStatus = new GetRecoveryEmailStatus(bindings);

    @Test
    void statusFor_withAConfirmedBinding_returnsItsHint() {
        saveConfirmedBinding(bindings, new RecoveryEmailDigest("digest"), CALLER, HINT, NOW);

        assertThat(getRecoveryEmailStatus.statusFor(CALLER_ID).addressHint()).isEqualTo("p***@e***.test");
    }

    @Test
    void statusFor_withOnlyAPendingBinding_hasNoHint() {
        bindings.savePending(RecoveryEmailBinding.pending(new RecoveryEmailDigest("digest"), CALLER, HINT, NOW));

        assertThat(getRecoveryEmailStatus.statusFor(CALLER_ID).addressHint()).isNull();
    }

    @Test
    void statusFor_withoutAnyBinding_hasNoHint() {
        assertThat(getRecoveryEmailStatus.statusFor(CALLER_ID).addressHint()).isNull();
    }

    @Test
    void statusFor_changesNeitherTheBindingsNorTheCodesNorAnyBudget() {
        RecoveryEmailDigest digest = new RecoveryEmailDigest("digest");
        saveConfirmedBinding(bindings, digest, CALLER, HINT, NOW);
        codeStore.store(RecoveryCodeSubject.forAccount(CALLER), RecoveryCodePurpose.ATTACH_CONFIRM, "hash", NOW.plusSeconds(60), NOW);
        var bindingsBefore = bindings.all();
        var codesBefore = codeStore.all();

        getRecoveryEmailStatus.statusFor(CALLER_ID);
        getRecoveryEmailStatus.statusFor(CALLER_ID);

        assertThat(bindings.all()).isEqualTo(bindingsBefore);
        assertThat(codeStore.all()).isEqualTo(codesBefore);
        // A budget slot consumed by the queries would make the first real use of it fail.
        assertThat(throttles.tryAttach(CALLER)).isTrue();
        assertThat(throttles.tryMail(digest)).isTrue();
        assertThat(throttles.tryRequest(digest)).isTrue();
    }
}
