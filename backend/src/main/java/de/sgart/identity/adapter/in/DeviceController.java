package de.sgart.identity.adapter.in;

import de.sgart.identity.adapter.in.security.AuthenticatedCaller;
import de.sgart.identity.application.RegisterDeviceToken;
import de.sgart.identity.application.UnregisterDeviceToken;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Device-token registration (Story 4.5, AC5) — a separate controller from {@link
 * IdentityController} (SRP: that one is the live {@code /me} identity read, this one is a write
 * on a person-scoped resource). {@code keycloakUserId} comes only from the JWT {@code sub} via
 * {@link AuthenticatedCaller} — never the request body (AR10, AD-5), mirroring every other
 * inbound adapter in this codebase.
 */
@RestController
@RequestMapping("/api/v1/devices")
class DeviceController {

    private final RegisterDeviceToken registerDeviceToken;
    private final UnregisterDeviceToken unregisterDeviceToken;

    DeviceController(RegisterDeviceToken registerDeviceToken, UnregisterDeviceToken unregisterDeviceToken) {
        this.registerDeviceToken = registerDeviceToken;
        this.unregisterDeviceToken = unregisterDeviceToken;
    }

    /** Register or refresh a device token — an upsert keyed by the token itself (idempotent). */
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void register(@AuthenticationPrincipal Jwt jwt, @RequestBody RegisterDeviceRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        registerDeviceToken.register(caller.keycloakUserId(), request.token(), request.platform());
    }

    /**
     * Unregister a device token — the sign-out path. The token travels in the request body, never
     * the URL path: a device token is an opaque, person-linked identifier (AD-6), and a path
     * segment would land it verbatim in access/proxy/APM logs — exactly the PII trail GDPR (§5)
     * keeps such identifiers out of. Mirrors the body-carrying {@code DELETE}s elsewhere in this
     * codebase (e.g. member/invite removal).
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unregister(@AuthenticationPrincipal Jwt jwt, @RequestBody UnregisterDeviceRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        unregisterDeviceToken.unregister(caller.keycloakUserId(), request.token());
    }

    /** Transport DTO — {@code platform} is the raw enum name ({@code "ANDROID"}/{@code "IOS"}). */
    record RegisterDeviceRequest(String token, String platform) {}

    /** Transport DTO — the token to unregister, in the body (never the URL path). */
    record UnregisterDeviceRequest(String token) {}
}
