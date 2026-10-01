package de.sgart.identity.application;

import static de.sgart.identity.RecoveryEmailBindingFixtures.saveConfirmedBinding;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.identity.adapter.out.InMemoryEmailRecoveryCodeStore;
import de.sgart.identity.adapter.out.InMemoryRecoveryEmailBindingRepository;
import de.sgart.identity.application.RecoveryEmailTestSupport.IdentityRecoveryCodeHasher;
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

/**
 * Fast unit test — a correct code confirms the caller's pending binding and consumes the code
 * (single-use); a wrong, expired, or exhausted code, or a vanished pending binding, is rejected
 * fast and changes nothing.
 */
class ConfirmRecoveryEmailTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final String CALLER_ID = "caller-1";
    private static final KeycloakUserId CALLER = new KeycloakUserId(CALLER_ID);
    private static final RecoveryCodeSubject CALLER_CODE_SUBJECT = RecoveryCodeSubject.forAccount(CALLER);
    private static final RecoveryEmailDigest FIRST_DIGEST = new RecoveryEmailDigest("first-digest");
    private static final RecoveryEmailDigest SECOND_DIGEST = new RecoveryEmailDigest("second-digest");
    private static final RecoveryEmailHint HINT = RecoveryEmailHint.masking("person@example.test");

    private final InMemoryRecoveryEmailBindingRepository bindings = new InMemoryRecoveryEmailBindingRepository();
    private final InMemoryEmailRecoveryCodeStore codeStore = new InMemoryEmailRecoveryCodeStore();
    private final IdentityRecoveryCodeHasher hasher = new IdentityRecoveryCodeHasher();
    private final ConfirmRecoveryEmail confirmRecoveryEmail =
            new ConfirmRecoveryEmail(bindings, codeStore, hasher, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void confirm_marksThePendingBindingAsConfirmedAndConsumesTheCode() {
        bindings.savePending(RecoveryEmailBinding.pending(FIRST_DIGEST, CALLER, HINT, NOW));
        storeCode("042817", NOW.plusSeconds(60), 0);

        confirmRecoveryEmail.confirm(CALLER_ID, "042817");

        assertThat(bindings.findConfirmedFor(CALLER).orElseThrow().confirmedAt()).isEqualTo(NOW);
        assertThat(bindings.findPendingFor(CALLER)).isEmpty();
        assertThat(codeStore.find(CALLER_CODE_SUBJECT, RecoveryCodePurpose.ATTACH_CONFIRM)).isEmpty();
    }

    @Test
    void confirm_replacesTheCallersPreviousConfirmedBinding() {
        saveConfirmedBinding(bindings, FIRST_DIGEST, CALLER, HINT, NOW);
        bindings.savePending(RecoveryEmailBinding.pending(SECOND_DIGEST, CALLER, HINT, NOW));
        storeCode("042817", NOW.plusSeconds(60), 0);

        confirmRecoveryEmail.confirm(CALLER_ID, "042817");

        assertThat(bindings.all()).extracting(RecoveryEmailBinding::digest).containsExactly(SECOND_DIGEST);
        assertThat(bindings.hasConfirmedBindingFor(CALLER)).isTrue();
    }

    @Test
    void confirm_whenTheCallerReattachedTheirAlreadyConfirmedAddress_succeedsAndConsumesTheCode() {
        saveConfirmedBinding(bindings, FIRST_DIGEST, CALLER, HINT, NOW);
        bindings.savePending(RecoveryEmailBinding.pending(FIRST_DIGEST, CALLER, HINT, NOW.plusSeconds(5)));
        storeCode("042817", NOW.plusSeconds(60), 0);

        confirmRecoveryEmail.confirm(CALLER_ID, "042817");

        assertThat(bindings.all()).extracting(RecoveryEmailBinding::digest).containsExactly(FIRST_DIGEST);
        assertThat(bindings.hasConfirmedBindingFor(CALLER)).isTrue();
        assertThat(codeStore.find(CALLER_CODE_SUBJECT, RecoveryCodePurpose.ATTACH_CONFIRM)).isEmpty();
    }

    @Test
    void confirm_withoutAPendingBinding_isRejectedLikeAWrongCode() {
        storeCode("042817", NOW.plusSeconds(60), 0);

        assertThatThrownBy(() -> confirmRecoveryEmail.confirm(CALLER_ID, "042817"))
                .isInstanceOf(RecoveryCodeRejectedException.class);
    }

    @Test
    void confirm_withAWrongCode_leavesTheBindingPending() {
        bindings.savePending(RecoveryEmailBinding.pending(FIRST_DIGEST, CALLER, HINT, NOW));
        storeCode("042817", NOW.plusSeconds(60), 0);

        assertThatThrownBy(() -> confirmRecoveryEmail.confirm(CALLER_ID, "000000"))
                .isInstanceOf(RecoveryCodeRejectedException.class);

        assertThat(bindings.findPendingFor(CALLER)).isPresent();
        assertThat(bindings.hasConfirmedBindingFor(CALLER)).isFalse();
    }

    @Test
    void confirm_withAnExpiredCode_isRejectedAndLeavesTheBindingPending() {
        bindings.savePending(RecoveryEmailBinding.pending(FIRST_DIGEST, CALLER, HINT, NOW));
        storeCode("042817", NOW.minusSeconds(1), 0);

        assertThatThrownBy(() -> confirmRecoveryEmail.confirm(CALLER_ID, "042817"))
                .isInstanceOf(RecoveryCodeRejectedException.class);

        assertThat(bindings.hasConfirmedBindingFor(CALLER)).isFalse();
    }

    @Test
    void confirm_withExhaustedAttempts_isRejected() {
        bindings.savePending(RecoveryEmailBinding.pending(FIRST_DIGEST, CALLER, HINT, NOW));
        storeCode("042817", NOW.plusSeconds(60), RecoveryCode.MAX_ATTEMPTS);

        assertThatThrownBy(() -> confirmRecoveryEmail.confirm(CALLER_ID, "042817"))
                .isInstanceOf(RecoveryCodeRejectedException.class);
    }

    private void storeCode(String code, Instant expiresAt, int attempts) {
        codeStore.store(CALLER_CODE_SUBJECT, RecoveryCodePurpose.ATTACH_CONFIRM, hasher.hash(code), expiresAt, NOW);
        for (int attempt = 0; attempt < attempts; attempt++) {
            codeStore.incrementAttempts(CALLER_CODE_SUBJECT, RecoveryCodePurpose.ATTACH_CONFIRM);
        }
    }
}
