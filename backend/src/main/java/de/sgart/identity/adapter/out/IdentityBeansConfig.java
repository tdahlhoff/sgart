package de.sgart.identity.adapter.out;

import de.sgart.identity.application.FindHouseholdMemberByEmail;
import de.sgart.identity.application.ListHouseholdsForCaller;
import de.sgart.identity.application.IssueMemberIdentity;
import de.sgart.identity.application.PruneDeviceToken;
import de.sgart.identity.application.RegisterDeviceToken;
import de.sgart.identity.application.ResolveHouseholdPushTargets;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.identity.application.RetractMembership;
import de.sgart.identity.application.UnregisterDeviceToken;
import de.sgart.identity.domain.DeviceTokenRepository;
import de.sgart.identity.domain.MemberMappingRepository;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

/**
 * Wires the Identity ACL's durable mapping repository and its application-layer ports (Story 1.6:
 * the issue/write path and the mapping table, deferred from Story 1.4). Building the {@code
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
    IssueMemberIdentity issueMemberIdentity(MemberMappingRepository memberMappingRepository) {
        return new IssueMemberIdentity(memberMappingRepository);
    }

    @Bean
    ListHouseholdsForCaller listHouseholdsForCaller(MemberMappingRepository memberMappingRepository) {
        return new ListHouseholdsForCaller(memberMappingRepository);
    }

    @Bean
    ResolveMemberIdentity resolveMemberIdentity(MemberMappingRepository memberMappingRepository) {
        return new ResolveMemberIdentity(memberMappingRepository);
    }

    /**
     * The real Story 4.6 (D4, AC4) lookup — active only when {@code
     * sgart.identity.keycloak-admin.enabled=true} is set explicitly. Building the {@link RestClient}
     * performs no I/O (lazy, like every other adapter in this class); the first Admin API call is
     * what actually reaches Keycloak.
     */
    @Bean
    @ConditionalOnProperty(prefix = "sgart.identity.keycloak-admin", name = "enabled", havingValue = "true")
    FindHouseholdMemberByEmail keycloakAdminFindHouseholdMemberByEmail(
            MemberMappingRepository memberMappingRepository,
            @Value("${sgart.identity.keycloak-admin.base-url}") String baseUrl,
            @Value("${sgart.identity.keycloak-admin.realm}") String realm,
            @Value("${sgart.identity.keycloak-admin.client-id}") String clientId,
            @Value("${sgart.identity.keycloak-admin.client-secret}") String clientSecret) {
        return new KeycloakAdminFindHouseholdMemberByEmail(
                RestClient.builder().baseUrl(baseUrl).build(), memberMappingRepository, realm, clientId, clientSecret);
    }

    /**
     * The wired default — active whenever the Keycloak Admin adapter above is not (Story 4.1).
     * Gated on the same property (its {@code false}/absent case) rather than {@code
     * @ConditionalOnMissingBean}, so the wiring does not depend on this method being declared after
     * the Keycloak bean ({@code @ConditionalOnMissingBean} is bean-declaration-order sensitive in a
     * user {@code @Configuration} — Story 4.6 review).
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "sgart.identity.keycloak-admin",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true)
    FindHouseholdMemberByEmail deferredFindHouseholdMemberByEmail() {
        return new DeferredFindHouseholdMemberByEmail();
    }

    @Bean
    RetractMembership retractMembership(MemberMappingRepository memberMappingRepository) {
        return new RetractMembership(memberMappingRepository);
    }

    @Bean
    DeviceTokenRepository deviceTokenRepository(JdbcClient jdbcClient) {
        return new JdbcDeviceTokenRepository(jdbcClient);
    }

    @Bean
    RegisterDeviceToken registerDeviceToken(DeviceTokenRepository deviceTokenRepository, Clock clock) {
        return new RegisterDeviceToken(deviceTokenRepository, clock);
    }

    @Bean
    UnregisterDeviceToken unregisterDeviceToken(DeviceTokenRepository deviceTokenRepository) {
        return new UnregisterDeviceToken(deviceTokenRepository);
    }

    @Bean
    PruneDeviceToken pruneDeviceToken(DeviceTokenRepository deviceTokenRepository) {
        return new PruneDeviceToken(deviceTokenRepository);
    }

    @Bean
    ResolveHouseholdPushTargets resolveHouseholdPushTargets(
            MemberMappingRepository memberMappingRepository, DeviceTokenRepository deviceTokenRepository) {
        return new ResolveHouseholdPushTargets(memberMappingRepository, deviceTokenRepository);
    }
}
