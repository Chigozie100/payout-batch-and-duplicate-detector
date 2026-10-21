package com.treasuryone.payout.partner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PartnerGatewayTest {

    private static final String SESSION = "999999260101120000123456789012";

    private enum Step { SUCCESS, UNAVAILABLE, TIMEOUT, BLOCKED, THROTTLED }

    /** A switch that plays back a fixed list of answers, then answers SUCCESS. */
    private static final class Scripted implements PartnerPayoutClient {
        private final Deque<Step> steps = new ArrayDeque<>();
        final AtomicInteger calls = new AtomicInteger();

        Scripted(Step... script) {
            steps.addAll(List.of(script));
        }

        @Override
        public synchronized PartnerResult pay(String ref, String acct, String bank, String name, BigDecimal amount) {
            calls.incrementAndGet();
            Step step = steps.isEmpty() ? Step.SUCCESS : steps.poll();
            return switch (step) {
                case SUCCESS -> PartnerResult.success(SESSION);
                case UNAVAILABLE -> PartnerResult.failed("BENEFICIARY_BANK_UNAVAILABLE");
                case BLOCKED -> PartnerResult.failed("ACCOUNT_BLOCKED");
                case TIMEOUT -> throw new PartnerTimeoutException("timeout");
                case THROTTLED -> throw new PartnerThrottledException("throttled");
            };
        }
    }

    private static PartnerGateway fast(PartnerPayoutClient client) {
        return new PartnerGateway(client, 1_000_000, 10, 4, 1, 4);
    }

    private static PartnerOutcome send(PartnerGateway gateway) throws InterruptedException {
        return gateway.pay("TR000000001000100", "0123456789", "000013", "Test Person", new BigDecimal("100.00"));
    }

    @Test
    void successIsPaidWithTheSessionId() throws Exception {
        Scripted client = new Scripted(Step.SUCCESS);
        PartnerOutcome outcome = send(fast(client));
        assertEquals(PartnerOutcome.Kind.PAID, outcome.getKind());
        assertEquals(SESSION, outcome.getSessionId());
        assertEquals(1, outcome.getAttempts());
    }

    @Test
    void nonRetryableFailureIsNotRetried() throws Exception {
        Scripted client = new Scripted(Step.BLOCKED);
        PartnerOutcome outcome = send(fast(client));
        assertEquals(PartnerOutcome.Kind.FAILED, outcome.getKind());
        assertEquals("ACCOUNT_BLOCKED", outcome.getFailureCode());
        assertEquals(1, client.calls.get());
    }

    @Test
    void timeoutThenSuccessIsPaidOnTheSecondAttempt() throws Exception {
        Scripted client = new Scripted(Step.TIMEOUT, Step.SUCCESS);
        PartnerOutcome outcome = send(fast(client));
        assertEquals(PartnerOutcome.Kind.PAID, outcome.getKind());
        assertEquals(2, outcome.getAttempts());
    }

    @Test
    void bankUnavailableOnEveryAttemptEndsAsFailedAfterFourAttempts() throws Exception {
        Scripted client = new Scripted(Step.UNAVAILABLE, Step.UNAVAILABLE, Step.UNAVAILABLE, Step.UNAVAILABLE, Step.SUCCESS);
        PartnerOutcome outcome = send(fast(client));
        assertEquals(PartnerOutcome.Kind.FAILED, outcome.getKind());
        assertEquals("BENEFICIARY_BANK_UNAVAILABLE", outcome.getFailureCode());
        assertEquals(4, client.calls.get(), "at most 4 attempts in total");
    }

    @Test
    void aTimeoutOnTheLastAttemptIsAnUnknownOutcome() throws Exception {
        Scripted client = new Scripted(Step.UNAVAILABLE, Step.UNAVAILABLE, Step.UNAVAILABLE, Step.TIMEOUT, Step.SUCCESS);
        PartnerOutcome outcome = send(fast(client));
        assertEquals(PartnerOutcome.Kind.UNCERTAIN, outcome.getKind());
        assertEquals(4, client.calls.get());
    }

    @Test
    void bankUnavailableOnTheLastAttemptAfterTimeoutsIsFailed() throws Exception {
        Scripted client = new Scripted(Step.TIMEOUT, Step.TIMEOUT, Step.TIMEOUT, Step.UNAVAILABLE);
        PartnerOutcome outcome = send(fast(client));
        assertEquals(PartnerOutcome.Kind.FAILED, outcome.getKind());
        assertEquals("BENEFICIARY_BANK_UNAVAILABLE", outcome.getFailureCode());
    }

    @Test
    void aThrottledCallNeverReachedTheSwitchSoItIsNotAnAttempt() throws Exception {
        Scripted client = new Scripted(Step.THROTTLED, Step.THROTTLED, Step.SUCCESS);
        PartnerOutcome outcome = send(fast(client));
        assertEquals(PartnerOutcome.Kind.PAID, outcome.getKind());
        assertEquals(1, outcome.getAttempts(), "throttled calls do not use up attempts");
        assertEquals(3, client.calls.get());
    }

    @Test
    void aSuccessWithoutAThirtyDigitSessionIdIsNotTrusted() throws Exception {
        PartnerPayoutClient broken = (ref, acct, bank, name, amount) -> PartnerResult.success("123");
        PartnerOutcome outcome = send(fast(broken));
        assertEquals(PartnerOutcome.Kind.UNCERTAIN, outcome.getKind());
    }

    /**
     * The two partner limits, across callers: 120 sends from 40 threads (standing in for many batches running at once)
     * through ONE gateway. The switch side records when every call started and how many overlapped.
     */
    @Test
    void neverMoreThanFiftyPerSecondAndTenInFlight() throws Exception {
        ConcurrentLinkedQueue<Long> starts = new ConcurrentLinkedQueue<>();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();

        PartnerPayoutClient client = (ref, acct, bank, name, amount) -> {
            starts.add(System.nanoTime());
            int now = running.incrementAndGet();
            maxRunning.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
            }
            return PartnerResult.success(SESSION);
        };
        PartnerGateway gateway = new PartnerGateway(client, 50, 10, 4, 1, 5);

        ExecutorService callers = Executors.newFixedThreadPool(40);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<PartnerOutcome>> results = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            results.add(callers.submit(() -> {
                go.await();
                return send(gateway);
            }));
        }
        long begin = System.nanoTime();
        go.countDown();
        for (Future<PartnerOutcome> f : results) {
            assertEquals(PartnerOutcome.Kind.PAID, f.get(60, TimeUnit.SECONDS).getKind());
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        callers.shutdownNow();

        assertTrue(maxRunning.get() <= 10, "in flight peaked at " + maxRunning.get());

        List<Long> sorted = new ArrayList<>(starts);
        sorted.sort(Long::compare);
        assertEquals(120, sorted.size());
        for (int i = 0; i + 50 < sorted.size(); i++) {
            long gap = sorted.get(i + 50) - sorted.get(i);
            assertTrue(gap >= TimeUnit.SECONDS.toNanos(1),
                    "51 calls started within " + TimeUnit.NANOSECONDS.toMillis(gap) + " ms");
        }
        // 120 calls at 50 per second cannot finish faster than about 1.4 s
        assertTrue(elapsedMs >= 1_300, "finished too quickly to have been rate limited: " + elapsedMs + " ms");
    }
}
