package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.application.CreateHouseholdHandler;
import de.sgart.collaboration.application.ListMyHouseholds;
import de.sgart.collaboration.domain.HouseholdNameReadModel;
import de.sgart.identity.application.ListHouseholdsForCaller;
import de.sgart.identity.application.MintMemberIdentity;
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
    ListMyHouseholds listMyHouseholds(
            ListHouseholdsForCaller listHouseholdsForCaller, HouseholdNameReadModel householdNameReadModel) {
        return new ListMyHouseholds(listHouseholdsForCaller, householdNameReadModel);
    }
}
