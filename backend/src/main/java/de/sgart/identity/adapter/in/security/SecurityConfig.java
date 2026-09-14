package de.sgart.identity.adapter.in.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless JWT resource-server config — the identity context's inbound security concern (AD-1).
 * This is the only place transport-level auth lives; the domain never sees a token or a framework
 * security type.
 *
 * <p>Every request under {@code /api/v1/**} requires a valid Keycloak-signed JWT; signature,
 * issuer, and audience are checked by {@link #jwtDecoder(String, String, String)} (AC1). No
 * session is created — the bearer token is the only credential per request.
 *
 * <p><strong>The one deliberate exception (Story 7.1, D-E, AC4):</strong> {@code POST
 * /api/v1/accounts} is permitted unauthenticated — silent account provisioning must run before any
 * credential exists. Spring matches {@code authorizeHttpRequests} matchers in declaration order, so
 * this exact method+path permit MUST stay above the blanket {@code /api/v1/**}.authenticated() rule
 * below it — reordering would 401 provisioning, and widening the matcher (e.g. dropping the
 * {@code HttpMethod.POST} restriction, or loosening the path) would open up more than intended.
 * This is the single unauthenticated <em>write</em> surface in the app; its abuse-surface
 * mitigation is IP-based rate limiting at the TLS reverse proxy (ADR-0002), documented as a beta
 * seam in {@code docs/first-real-world-test.md} (AC7) since that proxy is not yet deployed.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /**
     * Built manually (rather than via {@code issuer-uri} auto-configuration) so JWK-set retrieval
     * stays lazy — the bean itself performs no network call, only the first token verification
     * does. This is what keeps {@code contextLoads()} green while Keycloak is unreachable (Story
     * 1.4 Dev Notes, "Do not break contextLoads").
     */
    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${sgart.security.jwt.issuer}") String issuer,
            @Value("${sgart.security.jwt.audience}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), new AudienceValidator(audience));
        decoder.setJwtValidator(validator);
        return decoder;
    }
}
