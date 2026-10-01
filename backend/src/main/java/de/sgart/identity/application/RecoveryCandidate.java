package de.sgart.identity.application;

import java.util.List;

/**
 * An account a mailbox can recover, as shown in the recovery picker. Plain strings only, so the
 * inbound adapter never touches the domain. {@code accountId} is the candidate's pseudonymous
 * account id, revealed only to a caller who has just proven the mailbox.
 */
public record RecoveryCandidate(String accountId, List<Household> households) {

    /** One household of the candidate; either text is empty when it is unknown (the app shows a neutral label). */
    public record Household(String householdName, String nickname) {}
}
