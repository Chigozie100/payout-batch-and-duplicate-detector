package com.treasuryone.payout.partner;

/** The switch refused the call before processing it (over 50 requests/second or 10 in flight). */
public class PartnerThrottledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PartnerThrottledException(String message) {
        super(message);
    }
}
