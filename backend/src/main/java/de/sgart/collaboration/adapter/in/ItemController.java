package de.sgart.collaboration.adapter.in;

import de.sgart.collaboration.application.command.AddItemHandler;
import de.sgart.collaboration.application.command.RemoveItemHandler;
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
    private final ListItems listItems;

    ItemController(
            AddItemHandler addItemHandler,
            UpdateItemHandler updateItemHandler,
            RemoveItemHandler removeItemHandler,
            ListItems listItems) {
        this.addItemHandler = addItemHandler;
        this.updateItemHandler = updateItemHandler;
        this.removeItemHandler = removeItemHandler;
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
                        summary.itemId(), summary.name(), summary.note(), summary.amount(), summary.unit()))
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

    /** {@code note} is {@code null} when absent; {@code amount} a decimal string, {@code unit} the enum name. */
    record ItemResponse(String itemId, String name, String note, String amount, String unit) {}
}
