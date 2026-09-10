package de.sgart.collaboration.application;

/**
 * The outcome of one {@link ContentFreePushSender#send} call (Story 4.5, AC5). {@code
 * TOKEN_INVALID} is the transport's "this token is dead" signal (e.g. FCM's {@code UNREGISTERED}
 * response) — the notification fan-out reacts to it by pruning the token through Identity's {@code
 * PruneDeviceToken} port, never by inspecting a transport-specific exception type.
 */
public enum PushDeliveryResult {
    DELIVERED,
    TOKEN_INVALID
}
