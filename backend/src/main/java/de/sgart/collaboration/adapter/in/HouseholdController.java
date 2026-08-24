package de.sgart.collaboration.adapter.in;

import de.sgart.collaboration.application.CreateHouseholdHandler;
import de.sgart.collaboration.application.ListMyHouseholds;
import de.sgart.collaboration.application.RenameHouseholdHandler;
import de.sgart.identity.adapter.in.security.AuthenticatedCaller;
import de.sgart.shared.HouseholdId;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Create-household + first-run routing (AC1, AC2, AC3): {@code POST} creates and returns the new
 * household id so the client can route straight in (read-your-writes); {@code GET} lists the
 * caller's households by name for the 0/1/≥2 routing decision; {@code PATCH} renames a household
 * (Story 1.7, Admin-only enforced in the domain). The caller identity comes only from the JWT
 * {@code sub} via {@link AuthenticatedCaller} — never from the request body (AR10, AD-5); {@code
 * SecurityConfig} already secures {@code /api/v1/**}.
 */
@RestController
@RequestMapping("/api/v1/households")
class HouseholdController {

    private final CreateHouseholdHandler createHouseholdHandler;
    private final ListMyHouseholds listMyHouseholds;
    private final RenameHouseholdHandler renameHouseholdHandler;

    HouseholdController(
            CreateHouseholdHandler createHouseholdHandler,
            ListMyHouseholds listMyHouseholds,
            RenameHouseholdHandler renameHouseholdHandler) {
        this.createHouseholdHandler = createHouseholdHandler;
        this.listMyHouseholds = listMyHouseholds;
        this.renameHouseholdHandler = renameHouseholdHandler;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CreateHouseholdResponse create(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateHouseholdRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        // The handler validates the raw name + command envelope (fail fast), mapping a bad value to
        // a localizable 400 via WriteErrorAdvice rather than an opaque 500 from a raw parse here.
        HouseholdId householdId =
                createHouseholdHandler.handle(caller.keycloakUserId(), request.name(), request.commandId());

        return new CreateHouseholdResponse(householdId.toString());
    }

    @GetMapping
    List<HouseholdSummaryResponse> list(@AuthenticationPrincipal Jwt jwt) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        return listMyHouseholds.forCaller(caller.keycloakUserId()).stream()
                .map(summary -> new HouseholdSummaryResponse(summary.householdId().toString(), summary.name()))
                .toList();
    }

    @PatchMapping("/{householdId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void rename(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @RequestBody RenameHouseholdRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        // The handler resolves the caller's MemberId (403 if not a member), enforces Admin-only in
        // the domain (403 renameNotPermitted), and validates the raw name + command envelope (400).
        renameHouseholdHandler.handle(caller.keycloakUserId(), householdId, request.name(), request.commandId());
    }

    /** Transport DTO for {@code POST} — the command envelope's client-facing shape (AR10). */
    record CreateHouseholdRequest(String name, String commandId) {}

    record CreateHouseholdResponse(String householdId) {}

    record HouseholdSummaryResponse(String householdId, String name) {}

    /** Transport DTO for {@code PATCH} — the rename command envelope (AR10). */
    record RenameHouseholdRequest(String name, String commandId) {}
}
