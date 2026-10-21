package com.treasuryone.payout.partner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BackoffPolicyTest {

    private final BackoffPolicy policy = new BackoffPolicy(200, 3000);

    @Test
    void ceilingDoublesWithEveryAttemptAndIsCapped() {
        assertEquals(200, policy.ceilingMillis(1));
        assertEquals(400, policy.ceilingMillis(2));
        assertEquals(800, policy.ceilingMillis(3));
        assertEquals(3000, policy.ceilingMillis(10));
        assertEquals(3000, policy.ceilingMillis(60), "a huge attempt count must not overflow");
    }

    @Test
    void waitIsBetweenHalfTheCeilingAndTheCeiling() {
        for (int i = 0; i < 200; i++) {
            long first = policy.waitMillis(1);
            long second = policy.waitMillis(2);
            long third = policy.waitMillis(3);
            long capped = policy.waitMillis(10);
            assertTrue(first >= 100 && first <= 200, "first " + first);
            assertTrue(second >= 200 && second <= 400, "second " + second);
            assertTrue(third >= 400 && third <= 800, "third " + third);
            assertTrue(capped >= 1500 && capped <= 3000, "capped " + capped);
        }
    }

    @Test
    void waitIsRandomNotAFixedValue() {
        long distinct = java.util.stream.LongStream.range(0, 200).map(i -> policy.waitMillis(3)).distinct().count();
        assertTrue(distinct > 20, "the wait must be random, not a fixed value");
    }

    @Test
    void aZeroBaseNeverWaits() {
        assertEquals(0, new BackoffPolicy(0, 0).waitMillis(3));
    }
}
