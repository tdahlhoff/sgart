package de.sgart.collaboration.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.application.query.ListMyHouseholds.HouseholdSummary;
import de.sgart.collaboration.application.query.ListMyHouseholds;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.StoreName;
import de.sgart.collaboration.domain.EmailHmac;
import de.sgart.collaboration.domain.HouseholdRole;
import de.sgart.collaboration.domain.event.HouseholdCreated;
import de.sgart.collaboration.domain.event.HouseholdRenamed;
import de.sgart.collaboration.domain.event.InviteExpired;
import de.sgart.collaboration.domain.event.MemberInvited;
import de.sgart.collaboration.domain.event.MemberJoined;
import de.sgart.collaboration.domain.event.StoreAdded;
import de.sgart.collaboration.domain.event.StoreArchived;
import de.sgart.collaboration.domain.readmodel.InviteView;
import de.sgart.collaboration.domain.readmodel.StoreView;
import de.sgart.identity.adapter.out.JdbcMemberMappingRepository;
import de.sgart.identity.application.ListHouseholdsForCaller;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.shared.CommandId;
import de.sgart.shared.EventId;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.InviteId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StoreChainId;
import de.sgart.shared.StoreId;
import io.kurrent.dbclient.KurrentDBClient;
import io.kurrent.dbclient.KurrentDBConnectionString;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

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
    private JdbcStoreReadModel storeReadModel;
    private JdbcInviteReadModel inviteReadModel;
    private JdbcMemberMappingRepository mappingRepository;
    private MutableClock clock;

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
        jdbcClient
                .sql("TRUNCATE TABLE household_read_model, household_membership_read_model, store_read_model, invite_read_model")
                .update();
        jdbcClient.sql("TRUNCATE TABLE identity_member_mapping").update();
        readModel = new JdbcHouseholdReadModel(jdbcClient);
        storeReadModel = new JdbcStoreReadModel(jdbcClient);
        clock = new MutableClock(Instant.now());
        inviteReadModel = new JdbcInviteReadModel(jdbcClient, clock);
        mappingRepository = new JdbcMemberMappingRepository(jdbcClient);
        // Never connected: project(...) never touches the KurrentDB client (only start() does).
        KurrentDBClient neverConnectedClient =
                KurrentDBClient.create(KurrentDBConnectionString.parseOrThrow("esdb://localhost:1?tls=false"));
        projector = new HouseholdReadModelProjector(neverConnectedClient, readModel, storeReadModel, inviteReadModel);
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
    void projectingStoreAddedYieldsAnActiveStoreRow() {
        HouseholdId householdId = HouseholdId.generate();
        StoreId storeId = StoreId.generate();
        StoreChainId chainId = StoreChainId.generate();

        projector.project(new StoreAdded(EventId.generate(), householdId, storeId, new StoreName("Edeka"), chainId));

        assertThat(storeReadModel.activeStoresOf(householdId))
                .containsExactly(new StoreView(storeId, new StoreName("Edeka"), chainId));
    }

    @Test
    void projectingStoreArchivedRemovesTheStoreFromTheActiveList() {
        HouseholdId householdId = HouseholdId.generate();
        StoreId storeId = StoreId.generate();
        projector.project(new StoreAdded(EventId.generate(), householdId, storeId, new StoreName("Edeka"), null));

        projector.project(new StoreArchived(EventId.generate(), householdId, storeId));

        assertThat(storeReadModel.activeStoresOf(householdId)).isEmpty();
    }

    @Test
    void reProjectingStoreAddedIsIdempotent() {
        HouseholdId householdId = HouseholdId.generate();
        StoreId storeId = StoreId.generate();
        StoreAdded added = new StoreAdded(EventId.generate(), householdId, storeId, new StoreName("Edeka"), null);

        projector.project(added);
        projector.project(added);

        assertThat(storeReadModel.activeStoresOf(householdId))
                .containsExactly(new StoreView(storeId, new StoreName("Edeka"), null));
    }

    @Test
    void projectingMemberInvitedYieldsAPendingInviteRow() {
        HouseholdId householdId = HouseholdId.generate();
        InviteId inviteId = InviteId.generate();
        MemberId invitedBy = MemberId.generate();
        // PostgreSQL TIMESTAMPTZ has microsecond precision; truncate so the round-tripped value
        // compares equal rather than losing sub-microsecond nanos.
        Instant invitedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        projector.project(new MemberInvited(
                EventId.generate(), householdId, inviteId, new EmailHmac("hmac-1"), invitedBy,
                HouseholdRole.PARTICIPANT, invitedAt));

        assertThat(inviteReadModel.pendingInvitesOf(householdId))
                .containsExactly(new InviteView(inviteId, invitedAt, invitedBy, "PENDING"));
    }

    @Test
    void projectingInviteExpiredRemovesTheInviteFromThePendingList() {
        HouseholdId householdId = HouseholdId.generate();
        InviteId inviteId = InviteId.generate();
        projector.project(new MemberInvited(
                EventId.generate(), householdId, inviteId, new EmailHmac("hmac-1"), MemberId.generate(),
                HouseholdRole.PARTICIPANT, Instant.now()));

        projector.project(new InviteExpired(EventId.generate(), householdId, inviteId));

        assertThat(inviteReadModel.pendingInvitesOf(householdId)).isEmpty();
    }

    @Test
    void twoHouseholdsPendingInvitesAreIsolatedFromEachOther() {
        HouseholdId firstHousehold = HouseholdId.generate();
        HouseholdId secondHousehold = HouseholdId.generate();
        InviteId firstInvite = InviteId.generate();
        InviteId secondInvite = InviteId.generate();
        projector.project(new MemberInvited(
                EventId.generate(), firstHousehold, firstInvite, new EmailHmac("hmac-1"), MemberId.generate(),
                HouseholdRole.PARTICIPANT, Instant.now()));
        projector.project(new MemberInvited(
                EventId.generate(), secondHousehold, secondInvite, new EmailHmac("hmac-2"), MemberId.generate(),
                HouseholdRole.PARTICIPANT, Instant.now()));

        assertThat(inviteReadModel.pendingInvitesOf(firstHousehold))
                .extracting(InviteView::inviteId)
                .containsExactly(firstInvite);
        assertThat(inviteReadModel.pendingInvitesOf(secondHousehold))
                .extracting(InviteView::inviteId)
                .containsExactly(secondInvite);
    }

    @Test
    void reProjectingMemberInvitedThenInviteExpiredIsIdempotent() {
        HouseholdId householdId = HouseholdId.generate();
        InviteId inviteId = InviteId.generate();
        MemberInvited invited = new MemberInvited(
                EventId.generate(), householdId, inviteId, new EmailHmac("hmac-1"), MemberId.generate(),
                HouseholdRole.PARTICIPANT, Instant.now());
        InviteExpired expired = new InviteExpired(EventId.generate(), householdId, inviteId);

        projector.project(invited);
        projector.project(invited);
        projector.project(expired);
        projector.project(expired);

        assertThat(inviteReadModel.pendingInvitesOf(householdId)).isEmpty();
    }

    @Test
    void pendingInvitesOf_derivesExpiryFromInvitedAtPlusTimeToLiveWithNoExplicitInviteExpiredEvent() {
        HouseholdId householdId = HouseholdId.generate();
        InviteId inviteId = InviteId.generate();
        Instant invitedAt = clock.instant();
        projector.project(new MemberInvited(
                EventId.generate(), householdId, inviteId, new EmailHmac("hmac-1"), MemberId.generate(),
                HouseholdRole.PARTICIPANT, invitedAt));

        clock.advanceBy(de.sgart.collaboration.domain.Invite.TIME_TO_LIVE.plusSeconds(1));

        assertThat(inviteReadModel.pendingInvitesOf(householdId)).isEmpty();
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

    /** A settable {@link Clock} for asserting {@link JdbcInviteReadModel}'s query-time derived
     * expiry (AC6) without waiting real time or faking a stored {@code invited_at}. */
    private static final class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceBy(java.time.Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
