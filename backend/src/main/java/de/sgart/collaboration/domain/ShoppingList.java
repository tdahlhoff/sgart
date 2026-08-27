package de.sgart.collaboration.domain;

import de.sgart.collaboration.domain.event.ItemAdded;
import de.sgart.collaboration.domain.event.ItemMovedToList;
import de.sgart.collaboration.domain.event.ItemRemoved;
import de.sgart.collaboration.domain.event.ItemUpdated;
import de.sgart.collaboration.domain.event.ShoppingListCreated;
import de.sgart.collaboration.domain.event.ShoppingListRenamed;
import de.sgart.collaboration.domain.exception.DuplicateItemException;
import de.sgart.collaboration.domain.exception.ItemChangeNotPermittedException;
import de.sgart.collaboration.domain.exception.ItemNotFoundException;
import de.sgart.collaboration.domain.exception.ListNameChangeNotPermittedException;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.EventSourcedAggregate;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ItemId;
import de.sgart.shared.Quantity;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StreamId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The second real aggregate in the Collaboration context (Story 2.1, after {@code Household}): a
 * household's shopping list, named or auto-named, created {@code Open}. A distinct aggregate from
 * {@code Household} (AD-3) — it holds the owning household's id only and never loads or mutates the
 * {@code Household} aggregate; membership is enforced at the handler seam via the Identity ACL, not
 * here. State changes only through {@link #apply(DomainEvent)}, folding {@link ShoppingListCreated}
 * and {@link ShoppingListRenamed} (the {@link EventSourcedAggregate} contract). Simpler than {@code
 * Household}: no member-role map, since membership is a separate aggregate's concern.
 *
 * <p>{@code Item} is an entity <em>inside</em> this aggregate (Story 2.3, AD-10) — realised the
 * same way {@code Store} is realised inside {@code Household}: folded {@link ItemState} keyed by
 * {@link ItemId}, with dedup + no-op guards enforced here, on the root.
 */
public final class ShoppingList extends EventSourcedAggregate {

    private HouseholdId householdId;
    private ShoppingListId listId;
    private ShoppingListName name;
    private ListStatus status;
    private final Map<ItemId, ItemState> itemsById = new LinkedHashMap<>();

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

    /**
     * Adds an item to the list (Story 2.3, AC1) — only the list root accepts the command (AD-10).
     * Permitted only while {@link ListStatus#OPEN} (AC5). Items are keyed by (name, note), trimmed
     * and compared case-insensitively (mirrors {@code Household.hasActiveStoreNamed}); an exact
     * duplicate is rejected with {@link DuplicateItemException} (AC2).
     *
     * @param commandId validated for envelope completeness (AD-8) but with no domain meaning here
     */
    public void addItem(ItemId itemId, ItemName name, ItemNote note, Quantity quantity, CommandId commandId) {
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        // note is intentionally nullable — an item may carry no note (AC1/AC2).
        requireOpen();

        if (hasItemKeyed(name, note, null)) {
            throw new DuplicateItemException(
                    "An item named '" + name.value() + "' with the same note already exists on this list");
        }
        raise(new ItemAdded(EventId.generate(), householdId, listId, itemId, name, note, quantity));
    }

    /**
     * Updates an existing item's name, note, and/or quantity (Story 2.3, AC3). Permitted only while
     * {@link ListStatus#OPEN} (AC5). An unknown item raises {@link ItemNotFoundException}; a
     * (name, note) collision with a <em>different</em> item raises {@link DuplicateItemException}; a
     * fully unchanged update is a convergent no-op (raises nothing, AD-8, mirrors {@link #rename}).
     *
     * @param commandId validated for envelope completeness (AD-8) but with no domain meaning here
     */
    public void updateItem(ItemId itemId, ItemName name, ItemNote note, Quantity quantity, CommandId commandId) {
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        requireOpen();

        ItemState existing = itemsById.get(itemId);
        if (existing == null) {
            throw new ItemNotFoundException("No item found for id " + itemId + " on this list");
        }
        if (existing.name().equals(name) && Objects.equals(existing.note(), note) && quantitiesEqual(existing.quantity(), quantity)) {
            return; // convergent no-op — the item already matches what the caller asked for (AD-8)
        }
        if (hasItemKeyed(name, note, itemId)) {
            throw new DuplicateItemException(
                    "An item named '" + name.value() + "' with the same note already exists on this list");
        }
        raise(new ItemUpdated(EventId.generate(), listId, itemId, name, note, quantity));
    }

    /**
     * Removes an item from the list (Story 2.3, AC4). Permitted only while {@link
     * ListStatus#OPEN} (AC5). Removing an unknown/already-removed item is a convergent no-op — it
     * raises nothing (idempotent delete, AD-8, mirrors {@code Household.archiveStore}).
     *
     * @param commandId validated for envelope completeness (AD-8) but with no domain meaning here
     */
    public void removeItem(ItemId itemId, CommandId commandId) {
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        requireOpen();

        if (!itemsById.containsKey(itemId)) {
            return; // convergent no-op — nothing to remove (AD-8)
        }
        raise(new ItemRemoved(EventId.generate(), listId, itemId));
    }

    /**
     * Moves an item to another list while planning (Story 2.4, AC1, AC5, AC9) — the source side of
     * SGART's first cross-aggregate effect (AD-10). Permitted only while this (source) list is
     * {@link ListStatus#OPEN} (AC5); an unknown item raises {@link ItemNotFoundException}. Raises
     * {@link ItemMovedToList} carrying the item's current name/note/quantity so the {@code
     * ItemMoveProcessManager} can add it to the target without reloading this aggregate. Does
     * <strong>not</strong> validate {@code targetListId} — this aggregate does not own the target
     * list; that is the handler's job (Cl. 4), since the target is a separate aggregate this root
     * never loads or mutates (AD-10).
     *
     * @param commandId validated for envelope completeness (AD-8) but with no domain meaning here
     */
    public void moveItem(ItemId itemId, ShoppingListId targetListId, CommandId commandId) {
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(targetListId, "targetListId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        requireOpen();

        ItemState existing = itemsById.get(itemId);
        if (existing == null) {
            throw new ItemNotFoundException("No item found for id " + itemId + " on this list");
        }
        raise(new ItemMovedToList(
                EventId.generate(),
                householdId,
                listId,
                itemId,
                targetListId,
                existing.name(),
                existing.note(),
                existing.quantity()));
    }

    private void requireOpen() {
        if (status != ListStatus.OPEN) {
            throw new ItemChangeNotPermittedException(
                    "Items may only be changed on an Open list, list is " + status);
        }
    }

    /**
     * Compares two quantities by <em>value</em>, not scale. {@link Quantity} wraps a {@link
     * java.math.BigDecimal}, whose {@code equals} is scale-sensitive ({@code 1} ≠ {@code 1.0}) — so
     * the convergent-no-op check must use {@code compareTo} on the amount, or an unchanged update
     * that merely re-sends {@code 1.0} for a stored {@code 1} would raise a spurious {@code ItemUpdated}.
     */
    private static boolean quantitiesEqual(Quantity left, Quantity right) {
        return left.unit() == right.unit() && left.amount().compareTo(right.amount()) == 0;
    }

    /** @param excludedItemId an item id to ignore when checking (the item being updated), or {@code null} */
    private boolean hasItemKeyed(ItemName name, ItemNote note, ItemId excludedItemId) {
        String candidateName = name.value().toLowerCase(Locale.ROOT);
        String candidateNote = note == null ? null : note.value().toLowerCase(Locale.ROOT);
        return itemsById.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(excludedItemId))
                .map(Map.Entry::getValue)
                .anyMatch(item -> item.name().value().toLowerCase(Locale.ROOT).equals(candidateName)
                        && Objects.equals(
                                item.note() == null ? null : item.note().value().toLowerCase(Locale.ROOT),
                                candidateNote));
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
            case ItemAdded added ->
                itemsById.put(added.itemId(), new ItemState(added.name(), added.note(), added.quantity()));
            case ItemUpdated updated ->
                itemsById.put(updated.itemId(), new ItemState(updated.name(), updated.note(), updated.quantity()));
            case ItemRemoved removed -> itemsById.remove(removed.itemId());
            case ItemMovedToList moved -> itemsById.remove(moved.itemId());
            default -> throw new IllegalArgumentException(
                    "ShoppingList cannot apply unknown event type: " + event.getClass());
        }
    }

    /**
     * An item as held inside the {@link ShoppingList} aggregate (AD-10) — the folded state the
     * invariants read (dedup by (name, note), no-op update/remove). Not the read model; that is
     * projected separately (AD-4).
     */
    private record ItemState(ItemName name, ItemNote note, Quantity quantity) {}
}
