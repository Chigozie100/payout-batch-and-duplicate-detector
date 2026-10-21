package com.treasuryone.duplicate;

import static com.treasuryone.duplicate.TestAttempts.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ConcurrencyTest {

    private static final int THREADS = 64;

    /** Runs the tasks so that they all start at the same moment. */
    private static <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void identicalAttemptsArrivingTogetherProduceExactlyOneNonDuplicate() throws Exception {
        for (int round = 0; round < 50; round++) {
            DuplicateTransferDetector detector = new InMemoryDuplicateTransferDetector();
            List<Callable<Verdict>> tasks = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                TransferAttempt attempt = TestAttempts.withId("A" + i, round % 2 == 0 ? "45000.00" : "450000.00");
                tasks.add(() -> detector.observe(attempt, T0));
            }

            List<Verdict> verdicts = runTogether(tasks);

            assertEquals(1, verdicts.stream().filter(v -> !v.possibleDuplicate()).count(), "round " + round);
            assertEquals(THREADS - 1, verdicts.stream().filter(Verdict::possibleDuplicate).count());
        }
    }

    @Test
    void theAttemptsFormOneChainEachPointingAtItsPredecessor() throws Exception {
        DuplicateTransferDetector detector = new InMemoryDuplicateTransferDetector();
        List<Callable<Verdict>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            TransferAttempt attempt = TestAttempts.withId("A" + i, "45000");
            tasks.add(() -> detector.observe(attempt, T0));
        }

        List<Verdict> verdicts = runTogether(tasks);

        // every attempt is the previous attempt of exactly one other attempt, except the last one in the chain
        Set<String> referenced = new HashSet<>();
        for (Verdict v : verdicts) {
            if (v.possibleDuplicate()) {
                assertTrue(referenced.add(v.previousAttemptId()), "two attempts share the same predecessor");
            }
        }
        assertEquals(THREADS - 1, referenced.size());
    }

    @Test
    void manyThreadsWithManyKeysAgreeWithASequentialRun() throws Exception {
        int keys = 200;
        int perKey = 20;
        InMemoryDuplicateTransferDetector detector = new InMemoryDuplicateTransferDetector();
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int t = 0; t < 16; t++) {
            int offset = t;
            tasks.add(() -> {
                int firsts = 0;
                for (int k = 0; k < keys; k++) {
                    for (int n = offset; n < perKey; n += 16) {
                        TransferAttempt attempt = new TransferAttempt("T" + offset + "-" + k + "-" + n, "0123456789",
                                "000013", String.format("%010d", k), new java.math.BigDecimal(k % 2 == 0 ? "5000" : "500000"));
                        if (!detector.observe(attempt, T0).possibleDuplicate()) {
                            firsts++;
                        }
                    }
                }
                return firsts;
            });
        }

        int totalFirsts = runTogether(tasks).stream().mapToInt(Integer::intValue).sum();

        assertEquals(keys, totalFirsts);          // exactly one non-duplicate per key
        assertEquals(keys, detector.size());      // and one remembered entry per key
    }

    @Test
    void throughputOfThreeThousandCallsPerSecondIsFarBelowWhatTheDetectorCanDo() throws Exception {
        InMemoryDuplicateTransferDetector detector = new InMemoryDuplicateTransferDetector();
        int callsPerThread = 25_000;
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            int thread = t;
            tasks.add(() -> {
                for (int i = 0; i < callsPerThread; i++) {
                    long id = (long) thread * callsPerThread + i;
                    detector.observe(new TransferAttempt("X" + id, "0123456789", "000013", String.format("%010d", id),
                            new java.math.BigDecimal("5000")), T0.plusMillis(i));
                }
                return callsPerThread;
            });
        }
        long start = System.nanoTime();
        int calls = runTogether(tasks).stream().mapToInt(Integer::intValue).sum();
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(200_000, calls);
        // 200,000 calls would take 67 seconds at the required 3,000 per second. No tight timing check: it would be flaky.
        assertTrue(millis < 30_000, "took " + millis + " ms");
    }
}
