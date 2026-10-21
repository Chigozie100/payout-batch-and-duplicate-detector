package com.treasuryone.payout.partner;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

/**
 * Sliding-window rate limiter. Each call records its own start time, and a new call may start only when fewer than
 * {@code maxPerSecond} calls started inside the last window. The time is recorded when the slot is granted, which the
 * caller does immediately before the call, so the limit holds however threads are scheduled.
 *
 * <p>The window is the real second plus a small margin, so scheduling jitter can never exceed the limit.
 */
final class SlidingWindowRateLimiter {

    private static final long WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(1_010);
    private static final long MIN_SLEEP_NANOS = 500_000L;

    private final int maxPerSecond;
    private final ArrayDeque<Long> window;
    private final Object lock = new Object();

    SlidingWindowRateLimiter(int maxPerSecond) {
        this.maxPerSecond = maxPerSecond;
        this.window = new ArrayDeque<>(maxPerSecond);
    }

    void acquire() throws InterruptedException {
        while (true) {
            long waitNanos;
            synchronized (lock) {
                long now = System.nanoTime();
                while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_NANOS) {
                    window.pollFirst();
                }
                if (window.size() < maxPerSecond) {
                    window.addLast(now);
                    return;
                }
                waitNanos = WINDOW_NANOS - (now - window.peekFirst());
            }
            TimeUnit.NANOSECONDS.sleep(Math.max(waitNanos, MIN_SLEEP_NANOS));
        }
    }
}
