package de.sgart.collaboration.adapter.out;

import de.sgart.collaboration.application.ChangeNudge;
import de.sgart.collaboration.application.ContentFreePushSender;
import de.sgart.collaboration.application.PushDeliveryResult;
import de.sgart.identity.application.PruneDeviceToken;
import de.sgart.identity.application.PushTarget;
import de.sgart.identity.application.ResolveHouseholdPushTargets;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.StreamId;
import io.kurrent.dbclient.KurrentDBClient;
import io.kurrent.dbclient.RecordedEvent;
import io.kurrent.dbclient.ResolvedEvent;
import io.kurrent.dbclient.SubscribeToAllOptions;
import io.kurrent.dbclient.Subscription;
import io.kurrent.dbclient.SubscriptionFilter;
import io.kurrent.dbclient.SubscriptionListener;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The content-free background-push transport (Story 4.5, AC1/AC2/AC4) — a <strong>fourth,
 * independent</strong> consumer of the event streams, alongside the read-model projectors, the
 * process-manager subscription, and (Story 4.4) {@link HouseholdLiveSyncFanout}. Structurally
 * mirrors {@link HouseholdLiveSyncFanout} exactly ({@link SmartLifecycle}, autoStart flag,
 * single-threaded resubscribe, per-event log-and-skip, the single-regex {@code fromEnd} {@code
 * $all} filter — see that class's {@code subscribe()} javadoc for why chained {@code
 * addStreamNamePrefix} calls do not work). The one real difference is <em>what</em> it reacts to
 * and <em>who</em> it reaches: this fan-out routes a fixed MVP event set to a content-free {@link
 * ChangeNudge} (AC2) and resolves recipients through the Identity ACL's published {@link
 * ResolveHouseholdPushTargets} port (AC4, "mapping = access" — a de-linked member has no mapping
 * row, so they resolve to zero targets), then delivers through the swappable {@link
 * ContentFreePushSender} port (AC3).
 *
 * <p><strong>Fixed MVP trigger set (AC2, D1):</strong> a list-scoped mutation event (item
 * add/update/remove/status-change/transfer/assignment/reroute, list create/rename — see {@link
 * #LIST_CHANGED_EVENT_TYPES}) fires {@code resource=list}, debounced to at most one ping per
 * {@code listId} per {@link #debounceWindow}. {@code TripStarted}/{@code TripCompleted} (raised on
 * the {@code trip-} stream, distinct from the list-scoped {@code TripStartedForList}/{@code
 * TripCompletedForList} that drive {@link
 * de.sgart.collaboration.application.TripLifecycleProcessManager}) fire {@code resource=trip},
 * never debounced. Every other event — including every household-/member-scoped event — is
 * ignored: invitation push is deferred (D1) and there is no per-member notification
 * configuration in MVP (AC2, {@code NotificationSettingsUpdated} reserved-not-built).
 *
 * <p><strong>Debounce (AC2):</strong> an in-memory {@code listId -> lastSentAt} map — a
 * single-node modular-monolith simplification (KISS); a restart resets it, which merely means the
 * next list-changed event pings immediately instead of waiting out a window that started before
 * the restart — never a correctness issue, only ever more pings, and a missed ping always
 * self-heals via SSE/refetch (same reasoning as 4.4's {@code fromEnd}). {@link #clock} is
 * injected so the boundary is deterministically testable.
 *
 * <p><strong>Stale-token pruning (AC5):</strong> when {@link ContentFreePushSender#send} reports
 * {@link PushDeliveryResult#TOKEN_INVALID}, the token is pruned through Identity's {@link
 * PruneDeviceToken} port — never by reaching into {@code identity.domain} directly (AD-2).
 */
public final class HouseholdNotificationFanout implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(HouseholdNotificationFanout.class);
    private static final Duration RESUBSCRIBE_DELAY = Duration.ofSeconds(5);
    private static final String LIST_PREFIX = StreamId.StreamType.LIST.prefix() + "-";
    private static final String TRIP_PREFIX = StreamId.StreamType.TRIP.prefix() + "-";
    /** Sweep {@link #lastSentAtByListId} for stale entries only once it grows past this size. */
    private static final int DEBOUNCE_MAP_EVICTION_THRESHOLD = 1024;

    /**
     * The "list changed" trigger set (AC2) — every list-scoped mutation a member should be woken
     * for. Deliberately excludes {@code TripStartedForList}/{@code TripCompletedForList} (also
     * raised on the {@code list-} stream): those map to the {@code trip started}/{@code trip
     * completed} trigger instead (via the {@code trip-} stream's {@code TripStarted}/{@code
     * TripCompleted}), so the same real-world action never fires two pings. Also excludes {@code
     * ItemSuggestion}-related and read-only events (there are none on this stream) — an explicit
     * allow-list, not "any event on a list- stream", precisely to keep that exclusion correct.
     */
    private static final Set<String> LIST_CHANGED_EVENT_TYPES = Set.of(
            DomainEventJsonCodec.SHOPPING_LIST_CREATED_TYPE,
            DomainEventJsonCodec.SHOPPING_LIST_RENAMED_TYPE,
            DomainEventJsonCodec.ITEM_ADDED_TYPE,
            DomainEventJsonCodec.ITEM_UPDATED_TYPE,
            DomainEventJsonCodec.ITEM_REMOVED_TYPE,
            DomainEventJsonCodec.ITEM_TRANSFER_INITIATED_TYPE,
            DomainEventJsonCodec.ITEM_TRANSFER_CONFIRMED_TYPE,
            DomainEventJsonCodec.ITEM_TRANSFER_CANCELLED_TYPE,
            DomainEventJsonCodec.ITEM_ASSIGNED_TO_STORE_TYPE,
            DomainEventJsonCodec.ITEM_REROUTED_TYPE,
            DomainEventJsonCodec.ITEM_CHECKED_OFF_TYPE,
            DomainEventJsonCodec.ITEM_UNCHECKED_TYPE,
            DomainEventJsonCodec.ITEM_DISCARDED_TYPE);

    private final KurrentDBClient client;
    private final ContentFreePushSender pushSender;
    private final ResolveHouseholdPushTargets resolveHouseholdPushTargets;
    private final PruneDeviceToken pruneDeviceToken;
    private final HouseholdResolver resolver;
    private final Clock clock;
    private final Duration debounceWindow;
    private final boolean autoStart;

    private final ConcurrentMap<UUID, Instant> lastSentAtByListId = new ConcurrentHashMap<>();

    private volatile boolean running;
    private ScheduledExecutorService resubscribeScheduler;
    private volatile CompletableFuture<Subscription> currentSubscription;
    private final AtomicInteger resubscribeScheduleCount = new AtomicInteger();

    public HouseholdNotificationFanout(
            KurrentDBClient client,
            ContentFreePushSender pushSender,
            ResolveHouseholdPushTargets resolveHouseholdPushTargets,
            PruneDeviceToken pruneDeviceToken,
            HouseholdResolver resolver,
            Clock clock,
            Duration debounceWindow) {
        this(client, pushSender, resolveHouseholdPushTargets, pruneDeviceToken, resolver, clock, debounceWindow, false);
    }

    public HouseholdNotificationFanout(
            KurrentDBClient client,
            ContentFreePushSender pushSender,
            ResolveHouseholdPushTargets resolveHouseholdPushTargets,
            PruneDeviceToken pruneDeviceToken,
            HouseholdResolver resolver,
            Clock clock,
            Duration debounceWindow,
            boolean autoStart) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.pushSender = Objects.requireNonNull(pushSender, "pushSender must not be null");
        this.resolveHouseholdPushTargets =
                Objects.requireNonNull(resolveHouseholdPushTargets, "resolveHouseholdPushTargets must not be null");
        this.pruneDeviceToken = Objects.requireNonNull(pruneDeviceToken, "pruneDeviceToken must not be null");
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.debounceWindow = Objects.requireNonNull(debounceWindow, "debounceWindow must not be null");
        this.autoStart = autoStart;
    }

    /**
     * Reacts to one event: routes it to a trigger (AC2), debounces list-changed (AC2), and — when
     * not debounced away — resolves recipients and pushes (AC4). Takes the raw stream key/event
     * type/body, never deserializing it (mirrors {@link HouseholdLiveSyncFanout#react}, T4/T5's
     * "never read the event body" rationale, doubly important here since AC1 forbids it).
     */
    void react(String streamId, String eventType, byte[] eventData) {
        if (streamId.startsWith(LIST_PREFIX) && LIST_CHANGED_EVENT_TYPES.contains(eventType)) {
            // Resolve the household BEFORE consuming the debounce window: a projector-race
            // unresolved list (no household yet) must not mark the window, or the next genuinely
            // deliverable list-changed event would be suppressed for the whole window and the push
            // silently dropped. Only once we know there is a real household do we debounce-and-send.
            Optional<HouseholdId> householdId = resolver.resolveHouseholdId(streamId);
            if (householdId.isEmpty()) {
                return;
            }
            UUID listId = UUID.fromString(streamId.substring(LIST_PREFIX.length()));
            if (isDebounced(listId)) {
                return;
            }
            deliver(householdId.get(), streamId, eventType);
            return;
        }
        if (streamId.startsWith(TRIP_PREFIX)
                && (DomainEventJsonCodec.TRIP_STARTED_TYPE.equals(eventType)
                        || DomainEventJsonCodec.TRIP_COMPLETED_TYPE.equals(eventType))) {
            resolver.resolveHouseholdId(streamId).ifPresent(id -> deliver(id, streamId, eventType));
            return;
        }
        // Every other event (household-/member-scoped, or a non-triggering list/trip event) is
        // outside the fixed MVP set (AC2, D1) — intentionally ignored, not an error.
    }

    /**
     * @return {@code true} when the list already pinged within {@link #debounceWindow} (AC2) — a
     *     single atomic {@code compute} so concurrent events for the same list never both pass.
     */
    private boolean isDebounced(UUID listId) {
        Instant now = clock.instant();
        Instant[] previousHolder = new Instant[1];
        lastSentAtByListId.compute(listId, (ignoredKey, previous) -> {
            if (previous != null && now.isBefore(previous.plus(debounceWindow))) {
                previousHolder[0] = previous;
                return previous;
            }
            return now;
        });
        evictStaleDebounceEntries(now);
        return previousHolder[0] != null;
    }

    /**
     * Keeps {@link #lastSentAtByListId} from growing unbounded over the node's lifetime: an entry
     * older than {@link #debounceWindow} is dead (the next event for that list would just overwrite
     * it with {@code now}), so it can be dropped. Swept only once the map crosses {@link
     * #DEBOUNCE_MAP_EVICTION_THRESHOLD} so the common path stays O(1); {@code ConcurrentHashMap}
     * makes the {@code removeIf} safe against concurrent {@code compute}s.
     */
    private void evictStaleDebounceEntries(Instant now) {
        if (lastSentAtByListId.size() <= DEBOUNCE_MAP_EVICTION_THRESHOLD) {
            return;
        }
        lastSentAtByListId.values().removeIf(lastSentAt -> now.isAfter(lastSentAt.plus(debounceWindow)));
    }

    private void deliver(HouseholdId householdId, String streamId, String eventType) {
        ChangeNudge nudge = new ChangeNudge(householdId, resolver.resourceFor(streamId, eventType));

        for (PushTarget target : resolveHouseholdPushTargets.forHousehold(householdId)) {
            PushDeliveryResult result = pushSender.send(target, nudge);
            if (result == PushDeliveryResult.TOKEN_INVALID) {
                pruneDeviceToken.prune(target.token());
            }
        }
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        resubscribeScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "household-notification-fanout-resubscribe");
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

    /** Test seam (CLAUDE.md §6), mirrors {@link HouseholdLiveSyncFanout#resubscribeScheduleCount()}. */
    int resubscribeScheduleCount() {
        return resubscribeScheduleCount.get();
    }

    private void subscribe() {
        // Same single-regex-filter constraint as HouseholdLiveSyncFanout (T12) — see that class's
        // subscribe() javadoc for the full rationale against chained addStreamNamePrefix calls.
        // Filters ONLY the two prefixes this fan-out actually reacts to (list-, trip-): unlike the
        // live-sync fan-out it has no household-/member-scoped trigger (invitation push deferred,
        // D1), so subscribing to household- events only to drop them is dead scope (KISS/YAGNI).
        SubscriptionFilter filter = SubscriptionFilter.newBuilder()
                .withStreamNameRegularExpression("^(%s|%s)-.*".formatted(
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
                            log.error("Failed to fan out notification event {}", recordedEvent.getEventType(), failure);
                        }
                    }

                    @Override
                    public void onCancelled(Subscription subscription, Throwable throwable) {
                        if (throwable != null) {
                            log.warn("Notification fan-out subscription dropped; resubscribing", throwable);
                        }
                        scheduleResubscribe();
                    }
                },
                SubscribeToAllOptions.get().fromEnd().filter(filter));
        currentSubscription = subscription;
        subscription.exceptionally(failure -> {
            log.warn("Notification fan-out failed to subscribe; resubscribing", failure);
            scheduleResubscribe();
            return null;
        });
    }

    private synchronized void scheduleResubscribe() {
        if (running && resubscribeScheduler != null) {
            resubscribeScheduleCount.incrementAndGet();
            resubscribeScheduler.schedule(this::subscribe, RESUBSCRIBE_DELAY.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
