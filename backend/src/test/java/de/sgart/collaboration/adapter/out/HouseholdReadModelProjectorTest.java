package de.sgart.collaboration.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.application.ListMyHouseholds;
import de.sgart.collaboration.application.ListMyHouseholds.HouseholdSummary;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.HouseholdRenamed;
import de.sgart.identity.adapter.out.JdbcMemberMappingRepository;
import de.sgart.identity.application.ListHouseholdsForCaller;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.CommandId;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import io.kurrent.dbclient.KurrentDBClient;
import io.kurrent.dbclient.KurrentDBConnectionString;
import java.util.List;
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
 * Testcontainers integration test against real PostgreSQL (Clarification 2) — the first CQRS read
 * side: {@link HouseholdReadModelProjector} folding {@code HouseholdCreated}/{@code MemberJoined}
 * into {@link JdbcHouseholdReadModel}, and {@link ListMyHouseholds} reading it back composed with
 * the (also real) Identity ACL mapping (AC2). Drives the projector's {@code project(...)} method
 * directly — deterministic and fast — rather than its live KurrentDB subscription, which is a
 * thin, separately-risked wrapper over the vendor's own subscription mechanics (see {@code
 * KurrentDbEventStoreTest} for the proven KurrentDB read/write path). Owns its own container
 * lifecycle; never points at the dev compose PostgreSQL.
 */
@Testcontainers
class HouseholdReadModelProjectorTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private static DataSource dataSource;

    private HouseholdReadModelProjector projector;
    private JdbcHouseholdReadModel readModel;
    private JdbcMemberMappingRepository mappingRepository;

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
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("TRUNCATE TABLE household_read_model, household_membership_read_model").update();
        jdbcClient.sql("TRUNCATE TABLE identity_member_mapping").update();
        readModel = new JdbcHouseholdReadModel(jdbcClient);
        mappingRepository = new JdbcMemberMappingRepository(jdbcClient);
        // Never connected: project(...) never touches the KurrentDB client (only start() does).
        KurrentDBClient neverConnectedClient =
                KurrentDBClient.create(KurrentDBConnectionString.parseOrThrow("esdb://localhost:1?tls=false"));
        projector = new HouseholdReadModelProjector(neverConnectedClient, readModel);
    }

    @Test
    void projectingHouseholdCreatedAndMemberJoinedYieldsTheReadModelRows() {
        MemberId adminMemberId = MemberId.generate();
        Household household = Household.create(
                HouseholdId.generate(), new HouseholdName("Familie Muster"), adminMemberId, CommandId.generate());
        household.uncommittedEvents().forEach(projector::project);

        assertThat(readModel.namesFor(List.of(household.householdId())))
                .containsEntry(household.householdId(), household.name());
        Long membershipRowCount = JdbcClient.create(dataSource)
                .sql("SELECT COUNT(*) FROM household_membership_read_model WHERE household_id = :householdId AND member_id = :memberId")
                .param("householdId", household.householdId().value())
                .param("memberId", adminMemberId.value())
                .query(Long.class)
                .single();
        assertThat(membershipRowCount).isEqualTo(1L);
    }

    @Test
    void projectingHouseholdRenamedUpdatesTheReadModelToTheNewName() {
        HouseholdId householdId = HouseholdId.generate();
        Household household = Household.create(
                householdId, new HouseholdName("Familie Muster"), MemberId.generate(), CommandId.generate());
        household.uncommittedEvents().forEach(projector::project);

        projector.project(new HouseholdRenamed(EventId.generate(), householdId, new HouseholdName("Familie Beispiel")));

        assertThat(readModel.namesFor(List.of(householdId)))
                .containsEntry(householdId, new HouseholdName("Familie Beispiel"));
    }

    @Test
    void listMyHouseholds_returnsEmptyForACallerWithZeroHouseholds() {
        ListMyHouseholds listMyHouseholds =
                new ListMyHouseholds(new ListHouseholdsForCaller(mappingRepository), readModel);

        assertThat(listMyHouseholds.forCaller("stranger-sub")).isEmpty();
    }

    @Test
    void listMyHouseholds_returnsBothHouseholdsForACallerInTwo() {
        String rawKeycloakUserId = "anna-sub";
        KeycloakUserId keycloakUserId = new KeycloakUserId(rawKeycloakUserId);
        Household first = Household.create(
                HouseholdId.generate(), new HouseholdName("Familie Muster"), MemberId.generate(), CommandId.generate());
        Household second = Household.create(
                HouseholdId.generate(), new HouseholdName("WG Sonnenallee"), MemberId.generate(), CommandId.generate());
        first.uncommittedEvents().forEach(projector::project);
        second.uncommittedEvents().forEach(projector::project);
        mappingRepository.save(new MemberMapping(first.householdId(), MemberId.generate(), keycloakUserId));
        mappingRepository.save(new MemberMapping(second.householdId(), MemberId.generate(), keycloakUserId));

        ListMyHouseholds listMyHouseholds =
                new ListMyHouseholds(new ListHouseholdsForCaller(mappingRepository), readModel);

        List<HouseholdSummary> summaries = listMyHouseholds.forCaller(rawKeycloakUserId);

        assertThat(summaries)
                .containsExactlyInAnyOrder(
                        new HouseholdSummary(first.householdId(), first.name().value()),
                        new HouseholdSummary(second.householdId(), second.name().value()));
    }
}
