package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Testcontainers integration test against real PostgreSQL (Clarification 2) — the adapter/schema
 * pair proving the durable Identity ACL mapping (deferred from Story 1.4). Owns its own container
 * lifecycle; never points at the dev compose Postgres (Story 1.4's Keycloak precedent).
 */
@Testcontainers
class JdbcMemberMappingRepositoryTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private static DataSource dataSource;

    private JdbcMemberMappingRepository repository;

    @BeforeAll
    static void migrateDatabase() {
        DriverManagerDataSource driverManagerDataSource = new DriverManagerDataSource();
        driverManagerDataSource.setUrl(POSTGRES.getJdbcUrl());
        driverManagerDataSource.setUsername(POSTGRES.getUsername());
        driverManagerDataSource.setPassword(POSTGRES.getPassword());
        dataSource = driverManagerDataSource;
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @BeforeEach
    void setUp() {
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE identity_member_mapping").update();
        repository = new JdbcMemberMappingRepository(JdbcClient.create(dataSource));
    }

    @Test
    void save_writesARowThatFindMemberIdReadsBack() {
        KeycloakUserId keycloakUserId = new KeycloakUserId("anna-sub");
        HouseholdId householdId = HouseholdId.generate();
        MemberId memberId = MemberId.generate();

        repository.save(new MemberMapping(householdId, memberId, keycloakUserId));

        assertThat(repository.findMemberId(keycloakUserId, householdId)).contains(memberId);
    }

    @Test
    void findMemberId_isEmptyForAnUnknownPair() {
        assertThat(repository.findMemberId(new KeycloakUserId("unknown-sub"), HouseholdId.generate())).isEmpty();
    }

    @Test
    void twoMintsForTheSameKeycloakUserInTwoHouseholdsYieldTwoUnrelatedMemberIdsBothLocatable() {
        KeycloakUserId keycloakUserId = new KeycloakUserId("anna-sub");
        HouseholdId firstHousehold = HouseholdId.generate();
        HouseholdId secondHousehold = HouseholdId.generate();
        MemberId memberIdInFirstHousehold = MemberId.generate();
        MemberId memberIdInSecondHousehold = MemberId.generate();

        repository.save(new MemberMapping(firstHousehold, memberIdInFirstHousehold, keycloakUserId));
        repository.save(new MemberMapping(secondHousehold, memberIdInSecondHousehold, keycloakUserId));

        assertThat(memberIdInFirstHousehold).isNotEqualTo(memberIdInSecondHousehold);
        assertThat(repository.householdIdsFor(keycloakUserId))
                .containsExactlyInAnyOrder(firstHousehold, secondHousehold);
    }

    @Test
    void householdIdsFor_isEmptyForAPersonWithNoMappings() {
        assertThat(repository.householdIdsFor(new KeycloakUserId("stranger-sub"))).isEmpty();
    }
}
