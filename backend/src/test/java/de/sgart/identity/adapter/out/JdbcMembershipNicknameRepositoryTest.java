package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.identity.domain.MembershipNickname;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.util.List;
import java.util.Map;
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
 * Testcontainers integration test against real PostgreSQL (mirrors {@code
 * JdbcMemberMappingRepositoryTest}) — the adapter/schema pair proving the durable per-household
 * nickname store (Story 8.3, {@code V22__membership_nickname.sql}). Owns its own container
 * lifecycle.
 */
@Testcontainers
class JdbcMembershipNicknameRepositoryTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private static DataSource dataSource;

    private JdbcMembershipNicknameRepository repository;
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
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE membership_nickname").update();
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE identity_member_mapping").update();
        repository = new JdbcMembershipNicknameRepository(JdbcClient.create(dataSource));
        mappingRepository = new JdbcMemberMappingRepository(JdbcClient.create(dataSource));
    }

    @Test
    void save_writesARowThatFindReadsBack() {
        KeycloakUserId keycloakUserId = new KeycloakUserId("anna-sub");
        HouseholdId householdId = HouseholdId.generate();

        repository.save(new MembershipNickname(keycloakUserId, householdId, "Papa"));

        assertThat(repository.find(keycloakUserId, householdId)).contains("Papa");
    }

    @Test
    void save_overwritesAPreviouslySavedNickname() {
        KeycloakUserId keycloakUserId = new KeycloakUserId("anna-sub");
        HouseholdId householdId = HouseholdId.generate();
        repository.save(new MembershipNickname(keycloakUserId, householdId, "Papa"));

        repository.save(new MembershipNickname(keycloakUserId, householdId, "Timo"));

        assertThat(repository.find(keycloakUserId, householdId)).contains("Timo");
    }

    @Test
    void find_isEmptyForAnUnknownPair() {
        assertThat(repository.find(new KeycloakUserId("unknown-sub"), HouseholdId.generate())).isEmpty();
    }

    /**
     * I/O matrix, "Same nickname as another member" — no uniqueness constraint on {@code
     * membership_nickname} (locked decision, Story 8.3): two different people in the same
     * household can both persist the identical nickname and both rows survive.
     */
    @Test
    void save_allowsTwoDifferentPeopleInTheSameHouseholdToShareTheIdenticalNickname() {
        HouseholdId householdId = HouseholdId.generate();
        KeycloakUserId annaId = new KeycloakUserId("anna-sub");
        KeycloakUserId bobId = new KeycloakUserId("bob-sub");

        repository.save(new MembershipNickname(annaId, householdId, "Peter"));
        repository.save(new MembershipNickname(bobId, householdId, "Peter"));

        assertThat(repository.find(annaId, householdId)).contains("Peter");
        assertThat(repository.find(bobId, householdId)).contains("Peter");
    }

    /**
     * I/O matrix, "Two households, different nicknames" — the nickname is keyed per membership,
     * not per account: the same person holds independent nicknames in two different households.
     */
    @Test
    void save_letsTheSamePersonHoldIndependentNicknamesInTwoDifferentHouseholds() {
        KeycloakUserId keycloakUserId = new KeycloakUserId("anna-sub");
        HouseholdId firstHousehold = HouseholdId.generate();
        HouseholdId secondHousehold = HouseholdId.generate();

        repository.save(new MembershipNickname(keycloakUserId, firstHousehold, "Papa"));
        repository.save(new MembershipNickname(keycloakUserId, secondHousehold, "Timo"));

        assertThat(repository.find(keycloakUserId, firstHousehold)).contains("Papa");
        assertThat(repository.find(keycloakUserId, secondHousehold)).contains("Timo");
    }

    @Test
    void resolveForHousehold_resolvesTheSetNicknamesAndOmitsUnsetOnes() {
        HouseholdId householdId = HouseholdId.generate();
        MemberId annaMemberId = MemberId.generate();
        MemberId bobMemberId = MemberId.generate();
        mappingRepository.save(new MemberMapping(householdId, annaMemberId, new KeycloakUserId("anna-sub")));
        mappingRepository.save(new MemberMapping(householdId, bobMemberId, new KeycloakUserId("bob-sub")));
        repository.save(new MembershipNickname(new KeycloakUserId("anna-sub"), householdId, "Anna"));

        Map<MemberId, String> resolved =
                repository.resolveForHousehold(householdId, List.of(annaMemberId, bobMemberId));

        assertThat(resolved).containsExactly(Map.entry(annaMemberId, "Anna"));
    }

    @Test
    void deleteFor_removesEveryRowForThatPersonAcrossHouseholds() {
        KeycloakUserId keycloakUserId = new KeycloakUserId("anna-sub");
        HouseholdId firstHousehold = HouseholdId.generate();
        HouseholdId secondHousehold = HouseholdId.generate();
        repository.save(new MembershipNickname(keycloakUserId, firstHousehold, "Papa"));
        repository.save(new MembershipNickname(keycloakUserId, secondHousehold, "Timo"));

        repository.deleteFor(keycloakUserId);

        assertThat(repository.find(keycloakUserId, firstHousehold)).isEmpty();
        assertThat(repository.find(keycloakUserId, secondHousehold)).isEmpty();
    }

    @Test
    void deleteForMembership_removesOnlyThatMembersRow() {
        HouseholdId householdId = HouseholdId.generate();
        MemberId annaMemberId = MemberId.generate();
        MemberId bobMemberId = MemberId.generate();
        mappingRepository.save(new MemberMapping(householdId, annaMemberId, new KeycloakUserId("anna-sub")));
        mappingRepository.save(new MemberMapping(householdId, bobMemberId, new KeycloakUserId("bob-sub")));
        repository.save(new MembershipNickname(new KeycloakUserId("anna-sub"), householdId, "Anna"));
        repository.save(new MembershipNickname(new KeycloakUserId("bob-sub"), householdId, "Bob"));

        repository.deleteForMembership(householdId, annaMemberId);

        assertThat(repository.find(new KeycloakUserId("anna-sub"), householdId)).isEmpty();
        assertThat(repository.find(new KeycloakUserId("bob-sub"), householdId)).contains("Bob");
    }

    @Test
    void deleteForMembership_isIdempotent() {
        HouseholdId householdId = HouseholdId.generate();

        repository.deleteForMembership(householdId, MemberId.generate());

        assertThat(repository.find(new KeycloakUserId("anna-sub"), householdId)).isEmpty();
    }

    @Test
    void deleteAllForHousehold_removesEveryRowForTheHouseholdAndNoneOfAnothers() {
        HouseholdId householdToDelete = HouseholdId.generate();
        HouseholdId otherHousehold = HouseholdId.generate();
        repository.save(new MembershipNickname(new KeycloakUserId("anna-sub"), householdToDelete, "Anna"));
        repository.save(new MembershipNickname(new KeycloakUserId("anna-sub"), otherHousehold, "Anna2"));

        repository.deleteAllForHousehold(householdToDelete);

        assertThat(repository.find(new KeycloakUserId("anna-sub"), householdToDelete)).isEmpty();
        assertThat(repository.find(new KeycloakUserId("anna-sub"), otherHousehold)).contains("Anna2");
    }
}
