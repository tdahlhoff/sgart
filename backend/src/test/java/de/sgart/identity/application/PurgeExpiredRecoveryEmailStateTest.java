package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.adapter.out.InMemoryEmailRecoveryCodeStore;
import de.sgart.identity.adapter.out.InMemoryRecoveryEmailBindingRepository;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryCodePurpose;
import de.sgart.identity.domain.RecoveryCodeSubject;
import de.sgart.identity.domain.RecoveryEmailBinding;
import de.sgart.identity.domain.RecoveryEmailDigest;
import de.sgart.identity.domain.RecoveryEmailHint;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PurgeExpiredRecoveryEmailStateTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final KeycloakUserId STALE_ACCOUNT = new KeycloakUserId("stale-account");
    private static final KeycloakUserId FRESH_ACCOUNT = new KeycloakUserId("fresh-account");
    private static final KeycloakUserId CONFIRMED_ACCOUNT = new KeycloakUserId("confirmed-account");
    private static final RecoveryEmailHint HINT = RecoveryEmailHint.masking("person@example.test");

    private final InMemoryRecoveryEmailBindingRepository bindings = new InMemoryRecoveryEmailBindingRepository();
    private final InMemoryEmailRecoveryCodeStore codeStore = new InMemoryEmailRecoveryCodeStore();
    private final PurgeExpiredRecoveryEmailState purge =
            new PurgeExpiredRecoveryEmailState(bindings, codeStore, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void purge_deletesPendingBindingsOlderThanTheCodeLifetime() {
        Instant justPastTheLifetime = NOW.minus(RecoveryCode.TTL).minusSeconds(1);
        bindings.savePending(RecoveryEmailBinding.pending(new RecoveryEmailDigest("d1"), STALE_ACCOUNT, HINT, justPastTheLifetime));
        bindings.savePending(RecoveryEmailBinding.pending(
                new RecoveryEmailDigest("d2"), FRESH_ACCOUNT, HINT, NOW.minus(Duration.ofMinutes(1))));

        purge.purge();

        assertThat(bindings.findPendingFor(STALE_ACCOUNT)).isEmpty();
        assertThat(bindings.findPendingFor(FRESH_ACCOUNT)).isPresent();
    }

    @Test
    void purge_keepsConfirmedBindings() {
        bindings.savePending(RecoveryEmailBinding.pending(
                new RecoveryEmailDigest("d3"), CONFIRMED_ACCOUNT, HINT, NOW.minus(Duration.ofDays(30))));
        bindings.confirm(bindings.findPendingFor(CONFIRMED_ACCOUNT).orElseThrow().confirm(NOW.minus(Duration.ofDays(29))));

        purge.purge();

        assertThat(bindings.hasConfirmedBindingFor(CONFIRMED_ACCOUNT)).isTrue();
    }

    @Test
    void purge_deletesExpiredCodes() {
        codeStore.store(RecoveryCodeSubject.forAccount(STALE_ACCOUNT), RecoveryCodePurpose.ATTACH_CONFIRM, "hash", NOW.minusSeconds(1), NOW.minusSeconds(901));
        codeStore.store(RecoveryCodeSubject.forAccount(FRESH_ACCOUNT), RecoveryCodePurpose.ATTACH_CONFIRM, "hash", NOW.plusSeconds(60), NOW);

        purge.purge();

        assertThat(codeStore.find(RecoveryCodeSubject.forAccount(STALE_ACCOUNT), RecoveryCodePurpose.ATTACH_CONFIRM)).isEmpty();
        assertThat(codeStore.find(RecoveryCodeSubject.forAccount(FRESH_ACCOUNT), RecoveryCodePurpose.ATTACH_CONFIRM)).isPresent();
    }
}
