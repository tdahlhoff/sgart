package de.sgart.collaboration.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.shared.HouseholdId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test: the identity-owned {@code FindHouseholdNames} port is served from the household
 * name read model, unwrapping the domain value object to plain text.
 */
class CollaborationHouseholdNamesTest {

    private static final HouseholdId PROJECTED_HOUSEHOLD = HouseholdId.generate();
    private static final HouseholdId NOT_YET_PROJECTED_HOUSEHOLD = HouseholdId.generate();

    private final CollaborationHouseholdNames householdNames = new CollaborationHouseholdNames(
            householdIds -> Map.of(PROJECTED_HOUSEHOLD, new HouseholdName("Test Flat")));

    @Test
    void namesFor_returnsTheProjectedNameAsPlainText() {
        assertThat(householdNames.namesFor(List.of(PROJECTED_HOUSEHOLD)))
                .containsExactly(Map.entry(PROJECTED_HOUSEHOLD, "Test Flat"));
    }

    @Test
    void namesFor_omitsAHouseholdWhoseNameIsNotProjectedYet() {
        assertThat(householdNames.namesFor(List.of(PROJECTED_HOUSEHOLD, NOT_YET_PROJECTED_HOUSEHOLD)))
                .doesNotContainKey(NOT_YET_PROJECTED_HOUSEHOLD);
    }
}
