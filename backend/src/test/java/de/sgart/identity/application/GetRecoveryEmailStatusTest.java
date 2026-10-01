package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.adapter.out.InMemoryRecoveryEmailBindingRepository;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryEmailBinding;
import de.sgart.identity.domain.RecoveryEmailDigest;
import de.sgart.identity.domain.RecoveryEmailHint;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GetRecoveryEmailStatusTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final String CALLER_ID = "caller-1";
    private static final KeycloakUserId CALLER = new KeycloakUserId(CALLER_ID);
    private static final RecoveryEmailHint HINT = RecoveryEmailHint.masking("person@example.test");

    private final InMemoryRecoveryEmailBindingRepository bindings = new InMemoryRecoveryEmailBindingRepository();
    private final GetRecoveryEmailStatus getRecoveryEmailStatus = new GetRecoveryEmailStatus(bindings);

    @Test
    void returnsTheHintOfTheConfirmedBinding() {
        bindings.savePending(RecoveryEmailBinding.pending(new RecoveryEmailDigest("digest"), CALLER, HINT, NOW));
        bindings.confirm(bindings.findPendingFor(CALLER).orElseThrow().confirm(NOW));

        assertThat(getRecoveryEmailStatus.statusFor(CALLER_ID).addressHint()).isEqualTo("p***@example.test");
    }

    @Test
    void neverListsAPendingBinding() {
        bindings.savePending(RecoveryEmailBinding.pending(new RecoveryEmailDigest("digest"), CALLER, HINT, NOW));

        assertThat(getRecoveryEmailStatus.statusFor(CALLER_ID).addressHint()).isNull();
    }

    @Test
    void withoutAnyBinding_hasNoHint() {
        assertThat(getRecoveryEmailStatus.statusFor(CALLER_ID).addressHint()).isNull();
    }

    @Test
    void hasNoSideEffects() {
        bindings.savePending(RecoveryEmailBinding.pending(new RecoveryEmailDigest("digest"), CALLER, HINT, NOW));
        bindings.confirm(bindings.findPendingFor(CALLER).orElseThrow().confirm(NOW));
        var stateBefore = bindings.all();

        getRecoveryEmailStatus.statusFor(CALLER_ID);
        getRecoveryEmailStatus.statusFor(CALLER_ID);

        assertThat(bindings.all()).isEqualTo(stateBefore);
    }
}
