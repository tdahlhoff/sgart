package de.sgart.identity.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Fast unit test: the executor runs work off the caller's thread, drops overflow silently, and stops on close. */
class BoundedDaemonExecutorTest {

    @Test
    void execute_runsTheTaskOnADaemonThreadWithTheGivenName() throws Exception {
        try (BoundedDaemonExecutor executor = new BoundedDaemonExecutor("test-worker")) {
            CountDownLatch finished = new CountDownLatch(1);
            Thread[] workerThread = new Thread[1];

            executor.execute(() -> {
                workerThread[0] = Thread.currentThread();
                finished.countDown();
            });

            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(workerThread[0].getName()).isEqualTo("test-worker");
            assertThat(workerThread[0].isDaemon()).isTrue();
        }
    }

    @Test
    void execute_whenTheQueueIsFull_discardsTheOverflowWithoutThrowing() throws Exception {
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch workerBusy = new CountDownLatch(1);
        AtomicInteger executedTasks = new AtomicInteger();
        try (BoundedDaemonExecutor executor = new BoundedDaemonExecutor("test-worker")) {
            executor.execute(() -> {
                workerBusy.countDown();
                awaitUninterruptibly(releaseWorker);
            });
            assertThat(workerBusy.await(5, TimeUnit.SECONDS)).isTrue();

            int overflowingTasks = 10;
            for (int task = 0; task < BoundedDaemonExecutor.QUEUE_CAPACITY + overflowingTasks; task++) {
                executor.execute(executedTasks::incrementAndGet);
            }
            releaseWorker.countDown();
        }

        assertThat(executedTasks.get()).isEqualTo(BoundedDaemonExecutor.QUEUE_CAPACITY);
    }

    @Test
    void close_stopsTheExecutorAndLaterTasksAreDiscarded() {
        BoundedDaemonExecutor executor = new BoundedDaemonExecutor("test-worker");
        AtomicInteger executedTasks = new AtomicInteger();

        executor.close();
        executor.execute(executedTasks::incrementAndGet);

        assertThat(executor.isShutDown()).isTrue();
        assertThat(executedTasks.get()).isZero();
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
        }
    }
}
