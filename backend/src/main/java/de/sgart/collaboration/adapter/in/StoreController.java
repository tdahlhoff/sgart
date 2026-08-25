package de.sgart.collaboration.adapter.in;

import de.sgart.collaboration.application.command.AddStoreHandler;
import de.sgart.collaboration.application.command.ArchiveStoreHandler;
import de.sgart.collaboration.application.query.ListStores;
import de.sgart.identity.adapter.in.security.AuthenticatedCaller;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Store management (Story 1.8, AC1/AC3): stores are nested under the household they belong to — the
 * aggregate that owns them (AD-10). {@code POST} adds a store (the client minted the {@code storeId}
 * and carries it, so the response needs no body — {@code 201}); {@code DELETE} <strong>archives</strong>
 * a store ({@code 204}) — it never row-deletes it, so historical trips/assignments keep their record
 * (AC3), and the honest {@code DELETE}-that-archives is documented here; {@code GET} lists the
 * household's active stores (the AC5 structural source). Caller identity comes only from the JWT
 * {@code sub} via {@link AuthenticatedCaller} — never from the body/path (AR10, AD-5).
 */
@RestController
@RequestMapping("/api/v1/households/{householdId}/stores")
class StoreController {

    private final AddStoreHandler addStoreHandler;
    private final ArchiveStoreHandler archiveStoreHandler;
    private final ListStores listStores;

    StoreController(
            AddStoreHandler addStoreHandler, ArchiveStoreHandler archiveStoreHandler, ListStores listStores) {
        this.addStoreHandler = addStoreHandler;
        this.archiveStoreHandler = archiveStoreHandler;
        this.listStores = listStores;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    void add(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @RequestBody AddStoreRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        // The handler resolves the caller's MemberId (403 if not a member), enforces the
        // unique-active-name invariant (409 duplicate), and validates the envelope + name (400).
        addStoreHandler.handle(
                caller.keycloakUserId(),
                householdId,
                request.storeId(),
                request.name(),
                request.chainId(),
                request.commandId());
    }

    @DeleteMapping("/{storeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String householdId,
            @PathVariable String storeId,
            @RequestBody ArchiveStoreRequest request) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        // Semantically DELETE, but it archives (soft-remove), never row-deletes (AC3, FR3).
        archiveStoreHandler.handle(caller.keycloakUserId(), householdId, storeId, request.commandId());
    }

    @GetMapping
    List<StoreSummaryResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String householdId) {
        AuthenticatedCaller caller = AuthenticatedCaller.fromJwt(jwt);

        return listStores.forHousehold(caller.keycloakUserId(), householdId).stream()
                .map(summary -> new StoreSummaryResponse(summary.storeId(), summary.name(), summary.chainId()))
                .toList();
    }

    /**
     * Transport DTO for {@code POST} — the add-store command envelope (AR10). {@code storeId} is the
     * client-minted id; {@code chainId} is the optional accepted chain (AC2), {@code null} when the
     * store is unlinked.
     */
    record AddStoreRequest(String storeId, String name, String chainId, String commandId) {}

    /** Transport DTO for {@code DELETE} — the archive command envelope (AR10). */
    record ArchiveStoreRequest(String commandId) {}

    /** {@code chainId} is {@code null} for an unlinked store; the client resolves it to a name. */
    record StoreSummaryResponse(String storeId, String name, String chainId) {}
}
