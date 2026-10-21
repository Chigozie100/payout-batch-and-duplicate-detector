package com.treasuryone.payout.model;

import java.util.Optional;

/** Where one payout line is in its life. */
public enum LineStatus {
    PENDING, IN_FLIGHT, PAID, FAILED, UNCERTAIN, CANCELLED, REJECTED;

    public static Optional<LineStatus> parse(String text) {
        for (LineStatus status : values()) {
            if (status.name().equals(text)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
