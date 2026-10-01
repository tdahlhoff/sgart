package de.sgart.identity.application;

import java.util.List;

/** What a recovery confirmation led to: the device was rebound, or the person must pick one of several accounts first. */
public sealed interface EmailRecoveryOutcome {

    /** The device now authenticates into the recovered account. */
    record Rebound() implements EmailRecoveryOutcome {}

    /** The mailbox is bound to several accounts; the code is still valid and the person chooses one. */
    record ChooseAccount(List<RecoveryCandidate> candidates) implements EmailRecoveryOutcome {}
}
