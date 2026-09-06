package de.sgart.identity.adapter.out;

import de.sgart.identity.application.FindHouseholdMemberByEmail;
import de.sgart.identity.application.ListHouseholdsForCaller;
import de.sgart.identity.application.MintMemberIdentity;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.identity.domain.MemberMappingRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wires the Identity ACL's durable mapping repository and its application-layer ports (Story 1.6:
 * the mint/write path and the mapping table, deferred from Story 1.4). Building the {@code
 * JdbcClient}-backed repository performs no I/O, so {@code contextLoads()} survives Postgres being
 * down.
 */
@Configuration
public class IdentityBeansConfig {

    @Bean
    MemberMappingRepository memberMappingRepository(JdbcClient jdbcClient) {
        return new JdbcMemberMappingRepository(jdbcClient);
    }

    @Bean
    MintMemberIdentity mintMemberIdentity(MemberMappingRepository memberMappingRepository) {
        return new MintMemberIdentity(memberMappingRepository);
    }

    @Bean
    ListHouseholdsForCaller listHouseholdsForCaller(MemberMappingRepository memberMappingRepository) {
        return new ListHouseholdsForCaller(memberMappingRepository);
    }

    @Bean
    ResolveMemberIdentity resolveMemberIdentity(MemberMappingRepository memberMappingRepository) {
        return new ResolveMemberIdentity(memberMappingRepository);
    }

    @Bean
    FindHouseholdMemberByEmail findHouseholdMemberByEmail() {
        return new DeferredFindHouseholdMemberByEmail();
    }
}
