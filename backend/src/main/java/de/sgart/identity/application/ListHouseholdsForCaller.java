package de.sgart.identity.application;

import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMappingRepository;
import de.sgart.shared.HouseholdId;
import java.util.List;
import java.util.Objects;

/**
 * The Identity ACL's caller-lookup port — {@code keycloakUserId -> [householdId]} (AD-5). A query
 * use case, published for first-run routing (Story 1.6): the Collaboration
 * {@code ListMyHouseholds} query calls this across the context boundary rather than reaching into
 * {@code identity.domain} or its mapping table directly (AD-2). The published signature takes a
 * plain {@code String}, never {@link KeycloakUserId} — that type stays contained within Identity.
 */
public final class ListHouseholdsForCaller {

    private final MemberMappingRepository memberMappingRepository;

    public ListHouseholdsForCaller(MemberMappingRepository memberMappingRepository) {
        this.memberMappingRepository =
                Objects.requireNonNull(memberMappingRepository, "memberMappingRepository must not be null");
    }

    public List<HouseholdId> forCaller(String keycloakUserId) {
        return memberMappingRepository.householdIdsFor(new KeycloakUserId(keycloakUserId));
    }
}
