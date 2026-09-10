package de.sgart.identity.domain;

/**
 * The controlled vocabulary of push transports a {@link DeviceToken} can belong to (Story 4.5,
 * AC5). Deliberately just the two mobile platforms SGART ships for — no web/desktop value until a
 * real need exists (YAGNI).
 */
public enum DevicePlatform {
    ANDROID,
    IOS
}
