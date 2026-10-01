package de.sgart.identity.application;

/** Read model of an account's recovery email: the masked hint of the confirmed binding, or {@code null} if none. */
public record RecoveryEmailStatus(String addressHint) {}
