package de.sgart.collaboration.adapter.in;

import de.sgart.collaboration.application.command.StartTripHandler;
import de.sgart.identity.adapter.in.security.AuthenticatedCaller;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trip start (Story 3.1, AC1, AC3, AC7): nested under the list it starts from — the linked-list
 * requirement is structural (a trip is started <em>from</em> a list, AC3). {@code POST} starts a
 * trip across one or more stores (the client minted {@code tripId} and carries it, so the response
 * needs no body — {@code 201}). Caller identity comes only from the JWT {@code sub} via {@link
 * AuthenticatedCaller} — never from the body/path (AR10, AD-5).
 *
 * <p>A new controller (not a method on {@code ShoppingListController}) because the trip is a
 * first-class aggregate whose controller Stories 3.2–3.4 fill (check-off, reroute, complete); 3.1
 * seeds it with the one start endpoint (CLAUDE.md §8).
 */
@RestController
@RequestMapping("/api/v1/households/{householdId}/lists/{listId}/trips")
class TripController {

    private final StartTripHandler startTripHandler;

    TripController(StartTripHandler startTripHandler) {
        this.startTripHandler = startTripHandler;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    void start(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @PathVariable String listId,
            @RequestBody StartTripRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        startTripHandler.handle(
                caller.keycloakUserId(),
                householdId,
                listId,
                request.tripId(),
                request.storeIds(),
                request.commandId());
    }

    /**
     * Transport DTO for {@code POST} — the start-trip command envelope (AR10). {@code tripId} is
     * the client-minted id; {@code storeIds} are plain {@code String}s so this controller never
     * imports {@code ..domain..} (ArchUnit).
     */
    record StartTripRequest(String tripId, List<String> storeIds, String commandId) {}
}
