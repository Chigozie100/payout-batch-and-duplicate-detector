package com.treasuryone.payout.partner;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SlidingWindowRateLimiterTest {

    @Test
    void grantsTheLimitImmediatelyThenMakesTheNextCallWait() throws Exception {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(5);
        long begin = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            limiter.acquire();
        }
        long firstFiveMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        assertTrue(firstFiveMs < 200, "the first 5 slots are free but took " + firstFiveMs + " ms");

        limiter.acquire();   // the 6th must wait for the first slot to leave the window
        long sixthMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        assertTrue(sixthMs >= 1_000, "the 6th slot came after only " + sixthMs + " ms");
    }
}
