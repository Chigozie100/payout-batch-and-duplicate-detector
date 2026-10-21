package com.treasuryone.duplicate;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class InputGuardTest {

    private final DuplicateTransferDetector detector = new InMemoryDuplicateTransferDetector();

    @Test
    void anAttemptAndATimeAreRequired() {
        assertThrows(NullPointerException.class, () -> detector.observe(null, TestAttempts.T0));
        assertThrows(NullPointerException.class, () -> detector.observe(TestAttempts.withId("A1", "100"), null));
    }

    @Test
    void aBadAttemptIsRejectedAndNothingIsRemembered() {
        InMemoryDuplicateTransferDetector d = new InMemoryDuplicateTransferDetector();
        TransferAttempt noAmount = new TransferAttempt("A1", "0123456789", "000013", "2093456712", null);

        assertThrows(IllegalArgumentException.class, () -> d.observe(noAmount, TestAttempts.T0));
        org.junit.jupiter.api.Assertions.assertEquals(0, d.size());
    }
}
