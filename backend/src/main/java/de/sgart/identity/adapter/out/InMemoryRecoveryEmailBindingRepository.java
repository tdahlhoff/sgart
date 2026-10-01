package de.sgart.identity.adapter.out;

import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryEmailBinding;
import de.sgart.identity.domain.RecoveryEmailBindingExport;
import de.sgart.identity.domain.RecoveryEmailBindingRepository;
import de.sgart.identity.domain.RecoveryEmailDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * In-memory {@link RecoveryEmailBindingRepository} — the fast unit-test double (CLAUDE.md §6). The
 * durable production adapter is {@link JdbcRecoveryEmailBindingRepository}.
 */
public final class InMemoryRecoveryEmailBindingRepository implements RecoveryEmailBindingRepository {

    private final List<RecoveryEmailBinding> bindings = new ArrayList<>();

    @Override
    public synchronized void savePending(RecoveryEmailBinding pendingBinding) {
        bindings.removeIf(existing ->
                existing.keycloakUserId().equals(pendingBinding.keycloakUserId()) && !existing.isConfirmed());
        // Like the durable adapter: an already confirmed claim on the same address is never downgraded.
        boolean alreadyClaimed = bindings.stream().anyMatch(existing -> isSameClaim(existing, pendingBinding));
        if (!alreadyClaimed) {
            bindings.add(pendingBinding);
        }
    }

    @Override
    public synchronized Optional<RecoveryEmailBinding> findPendingFor(KeycloakUserId keycloakUserId) {
        return bindings.stream()
                .filter(binding -> binding.keycloakUserId().equals(keycloakUserId) && !binding.isConfirmed())
                .findFirst();
    }

    @Override
    public synchronized void confirm(RecoveryEmailBinding confirmedBinding) {
        // Like the durable adapter: confirming a claim that was never saved is a defect, not an insert.
        if (bindings.stream().noneMatch(existing -> isSameClaim(existing, confirmedBinding))) {
            throw new IllegalStateException("no recovery email binding to confirm");
        }
        bindings.removeIf(existing -> existing.keycloakUserId().equals(confirmedBinding.keycloakUserId())
                && existing.isConfirmed());
        bindings.removeIf(existing -> isSameClaim(existing, confirmedBinding));
        bindings.add(confirmedBinding);
    }

    @Override
    public synchronized List<RecoveryEmailBinding> findConfirmedFor(RecoveryEmailDigest digest) {
        return bindings.stream()
                .filter(binding -> binding.digest().equals(digest) && binding.isConfirmed())
                .toList();
    }

    @Override
    public synchronized Optional<RecoveryEmailBinding> findConfirmedFor(KeycloakUserId keycloakUserId) {
        return bindings.stream()
                .filter(binding -> binding.keycloakUserId().equals(keycloakUserId) && binding.isConfirmed())
                .findFirst();
    }

    @Override
    public synchronized boolean hasConfirmedBindingFor(KeycloakUserId keycloakUserId) {
        return findConfirmedFor(keycloakUserId).isPresent();
    }

    @Override
    public synchronized void deleteAllFor(KeycloakUserId keycloakUserId) {
        bindings.removeIf(binding -> binding.keycloakUserId().equals(keycloakUserId));
    }

    @Override
    public synchronized List<RecoveryEmailBindingExport> findAllFor(KeycloakUserId keycloakUserId) {
        return bindings.stream()
                .filter(binding -> binding.keycloakUserId().equals(keycloakUserId))
                .map(binding -> new RecoveryEmailBindingExport(binding.hint(), binding.confirmedAt()))
                .toList();
    }

    @Override
    public synchronized void deletePendingCreatedBefore(Instant threshold) {
        bindings.removeIf(binding -> !binding.isConfirmed() && binding.createdAt().isBefore(threshold));
    }

    /** Test helper — resets this double between test methods that share one Spring context. */
    public synchronized void clear() {
        bindings.clear();
    }

    /** Test helper — every binding currently stored, for assertions the port does not expose. */
    public synchronized List<RecoveryEmailBinding> all() {
        return List.copyOf(bindings);
    }

    private static boolean isSameClaim(RecoveryEmailBinding first, RecoveryEmailBinding second) {
        return first.digest().equals(second.digest()) && first.keycloakUserId().equals(second.keycloakUserId());
    }
}
