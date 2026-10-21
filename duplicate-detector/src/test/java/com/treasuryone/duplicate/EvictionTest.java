package com.treasuryone.duplicate;

import static com.treasuryone.duplicate.TestAttempts.at;
import static com.treasuryone.duplicate.TestAttempts.transfer;
import static com.treasuryone.duplicate.TestAttempts.withId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Expired entries are removed on the way, from the oldest end, and the two window lengths expire independently. */
class EvictionTest {

    private final InMemoryDuplicateTransferDetector detector = new InMemoryDuplicateTransferDetector();

    @Test
    void shortWindowEntriesAreEvictedOnceTheyExpire() {
        for (int i = 0; i < 100; i++) {
            detector.observe(transfer("0123456789", "000013", String.format("%010d", i), "5000"), at(0));
        }
        assertEquals(100, detector.size());

        // one unrelated attempt after the window: all 100 old entries go, only the new one stays
        detector.observe(transfer("0123456789", "000013", "9999999999", "5000"), at(121));
        assertEquals(1, detector.size());
    }

    @Test
    void anEntryIsKeptUntilTheLastSecondOfItsWindow() {
        detector.observe(transfer("0123456789", "000013", "2093456712", "5000"), at(0));
        detector.observe(transfer("0123456789", "000013", "1111111111", "5000"), at(120));
        assertEquals(2, detector.size());
        detector.observe(transfer("0123456789", "000013", "2222222222", "5000"), at(121));
        assertEquals(2, detector.size());   // the t=0 entry is gone, t=120 and t=121 remain
    }

    @Test
    void entriesWithDifferentLifetimesExpireIndependently() {
        detector.observe(withId("S1", "5000"), at(0));         // short: lives 120s
        detector.observe(withId("L1", "500000"), at(0));       // long: lives 600s
        assertEquals(2, detector.size());

        // at t=300 a short-lane attempt arrives; it evicts the expired short entry and keeps the live long one
        detector.observe(transfer("0123456789", "000013", "7777777777", "5000"), at(300));
        assertEquals(2, detector.size());   // L1 and the new short entry

        // the long entry is still a live duplicate reference
        assertTrue(detector.observe(withId("L2", "500000"), at(301)).possibleDuplicate());

        // after the long window, traffic in the SHORT lane alone still frees the long lane
        detector.observe(transfer("0123456789", "000013", "8888888888", "5000"), at(1000));
        assertEquals(1, detector.size());
    }

    @Test
    void repeatedAttemptsKeepOneEntryAndKeepItAlive() {
        for (int i = 0; i < 50; i++) {
            Verdict v = detector.observe(withId("A" + i, "5000"), at(i * 100L));   // each within 120s of the last
            assertEquals(i > 0, v.possibleDuplicate());
        }
        assertEquals(1, detector.size());
    }

    @Test
    void aRefreshedEntryMovesBehindOlderOnesSoTheOlderOnesCanExpire() {
        TransferAttempt a = transfer("0123456789", "000013", "1111111111", "5000");
        TransferAttempt b = transfer("0123456789", "000013", "2222222222", "5000");
        detector.observe(a, at(0));
        detector.observe(b, at(10));
        detector.observe(a, at(100));          // A is refreshed: it is now the newest, B is the oldest

        // t=135: B (seen at 10) has expired, A (seen at 100) is live. If A stayed at the head, B would be stuck behind it.
        detector.observe(transfer("0123456789", "000013", "3333333333", "5000"), at(135));
        assertEquals(2, detector.size());      // A and the new one; B is gone
    }

    @Test
    void anExpiredEntryIsNeverReportedEvenIfItWasNotEvictedYet() {
        // Out-of-order times can leave an expired entry behind a live one; the check on the entry itself still holds.
        detector.observe(withId("A1", "5000"), at(0));
        assertFalse(detector.observe(withId("A2", "5000"), at(500)).possibleDuplicate());
    }

    @Test
    void memoryFollowsTheLastTenMinutes() {
        // 3,000 distinct attempts per second for 20 minutes of simulated time (short and long amounts mixed)
        int perSecond = 30;                // scaled down 100x to keep the test quick; the bound is what matters
        int maxLive = 0;
        for (int second = 0; second < 20 * 60; second++) {
            for (int n = 0; n < perSecond; n++) {
                String beneficiary = String.format("%010d", (long) second * perSecond + n);
                String amount = n % 2 == 0 ? "5000" : "500000";
                detector.observe(transfer("0123456789", "000013", beneficiary, amount), at(second));
            }
            maxLive = Math.max(maxLive, detector.size());
        }
        // long entries live 600s, short ones 120s: at most 15 long + 15 short per second for those periods
        int bound = (600 + 1) * perSecond / 2 + (120 + 1) * perSecond / 2;
        assertTrue(maxLive <= bound, "live entries " + maxLive + " exceeded " + bound);
        assertTrue(detector.size() <= bound);
    }
}
