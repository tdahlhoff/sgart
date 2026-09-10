package de.sgart.collaboration.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.application.ChangeNudge;
import de.sgart.collaboration.application.ContentFreePushSender;
import de.sgart.collaboration.application.PushDeliveryResult;
import de.sgart.collaboration.domain.event.ItemAdded;
import de.sgart.collaboration.domain.ItemName;
import de.sgart.identity.adapter.out.InMemoryDeviceTokenRepository;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.application.PruneDeviceToken;
import de.sgart.identity.application.PushTarget;
import de.sgart.identity.application.ResolveHouseholdPushTargets;
import de.sgart.identity.domain.DevicePlatform;
import de.sgart.identity.domain.DeviceToken;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ItemId;
import de.sgart.shared.MemberId;
import de.sgart.shared.Quantity;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StreamId;
import de.sgart.shared.Unit;
import io.kurrent.dbclient.KurrentDBClient;
import io.kurrent.dbclient.KurrentDBConnectionString;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
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
 * Testcontainers integration test proving the notification fan-out end-to-end through the <em>real
 * live {@code fromEnd} KurrentDB subscription</em> (Story 4.5, AC2/AC4) — the one path {@link
 * HouseholdNotificationFanoutTest}'s direct {@code react(...)} calls cannot cover. Mirrors {@link
 * HouseholdLiveSyncFanoutIntegrationTest} exactly: append via the real {@link KurrentDbEventStore}
 * → the fan-out's live subscription observes it → exactly one push per recipient device; a second
 * list-changed event within the debounce window is suppressed.
 */
@Testcontainers
class HouseholdNotificationFanoutIntegrationTest {

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

    private KurrentDbEventStore eventStore;
    private HouseholdNotificationFanout fanout;
    private RecordingPushSender pushSender;
    private InMemoryMemberMappingRepository memberMappingRepository;
    private InMemoryDeviceTokenRepository deviceTokenRepository;

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
        jdbcClient.sql("TRUNCATE TABLE shopping_list_read_model, item_read_model").update();
        eventStore = new KurrentDbEventStore(client);
        pushSender = new RecordingPushSender();
        memberMappingRepository = new InMemoryMemberMappingRepository();
        deviceTokenRepository = new InMemoryDeviceTokenRepository();
        HouseholdResolver resolver =
                new HouseholdResolver(new JdbcShoppingListReadModel(jdbcClient), new JdbcTripStoreReadModel(jdbcClient));
        fanout = new HouseholdNotificationFanout(
                client,
                pushSender,
                new ResolveHouseholdPushTargets(memberMappingRepository, deviceTokenRepository),
                new PruneDeviceToken(deviceTokenRepository),
                resolver,
                Clock.systemUTC(),
                Duration.ofMinutes(5));
        fanout.start();
    }

    @AfterEach
    void tearDown() {
        fanout.stop();
    }

    @Test
    void aListChangedEventPushesExactlyOncePerRecipientDeviceAndDebouncesASecondWithinTheWindow()
            throws InterruptedException {
        HouseholdId householdId = HouseholdId.generate();
        KeycloakUserId keycloakUserId = new KeycloakUserId("anna-sub");
        memberMappingRepository.save(new MemberMapping(householdId, MemberId.generate(), keycloakUserId));
        deviceTokenRepository.upsert(
                new DeviceToken(keycloakUserId, "anna-phone", DevicePlatform.ANDROID, java.time.Instant.now()));
        ShoppingListId listId = ShoppingListId.generate();
        new JdbcShoppingListReadModel(JdbcClient.create(dataSource)).insertList(householdId, listId, null);

        eventStore.append(
                AggregateVersion.initial(StreamId.forList(listId)),
                List.of(new ItemAdded(
                        EventId.generate(),
                        householdId,
                        listId,
                        ItemId.generate(),
                        new ItemName("Milch"),
                        null,
                        new Quantity(BigDecimal.ONE, Unit.PIECE))),
                CommandId.generate());
        awaitPushCount(1);

        // A second list-changed event for the same list, immediately after — suppressed by the
        // 5-minute debounce window (AC2).
        eventStore.append(
                AggregateVersion.of(StreamId.forList(listId), 1),
                List.of(new ItemAdded(
                        EventId.generate(),
                        householdId,
                        listId,
                        ItemId.generate(),
                        new ItemName("Brot"),
                        null,
                        new Quantity(BigDecimal.ONE, Unit.PIECE))),
                CommandId.generate());
        Thread.sleep(2000); // negative wait: prove the second event never arrives, not just "not yet"

        assertThat(pushSender.sent).hasSize(1);
        SentPush onlyPush = pushSender.sent.peek();
        assertThat(onlyPush.target()).isEqualTo(new PushTarget("anna-phone", "ANDROID"));
        assertThat(onlyPush.nudge()).isEqualTo(new ChangeNudge(householdId, "list"));
    }

    /** "mapping = access" (AC4): no mapping/device registered for the household → no push. */
    @Test
    void aListChangedEventForAHouseholdWithNoRegisteredRecipientsPushesNothing() throws InterruptedException {
        HouseholdId householdId = HouseholdId.generate();
        ShoppingListId listId = ShoppingListId.generate();
        new JdbcShoppingListReadModel(JdbcClient.create(dataSource)).insertList(householdId, listId, null);

        eventStore.append(
                AggregateVersion.initial(StreamId.forList(listId)),
                List.of(new ItemAdded(
                        EventId.generate(),
                        householdId,
                        listId,
                        ItemId.generate(),
                        new ItemName("Milch"),
                        null,
                        new Quantity(BigDecimal.ONE, Unit.PIECE))),
                CommandId.generate());

        Thread.sleep(2000);
        assertThat(pushSender.sent).isEmpty();
    }

    private void awaitPushCount(int expectedCount) throws InterruptedException {
        for (int attempt = 0; attempt < 80; attempt++) {
            if (pushSender.sent.size() >= expectedCount) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("expected at least " + expectedCount + " push(es) within the timeout, got "
                + pushSender.sent.size());
    }

    private record SentPush(PushTarget target, ChangeNudge nudge) {}

    private static final class RecordingPushSender implements ContentFreePushSender {
        final ConcurrentLinkedQueue<SentPush> sent = new ConcurrentLinkedQueue<>();

        @Override
        public PushDeliveryResult send(PushTarget target, ChangeNudge nudge) {
            sent.add(new SentPush(target, nudge));
            return PushDeliveryResult.DELIVERED;
        }
    }
}
