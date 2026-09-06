package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.domain.ItemName;
import de.sgart.collaboration.domain.ItemStatus;
import de.sgart.collaboration.domain.event.ItemAdded;
import de.sgart.collaboration.domain.event.ItemAssignedToStore;
import de.sgart.collaboration.domain.event.ItemCheckedOff;
import de.sgart.collaboration.domain.event.ItemDiscarded;
import de.sgart.collaboration.domain.event.ItemTransferCancelled;
import de.sgart.collaboration.domain.event.ItemTransferConfirmed;
import de.sgart.collaboration.domain.event.ItemTransferInitiated;
import de.sgart.collaboration.domain.event.TripCompletedForList;
import de.sgart.collaboration.domain.event.ItemRemoved;
import de.sgart.collaboration.domain.event.ItemRerouted;
import de.sgart.collaboration.domain.event.ItemUnchecked;
import de.sgart.collaboration.domain.event.ItemUpdated;
import de.sgart.collaboration.domain.event.ShoppingListCreated;
import de.sgart.collaboration.domain.event.ShoppingListRenamed;
import de.sgart.collaboration.domain.event.TripStartedForList;
import de.sgart.shared.DomainEvent;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.StreamId;
import io.kurrent.dbclient.KurrentDBClient;
import io.kurrent.dbclient.RecordedEvent;
import io.kurrent.dbclient.ResolvedEvent;
import io.kurrent.dbclient.SubscribeToAllOptions;
import io.kurrent.dbclient.Subscription;
import io.kurrent.dbclient.SubscriptionFilter;
import io.kurrent.dbclient.SubscriptionListener;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The second projector (Story 2.1): subscribes to every {@code list-} stream and folds {@code
 * ShoppingListCreated}/{@code ShoppingListRenamed} into the PostgreSQL read model ({@link
 * JdbcShoppingListReadModel}) — command handlers never write the read model directly (AD-4). A
 * separate projector from {@link HouseholdReadModelProjector} — that one's subscription filter is
 * the {@code household-} prefix, so lists need their own subscription over the {@code list-}
 * prefix rather than overloading it. Eventually consistent (AR3/NFR9): the create-list response
 * carries no body (the client minted the {@code listId}), so first paint never waits on this
 * catching up.
 *
 * <p>Runs as a {@link SmartLifecycle} whose auto-start is gated by a flag (default off), mirroring
 * {@link HouseholdReadModelProjector} exactly, so {@code contextLoads()} survives KurrentDB/Postgres
 * being down: constructing the bean performs no I/O, and the live subscription only opens when a
 * real deployment enables it against a reachable KurrentDB. The subscription resubscribes on a
 * dropped connection and logs-and-skips a failure projecting one event rather than tearing the
 * subscription down; re-projection is idempotent (upserts), so replay-from-start after a reconnect
 * is safe.
 */
public final class ShoppingListReadModelProjector implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ShoppingListReadModelProjector.class);
    private static final Duration RESUBSCRIBE_DELAY = Duration.ofSeconds(5);

    private final KurrentDBClient client;
    private final JdbcShoppingListReadModel readModel;
    private final JdbcItemReadModel itemReadModel;
    private final JdbcItemSuggestionReadModel itemSuggestionReadModel;
    private final DomainEventJsonCodec codec = new DomainEventJsonCodec();
    private final boolean autoStart;

    private volatile boolean running;
    private ScheduledExecutorService resubscribeScheduler;

    public ShoppingListReadModelProjector(
            KurrentDBClient client,
            JdbcShoppingListReadModel readModel,
            JdbcItemReadModel itemReadModel,
            JdbcItemSuggestionReadModel itemSuggestionReadModel) {
        this(client, readModel, itemReadModel, itemSuggestionReadModel, false);
    }

    public ShoppingListReadModelProjector(
            KurrentDBClient client,
            JdbcShoppingListReadModel readModel,
            JdbcItemReadModel itemReadModel,
            JdbcItemSuggestionReadModel itemSuggestionReadModel,
            boolean autoStart) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.readModel = Objects.requireNonNull(readModel, "readModel must not be null");
        this.itemReadModel = Objects.requireNonNull(itemReadModel, "itemReadModel must not be null");
        this.itemSuggestionReadModel =
                Objects.requireNonNull(itemSuggestionReadModel, "itemSuggestionReadModel must not be null");
        this.autoStart = autoStart;
    }

    /** Folds one event into the read model. Idempotent — safe to call again for the same event. */
    public void project(DomainEvent event) {
        switch (event) {
            case ShoppingListCreated created ->
                readModel.insertList(created.householdId(), created.listId(), created.name());
            case ShoppingListRenamed renamed -> readModel.renameList(renamed.listId(), renamed.newName());
            case ItemAdded added -> {
                itemReadModel.insertItem(
                        added.householdId(),
                        added.listId(),
                        added.itemId(),
                        added.name(),
                        added.note(),
                        added.quantity());
                // Also record usage in the history-surviving suggestion read model (Story 2.5, Cl. 1/5) —
                // ItemAdded carries householdId directly, unlike ItemUpdated below.
                itemSuggestionReadModel.recordUsage(
                        added.householdId(), added.name(), added.note(), added.quantity());
            }
            case ItemUpdated updated -> {
                itemReadModel.updateItem(updated.itemId(), updated.name(), updated.note(), updated.quantity());
                // ItemUpdated carries no householdId (Story 2.3 event) — resolve it via the item read
                // model, whose row already exists (its ItemAdded was projected earlier on the same
                // ordered stream). An empty lookup is an out-of-order/replay edge: skip the suggestion
                // for this event, a later full replay recovers it (Cl. 5).
                Optional<HouseholdId> householdId = itemReadModel.householdIdOf(updated.itemId());
                if (householdId.isPresent()) {
                    itemSuggestionReadModel.recordUsage(
                            householdId.get(), updated.name(), updated.note(), updated.quantity());
                } else {
                    log.debug(
                            "Skipping suggestion recording for ItemUpdated {} — household not yet resolvable",
                            updated.itemId());
                }
            }
            case ItemRemoved removed -> itemReadModel.removeItem(removed.itemId(), removed.listId());
            case ItemAssignedToStore assigned -> {
                itemReadModel.assignStore(assigned.itemId(), assigned.storeId());
                // Also record the name's last-used store on the suggestion read model (AC6, Cl. 6) —
                // the event carries no name, so resolve it via the item read model (its ItemAdded row
                // was projected earlier on the same ordered stream). Empty on an out-of-order/replay
                // edge: skip the suggestion write, a later full replay recovers it (mirrors ItemUpdated).
                Optional<ItemName> name = itemReadModel.nameOf(assigned.itemId());
                if (name.isPresent()) {
                    itemSuggestionReadModel.recordDefaultStore(assigned.householdId(), name.get(), assigned.storeId());
                } else {
                    log.debug(
                            "Skipping suggestion default-store recording for ItemAssignedToStore {} — name not yet resolvable",
                            assigned.itemId());
                }
            }
            case TripStartedForList started -> readModel.markInTrip(started.listId(), started.tripId());
            case TripCompletedForList completed -> readModel.markDone(completed.listId());
            case ItemRerouted rerouted -> itemReadModel.assignStore(rerouted.itemId(), rerouted.storeId());
            case ItemCheckedOff checkedOff -> itemReadModel.setStatus(checkedOff.itemId(), ItemStatus.DONE);
            case ItemUnchecked unchecked -> itemReadModel.setStatus(unchecked.itemId(), ItemStatus.OPEN);
            case ItemDiscarded discarded -> itemReadModel.setStatus(discarded.itemId(), ItemStatus.DISCARDED);
            case ItemTransferInitiated initiated -> itemReadModel.setTransferPending(initiated.itemId(), true);
            case ItemTransferConfirmed confirmed -> itemReadModel.removeItem(confirmed.itemId(), confirmed.listId());
            case ItemTransferCancelled cancelled -> itemReadModel.setTransferPending(cancelled.itemId(), false);
            default -> {
                // The subscription filter (see start()) only ever delivers list-stream events. The
                // trip's own TripStarted (on trip-{id}) is not projected in 3.1 (Cl. 2) — it never
                // reaches this filter anyway.
            }
        }
    }

    /**
     * Opens the catch-up-then-live subscription across every list stream, projecting each event as
     * it arrives and resubscribing if the connection drops. Never opens a connection at bean
     * construction — only here, once (auto-)started against a reachable KurrentDB.
     */
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        resubscribeScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "shopping-list-projector-resubscribe");
            thread.setDaemon(true);
            return thread;
        });
        subscribe();
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (resubscribeScheduler != null) {
            resubscribeScheduler.shutdownNow();
            resubscribeScheduler = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return autoStart;
    }

    private void subscribe() {
        SubscriptionFilter filter = SubscriptionFilter.newBuilder()
                .addStreamNamePrefix(StreamId.StreamType.LIST.prefix() + "-")
                .build();
        client.subscribeToAll(
                new SubscriptionListener() {
                    @Override
                    public void onEvent(Subscription subscription, ResolvedEvent resolvedEvent) {
                        RecordedEvent recordedEvent = resolvedEvent.getOriginalEvent();
                        try {
                            project(codec.fromJsonBytes(recordedEvent.getEventType(), recordedEvent.getEventData()));
                        } catch (RuntimeException failure) {
                            // Never let one bad event tear down the whole subscription — log and skip;
                            // re-projection is idempotent, so a later replay recovers it.
                            log.error("Failed to project shopping list event {}", recordedEvent.getEventType(), failure);
                        }
                    }

                    @Override
                    public void onCancelled(Subscription subscription, Throwable throwable) {
                        if (throwable != null) {
                            log.warn("Shopping list read-model subscription dropped; resubscribing", throwable);
                        }
                        scheduleResubscribe();
                    }
                },
                SubscribeToAllOptions.get().fromStart().filter(filter));
    }

    private synchronized void scheduleResubscribe() {
        if (running && resubscribeScheduler != null) {
            resubscribeScheduler.schedule(this::subscribe, RESUBSCRIBE_DELAY.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
