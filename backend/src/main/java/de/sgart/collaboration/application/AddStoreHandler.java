package de.sgart.collaboration.application;

import de.sgart.collaboration.domain.DuplicateStoreNameException;
import de.sgart.collaboration.domain.Household;
import de.sgart.collaboration.domain.StoreName;
import de.sgart.identity.application.NotAMemberException;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.EventStore;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import de.sgart.shared.StoreChainId;
import de.sgart.shared.StoreId;
import de.sgart.shared.StreamId;
import java.util.Objects;

/**
 * Orchestrates {@link AddStore} (AC1, AC4): resolve the caller's household-scoped {@link MemberId}
 * through the Identity ACL's published {@link ResolveMemberIdentity} port (AD-2 — never {@code
 * identity.domain}), load the {@link Household} aggregate, and let it enforce the unique-active-name
 * invariant (AC1). The append uses the <em>loaded</em> stream version as the expected version
 * (online load-then-append, AD-8); a concurrent write loses with the store's {@code
 * ConcurrencyConflictException} (→ 409). The command returns {@code void} — a command yields no
 * domain data (CQRS); the client minted the {@link StoreId} and knows the name it sent.
 *
 * <p><strong>AC4 reusability:</strong> this handler holds no manage-screen assumption — it is the
 * single store-creation path every later inline picker (Stories 2.6 / 3.1 / 3.2) reuses.
 */
public final class AddStoreHandler {

    private final EventStore eventStore;
    private final ResolveMemberIdentity resolveMemberIdentity;

    public AddStoreHandler(EventStore eventStore, ResolveMemberIdentity resolveMemberIdentity) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.resolveMemberIdentity =
                Objects.requireNonNull(resolveMemberIdentity, "resolveMemberIdentity must not be null");
    }

    /**
     * @param keycloakUserId the caller's identity, resolved server-side from the JWT {@code sub} by
     *     {@code adapter.in} — never accepted from the request body (AR10, AD-5).
     * @param rawChainId the optional accepted chain id; {@code null}/blank leaves the store unlinked
     * @throws InvalidCommandEnvelopeException if the command envelope is malformed (400)
     * @throws InvalidStoreNameException if {@code rawName} fails the domain invariant (400)
     * @throws NotAMemberException if the caller is not a member of the household (403)
     * @throws DuplicateStoreNameApplicationException if the active name is already taken (409)
     */
    public void handle(
            String keycloakUserId,
            String rawHouseholdId,
            String rawStoreId,
            String rawName,
            String rawChainId,
            String rawCommandId) {
        Objects.requireNonNull(keycloakUserId, "keycloakUserId must not be null");

        CommandId commandId = CommandFieldTranslations.toCommandId(rawCommandId);
        HouseholdId householdId = CommandFieldTranslations.toHouseholdId(rawHouseholdId);
        StoreId storeId = CommandFieldTranslations.toStoreId(rawStoreId);
        StoreName name = CommandFieldTranslations.toStoreName(rawName);
        StoreChainId chainId = CommandFieldTranslations.toStoreChainIdOrNull(rawChainId);

        // A non-member never reaches addStore — NotAMemberException propagates as a 403 (AD-2/AD-5).
        MemberId memberId = resolveMemberIdentity.resolve(keycloakUserId, householdId);

        StreamId streamId = StreamId.forHousehold(householdId);
        Household household = Household.rehydrate(streamId, eventStore.readStream(streamId));
        AggregateVersion loadedVersion = household.version();
        AddStore command = new AddStore(householdId, storeId, name, chainId, commandId, loadedVersion);

        try {
            household.addStore(
                    memberId, command.storeId(), command.name(), command.chainId(), command.commandId());
        } catch (DuplicateStoreNameException duplicate) {
            throw new DuplicateStoreNameApplicationException(duplicate.getMessage());
        }

        eventStore.append(command.basedOnVersion(), household.uncommittedEvents(), command.commandId());
    }
}
