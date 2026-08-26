package de.sgart.collaboration.adapter.in;

import de.sgart.collaboration.application.CommandFieldTranslations;
import de.sgart.collaboration.application.command.CreateShoppingListHandler;
import de.sgart.collaboration.application.command.RenameShoppingListHandler;
import de.sgart.collaboration.application.query.ListDoneLists;
import de.sgart.collaboration.application.query.ListOpenLists;
import de.sgart.identity.adapter.in.security.AuthenticatedCaller;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shopping list management (Story 2.1 AC1/AC2/AC3; Story 2.2 AC1/AC2): lists are nested under the
 * household they belong to (mirroring {@code StoreController}), even though {@code ShoppingList} is
 * a distinct aggregate that references the household by id only (AD-3). {@code POST} creates a list
 * (the client minted the {@code listId} and carries it, so the response needs no body — {@code
 * 201}); {@code GET} lists the household's lists, {@code open} (default, the AC2 ordinal source) or
 * {@code done} (the read-only archive) per the {@code ?filter} parameter — an unrecognized value is a
 * fail-fast {@code 400}; {@code PATCH} renames a list ({@code 204}, mirroring the household rename
 * shape). Caller identity comes only from the JWT {@code sub} via {@link AuthenticatedCaller} — never
 * from the body/path (AR10, AD-5).
 */
@RestController
@RequestMapping("/api/v1/households/{householdId}/lists")
class ShoppingListController {

    private final CreateShoppingListHandler createShoppingListHandler;
    private final RenameShoppingListHandler renameShoppingListHandler;
    private final ListOpenLists listOpenLists;
    private final ListDoneLists listDoneLists;

    ShoppingListController(
            CreateShoppingListHandler createShoppingListHandler,
            RenameShoppingListHandler renameShoppingListHandler,
            ListOpenLists listOpenLists,
            ListDoneLists listDoneLists) {
        this.createShoppingListHandler = createShoppingListHandler;
        this.renameShoppingListHandler = renameShoppingListHandler;
        this.listOpenLists = listOpenLists;
        this.listDoneLists = listDoneLists;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    void create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @RequestBody CreateShoppingListRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        createShoppingListHandler.handle(
                caller.keycloakUserId(), householdId, request.listId(), request.name(), request.commandId());
    }

    @GetMapping
    List<ShoppingListSummaryResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @RequestParam(defaultValue = "open") String filter) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);
        String validatedFilter = CommandFieldTranslations.toValidatedListFilter(filter);

        List<ListOpenLists.ShoppingListSummary> summaries = "done".equals(validatedFilter)
                ? listDoneLists.forHousehold(caller.keycloakUserId(), householdId)
                : listOpenLists.forHousehold(caller.keycloakUserId(), householdId);

        return summaries.stream()
                .map(summary -> new ShoppingListSummaryResponse(summary.listId(), summary.name(), summary.status()))
                .toList();
    }

    @PatchMapping("/{listId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void rename(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @PathVariable String listId,
            @RequestBody RenameShoppingListRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        renameShoppingListHandler.handle(
                caller.keycloakUserId(), householdId, listId, request.name(), request.commandId());
    }

    /** Transport DTO for {@code POST} — the create-list command envelope (AR10). {@code listId} is the
     * client-minted id; {@code name} is the optional list name, {@code null}/blank for an unnamed list. */
    record CreateShoppingListRequest(String listId, String name, String commandId) {}

    /** Transport DTO for {@code PATCH} — the rename-list command envelope (AR10). */
    record RenameShoppingListRequest(String name, String commandId) {}

    /** {@code name} is {@code null} for an unnamed list; the client derives „Liste N" from position. */
    record ShoppingListSummaryResponse(String listId, String name, String status) {}
}
