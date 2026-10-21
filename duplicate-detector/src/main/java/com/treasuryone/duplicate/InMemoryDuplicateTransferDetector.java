package com.treasuryone.duplicate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The detector. It only decides which lane an attempt belongs to; the lanes do the remembering.
 *
 * <ul>
 *   <li>Below the threshold (default ₦100,000): the short lane, 120 seconds.</li>
 *   <li>At or above the threshold: the long lane, 10 minutes.</li>
 * </ul>
 *
 * The amount is part of the fingerprint, so one fingerprint always belongs to the same lane.
 */
public final class InMemoryDuplicateTransferDetector implements DuplicateTransferDetector {

    public static final Duration SHORT_WINDOW = Duration.ofSeconds(120);
    public static final Duration LONG_WINDOW = Duration.ofMinutes(10);
    public static final BigDecimal LONG_WINDOW_THRESHOLD = new BigDecimal("100000");

    private final BigDecimal threshold;
    private final ExpiringAttemptLane shortLane;
    private final ExpiringAttemptLane longLane;

    public InMemoryDuplicateTransferDetector() {
        this(SHORT_WINDOW, LONG_WINDOW, LONG_WINDOW_THRESHOLD);
    }

    public InMemoryDuplicateTransferDetector(Duration shortWindow, Duration longWindow, BigDecimal threshold) {
        this.threshold = Objects.requireNonNull(threshold, "threshold");
        this.shortLane = new ExpiringAttemptLane(Objects.requireNonNull(shortWindow, "shortWindow"));
        this.longLane = new ExpiringAttemptLane(Objects.requireNonNull(longWindow, "longWindow"));
    }

    @Override
    public Verdict observe(TransferAttempt attempt, Instant now) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(now, "now");
        Fingerprint fingerprint = Fingerprint.of(attempt);

        boolean isLong = attempt.amount().compareTo(threshold) >= 0;
        ExpiringAttemptLane own = isLong ? longLane : shortLane;
        ExpiringAttemptLane other = isLong ? shortLane : longLane;

        Verdict verdict = own.observe(fingerprint, attempt.attemptId(), now);
        // Free the other lane's expired entries too, so memory drops even when all the traffic is in one lane.
        // The locks are taken one after the other, never together, so there is no lock-ordering problem.
        other.evictExpired(now);
        return verdict;
    }

    /** Number of fingerprints currently remembered (live plus any not yet evicted). For tests and monitoring. */
    public int size() {
        return shortLane.size() + longLane.size();
    }
}
