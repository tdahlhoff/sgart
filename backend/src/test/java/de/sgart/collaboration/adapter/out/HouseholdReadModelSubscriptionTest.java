package de.sgart.collaboration.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.application.query.ListMyHouseholds;
import de.sgart.collaboration.application.query.ListMyHouseholds.HouseholdSummary;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.identity.adapter.out.JdbcMemberMappingRepository;
import de.sgart.identity.application.ListHouseholdsForCaller;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StreamId;
import io.kurrent.dbclient.KurrentDBClient;
import io.kurrent.dbclient.KurrentDBConnectionString;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Testcontainers integration test proving the household read side end-to-end through the <em>live
 * KurrentDB subscription</em> (Story 1.6, AC2/AC4) — the one runtime path returning-user routing
 * depends on that {@link HouseholdReadModelProjectorTest} could not cover by driving {@code
 * project(...)} directly: append via the real {@link KurrentDbEventStore} → the projector's live
 * subscription folds the events → {@link ListMyHouseholds} surfaces the household with its name.
 * Uses real KurrentDB {@code 25.1.4} and real PostgreSQL {@code 18.6}, owning both container
 * lifecycles; never points at the dev compose services.
 */
@Testcontainers
class HouseholdReadModelSubscriptionTest {

    @Container
    static final GenericContainer<?> KURRENTDB =
            new GenericContainer<>("docker.kurrent.io/kurrent-latest/kurrentdb:25.1.4")
                    .withExposedPorts(2113)
                    .withEnv("KURRENTDB_CLUSTER_SIZE", "1")
                    .withEnv("KURRENTDB_RUN_PROJECTIONS", "All")
                    .withEnv("KURRENTDB_START_STANDARD_PROJECTIONS", "true")
                    .withEnv("KURRENTDB_INSECURE", "true")
                    .withEnv("KURRENTDB_ENABLE_ATOM_PUB_OVER_HTTP", "true")
                    .waitingFor(Wait.forHttp("/health/live")
                            .forPort(2113)
                            .forStatusCode(204)
                            .withStartupTimeout(Duration.ofMinutes(2)));

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private static KurrentDBClient client;
    private static DataSource dataSource;

    private JdbcHouseholdReadModel readModel;
    private JdbcMemberMappingRepository mappingRepository;
    private KurrentDbEventStore eventStore;
    private HouseholdReadModelProjector projector;

    @BeforeAll
    static void startInfrastructure() {
        String connectionString =
                "esdb://" + KURRENTDB.getHost() + ":" + KURRENTDB.getMappedPort(2113) + "?tls=false";
        client = KurrentDBClient.create(KurrentDBConnectionString.parseOrThrow(connectionString));

        DriverManagerDataSource driverManagerDataSource = new DriverManagerDataSource();
        driverManagerDataSource.setUrl(POSTGRES.getJdbcUrl());
        driverManagerDataSource.setUsername(POSTGRES.getUsername());
        driverManagerDataSource.setPassword(POSTGRES.getPassword());
        dataSource = driverManagerDataSource;
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @AfterAll
    static void closeClient() {
        client.shutdown().join();
    }

    @BeforeEach
    void setUp() {
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("TRUNCATE TABLE household_read_model, household_membership_read_model, store_read_model").update();
        jdbcClient.sql("TRUNCATE TABLE identity_member_mapping").update();
        readModel = new JdbcHouseholdReadModel(jdbcClient);
        mappingRepository = new JdbcMemberMappingRepository(jdbcClient);
        eventStore = new KurrentDbEventStore(client);
        projector = new HouseholdReadModelProjector(
                client, readModel, new JdbcStoreReadModel(jdbcClient), new JdbcInviteReadModel(jdbcClient, Clock.systemUTC()));
        projector.start();
    }

    @AfterEach
    void tearDown() {
        projector.stop();
    }

    @Test
    void aHouseholdAppendedToKurrentDbSurfacesInFirstRunRoutingViaTheLiveSubscription() throws InterruptedException {
        String rawKeycloakUserId = "anna-sub";
        HouseholdId householdId = HouseholdId.generate();
        MemberId adminMemberId = MemberId.generate();
        mappingRepository.save(new MemberMapping(householdId, adminMemberId, new KeycloakUserId(rawKeycloakUserId)));
        Household household =
                Household.create(householdId, new HouseholdName("Familie Muster"), adminMemberId, CommandId.generate());

        eventStore.append(
                AggregateVersion.initial(StreamId.forHousehold(householdId)),
                household.uncommittedEvents(),
                CommandId.generate());

        awaitProjected(householdId);
        ListMyHouseholds listMyHouseholds =
                new ListMyHouseholds(new ListHouseholdsForCaller(mappingRepository), readModel);
        assertThat(listMyHouseholds.forCaller(rawKeycloakUserId))
                .containsExactly(new HouseholdSummary(householdId, "Familie Muster"));
    }

    /** Polls the eventually-consistent read model until the live subscription has projected the name. */
    private void awaitProjected(HouseholdId householdId) throws InterruptedException {
        for (int attempt = 0; attempt < 80; attempt++) {
            Map<HouseholdId, HouseholdName> names = readModel.namesFor(List.of(householdId));
            if (names.containsKey(householdId)) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("The live subscription did not project the household within the timeout");
    }
}
