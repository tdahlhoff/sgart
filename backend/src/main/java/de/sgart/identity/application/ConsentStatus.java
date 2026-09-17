package de.sgart.identity.application;

import java.util.Objects;

/**
 * The side-effect-free read model {@link GetConsentStatus} returns (Story 7.4, AC2) — whether the
 * caller has ever accepted a notice, which version they accepted ({@code null} if none), and the
 * deployment's current notice version (D-E), so a client can decide whether to show the consent
 * screen without hard-coding the version itself. The exact transport shape {@code GET
 * /api/v1/consent} returns.
 */
public record ConsentStatus(boolean accepted, String acceptedVersion, String currentVersion) {

    public ConsentStatus {
        Objects.requireNonNull(currentVersion, "currentVersion must not be null");
    }
}
