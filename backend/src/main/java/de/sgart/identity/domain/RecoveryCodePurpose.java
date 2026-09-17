package de.sgart.identity.domain;

/**
 * What a stored {@link EmailRecoveryCode} row proves (Story 7.3, design §4): {@code
 * ATTACH_CONFIRM} proves ownership of a newly attached email on the caller's own account;
 * {@code RECOVER} proves ownership of an already-confirmed email on a different device, driving
 * the R1 rebind. One active code per {@code (keycloakUserId, purpose)}.
 */
public enum RecoveryCodePurpose {
    ATTACH_CONFIRM,
    RECOVER
}
