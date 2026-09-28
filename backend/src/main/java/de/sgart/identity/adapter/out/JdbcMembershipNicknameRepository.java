package de.sgart.identity.adapter.out;

import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MembershipNickname;
import de.sgart.identity.domain.MembershipNicknameRepository;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Durable PostgreSQL {@link MembershipNicknameRepository} (Story 8.3). Plain SQL over {@link
 * JdbcClient}, mirroring {@code JdbcMemberMappingRepository} — a simple row, so JPA would be
 * ceremony without benefit (KISS/YAGNI). Schema: {@code
 * db/migration/V22__membership_nickname.sql}.
 */
public final class JdbcMembershipNicknameRepository implements MembershipNicknameRepository {

    private final JdbcClient jdbcClient;

    public JdbcMembershipNicknameRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public void save(MembershipNickname membershipNickname) {
        jdbcClient
                .sql("""
                        INSERT INTO membership_nickname (keycloak_user_id, household_id, nickname, updated_at)
                        VALUES (:keycloakUserId, :householdId, :nickname, :updatedAt)
                        ON CONFLICT (keycloak_user_id, household_id)
                        DO UPDATE SET nickname = EXCLUDED.nickname, updated_at = EXCLUDED.updated_at
                        """)
                .param("keycloakUserId", membershipNickname.keycloakUserId().value())
                .param("householdId", membershipNickname.householdId().value())
                .param("nickname", membershipNickname.nickname())
                .param("updatedAt", Timestamp.from(Instant.now()))
                .update();
    }

    @Override
    public Optional<String> find(KeycloakUserId keycloakUserId, HouseholdId householdId) {
        return jdbcClient
                .sql("""
                        SELECT nickname FROM membership_nickname
                        WHERE keycloak_user_id = :keycloakUserId AND household_id = :householdId
                        """)
                .param("keycloakUserId", keycloakUserId.value())
                .param("householdId", householdId.value())
                .query(String.class)
                .optional();
    }

    @Override
    public Map<MemberId, String> resolveForHousehold(HouseholdId householdId, List<MemberId> memberIds) {
        if (memberIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> rawMemberIds = memberIds.stream().map(MemberId::value).toList();
        List<NicknameRow> rows = jdbcClient
                .sql("""
                        SELECT mapping.member_id AS member_id, nickname.nickname AS nickname
                        FROM identity_member_mapping mapping
                        JOIN membership_nickname nickname
                          ON nickname.keycloak_user_id = mapping.keycloak_user_id
                         AND nickname.household_id = mapping.household_id
                        WHERE mapping.household_id = :householdId
                          AND mapping.member_id IN (:memberIds)
                        """)
                .param("householdId", householdId.value())
                .param("memberIds", rawMemberIds)
                .query((resultSet, rowNumber) -> new NicknameRow(
                        new MemberId(UUID.fromString(resultSet.getString("member_id"))),
                        resultSet.getString("nickname")))
                .list();

        Map<MemberId, String> nicknamesByMemberId = new HashMap<>();
        rows.forEach(row -> nicknamesByMemberId.put(row.memberId(), row.nickname()));
        return Map.copyOf(nicknamesByMemberId);
    }

    private record NicknameRow(MemberId memberId, String nickname) {}

    @Override
    public void deleteFor(KeycloakUserId keycloakUserId) {
        jdbcClient
                .sql("DELETE FROM membership_nickname WHERE keycloak_user_id = :keycloakUserId")
                .param("keycloakUserId", keycloakUserId.value())
                .update();
    }

    @Override
    public void deleteForMembership(HouseholdId householdId, MemberId memberId) {
        jdbcClient
                .sql("""
                        DELETE FROM membership_nickname
                        WHERE household_id = :householdId
                          AND keycloak_user_id = (
                              SELECT keycloak_user_id FROM identity_member_mapping
                              WHERE household_id = :householdId AND member_id = :memberId
                          )
                        """)
                .param("householdId", householdId.value())
                .param("memberId", memberId.value())
                .update();
    }

    @Override
    public void deleteAllForHousehold(HouseholdId householdId) {
        jdbcClient
                .sql("DELETE FROM membership_nickname WHERE household_id = :householdId")
                .param("householdId", householdId.value())
                .update();
    }
}
