package de.sgart.identity.adapter.in;

import de.sgart.identity.application.ProvisionAccount;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Silent account provisioning (Story 7.1, AC1/AC3) — the app's <strong>only</strong> unauthenticated
 * write endpoint (D-E), reachable before any credential exists so a fresh install can create its
 * Keycloak account with zero input. No {@code @AuthenticationPrincipal}: unlike every other
 * controller in this codebase, there is deliberately no caller yet. {@code SecurityConfig} permits
 * exactly {@code POST /api/v1/accounts} above the blanket {@code /api/v1/**}.authenticated() rule
 * (AC4) — nothing else opens up.
 *
 * <p>{@code 204 No Content} on success (CQRS — no domain data returned; the app already derived the
 * username locally and reads {@code sub} from the JWT after the following Direct-Grant sign-in).
 */
@RestController
@RequestMapping("/api/v1/accounts")
class AccountController {

    private final ProvisionAccount provisionAccount;

    AccountController(ProvisionAccount provisionAccount) {
        this.provisionAccount = provisionAccount;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void provision(@RequestBody ProvisionAccountRequest request) {
        provisionAccount.provision(request.publicKey(), request.platform());
    }

    /** Transport DTO — {@code publicKey} is base64url; {@code platform} the raw enum name. */
    record ProvisionAccountRequest(String publicKey, String platform) {}
}
