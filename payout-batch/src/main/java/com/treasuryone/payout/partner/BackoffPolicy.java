package com.treasuryone.payout.partner;

import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;

/**
 * Exponential backoff with jitter: the ceiling doubles with every attempt (capped), and the wait is half the
 * ceiling plus a random share of the other half, so retries from many lines do not synchronise.
 */
@RequiredArgsConstructor
final class BackoffPolicy {

    private final long baseMs;
    private final long maxMs;

    long ceilingMillis(int attemptsSoFar) {
        return Math.min(maxMs, baseMs * (1L << Math.min(attemptsSoFar - 1, 20)));
    }

    long waitMillis(int attemptsSoFar) {
        long half = ceilingMillis(attemptsSoFar) / 2;
        return half + (half > 0 ? ThreadLocalRandom.current().nextLong(half + 1) : 0);
    }
}
