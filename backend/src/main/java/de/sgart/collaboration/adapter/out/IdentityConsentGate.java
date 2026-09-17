package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.application.ConsentGate;
import de.sgart.identity.application.GetConsentStatus;
import java.util.Objects;

/**
 * Implements {@link ConsentGate} by delegating to {@code identity}'s {@link GetConsentStatus}
 * query (Story 7.4, design §4) — the one sanctioned synchronous cross-context read, consistent
 * with the identity-ACL {@code MemberMapping} crossing (mapping = access). Lives in {@code
 * adapter.out}, never in {@code collaboration.application}, so the ArchUnit rule that keeps {@code
 * collaboration.application} from reaching another context's internals stays clean — only this
 * adapter, the driven side of the port, knows {@code identity} exists.
 */
public final class IdentityConsentGate implements ConsentGate {

    private final GetConsentStatus getConsentStatus;

    public IdentityConsentGate(GetConsentStatus getConsentStatus) {
        this.getConsentStatus = Objects.requireNonNull(getConsentStatus, "getConsentStatus must not be null");
    }

    @Override
    public boolean hasRecordedConsent(String keycloakUserId) {
        return getConsentStatus.statusFor(keycloakUserId).accepted();
    }
}
