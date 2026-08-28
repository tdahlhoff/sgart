package de.sgart.collaboration.adapter.in;

import de.sgart.collaboration.application.query.ListItemSuggestions;
import de.sgart.identity.adapter.in.security.AuthenticatedCaller;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Item suggestions (Story 2.5, AC1/AC7): household-scoped, not list-scoped — the suggestion history
 * spans the whole household, not one list (mirrors {@link StoreController}'s household-scoped
 * pattern rather than nesting under a list like {@link ItemController}). {@code GET} returns the
 * household's whole suggestion set for the client to cache and filter locally (Cl. 2 — no {@code
 * ?prefix=} query). Caller identity comes only from the JWT {@code sub} via {@link
 * AuthenticatedCaller} — never from the path (AR10, AD-5).
 */
@RestController
@RequestMapping("/api/v1/households/{householdId}/item-suggestions")
class ItemSuggestionController {

    private final ListItemSuggestions listItemSuggestions;

    ItemSuggestionController(ListItemSuggestions listItemSuggestions) {
        this.listItemSuggestions = listItemSuggestions;
    }

    @GetMapping
    List<ItemSuggestionResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String householdId) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        return listItemSuggestions.forHousehold(caller.keycloakUserId(), householdId).stream()
                .map(summary -> new ItemSuggestionResponse(
                        summary.name(), summary.note(), summary.amount(), summary.unit(), summary.defaultStoreId()))
                .toList();
    }

    /** {@code note}/{@code defaultStoreId} are {@code null} when absent; {@code amount} a decimal string, {@code unit} the enum name. */
    record ItemSuggestionResponse(String name, String note, String amount, String unit, String defaultStoreId) {}
}
