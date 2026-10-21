package com.treasuryone.payout.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** The one ExecutorService for line processing. It only runs work; it decides nothing about it. */
final class WorkerPool {

    private static final long SHUTDOWN_WAIT_SECONDS = 5;

    private final ExecutorService executor;

    WorkerPool(int threads) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "payout-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newFixedThreadPool(threads, factory);
    }

    void execute(Runnable task) {
        executor.execute(task);
    }

    /** Waits briefly for running lines, then interrupts them. Interrupted lines become UNCERTAIN and resume recovers them. */
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
