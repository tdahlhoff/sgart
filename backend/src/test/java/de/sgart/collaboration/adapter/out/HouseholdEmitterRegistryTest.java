package de.sgart.collaboration.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.sgart.collaboration.application.LiveConnection;
import de.sgart.collaboration.application.exception.LiveConnectionClosedException;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test — pure in-memory, no framework/transport (CLAUDE.md §6). Proves the live-sync
 * registry's contract (Story 4.4, T3): per-household/per-member fan-out, multi-device support (a
 * {@code Set} per member), dead-connection tolerance during broadcast, and the eviction operations
 * AC3 depends on.
 *
 * <p>{@code broadcast} dispatches each send asynchronously (Review P7 follow-up — the fan-out thread
 * never waits on a client), so delivery assertions poll via {@link #awaitResources} rather than read
 * the recorder synchronously; the recorder is thread-safe for the same reason. Follows this project's
 * polling-loop convention (no Awaitility dependency, mirroring {@code HouseholdLiveSyncFanoutIntegrationTest}).
 */
class HouseholdEmitterRegistryTest {

    private final HouseholdEmitterRegistry registry = new HouseholdEmitterRegistry();
    private final HouseholdId householdId = HouseholdId.generate();
    private final HouseholdId otherHouseholdId = HouseholdId.generate();
    private final MemberId memberId = MemberId.generate();
    private final MemberId otherMemberId = MemberId.generate();

    @Test
    void broadcast_notifiesOnlyConnectionsRegisteredForThatHousehold() {
        RecordingConnection inHousehold = new RecordingConnection();
        RecordingConnection inOtherHousehold = new RecordingConnection();
        registry.register(householdId, memberId, inHousehold);
        registry.register(otherHouseholdId, otherMemberId, inOtherHousehold);

        registry.broadcast(householdId, "list");

        awaitResources(inHousehold, "list");
        assertThat(inOtherHousehold.receivedResources).isEmpty();
    }

    @Test
    void broadcast_notifiesEveryDeviceOfTheSameMember() {
        RecordingConnection deviceOne = new RecordingConnection();
        RecordingConnection deviceTwo = new RecordingConnection();
        registry.register(householdId, memberId, deviceOne);
        registry.register(householdId, memberId, deviceTwo);

        registry.broadcast(householdId, "household");

        awaitResources(deviceOne, "household");
        awaitResources(deviceTwo, "household");
    }

    @Test
    void broadcast_toleratesADeadConnectionAndStillNotifiesTheOthers() {
        RecordingConnection dead = new RecordingConnection();
        dead.failOnSend = true;
        RecordingConnection alive = new RecordingConnection();
        registry.register(householdId, memberId, dead);
        registry.register(householdId, otherMemberId, alive);

        registry.broadcast(householdId, "trip");
        awaitResources(alive, "trip");

        // The dead send throws and deregisters that connection (asynchronously) — a live connection
        // sharing the household is unaffected and keeps receiving on a second broadcast.
        registry.broadcast(householdId, "trip");
        awaitResources(alive, "trip", "trip");
    }

    @Test
    void broadcast_dropsAConnectionWhoseSendExceedsTheTimeoutWithoutBlockingTheOthers() throws Exception {
        ExecutorService sendExecutor = Executors.newCachedThreadPool();
        try {
            HouseholdEmitterRegistry timeoutBoundedRegistry =
                    new HouseholdEmitterRegistry(sendExecutor, Duration.ofMillis(50));
            CountDownLatch releaseWedgedSend = new CountDownLatch(1);
            WedgedConnection wedged = new WedgedConnection(releaseWedgedSend);
            RecordingConnection alive = new RecordingConnection();
            timeoutBoundedRegistry.register(householdId, memberId, wedged);
            timeoutBoundedRegistry.register(householdId, otherMemberId, alive);

            long startNanos = System.nanoTime();
            timeoutBoundedRegistry.broadcast(householdId, "list");
            long elapsed = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

            // broadcast returns without waiting on the wedged send at all (fire-and-don't-await,
            // Review P7 follow-up) — never blocked on a client that never completes — and the other
            // connection still receives its nudge ("one wedged client can't stall the fan-out").
            assertThat(elapsed).isLessThan(5000);
            awaitResources(alive, "list");

            releaseWedgedSend.countDown();
        } finally {
            sendExecutor.shutdownNow();
        }
    }

    @Test
    void evictMember_closesAndRemovesOnlyThatMembersConnections() {
        RecordingConnection evicted = new RecordingConnection();
        RecordingConnection remaining = new RecordingConnection();
        registry.register(householdId, memberId, evicted);
        registry.register(householdId, otherMemberId, remaining);

        registry.evictMember(householdId, memberId);

        assertThat(evicted.closed).isTrue();
        registry.broadcast(householdId, "members");
        awaitResources(remaining, "members");
        assertThat(evicted.receivedResources).isEmpty();
    }

    @Test
    void evictHousehold_closesAndRemovesEveryConnectionForThatHousehold() {
        RecordingConnection memberOne = new RecordingConnection();
        RecordingConnection memberTwo = new RecordingConnection();
        registry.register(householdId, memberId, memberOne);
        registry.register(householdId, otherMemberId, memberTwo);

        registry.evictHousehold(householdId);

        assertThat(memberOne.closed).isTrue();
        assertThat(memberTwo.closed).isTrue();
        registry.broadcast(householdId, "household");
        assertThat(memberOne.receivedResources).isEmpty();
        assertThat(memberTwo.receivedResources).isEmpty();
    }

    @Test
    void deregister_removesOnlyTheGivenConnectionNotOtherDevicesOfTheSameMember() {
        RecordingConnection deviceOne = new RecordingConnection();
        RecordingConnection deviceTwo = new RecordingConnection();
        registry.register(householdId, memberId, deviceOne);
        registry.register(householdId, memberId, deviceTwo);

        registry.deregister(householdId, memberId, deviceOne);
        registry.broadcast(householdId, "list");

        awaitResources(deviceTwo, "list");
        assertThat(deviceOne.receivedResources).isEmpty();
    }

    /**
     * Polls until {@code connection} has received exactly {@code expected} (in order), then asserts
     * it — {@code broadcast} now delivers asynchronously, so a real regression still fails the final
     * assertion once the 2s deadline lapses. Project convention: a bounded poll loop, no Awaitility.
     */
    private static void awaitResources(RecordingConnection connection, String... expected) {
        List<String> want = List.of(expected);
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!List.copyOf(connection.receivedResources).equals(want) && System.nanoTime() < deadlineNanos) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(connection.receivedResources).containsExactly(expected);
    }

    private static final class RecordingConnection implements LiveConnection {
        final Queue<String> receivedResources = new ConcurrentLinkedQueue<>();
        volatile boolean failOnSend;
        volatile boolean closed;

        @Override
        public void sendChangedEvent(String resource) {
            if (failOnSend) {
                throw new LiveConnectionClosedException("dead", null);
            }
            receivedResources.add(resource);
        }

        @Override
        public void sendHeartbeat() {
            if (failOnSend) {
                throw new LiveConnectionClosedException("dead", null);
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** A connection whose send blocks until released — simulates a wedged client socket (P7). */
    private static final class WedgedConnection implements LiveConnection {
        private final CountDownLatch releaseSend;

        WedgedConnection(CountDownLatch releaseSend) {
            this.releaseSend = releaseSend;
        }

        @Override
        public void sendChangedEvent(String resource) {
            await();
        }

        @Override
        public void sendHeartbeat() {
            await();
        }

        @Override
        public void close() {
            // Not exercised by this test.
        }

        private void await() {
            try {
                releaseSend.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
