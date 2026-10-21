package com.treasuryone.duplicate;

import java.time.Instant;

/** Tells a customer, before they confirm a transfer, whether they just sent the same one. */
public interface DuplicateTransferDetector {

    /**
     * Reports whether {@code attempt} duplicates an earlier one that is still inside its window, then records it.
     *
     * @param now the time of the attempt. The detector never reads the system clock, so this is the only time it knows.
     */
    Verdict observe(TransferAttempt attempt, Instant now);
}
