package de.sgart.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.adapter.out.InMemoryProvisionedAccountRepository;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — pure, in-memory doubles, no framework (CLAUDE.md §6). Proves the GDPR
 * storage-limitation sweep (Story 7.1, AC5): a never-activated shell past the retention TTL is
 * deleted (Keycloak account + row); an activated one is kept regardless of age.
 */
class SweepNeverActivatedAccountsTest {

    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");
    private static final Duration RETENTION_PERIOD = Duration.ofDays(14);

    private final InMemoryProvisionedAccountRepository provisionedAccountRepository =
            new InMemoryProvisionedAccountRepository();
    private final InMemoryMemberMappingRepository memberMappingRepository = new InMemoryMemberMappingRepository();
    private final RecordingDeleteAccount deleteAccount = new RecordingDeleteAccount();
    private final SweepNeverActivatedAccounts sweep = new SweepNeverActivatedAccounts(
            provisionedAccountRepository,
            memberMappingRepository,
            deleteAccount,
            Clock.fixed(NOW, ZoneOffset.UTC),
            RETENTION_PERIOD);

    @Test
    void retentionSweep_deletesNeverActivatedAccountPastTtl() {
        KeycloakUserId neverActivated = new KeycloakUserId("never-activated");
        provisionedAccountRepository.recordIfAbsent(neverActivated, NOW.minus(Duration.ofDays(15)));

        sweep.sweep();

        assertThat(provisionedAccountRepository.contains(neverActivated)).isFalse();
        assertThat(deleteAccount.deletedIds).containsExactly(neverActivated);
    }

    @Test
    void retentionSweep_keepsActivatedAccount() {
        KeycloakUserId activated = new KeycloakUserId("activated");
        // Provisioned long ago (well past the TTL) but activated — a MemberMapping exists.
        provisionedAccountRepository.recordIfAbsent(activated, NOW.minus(Duration.ofDays(100)));
        memberMappingRepository.save(new MemberMapping(HouseholdId.generate(), MemberId.generate(), activated));

        sweep.sweep();

        assertThat(provisionedAccountRepository.contains(activated)).isTrue();
        assertThat(deleteAccount.deletedIds).isEmpty();
    }

    @Test
    void retentionSweep_keepsANeverActivatedAccountStillWithinTheTtl() {
        KeycloakUserId recentlyProvisioned = new KeycloakUserId("recently-provisioned");
        provisionedAccountRepository.recordIfAbsent(recentlyProvisioned, NOW.minus(Duration.ofDays(1)));

        sweep.sweep();

        assertThat(provisionedAccountRepository.contains(recentlyProvisioned)).isTrue();
        assertThat(deleteAccount.deletedIds).isEmpty();
    }

    private static final class RecordingDeleteAccount implements DeleteAccount {
        private final Set<KeycloakUserId> deletedIds = new HashSet<>();

        @Override
        public void delete(KeycloakUserId keycloakUserId) {
            deletedIds.add(keycloakUserId);
        }
    }
}
