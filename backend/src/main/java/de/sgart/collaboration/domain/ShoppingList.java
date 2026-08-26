package de.sgart.collaboration.domain;

import de.sgart.collaboration.domain.event.ShoppingListCreated;
import de.sgart.collaboration.domain.event.ShoppingListRenamed;
import de.sgart.collaboration.domain.exception.ListNameChangeNotPermittedException;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.EventSourcedAggregate;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StreamId;
import java.util.List;
import java.util.Objects;

/**
 * The second real aggregate in the Collaboration context (Story 2.1, after {@code Household}): a
 * household's shopping list, named or auto-named, created {@code Open}. A distinct aggregate from
 * {@code Household} (AD-3) — it holds the owning household's id only and never loads or mutates the
 * {@code Household} aggregate; membership is enforced at the handler seam via the Identity ACL, not
 * here. State changes only through {@link #apply(DomainEvent)}, folding {@link ShoppingListCreated}
 * and {@link ShoppingListRenamed} (the {@link EventSourcedAggregate} contract). Simpler than {@code
 * Household}: no member-role map, since membership is a separate aggregate's concern.
 */
public final class ShoppingList extends EventSourcedAggregate {

    private HouseholdId householdId;
    private ShoppingListId listId;
    private ShoppingListName name;
    private ListStatus status;

    private ShoppingList(StreamId streamId) {
        super(streamId);
    }

    /**
     * Creates a brand-new list on its own stream (AC1). {@code name} may be {@code null} — a
     * blank/absent name creates a valid unnamed list (the "Liste N" case, AC2), never an error.
     *
     * @param commandId validated for completeness of the command envelope (AD-8) but with no domain
     *     meaning here; idempotency is the {@code EventStore}'s concern, not the aggregate's
     */
    public static ShoppingList create(
            ShoppingListId listId, HouseholdId householdId, ShoppingListName name, CommandId commandId) {
        Objects.requireNonNull(listId, "listId must not be null");
        Objects.requireNonNull(householdId, "householdId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        // name is intentionally nullable — an unnamed list is valid (AC1/AC2).

        ShoppingList list = new ShoppingList(StreamId.forList(listId));
        list.raise(new ShoppingListCreated(EventId.generate(), householdId, listId, name));
        return list;
    }

    /** Rebuilds a list from its persisted event history (empty history for an unseen stream). */
    public static ShoppingList rehydrate(StreamId streamId, List<? extends DomainEvent> history) {
        ShoppingList list = new ShoppingList(streamId);
        list.replay(history);
        return list;
    }

    public HouseholdId householdId() {
        return householdId;
    }

    public ShoppingListId listId() {
        return listId;
    }

    public ShoppingListName name() {
        return name;
    }

    public ListStatus status() {
        return status;
    }

    /**
     * Renames the list (AC3) — permitted only while the list is {@link ListStatus#OPEN} (and, from
     * Epic 3, {@code IN_TRIP}); a {@code DONE} list raises {@link
     * ListNameChangeNotPermittedException}. Not role-gated — membership is the handler's job, and
     * this aggregate does not know household roles (it is a separate aggregate from {@code
     * Household}). A rename to the current name is a convergent no-op — it raises nothing (AD-8).
     *
     * @param commandId validated for completeness of the command envelope (AD-8) but with no domain
     *     meaning here; idempotency is the {@code EventStore}'s concern, not the aggregate's
     */
    public void rename(ShoppingListName newName, CommandId commandId) {
        Objects.requireNonNull(newName, "newName must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");

        if (status != ListStatus.OPEN) {
            throw new ListNameChangeNotPermittedException(
                    "Only an Open (or In-Trip) list's name may be changed, list is " + status);
        }
        if (newName.equals(this.name)) {
            return; // convergent no-op — the name is already what the caller asked for (AD-8)
        }
        raise(new ShoppingListRenamed(EventId.generate(), listId, newName));
    }

    @Override
    protected void apply(DomainEvent event) {
        switch (event) {
            case ShoppingListCreated created -> {
                this.householdId = created.householdId();
                this.listId = created.listId();
                this.name = created.name();
                this.status = ListStatus.OPEN;
            }
            case ShoppingListRenamed renamed -> this.name = renamed.newName();
            default -> throw new IllegalArgumentException(
                    "ShoppingList cannot apply unknown event type: " + event.getClass());
        }
    }
}
