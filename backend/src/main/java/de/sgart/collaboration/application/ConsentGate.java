package de.sgart.collaboration.application;

/**
 * Outbound port (Story 7.4, design §4): {@code collaboration} owns this abstraction and asks it
 * whether the caller has recorded consent before processing household personal data — the first
 * processing happens in {@code CreateHouseholdHandler}/{@code AcceptInviteHandler}. Implemented by
 * {@code collaboration.adapter.out.IdentityConsentGate}, which delegates to {@code identity}'s
 * {@code GetConsentStatus} query (the one sanctioned synchronous cross-context read, consistent
 * with the identity-ACL {@code MemberMapping} crossing). {@code collaboration.application} never
 * imports {@code identity} internals — this port is the whole of the crossing (AD-1/AD-2).
 */
public interface ConsentGate {

    /** @return whether {@code keycloakUserId} has a recorded consent row (AC3). */
    boolean hasRecordedConsent(String keycloakUserId);
}
