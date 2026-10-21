package com.treasuryone.payout.partner;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * The provided stub: a fake partner switch (the assessment's "Provided stub", not one of the 5 components).
 *
 * <ul>
 *   <li>100-600 ms latency</li>
 *   <li>8% of calls time out; in half of those the payment was actually made</li>
 *   <li>5% return BENEFICIARY_BANK_UNAVAILABLE</li>
 *   <li>3% return a non-retryable failure</li>
 *   <li>more than 50 requests per second or 10 in flight throw {@link PartnerThrottledException}</li>
 *   <li>de-duplicates on the payment reference: a repeated reference returns the original result</li>
 * </ul>
 *
 * It also keeps counters (calls per reference, concurrent calls with the same reference) that the tests use
 * to prove that no line was sent by two threads at once.
 */
@Component
public class FakePartnerPayoutClient implements PartnerPayoutClient {

    private static final String[] NON_RETRYABLE = {"INVALID_ACCOUNT", "DORMANT_ACCOUNT", "ACCOUNT_BLOCKED"};

    private final int minLatencyMs;
    private final int maxLatencyMs;
    private final double timeoutRate;
    private final double unavailableRate;
    private final double hardFailRate;
    private final int maxRequestsPerSecond;
    private final int maxInFlight;

    private final Map<String, PartnerResult> results = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> callsPerRef = new ConcurrentHashMap<>();
    private final Set<String> activeRefs = ConcurrentHashMap.newKeySet();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxObservedInFlight = new AtomicInteger();
    private final AtomicInteger sameRefOverlaps = new AtomicInteger();
    private final AtomicLong totalCalls = new AtomicLong();
    private final ArrayDeque<Long> recentCalls = new ArrayDeque<>();

    public FakePartnerPayoutClient() {
        this(100, 600, 0.08, 0.05, 0.03, 50, 10);
    }

    public FakePartnerPayoutClient(int minLatencyMs, int maxLatencyMs, double timeoutRate, double unavailableRate,
                                   double hardFailRate, int maxRequestsPerSecond, int maxInFlight) {
        this.minLatencyMs = minLatencyMs;
        this.maxLatencyMs = maxLatencyMs;
        this.timeoutRate = timeoutRate;
        this.unavailableRate = unavailableRate;
        this.hardFailRate = hardFailRate;
        this.maxRequestsPerSecond = maxRequestsPerSecond;
        this.maxInFlight = maxInFlight;
    }

    @Override
    public PartnerResult pay(String paymentRef, String accountNumber, String bankCode,
                             String beneficiaryName, BigDecimal amount) {
        int now = inFlight.incrementAndGet();
        if (now > maxInFlight) {
            inFlight.decrementAndGet();
            throw new PartnerThrottledException("more than " + maxInFlight + " requests in flight");
        }
        if (!admitWithinRate()) {
            inFlight.decrementAndGet();
            throw new PartnerThrottledException("more than " + maxRequestsPerSecond + " requests per second");
        }
        maxObservedInFlight.accumulateAndGet(now, Math::max);
        totalCalls.incrementAndGet();
        callsPerRef.computeIfAbsent(paymentRef, k -> new AtomicInteger()).incrementAndGet();
        if (!activeRefs.add(paymentRef)) {
            sameRefOverlaps.incrementAndGet();
        }
        try {
            sleepLatency();
            PartnerResult original = results.get(paymentRef);
            if (original != null) {
                return original;
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double roll = random.nextDouble();
            if (roll < timeoutRate) {
                if (random.nextBoolean()) {
                    results.putIfAbsent(paymentRef, PartnerResult.success(sessionId(random)));
                }
                throw new PartnerTimeoutException("switch did not answer in time");
            }
            if (roll < timeoutRate + unavailableRate) {
                return PartnerResult.failed(PartnerFailureCodes.BENEFICIARY_BANK_UNAVAILABLE);
            }
            PartnerResult result = roll < timeoutRate + unavailableRate + hardFailRate
                    ? PartnerResult.failed(NON_RETRYABLE[random.nextInt(NON_RETRYABLE.length)])
                    : PartnerResult.success(sessionId(random));
            PartnerResult previous = results.putIfAbsent(paymentRef, result);
            return previous != null ? previous : result;
        } finally {
            activeRefs.remove(paymentRef);
            inFlight.decrementAndGet();
        }
    }

    /** Exact sliding one-second window, the way the real switch is described. */
    private boolean admitWithinRate() {
        long now = System.nanoTime();
        synchronized (recentCalls) {
            while (!recentCalls.isEmpty() && now - recentCalls.peekFirst() >= TimeUnit.SECONDS.toNanos(1)) {
                recentCalls.pollFirst();
            }
            if (recentCalls.size() >= maxRequestsPerSecond) {
                return false;
            }
            recentCalls.addLast(now);
            return true;
        }
    }

    private void sleepLatency() {
        if (maxLatencyMs <= 0) {
            return;
        }
        int latency = minLatencyMs >= maxLatencyMs
                ? minLatencyMs
                : ThreadLocalRandom.current().nextInt(minLatencyMs, maxLatencyMs + 1);
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PartnerTimeoutException("interrupted while waiting for the switch");
        }
    }

    /** A 30-digit NIP session id: 6-digit institution code + yyMMddHHmmss + 12 random digits. */
    private static String sessionId(ThreadLocalRandom random) {
        String time = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMddHHmmss"));
        return String.format("999999%s%012d", time, random.nextLong(1_000_000_000_000L));
    }

    // ---- observation helpers used by the tests ----

    public int callCount(String paymentRef) {
        AtomicInteger n = callsPerRef.get(paymentRef);
        return n == null ? 0 : n.get();
    }

    public long totalCalls() {
        return totalCalls.get();
    }

    /** Number of times two calls with the same payment reference were in flight together. Must stay 0. */
    public int sameRefOverlaps() {
        return sameRefOverlaps.get();
    }

    public int maxObservedInFlight() {
        return maxObservedInFlight.get();
    }

    /** The result the switch holds for this reference (null if it never completed a payment decision). */
    public PartnerResult storedResult(String paymentRef) {
        return results.get(paymentRef);
    }
}
