package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.application.ItemMoveProcessManager;
import de.sgart.collaboration.domain.event.ItemMovedToList;
import de.sgart.shared.DomainEvent;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The transport for {@link ItemMoveProcessManager} (Story 2.4, AD-10): a <strong>second,
 * independent</strong> subscription over the {@code list-} stream prefix, alongside {@link
 * ShoppingListReadModelProjector}'s — same prefix, different consumer, no interaction between the
 * two (the projector only projects; this only reacts to {@code ItemMovedToList}). Structurally
 * mirrors {@link ShoppingListReadModelProjector} exactly: a {@link SmartLifecycle} bean, auto-start
 * gated by the same flag pattern (default off; construction does no I/O so {@code contextLoads()}
 * survives KurrentDB down), catch-up-from-start on every (re)subscribe (which is what makes the
 * process manager's derived-command-id idempotency the exactly-once mechanism, AC2), and per-event
 * log-and-skip so one bad event never tears the subscription down.
 */
public final class CollaborationProcessManagerSubscription implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CollaborationProcessManagerSubscription.class);
    private static final Duration RESUBSCRIBE_DELAY = Duration.ofSeconds(5);

    private final KurrentDBClient client;
    private final ItemMoveProcessManager processManager;
    private final DomainEventJsonCodec codec = new DomainEventJsonCodec();
    private final boolean autoStart;

    private volatile boolean running;
    private ScheduledExecutorService resubscribeScheduler;

    public CollaborationProcessManagerSubscription(KurrentDBClient client, ItemMoveProcessManager processManager) {
        this(client, processManager, false);
    }

    public CollaborationProcessManagerSubscription(
            KurrentDBClient client, ItemMoveProcessManager processManager, boolean autoStart) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.processManager = Objects.requireNonNull(processManager, "processManager must not be null");
        this.autoStart = autoStart;
    }

    /** Reacts to one event; only {@code ItemMovedToList} triggers the process manager. */
    void react(DomainEvent event) {
        if (event instanceof ItemMovedToList moved) {
            processManager.onItemMovedToList(moved);
        }
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        resubscribeScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "item-move-process-manager-resubscribe");
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
                            react(codec.fromJsonBytes(recordedEvent.getEventType(), recordedEvent.getEventData()));
                        } catch (RuntimeException failure) {
                            // Never let one bad event tear down the whole subscription — log and skip;
                            // a later catch-up replay retries with the same derived command id (idempotent).
                            log.error("Failed to process move event {}", recordedEvent.getEventType(), failure);
                        }
                    }

                    @Override
                    public void onCancelled(Subscription subscription, Throwable throwable) {
                        if (throwable != null) {
                            log.warn("Item move process-manager subscription dropped; resubscribing", throwable);
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
