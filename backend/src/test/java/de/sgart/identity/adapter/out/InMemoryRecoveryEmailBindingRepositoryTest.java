package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryEmailBinding;
import de.sgart.identity.domain.RecoveryEmailDigest;
import de.sgart.identity.domain.RecoveryEmailHint;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryRecoveryEmailBindingRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final KeycloakUserId ACCOUNT = new KeycloakUserId("account-a");
    private static final RecoveryEmailDigest DIGEST = new RecoveryEmailDigest("digest");
    private static final RecoveryEmailHint HINT = RecoveryEmailHint.masking("tester@example.test");

    private final InMemoryRecoveryEmailBindingRepository repository = new InMemoryRecoveryEmailBindingRepository();

    @Test
    void confirm_aBindingThatWasNeverSaved_failsWithoutAddingIt() {
        RecoveryEmailBinding neverSaved = RecoveryEmailBinding.pending(DIGEST, ACCOUNT, HINT, NOW).confirm(NOW);

        assertThatThrownBy(() -> repository.confirm(neverSaved))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(DIGEST.value());

        assertThat(repository.all()).isEmpty();
    }

    @Test
    void confirm_aSavedPendingBinding_becomesConfirmed() {
        repository.savePending(RecoveryEmailBinding.pending(DIGEST, ACCOUNT, HINT, NOW));

        repository.confirm(repository.findPendingFor(ACCOUNT).orElseThrow().confirm(NOW));

        assertThat(repository.hasConfirmedBindingFor(ACCOUNT)).isTrue();
    }
}
