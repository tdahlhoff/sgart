package de.sgart.identity.adapter.out;

import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.identity.domain.MemberMappingRepository;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Durable PostgreSQL {@link MemberMappingRepository} (deferred from Story 1.4, landing with the
 * first writer, Story 1.6). Plain SQL over {@link JdbcClient} — the mapping is a simple row, so
 * JPA would be ceremony without benefit (Clarification 3, KISS/YAGNI). Schema: {@code
 * db/migration/V1__identity_member_mapping.sql}.
 */
public final class JdbcMemberMappingRepository implements MemberMappingRepository {

    private final JdbcClient jdbcClient;

    public JdbcMemberMappingRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public Optional<MemberId> findMemberId(KeycloakUserId keycloakUserId, HouseholdId householdId) {
        return jdbcClient
                .sql("""
                        SELECT member_id FROM identity_member_mapping
                        WHERE household_id = :householdId AND keycloak_user_id = :keycloakUserId
                        """)
                .param("householdId", householdId.value())
                .param("keycloakUserId", keycloakUserId.value())
                .query(UUID.class)
                .optional()
                .map(MemberId::new);
    }

    @Override
    public void save(MemberMapping mapping) {
        jdbcClient
                .sql("""
                        INSERT INTO identity_member_mapping (household_id, member_id, keycloak_user_id)
                        VALUES (:householdId, :memberId, :keycloakUserId)
                        """)
                .param("householdId", mapping.householdId().value())
                .param("memberId", mapping.memberId().value())
                .param("keycloakUserId", mapping.keycloakUserId().value())
                .update();
    }

    @Override
    public List<HouseholdId> householdIdsFor(KeycloakUserId keycloakUserId) {
        return jdbcClient
                .sql("SELECT household_id FROM identity_member_mapping WHERE keycloak_user_id = :keycloakUserId")
                .param("keycloakUserId", keycloakUserId.value())
                .query(UUID.class)
                .list()
                .stream()
                .map(HouseholdId::new)
                .toList();
    }
}
