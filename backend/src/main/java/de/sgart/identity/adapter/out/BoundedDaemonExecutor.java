package de.sgart.identity.adapter.out;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A single daemon worker thread behind a bounded queue. Work beyond the queue is dropped and logged
 * (never with its content, which may quote an address), so a burst of requests cannot exhaust
 * memory. Deliberately not registered as a bean: a user-defined {@link Executor} bean would switch
 * off Spring Boot's own task executor, so the owner closes it explicitly.
 */
final class BoundedDaemonExecutor implements Executor, AutoCloseable {

    static final int QUEUE_CAPACITY = 100;

    private static final Logger log = LoggerFactory.getLogger(BoundedDaemonExecutor.class);
    private static final long SHUTDOWN_GRACE_SECONDS = 5;

    private final ThreadPoolExecutor executor;

    BoundedDaemonExecutor(String threadName) {
        Objects.requireNonNull(threadName, "threadName must not be null");
        RejectedExecutionHandler logAndDiscard = (task, rejectingExecutor) ->
                log.warn("Dropped a task for {}: its queue is full or the executor is shut down", threadName);
        this.executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> startDaemonThread(threadName, runnable),
                logAndDiscard);
    }

    @Override
    public void execute(Runnable task) {
        executor.execute(task);
    }

    boolean isShutDown() {
        return executor.isShutdown();
    }

    /** Lets queued work finish for a short grace period, then interrupts whatever is left. */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interruption) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static Thread startDaemonThread(String threadName, Runnable runnable) {
        Thread thread = new Thread(runnable, threadName);
        thread.setDaemon(true);
        return thread;
    }
}
