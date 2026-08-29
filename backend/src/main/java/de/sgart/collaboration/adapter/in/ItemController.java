package de.sgart.collaboration.adapter.in;

import de.sgart.collaboration.application.command.AddItemHandler;
import de.sgart.collaboration.application.command.AssignItemToStoreHandler;
import de.sgart.collaboration.application.command.CheckOffItemHandler;
import de.sgart.collaboration.application.command.MoveItemHandler;
import de.sgart.collaboration.application.command.PostponeItemHandler;
import de.sgart.collaboration.application.command.PostponeItemToListHandler;
import de.sgart.collaboration.application.command.RemoveItemHandler;
import de.sgart.collaboration.application.command.RerouteItemHandler;
import de.sgart.collaboration.application.command.UncheckItemHandler;
import de.sgart.collaboration.application.command.UpdateItemHandler;
import de.sgart.collaboration.application.query.ListItems;
import de.sgart.identity.adapter.in.security.AuthenticatedCaller;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Item management (Story 2.3, AC1–AC8): items are nested under the list they belong to, even
 * though {@code Item} is an entity inside the {@code ShoppingList} aggregate rather than a
 * standalone one (AD-10), mirroring {@code StoreController}. {@code POST} adds an item (the client
 * minted the {@code itemId} and carries it, so the response needs no body — {@code 201}); {@code
 * GET} lists the list's items in creation order; {@code PATCH} updates an item ({@code 204});
 * {@code DELETE} removes an item ({@code 204}, idempotent — AC4). Caller identity comes only from
 * the JWT {@code sub} via {@link AuthenticatedCaller} — never from the body/path (AR10, AD-5).
 */
@RestController
@RequestMapping("/api/v1/households/{householdId}/lists/{listId}/items")
class ItemController {

    private final AddItemHandler addItemHandler;
    private final UpdateItemHandler updateItemHandler;
    private final RemoveItemHandler removeItemHandler;
    private final MoveItemHandler moveItemHandler;
    private final AssignItemToStoreHandler assignItemToStoreHandler;
    private final RerouteItemHandler rerouteItemHandler;
    private final CheckOffItemHandler checkOffItemHandler;
    private final UncheckItemHandler uncheckItemHandler;
    private final PostponeItemHandler postponeItemHandler;
    private final PostponeItemToListHandler postponeItemToListHandler;
    private final ListItems listItems;

    ItemController(
            AddItemHandler addItemHandler,
            UpdateItemHandler updateItemHandler,
            RemoveItemHandler removeItemHandler,
            MoveItemHandler moveItemHandler,
            AssignItemToStoreHandler assignItemToStoreHandler,
            RerouteItemHandler rerouteItemHandler,
            CheckOffItemHandler checkOffItemHandler,
            UncheckItemHandler uncheckItemHandler,
            PostponeItemHandler postponeItemHandler,
            PostponeItemToListHandler postponeItemToListHandler,
            ListItems listItems) {
        this.addItemHandler = addItemHandler;
        this.updateItemHandler = updateItemHandler;
        this.removeItemHandler = removeItemHandler;
        this.moveItemHandler = moveItemHandler;
        this.assignItemToStoreHandler = assignItemToStoreHandler;
        this.rerouteItemHandler = rerouteItemHandler;
        this.checkOffItemHandler = checkOffItemHandler;
        this.uncheckItemHandler = uncheckItemHandler;
        this.postponeItemHandler = postponeItemHandler;
        this.postponeItemToListHandler = postponeItemToListHandler;
        this.listItems = listItems;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    void add(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @PathVariable String listId,
            @RequestBody AddItemRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        addItemHandler.handle(
                caller.keycloakUserId(),
                householdId,
                listId,
                request.itemId(),
                request.name(),
                request.note(),
                request.amount(),
                request.unit(),
                request.commandId());
    }

    @GetMapping
    List<ItemResponse> list(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String householdId, @PathVariable String listId) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        return listItems.forList(caller.keycloakUserId(), householdId, listId).stream()
                .map(summary -> new ItemResponse(
                        summary.itemId(),
                        summary.name(),
                        summary.note(),
                        summary.amount(),
                        summary.unit(),
                        summary.storeId(),
                        summary.status()))
                .toList();
    }

    @PatchMapping("/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @PathVariable String listId,
            @PathVariable String itemId,
            @RequestBody UpdateItemRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        updateItemHandler.handle(
                caller.keycloakUserId(),
                householdId,
                listId,
                itemId,
                request.name(),
                request.note(),
                request.amount(),
                request.unit(),
                request.commandId());
    }

    @DeleteMapping("/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @PathVariable String listId,
            @PathVariable String itemId,
            @RequestBody RemoveItemRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        removeItemHandler.handle(caller.keycloakUserId(), householdId, listId, itemId, request.commandId());
    }

    @PostMapping("/{itemId}/move")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void move(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @PathVariable String listId,
            @PathVariable String itemId,
            @RequestBody MoveItemRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        moveItemHandler.handle(
                caller.keycloakUserId(), householdId, listId, itemId, request.targetListId(), request.commandId());
    }

    @PutMapping("/{itemId}/store")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void assignStore(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @PathVariable String listId,
            @PathVariable String itemId,
            @RequestBody AssignStoreRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        assignItemToStoreHandler.handle(
                caller.keycloakUserId(), householdId, listId, itemId, request.storeId(), request.commandId());
    }

    /** Re-routes an item to a different trip store during a trip (Story 3.2, AC2). */
    @PostMapping("/{itemId}/reroute")
    @ResponseStatus(HttpStatus.OK)
    void reroute(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @PathVariable String listId,
            @PathVariable String itemId,
            @RequestBody RerouteItemRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        rerouteItemHandler.handle(
                caller.keycloakUserId(), householdId, listId, itemId, request.storeId(), request.commandId());
    }

    @PostMapping("/{itemId}/check-off")
    @ResponseStatus(HttpStatus.OK)
    void checkOff(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @PathVariable String listId,
            @PathVariable String itemId,
            @RequestBody StatusCommandRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);
        checkOffItemHandler.handle(caller.keycloakUserId(), householdId, listId, itemId, request.commandId());
    }

    @PostMapping("/{itemId}/uncheck")
    @ResponseStatus(HttpStatus.OK)
    void uncheck(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @PathVariable String listId,
            @PathVariable String itemId,
            @RequestBody StatusCommandRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);
        uncheckItemHandler.handle(caller.keycloakUserId(), householdId, listId, itemId, request.commandId());
    }

    @PostMapping("/{itemId}/postpone")
    @ResponseStatus(HttpStatus.OK)
    void postpone(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @PathVariable String listId,
            @PathVariable String itemId,
            @RequestBody StatusCommandRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);
        postponeItemHandler.handle(caller.keycloakUserId(), householdId, listId, itemId, request.commandId());
    }

    @PostMapping("/{itemId}/postpone-to-list")
    @ResponseStatus(HttpStatus.OK)
    void postponeToList(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @PathVariable String listId,
            @PathVariable String itemId,
            @RequestBody PostponeToListRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);
        postponeItemToListHandler.handle(
                caller.keycloakUserId(), householdId, listId, itemId, request.targetListId(), request.commandId());
    }

    /**
     * Transport DTO for {@code POST} — the add-item command envelope (AR10). {@code itemId} is the
     * client-minted id; {@code note} is optional; {@code amount} is a decimal string, {@code unit}
     * the enum name.
     */
    record AddItemRequest(String itemId, String name, String note, String amount, String unit, String commandId) {}

    /** Transport DTO for {@code PATCH} — the update-item command envelope (AR10). */
    record UpdateItemRequest(String name, String note, String amount, String unit, String commandId) {}

    /** Transport DTO for {@code DELETE} — the remove-item command envelope (AR10). */
    record RemoveItemRequest(String commandId) {}

    /**
     * Transport DTO for {@code POST .../move} — the move-item command envelope (AR10, Story 2.4).
     * The path {@code listId} is the <em>source</em>; {@code targetListId} rides the body since it
     * names a different aggregate than the path's list.
     */
    record MoveItemRequest(String targetListId, String commandId) {}

    /** Transport DTO for {@code PUT .../store} — the assign-item-to-store command envelope (Story 2.6). */
    record AssignStoreRequest(String storeId, String commandId) {}

    /** Transport DTO for {@code POST .../reroute} — the reroute-item command envelope (Story 3.2). */
    record RerouteItemRequest(String storeId, String commandId) {}

    /** Transport DTO for {@code POST .../check-off}, {@code .../uncheck}, {@code .../postpone} (Story 3.3). */
    record StatusCommandRequest(String commandId) {}

    /** Transport DTO for {@code POST .../postpone-to-list} (Story 3.3, AC4/AC5). */
    record PostponeToListRequest(String targetListId, String commandId) {}

    /** {@code note}/{@code storeId} are {@code null} when absent; {@code amount} a decimal string, {@code unit} and {@code status} enum names. */
    record ItemResponse(String itemId, String name, String note, String amount, String unit, String storeId, String status) {}
}
