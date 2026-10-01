package de.sgart.identity.application;

import de.sgart.identity.domain.EmailRecoveryCodeStore;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryCodePurpose;
import de.sgart.identity.domain.RecoveryEmailBinding;
import de.sgart.identity.domain.RecoveryEmailBindingRepository;
import java.time.Clock;
import java.util.Objects;

/**
 * Confirms ownership of an address just attached via {@link AttachRecoveryEmail}: on a correct,
 * unexpired, non-exhausted code, the caller's pending binding becomes confirmed (replacing the
 * caller's previous confirmed binding, since an account has one recovery email) and the code is
 * consumed (single-use).
 */
public final class ConfirmRecoveryEmail {

    private final RecoveryEmailBindingRepository recoveryEmailBindingRepository;
    private final EmailRecoveryCodeStore emailRecoveryCodeStore;
    private final VerifyRecoveryCode verifyRecoveryCode;
    private final Clock clock;

    public ConfirmRecoveryEmail(
            RecoveryEmailBindingRepository recoveryEmailBindingRepository,
            EmailRecoveryCodeStore emailRecoveryCodeStore,
            RecoveryCodeHasher recoveryCodeHasher,
            Clock clock) {
        this.recoveryEmailBindingRepository = Objects.requireNonNull(
                recoveryEmailBindingRepository, "recoveryEmailBindingRepository must not be null");
        this.emailRecoveryCodeStore =
                Objects.requireNonNull(emailRecoveryCodeStore, "emailRecoveryCodeStore must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.verifyRecoveryCode = new VerifyRecoveryCode(emailRecoveryCodeStore, recoveryCodeHasher, clock);
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub}
     *     (AR10, AD-5) — never taken from the request body.
     * @throws RecoveryCodeRejectedException if the code is wrong, expired, or attempt-exhausted, or
     *     if no pending binding exists any more (indistinguishable from a wrong code).
     */
    public void confirm(String keycloakUserId, String code) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");
        KeycloakUserId caller = new KeycloakUserId(keycloakUserId);

        verifyRecoveryCode.verify(caller, RecoveryCodePurpose.ATTACH_CONFIRM, code);

        RecoveryEmailBinding pendingBinding = recoveryEmailBindingRepository
                .findPendingFor(caller)
                .orElseThrow(() -> new RecoveryCodeRejectedException("no pending recovery email binding"));
        recoveryEmailBindingRepository.confirm(pendingBinding.confirm(clock.instant()));
        emailRecoveryCodeStore.delete(caller, RecoveryCodePurpose.ATTACH_CONFIRM);
    }
}
