package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.application.command.AddStoreHandler;
import de.sgart.collaboration.application.command.ArchiveStoreHandler;
import de.sgart.collaboration.application.command.CreateHouseholdHandler;
import de.sgart.collaboration.application.command.CreateShoppingListHandler;
import de.sgart.collaboration.application.command.RenameShoppingListHandler;
import de.sgart.collaboration.application.query.ListMyHouseholds;
import de.sgart.collaboration.application.query.ListOpenLists;
import de.sgart.collaboration.application.query.ListStores;
import de.sgart.collaboration.application.command.RenameHouseholdHandler;
import de.sgart.collaboration.domain.readmodel.HouseholdNameReadModel;
import de.sgart.collaboration.domain.readmodel.ShoppingListReadModel;
import de.sgart.collaboration.domain.readmodel.StoreReadModel;
import de.sgart.identity.application.ListHouseholdsForCaller;
import de.sgart.identity.application.MintMemberIdentity;
import de.sgart.identity.application.ResolveMemberIdentity;
import de.sgart.shared.EventStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the create-household command handler and the first-run-routing query (Story 1.6). Lives
 * in {@code adapter.out}, not {@code adapter.in}, because it references the domain-owned {@link
 * HouseholdNameReadModel} port — {@code adapter.in} may not reach into {@code
 * collaboration.domain} directly (the hexagonal layer-direction ArchUnit rule).
 */
@Configuration
public class HouseholdApplicationConfig {

    @Bean
    CreateHouseholdHandler createHouseholdHandler(EventStore eventStore, MintMemberIdentity mintMemberIdentity) {
        return new CreateHouseholdHandler(eventStore, mintMemberIdentity);
    }

    @Bean
    RenameHouseholdHandler renameHouseholdHandler(
            EventStore eventStore, ResolveMemberIdentity resolveMemberIdentity) {
        return new RenameHouseholdHandler(eventStore, resolveMemberIdentity);
    }

    @Bean
    ListMyHouseholds listMyHouseholds(
            ListHouseholdsForCaller listHouseholdsForCaller, HouseholdNameReadModel householdNameReadModel) {
        return new ListMyHouseholds(listHouseholdsForCaller, householdNameReadModel);
    }

    @Bean
    AddStoreHandler addStoreHandler(EventStore eventStore, ResolveMemberIdentity resolveMemberIdentity) {
        return new AddStoreHandler(eventStore, resolveMemberIdentity);
    }

    @Bean
    ArchiveStoreHandler archiveStoreHandler(EventStore eventStore, ResolveMemberIdentity resolveMemberIdentity) {
        return new ArchiveStoreHandler(eventStore, resolveMemberIdentity);
    }

    @Bean
    ListStores listStores(ResolveMemberIdentity resolveMemberIdentity, StoreReadModel storeReadModel) {
        return new ListStores(resolveMemberIdentity, storeReadModel);
    }

    @Bean
    CreateShoppingListHandler createShoppingListHandler(
            EventStore eventStore, ResolveMemberIdentity resolveMemberIdentity) {
        return new CreateShoppingListHandler(eventStore, resolveMemberIdentity);
    }

    @Bean
    RenameShoppingListHandler renameShoppingListHandler(
            EventStore eventStore, ResolveMemberIdentity resolveMemberIdentity) {
        return new RenameShoppingListHandler(eventStore, resolveMemberIdentity);
    }

    @Bean
    ListOpenLists listOpenLists(ResolveMemberIdentity resolveMemberIdentity, ShoppingListReadModel shoppingListReadModel) {
        return new ListOpenLists(resolveMemberIdentity, shoppingListReadModel);
    }
}
