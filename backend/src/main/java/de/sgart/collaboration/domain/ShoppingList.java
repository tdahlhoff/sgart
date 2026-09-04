package de.sgart.collaboration.domain;

import de.sgart.collaboration.domain.event.ItemAdded;
import de.sgart.collaboration.domain.event.ItemAssignedToStore;
import de.sgart.collaboration.domain.event.ItemCheckedOff;
import de.sgart.collaboration.domain.event.ItemDiscarded;
import de.sgart.collaboration.domain.event.ItemMovedToList;
import de.sgart.collaboration.domain.event.ItemPostponedToList;
import de.sgart.collaboration.domain.event.ItemRemoved;
import de.sgart.collaboration.domain.event.ItemRerouted;
import de.sgart.collaboration.domain.event.ItemUnchecked;
import de.sgart.collaboration.domain.event.ItemUpdated;
import de.sgart.collaboration.domain.event.ShoppingListCreated;
import de.sgart.collaboration.domain.event.ShoppingListRenamed;
import de.sgart.collaboration.domain.event.TripCompletedForList;
import de.sgart.collaboration.domain.event.TripStartedForList;
import de.sgart.collaboration.domain.exception.DuplicateItemException;
import de.sgart.collaboration.domain.exception.ItemChangeNotPermittedException;
import de.sgart.collaboration.domain.exception.ItemNotFoundException;
import de.sgart.collaboration.domain.exception.ItemNotDuringTripException;
import de.sgart.collaboration.domain.exception.ListNameChangeNotPermittedException;
import de.sgart.collaboration.domain.exception.TripNotCompletableException;
import de.sgart.collaboration.domain.exception.TripNotStartableException;
import de.sgart.shared.CommandId;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.EventId;
import de.sgart.shared.EventSourcedAggregate;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.ItemId;
import de.sgart.shared.Quantity;
import de.sgart.shared.ShoppingListId;
import de.sgart.shared.StoreId;
import de.sgart.shared.StreamId;
import de.sgart.shared.TripId;
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
    private TripId activeTripId;
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

        if (status != ListStatus.OPEN && status != ListStatus.IN_TRIP) {
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

    /**
     * Assigns an item to a store while planning (Story 2.6, AC1, AC5) — only the list root accepts
     * the command (AD-10). Permitted only while {@link ListStatus#OPEN} (AC5). Does
     * <strong>not</strong> validate that {@code storeId} exists in the household — {@code Store} is
     * an entity inside the separate {@code Household} aggregate, which this root never loads or
     * mutates (Cl. 1, mirrors {@link #moveItem} not validating {@code targetListId}). Validity is
     * enforced client-side (the picker offers only active household stores) and by the read-side
     * archived-store fallback (AC4). Reassigning to a different store raises a new event
     * (last-wins); assigning the same store again is a convergent no-op (raises nothing, AD-8).
     *
     * @param commandId validated for envelope completeness (AD-8) but with no domain meaning here
     */
    public void assignItemToStore(ItemId itemId, StoreId storeId, CommandId commandId) {
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(storeId, "storeId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        requireOpen();

        ItemState existing = itemsById.get(itemId);
        if (existing == null) {
            throw new ItemNotFoundException("No item found for id " + itemId + " on this list");
        }
        if (storeId.equals(existing.assignedStore())) {
            return; // convergent no-op — already assigned to this store (AD-8)
        }
        raise(new ItemAssignedToStore(EventId.generate(), householdId, listId, itemId, storeId));
    }

    /**
     * Starts a trip against this list across the given stores (Story 3.1, AC1, AC2, AC3, Cl. 1/5).
     * Permitted only while {@link ListStatus#OPEN} — a second start on an already {@code IN_TRIP}
     * (or a {@code DONE}) list raises {@link TripNotStartableException} (AC2, the atomic "at most
     * one Active trip per list" guard, since this is the list stream's own expected-version
     * append). Raises {@link TripStartedForList} carrying {@code tripId}/{@code storeIds} as the
     * payload the {@code TripStartProcessManager} needs to create the {@code ShoppingTrip}
     * aggregate (Cl. 1) — this root does <strong>not</strong> load or validate that aggregate (it
     * does not exist yet) and does <strong>not</strong> validate that the stores exist (client
     * picker + AD-3 reference-by-id, mirrors {@link #moveItem} not validating {@code
     * targetListId}). At-least-one-store is enforced fail-fast by the handler (AC3) and, in depth,
     * by the event constructor.
     *
     * @param commandId validated for envelope completeness (AD-8) but with no domain meaning here
     */
    public void startTrip(TripId tripId, List<StoreId> storeIds, CommandId commandId) {
        Objects.requireNonNull(tripId, "tripId must not be null");
        Objects.requireNonNull(storeIds, "storeIds must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");

        if (status != ListStatus.OPEN) {
            throw new TripNotStartableException(
                    "A trip may only be started from an Open list, list is " + status);
        }
        raise(new TripStartedForList(EventId.generate(), householdId, listId, tripId, storeIds));
    }

    /**
     * Re-routes an item to a different trip store <em>during</em> a trip (Story 3.2, AC2, Cl. 1) —
     * the inverse-phase counterpart to {@link #assignItemToStore}: permitted only while {@link
     * ListStatus#IN_TRIP} ({@link #requireInTrip()}), where planning's {@code assignItemToStore}
     * requires {@code OPEN}. An unknown item raises {@link ItemNotFoundException}; rerouting to the
     * item's current store is a convergent no-op (raises nothing, AD-8, mirrors {@link
     * #assignItemToStore}). Does <strong>not</strong> validate that {@code storeId} is one of the
     * trip's stores (Cl. 5) — this aggregate does not know the trip's store set (a separate
     * aggregate, AD-3); the client picker + read-side grouping enforce it.
     *
     * @param commandId validated for envelope completeness (AD-8) but with no domain meaning here
     */
    public void rerouteItem(ItemId itemId, StoreId storeId, CommandId commandId) {
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(storeId, "storeId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        requireInTrip();

        ItemState existing = itemsById.get(itemId);
        if (existing == null) {
            throw new ItemNotFoundException("No item found for id " + itemId + " on this list");
        }
        if (storeId.equals(existing.assignedStore())) {
            return; // convergent no-op — already routed to this store (AD-8)
        }
        raise(new ItemRerouted(EventId.generate(), householdId, listId, itemId, storeId));
    }

    /**
     * Checks an item off during a trip (Story 3.3, AC2, Cl. 1) — the item's status becomes
     * {@link ItemStatus#DONE}. Permitted only while {@link ListStatus#IN_TRIP}. An unknown item
     * raises {@link ItemNotFoundException}; an already-{@code DONE} item is a convergent no-op
     * (raises nothing, AD-8). Check-off does not require a store assignment — unassigned items are
     * checkable. This is the only place an item reaches {@code DONE}.
     *
     * @param commandId validated for envelope completeness (AD-8) but with no domain meaning here
     */
    public void checkOffItem(ItemId itemId, CommandId commandId) {
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        requireInTrip();

        ItemState existing = itemsById.get(itemId);
        if (existing == null) {
            throw new ItemNotFoundException("No item found for id " + itemId + " on this list");
        }
        if (existing.status() == ItemStatus.DONE) {
            return; // convergent no-op — already DONE (AD-8)
        }
        raise(new ItemCheckedOff(EventId.generate(), householdId, listId, itemId));
    }

    /**
     * Unchecks an item during a trip (Story 3.3, AC2/AC3, Cl. 1; Story 3.4, Cl. 1) — the item's
     * status returns to {@link ItemStatus#OPEN}. Permitted only while {@link ListStatus#IN_TRIP}. An
     * unknown item raises {@link ItemNotFoundException}; an already-{@code OPEN} item is a
     * convergent no-op (raises nothing, AD-8). This is the undo affordance for both {@code DONE} and
     * {@code DISCARDED} — unchecking returns any non-{@code OPEN} item to {@code OPEN}.
     *
     * @param commandId validated for envelope completeness (AD-8) but with no domain meaning here
     */
    public void uncheckItem(ItemId itemId, CommandId commandId) {
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        requireInTrip();

        ItemState existing = itemsById.get(itemId);
        if (existing == null) {
            throw new ItemNotFoundException("No item found for id " + itemId + " on this list");
        }
        if (existing.status() == ItemStatus.OPEN) {
            return; // convergent no-op — already OPEN (AD-8)
        }
        raise(new ItemUnchecked(EventId.generate(), householdId, listId, itemId));
    }

    /**
     * Discards an item during a trip (Story 3.4, AC2, Cl. 12) — the item's status becomes the
     * terminal {@link ItemStatus#DISCARDED}. The item stays on the list, dimmed ("Verworfen") — it
     * is <strong>not</strong> removed. Permitted only while {@link ListStatus#IN_TRIP}. An unknown
     * item raises {@link ItemNotFoundException}; an already-{@code DISCARDED} item is a convergent
     * no-op (raises nothing, AD-8). {@link #uncheckItem} returns a {@code DISCARDED} item to
     * {@code OPEN}; {@link #checkOffItem} may still move a {@code DISCARDED} item to {@code DONE}
     * ("found it after all").
     *
     * @param commandId validated for envelope completeness (AD-8) but with no domain meaning here
     */
    public void discardItem(ItemId itemId, CommandId commandId) {
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        requireInTrip();

        ItemState existing = itemsById.get(itemId);
        if (existing == null) {
            throw new ItemNotFoundException("No item found for id " + itemId + " on this list");
        }
        if (existing.status() == ItemStatus.DISCARDED) {
            return; // convergent no-op — already DISCARDED (AD-8)
        }
        raise(new ItemDiscarded(EventId.generate(), householdId, listId, itemId));
    }

    /**
     * Completes the trip against this list (Story 3.4, AC4, AC6, Cl. 2) — the only place a list
     * reaches {@link ListStatus#DONE} and becomes immutable. Permitted only while {@link
     * ListStatus#IN_TRIP} — an {@code OPEN} or {@code DONE} list raises {@link
     * TripNotCompletableException}.
     *
     * <p><strong>Sweep-then-complete (Cl. 2):</strong> raises one {@link ItemDiscarded} for
     * <em>every item still {@code OPEN}</em> (a quality-of-life safety net over the first-class
     * explicit {@link #discardItem}; a {@code DONE} or {@code DISCARDED} item is untouched), then
     * raises {@link TripCompletedForList} (folding the list {@code IN_TRIP → DONE}). All in one
     * command / one append / one aggregate while still {@code IN_TRIP}, so the sweep discards are
     * valid before immutability lands in the same append. The sweep never force-completes — only an
     * explicit confirm from the member triggers this command; "Doch noch weiter einkaufen" (AC5)
     * closes the dialog without calling this.
     *
     * @param commandId validated for envelope completeness (AD-8) but with no domain meaning here
     */
    public void completeTrip(CommandId commandId) {
        Objects.requireNonNull(commandId, "commandId must not be null");

        if (status != ListStatus.IN_TRIP) {
            throw new TripNotCompletableException(
                    "A trip may only be completed from an In-Trip list, list is " + status);
        }
        // Sweep: discard every still-OPEN item (QoL safety net, Cl. 2)
        for (Map.Entry<ItemId, ItemState> entry : itemsById.entrySet()) {
            if (entry.getValue().status() == ItemStatus.OPEN) {
                raise(new ItemDiscarded(EventId.generate(), householdId, listId, entry.getKey()));
            }
        }
        raise(new TripCompletedForList(EventId.generate(), householdId, listId, activeTripId));
    }

    /**
     * Postpones an item onto another list during a trip (Story 3.3, AC4, Cl. 3/6) — the source
     * side of the in-trip cross-aggregate effect (AD-10). The item leaves this list (folds to a
     * removal). Permitted only while {@link ListStatus#IN_TRIP}. An unknown item raises {@link
     * ItemNotFoundException}. Does <strong>not</strong> validate {@code targetListId} — this
     * aggregate does not own the target; that is the handler's job (Cl. 6, mirrors {@link
     * #moveItem}). The target-side add is the {@code ItemMoveProcessManager}'s job.
     *
     * @param commandId validated for envelope completeness (AD-8) but with no domain meaning here
     */
    public void postponeItemToList(ItemId itemId, ShoppingListId targetListId, CommandId commandId) {
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(targetListId, "targetListId must not be null");
        Objects.requireNonNull(commandId, "commandId must not be null");
        requireInTrip();

        ItemState existing = itemsById.get(itemId);
        if (existing == null) {
            throw new ItemNotFoundException("No item found for id " + itemId + " on this list");
        }
        raise(new ItemPostponedToList(
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

    private void requireInTrip() {
        if (status != ListStatus.IN_TRIP) {
            throw new ItemNotDuringTripException(
                    "Items may only be changed during a trip, list is " + status);
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
                itemsById.put(added.itemId(), new ItemState(added.name(), added.note(), added.quantity(), null, ItemStatus.OPEN));
            case ItemUpdated updated -> {
                // Cl. 4/7 regression trap: an edit must carry the existing assignment and status
                // forward — only ItemAssignedToStore may change assignedStore; only the status
                // events may change status.
                ItemState existing = itemsById.get(updated.itemId());
                StoreId assignedStore = existing == null ? null : existing.assignedStore();
                ItemStatus status = existing == null ? ItemStatus.OPEN : existing.status();
                itemsById.put(
                        updated.itemId(),
                        new ItemState(updated.name(), updated.note(), updated.quantity(), assignedStore, status));
            }
            case ItemRemoved removed -> itemsById.remove(removed.itemId());
            case ItemMovedToList moved -> itemsById.remove(moved.itemId());
            case ItemAssignedToStore assigned -> assignStore(assigned.itemId(), assigned.storeId());
            case ItemRerouted rerouted -> assignStore(rerouted.itemId(), rerouted.storeId());
            case TripStartedForList started -> {
                this.status = ListStatus.IN_TRIP;
                this.activeTripId = started.tripId();
            }
            case ItemCheckedOff checkedOff -> setStatus(checkedOff.itemId(), ItemStatus.DONE);
            case ItemUnchecked unchecked -> setStatus(unchecked.itemId(), ItemStatus.OPEN);
            case ItemDiscarded discarded -> setStatus(discarded.itemId(), ItemStatus.DISCARDED);
            case ItemPostponedToList postponedToList -> itemsById.remove(postponedToList.itemId());
            case TripCompletedForList completed -> this.status = ListStatus.DONE;
            default -> throw new IllegalArgumentException(
                    "ShoppingList cannot apply unknown event type: " + event.getClass());
        }
    }

    /**
     * Folds an item's store assignment — shared by {@link ItemAssignedToStore} (planning) and
     * {@link ItemRerouted} (in-trip, Cl. 1) since both converge on the same {@code assignedStore}
     * field (one source of truth for item→store). The command guards item existence before raising,
     * so {@code existing} is non-null for any well-formed stream; skip defensively on a
     * reordered/repaired stream rather than NPE (mirrors the {@code ItemUpdated} case's
     * null-tolerance). Preserves {@code status} — only the status events may change it (Cl. 4).
     */
    private void assignStore(ItemId itemId, StoreId storeId) {
        ItemState existing = itemsById.get(itemId);
        if (existing != null) {
            itemsById.put(itemId, new ItemState(existing.name(), existing.note(), existing.quantity(), storeId, existing.status()));
        }
    }

    /**
     * Folds an item's status — shared by {@link ItemCheckedOff}, {@link ItemUnchecked}, and {@link
     * ItemDiscarded} (Stories 3.3/3.4, Cl. 1/4). The command guards item existence before raising,
     * so {@code existing} is non-null for any well-formed stream; skip defensively on a
     * reordered/repaired stream rather than NPE (mirrors {@link #assignStore}). Preserves all other
     * fields — only this fold may write {@code status}.
     */
    private void setStatus(ItemId itemId, ItemStatus newStatus) {
        ItemState existing = itemsById.get(itemId);
        if (existing != null) {
            itemsById.put(itemId, new ItemState(existing.name(), existing.note(), existing.quantity(), existing.assignedStore(), newStatus));
        }
    }

    /**
     * An item as held inside the {@link ShoppingList} aggregate (AD-10) — the folded state the
     * invariants read (dedup by (name, note), no-op update/remove/assign). Not the read model; that
     * is projected separately (AD-4). {@code assignedStore} is a bare reference into the separate
     * {@code Household} aggregate (AD-3) — {@code null} means unassigned. {@code status} is the
     * item's in-trip lifecycle ({@link ItemStatus}), {@code OPEN} at birth, changed only by the
     * status events (Stories 3.3/3.4, Cl. 4).
     */
    private record ItemState(ItemName name, ItemNote note, Quantity quantity, StoreId assignedStore, ItemStatus status) {}
}
