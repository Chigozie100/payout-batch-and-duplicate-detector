package com.treasuryone.duplicate;

import static com.treasuryone.duplicate.TestAttempts.at;
import static com.treasuryone.duplicate.TestAttempts.transfer;
import static com.treasuryone.duplicate.TestAttempts.withId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The two window lengths and the "most recent attempt is the reference" rule. */
class DuplicateWindowsTest {

    private final DuplicateTransferDetector detector = new InMemoryDuplicateTransferDetector();

    @Test
    void aFirstAttemptIsNeverADuplicate() {
        Verdict v = detector.observe(withId("A1", "45000.00"), at(0));
        assertFalse(v.possibleDuplicate());
        assertNull(v.previousAttemptId());
        assertEquals(Duration.ZERO, v.since());
    }

    @Test
    void belowOneHundredThousandTheWindowIsTwoMinutes() {
        detector.observe(withId("A1", "99999.99"), at(0));

        Verdict inside = detector.observe(withId("A2", "99999.99"), at(119));
        assertTrue(inside.possibleDuplicate());
        assertEquals("A1", inside.previousAttemptId());
        assertEquals(Duration.ofSeconds(119), inside.since());
    }

    @Test
    void belowOneHundredThousandAnAttemptAfterTwoMinutesIsNotADuplicate() {
        detector.observe(withId("A1", "99999.99"), at(0));
        Verdict after = detector.observe(withId("A2", "99999.99"), at(121));
        assertFalse(after.possibleDuplicate());
        assertNull(after.previousAttemptId());
    }

    @Test
    void theWindowIncludesItsLastSecond() {
        detector.observe(withId("A1", "5000"), at(0));
        assertTrue(detector.observe(withId("A2", "5000"), at(120)).possibleDuplicate());
    }

    @Test
    void fromOneHundredThousandTheWindowIsTenMinutes() {
        detector.observe(withId("A1", "100000.00"), at(0));

        Verdict inside = detector.observe(withId("A2", "100000"), at(599));
        assertTrue(inside.possibleDuplicate());
        assertEquals("A1", inside.previousAttemptId());
        assertEquals(Duration.ofSeconds(599), inside.since());
    }

    @Test
    void fromOneHundredThousandAnAttemptAfterTenMinutesIsNotADuplicate() {
        detector.observe(withId("A1", "100000.00"), at(0));
        assertFalse(detector.observe(withId("A2", "100000.00"), at(601)).possibleDuplicate());
    }

    @Test
    void theLongWindowIncludesItsLastSecond() {
        detector.observe(withId("A1", "250000"), at(0));
        assertTrue(detector.observe(withId("A2", "250000"), at(600)).possibleDuplicate());
    }

    @Test
    void theWindowIsChosenByTheAmountNotByTheEarlierAttempt() {
        // ₦99,999.99 is short: three minutes later it is no longer a duplicate, although ₦100,000 still would be.
        detector.observe(withId("A1", "99999.99"), at(0));
        assertFalse(detector.observe(withId("A2", "99999.99"), at(180)).possibleDuplicate());

        detector.observe(withId("B1", "100000.00"), at(0));
        assertTrue(detector.observe(withId("B2", "100000.00"), at(180)).possibleDuplicate());
    }

    @Test
    void theMostRecentMatchIsTheReference() {
        detector.observe(withId("A1", "45000"), at(0));

        Verdict second = detector.observe(withId("A2", "45000"), at(60));
        assertEquals("A1", second.previousAttemptId());

        // 150s after A1 is outside A1's window, but only 90s after A2: the third is compared with the second.
        Verdict third = detector.observe(withId("A3", "45000"), at(150));
        assertTrue(third.possibleDuplicate());
        assertEquals("A2", third.previousAttemptId());
        assertEquals(Duration.ofSeconds(90), third.since());
    }

    @Test
    void aNonDuplicateAfterTheWindowBecomesTheNewReference() {
        detector.observe(withId("A1", "45000"), at(0));
        assertFalse(detector.observe(withId("A2", "45000"), at(200)).possibleDuplicate());

        Verdict third = detector.observe(withId("A3", "45000"), at(250));
        assertEquals("A2", third.previousAttemptId());
    }

    @Test
    void anyDifferenceInTheFingerprintIsNotADuplicate() {
        detector.observe(transfer("0123456789", "000013", "2093456712", "45000"), at(0));

        assertFalse(detector.observe(transfer("0123456780", "000013", "2093456712", "45000"), at(1)).possibleDuplicate());
        assertFalse(detector.observe(transfer("0123456789", "000014", "2093456712", "45000"), at(1)).possibleDuplicate());
        assertFalse(detector.observe(transfer("0123456789", "000013", "2093456713", "45000"), at(1)).possibleDuplicate());
        assertFalse(detector.observe(transfer("0123456789", "000013", "2093456712", "45000.01"), at(1)).possibleDuplicate());
    }

    @Test
    void anAttemptWithAnEarlierTimeStillCountsAndNeverShortensTheReference() {
        detector.observe(withId("A1", "45000"), at(100));

        Verdict early = detector.observe(withId("A2", "45000"), at(99));   // another thread's clock was a second behind
        assertTrue(early.possibleDuplicate());
        assertEquals(Duration.ZERO, early.since());

        // the reference stayed at t=100, so t=220 (120s later) is still inside the window
        assertTrue(detector.observe(withId("A3", "45000"), at(220)).possibleDuplicate());
    }

    @Test
    void theThresholdAndWindowsCanBeChanged() {
        DuplicateTransferDetector custom = new InMemoryDuplicateTransferDetector(
                Duration.ofSeconds(10), Duration.ofSeconds(30), new java.math.BigDecimal("1000"));

        custom.observe(withId("A1", "999"), at(0));
        assertFalse(custom.observe(withId("A2", "999"), at(11)).possibleDuplicate());

        custom.observe(withId("B1", "1000"), at(0));
        assertTrue(custom.observe(withId("B2", "1000"), at(29)).possibleDuplicate());
    }
}
