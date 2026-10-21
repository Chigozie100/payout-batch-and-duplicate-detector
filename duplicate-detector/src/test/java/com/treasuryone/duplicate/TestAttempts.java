package com.treasuryone.duplicate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/** Builders shared by the tests. */
final class TestAttempts {

    static final Instant T0 = Instant.parse("2026-10-14T09:00:00Z");
    private static final AtomicInteger IDS = new AtomicInteger();

    private TestAttempts() {
    }

    static Instant at(long seconds) {
        return T0.plusSeconds(seconds);
    }

    static TransferAttempt transfer(String amount) {
        return transfer("0123456789", "000013", "2093456712", amount);
    }

    static TransferAttempt transfer(String source, String bank, String beneficiary, String amount) {
        return new TransferAttempt("A" + IDS.incrementAndGet(), source, bank, beneficiary, new BigDecimal(amount));
    }

    static TransferAttempt withId(String id, String amount) {
        return new TransferAttempt(id, "0123456789", "000013", "2093456712", new BigDecimal(amount));
    }
}
