package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.application.LiveConnectionRegistry;
import de.sgart.collaboration.domain.event.MemberLeft;
import de.sgart.collaboration.domain.event.MemberRemoved;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The live-sync transport (Story 4.4, AC1/AC3, LD-1/LD-2): a <strong>third, independent</strong>
 * consumer of the event streams — alongside the read-model projectors and the process-manager
 * subscription — that never writes, never projects, only observes and pushes a content-free
 * "changed" nudge to connected household members. Structurally mirrors {@link
 * CollaborationProcessManagerSubscription} exactly ({@link SmartLifecycle}, autoStart flag,
 * single-threaded resubscribe, per-event log-and-skip); the one difference is {@link
 * SubscribeToAllOptions#fromEnd()} — LD-2, this fan-out needs only <em>new</em> events and holds no
 * per-client position, so a downtime gap is healed by the client's reconnect-refetch (AC2), never
 * by replay. Filters the three prefixes that carry user-visible change ({@code household-},
 * {@code list-}, {@code trip-}) on one subscription via a single regular-expression filter — see
 * {@link #subscribe()} for why this is not the chained {@code addStreamNamePrefix} calls {@link
 * ShoppingListReadModelProjector} appears to use (a client-library constraint discovered while
 * writing this story's Testcontainers test, T12).
 *
 * <p>Also enforces "mapping = access" (AC3, T6) for the live channel: on {@link MemberRemoved}/
 * {@link MemberLeft} it evicts that member's open connections; on {@code HouseholdDeleted} it
 * evicts every connection for the household. New connections are already blocked by the SSE
 * endpoint's {@code ResolveMemberIdentity} gate — this handles the already-open ones. The eviction
 * race against the (synchronous) ACL de-link is benign by design: see the story's Dev Notes.
 */
public final class HouseholdLiveSyncFanout implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(HouseholdLiveSyncFanout.class);
    private static final Duration RESUBSCRIBE_DELAY = Duration.ofSeconds(5);

    private final KurrentDBClient client;
    private final LiveConnectionRegistry registry;
    private final HouseholdResolver resolver;
    private final DomainEventJsonCodec codec = new DomainEventJsonCodec();
    private final boolean autoStart;

    private volatile boolean running;
    private ScheduledExecutorService resubscribeScheduler;
    private volatile CompletableFuture<Subscription> currentSubscription;
    private final AtomicInteger resubscribeScheduleCount = new AtomicInteger();

    public HouseholdLiveSyncFanout(KurrentDBClient client, LiveConnectionRegistry registry, HouseholdResolver resolver) {
        this(client, registry, resolver, false);
    }

    public HouseholdLiveSyncFanout(
            KurrentDBClient client, LiveConnectionRegistry registry, HouseholdResolver resolver, boolean autoStart) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.autoStart = autoStart;
    }

    /**
     * Reacts to one event: evicts on the de-link events (T6) and always broadcasts a nudge (T5).
     * Takes the raw stream key/event-type/body rather than a {@link RecordedEvent} so this routing
     * logic is unit-testable without the event-store client's package-private constructor.
     */
    void react(String streamId, String eventType, byte[] eventData) {
        Optional<HouseholdId> householdId = resolver.resolveHouseholdId(streamId);
        if (householdId.isEmpty()) {
            // A list-/trip-scoped event whose row isn't projected yet — a projector-race edge with
            // no practical occurrence (Dev Notes). Skip; there is nothing to notify.
            return;
        }
        if (streamId.startsWith(StreamId.StreamType.HOUSEHOLD.prefix() + "-")) {
            if (DomainEventJsonCodec.MEMBER_REMOVED_TYPE.equals(eventType)) {
                MemberRemoved removed = (MemberRemoved) codec.fromJsonBytes(eventType, eventData);
                registry.evictMember(householdId.get(), removed.memberId());
            } else if (DomainEventJsonCodec.MEMBER_LEFT_TYPE.equals(eventType)) {
                MemberLeft left = (MemberLeft) codec.fromJsonBytes(eventType, eventData);
                registry.evictMember(householdId.get(), left.memberId());
            } else if (DomainEventJsonCodec.HOUSEHOLD_DELETED_TYPE.equals(eventType)) {
                registry.evictHousehold(householdId.get());
            }
        }
        registry.broadcast(householdId.get(), resolver.resourceFor(streamId, eventType));
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        resubscribeScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "household-live-sync-fanout-resubscribe");
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
        // Cancel the live $all subscription itself — without this, `running=false` only stops
        // future resubscribes; the already-open subscription keeps firing onEvent into a registry
        // whose lifecycle has otherwise ended (and, in tests, leaks across cases sharing a static
        // client).
        if (currentSubscription != null) {
            currentSubscription.thenAccept(Subscription::stop);
            currentSubscription = null;
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

    /**
     * Test seam (CLAUDE.md §6): how many times a resubscribe has been queued so far — lets a test
     * prove the initial-subscribe-failure path (Review) schedules a resubscribe without waiting out
     * the full {@link #RESUBSCRIBE_DELAY} for it to actually run.
     */
    int resubscribeScheduleCount() {
        return resubscribeScheduleCount.get();
    }

    private void subscribe() {
        // NOT SubscriptionFilter.newBuilder().addStreamNamePrefix(...) chained three times: verified
        // against the client (1.2.1) that a second addStreamNamePrefix call always throws
        // IllegalStateException("Filter type is already set to STREAM") — it accepts exactly one
        // prefix per filter, despite ShoppingListReadModelProjector's/ShoppingTripReadModelProjector's
        // two-prefix filters reading as if chaining were supported (their live subscriptions, driven
        // only by project(...) directly in tests, have never actually exercised this path — a
        // pre-existing latent defect out of this story's scope, LD-2, to fix). A single regular
        // expression covers all three prefixes in one filter instead.
        SubscriptionFilter filter = SubscriptionFilter.newBuilder()
                .withStreamNameRegularExpression("^(%s|%s|%s)-.*".formatted(
                        StreamId.StreamType.HOUSEHOLD.prefix(),
                        StreamId.StreamType.LIST.prefix(),
                        StreamId.StreamType.TRIP.prefix()))
                .build();
        CompletableFuture<Subscription> subscription = client.subscribeToAll(
                new SubscriptionListener() {
                    @Override
                    public void onEvent(Subscription subscription, ResolvedEvent resolvedEvent) {
                        RecordedEvent recordedEvent = resolvedEvent.getOriginalEvent();
                        try {
                            react(recordedEvent.getStreamId(), recordedEvent.getEventType(), recordedEvent.getEventData());
                        } catch (RuntimeException failure) {
                            // Never let one bad event tear down the whole subscription — log and skip;
                            // a missed nudge self-heals via the client's reconnect-refetch (AC2).
                            log.error("Failed to fan out live-sync event {}", recordedEvent.getEventType(), failure);
                        }
                    }

                    @Override
                    public void onCancelled(Subscription subscription, Throwable throwable) {
                        if (throwable != null) {
                            log.warn("Live-sync fan-out subscription dropped; resubscribing", throwable);
                        }
                        scheduleResubscribe();
                    }
                },
                SubscribeToAllOptions.get().fromEnd().filter(filter));
        if (!retainSubscription(subscription)) {
            // stop() won the race between subscribeToAll returning and the assignment below (this
            // runs unsynchronized on the resubscribe scheduler thread). Never retain the live
            // subscription then — it would be orphaned, firing onEvent into an ended registry and
            // never stopped. Cancel it as soon as it lands, and do not schedule a resubscribe.
            subscription.thenAccept(Subscription::stop);
            return;
        }
        // onCancelled only fires once a subscription was actually established; if the initial
        // subscribeToAll call itself fails (e.g. KurrentDB unreachable at start()), that callback
        // never runs and this fan-out would otherwise never subscribe again. Mirror the same
        // resubscribe here.
        subscription.exceptionally(failure -> {
            log.warn("Live-sync fan-out failed to subscribe; resubscribing", failure);
            scheduleResubscribe();
            return null;
        });
    }

    /**
     * Atomically retain a just-created subscription, but only while still running (the shutdown-race
     * guard, Epic 4 retro): {@link #stop()} sets {@code running=false} and clears/cancels {@link
     * #currentSubscription} under this same monitor, so a subscription created concurrently on the
     * resubscribe thread is either retained-then-cancelled-by-stop or rejected here — never orphaned.
     *
     * @return {@code true} if retained (still running); {@code false} if {@code stop()} already ran,
     *     in which case the caller must cancel the subscription itself.
     */
    synchronized boolean retainSubscription(CompletableFuture<Subscription> subscription) {
        if (!running) {
            return false;
        }
        currentSubscription = subscription;
        return true;
    }

    private synchronized void scheduleResubscribe() {
        if (running && resubscribeScheduler != null) {
            resubscribeScheduleCount.incrementAndGet();
            resubscribeScheduler.schedule(this::subscribe, RESUBSCRIBE_DELAY.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
