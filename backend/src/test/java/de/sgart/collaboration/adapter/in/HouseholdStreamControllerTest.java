package de.sgart.collaboration.adapter.in;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.collaboration.domain.event.MemberRemoved;
import de.sgart.identity.adapter.out.InMemoryMemberMappingRepository;
import de.sgart.identity.domain.KeycloakUserId;
import de.sgart.identity.domain.MemberMapping;
import de.sgart.identity.domain.MemberMappingRepository;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.EventId;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StreamId;
import de.sgart.shared.support.InMemoryEventStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * MockMvc slice over the real {@code HouseholdStreamController} wiring, with the durable adapters
 * swapped for in-memory doubles — no live KurrentDB/PostgreSQL (mirrors {@code
 * MemberControllerTest}). Proves the endpoint's auth gate (Story 4.4, T1, AC3): a member's request
 * opens the async SSE stream; a non-member is rejected {@code 403} <strong>before</strong> any
 * stream starts; and — the "mapping = access" crux — a member removed from the household is
 * rejected {@code 403} on their very next connection attempt (the ACL de-link, proven synchronous
 * in Story 4.3, already blocks a fresh connect without T6's eviction needing to run at all).
 */
@SpringBootTest
@AutoConfigureMockMvc
class HouseholdStreamControllerTest {

    private static final String ADMIN_SUB = "anna-sub";
    private static final String STRANGER_SUB = "stranger-sub";

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Autowired
    private EventStore eventStore;

    @Autowired
    private MemberMappingRepository mappingRepository;

    @TestConfiguration
    static class InMemoryAdaptersConfig {

        @Bean
        @Primary
        EventStore testEventStore() {
            return new InMemoryEventStore();
        }

        @Bean
        @Primary
        MemberMappingRepository testMemberMappingRepository() {
            return new InMemoryMemberMappingRepository();
        }
    }

    private HouseholdId seedHouseholdWithAdmin(MemberId adminMemberId) {
        HouseholdId householdId = HouseholdId.generate();
        Household household = Household.create(
                householdId, new HouseholdName("Familie Muster"), adminMemberId, CommandId.generate());
        eventStore.append(
                AggregateVersion.initial(StreamId.forHousehold(householdId)),
                household.uncommittedEvents(),
                CommandId.generate());
        mappingRepository.save(new MemberMapping(householdId, adminMemberId, new KeycloakUserId(ADMIN_SUB)));
        return householdId;
    }

    @Test
    void stream_aMemberOpensTheAsyncSseStream() throws Exception {
        MemberId adminMemberId = MemberId.generate();
        HouseholdId householdId = seedHouseholdWithAdmin(adminMemberId);

        mockMvc.perform(get("/api/v1/households/{householdId}/stream", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(ADMIN_SUB))))
                .andExpect(request().asyncStarted());
    }

    @Test
    void stream_aNonMemberIsRejectedWith403BeforeAnyStreamStarts() throws Exception {
        MemberId adminMemberId = MemberId.generate();
        HouseholdId householdId = seedHouseholdWithAdmin(adminMemberId);

        mockMvc.perform(get("/api/v1/households/{householdId}/stream", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject(STRANGER_SUB))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("identity.notAMember"));
    }

    @Test
    void stream_aRemovedMembersReconnectIsRejectedWith403() throws Exception {
        MemberId adminMemberId = MemberId.generate();
        MemberId removedMemberId = MemberId.generate();
        HouseholdId householdId = seedHouseholdWithAdmin(adminMemberId);
        mappingRepository.save(new MemberMapping(householdId, removedMemberId, new KeycloakUserId("removed-sub")));
        eventStore.append(
                AggregateVersion.of(StreamId.forHousehold(householdId), 2),
                java.util.List.of(new MemberRemoved(EventId.generate(), householdId, removedMemberId, adminMemberId)),
                CommandId.generate());
        // The de-link is a separate Identity ACL write in the real handler (Story 4.3,
        // RetractMembership) — simulate it directly here since this test targets the SSE
        // controller's auth gate, not the governance handler's orchestration.
        mappingRepository.deleteMappingByMember(householdId, removedMemberId);

        mockMvc.perform(get("/api/v1/households/{householdId}/stream", householdId.toString())
                        .with(jwt().jwt(jwt -> jwt.subject("removed-sub"))))
                .andExpect(status().isForbidden());
    }
}
