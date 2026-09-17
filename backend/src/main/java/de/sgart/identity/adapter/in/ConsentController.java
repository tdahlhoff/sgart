package de.sgart.identity.adapter.in;

import de.sgart.identity.adapter.in.security.AuthenticatedCaller;
import de.sgart.identity.application.ConsentStatus;
import de.sgart.identity.application.GetConsentStatus;
import de.sgart.identity.application.RecordConsent;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consent capture (Story 7.4, AC1/AC2): records and reports the caller's acceptance of the current
 * privacy notice/terms — the lawful-basis moment for processing household personal data. Both
 * endpoints authenticate as the caller's own JWT and sit under the existing {@code
 * /api/v1/**}.authenticated() rule — {@code SecurityConfig} gains no new permit rule (AC6).
 */
@RestController
class ConsentController {

    private final RecordConsent recordConsent;
    private final GetConsentStatus getConsentStatus;

    ConsentController(RecordConsent recordConsent, GetConsentStatus getConsentStatus) {
        this.recordConsent = recordConsent;
        this.getConsentStatus = getConsentStatus;
    }

    /**
     * AC1: records the caller's acceptance of the deployment's current notice version — the client
     * sends no {@code noticeVersion}; a client-supplied value would be unvalidated and could record
     * consent for a fabricated version (Story 7.4 review). {@code 204} — no domain data returned.
     */
    @PostMapping("/api/v1/consent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void accept(@AuthenticationPrincipal Jwt jwt) {
        recordConsent.record(callerId(jwt));
    }

    /** AC2: the caller's current consent status — side-effect-free. */
    @GetMapping("/api/v1/consent")
    ConsentStatus status(@AuthenticationPrincipal Jwt jwt) {
        return getConsentStatus.statusFor(callerId(jwt));
    }

    private static String callerId(Jwt jwt) {
        return AuthenticatedCaller.fromJwt(jwt).keycloakUserId();
    }
}
