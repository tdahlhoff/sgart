package de.sgart.identity.adapter.in;

import de.sgart.identity.adapter.in.security.AuthenticatedCaller;
import de.sgart.identity.application.AttachRecoveryEmail;
import de.sgart.identity.application.ConfirmEmailRecovery;
import de.sgart.identity.application.ConfirmRecoveryEmail;
import de.sgart.identity.application.DetachRecoveryEmail;
import de.sgart.identity.application.GetRecoveryEmailStatus;
import de.sgart.identity.application.ProvisionAccount;
import de.sgart.identity.application.RequestEmailRecoveryCode;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Silent account provisioning (Story 7.1, AC1/AC3) plus, from Story 7.3, the "recover by email"
 * (opt-in) endpoints. Only {@code POST /api/v1/accounts} is unauthenticated (D-E); every endpoint
 * below sits under the blanket {@code /api/v1/**}.authenticated() rule — {@code SecurityConfig}
 * gains no new permit rule (design §5, D-C). The attach/confirm/detach endpoints authenticate as
 * the caller's real account; the two recovery endpoints authenticate as the throwaway account the
 * app already holds a JWT for (7.1 always provisions and signs in first).
 */
@RestController
class AccountController {

    private final ProvisionAccount provisionAccount;
    private final AttachRecoveryEmail attachRecoveryEmail;
    private final ConfirmRecoveryEmail confirmRecoveryEmail;
    private final DetachRecoveryEmail detachRecoveryEmail;
    private final GetRecoveryEmailStatus getRecoveryEmailStatus;
    private final RequestEmailRecoveryCode requestEmailRecoveryCode;
    private final ConfirmEmailRecovery confirmEmailRecovery;

    AccountController(
            ProvisionAccount provisionAccount,
            AttachRecoveryEmail attachRecoveryEmail,
            ConfirmRecoveryEmail confirmRecoveryEmail,
            DetachRecoveryEmail detachRecoveryEmail,
            GetRecoveryEmailStatus getRecoveryEmailStatus,
            RequestEmailRecoveryCode requestEmailRecoveryCode,
            ConfirmEmailRecovery confirmEmailRecovery) {
        this.provisionAccount = provisionAccount;
        this.attachRecoveryEmail = attachRecoveryEmail;
        this.confirmRecoveryEmail = confirmRecoveryEmail;
        this.detachRecoveryEmail = detachRecoveryEmail;
        this.getRecoveryEmailStatus = getRecoveryEmailStatus;
        this.requestEmailRecoveryCode = requestEmailRecoveryCode;
        this.confirmEmailRecovery = confirmEmailRecovery;
    }

    /**
     * {@code 204 No Content} on success (CQRS — no domain data returned; the app already derived
     * the username locally and reads {@code sub} from the JWT after the following Direct-Grant
     * sign-in). The app's <strong>only</strong> unauthenticated write endpoint (D-E) — reachable
     * before any credential exists so a fresh install can create its Keycloak account with zero
     * input. {@code SecurityConfig} permits exactly {@code POST /api/v1/accounts} above the blanket
     * {@code /api/v1/**}.authenticated() rule (AC4) — nothing else opens up.
     */
    @PostMapping("/api/v1/accounts")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void provision(@RequestBody ProvisionAccountRequest request) {
        provisionAccount.provision(request.publicKey(), request.platform());
    }

    /**
     * Attach: writes a pending binding and mails a confirmation code. {@code 202} in every case
     * except a malformed address ({@code 400}) and the caller's own budget ({@code 429}); nothing
     * about the address changes the response.
     */
    @PostMapping("/api/v1/account/email")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void attachEmail(@AuthenticationPrincipal Jwt jwt, @RequestBody EmailRequest request) {
        attachRecoveryEmail.attach(callerId(jwt), request.email());
    }

    /** Confirm: the caller's pending binding becomes confirmed and the code is consumed (single-use). */
    @PostMapping("/api/v1/account/email/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void confirmEmail(@AuthenticationPrincipal Jwt jwt, @RequestBody CodeRequest request) {
        confirmRecoveryEmail.confirm(callerId(jwt), request.code());
    }

    /** Detach: deletes every binding of the caller and any pending code rows. */
    @DeleteMapping("/api/v1/account/email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void detachEmail(@AuthenticationPrincipal Jwt jwt) {
        detachRecoveryEmail.detach(callerId(jwt));
    }

    /** Profile status: the masked hint of the caller's confirmed recovery email, or a {@code null} hint. */
    @GetMapping("/api/v1/account/email")
    RecoveryEmailStatusResponse recoveryEmailStatus(@AuthenticationPrincipal Jwt jwt) {
        return new RecoveryEmailStatusResponse(
                getRecoveryEmailStatus.statusFor(callerId(jwt)).addressHint());
    }

    /**
     * AC2 recovery request: {@code 202} regardless of whether {@code email} is registered (D-H,
     * no enumeration) — the request record is deliberately not surfaced to the caller either way.
     */
    @PostMapping("/api/v1/account/recovery/email")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void requestRecoveryCode(@RequestBody EmailRequest request) {
        requestEmailRecoveryCode.request(request.email());
    }

    /**
     * AC2 confirm & rebind (design §1.1, R1): authenticated as the throwaway {@code kc2} the caller
     * currently holds a JWT for — never taken from the request body (AR10).
     */
    @PostMapping("/api/v1/account/recovery/email/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void confirmRecovery(@AuthenticationPrincipal Jwt jwt, @RequestBody RecoveryConfirmRequest request) {
        confirmEmailRecovery.confirmAndRebind(callerId(jwt), request.email(), request.code());
    }

    private static String callerId(Jwt jwt) {
        return AuthenticatedCaller.fromJwt(jwt).keycloakUserId();
    }

    /** Transport DTO — {@code publicKey} is base64url; {@code platform} the raw enum name. */
    record ProvisionAccountRequest(String publicKey, String platform) {}

    record EmailRequest(String email) {}

    record CodeRequest(String code) {}

    record RecoveryEmailStatusResponse(String addressHint) {}

    record RecoveryConfirmRequest(String email, String code) {}
}
