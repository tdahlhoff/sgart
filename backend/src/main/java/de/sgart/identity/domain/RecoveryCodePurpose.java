package de.sgart.identity.domain;

/**
 * What a stored {@link EmailRecoveryCode} row proves (Story 7.3, design §4): {@code
 * ATTACH_CONFIRM} proves ownership of a newly attached address on the caller's own account, so it
 * belongs to that account; {@code RECOVER} proves ownership of a mailbox on a different device,
 * driving the R1 rebind, so it belongs to the address. One active code per {@code (subject,
 * purpose)}.
 */
public enum RecoveryCodePurpose {
    ATTACH_CONFIRM(RecoveryCodeSubject.Kind.ACCOUNT),
    RECOVER(RecoveryCodeSubject.Kind.ADDRESS);

    private final RecoveryCodeSubject.Kind subjectKind;

    RecoveryCodePurpose(RecoveryCodeSubject.Kind subjectKind) {
        this.subjectKind = subjectKind;
    }

    /** The kind of subject a code of this purpose belongs to; it is what lets a stored row be re-read. */
    public RecoveryCodeSubject.Kind subjectKind() {
        return subjectKind;
    }
}
