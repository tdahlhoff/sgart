package de.sgart.collaboration.adapter.in;

import de.sgart.collaboration.application.command.AddStoreToTripHandler;
import de.sgart.collaboration.application.command.CompleteTripHandler;
import de.sgart.collaboration.application.command.StartTripHandler;
import de.sgart.collaboration.application.query.ListItems;
import de.sgart.collaboration.application.query.TripView;
import de.sgart.identity.adapter.in.security.AuthenticatedCaller;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final CompleteTripHandler completeTripHandler;
    private final AddStoreToTripHandler addStoreToTripHandler;
    private final TripView tripView;

    TripController(
            StartTripHandler startTripHandler,
            CompleteTripHandler completeTripHandler,
            AddStoreToTripHandler addStoreToTripHandler,
            TripView tripView) {
        this.startTripHandler = startTripHandler;
        this.completeTripHandler = completeTripHandler;
        this.addStoreToTripHandler = addStoreToTripHandler;
        this.tripView = tripView;
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

    /** The store-grouped active-trip view (Story 3.2, AC1). */
    @GetMapping("/active")
    TripViewResponse activeTrip(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String householdId, @PathVariable String listId) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        TripView.TripViewResult result = tripView.forList(caller.keycloakUserId(), householdId, listId);
        return new TripViewResponse(
                result.tripId(),
                result.listId(),
                result.storeIds(),
                result.items().stream()
                        .map(item -> new ItemController.ItemResponse(
                                item.itemId(),
                                item.name(),
                                item.note(),
                                item.amount(),
                                item.unit(),
                                item.storeId(),
                                item.status(),
                                item.transferPending()))
                        .toList());
    }

    /** Completes the trip (Story 3.4, AC4): sweeps leftover OPEN items to DISCARDED and closes the list. */
    @PostMapping("/{tripId}/complete")
    @ResponseStatus(HttpStatus.OK)
    void complete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @PathVariable String listId,
            @PathVariable String tripId,
            @RequestBody CompleteTripRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        completeTripHandler.handle(caller.keycloakUserId(), householdId, listId, tripId, request.commandId());
    }

    /** Adds a store to the trip spontaneously (Story 3.2, AC3) — the trip's first in-trip mutation. */
    @PostMapping("/{tripId}/stores")
    @ResponseStatus(HttpStatus.CREATED)
    void addStore(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @PathVariable String listId,
            @PathVariable String tripId,
            @RequestBody AddStoreToTripRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        addStoreToTripHandler.handle(
                caller.keycloakUserId(), householdId, tripId, request.storeId(), request.commandId());
    }

    /**
     * Transport DTO for {@code POST} — the start-trip command envelope (AR10). {@code tripId} is
     * the client-minted id; {@code storeIds} are plain {@code String}s so this controller never
     * imports {@code ..domain..} (ArchUnit).
     */
    record StartTripRequest(String tripId, List<String> storeIds, String commandId) {}

    /** Transport DTO for {@code POST .../{tripId}/complete} — the complete-trip command envelope (AR10, Story 3.4). */
    record CompleteTripRequest(String commandId) {}

    /** Transport DTO for {@code POST .../stores} — the add-store-to-trip command envelope (AR10). */
    record AddStoreToTripRequest(String storeId, String commandId) {}

    /** The grouped-view payload (Story 3.2, AC1) — grouping by store is the client's job (Cl. 7). */
    record TripViewResponse(String tripId, String listId, List<String> storeIds, List<ItemController.ItemResponse> items) {}
}
