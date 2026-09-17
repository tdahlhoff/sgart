package de.sgart.identity.application;

import de.sgart.identity.domain.EmailRecoveryCodeStore;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.RecoveryCodePurpose;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Requests a recover-by-email code on a fresh device (Story 7.3, AC2, D-H): looks the account up
 * by email and, <strong>only if found</strong>, stores and sends a code. The caller always
 * "succeeds" from the outside (the controller answers {@code 202} regardless) and this service
 * never signals which case occurred in its outcome (no account/email enumeration). The known- and
 * unknown-email paths are <em>not</em> timing-equalized — the known path additionally hashes,
 * stores, and sends — so the abuse-resistance posture rests on the constant {@code 202} response
 * plus rate-limiting at the reverse-proxy seam (ADR-0002), not on timing parity between the two
 * paths (Story 7.3 review finding: corrects an earlier overstated claim here).
 */
public final class RequestEmailRecoveryCode {

    private final FindAccountByEmail findAccountByEmail;
    private final EmailRecoveryCodeStore emailRecoveryCodeStore;
    private final RecoveryCodeHasher recoveryCodeHasher;
    private final SendRecoveryCodeEmail sendRecoveryCodeEmail;
    private final Clock clock;

    public RequestEmailRecoveryCode(
            FindAccountByEmail findAccountByEmail,
            EmailRecoveryCodeStore emailRecoveryCodeStore,
            RecoveryCodeHasher recoveryCodeHasher,
            SendRecoveryCodeEmail sendRecoveryCodeEmail,
            Clock clock) {
        this.findAccountByEmail = Objects.requireNonNull(findAccountByEmail, "findAccountByEmail must not be null");
        this.emailRecoveryCodeStore =
                Objects.requireNonNull(emailRecoveryCodeStore, "emailRecoveryCodeStore must not be null");
        this.recoveryCodeHasher = Objects.requireNonNull(recoveryCodeHasher, "recoveryCodeHasher must not be null");
        this.sendRecoveryCodeEmail =
                Objects.requireNonNull(sendRecoveryCodeEmail, "sendRecoveryCodeEmail must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** @throws InvalidRecoveryEmailException if {@code rawEmail} is missing or not a plausible address. */
    public void request(String rawEmail) {
        String email = RecoveryEmailValidation.validated(rawEmail);

        Optional<KeycloakUserId> target = findAccountByEmail.findByEmail(email);
        if (target.isEmpty()) {
            return;
        }

        String code = RecoveryCode.generate();
        emailRecoveryCodeStore.store(
                target.get(), RecoveryCodePurpose.RECOVER, recoveryCodeHasher.hash(code), RecoveryCode.expiresAt(clock),
                clock.instant());
        sendRecoveryCodeEmail.send(email, code);
    }
}
