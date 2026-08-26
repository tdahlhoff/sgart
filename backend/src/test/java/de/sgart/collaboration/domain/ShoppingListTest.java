package de.sgart.collaboration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sgart.collaboration.domain.event.ShoppingListCreated;
import de.sgart.collaboration.domain.event.ShoppingListRenamed;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StreamId;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Pure domain-layer unit test — no framework, persistence, or transport (CLAUDE.md §6). Proves the
 * second aggregate: creating a list raises {@code ShoppingListCreated} (named or unnamed) into
 * {@code OPEN}, renaming an {@code OPEN} list raises {@code ShoppingListRenamed}, a rename to the
 * same name is a convergent no-op, and replaying history rebuilds identical state (AC1, AC3).
 *
 * <p>The {@code DONE}-rejects-rename branch is coded but only reachable end-to-end once Epic 3
 * introduces a status-changing transition beyond {@code ShoppingListCreated} — see Story 2.1
 * Clarification 1 and {@code deferred-work.md}; it is not tested here (no synthetic Epic-3 event).
 */
class ShoppingListTest {

    private final HouseholdId householdId = HouseholdId.generate();
    private final ShoppingListId listId = ShoppingListId.generate();
    private final CommandId commandId = CommandId.generate();

    @Test
    void create_withANameRaisesShoppingListCreatedCarryingItAtVersionOneAndStatusOpen() {
        ShoppingList list =
                ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), commandId);

        List<DomainEvent> events = list.uncommittedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ShoppingListCreated.class);
        ShoppingListCreated created = (ShoppingListCreated) events.get(0);
        assertThat(created.householdId()).isEqualTo(householdId);
        assertThat(created.listId()).isEqualTo(listId);
        assertThat(created.name()).isEqualTo(new ShoppingListName("Wocheneinkauf"));

        assertThat(list.status()).isEqualTo(ListStatus.OPEN);
        assertThat(list.version()).isEqualTo(AggregateVersion.of(StreamId.forList(listId), 1));
    }

    @Test
    void create_withANullNameRaisesAnUnnamedShoppingListCreated() {
        ShoppingList list = ShoppingList.create(listId, householdId, null, commandId);

        ShoppingListCreated created = (ShoppingListCreated) list.uncommittedEvents().get(0);
        assertThat(created.name()).isNull();
        assertThat(list.name()).isNull();
        assertThat(list.status()).isEqualTo(ListStatus.OPEN);
    }

    @Test
    void create_rejectsANullListId() {
        assertThatThrownBy(() ->
                        ShoppingList.create(null, householdId, new ShoppingListName("Wocheneinkauf"), commandId))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void create_rejectsANullHouseholdId() {
        assertThatThrownBy(() ->
                        ShoppingList.create(listId, null, new ShoppingListName("Wocheneinkauf"), commandId))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void renamingAnOpenListRaisesShoppingListRenamed() {
        ShoppingList list =
                ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), commandId);
        list.markEventsCommitted();

        list.rename(new ShoppingListName("Getränke"), CommandId.generate());

        List<DomainEvent> events = list.uncommittedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ShoppingListRenamed.class);
        ShoppingListRenamed renamed = (ShoppingListRenamed) events.get(0);
        assertThat(renamed.listId()).isEqualTo(listId);
        assertThat(renamed.newName()).isEqualTo(new ShoppingListName("Getränke"));
        assertThat(list.name()).isEqualTo(new ShoppingListName("Getränke"));
    }

    @Test
    void renamingToTheCurrentNameRaisesNothing() {
        ShoppingList list =
                ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), commandId);
        list.markEventsCommitted();

        list.rename(new ShoppingListName("Wocheneinkauf"), CommandId.generate());

        assertThat(list.uncommittedEvents()).isEmpty();
    }

    @Test
    void renamingAnUnnamedOpenListNamesIt() {
        ShoppingList list = ShoppingList.create(listId, householdId, null, commandId);
        list.markEventsCommitted();

        list.rename(new ShoppingListName("Wocheneinkauf"), CommandId.generate());

        assertThat(list.uncommittedEvents()).hasSize(1);
        assertThat(list.name()).isEqualTo(new ShoppingListName("Wocheneinkauf"));
    }

    @Test
    void replayingShoppingListCreatedThenShoppingListRenamedRebuildsIdenticalStateAndVersion() {
        ShoppingList original =
                ShoppingList.create(listId, householdId, new ShoppingListName("Wocheneinkauf"), commandId);
        original.rename(new ShoppingListName("Getränke"), CommandId.generate());
        List<DomainEvent> history = original.uncommittedEvents();

        ShoppingList rehydrated = ShoppingList.rehydrate(StreamId.forList(listId), history);

        assertThat(rehydrated.listId()).isEqualTo(original.listId());
        assertThat(rehydrated.householdId()).isEqualTo(original.householdId());
        assertThat(rehydrated.name()).isEqualTo(original.name());
        assertThat(rehydrated.status()).isEqualTo(original.status());
        assertThat(rehydrated.version()).isEqualTo(original.version());
    }

    @Test
    void noEventCarriesADisplayNameEmailOrKeycloakUserId() {
        assertNoPersonalDataComponent(ShoppingListCreated.class);
        assertNoPersonalDataComponent(ShoppingListRenamed.class);
    }

    private void assertNoPersonalDataComponent(Class<? extends DomainEvent> eventType) {
        List<String> componentNames = Arrays.stream(eventType.getRecordComponents())
                .map(RecordComponent::getName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();

        assertThat(componentNames)
                .noneMatch(name -> name.contains("displayname")
                        || name.contains("email")
                        || name.contains("keycloak"));
    }
}
