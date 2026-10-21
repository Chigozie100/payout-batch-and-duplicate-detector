package com.treasuryone.payout.model;

/** Where one payout batch is in its life. */
public enum BatchStatus {
    ACCEPTED, PROCESSING, COMPLETED, COMPLETED_WITH_FAILURES, CANCELLED;

    /** An open batch can still change: it is waiting to be sent or being sent. */
    public boolean isOpen() {
        return this == ACCEPTED || this == PROCESSING;
    }

    /** Completed by sending every line (as opposed to being cancelled). */
    public boolean isCompleted() {
        return this == COMPLETED || this == COMPLETED_WITH_FAILURES;
    }
}
