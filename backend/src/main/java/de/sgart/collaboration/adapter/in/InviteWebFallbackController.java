package de.sgart.collaboration.adapter.in;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the Story 4.6 (D3, AC2/AC5) static web-fallback page at the clean {@code /invite} path
 * (Spring's static-resource handling alone would need the trailing {@code /invite/}) and the small
 * runtime config the page's in-browser Keycloak PKCE flow needs — injected here rather than
 * hard-coded into the committed static file (env-driven like the rest of the config). Reachable
 * without authentication (the default {@code SecurityConfig} rule already permits anything outside
 * {@code /api/v1/**}); the page itself is accept-only, exposing no other app functionality (AC5).
 */
@RestController
class InviteWebFallbackController {

    private final String keycloakAuthorizeUrl;
    private final String keycloakTokenUrl;
    private final String webClientId;

    InviteWebFallbackController(
            @Value("${sgart.security.jwt.issuer}") String keycloakIssuer,
            @Value("${sgart.invite.web-client-id}") String webClientId) {
        this.keycloakAuthorizeUrl = keycloakIssuer + "/protocol/openid-connect/auth";
        this.keycloakTokenUrl = keycloakIssuer + "/protocol/openid-connect/token";
        this.webClientId = webClientId;
    }

    @GetMapping(value = "/invite", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<Resource> acceptPage() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(new ClassPathResource("static/invite/index.html"));
    }

    @GetMapping("/invite/config.json")
    InviteWebConfigResponse config() {
        return new InviteWebConfigResponse(keycloakAuthorizeUrl, keycloakTokenUrl, webClientId);
    }

    /** The public PKCE config the browser needs — no secret, since a public client has none. */
    record InviteWebConfigResponse(String authorizeUrl, String tokenUrl, String clientId) {}
}
